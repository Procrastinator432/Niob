mod constants;
mod engine;
mod theme;
mod verification;

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex};

type PreviewKey = (String, AngleMode, bool, u64);
type AsyncPreviewMsg = (PreviewKey, Result<EvalOutput, String>);
type AsyncCommitMsg = (String, MathEngine, Result<EvalOutput, String>);

struct PreviewJob {
    key: PreviewKey,
    engine: MathEngine,
    gen: u64,
    ctx: egui::Context,
}

struct PreviewWorker {
    latest_gen: Arc<AtomicU64>,
    closed: Arc<AtomicBool>,
    shared: Arc<(Mutex<Option<PreviewJob>>, Condvar)>,
}

impl PreviewWorker {
    fn new(tx: mpsc::Sender<AsyncPreviewMsg>) -> Self {
        let latest_gen = Arc::new(AtomicU64::new(0));
        let closed = Arc::new(AtomicBool::new(false));
        let shared = Arc::new((Mutex::new(None::<PreviewJob>), Condvar::new()));
        let worker_gen = Arc::clone(&latest_gen);
        let worker_closed = Arc::clone(&closed);
        let worker_shared = Arc::clone(&shared);
        std::thread::spawn(move || {
            loop {
                let job = {
                    let (lock, cvar) = &*worker_shared;
                    let mut guard = match lock.lock() {
                        Ok(g) => g,
                        Err(_) => break,
                    };
                    while guard.is_none() && !worker_closed.load(Ordering::Relaxed) {
                        guard = match cvar.wait(guard) {
                            Ok(g) => g,
                            Err(_) => return,
                        };
                    }
                    if worker_closed.load(Ordering::Relaxed) {
                        break;
                    }
                    guard.take()
                };
                if let Some(mut j) = job {
                    let res = j.engine.evaluate_preview_cancellable(&j.key.0, j.gen, &worker_gen);
                    if worker_gen.load(Ordering::Relaxed) == j.gen {
                        let _ = tx.send((j.key, res));
                        j.ctx.request_repaint();
                    }
                }
            }
        });
        Self { latest_gen, closed, shared }
    }

    fn submit(&self, key: PreviewKey, engine: MathEngine, ctx: egui::Context) {
        let gen = self.latest_gen.fetch_add(1, Ordering::Relaxed) + 1;
        let (lock, cvar) = &*self.shared;
        if let Ok(mut guard) = lock.lock() {
            *guard = Some(PreviewJob { key, engine, gen, ctx });
            cvar.notify_one();
        }
    }
}

impl Drop for PreviewWorker {
    fn drop(&mut self) {
        self.closed.store(true, Ordering::Relaxed);
        self.latest_gen.fetch_add(1, Ordering::Relaxed);
        let (lock, cvar) = &*self.shared;
        if let Ok(_guard) = lock.lock() {
            cvar.notify_one();
        }
    }
}
use egui::{
    Align, Color32, CornerRadius, FontId, Frame, Layout, Pos2, RichText, ScrollArea, Sense,
    Stroke, StrokeKind, Ui, Vec2,
};

use crate::constants::{
     ASSIGNABLE_VARIABLE_SLOTS, ConstantCategory, DEFAULT_KEYBOARD_SLOTS, HISTORY_TERM_FONT_SIZE,
    INPUT_TERM_FONT_SIZE, INPUT_TERM_MIN_FONT_SIZE, KEYBOARD_CARD_CORNER_RADIUS,
    KEYBOARD_SECTION_BAR_HEIGHT, PLOTTER_AXIS_LABEL_SIZE, PLOTTER_MAJOR_GRID_STROKE,
    PLOTTER_MINOR_GRID_STROKE, SCIENTIFIC_CONSTANTS, SYMBOLIC_WATCHDOG_TIMEOUT_MS, USER_VARIABLES,
};
use crate::engine::{AngleMode, Complex, EvalOutput, MathEngine, NumberFormatMode, Value};
use crate::theme::{AccentSeed, MaterialYouPalette, ThemeMode};

mod state_json {
    use super::{AngleMode, Complex, HistoryItem, MathEngine, NumberFormatMode, Value, USER_VARIABLES};
    use std::collections::HashMap;
    use std::path::PathBuf;

    pub(super) fn state_file_path() -> Option<PathBuf> {
        #[cfg(target_os = "windows")]
        let base = std::env::var_os("LOCALAPPDATA")
            .or_else(|| std::env::var_os("APPDATA"))
            .map(PathBuf::from);
        #[cfg(target_os = "macos")]
        let base = std::env::var_os("HOME")
            .map(|h| PathBuf::from(h).join("Library/Application Support"));
        #[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
        let base = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")));
        base.map(|dir| dir.join("niob_calculator").join("state.json"))
    }

    fn json_escape(s: &str, out: &mut String) {
        out.push('"');
        for ch in s.chars() {
            match ch {
                '"' => out.push_str("\\\""),
                '\\' => out.push_str("\\\\"),
                '\n' => out.push_str("\\n"),
                '\r' => out.push_str("\\r"),
                '\t' => out.push_str("\\t"),
                c if (c as u32) < 0x20 => {
                    out.push_str(&format!("\\u{:04x}", c as u32));
                }
                c => out.push(c),
            }
        }
        out.push('"');
    }

    fn push_value_json(v: Value, out: &mut String) {
        let re = if v.c.re.is_finite() { v.c.re } else { 0.0 };
        let im = if v.c.im.is_finite() { v.c.im } else { 0.0 };
        out.push_str("{\"re\":");
        out.push_str(&format!("{re:?}"));
        out.push_str(",\"im\":");
        out.push_str(&format!("{im:?}"));
        out.push_str(",\"rat\":");
        if let Some(r) = v.rat {
            json_escape(&format!("{}/{}", r.num, r.den), out);
        } else {
            out.push_str("null");
        }
        out.push('}');
    }

    fn push_matrix_json(mat: &[Vec<Value>], out: &mut String) {
        out.push('[');
        for (r_idx, row) in mat.iter().enumerate() {
            if r_idx > 0 {
                out.push(',');
            }
            out.push('[');
            for (c_idx, cell) in row.iter().enumerate() {
                if c_idx > 0 {
                    out.push(',');
                }
                push_value_json(*cell, out);
            }
            out.push(']');
        }
        out.push(']');
    }

    fn decode_matrix(jv: &JsonVal) -> Option<Vec<Vec<Value>>> {
        let JsonVal::Arr(rows) = jv else { return None };
        if rows.is_empty() {
            return None;
        }
        let mut mat = Vec::with_capacity(rows.len());
        let mut expected_cols = None;
        for row_val in rows {
            let JsonVal::Arr(cols) = row_val else { return None };
            if cols.is_empty() {
                return None;
            }
            match expected_cols {
                None => expected_cols = Some(cols.len()),
                Some(n) if n != cols.len() => return None,
                _ => {}
            }
            let mut row = Vec::with_capacity(cols.len());
            for cell_val in cols {
                row.push(decode_value(cell_val)?);
            }
            mat.push(row);
        }
        Some(mat)
    }

    fn write_state_json_atomic(path: &std::path::Path, out: &str) {
        let tmp_path = path.with_extension("json.tmp");
        if std::fs::write(&tmp_path, out).is_ok() {
            if std::fs::rename(&tmp_path, path).is_err() {
                let _ = std::fs::remove_file(&tmp_path);
                let _ = std::fs::write(path, out);
            }
        } else {
            let _ = std::fs::write(path, out);
        }
    }

    pub(super) fn save_state_to_disk(engine: &MathEngine, history: &[HistoryItem]) {
        let Some(path) = state_file_path() else {
            return;
        };
        if let Some(parent) = path.parent() {
            if std::fs::create_dir_all(parent).is_err() {
                return;
            }
        }
        let out = serialize_state_json(engine, history);
        write_state_json_atomic(&path, &out);
    }

    fn serialize_state_json(engine: &MathEngine, history: &[HistoryItem]) -> String {
        let mut out = String::from("{\n  \"angle_mode\": ");
        json_escape(
            match engine.angle_mode {
                AngleMode::Radians => "radians",
                AngleMode::Degrees => "degrees",
            },
            &mut out,
        );
        out.push_str(",\n  \"number_format\": ");
        json_escape(
            match engine.number_format {
                NumberFormatMode::SpacePoint => "space_point",
                NumberFormatMode::CommaPoint => "comma_point",
                NumberFormatMode::NonePoint => "none_point",
                NumberFormatMode::SpaceComma => "space_comma",
                NumberFormatMode::PointComma => "point_comma",
                NumberFormatMode::NoneComma => "none_comma",
            },
            &mut out,
        );
        out.push_str(",\n  \"modulo_always_positive\": ");
        out.push_str(if engine.modulo_always_positive { "true" } else { "false" });
        out.push_str(",\n  \"ans\": ");
        push_value_json(engine.ans, &mut out);
        out.push_str(",\n  \"variables\": {");
        let mut first_var = true;
        for &name in &USER_VARIABLES {
            if let Some(&val) = engine.variables.get(name) {
                if !first_var {
                    out.push(',');
                }
                first_var = false;
                out.push_str("\n    ");
                json_escape(name, &mut out);
                out.push_str(": ");
                push_value_json(val, &mut out);
            }
        }
        out.push_str("\n  },\n  \"history\": [");
        for (idx, item) in history.iter().enumerate() {
            if idx > 0 {
                out.push(',');
            }
            out.push_str("\n    {\"raw_input\":");
            json_escape(&item.raw_input, &mut out);
            out.push_str(",\"exact_result\":");
            json_escape(&item.exact_result, &mut out);
            out.push_str(",\"decimal_result\":");
            json_escape(&item.decimal_result, &mut out);
            out.push_str(",\"reusable_result\":");
            json_escape(&item.reusable_result, &mut out);
            out.push_str(",\"has_distinct_exact\":");
            out.push_str(if item.has_distinct_exact { "true" } else { "false" });
            out.push_str(",\"showing_exact\":");
            out.push_str(if item.showing_exact { "true" } else { "false" });
            out.push_str(",\"is_error\":");
            out.push_str(if item.is_error { "true" } else { "false" });
            out.push('}');
        }
        out.push_str("\n  ]");
        out.push_str(",\n  \"ans_matrix\": ");
        match &engine.matrix_ans {
            Some(mat) => push_matrix_json(mat, &mut out),
            None => out.push_str("null"),
        }
        out.push_str(",\n  \"matrix_variables\": {");
        let mut first_mat = true;
        for &name in &USER_VARIABLES {
            if let Some(mat) = engine.matrix_variables.get(name) {
                if !first_mat {
                    out.push(',');
                }
                first_mat = false;
                out.push_str("\n    ");
                json_escape(name, &mut out);
                out.push_str(": ");
                push_matrix_json(mat, &mut out);
            }
        }
        out.push_str("\n  }\n}\n");
        out
    }

    enum JsonVal {
        Null,
        Bool(bool),
        Num(f64),
        Str(String),
        Arr(Vec<JsonVal>),
        Obj(Vec<(String, JsonVal)>),
    }

    impl JsonVal {
        fn get(&self, key: &str) -> Option<&JsonVal> {
            let Self::Obj(entries) = self else { return None };
            entries.iter().find(|(k, _)| k == key).map(|(_, v)| v)
        }
        fn as_str(&self) -> Option<&str> {
            match self { Self::Str(s) => Some(s.as_str()), _ => None }
        }
        fn as_bool(&self) -> Option<bool> {
            match self { Self::Bool(b) => Some(*b), _ => None }
        }
        fn as_f64(&self) -> Option<f64> {
            match self { Self::Num(n) if n.is_finite() => Some(*n), _ => None }
        }
    }

    struct Parser<'a> {
        bytes: &'a [u8],
        pos: usize,
    }

    impl<'a> Parser<'a> {
        fn skip_ws(&mut self) {
            while self.pos < self.bytes.len() && self.bytes[self.pos].is_ascii_whitespace() {
                self.pos += 1;
            }
        }
        fn parse_value(&mut self, depth: usize) -> Option<JsonVal> {
            if depth > 64 {
                return None;
            }
            self.skip_ws();
            let b = *self.bytes.get(self.pos)?;
            match b {
                b'{' => self.parse_obj(depth + 1),
                b'[' => self.parse_arr(depth + 1),
                b'"' => Some(JsonVal::Str(self.parse_str()?)),
                b't' if self.bytes[self.pos..].starts_with(b"true") => {
                    self.pos += 4;
                    Some(JsonVal::Bool(true))
                }
                b'f' if self.bytes[self.pos..].starts_with(b"false") => {
                    self.pos += 5;
                    Some(JsonVal::Bool(false))
                }
                b'n' if self.bytes[self.pos..].starts_with(b"null") => {
                    self.pos += 4;
                    Some(JsonVal::Null)
                }
                b'-' | b'0'..=b'9' => self.parse_num(),
                _ => None,
            }
        }
        fn parse_str(&mut self) -> Option<String> {
            if *self.bytes.get(self.pos)? != b'"' {
                return None;
            }
            self.pos += 1;
            let mut s = String::new();
            while self.pos < self.bytes.len() {
                let b = self.bytes[self.pos];
                if b == b'"' {
                    self.pos += 1;
                    return Some(s);
                }
                if b == b'\\' {
                    self.pos += 1;
                    let esc = *self.bytes.get(self.pos)?;
                    self.pos += 1;
                    match esc {
                        b'"' => s.push('"'),
                        b'\\' => s.push('\\'),
                        b'/' => s.push('/'),
                        b'b' => s.push('\u{0008}'),
                        b'f' => s.push('\u{000C}'),
                        b'n' => s.push('\n'),
                        b'r' => s.push('\r'),
                        b't' => s.push('\t'),
                        b'u' => {
                            let hex = std::str::from_utf8(self.bytes.get(self.pos..self.pos + 4)?).ok()?;
                            self.pos += 4;
                            let code = u32::from_str_radix(hex, 16).ok()?;
                            s.push(char::from_u32(code)?);
                        }
                        _ => return None,
                    }
                } else if b < 0x80 {
                    s.push(b as char);
                    self.pos += 1;
                } else {
                    let tail = std::str::from_utf8(&self.bytes[self.pos..]).ok()?;
                    let ch = tail.chars().next()?;
                    s.push(ch);
                    self.pos += ch.len_utf8();
                }
            }
            None
        }
        fn parse_num(&mut self) -> Option<JsonVal> {
            let start = self.pos;
            while self.pos < self.bytes.len() {
                match self.bytes[self.pos] {
                    b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9' => self.pos += 1,
                    _ => break,
                }
            }
            let txt = std::str::from_utf8(&self.bytes[start..self.pos]).ok()?;
            let n = txt.parse::<f64>().ok()?;
            Some(JsonVal::Num(n))
        }
        fn parse_arr(&mut self, depth: usize) -> Option<JsonVal> {
            self.pos += 1;
            let mut items = Vec::new();
            self.skip_ws();
            if self.bytes.get(self.pos) == Some(&b']') {
                self.pos += 1;
                return Some(JsonVal::Arr(items));
            }
            loop {
                items.push(self.parse_value(depth)?);
                self.skip_ws();
                match *self.bytes.get(self.pos)? {
                    b',' => self.pos += 1,
                    b']' => {
                        self.pos += 1;
                        return Some(JsonVal::Arr(items));
                    }
                    _ => return None,
                }
            }
        }
        fn parse_obj(&mut self, depth: usize) -> Option<JsonVal> {
            self.pos += 1;
            let mut entries = Vec::new();
            self.skip_ws();
            if self.bytes.get(self.pos) == Some(&b'}') {
                self.pos += 1;
                return Some(JsonVal::Obj(entries));
            }
            loop {
                self.skip_ws();
                let key = self.parse_str()?;
                self.skip_ws();
                if *self.bytes.get(self.pos)? != b':' {
                    return None;
                }
                self.pos += 1;
                let val = self.parse_value(depth)?;
                entries.push((key, val));
                self.skip_ws();
                match *self.bytes.get(self.pos)? {
                    b',' => self.pos += 1,
                    b'}' => {
                        self.pos += 1;
                        return Some(JsonVal::Obj(entries));
                    }
                    _ => return None,
                }
            }
        }
    }

    fn decode_value(jv: &JsonVal) -> Option<Value> {
        if let Some(rat_str) = jv.get("rat").and_then(JsonVal::as_str) {
            if let Some((n_s, d_s)) = rat_str.split_once('/') {
                if let (Ok(n), Ok(d)) = (n_s.parse::<i128>(), d_s.parse::<i128>()) {
                    if let Some(v) = Value::from_rat(n, d) {
                        return Some(v);
                    }
                }
            }
        }
        let re = jv.get("re").and_then(JsonVal::as_f64)?;
        let im = jv.get("im").and_then(JsonVal::as_f64).unwrap_or(0.0);
        Some(Value::from_complex(Complex::new(re, im)))
    }

    pub(super) fn load_state_from_disk(engine: &mut MathEngine, history: &mut Vec<HistoryItem>) {
        let Some(path) = state_file_path() else {
            return;
        };
        let Ok(raw) = std::fs::read_to_string(path) else {
            return;
        };
        apply_state_json(&raw, engine, history);
    }

    fn apply_state_json(raw: &str, engine: &mut MathEngine, history: &mut Vec<HistoryItem>) {
        let mut parser = Parser { bytes: raw.as_bytes(), pos: 0 };
        let Some(root) = parser.parse_value(0) else {
            return;
        };
        if let Some(am) = root.get("angle_mode").and_then(JsonVal::as_str) {
            engine.angle_mode = if am == "degrees" { AngleMode::Degrees } else { AngleMode::Radians };
        }
        if let Some(nf) = root.get("number_format").and_then(JsonVal::as_str) {
            engine.number_format = match nf {
                "comma_point" => NumberFormatMode::CommaPoint,
                "none_point" => NumberFormatMode::NonePoint,
                "space_comma" => NumberFormatMode::SpaceComma,
                "point_comma" => NumberFormatMode::PointComma,
                "none_comma" => NumberFormatMode::NoneComma,
                _ => NumberFormatMode::SpacePoint,
            };
        }
        if let Some(mp) = root.get("modulo_always_positive").and_then(JsonVal::as_bool) {
            engine.modulo_always_positive = mp;
        }
        if let Some(ans_val) = root.get("ans").and_then(decode_value) {
            engine.ans = ans_val;
        }
        if let Some(JsonVal::Obj(vars)) = root.get("variables") {
            let mut loaded_vars = HashMap::new();
            for (k, v) in vars {
                if USER_VARIABLES.contains(&k.as_str()) {
                    if let Some(decoded) = decode_value(v) {
                        loaded_vars.insert(k.clone(), decoded);
                    }
                }
            }
            engine.variables = loaded_vars;
        }
        engine.matrix_ans = root.get("ans_matrix").and_then(decode_matrix);
        if let Some(JsonVal::Obj(mvars)) = root.get("matrix_variables") {
            let mut loaded_mats = std::collections::HashMap::new();
            for (k, v) in mvars {
                if USER_VARIABLES.contains(&k.as_str()) {
                    if let Some(mat) = decode_matrix(v) {
                        engine.variables.remove(k.as_str());
                        loaded_mats.insert(k.clone(), mat);
                    }
                }
            }
            engine.matrix_variables = loaded_mats;
        }
        if let Some(JsonVal::Arr(items)) = root.get("history") {
            history.clear();
            for entry in items {
                let Some(raw_input) = entry.get("raw_input").and_then(JsonVal::as_str) else { continue };
                let exact_result = entry.get("exact_result").and_then(JsonVal::as_str).unwrap_or("").to_string();
                let decimal_result = entry.get("decimal_result").and_then(JsonVal::as_str).unwrap_or("").to_string();
                let reusable_result = entry.get("reusable_result").and_then(JsonVal::as_str).unwrap_or("").to_string();
                let has_distinct_exact = entry.get("has_distinct_exact").and_then(JsonVal::as_bool).unwrap_or(false);
                let showing_exact = entry.get("showing_exact").and_then(JsonVal::as_bool).unwrap_or(true);
                let is_error = entry.get("is_error").and_then(JsonVal::as_bool).unwrap_or(false);
                history.push(HistoryItem {
                    raw_input: raw_input.to_string(),
                    exact_result,
                    decimal_result,
                    reusable_result,
                    has_distinct_exact,
                    showing_exact,
                    is_error,
                    integration_report: None,
                });
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn atomic_state_json_persists_matrices_and_removes_tmp() {
            let mut engine = MathEngine::default();
            engine.evaluate_and_commit("m := [[1, 2], [3, 4]]").unwrap();
            engine.evaluate_and_commit("inv([[1, 2], [3, 4]])").unwrap();
            let history = vec![HistoryItem {
                raw_input: "inv([[1, 2], [3, 4]])".to_string(),
                exact_result: "[[-2, 1], [3/2, -1/2]]".to_string(),
                decimal_result: "[[-2, 1], [1.5, -0.5]]".to_string(),
                reusable_result: "[[-2, 1], [3/2, -1/2]]".to_string(),
                has_distinct_exact: true,
                showing_exact: true,
                is_error: false,
                integration_report: None,
            }];

            let json = serialize_state_json(&engine, &history);
            let dir = std::env::temp_dir().join(format!("niob_state_test_{}", std::process::id()));
            std::fs::create_dir_all(&dir).unwrap();
            let state_path = dir.join("state.json");
            let tmp_path = state_path.with_extension("json.tmp");

            write_state_json_atomic(&state_path, &json);
            assert!(state_path.exists());
            assert!(!tmp_path.exists(), "temporary file must not remain after rename");

            let raw = std::fs::read_to_string(&state_path).unwrap();
            let mut restored = MathEngine::default();
            let mut restored_history = Vec::new();
            apply_state_json(&raw, &mut restored, &mut restored_history);

            let det_ans = restored.evaluate_preview("det(ans)", false).unwrap();
            assert!((det_ans.numeric_value.unwrap().re - (-0.5)).abs() < 1e-12);
            let det_m = restored.evaluate_preview("det(m)", false).unwrap();
            assert!((det_m.numeric_value.unwrap().re - (-2.0)).abs() < 1e-12);
            assert!(restored.variables.get("m").is_none());
            assert_eq!(restored_history.len(), 1);

            let _ = std::fs::remove_dir_all(&dir);
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum MainTab {
    Calculator,
    Plotter,
    VariablesAndConstants,
    ConverterAndSettings,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum KeyboardSection {
    Numbers,      // 1 2 3
    Operators,    // ( ) √ ^
    Trigonometry, // sin
    AdvancedMath, // Σ ∫ i
    Variables,    // a b c
    More,         // more
}

impl KeyboardSection {
    const ALL: [KeyboardSection; 6] = [
        Self::Numbers,
        Self::Operators,
        Self::Trigonometry,
        Self::AdvancedMath,
        Self::Variables,
        Self::More,
    ];

    fn label(self, de: bool) -> &'static str {
        match self {
            Self::Numbers => "1 2 3",
            Self::Operators => "( ) √ xⁿ",
            Self::Trigonometry => "sin cos",
            Self::AdvancedMath => "∑ ∫ i",
            Self::Variables => "a b c",
            Self::More => {
                if de {
                    "Mehr"
                } else {
                    "more"
                }
            }
        }
    }
}

#[derive(Clone, Debug)]
struct HistoryItem {
    raw_input: String,
    exact_result: String,
    decimal_result: String,
    reusable_result: String,
    has_distinct_exact: bool,
    showing_exact: bool,
    is_error: bool,
    integration_report: Option<crate::engine::IntegrationReport>,
}

#[derive(Clone, Debug)]
struct PlotFunction {
    expr: String,
    visible: bool,
    color: Color32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum UnitCategory {
    Length,
    Mass,
    Temperature,
    Speed,
    Energy,
    Angle,
    Data,
}

impl UnitCategory {
    const ALL: [UnitCategory; 7] = [
        Self::Length,
        Self::Mass,
        Self::Temperature,
        Self::Speed,
        Self::Energy,
        Self::Angle,
        Self::Data,
    ];

    fn name(self, de: bool) -> &'static str {
        match (self, de) {
            (Self::Length, true) => "Länge",
            (Self::Length, false) => "Length",
            (Self::Mass, true) => "Masse",
            (Self::Mass, false) => "Mass",
            (Self::Temperature, true) => "Temperatur",
            (Self::Temperature, false) => "Temperature",
            (Self::Speed, true) => "Geschwindigkeit",
            (Self::Speed, false) => "Speed",
            (Self::Energy, true) => "Energie",
            (Self::Energy, false) => "Energy",
            (Self::Angle, true) => "Winkel",
            (Self::Angle, false) => "Angle",
            (Self::Data, true) => "Datenmenge",
            (Self::Data, false) => "Data Size",
        }
    }

    fn units(self) -> &'static [(&'static str, f64)] {
        match self {
            Self::Length => &[
                ("Meter (m)", 1.0),
                ("Kilometer (km)", 1000.0),
                ("Zentimeter (cm)", 0.01),
                ("Millimeter (mm)", 0.001),
                ("Mikrometer (µm)", 1e-6),
                ("Nanometer (nm)", 1e-9),
                ("Meile (mi)", 1609.344),
                ("Fuß (ft)", 0.3048),
                ("Zoll (in)", 0.0254),
            ],
            Self::Mass => &[
                ("Kilogramm (kg)", 1.0),
                ("Gramm (g)", 0.001),
                ("Milligramm (mg)", 1e-6),
                ("Tonne (t)", 1000.0),
                ("Pfund (lb)", 0.45359237),
                ("Unze (oz)", 0.0283495231),
            ],
            Self::Temperature => &[
                ("Celsius (°C)", 1.0),
                ("Kelvin (K)", 1.0),
                ("Fahrenheit (°F)", 1.0),
            ],
            Self::Speed => &[
                ("Meter/Sekunde (m/s)", 1.0),
                ("Kilometer/Stunde (km/h)", 1.0 / 3.6),
                ("Meilen/Stunde (mph)", 0.44704),
                ("Knoten (kn)", 0.514444),
                ("Lichtgeschwindigkeit (c₀)", 299790000.0),
            ],
            Self::Energy => &[
                ("Joule (J)", 1.0),
                ("Kilojoule (kJ)", 1000.0),
                ("Wattstunde (Wh)", 3600.0),
                ("Kilowattstunde (kWh)", 3_600_000.0),
                ("Elektronenvolt (eV)", 1.6022e-19),
                ("Kalorie (cal)", 4.184),
            ],
            Self::Angle => &[
                ("Radiant (rad)", 1.0),
                ("Grad (°)", std::f64::consts::PI / 180.0),
                ("Gon (grad)", std::f64::consts::PI / 200.0),
            ],
            Self::Data => &[
                ("Byte (B)", 1.0),
                ("Kilobyte (KB)", 1000.0),
                ("Megabyte (MB)", 1e6),
                ("Gigabyte (GB)", 1e9),
                ("Kibibyte (KiB)", 1024.0),
                ("Mebibyte (MiB)", 1_048_576.0),
            ],
        }
    }
}

struct NiobCalculatorApp {
    // Core engine & state
    engine: MathEngine,
    input_expr: String,
    history: Vec<HistoryItem>,
    active_tab: MainTab,
    active_section: KeyboardSection,
    aux_section: KeyboardSection,
    dual_keyboard: bool,
    show_hex_row: bool,
    keyboard_scale: f32, // 0.9 = small, 1.0 = default, 1.12 = large
    german_ui: bool,

    // Material You Theme
    theme_mode: ThemeMode,
    accent_seed: AccentSeed,

    // Variable & Constant slots (`a b c` section)
    keyboard_slots: [String; ASSIGNABLE_VARIABLE_SLOTS],
    selected_slot_to_assign: usize,
    constants_search: String,
    var_edit_name: String,
    var_edit_value: String,

    // 2D Function Plotter state (`FunctionPlotting2DView`)
    plot_functions: Vec<PlotFunction>,
    plot_center_x: f64,
    plot_center_y: f64,
    plot_scale: f64, // pixels per unit
    plot_trace_x: f64,

    // Unit Converter state
    unit_cat: UnitCategory,
    unit_from_idx: usize,
    unit_to_idx: usize,
    unit_input_str: String,

    // Preview & async evaluation state
    eval_revision: u64,
    preview_cache_key: Option<PreviewKey>,
    preview_cache_result: Option<Result<EvalOutput, String>>,
    preview_pending_key: Option<PreviewKey>,
    preview_rx: Option<mpsc::Receiver<AsyncPreviewMsg>>,
    preview_worker: Option<PreviewWorker>,
    commit_pending: bool,
    commit_rx: Option<mpsc::Receiver<AsyncCommitMsg>>,

    // Screenshot automation
    screenshot_path: Option<PathBuf>,
    frame_count: u32,
    screenshot_requested: bool,
}

impl NiobCalculatorApp {
    fn new(screenshot_path: Option<PathBuf>) -> Self {
        let mut engine = MathEngine::default();
        let mut history = Vec::new();
        let mut input_expr = String::new();

        if screenshot_path.is_some() {
            // Populate initial history with representative scientific expressions
            let samples = [
                "1/3 + 1/6",
                "sin(pi / 3)",
                "int(x^2, x, 0, 3)",
                "sum(k^2, k, 1, 10)",
                "det([[4, 2], [1, 3]])",
            ];
            for s in samples {
                if let Ok(out) = engine.evaluate_and_commit(s) {
                    history.push(HistoryItem {
                        raw_input: s.to_string(),
                        reusable_result: out.reusable_expression(),
                        exact_result: out.exact_text,
                        decimal_result: out.decimal_text,
                        has_distinct_exact: out.has_distinct_exact,
                        showing_exact: engine.show_exact_first,
                        is_error: false,
                        integration_report: out.integration_report,
                    });
                }
            }
            input_expr = "sqrt(2)/2 + sin(pi/4)".to_string();
        } else {
            state_json::load_state_from_disk(&mut engine, &mut history);
        }

        let keyboard_slots = DEFAULT_KEYBOARD_SLOTS.map(|s| s.to_string());

        Self {
            engine,
            input_expr,
            history,
            active_tab: MainTab::Calculator,
            active_section: KeyboardSection::Operators,
            aux_section: KeyboardSection::Numbers,
            dual_keyboard: true,
            show_hex_row: false,
            keyboard_scale: 1.0,
            german_ui: true,
            theme_mode: ThemeMode::Dark,
            accent_seed: AccentSeed::NiobEmerald,
            keyboard_slots,
            selected_slot_to_assign: 0,
            constants_search: String::new(),
            var_edit_name: "a".to_string(),
            var_edit_value: "5".to_string(),
            plot_functions: vec![
                PlotFunction {
                    expr: "sin(x) * cos(x/2)".to_string(),
                    visible: true,
                    color: Color32::from_rgb(0x3D, 0xDC, 0x84),
                },
                PlotFunction {
                    expr: "x^2 / 4 - 2".to_string(),
                    visible: true,
                    color: Color32::from_rgb(0x64, 0xB5, 0xF6),
                },
                PlotFunction {
                    expr: "exp(-x^2 / 3) * 2".to_string(),
                    visible: true,
                    color: Color32::from_rgb(0xFF, 0xB7, 0x4D),
                },
            ],
            plot_center_x: 0.0,
            plot_center_y: 0.0,
            plot_scale: 48.0,
            plot_trace_x: 1.0,
            unit_cat: UnitCategory::Length,
            unit_from_idx: 0,
            unit_to_idx: 1,
            unit_input_str: "1250".to_string(),
            eval_revision: 0,
            preview_cache_key: None,
            preview_cache_result: None,
            preview_pending_key: None,
            preview_rx: None,
            preview_worker: None,
            commit_pending: false,
            commit_rx: None,
            screenshot_path,
            frame_count: 0,
            screenshot_requested: false,
        }
    }

    fn mark_state_dirty(&mut self) {
        self.eval_revision = self.eval_revision.wrapping_add(1);
        if self.screenshot_path.is_none() {
            state_json::save_state_to_disk(&self.engine, &self.history);
        }
    }

    fn insert_token(&mut self, token: &str) {
        if self.input_expr.is_empty()
            && self.engine.auto_ans_insertion
            && matches!(token, "+" | "-" | "*" | "/" | "^")
        {
            self.input_expr.push_str("ans");
        }
        self.input_expr.push_str(token);
    }

    fn backspace(&mut self) {
        if self.input_expr.is_empty() {
            return;
        }
        const MULTI_CHAR_TOKENS: &[&str] = &[
            "transpose(", "nthroot(", "binomial(", "fraction(", "division(",
            "addition(", "subtraction(", "multiplication(",
            "arcsinh(", "arccosh(", "arctanh(", "arcsin(", "arccos(", "arctan(",
            "asinh(", "acosh(", "atanh(", "deriv(", "floor(", "round(", "log10(",
            "trans(", "asin(", "acos(", "atan(", "sinh(", "cosh(", "tanh(", "sqrt(",
            "ceil(", "sign(", "prod(", "sin(", "cos(", "tan(", "log(", "exp(",
            "abs(", "sgn(", "gcd(", "lcm(", "mod(", "sum(", "int(", "nCr(", "det(",
            "inv(", "pow(", "ln(", "^(-1)", "10^(", "alpha_fs", "e_charge", "R_inf",
            "ans", "ANS", "c0", "mu0", "eps0", "k_B", "m_P", "t_P", "l_P", "Z0",
            "Phi0", "K_J", "R_K", "G0", "sigma", "E_h", "mu_B", "mu_e", "N_A",
            "m_u", "m_e", "m_p", "m_n", "pi", "^2", ":=", "0x", "[[", "]]",
        ];
        for tok in MULTI_CHAR_TOKENS {
            if self.input_expr.ends_with(tok) {
                let new_len = self.input_expr.len() - tok.len();
                self.input_expr.truncate(new_len);
                return;
            }
        }
        self.input_expr.pop();
    }

    fn finish_commit(&mut self, trimmed: String, res: Result<EvalOutput, String>, updated_engine: Option<MathEngine>) {
        match res {
            Ok(out) => {
                if let Some(up) = updated_engine {
                    let rep = up.get_last_integration_report();
                    self.engine.ans = up.ans;
                    self.engine.matrix_ans = up.matrix_ans;
                    self.engine.variables = up.variables;
                    self.engine.matrix_variables = up.matrix_variables;
                    self.engine.last_integration_report.replace(rep);
                }
                self.history.push(HistoryItem {
                    raw_input: trimmed,
                    reusable_result: out.reusable_expression(),
                    exact_result: out.exact_text,
                    decimal_result: out.decimal_text,
                    has_distinct_exact: out.has_distinct_exact,
                    showing_exact: self.engine.show_exact_first,
                    is_error: false,
                    integration_report: out.integration_report,
                });
                self.input_expr.clear();
            }
            Err(err_msg) => {
                self.history.push(HistoryItem {
                    raw_input: trimmed,
                    reusable_result: String::new(),
                    exact_result: err_msg.clone(),
                    decimal_result: err_msg,
                    has_distinct_exact: false,
                    showing_exact: true,
                    is_error: true,
                    integration_report: None,
                });
            }
        }
        self.mark_state_dirty();
    }

    fn poll_async_eval(&mut self) {
        if let Some(ref rx) = self.preview_rx {
            let mut disconnected = false;
            loop {
                match rx.try_recv() {
                    Ok((key, res)) => {
                        if self.preview_pending_key.as_ref() == Some(&key) {
                            self.preview_pending_key = None;
                        }
                        self.preview_cache_key = Some(key);
                        self.preview_cache_result = Some(res);
                    }
                    Err(mpsc::TryRecvError::Disconnected) => {
                        disconnected = true;
                        break;
                    }
                    Err(mpsc::TryRecvError::Empty) => break,
                }
            }
            if disconnected {
                self.preview_pending_key = None;
                self.preview_rx = None;
                self.preview_worker = None;
            }
        }
        if let Some(ref rx) = self.commit_rx {
            match rx.try_recv() {
                Ok((trimmed, updated_engine, res)) => {
                    self.commit_pending = false;
                    self.commit_rx = None;
                    self.finish_commit(trimmed, res, Some(updated_engine));
                }
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.commit_pending = false;
                    self.commit_rx = None;
                }
                Err(mpsc::TryRecvError::Empty) => {}
            }
        }
    }

    fn evaluate_current(&mut self, ctx: Option<&egui::Context>) {
        if self.commit_pending {
            return;
        }
        let trimmed = self.input_expr.trim().to_string();
        if trimmed.is_empty() {
            return;
        }
        if self.screenshot_path.is_some() {
            let res = self.engine.evaluate_and_commit(&trimmed);
            self.finish_commit(trimmed, res, None);
            return;
        }
        let mut bg_engine = self.engine.clone();
        let (tx, rx) = mpsc::channel();
        let ctx_opt = ctx.cloned();
        self.commit_pending = true;
        self.commit_rx = Some(rx);
        std::thread::spawn(move || {
            let res = bg_engine.evaluate_and_commit(&trimmed);
            let _ = tx.send((trimmed, bg_engine, res));
            if let Some(c) = ctx_opt {
                c.request_repaint();
            }
        });
    }

    fn handle_keyboard_input(&mut self, ctx: &egui::Context) {
        if self.active_tab != MainTab::Calculator {
            return;
        }
        // Only handle global hotkeys when no text edit widget has focus
        if ctx.memory(|m| m.focused().is_some()) {
            return;
        }
        let mut enter_pressed = false;
        ctx.input(|i| {
            if i.key_pressed(egui::Key::Enter) {
                enter_pressed = true;
            } else if i.key_pressed(egui::Key::Backspace) {
                self.backspace();
            } else if i.key_pressed(egui::Key::Escape) {
                self.input_expr.clear();
            }
            for event in &i.events {
                if let egui::Event::Text(text) = event {
                    for ch in text.chars() {
                        if !ch.is_control() && ch != '\r' && ch != '\n' {
                            self.input_expr.push(ch);
                        }
                    }
                }
            }
        });
        if enter_pressed {
            self.evaluate_current(Some(ctx));
        }
    }

    fn handle_screenshot(&mut self, ctx: &egui::Context) {
        let Some(path) = self.screenshot_path.clone() else {
            return;
        };

        self.frame_count += 1;
        ctx.request_repaint();

        if self.frame_count >= 120 && (!self.screenshot_requested || self.frame_count % 15 == 0) {
            self.screenshot_requested = true;
            ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(egui::UserData::default()));
        }

        let mut captured_image = None;
        ctx.input(|i| {
            for event in &i.raw.events {
                if let egui::Event::Screenshot { image, .. } = event {
                    captured_image = Some(image.clone());
                }
            }
        });

        if let Some(img) = captured_image {
            if let Some(parent) = path.parent() {
                if !parent.as_os_str().is_empty() {
                    let _ = std::fs::create_dir_all(parent);
                }
            }
            let width = img.width() as u32;
            let height = img.height() as u32;
            let rgba: Vec<u8> = img
                .pixels
                .iter()
                .flat_map(|c| [c.r(), c.g(), c.b(), c.a()])
                .collect();
            let _ = image::save_buffer(&path, &rgba, width, height, image::ColorType::Rgba8);
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
            std::process::exit(0);
        }
    }

    fn render_top_bar(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        ui.horizontal_wrapped(|ui| {
            ui.label(
                RichText::new(if self.german_ui {
                    "Niob Rechner"
                } else {
                    "Niob Calculator"
                })
                .font(FontId::proportional(17.5))
                .strong()
                .color(pal.on_surface),
            );

            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                // Language toggle DE / EN
                let lang_lbl = if self.german_ui { "DE" } else { "EN" };
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(lang_lbl)
                                .font(FontId::proportional(12.0))
                                .strong(),
                        )
                        .corner_radius(CornerRadius::same(10)),
                    )
                    .on_hover_text("Sprache wechseln / Toggle Language")
                    .clicked()
                {
                    self.german_ui = !self.german_ui;
                }

                // Theme Mode cycle button (Dark / Light / AMOLED)
                let mode_icon = match self.theme_mode {
                    ThemeMode::Dark => "🌙 Dark",
                    ThemeMode::Light => "☀ Light",
                    ThemeMode::Amoled => "⬛ AMOLED",
                };
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(mode_icon).font(FontId::proportional(12.0)),
                        )
                        .corner_radius(CornerRadius::same(10)),
                    )
                    .clicked()
                {
                    self.theme_mode = match self.theme_mode {
                        ThemeMode::Dark => ThemeMode::Light,
                        ThemeMode::Light => ThemeMode::Amoled,
                        ThemeMode::Amoled => ThemeMode::Dark,
                    };
                }

                // Material You Seed Color quick dots
                for seed in [
                    AccentSeed::NiobEmerald,
                    AccentSeed::DynamicViolet,
                    AccentSeed::OceanBlue,
                    AccentSeed::SunsetOrange,
                    AccentSeed::RosePink,
                ] {
                    let c = seed.seed_color();
                    let selected = self.accent_seed == seed;
                    let size = if selected { 18.0 } else { 14.0 };
                    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), Sense::click());
                    ui.painter().circle_filled(rect.center(), size * 0.45, c);
                    if selected {
                        ui.painter().circle_stroke(
                            rect.center(),
                            size * 0.48,
                            Stroke::new(2.0_f32, pal.on_surface),
                        );
                    }
                    if resp.on_hover_text(seed.name_de()).clicked() {
                        self.accent_seed = seed;
                    }
                }

                // Dual Keyboard toggle pill
                let dual_txt = if self.dual_keyboard {
                    if self.german_ui {
                        "⌨ Dual-Tastatur"
                    } else {
                        "⌨ Dual Pad"
                    }
                } else if self.german_ui {
                    "⌨ Einzel-Tastatur"
                } else {
                    "⌨ Single Pad"
                };
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(dual_txt)
                                .font(FontId::proportional(12.0))
                                .color(if self.dual_keyboard {
                                    pal.on_primary_container
                                } else {
                                    pal.on_surface_variant
                                }),
                        )
                        .fill(if self.dual_keyboard {
                            pal.primary_container
                        } else {
                            pal.surface_container_high
                        })
                        .corner_radius(CornerRadius::same(10)),
                    )
                    .clicked()
                {
                    self.dual_keyboard = !self.dual_keyboard;
                }

                // Angle mode quick pill (RAD / DEG)
                let angle_str = match self.engine.angle_mode {
                    AngleMode::Radians => "RAD",
                    AngleMode::Degrees => "DEG",
                };
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(angle_str)
                                .font(FontId::proportional(12.0))
                                .strong()
                                .color(pal.on_tertiary_container),
                        )
                        .fill(pal.tertiary_container)
                        .corner_radius(CornerRadius::same(10)),
                    )
                    .on_hover_text("Bogenmaß (RAD) / Gradmaß (DEG) umschalten")
                    .clicked()
                {
                    self.engine.angle_mode = match self.engine.angle_mode {
                        AngleMode::Radians => AngleMode::Degrees,
                        AngleMode::Degrees => AngleMode::Radians,
                    };
                    self.mark_state_dirty();
                }
            });
        });
    }

    fn render_bottom_nav(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let tabs = [
            (
                MainTab::Calculator,
                if self.german_ui {
                    "Rechner"
                } else {
                    "Calculator"
                },
            ),
            (
                MainTab::Plotter,
                if self.german_ui {
                    "2D-Plotter"
                } else {
                    "Graphing"
                },
            ),
            (
                MainTab::VariablesAndConstants,
                if self.german_ui {
                    "Konstanten & Var."
                } else {
                    "Constants & Vars"
                },
            ),
            (
                MainTab::ConverterAndSettings,
                if self.german_ui {
                    "Einstellungen & Setup"
                } else {
                    "Settings & Setup"
                },
            ),
        ];

        ui.columns(tabs.len(), |cols| {
            for (i, (tab, title)) in tabs.iter().enumerate() {
                let is_active = self.active_tab == *tab;
                let fill = if is_active {
                    pal.primary_container
                } else {
                    Color32::TRANSPARENT
                };
                let text_col = if is_active {
                    pal.on_primary_container
                } else {
                    pal.on_surface_variant
                };
                cols[i].vertical_centered(|ui| {
                    let btn = egui::Button::new(
                        RichText::new(*title)
                            .font(FontId::proportional(13.5))
                            .strong()
                            .color(text_col),
                    )
                    .fill(fill)
                    .stroke(if is_active {
                        Stroke::new(1.5_f32, pal.primary)
                    } else {
                        Stroke::NONE
                    })
                    .corner_radius(CornerRadius::same(18))
                    .min_size(egui::vec2(ui.available_width() * 0.94, 34.0));

                    if ui.add(btn).clicked() {
                        self.active_tab = *tab;
                    }
                });
            }
        });

        // Android 12 System Gesture Navigation Pill
        ui.add_space(4.0);
        ui.vertical_centered(|ui| {
            let (pill_rect, _) = ui.allocate_exact_size(egui::vec2(130.0, 5.0), Sense::hover());
            ui.painter().rect_filled(
                pill_rect,
                CornerRadius::same(3),
                pal.on_surface_variant,
            );
        });
    }

    fn render_calculator_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let total_h = ui.available_height();
        let kb_height = (total_h * 0.47 * self.keyboard_scale).clamp(225.0, 340.0);
        let input_bar_height = 84.0;
        let history_height = (total_h - kb_height - input_bar_height - 16.0).max(95.0);

        // 1. Top Area: TermHistoryRecyclerView (`@id/term_history_list_view`)
        Frame::new()
            .fill(pal.surface_container_low)
            .corner_radius(CornerRadius::same(16))
            .inner_margin(egui::Margin::same(10))
            .stroke(Stroke::new(1.0_f32, pal.outline_variant))
            .show(ui, |ui| {
                ui.set_height(history_height);
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(if self.german_ui {
                            "Rechenverlauf (Klick auf exact / decim. wechselt Darstellung)"
                        } else {
                            "Calculation History (Click exact / decim. to toggle format)"
                        })
                        .font(FontId::proportional(11.5))
                        .color(pal.on_surface_variant),
                    );
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if !self.history.is_empty()
                            && ui
                                .small_button(if self.german_ui {
                                    "Verlauf leeren"
                                } else {
                                    "Clear History"
                                })
                                .clicked()
                        {
                            self.history.clear();
                            self.mark_state_dirty();
                        }
                    });
                });
                ui.add_space(4.0);

                ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                        let mut paste_text: Option<String> = None;
                        let mut plot_expr: Option<String> = None;
                        let mut delete_idx: Option<usize> = None;

                        for (idx, item) in self.history.iter_mut().enumerate() {
                            Frame::new()
                                .fill(pal.surface_container)
                                .corner_radius(CornerRadius::same(12))
                                .inner_margin(egui::Margin::symmetric(12, 6))
                                .show(ui, |ui| {
                                    // Row 1 (`@id/input_term_view` from `term_input_result_combination_layout.xml`):
                                    // Left-aligned input expression + right-aligned plot/delete actions
                                    ui.horizontal(|ui| {
                                        let pretty_in =
                                            MathEngine::prettify_expression(&item.raw_input);
                                        if ui
                                            .add(
                                                egui::Button::new(
                                                    RichText::new(&pretty_in)
                                                        .font(FontId::proportional(
                                                            HISTORY_TERM_FONT_SIZE - 2.0,
                                                        ))
                                                        .color(pal.on_surface),
                                                )
                                                .frame(false),
                                            )
                                            .on_hover_text(if self.german_ui {
                                                "Klicken, um Ausdruck in die Eingabe zu übernehmen"
                                            } else {
                                                "Click to paste expression to input"
                                            })
                                            .clicked()
                                        {
                                            paste_text = Some(item.raw_input.clone());
                                        }

                                        ui.with_layout(
                                            Layout::right_to_left(Align::Center),
                                            |ui| {
                                                if ui
                                                    .add(
                                                        egui::Button::new(
                                                            RichText::new("×")
                                                                .font(FontId::proportional(14.0))
                                                                .color(pal.on_surface_variant),
                                                        )
                                                        .frame(false),
                                                    )
                                                    .on_hover_text(if self.german_ui {
                                                        "Eintrag löschen"
                                                    } else {
                                                        "Delete item"
                                                    })
                                                    .clicked()
                                                {
                                                    delete_idx = Some(idx);
                                                }

                                                if item.raw_input.contains('x')
                                                    && !item.raw_input.contains("int(")
                                                {
                                                    if ui
                                                        .small_button("Plot")
                                                        .on_hover_text(if self.german_ui {
                                                            "Im 2D-Funktionsplotter zeichnen"
                                                        } else {
                                                            "Plot in 2D Graph"
                                                        })
                                                        .clicked()
                                                    {
                                                        plot_expr = Some(item.raw_input.clone());
                                                    }
                                                }
                                            },
                                        );
                                    });

                                    // Row 2 (`@id/exact_decimal_switch_button` + `@id/equal_sign_text_view` on left,
                                    // `@id/result_term_view_exact` / `@id/result_term_view_decimal` right-aligned)
                                    ui.horizontal(|ui| {
                                        if !item.is_error {
                                            let badge_txt = if self.german_ui {
                                                if item.showing_exact {
                                                    "> dezi."
                                                } else {
                                                    "> exakt"
                                                }
                                            } else if item.showing_exact {
                                                "↔ exact"
                                            } else {
                                                "↔ decim."
                                            };
                                            let pill_fill = if item.showing_exact {
                                                pal.primary_container
                                            } else if item.has_distinct_exact {
                                                pal.secondary_container
                                            } else {
                                                pal.surface_container_highest
                                            };
                                            let pill_fg = if item.showing_exact {
                                                pal.on_primary_container
                                            } else {
                                                pal.on_secondary_container
                                            };
                                            let btn = egui::Button::new(
                                                RichText::new(badge_txt)
                                                    .font(FontId::proportional(11.5))
                                                    .strong()
                                                    .color(pill_fg),
                                            )
                                            .fill(pill_fill)
                                            .corner_radius(CornerRadius::same(10));
                                            let tooltip = if self.german_ui {
                                                format!(
                                                    "Exakt: {}  |  Dezi.: {}",
                                                    item.exact_result, item.decimal_result
                                                )
                                            } else {
                                                format!(
                                                    "Exact: {}  |  Decimal: {}",
                                                    item.exact_result, item.decimal_result
                                                )
                                            };
                                            if ui.add(btn).on_hover_text(tooltip).clicked() {
                                                item.showing_exact = !item.showing_exact;
                                            }
                                        }

                                        ui.label(
                                            RichText::new("=")
                                                .font(FontId::proportional(HISTORY_TERM_FONT_SIZE))
                                                .strong()
                                                .color(pal.on_surface_variant),
                                        );

                                        ui.with_layout(
                                            Layout::right_to_left(Align::Center),
                                            |ui| {
                                                let shown_res = if item.showing_exact {
                                                    &item.exact_result
                                                } else {
                                                    &item.decimal_result
                                                };
                                                let res_col = if item.is_error {
                                                    pal.error
                                                } else {
                                                    pal.primary
                                                };
                                                if ui
                                                    .add(
                                                        egui::Button::new(
                                                            RichText::new(shown_res)
                                                                .font(FontId::proportional(
                                                                    HISTORY_TERM_FONT_SIZE,
                                                                ))
                                                                .strong()
                                                                .color(res_col),
                                                        )
                                                        .frame(false),
                                                    )
                                                    .on_hover_text(if self.german_ui {
                                                        "Klicken, um Ergebnis einzufügen"
                                                    } else {
                                                        "Click to insert result"
                                                    })
                                                    .clicked()
                                                {
                                                    if !item.is_error {
                                                        paste_text = Some(item.reusable_result.clone());
                                                    }
                                                }
                                            },
                                        );
                                    });

                                    if let Some(ref rep) = item.integration_report {
                                        ui.horizontal(|ui| {
                                            let (info, color) = if rep.converged {
                                                (
                                                    format!(
                                                        "✓ conv. | err ≈ {:.1e} | {} subintervals",
                                                        rep.abs_err, rep.subintervals
                                                    ),
                                                    pal.on_surface_variant,
                                                )
                                            } else {
                                                (
                                                    format!(
                                                        "⚠ not converged | {} subintervals",
                                                        rep.subintervals
                                                    ),
                                                    pal.error,
                                                )
                                            };
                                            ui.label(
                                                RichText::new(info)
                                                    .font(FontId::proportional(11.0))
                                                    .color(color),
                                            );
                                        });
                                    }
                                });
                        }

                        if let Some(txt) = paste_text {
                            self.insert_token(&txt);
                        }
                        if let Some(p_expr) = plot_expr {
                            self.plot_functions[0].expr = p_expr;
                            self.plot_functions[0].visible = true;
                            self.active_tab = MainTab::Plotter;
                        }
                        if let Some(i) = delete_idx {
                            self.history.remove(i);
                            self.mark_state_dirty();
                        }
                    });
            });

        ui.add_space(6.0);

        // 2. Middle Area: Editable User Input TermView (`user_input_termview_layout.xml`)
        Frame::new()
            .fill(pal.surface_container)
            .corner_radius(CornerRadius::same(16))
            .inner_margin(egui::Margin::symmetric(12, 8))
            .stroke(Stroke::new(1.5_f32, pal.primary))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    let pretty = if self.input_expr.is_empty() {
                        if self.german_ui {
                            "Ausdruck eingeben oder über Tastatur wählen…".to_string()
                        } else {
                            "Enter expression or use keyboard below…".to_string()
                        }
                    } else {
                        MathEngine::prettify_expression(&self.input_expr)
                    };
                    let font_sz = if pretty.len() > 32 {
                        INPUT_TERM_MIN_FONT_SIZE
                    } else {
                        INPUT_TERM_FONT_SIZE
                    };
                    let text_col = if self.input_expr.is_empty() {
                        pal.on_surface_variant
                    } else {
                        pal.on_surface
                    };
                    ui.label(
                        RichText::new(pretty)
                            .font(FontId::proportional(font_sz))
                            .strong()
                            .color(text_col),
                    );

                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if ui
                            .add(
                                egui::Button::new(
                                    RichText::new("AC")
                                        .font(FontId::proportional(13.0))
                                        .strong()
                                        .color(pal.error),
                                )
                                .fill(pal.surface_container_high)
                                .corner_radius(CornerRadius::same(10)),
                            )
                            .on_hover_text(if self.german_ui {
                                "Eingabe löschen (Escape)"
                            } else {
                                "Clear input (Escape)"
                            })
                            .clicked()
                        {
                            self.input_expr.clear();
                        }
                    });
                });

                // Live Instant Symbolic + Decimal Preview row
                ui.horizontal(|ui| {
                    let raw = self.input_expr.trim().to_string();
                    if raw.is_empty() {
                        let ans_s = self.engine.number_format.format_f64(self.engine.ans.re);
                        ui.label(
                            RichText::new(format!(
                                "ANS = {}   •   Timeout: {} ms",
                                ans_s, SYMBOLIC_WATCHDOG_TIMEOUT_MS
                            ))
                            .font(FontId::proportional(12.5))
                            .color(pal.on_surface_variant),
                        );
                    } else {
                        let key: PreviewKey = (
                            raw,
                            self.engine.angle_mode,
                            self.engine.modulo_always_positive,
                            self.eval_revision,
                        );
                        if self.commit_pending {
                            ui.label(
                                RichText::new(if self.german_ui {
                                    "… wird berechnet"
                                } else {
                                    "… calculating"
                                })
                                .font(FontId::proportional(12.5))
                                .color(pal.on_surface_variant),
                            );
                        } else {
                            if self.screenshot_path.is_some() {
                                if self.preview_cache_key.as_ref() != Some(&key) {
                                    self.preview_cache_result = Some(self.engine.evaluate_preview(&key.0, false));
                                    self.preview_cache_key = Some(key.clone());
                                }
                            } else if self.preview_cache_key.as_ref() != Some(&key)
                                && self.preview_pending_key.as_ref() != Some(&key)
                            {
                                if self.preview_worker.is_none() || self.preview_rx.is_none() {
                                    let (tx, rx) = mpsc::channel();
                                    self.preview_rx = Some(rx);
                                    self.preview_worker = Some(PreviewWorker::new(tx));
                                }
                                if let Some(ref worker) = self.preview_worker {
                                    self.preview_pending_key = Some(key.clone());
                                    worker.submit(key.clone(), self.engine.clone(), ui.ctx().clone());
                                }
                            }
                            if self.preview_cache_key.as_ref() != Some(&key) {
                                ui.label(
                                    RichText::new(if self.german_ui {
                                        "… wird berechnet"
                                    } else {
                                        "… calculating"
                                    })
                                    .font(FontId::proportional(12.5))
                                    .color(pal.on_surface_variant),
                                );
                            } else {
                                match self.preview_cache_result.as_ref().unwrap() {
                                    Ok(preview) => {
                                        ui.label(
                                            RichText::new(format!("exact: {}", preview.exact_text))
                                                .font(FontId::proportional(13.5))
                                                .strong()
                                                .color(pal.primary),
                                        );
                                        if preview.has_distinct_exact {
                                            ui.separator();
                                            ui.label(
                                                RichText::new(format!("decim.: ≈ {}", preview.decimal_text))
                                                    .font(FontId::proportional(13.5))
                                                    .color(pal.on_surface_variant),
                                            );
                                        }
                                    }
                                    Err(_) => {
                                        ui.label(
                                            RichText::new(if self.german_ui {
                                                "… Ausdruck wird vervollständigt"
                                            } else {
                                                "… completing expression"
                                            })
                                            .font(FontId::proportional(12.5))
                                            .color(pal.on_surface_variant),
                                        );
                                    }
                                }
                            }
                        }
                    }
                });
            });

        ui.add_space(6.0);

        // 3. Bottom Keyboard Card (`main_keyboard_layout.xml`, corner radius 20dp + `@id/main_keyboard_drag_bar`)
        Frame::new()
            .fill(pal.surface_container)
            .corner_radius(CornerRadius::same(KEYBOARD_CARD_CORNER_RADIUS as u8))
            .inner_margin(egui::Margin::same(10))
            .stroke(Stroke::new(1.0_f32, pal.outline_variant))
            .show(ui, |ui| {
                // Centered 64dp Android Drag Handle Bar (`@id/main_keyboard_drag_bar` from `main_keyboard_layout.xml`)
                ui.vertical_centered(|ui| {
                    let (drag_rect, drag_resp) =
                        ui.allocate_exact_size(egui::vec2(64.0, 10.0), Sense::click());
                    let bar_rect = egui::Rect::from_center_size(
                        drag_rect.center(),
                        egui::vec2(64.0, 4.0),
                    );
                    ui.painter().rect_filled(
                        bar_rect,
                        CornerRadius::same(2),
                        if drag_resp.hovered() {
                            pal.primary
                        } else {
                            pal.on_surface_variant
                        },
                    );
                    if drag_resp
                        .on_hover_text(if self.german_ui {
                            "Ziehleiste (@id/main_keyboard_drag_bar) — Klicken für Zweittastatur (Dual-Ansicht)"
                        } else {
                            "Drag handle (@id/main_keyboard_drag_bar) — Click to toggle Auxiliary Keyboard"
                        })
                        .clicked()
                    {
                        self.dual_keyboard = !self.dual_keyboard;
                    }
                });
                ui.add_space(2.0);

                // Section Selector Header Bar (`height = 32.0` dp)
                ui.allocate_ui_with_layout(
                    egui::vec2(ui.available_width(), KEYBOARD_SECTION_BAR_HEIGHT),
                    Layout::left_to_right(Align::Center),
                    |ui| {
                        ui.columns(KeyboardSection::ALL.len(), |cols| {
                            for (i, sec) in KeyboardSection::ALL.iter().enumerate() {
                                let is_selected = if self.dual_keyboard && *sec == KeyboardSection::Numbers {
                                    true
                                } else {
                                    self.active_section == *sec
                                };
                                let fill = if self.active_section == *sec {
                                    pal.primary
                                } else if is_selected {
                                    pal.primary_container
                                } else {
                                    pal.surface_container_high
                                };
                                let fg = if self.active_section == *sec {
                                    pal.on_primary
                                } else if is_selected {
                                    pal.on_primary_container
                                } else {
                                    pal.on_surface
                                };

                                cols[i].vertical_centered(|ui| {
                                    let btn = egui::Button::new(
                                        RichText::new(sec.label(self.german_ui))
                                            .font(FontId::proportional(13.0))
                                            .strong()
                                            .color(fg),
                                    )
                                    .fill(fill)
                                    .corner_radius(CornerRadius::same(14))
                                    .min_size(egui::vec2(
                                        ui.available_width() * 0.95,
                                        KEYBOARD_SECTION_BAR_HEIGHT - 4.0,
                                    ));
                                    if ui.add(btn).clicked() {
                                        if self.dual_keyboard && *sec == KeyboardSection::Numbers {
                                            self.dual_keyboard = false;
                                            self.active_section = KeyboardSection::Numbers;
                                        } else {
                                            self.active_section = *sec;
                                        }
                                    }
                                });
                            }
                        });
                    },
                );

                ui.add_space(4.0);

                // Keyboard body: Dual Keyboard (Left: Scientific section, Right: 1 2 3 Number pad) or Single Keyboard
                if self.dual_keyboard {
                    let sci_sec = if self.active_section == KeyboardSection::Numbers {
                        KeyboardSection::Operators
                    } else {
                        self.active_section
                    };
                    let _ = self.aux_section;
                    ui.columns(2, |cols| {
                        self.render_keyboard_section(&mut cols[0], sci_sec, pal);
                        self.render_keyboard_section(&mut cols[1], KeyboardSection::Numbers, pal);
                    });
                } else {
                    self.render_keyboard_section(ui, self.active_section, pal);
                }
            });
    }

    fn render_keyboard_section(
        &mut self,
        ui: &mut Ui,
        section: KeyboardSection,
        pal: &MaterialYouPalette,
    ) {
        let avail_h = ui.available_height().max(150.0);

        match section {
            KeyboardSection::Numbers => {
                // Optional Hex row A B C D E F (`keyboard_hex_number_section.xml`)
                let hex_h = if self.show_hex_row {
                    let h = (avail_h * 0.17).max(26.0);
                    ui.columns(6, |cols| {
                        for (i, hex_ch) in ["A", "B", "C", "D", "E", "F"].iter().enumerate() {
                            if Self::key_btn(
                                &mut cols[i],
                                hex_ch,
                                h,
                                pal.tertiary_container,
                                pal.on_tertiary_container,
                            ) {
                                if !self.input_expr.ends_with("0x")
                                    && !self
                                        .input_expr
                                        .chars()
                                        .last()
                                        .map(|c| c.is_ascii_hexdigit())
                                        .unwrap_or(false)
                                {
                                    self.insert_token(&format!("0x{}", hex_ch));
                                } else {
                                    self.insert_token(hex_ch);
                                }
                            }
                        }
                    });
                    h + 4.0
                } else {
                    0.0
                };

                // Authentic `keyboard_number_section.xml` layout:
                // Left 3 columns (weight 3): 4 rows of digits (`7 8 9`, `4 5 6`, `1 2 3`, `0 . =`)
                // Right 1 column (weight 1, `layout_rowSpan="4"`): 5 vertically stacked operators (`DEL`, `÷`, `×`, `+`, `−`)
                let grid_h = (avail_h - hex_h - 6.0).max(120.0);
                let digit_row_h = ((grid_h - 18.0) / 4.0).max(26.0);
                let spacing_y = ui.spacing().item_spacing.y;
                let op_row_h = ((4.0 * digit_row_h - spacing_y) / 5.0).max(16.0);

                let dec_lbl = match self.engine.number_format {
                    NumberFormatMode::SpaceComma
                    | NumberFormatMode::PointComma
                    | NumberFormatMode::NoneComma => ",",
                    NumberFormatMode::SpacePoint
                    | NumberFormatMode::CommaPoint
                    | NumberFormatMode::NonePoint => ".",
                };
                let ctx_clone = ui.ctx().clone();
                ui.columns(4, |cols| {
                    let digit_cols = [
                        ["7", "4", "1", "0"],
                        ["8", "5", "2", dec_lbl],
                        ["9", "6", "3", "="],
                    ];
                    for (c_idx, col_keys) in digit_cols.iter().enumerate() {
                        for &lbl in col_keys {
                            let is_enter = lbl == "=";
                            let fill = if is_enter {
                                pal.primary
                            } else {
                                pal.surface_container_high
                            };
                            let fg = if is_enter {
                                pal.on_primary
                            } else {
                                pal.on_surface
                            };
                            if Self::key_btn(&mut cols[c_idx], lbl, digit_row_h, fill, fg) {
                                if is_enter {
                                    self.evaluate_current(Some(&ctx_clone));
                                } else if lbl == dec_lbl {
                                    self.insert_token(".");
                                } else {
                                    self.insert_token(lbl);
                                }
                            }
                        }
                    }

                    // Rightmost column (`rowSpan="4"` with 5 vertical buttons: `⌫`, `÷`, `×`, `+`, `−`)
                    for (op_lbl, op_tok) in [
                        ("⌫", "DEL"),
                        ("÷", "/"),
                        ("×", "*"),
                        ("+", "+"),
                        ("−", "-"),
                    ] {
                        if Self::key_btn(
                            &mut cols[3],
                            op_lbl,
                            op_row_h,
                            pal.secondary_container,
                            pal.on_secondary_container,
                        ) {
                            if op_tok == "DEL" {
                                self.backspace();
                            } else {
                                self.insert_token(op_tok);
                            }
                        }
                    }
                });
            }

            KeyboardSection::Operators => {
                let row_h = ((avail_h - 16.0) / 3.0).max(34.0);
                let grid = [
                    [("(", "("), (")", ")"), ("π", "pi"), ("e", "e")],
                    [
                        ("x²", "^2"),
                        ("xⁿ", "^("),
                        ("√x", "sqrt("),
                        ("ⁿ√x", "nthroot(3, "),
                    ],
                    [
                        ("ln", "ln("),
                        ("log₁₀", "log(10, "),
                        ("10ⁿ", "10^("),
                        ("ANS", "ans"),
                    ],
                ];
                for row in grid {
                    ui.columns(4, |cols| {
                        for (i, (label, tok)) in row.iter().enumerate() {
                            let is_accent = *label == "ANS" || *label == "π" || *label == "e";
                            let fill = if is_accent {
                                pal.primary_container
                            } else {
                                pal.surface_container_high
                            };
                            let fg = if is_accent {
                                pal.on_primary_container
                            } else {
                                pal.on_surface
                            };
                            if Self::key_btn(&mut cols[i], label, row_h, fill, fg) {
                                self.insert_token(tok);
                            }
                        }
                    });
                }
            }

            KeyboardSection::Trigonometry => {
                // Top toggle row (`radians` | `degrees`) + 4x3 grid from `keyboard_trig_section.xml`
                let top_h = 28.0;
                let row_h = ((avail_h - top_h - 20.0) / 3.0).max(30.0);

                ui.columns(2, |cols| {
                    let is_rad = self.engine.angle_mode == AngleMode::Radians;
                    if Self::key_btn(
                        &mut cols[0],
                        if self.german_ui {
                            "Bogenmaß (rad)"
                        } else {
                            "radians"
                        },
                        top_h,
                        if is_rad {
                            pal.primary
                        } else {
                            pal.surface_container_high
                        },
                        if is_rad {
                            pal.on_primary
                        } else {
                            pal.on_surface_variant
                        },
                    ) {
                        self.engine.angle_mode = AngleMode::Radians;
                        self.mark_state_dirty();
                    }
                    if Self::key_btn(
                        &mut cols[1],
                        if self.german_ui {
                            "Gradmaß (deg)"
                        } else {
                            "degrees"
                        },
                        top_h,
                        if !is_rad {
                            pal.primary
                        } else {
                            pal.surface_container_high
                        },
                        if !is_rad {
                            pal.on_primary
                        } else {
                            pal.on_surface_variant
                        },
                    ) {
                        self.engine.angle_mode = AngleMode::Degrees;
                        self.mark_state_dirty();
                    }
                });

                let grid = [
                    [
                        ("sin", "sin("),
                        ("sin⁻¹", "asin("),
                        ("sinh", "sinh("),
                        ("sinh⁻¹", "asinh("),
                    ],
                    [
                        ("cos", "cos("),
                        ("cos⁻¹", "acos("),
                        ("cosh", "cosh("),
                        ("cosh⁻¹", "acosh("),
                    ],
                    [
                        ("tan", "tan("),
                        ("tan⁻¹", "atan("),
                        ("tanh", "tanh("),
                        ("tanh⁻¹", "atanh("),
                    ],
                ];
                for row in grid {
                    ui.columns(4, |cols| {
                        for (i, (label, tok)) in row.iter().enumerate() {
                            if Self::key_btn(
                                &mut cols[i],
                                label,
                                row_h,
                                pal.surface_container_high,
                                pal.on_surface,
                            ) {
                                self.insert_token(tok);
                            }
                        }
                    });
                }
            }

            KeyboardSection::AdvancedMath => {
                // 4x3 grid from `keyboard_advanced_math_section.xml`:
                // Row 1: `∑`, `∏`, `nCr`, `n!`
                // Row 2: `∫`, `d/dx`, `mod`, `i`
                // Row 3: `[ ]`, `A⁻¹`, `Aᵀ`, `det`
                let row_h = ((avail_h - 16.0) / 3.0).max(34.0);
                let grid = [
                    [
                        ("∑", "sum(k, k, 1, 10)"),
                        ("∏", "prod(k, k, 1, 5)"),
                        ("nCr", "binomial(5, 2)"),
                        ("n!", "!"),
                    ],
                    [
                        ("∫", "int(x^2, x, 0, 1)"),
                        ("d/dx", "deriv(x^3, x, 2)"),
                        ("mod", " mod "),
                        ("i", "i"),
                    ],
                    [
                        ("[ Matrix ]", "[["),
                        ("A⁻¹", "inv("),
                        ("Aᵀ", "trans("),
                        ("det", "det("),
                    ],
                ];
                for row in grid {
                    ui.columns(4, |cols| {
                        for (i, (label, tok)) in row.iter().enumerate() {
                            if Self::key_btn(
                                &mut cols[i],
                                label,
                                row_h,
                                pal.surface_container_high,
                                pal.on_surface,
                            ) {
                                self.insert_token(tok);
                            }
                        }
                    });
                }
            }

            KeyboardSection::Variables => {
                // 4x3 grid from `keyboard_variable_section.xml`:
                // 11 customizable variable/constant slots + `more` button
                let row_h = ((avail_h - 16.0) / 3.0).max(34.0);
                let slots = self.keyboard_slots.clone();

                for r in 0..3 {
                    ui.columns(4, |cols| {
                        for c in 0..4 {
                            let idx = r * 4 + c;
                            if idx < ASSIGNABLE_VARIABLE_SLOTS {
                                let sym = &slots[idx];
                                let badge = if let Some(val) = self.engine.variables.get(sym) {
                                    format!("{} ({})", sym, val)
                                } else {
                                    sym.clone()
                                };
                                if Self::key_btn(
                                    &mut cols[c],
                                    &badge,
                                    row_h,
                                    pal.surface_container_high,
                                    pal.on_surface,
                                ) {
                                    self.insert_token(sym);
                                }
                            } else {
                                // Slot 11: `:= / Mehr…` button (`@id/key_variable_more`)
                                let lbl = if self.german_ui {
                                    ":= / Konst."
                                } else {
                                    ":= / more"
                                };
                                if Self::key_btn(
                                    &mut cols[c],
                                    lbl,
                                    row_h,
                                    pal.primary_container,
                                    pal.on_primary_container,
                                ) {
                                    if !self.input_expr.is_empty()
                                        && USER_VARIABLES.contains(&self.input_expr.trim())
                                    {
                                        self.insert_token(" := ");
                                    } else {
                                        self.active_tab = MainTab::VariablesAndConstants;
                                    }
                                }
                            }
                        }
                    });
                }
            }

            KeyboardSection::More => {
                // 2x2 grid from `keyboard_more_section.xml`
                let row_h = ((avail_h - 12.0) / 2.0).max(48.0);
                ui.columns(2, |cols| {
                    if Self::key_btn(
                        &mut cols[0],
                        if self.german_ui {
                            "Naturkonstanten & Variablen"
                        } else {
                            "Physical Constants & Variables"
                        },
                        row_h,
                        pal.primary_container,
                        pal.on_primary_container,
                    ) {
                        self.active_tab = MainTab::VariablesAndConstants;
                    }
                    if Self::key_btn(
                        &mut cols[1],
                        if self.german_ui {
                            "2D-Funktionsplotter"
                        } else {
                            "2D Function Plotter"
                        },
                        row_h,
                        pal.secondary_container,
                        pal.on_secondary_container,
                    ) {
                        self.active_tab = MainTab::Plotter;
                    }
                });
                ui.columns(2, |cols| {
                    if Self::key_btn(
                        &mut cols[0],
                        if self.german_ui {
                            "Einstellungen & Zahlenformat"
                        } else {
                            "Settings & Number Formatting"
                        },
                        row_h,
                        pal.surface_container_high,
                        pal.on_surface,
                    ) {
                        self.active_tab = MainTab::ConverterAndSettings;
                    }
                    let hex_lbl = if self.show_hex_row {
                        if self.german_ui {
                            "🔢 Hex-Zeile (A–F): Aktiv"
                        } else {
                            "🔢 Hex Row (A–F): On"
                        }
                    } else if self.german_ui {
                        "🔢 Hex-Zeile (A–F) einblenden"
                    } else {
                        "🔢 Enable Hex Row (A–F)"
                    };
                    if Self::key_btn(
                        &mut cols[1],
                        hex_lbl,
                        row_h,
                        pal.tertiary_container,
                        pal.on_tertiary_container,
                    ) {
                        self.show_hex_row = !self.show_hex_row;
                        self.active_section = KeyboardSection::Numbers;
                    }
                });
            }
        }
    }

    fn key_btn(ui: &mut Ui, label: &str, height: f32, fill: Color32, fg: Color32) -> bool {
        let w = ui.available_width();
        let btn = egui::Button::new(
            RichText::new(label)
                .font(FontId::proportional(15.0))
                .strong()
                .color(fg),
        )
        .fill(fill)
        .corner_radius(CornerRadius::same(14))
        .min_size(egui::vec2(w, height));
        ui.add(btn).clicked()
    }

    fn render_plotter_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        // Function inputs panel
        Frame::new()
            .fill(pal.surface_container)
            .corner_radius(CornerRadius::same(16))
            .inner_margin(egui::Margin::same(10))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(if self.german_ui {
                            "2D-Funktionsplotter (Ziehen zum Verschieben, Mausrad zum Zoomen)"
                        } else {
                            "2D Function Plotter (Drag to pan, Scroll to zoom)"
                        })
                        .font(FontId::proportional(14.5))
                        .strong(),
                    );
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if ui
                            .small_button(if self.german_ui {
                                "⟳ Ursprung zentrieren"
                            } else {
                                "⟳ Reset View"
                            })
                            .clicked()
                        {
                            self.plot_center_x = 0.0;
                            self.plot_center_y = 0.0;
                            self.plot_scale = 48.0;
                        }
                        if ui.small_button("🔍+").clicked() {
                            self.plot_scale = (self.plot_scale * 1.25).min(400.0);
                        }
                        if ui.small_button("🔍−").clicked() {
                            self.plot_scale = (self.plot_scale / 1.25).max(8.0);
                        }
                    });
                });

                ui.add_space(4.0);
                for (i, pf) in self.plot_functions.iter_mut().enumerate() {
                    ui.horizontal(|ui| {
                        ui.checkbox(&mut pf.visible, "");
                        ui.label(
                            RichText::new(format!("f{}(x) =", i + 1))
                                .font(FontId::proportional(13.5))
                                .strong()
                                .color(pf.color),
                        );
                        ui.add(
                            egui::TextEdit::singleline(&mut pf.expr)
                                .desired_width(220.0)
                                .font(FontId::monospace(13.5)),
                        );
                        if let Some(y_at_trace) = self.engine.eval_for_x(&pf.expr, self.plot_trace_x)
                        {
                            let h = 1e-5;
                            let dy = match (
                                self.engine.eval_for_x(&pf.expr, self.plot_trace_x + h),
                                self.engine.eval_for_x(&pf.expr, self.plot_trace_x - h),
                            ) {
                                (Some(yp), Some(ym)) => (yp - ym) / (2.0 * h),
                                _ => 0.0,
                            };
                            ui.label(
                                RichText::new(format!(
                                    "f({:.2}) = {:.4}  |  f'({:.2}) = {:.4}",
                                    self.plot_trace_x, y_at_trace, self.plot_trace_x, dy
                                ))
                                .font(FontId::monospace(12.0))
                                .color(pal.on_surface_variant),
                            );
                        }
                    });
                }
            });

        ui.add_space(6.0);

        // Plot Canvas (`FunctionPlotting2DView.onDraw` grid stroke mechanics)
        let avail = ui.available_size();
        let (rect, response) =
            ui.allocate_exact_size(egui::vec2(avail.x, avail.y.max(200.0)), Sense::click_and_drag());

        if response.dragged() {
            let delta = response.drag_delta();
            self.plot_center_x -= (delta.x as f64) / self.plot_scale;
            self.plot_center_y += (delta.y as f64) / self.plot_scale;
        }
        if let Some(hover_pos) = response.hover_pos() {
            let scroll = ui.input(|i| i.smooth_scroll_delta.y);
            if scroll.abs() > 0.1 {
                let factor = (1.0 + scroll as f64 * 0.002).clamp(0.8, 1.25);
                self.plot_scale = (self.plot_scale * factor).clamp(8.0, 400.0);
            }
            self.plot_trace_x =
                self.plot_center_x + ((hover_pos.x - rect.center().x) as f64) / self.plot_scale;
        }

        let painter = ui.painter_at(rect);
        painter.rect_filled(rect, CornerRadius::same(16), pal.surface_container_low);

        let cx = rect.center().x;
        let cy = rect.center().y;
        let scale = self.plot_scale as f32;

        // Choose grid step based on zoom scale
        let raw_step = (80.0 / self.plot_scale).max(0.1);
        let mag = 10f64.powf(raw_step.log10().floor());
        let major_step = if raw_step / mag < 2.0 {
            mag
        } else if raw_step / mag < 5.0 {
            2.0 * mag
        } else {
            5.0 * mag
        };
        let minor_step = major_step / 5.0;

        let x_min = self.plot_center_x - ((rect.width() * 0.5) as f64) / self.plot_scale;
        let x_max = self.plot_center_x + ((rect.width() * 0.5) as f64) / self.plot_scale;
        let y_min = self.plot_center_y - ((rect.height() * 0.5) as f64) / self.plot_scale;
        let y_max = self.plot_center_y + ((rect.height() * 0.5) as f64) / self.plot_scale;

        // Minor grid lines (`PLOTTER_MINOR_GRID_STROKE = 1.0` px)
        let mut gx = (x_min / minor_step).floor() * minor_step;
        while gx <= x_max {
            let sx = cx + ((gx - self.plot_center_x) as f32) * scale;
            painter.line_segment(
                [Pos2::new(sx, rect.top()), Pos2::new(sx, rect.bottom())],
                Stroke::new(PLOTTER_MINOR_GRID_STROKE, pal.plotter_minor_grid),
            );
            gx += minor_step;
        }
        let mut gy = (y_min / minor_step).floor() * minor_step;
        while gy <= y_max {
            let sy = cy - ((gy - self.plot_center_y) as f32) * scale;
            painter.line_segment(
                [Pos2::new(rect.left(), sy), Pos2::new(rect.right(), sy)],
                Stroke::new(PLOTTER_MINOR_GRID_STROKE, pal.plotter_minor_grid),
            );
            gy += minor_step;
        }

        // Major grid lines (`PLOTTER_MAJOR_GRID_STROKE = 1.25` px) & Axis labels (`PLOTTER_AXIS_LABEL_SIZE = 19.0`)
        let label_font = FontId::proportional((PLOTTER_AXIS_LABEL_SIZE * 0.65).max(11.5));
        let mut mx = (x_min / major_step).floor() * major_step;
        while mx <= x_max {
            let sx = cx + ((mx - self.plot_center_x) as f32) * scale;
            painter.line_segment(
                [Pos2::new(sx, rect.top()), Pos2::new(sx, rect.bottom())],
                Stroke::new(PLOTTER_MAJOR_GRID_STROKE, pal.plotter_major_grid),
            );
            if mx.abs() > 1e-9 {
                let y_axis_screen =
                    (cy - ((-self.plot_center_y) as f32) * scale).clamp(rect.top() + 14.0, rect.bottom() - 18.0);
                painter.text(
                    Pos2::new(sx + 3.0, y_axis_screen + 4.0),
                    egui::Align2::LEFT_TOP,
                    format!("{:.2}", mx).trim_end_matches('0').trim_end_matches('.'),
                    label_font.clone(),
                    pal.on_surface_variant,
                );
            }
            mx += major_step;
        }
        let mut my = (y_min / major_step).floor() * major_step;
        while my <= y_max {
            let sy = cy - ((my - self.plot_center_y) as f32) * scale;
            painter.line_segment(
                [Pos2::new(rect.left(), sy), Pos2::new(rect.right(), sy)],
                Stroke::new(PLOTTER_MAJOR_GRID_STROKE, pal.plotter_major_grid),
            );
            if my.abs() > 1e-9 {
                let x_axis_screen =
                    (cx + ((-self.plot_center_x) as f32) * scale).clamp(rect.left() + 6.0, rect.right() - 40.0);
                painter.text(
                    Pos2::new(x_axis_screen + 5.0, sy - 2.0),
                    egui::Align2::LEFT_BOTTOM,
                    format!("{:.2}", my).trim_end_matches('0').trim_end_matches('.'),
                    label_font.clone(),
                    pal.on_surface_variant,
                );
            }
            my += major_step;
        }

        // X and Y Axes
        let x0_screen = cx + ((-self.plot_center_x) as f32) * scale;
        let y0_screen = cy - ((-self.plot_center_y) as f32) * scale;
        if x0_screen >= rect.left() && x0_screen <= rect.right() {
            painter.line_segment(
                [Pos2::new(x0_screen, rect.top()), Pos2::new(x0_screen, rect.bottom())],
                Stroke::new(2.0_f32, pal.on_surface_variant),
            );
        }
        if y0_screen >= rect.top() && y0_screen <= rect.bottom() {
            painter.line_segment(
                [Pos2::new(rect.left(), y0_screen), Pos2::new(rect.right(), y0_screen)],
                Stroke::new(2.0_f32, pal.on_surface_variant),
            );
        }

        // Plot each visible function curve
        let samples = (rect.width() as usize).clamp(200, 900);
        for pf in &self.plot_functions {
            if !pf.visible || pf.expr.trim().is_empty() {
                continue;
            }
            let mut prev_pt: Option<Pos2> = None;
            let mut prev_val: Option<(f64, f64)> = None;

            for s in 0..=samples {
                let t = (s as f32) / (samples as f32);
                let sx = rect.left() + t * rect.width();
                let x_val = self.plot_center_x + ((sx - cx) as f64) / self.plot_scale;
                if let Some(y_val) = self.engine.eval_for_x(&pf.expr, x_val) {
                    let sy = cy - ((y_val - self.plot_center_y) as f32) * scale;
                    let cur_pt = Pos2::new(sx, sy);
                    if sy >= rect.top() - 200.0 && sy <= rect.bottom() + 200.0 {
                        if let Some(p) = prev_pt {
                            if (cur_pt.y - p.y).abs() < rect.height() * 0.9 {
                                painter.line_segment([p, cur_pt], Stroke::new(2.4_f32, pf.color));
                            }
                        }
                        prev_pt = Some(cur_pt);
                    } else {
                        prev_pt = None;
                    }

                    // Highlight roots (zero crossings)
                    if let Some((px, py)) = prev_val {
                        if (py <= 0.0 && y_val >= 0.0 || py >= 0.0 && y_val <= 0.0)
                            && (y_val - py).abs() < 5.0
                        {
                            let root_x = px - py * (x_val - px) / (y_val - py);
                            let rx = cx + ((root_x - self.plot_center_x) as f32) * scale;
                            if y0_screen >= rect.top() && y0_screen <= rect.bottom() {
                                painter.circle_filled(Pos2::new(rx, y0_screen), 4.0, pf.color);
                            }
                        }
                    }
                    prev_val = Some((x_val, y_val));
                } else {
                    prev_pt = None;
                    prev_val = None;
                }
            }

            // Draw trace point at `self.plot_trace_x`
            if let Some(ty) = self.engine.eval_for_x(&pf.expr, self.plot_trace_x) {
                let tx_s = cx + ((self.plot_trace_x - self.plot_center_x) as f32) * scale;
                let ty_s = cy - ((ty - self.plot_center_y) as f32) * scale;
                if rect.contains(Pos2::new(tx_s, ty_s)) {
                    painter.circle_filled(Pos2::new(tx_s, ty_s), 5.5, pf.color);
                    painter.circle_stroke(
                        Pos2::new(tx_s, ty_s),
                        5.5,
                        Stroke::new(1.5_f32, pal.bg_surface),
                    );
                }
            }
        }

        painter.rect_stroke(
            rect,
            CornerRadius::same(16),
            Stroke::new(1.0_f32, pal.outline_variant),
            StrokeKind::Inside,
        );
    }

    fn render_variables_and_constants_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        // 1. User Variables Management (`Lc/a/a/e/h;` 11 assignable variables)
        Frame::new()
            .fill(pal.surface_container)
            .corner_radius(CornerRadius::same(16))
            .inner_margin(egui::Margin::same(12))
            .show(ui, |ui| {
                ui.label(
                    RichText::new(if self.german_ui {
                        "📌 Benutzer-Variablen (11 zuweisbare Speicherplätze: a, b, c, d, k, l, m, n, x, y, z)"
                    } else {
                        "📌 User Variables (11 assignable slots: a, b, c, d, k, l, m, n, x, y, z)"
                    })
                    .font(FontId::proportional(14.5))
                    .strong(),
                );
                ui.add_space(4.0);

                ui.horizontal_wrapped(|ui| {
                    for &vname in &USER_VARIABLES {
                        let val_opt = self.engine.variables.get(vname).copied();
                        let txt = match val_opt {
                            Some(v) => format!("{} = {}", vname, self.engine.number_format.format_f64(v.to_f64())),
                            None => format!("{} (leer)", vname),
                        };
                        let btn = egui::Button::new(
                            RichText::new(txt)
                                .font(FontId::proportional(13.0))
                                .strong()
                                .color(if val_opt.is_some() {
                                    pal.on_primary_container
                                } else {
                                    pal.on_surface_variant
                                }),
                        )
                        .fill(if val_opt.is_some() {
                            pal.primary_container
                        } else {
                            pal.surface_container_high
                        })
                        .corner_radius(CornerRadius::same(12));

                        if ui
                            .add(btn)
                            .on_hover_text("Klicken zum Einfügen / Bearbeiten")
                            .clicked()
                        {
                            self.var_edit_name = vname.to_string();
                            if let Some(v) = val_opt {
                                self.var_edit_value = v.to_string();
                            }
                            self.insert_token(vname);
                        }
                    }
                });

                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    ui.label(if self.german_ui {
                        "Variable setzen:"
                    } else {
                        "Assign variable:"
                    });
                    egui::ComboBox::from_id_salt("var_select_combo")
                        .selected_text(&self.var_edit_name)
                        .width(60.0)
                        .show_ui(ui, |ui| {
                            for &v in &USER_VARIABLES {
                                ui.selectable_value(&mut self.var_edit_name, v.to_string(), v);
                            }
                        });
                    ui.label(":=");
                    ui.add(
                        egui::TextEdit::singleline(&mut self.var_edit_value)
                            .desired_width(110.0)
                            .font(FontId::monospace(13.5)),
                    );
                    if ui
                        .button(if self.german_ui {
                            "💾 Speichern"
                        } else {
                            "💾 Save"
                        })
                        .clicked()
                    {
                        if let Ok(out) = self.engine.evaluate_preview(&self.var_edit_value, false) {
                            if let Some(val) = out.val {
                                self.engine
                                    .variables
                                    .insert(self.var_edit_name.clone(), val);
                                self.engine.matrix_variables.remove(&self.var_edit_name);
                                self.mark_state_dirty();
                            } else if let Some(c) = out.numeric_value {
                                self.engine
                                    .variables
                                    .insert(self.var_edit_name.clone(), Value::from_complex(c));
                                self.engine.matrix_variables.remove(&self.var_edit_name);
                                self.mark_state_dirty();
                            }
                        }
                    }
                    if ui
                        .button(if self.german_ui {
                            "Löschen (unset)"
                        } else {
                            "Unset"
                        })
                        .clicked()
                    {
                        self.engine.variables.remove(&self.var_edit_name);
                        self.engine.matrix_variables.remove(&self.var_edit_name);
                        self.mark_state_dirty();
                    }
                });
            });

        ui.add_space(8.0);

        // 2. Scientific Constants Library (`VariableMoreActivity` — 28 Constants in 4 categories)
        ui.horizontal(|ui| {
            ui.label(
                RichText::new(if self.german_ui {
                    "🔍 Naturkonstanten durchsuchen:"
                } else {
                    "🔍 Search Constants:"
                })
                .strong(),
            );
            ui.add(
                egui::TextEdit::singleline(&mut self.constants_search)
                    .desired_width(220.0)
                    .hint_text("c₀, Planck, Avogadro, Faraday…"),
            );
            ui.separator();
            ui.label(if self.german_ui {
                "Tastatur-Slot für 'a b c' belegen:"
            } else {
                "Pin to 'a b c' slot:"
            });
            egui::ComboBox::from_id_salt("slot_pin_combo")
                .selected_text(format!(
                    "Slot #{} ({})",
                    self.selected_slot_to_assign + 1,
                    self.keyboard_slots[self.selected_slot_to_assign]
                ))
                .show_ui(ui, |ui| {
                    for s_idx in 0..ASSIGNABLE_VARIABLE_SLOTS {
                        ui.selectable_value(
                            &mut self.selected_slot_to_assign,
                            s_idx,
                            format!("Slot #{} ({})", s_idx + 1, self.keyboard_slots[s_idx]),
                        );
                    }
                });
        });

        ui.add_space(6.0);

        let query = self.constants_search.to_lowercase();
        ScrollArea::vertical()
            .auto_shrink([false, false])
            .show(ui, |ui| {
                for cat in [
                    ConstantCategory::Universal,
                    ConstantCategory::Electromagnetic,
                    ConstantCategory::Chemical,
                    ConstantCategory::Mathematical,
                ] {
                    let cat_title = if self.german_ui {
                        cat.title_de()
                    } else {
                        cat.title_en()
                    };
                    Frame::new()
                        .fill(pal.surface_container_low)
                        .corner_radius(CornerRadius::same(14))
                        .inner_margin(egui::Margin::same(10))
                        .show(ui, |ui| {
                            ui.label(
                                RichText::new(cat_title)
                                    .font(FontId::proportional(14.5))
                                    .strong()
                                    .color(pal.primary),
                            );
                            ui.add_space(4.0);

                            for sc in SCIENTIFIC_CONSTANTS.iter().filter(|c| c.category == cat) {
                                let name = if self.german_ui { sc.name_de } else { sc.name_en };
                                if !query.is_empty()
                                    && !name.to_lowercase().contains(&query)
                                    && !sc.symbol.to_lowercase().contains(&query)
                                    && !sc.token.to_lowercase().contains(&query)
                                {
                                    continue;
                                }

                                Frame::new()
                                    .fill(pal.surface_container)
                                    .corner_radius(CornerRadius::same(10))
                                    .inner_margin(egui::Margin::symmetric(10, 6))
                                    .show(ui, |ui| {
                                        ui.horizontal(|ui| {
                                            // Symbol badge
                                            Frame::new()
                                                .fill(pal.primary_container)
                                                .corner_radius(CornerRadius::same(8))
                                                .inner_margin(egui::Margin::symmetric(8, 4))
                                                .show(ui, |ui| {
                                                    ui.label(
                                                        RichText::new(sc.symbol)
                                                            .font(FontId::proportional(14.5))
                                                            .strong()
                                                            .color(pal.on_primary_container),
                                                    );
                                                });

                                            ui.vertical(|ui| {
                                                ui.label(
                                                    RichText::new(name)
                                                        .font(FontId::proportional(13.5))
                                                        .strong(),
                                                );
                                                ui.label(
                                                    RichText::new(format!(
                                                        "{} {}",
                                                        sc.display_value, sc.unit
                                                    ))
                                                    .font(FontId::monospace(12.0))
                                                    .color(pal.on_surface_variant),
                                                );
                                            });

                                            ui.with_layout(
                                                Layout::right_to_left(Align::Center),
                                                |ui| {
                                                    if ui
                                                        .button(if self.german_ui {
                                                            "➕ In Rechner"
                                                        } else {
                                                            "➕ Insert"
                                                        })
                                                        .clicked()
                                                    {
                                                        self.insert_token(sc.token);
                                                        self.active_tab = MainTab::Calculator;
                                                    }
                                                    if ui
                                                        .small_button(if self.german_ui {
                                                            "📌 Auf Tastatur"
                                                        } else {
                                                            "📌 Pin to Slot"
                                                        })
                                                        .clicked()
                                                    {
                                                        self.keyboard_slots
                                                            [self.selected_slot_to_assign] =
                                                            sc.token.to_string();
                                                    }
                                                },
                                            );
                                        });
                                    });
                            }
                        });
                    ui.add_space(6.0);
                }
            });
    }

    fn render_converter_and_settings_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        ScrollArea::vertical()
            .auto_shrink([false, false])
            .show(ui, |ui| {
                // 1. Offline Unit Converter (`gui.k1.a`)
                Frame::new()
                    .fill(pal.surface_container)
                    .corner_radius(CornerRadius::same(16))
                    .inner_margin(egui::Margin::same(12))
                    .show(ui, |ui| {
                        ui.label(
                            RichText::new(if self.german_ui {
                                "🔄 Einheitenrechner"
                            } else {
                                "🔄 Unit Converter"
                            })
                            .font(FontId::proportional(15.0))
                            .strong()
                            .color(pal.primary),
                        );
                        ui.add_space(4.0);

                        ui.horizontal_wrapped(|ui| {
                            for cat in UnitCategory::ALL {
                                let sel = self.unit_cat == cat;
                                if ui
                                    .selectable_label(sel, cat.name(self.german_ui))
                                    .clicked()
                                {
                                    self.unit_cat = cat;
                                    self.unit_from_idx = 0;
                                    self.unit_to_idx = 1;
                                }
                            }
                        });

                        let units = self.unit_cat.units();
                        self.unit_from_idx = self.unit_from_idx.min(units.len() - 1);
                        self.unit_to_idx = self.unit_to_idx.min(units.len() - 1);

                        ui.add_space(6.0);
                        ui.horizontal(|ui| {
                            ui.add(
                                egui::TextEdit::singleline(&mut self.unit_input_str)
                                    .desired_width(110.0)
                                    .font(FontId::monospace(14.5)),
                            );
                            egui::ComboBox::from_id_salt("unit_from_cb")
                                .selected_text(units[self.unit_from_idx].0)
                                .show_ui(ui, |ui| {
                                    for (i, (uname, _)) in units.iter().enumerate() {
                                        ui.selectable_value(&mut self.unit_from_idx, i, *uname);
                                    }
                                });
                            ui.label("➡");
                            egui::ComboBox::from_id_salt("unit_to_cb")
                                .selected_text(units[self.unit_to_idx].0)
                                .show_ui(ui, |ui| {
                                    for (i, (uname, _)) in units.iter().enumerate() {
                                        ui.selectable_value(&mut self.unit_to_idx, i, *uname);
                                    }
                                });

                            let in_val: f64 = self.unit_input_str.trim().parse().unwrap_or(0.0);
                            let converted = if self.unit_cat == UnitCategory::Temperature {
                                convert_temperature(in_val, self.unit_from_idx, self.unit_to_idx)
                            } else {
                                let base = in_val * units[self.unit_from_idx].1;
                                base / units[self.unit_to_idx].1
                            };
                            let formatted = self.engine.number_format.format_f64(converted);
                            ui.label(
                                RichText::new(format!("= {}", formatted))
                                    .font(FontId::proportional(16.0))
                                    .strong()
                                    .color(pal.primary),
                            );
                            if ui
                                .small_button(if self.german_ui {
                                    "In Rechner übernehmen"
                                } else {
                                    "Send to Calculator"
                                })
                                .clicked()
                            {
                                self.insert_token(&converted.to_string());
                                self.active_tab = MainTab::Calculator;
                            }
                        });
                    });

                ui.add_space(10.0);

                // 2. Material You & Niob Preferences (`res/xml/root_preferences.xml`)
                Frame::new()
                    .fill(pal.surface_container)
                    .corner_radius(CornerRadius::same(16))
                    .inner_margin(egui::Margin::same(12))
                    .show(ui, |ui| {
                        ui.label(
                            RichText::new(if self.german_ui {
                                "🎨 Material You Design & Darstellung"
                            } else {
                                "🎨 Material You Theme & Appearance"
                            })
                            .font(FontId::proportional(15.0))
                            .strong()
                            .color(pal.primary),
                        );
                        ui.add_space(6.0);

                        ui.horizontal(|ui| {
                            ui.label(if self.german_ui {
                                "Farbmodus:"
                            } else {
                                "Color Mode:"
                            });
                            for mode in [ThemeMode::Dark, ThemeMode::Light, ThemeMode::Amoled] {
                                let lbl = if self.german_ui {
                                    mode.label_de()
                                } else {
                                    mode.label_en()
                                };
                                ui.selectable_value(&mut self.theme_mode, mode, lbl);
                            }
                        });

                        ui.add_space(4.0);
                        ui.horizontal_wrapped(|ui| {
                            ui.label(if self.german_ui {
                                "Material You Akzentfarbe:"
                            } else {
                                "Material You Seed Palette:"
                            });
                            for seed in AccentSeed::ALL {
                                let selected = self.accent_seed == seed;
                                let btn = egui::Button::new(
                                    RichText::new(seed.name_de())
                                        .color(if selected {
                                            Color32::BLACK
                                        } else {
                                            pal.on_surface
                                        })
                                        .strong(),
                                )
                                .fill(if selected {
                                    seed.seed_color()
                                } else {
                                    pal.surface_container_high
                                })
                                .corner_radius(CornerRadius::same(12));
                                if ui.add(btn).clicked() {
                                    self.accent_seed = seed;
                                }
                            }
                        });

                        ui.add_space(6.0);
                        ui.horizontal(|ui| {
                            ui.label(if self.german_ui {
                                "Tastaturgröße:"
                            } else {
                                "Keyboard Size:"
                            });
                            ui.selectable_value(
                                &mut self.keyboard_scale,
                                0.9,
                                if self.german_ui { "Klein" } else { "Small" },
                            );
                            ui.selectable_value(
                                &mut self.keyboard_scale,
                                1.0,
                                if self.german_ui { "Standard" } else { "Default" },
                            );
                            ui.selectable_value(
                                &mut self.keyboard_scale,
                                1.12,
                                if self.german_ui { "Groß" } else { "Large" },
                            );
                        });
                    });

                ui.add_space(10.0);

                // 3. Calculation & Number Formatting Settings (`Lc/a/a/a;`)
                Frame::new()
                    .fill(pal.surface_container)
                    .corner_radius(CornerRadius::same(16))
                    .inner_margin(egui::Margin::same(12))
                    .show(ui, |ui| {
                        ui.label(
                            RichText::new(if self.german_ui {
                                "🧮 Rechenwerk & Zahlenformatierung"
                            } else {
                                "🧮 Math Engine & Number Formatting"
                            })
                            .font(FontId::proportional(15.0))
                            .strong()
                            .color(pal.primary),
                        );
                        ui.add_space(6.0);

                        let prev_nf = self.engine.number_format;
                        let is_comma = matches!(
                            self.engine.number_format,
                            NumberFormatMode::SpaceComma
                                | NumberFormatMode::PointComma
                                | NumberFormatMode::NoneComma
                        );
                        ui.horizontal_wrapped(|ui| {
                            ui.label(if self.german_ui {
                                "Dezimaltrennzeichen:"
                            } else {
                                "Decimal Separator:"
                            });
                            if ui
                                .selectable_label(
                                    is_comma,
                                    if self.german_ui {
                                        "Europa (Komma ,)"
                                    } else {
                                        "Europe (Comma ,)"
                                    },
                                )
                                .clicked()
                                && !is_comma
                            {
                                self.engine.number_format = match self.engine.number_format {
                                    NumberFormatMode::CommaPoint => NumberFormatMode::PointComma,
                                    NumberFormatMode::NonePoint => NumberFormatMode::NoneComma,
                                    _ => NumberFormatMode::SpaceComma,
                                };
                            }
                            if ui
                                .selectable_label(
                                    !is_comma,
                                    if self.german_ui {
                                        "US (Punkt .)"
                                    } else {
                                        "US (Point .)"
                                    },
                                )
                                .clicked()
                                && is_comma
                            {
                                self.engine.number_format = match self.engine.number_format {
                                    NumberFormatMode::PointComma => NumberFormatMode::CommaPoint,
                                    NumberFormatMode::NoneComma => NumberFormatMode::NonePoint,
                                    _ => NumberFormatMode::SpacePoint,
                                };
                            }
                        });

                        ui.horizontal_wrapped(|ui| {
                            ui.label(if self.german_ui {
                                "Zahlenformat (Tausendertrennzeichen & Dezimalzeichen):"
                            } else {
                                "Number Format:"
                            });
                            for nf in NumberFormatMode::ALL {
                                ui.selectable_value(
                                    &mut self.engine.number_format,
                                    nf,
                                    nf.example(),
                                );
                            }
                        });
                        if self.engine.number_format != prev_nf {
                            self.mark_state_dirty();
                        }

                        ui.add_space(4.0);
                        ui.checkbox(
                            &mut self.engine.show_exact_first,
                            if self.german_ui {
                                "Exakte Ergebnisse (Brüche, π, Wurzeln) im Verlauf zuerst anzeigen"
                            } else {
                                "Show exact results (fractions, π, surds) first in history"
                            },
                        );
                        if ui
                            .checkbox(
                                &mut self.engine.modulo_always_positive,
                                if self.german_ui {
                                    "Modulo-Ergebnis immer positiv (a mod b ≥ 0)"
                                } else {
                                    "Modulo result always positive (a mod b ≥ 0)"
                                },
                            )
                            .changed()
                        {
                            self.mark_state_dirty();
                        }
                        ui.checkbox(
                            &mut self.engine.auto_ans_insertion,
                            if self.german_ui {
                                "Automatisches Einfügen von ANS vor Operatoren (+, −, ×, ÷, ^)"
                            } else {
                                "Automatic ANS insertion before binary operators"
                            },
                        );
                        ui.checkbox(
                            &mut self.dual_keyboard,
                            if self.german_ui {
                                "Zweittastatur (Dual-Ansicht: Wissenschaftlich + Ziffernblock gleichzeitig)"
                            } else {
                                "Dual / Auxiliary Keyboard (Show scientific pad + number pad side by side)"
                            },
                        );
                        ui.checkbox(
                            &mut self.show_hex_row,
                            if self.german_ui {
                                "Hexadezimal-Zeile (A B C D E F) über dem Ziffernblock anzeigen"
                            } else {
                                "Show hexadecimal row (A B C D E F) above number pad"
                            },
                        );
                    });

                ui.add_space(10.0);

                // 4. Automated Original-App Compatibility & Bug Audit Suite (`verification.rs`)
                Frame::new()
                    .fill(pal.surface_container)
                    .corner_radius(CornerRadius::same(16))
                    .inner_margin(egui::Margin::same(12))
                    .show(ui, |ui| {
                        let checks = crate::verification::run_all_verification_checks();
                        let passed_count = checks.iter().filter(|c| c.passed).count();
                        ui.label(
                            RichText::new(format!(
                                "Kompatibilitäts- & Fehlerdiagnose ({}/{} Tests bestanden)",
                                passed_count,
                                checks.len()
                            ))
                            .font(FontId::proportional(15.0))
                            .strong()
                            .color(pal.primary),
                        );
                        ui.add_space(4.0);
                        for chk in checks {
                            let badge = match chk.kind {
                                crate::verification::FindingKind::MatchesOriginal => "1:1 Original",
                                crate::verification::FindingKind::RebuildBugFixed => "Nachbau-Fix",
                                crate::verification::FindingKind::OriginalAppBugDiagnosed => "Original-APK Bug",
                            };
                            ui.horizontal_wrapped(|ui| {
                                ui.label(
                                    RichText::new(format!("[{} | {}]", chk.id, badge))
                                        .font(FontId::monospace(11.5))
                                        .strong()
                                        .color(pal.primary),
                                );
                                ui.label(
                                    RichText::new(format!(
                                        "{} ({}) -> {} ( Soll: {} )",
                                        chk.category,
                                        chk.expression,
                                        chk.actual_behavior,
                                        chk.expected_behavior
                                    ))
                                    .font(FontId::proportional(12.5))
                                    .strong(),
                                );
                            });
                            ui.label(
                                RichText::new(chk.note)
                                    .font(FontId::proportional(11.5))
                                    .color(pal.on_surface_variant),
                            );
                            ui.add_space(4.0);
                        }
                    });
            });
    }
}

fn convert_temperature(v: f64, from: usize, to: usize) -> f64 {
    if from == to {
        return v;
    }
    let celsius = match from {
        0 => v,
        1 => v - 273.15,
        _ => (v - 32.0) * 5.0 / 9.0,
    };
    match to {
        0 => celsius,
        1 => celsius + 273.15,
        _ => celsius * 9.0 / 5.0 + 32.0,
    }
}

impl eframe::App for NiobCalculatorApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.poll_async_eval();
        let pal = MaterialYouPalette::generate(self.theme_mode, self.accent_seed);
        pal.apply(&ctx, self.theme_mode);

        self.handle_keyboard_input(&ctx);

        egui::Panel::top("material_you_top_bar")
            .frame(
                Frame::new()
                    .fill(pal.surface_container)
                    .inner_margin(egui::Margin::symmetric(14, 8))
                    .stroke(Stroke::new(1.0_f32, pal.outline_variant)),
            )
            .show_inside(ui, |ui| {
                self.render_top_bar(ui, &pal);
            });

        egui::Panel::bottom("material_you_bottom_nav")
            .frame(
                Frame::new()
                    .fill(pal.surface_container)
                    .inner_margin(egui::Margin::symmetric(12, 8))
                    .stroke(Stroke::new(1.0_f32, pal.outline_variant)),
            )
            .show_inside(ui, |ui| {
                self.render_bottom_nav(ui, &pal);
            });

        egui::CentralPanel::default()
            .frame(
                Frame::new()
                    .fill(pal.bg_surface)
                    .inner_margin(egui::Margin::same(12)),
            )
            .show_inside(ui, |ui| match self.active_tab {
                MainTab::Calculator => self.render_calculator_tab(ui, &pal),
                MainTab::Plotter => self.render_plotter_tab(ui, &pal),
                MainTab::VariablesAndConstants => {
                    self.render_variables_and_constants_tab(ui, &pal)
                }
                MainTab::ConverterAndSettings => self.render_converter_and_settings_tab(ui, &pal),
            });

        self.handle_screenshot(&ctx);
    }
}

fn parse_screenshot_arg() -> Option<PathBuf> {
    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        if args[i] == "--screenshot" && i + 1 < args.len() {
            return Some(PathBuf::from(&args[i + 1]));
        }
        if let Some(rest) = args[i].strip_prefix("--screenshot=") {
            return Some(PathBuf::from(rest));
        }
        i += 1;
    }
    None
}

fn main() -> eframe::Result {
    let screenshot_path = parse_screenshot_arg();
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Niob — Wissenschaftlicher Rechner (Material You)")
            .with_inner_size([960.0, 680.0])
            .with_min_inner_size([420.0, 520.0]),
        ..Default::default()
    };

    eframe::run_native(
        "Niob",
        options,
        Box::new(move |_cc| Ok(Box::new(NiobCalculatorApp::new(screenshot_path)))),
    )
}
