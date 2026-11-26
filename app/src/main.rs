mod constants;
mod engine;
mod mathview;
mod numdiff;
mod i18n;
mod theme;
#[cfg(test)]
mod verification;

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex};

type PreviewKey = (String, AngleMode, bool, u64);
type AsyncPreviewMsg = (PreviewKey, Result<EvalOutput, String>);
type AsyncCommitMsg = (String, MathEngine, Result<EvalOutput, String>);

/// Id der Formelzeile im Rechner (für Fokus und Cursorzustand).
const INPUT_FIELD_ID: &str = "niob_formula_input";

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
    Stroke, StrokeKind, Ui,
};

use crate::constants::{
     ASSIGNABLE_VARIABLE_SLOTS, ConstantCategory, DEFAULT_KEYBOARD_SLOTS, HISTORY_TERM_FONT_SIZE,
    INPUT_TERM_FONT_SIZE, INPUT_TERM_MIN_FONT_SIZE, KEYBOARD_CARD_CORNER_RADIUS,
    KEYBOARD_SECTION_BAR_HEIGHT, PLOTTER_AXIS_LABEL_SIZE, PLOTTER_MAJOR_GRID_STROKE,
    PLOTTER_MINOR_GRID_STROKE, SCIENTIFIC_CONSTANTS, USER_VARIABLES,
};
use crate::engine::{AngleMode, Complex, EvalOutput, MathEngine, NumberFormatMode, Value};
use crate::theme::{readable_on, AccentSeed, MaterialYouPalette, ThemeMode, PLOT_COLOR_COUNT};

/// Bedien-Einstellungen, die zusammen mit Verlauf und Variablen im
/// `state.json` landen (Befund: Theme, Sprache, Tastatur gingen bei jedem
/// Neustart verloren).
#[derive(Clone, Debug, PartialEq)]
struct UiPrefs {
    theme_mode: ThemeMode,
    accent_seed: AccentSeed,
    german_ui: bool,
    dual_keyboard: bool,
    show_hex_row: bool,
    keyboard_scale: f32,
    keyboard_slots: [String; ASSIGNABLE_VARIABLE_SLOTS],
    show_exact_first: bool,
    auto_ans_insertion: bool,
}

impl Default for UiPrefs {
    fn default() -> Self {
        Self {
            theme_mode: ThemeMode::Dark,
            accent_seed: AccentSeed::NiobEmerald,
            german_ui: true,
            dual_keyboard: true,
            show_hex_row: false,
            keyboard_scale: 1.0,
            keyboard_slots: DEFAULT_KEYBOARD_SLOTS.map(|s| s.to_string()),
            show_exact_first: true,
            auto_ans_insertion: true,
        }
    }
}

/// Die drei wählbaren Tastaturgrößen.
const KEYBOARD_SCALES: [f32; 3] = [0.9, 1.0, 1.12];

mod state_json {
    use super::{
        AccentSeed, AngleMode, Complex, HistoryItem, MathEngine, NumberFormatMode, ThemeMode, UiPrefs, Value,
        ASSIGNABLE_VARIABLE_SLOTS, KEYBOARD_SCALES, USER_VARIABLES,
    };
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

    pub(super) fn save_state_to_disk(engine: &MathEngine, history: &[HistoryItem], prefs: &UiPrefs) {
        let Some(path) = state_file_path() else {
            return;
        };
        if let Some(parent) = path.parent() {
            if std::fs::create_dir_all(parent).is_err() {
                return;
            }
        }
        let out = serialize_state_json(engine, history, prefs);
        write_state_json_atomic(&path, &out);
    }

    fn serialize_state_json(engine: &MathEngine, history: &[HistoryItem], prefs: &UiPrefs) -> String {
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
            out.push_str(",\"is_exact\":");
            out.push_str(if item.is_exact { "true" } else { "false" });
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
        out.push_str("\n  },\n  \"ui\": {\n    \"theme\": ");
        json_escape(prefs.theme_mode.key(), &mut out);
        out.push_str(",\n    \"accent\": ");
        json_escape(prefs.accent_seed.key(), &mut out);
        out.push_str(",\n    \"language\": ");
        json_escape(if prefs.german_ui { "de" } else { "en" }, &mut out);
        for (key, on) in [
            ("dual_keyboard", prefs.dual_keyboard),
            ("hex_row", prefs.show_hex_row),
            ("show_exact_first", prefs.show_exact_first),
            ("auto_ans_insertion", prefs.auto_ans_insertion),
        ] {
            out.push_str(&format!(",\n    \"{key}\": {on}"));
        }
        out.push_str(&format!(",\n    \"keyboard_scale\": {}", prefs.keyboard_scale));
        out.push_str(",\n    \"keyboard_slots\": [");
        for (i, slot) in prefs.keyboard_slots.iter().enumerate() {
            if i > 0 {
                out.push_str(", ");
            }
            json_escape(slot, &mut out);
        }
        out.push_str("]\n  }\n}\n");
        out
    }

    /// Gespeicherte Bedien-Einstellungen lesen; fehlende oder ungültige Felder
    /// behalten den Standardwert.
    fn decode_prefs(ui: &JsonVal) -> UiPrefs {
        let mut p = UiPrefs::default();
        if let Some(m) = ui.get("theme").and_then(JsonVal::as_str).and_then(ThemeMode::from_key) {
            p.theme_mode = m;
        }
        if let Some(a) = ui.get("accent").and_then(JsonVal::as_str).and_then(AccentSeed::from_key) {
            p.accent_seed = a;
        }
        if let Some(l) = ui.get("language").and_then(JsonVal::as_str) {
            p.german_ui = l != "en";
        }
        for (key, slot) in [
            ("dual_keyboard", &mut p.dual_keyboard),
            ("hex_row", &mut p.show_hex_row),
            ("show_exact_first", &mut p.show_exact_first),
            ("auto_ans_insertion", &mut p.auto_ans_insertion),
        ] {
            if let Some(v) = ui.get(key).and_then(JsonVal::as_bool) {
                *slot = v;
            }
        }
        if let Some(s) = ui.get("keyboard_scale").and_then(JsonVal::as_f64) {
            // Auf die nächste wählbare Größe einrasten
            p.keyboard_scale = KEYBOARD_SCALES
                .into_iter()
                .min_by(|a, b| ((*a as f64) - s).abs().total_cmp(&((*b as f64) - s).abs()))
                .unwrap_or(1.0);
        }
        if let Some(JsonVal::Arr(slots)) = ui.get("keyboard_slots") {
            let names: Vec<&str> = slots.iter().filter_map(JsonVal::as_str).collect();
            if names.len() == ASSIGNABLE_VARIABLE_SLOTS
                && names.iter().all(|n| !n.trim().is_empty() && n.chars().count() <= 24)
            {
                for (dst, src) in p.keyboard_slots.iter_mut().zip(names) {
                    *dst = src.to_string();
                }
            }
        }
        p
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

    /// Zustand laden. Liefert die gespeicherten Bedien-Einstellungen, falls vorhanden.
    pub(super) fn load_state_from_disk(engine: &mut MathEngine, history: &mut Vec<HistoryItem>) -> Option<UiPrefs> {
        let path = state_file_path()?;
        let raw = std::fs::read_to_string(path).ok()?;
        apply_state_json(&raw, engine, history)
    }

    fn apply_state_json(raw: &str, engine: &mut MathEngine, history: &mut Vec<HistoryItem>) -> Option<UiPrefs> {
        let mut parser = Parser { bytes: raw.as_bytes(), pos: 0 };
        let root = parser.parse_value(0)?;
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
        // Frühere Versionen speicherten die Vorbelegung a = 5, b = 3, x = 2
        // mit. In Dateien ohne Bedien-Einstellungen (also von vor dieser
        // Version) werden genau diese Werte einmalig entfernt; andere bleiben.
        if root.get("ui").is_none() {
            for (name, preset) in [("a", 5.0), ("b", 3.0), ("x", 2.0)] {
                if engine.variables.get(name).is_some_and(|v| v.to_f64() == preset) {
                    engine.variables.remove(name);
                }
            }
        }
        if let Some(JsonVal::Arr(items)) = root.get("history") {
            history.clear();
            for entry in items {
                let Some(raw_input) = entry.get("raw_input").and_then(JsonVal::as_str) else { continue };
                let exact_result = entry.get("exact_result").and_then(JsonVal::as_str).unwrap_or("").to_string();
                let decimal_result = entry.get("decimal_result").and_then(JsonVal::as_str).unwrap_or("").to_string();
                let reusable_result = entry.get("reusable_result").and_then(JsonVal::as_str).unwrap_or("").to_string();
                let has_distinct_exact = entry.get("has_distinct_exact").and_then(JsonVal::as_bool).unwrap_or(false);
                let is_exact = entry.get("is_exact").and_then(JsonVal::as_bool).unwrap_or_else(|| {
                    has_distinct_exact || super::is_plain_integer_str(&exact_result)
                });
                let showing_exact = entry.get("showing_exact").and_then(JsonVal::as_bool).unwrap_or(is_exact);
                let is_error = entry.get("is_error").and_then(JsonVal::as_bool).unwrap_or(false);
                history.push(HistoryItem {
                    raw_input: raw_input.to_string(),
                    exact_result,
                    decimal_result,
                    reusable_result,
                    has_distinct_exact,
                    is_exact,
                    showing_exact,
                    is_error,
                    integration_report: None,
                });
            }
        }
        root.get("ui").map(decode_prefs)
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
                is_exact: true,
                showing_exact: true,
                is_error: false,
                integration_report: None,
            }];

            let json = serialize_state_json(&engine, &history, &UiPrefs::default());
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
            assert!(!restored.variables.contains_key("m"));
            assert_eq!(restored_history.len(), 1);

            let _ = std::fs::remove_dir_all(&dir);
        }

        /// Befund aus der UI-Bewertung: Theme, Sprache und Tastatur gingen beim
        /// Neustart verloren.
        #[test]
        fn ui_preferences_survive_a_restart() {
            let mut prefs = UiPrefs {
                theme_mode: ThemeMode::Light,
                accent_seed: AccentSeed::GoldenAmber,
                german_ui: false,
                dual_keyboard: false,
                show_hex_row: true,
                keyboard_scale: 1.12,
                show_exact_first: false,
                auto_ans_insertion: false,
                ..UiPrefs::default()
            };
            prefs.keyboard_slots[2] = "c0".to_string();
            let json = serialize_state_json(&MathEngine::default(), &[], &prefs);
            let restored = apply_state_json(&json, &mut MathEngine::default(), &mut Vec::new());
            assert_eq!(restored, Some(prefs));
            // Alte Zustandsdateien ohne „ui“ laden weiter, ohne Einstellungen
            let legacy = "{\"angle_mode\": \"degrees\", \"history\": []}";
            let mut engine = MathEngine::default();
            assert_eq!(apply_state_json(legacy, &mut engine, &mut Vec::new()), None);
            assert_eq!(engine.angle_mode, AngleMode::Degrees);
        }

        /// Befund: Bestehende Installationen behielten die alte Vorbelegung.
        #[test]
        fn legacy_state_drops_only_the_old_variable_presets() {
            let mut engine = MathEngine::default(); // alte Vorbelegung a = 5, b = 3, x = 2
            engine.evaluate_and_commit("b := 7").unwrap();
            engine.evaluate_and_commit("c := 5").unwrap();
            let json = serialize_state_json(&engine, &[], &UiPrefs::default());
            let cut = json.find(",\n  \"ui\":").expect("ui-Abschnitt");
            let legacy = format!("{}\n}}\n", &json[..cut]);

            let mut restored = MathEngine::default();
            restored.variables.clear();
            apply_state_json(&legacy, &mut restored, &mut Vec::new());
            assert!(!restored.variables.contains_key("a"));
            assert!(!restored.variables.contains_key("x"));
            assert_eq!(restored.variables.get("b").map(|v| v.to_f64()), Some(7.0));
            assert_eq!(restored.variables.get("c").map(|v| v.to_f64()), Some(5.0));

            // Neues Format: gespeicherte Werte bleiben, auch wenn sie den alten Vorgaben gleichen
            let mut restored = MathEngine::default();
            restored.variables.clear();
            apply_state_json(&json, &mut restored, &mut Vec::new());
            assert_eq!(restored.variables.get("a").map(|v| v.to_f64()), Some(5.0));
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum MainTab {
    Calculator,
    Plotter,
    VariablesAndConstants,
    Settings,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum KeyboardSection {
    Numbers,      // 1 2 3
    Operators,    // ( ) √ ^
    Trigonometry, // sin
    AdvancedMath, // Σ ∫ i
    Variables,    // a b c
}

impl KeyboardSection {
    const ALL: [KeyboardSection; 5] = [
        Self::Numbers,
        Self::Operators,
        Self::Trigonometry,
        Self::AdvancedMath,
        Self::Variables,
    ];

    fn label(self) -> &'static str {
        match self {
            Self::Numbers => "1 2 3",
            Self::Operators => "( ) √ xⁿ",
            Self::Trigonometry => "sin cos",
            Self::AdvancedMath => "∑ ∫ i",
            Self::Variables => "a b c",
        }
    }

    fn tooltip(self, de: bool) -> &'static str {
        match (self, de) {
            (Self::Numbers, true) => "Ziffernblock",
            (Self::Numbers, false) => "Number pad",
            (Self::Operators, true) => "Klammern, Potenzen, Wurzeln, Logarithmen",
            (Self::Operators, false) => "Brackets, powers, roots, logarithms",
            (Self::Trigonometry, true) => "Winkel- und Hyperbelfunktionen",
            (Self::Trigonometry, false) => "Trigonometric and hyperbolic functions",
            (Self::AdvancedMath, true) => "Summen, Integrale, Ableitungen, Matrizen",
            (Self::AdvancedMath, false) => "Sums, integrals, derivatives, matrices",
            (Self::Variables, true) => "Variablen und belegbare Tastatur-Plätze",
            (Self::Variables, false) => "Variables and assignable keyboard slots",
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
    is_exact: bool,
    showing_exact: bool,
    is_error: bool,
    integration_report: Option<crate::engine::IntegrationReport>,
}

fn is_plain_integer_str(s: &str) -> bool {
    let rhs = s.split_once(":=").map(|(_, r)| r).unwrap_or(s).trim();
    let digits = rhs
        .strip_prefix('-')
        .or_else(|| rhs.strip_prefix('+'))
        .unwrap_or(rhs);
    !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit())
}

fn decimal_and_group_seps(nf: NumberFormatMode) -> (&'static str, char) {
    match nf {
        NumberFormatMode::SpacePoint => (" ", '.'),
        NumberFormatMode::CommaPoint => (",", '.'),
        NumberFormatMode::NonePoint => ("", '.'),
        NumberFormatMode::SpaceComma => (" ", ','),
        NumberFormatMode::PointComma => (".", ','),
        NumberFormatMode::NoneComma => ("", ','),
    }
}

fn group_exact_integer_desktop(int_str: &str, nf: NumberFormatMode) -> String {
    let (sign, digits) = if let Some(r) = int_str.strip_prefix('-') {
        ("-", r)
    } else if let Some(r) = int_str.strip_prefix('+') {
        ("+", r)
    } else {
        ("", int_str)
    };
    let (group_sep, _) = decimal_and_group_seps(nf);
    let sep = if group_sep.is_empty() && digits.len() > 6 {
        " "
    } else {
        group_sep
    };
    if digits.len() <= 3 || sep.is_empty() {
        return format!("{sign}{digits}");
    }
    let mut out = String::with_capacity(digits.len() + digits.len() / 3 + 2);
    out.push_str(sign);
    for (i, ch) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 {
            out.push_str(sep);
        }
        out.push(ch);
    }
    out
}

fn group_frac_3(frac: &str) -> String {
    let mut out = String::with_capacity(frac.len() + frac.len() / 3);
    for (i, ch) in frac.chars().enumerate() {
        if i > 0 && i % 3 == 0 {
            out.push(' ');
        }
        out.push(ch);
    }
    out
}

fn format_exact_history_display(raw: &str, nf: NumberFormatMode) -> String {
    if raw.contains('[') {
        return raw.to_string();
    }
    if let Some((lhs, rhs)) = raw.split_once(":=") {
        return format!(
            "{} := {}",
            lhs.trim(),
            format_exact_history_display(rhs.trim(), nf)
        );
    }
    let trimmed = raw.trim();
    if is_plain_integer_str(trimmed) {
        return group_exact_integer_desktop(trimmed, nf);
    }
    let (_, dec_sep) = decimal_and_group_seps(nf);
    if let Some((mant, exp_s)) = trimmed.split_once('e').or_else(|| trimmed.split_once('E')) {
        if let Ok(exp) = exp_s.parse::<i32>() {
            let (m_int, m_frac) = mant.split_once('.').unwrap_or((mant, ""));
            let (sign, u_int) = if let Some(r) = m_int.strip_prefix('-') {
                ("-", r)
            } else {
                ("", m_int)
            };
            if u_int.bytes().all(|b| b.is_ascii_digit())
                && m_frac.bytes().all(|b| b.is_ascii_digit())
            {
                return if m_frac.is_empty() {
                    format!("{sign}{u_int} × 10{}", crate::engine::to_superscript(exp))
                } else {
                    format!(
                        "{sign}{u_int}{dec_sep}{} × 10{}",
                        group_frac_3(m_frac),
                        crate::engine::to_superscript(exp)
                    )
                };
            }
        }
    }
    trimmed.to_string()
}

fn format_decimal_history_display(raw: &str, nf: NumberFormatMode) -> String {
    if raw.contains('[') {
        return raw.to_string();
    }
    if let Some((lhs, rhs)) = raw.split_once(":=") {
        return format!(
            "{} := {}",
            lhs.trim(),
            format_decimal_history_display(rhs.trim(), nf)
        );
    }
    let trimmed = raw.trim();
    let (_, dec_sep) = decimal_and_group_seps(nf);
    let unsigned = trimmed
        .strip_prefix('-')
        .or_else(|| trimmed.strip_prefix('+'))
        .unwrap_or(trimmed);
    if unsigned.len() >= 7 && unsigned.bytes().all(|b| b.is_ascii_digit()) {
        let sign = if trimmed.starts_with('-') { "-" } else { "" };
        let exp = (unsigned.len() - 1) as i32;
        let round_idx = 7;
        let mut digits: Vec<u8> = unsigned.bytes().take(round_idx).map(|b| b - b'0').collect();
        let next_digit = unsigned.as_bytes().get(round_idx).copied().unwrap_or(b'0') - b'0';
        let mut final_exp = exp;
        if next_digit >= 5 {
            let mut carry = 1u8;
            for d in digits.iter_mut().rev() {
                let s = *d + carry;
                *d = s % 10;
                carry = s / 10;
                if carry == 0 {
                    break;
                }
            }
            if carry > 0 {
                digits.insert(0, carry);
                digits.pop();
                final_exp += 1;
            }
        }
        let lead = digits[0];
        let frac: String = digits[1..7].iter().map(|d| (b'0' + *d) as char).collect();
        return format!(
            "{sign}{lead}{dec_sep}{} {} × 10{}",
            &frac[0..3],
            &frac[3..6],
            crate::engine::to_superscript(final_exp)
        );
    }
    trimmed.to_string()
}

/// Wie die Live-Vorschau eine Fehlermeldung der Engine zeigt.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PreviewError {
    /// Eingabe ist vermutlich nur noch nicht fertig („2+“, „sin(“).
    Incomplete,
    /// Echter Fehler, Meldung anzeigen (Division durch Null, unbekannte Funktion …).
    Show,
}

/// `engine_input` ist die Eingabe in Engine-Schreibweise.
fn classify_preview_error(err: &str, engine_input: &str) -> PreviewError {
    const MATH_ERRORS: [&str; 4] = ["Wert zu groß", "Überlauf", "Division durch Null", "nicht definiert"];
    if MATH_ERRORS.iter().any(|m| err.contains(m)) {
        return PreviewError::Show;
    }
    if err.starts_with("Unbekannte Funktion") {
        return PreviewError::Show;
    }
    // Falsche Argumentzahl bei geschlossenem Aufruf, z. B. log(). Bei noch
    // offener Klammer („sin(“, „nthroot(3, “) meldet die Engine dasselbe.
    if err.contains("Argument") && (err.contains("benötigt") || err.contains("erwartet")) {
        let t = engine_input.trim_end();
        let closed = t.matches('(').count() <= t.matches(')').count() && !t.ends_with(',');
        return if closed {
            PreviewError::Show
        } else {
            PreviewError::Incomplete
        };
    }
    if let Some(name) = err
        .strip_prefix("Unbekannte Variable oder Konstante '")
        .and_then(|r| r.strip_suffix('\''))
    {
        // Steht der Name ganz am Ende, wird er womöglich gerade noch getippt
        // (`s` → `si` → `sin(`). Sonst ist es ein Tippfehler.
        let t = engine_input.trim_end();
        let still_typing = t.ends_with(name)
            && t[..t.len() - name.len()]
                .chars()
                .last()
                .is_none_or(|c| !(c.is_alphanumeric() || c == '_'));
        return if still_typing {
            PreviewError::Incomplete
        } else {
            PreviewError::Show
        };
    }
    if err.starts_with("Unbekannte Variable") {
        return PreviewError::Show;
    }
    PreviewError::Incomplete
}

/// `12` → `₁₂` (für die Funktionsnamen f₁, f₂ … im Plotter).
fn subscript_number(n: usize) -> String {
    n.to_string()
        .chars()
        .map(|c| char::from_u32(0x2080 + c.to_digit(10).unwrap_or(0)).unwrap_or(c))
        .collect()
}

/// Statuszeile einer Plotter-Funktion an der Spurposition `x`:
/// Funktionswert und Ableitung, oder ein eindeutiger Hinweis, wenn die Eingabe
/// fehlerhaft ist, die Funktion an der Stelle nicht definiert ist oder dort
/// keine endliche zweiseitige Ableitung existiert. Zweites Feld: Fehler.
#[cfg(test)]
fn plot_readout(engine: &MathEngine, display_expr: &str, x: f64, de: bool) -> (String, bool) {
    plot_readout_named(engine, display_expr, x, de, "f")
}

/// Wie [`plot_readout`], mit dem Funktionsnamen der Zeile (f₁, f₂ …), damit
/// die Statuszeilen mehrerer Kurven unterscheidbar sind.
fn plot_readout_named(engine: &MathEngine, display_expr: &str, x: f64, de: bool, name: &str) -> (String, bool) {
    let expr = mathview::to_engine_syntax(display_expr);
    if expr.trim().is_empty() {
        return (String::new(), false);
    }
    // Eingabefehler (Syntax, unbekannte Namen) unabhängig von der Spurposition
    if let Err(err) = engine.validate_expression(&expr, &["x"]) {
        return (format!("⚠ {}", i18n::localize_error(&err, de)), true);
    }
    let fx = engine.eval_for_x_result(&expr, x);
    let x_txt = readout_number(x, 2);
    let Ok(y) = fx else {
        return (
            if de {
                format!("{name}({x_txt}) nicht definiert")
            } else {
                format!("{name}({x_txt}) undefined")
            },
            false,
        );
    };
    let derivative = match numdiff::derivative_at(|t| engine.eval_for_x(&expr, t), x) {
        Some(numdiff::Derivative::Value(v)) => format!("{name}'({x_txt}) = {}", readout_number(v, 4)),
        _ if de => format!("{name}'({x_txt}) existiert nicht"),
        _ => format!("{name}'({x_txt}) does not exist"),
    };
    (format!("{name}({x_txt}) = {}   |   {derivative}", readout_number(y, 4)), false)
}

/// Zahl für die Plotter-Statuszeile: höchstens `decimals` Nachkommastellen und
/// höchstens 8 gültige Stellen (mehr liefert die numerische Ableitung nicht
/// verlässlich); sehr kleine und sehr große Beträge in Exponentenschreibweise
/// (sonst zeigt x = 10⁻⁷ „0.00“).
fn readout_number(v: f64, decimals: usize) -> String {
    let a = v.abs();
    if v == 0.0 || (1e-2..1e7).contains(&a) {
        let int_digits = if a >= 1.0 { a.log10().floor() as usize + 1 } else { 1 };
        let dec = decimals.min(8usize.saturating_sub(int_digits));
        format!("{:.*}", dec, v)
    } else {
        format!("{:.*e}", decimals, v)
    }
}

#[derive(Clone, Debug)]
struct PlotFunction {
    expr: String,
    visible: bool,
    /// Index in die Plotfarben der Palette (passt sich dem Farbmodus an).
    color_idx: usize,
}

/// Höchstzahl der Funktionen im Plotter.
const MAX_PLOT_FUNCTIONS: usize = PLOT_COLOR_COUNT;
/// Gemeinsame Zoomgrenzen für Knöpfe und Mausrad (Pixel je Einheit).
const PLOT_SCALE_RANGE: (f64, f64) = (0.01, 100_000.0);

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

    /// Einheiten als (deutscher Name, englischer Name, Faktor zur Basiseinheit).
    fn units(self) -> &'static [(&'static str, &'static str, f64)] {
        match self {
            Self::Length => &[
                ("Meter (m)", "Meter (m)", 1.0),
                ("Kilometer (km)", "Kilometer (km)", 1000.0),
                ("Zentimeter (cm)", "Centimeter (cm)", 0.01),
                ("Millimeter (mm)", "Millimeter (mm)", 0.001),
                ("Mikrometer (µm)", "Micrometer (µm)", 1e-6),
                ("Nanometer (nm)", "Nanometer (nm)", 1e-9),
                ("Meile (mi)", "Mile (mi)", 1609.344),
                ("Fuß (ft)", "Foot (ft)", 0.3048),
                ("Zoll (in)", "Inch (in)", 0.0254),
            ],
            Self::Mass => &[
                ("Kilogramm (kg)", "Kilogram (kg)", 1.0),
                ("Gramm (g)", "Gram (g)", 0.001),
                ("Milligramm (mg)", "Milligram (mg)", 1e-6),
                ("Tonne (t)", "Tonne (t)", 1000.0),
                ("Pfund (lb)", "Pound (lb)", 0.45359237),
                ("Unze (oz)", "Ounce (oz)", 0.0283495231),
            ],
            Self::Temperature => &[
                ("Celsius (°C)", "Celsius (°C)", 1.0),
                ("Kelvin (K)", "Kelvin (K)", 1.0),
                ("Fahrenheit (°F)", "Fahrenheit (°F)", 1.0),
            ],
            Self::Speed => &[
                ("Meter/Sekunde (m/s)", "Meters/second (m/s)", 1.0),
                ("Kilometer/Stunde (km/h)", "Kilometers/hour (km/h)", 1.0 / 3.6),
                ("Meilen/Stunde (mph)", "Miles/hour (mph)", 0.44704),
                ("Knoten (kn)", "Knot (kn)", 0.514444),
                ("Lichtgeschwindigkeit (c₀)", "Speed of light (c₀)", crate::constants::SPEED_OF_LIGHT),
            ],
            Self::Energy => &[
                ("Joule (J)", "Joule (J)", 1.0),
                ("Kilojoule (kJ)", "Kilojoule (kJ)", 1000.0),
                ("Wattstunde (Wh)", "Watt-hour (Wh)", 3600.0),
                ("Kilowattstunde (kWh)", "Kilowatt-hour (kWh)", 3_600_000.0),
                ("Elektronenvolt (eV)", "Electronvolt (eV)", crate::constants::ELECTRON_CHARGE),
                ("Kalorie (cal)", "Calorie (cal)", 4.184),
            ],
            Self::Angle => &[
                ("Radiant (rad)", "Radian (rad)", 1.0),
                ("Grad (°)", "Degree (°)", std::f64::consts::PI / 180.0),
                ("Gon (grad)", "Gradian (grad)", std::f64::consts::PI / 200.0),
            ],
            Self::Data => &[
                ("Byte (B)", "Byte (B)", 1.0),
                ("Kilobyte (KB)", "Kilobyte (KB)", 1000.0),
                ("Megabyte (MB)", "Megabyte (MB)", 1e6),
                ("Gigabyte (GB)", "Gigabyte (GB)", 1e9),
                ("Kibibyte (KiB)", "Kibibyte (KiB)", 1024.0),
                ("Mebibyte (MiB)", "Mebibyte (MiB)", 1_048_576.0),
            ],
        }
    }
}

struct NiobCalculatorApp {
    // Core engine & state
    engine: MathEngine,
    /// Eingabe in Anzeige-Schreibweise (`√(2)÷2`); für die Engine siehe [`Self::engine_input`].
    input_expr: String,
    /// Auswahl in der Formelzeile als Zeichenindizes (Start, Ende); leer = Cursor.
    input_sel: (usize, usize),
    /// Cursor, der beim nächsten Zeichnen ins Eingabefeld übernommen wird.
    pending_cursor: Option<usize>,
    /// Höhe der Eingabekarte im letzten Frame (für die Platzaufteilung).
    last_input_bar_height: f32,
    /// Hatte die Formelzeile im letzten Frame den Fokus? (für Escape)
    input_had_focus: bool,
    /// Sicherheitsabfrage für „Verlauf leeren“ sichtbar.
    confirm_clear_history: bool,
    history: Vec<HistoryItem>,
    active_tab: MainTab,
    active_section: KeyboardSection,
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
    /// Rückmeldung des Variablen-Editors (Text, ist Fehler).
    var_status: Option<(String, bool)>,
    /// Rückmeldung nach „Auf Tastatur“ in der Konstantenliste.
    const_status: Option<String>,
    /// Hinweis im Plotter (z. B. wenn eine Funktion ersetzt wurde).
    plot_notice: Option<String>,
    /// Zuletzt gespeicherte Bedien-Einstellungen (Änderungen werden gespeichert).
    last_saved_prefs: Option<UiPrefs>,
    /// Sprache, für die der Fenstertitel zuletzt gesetzt wurde.
    window_title_de: Option<bool>,

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
        // Keine stillen Vorbelegungen (a = 5, b = 3, x = 2): `x+1` soll bei
        // einer frischen Installation nicht einfach 3 ergeben.
        engine.variables.clear();
        let mut history = Vec::new();
        let mut input_expr = String::new();
        let mut prefs = UiPrefs::default();

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
                        is_exact: out.is_exact,
                        showing_exact: out.is_exact && engine.show_exact_first,
                        is_error: false,
                        integration_report: out.integration_report,
                    });
                }
            }
            input_expr = mathview::to_display_syntax("sqrt(2)/2 + sin(pi/4)");
        } else {
            let first_start = state_json::state_file_path().is_none_or(|p| !p.exists());
            if let Some(saved) = state_json::load_state_from_disk(&mut engine, &mut history) {
                prefs = saved;
            }
            // Erster Start mit deutscher Oberfläche: Dezimalkomma statt Punkt.
            // Danach gilt, was gespeichert ist.
            if first_start && prefs.german_ui {
                engine.number_format = NumberFormatMode::SpaceComma;
            }
        }
        engine.show_exact_first = prefs.show_exact_first;
        engine.auto_ans_insertion = prefs.auto_ans_insertion;

        let mut app = Self {
            engine,
            input_sel: (input_expr.chars().count(), input_expr.chars().count()),
            pending_cursor: Some(input_expr.chars().count()),
            input_expr,
            last_input_bar_height: 84.0,
            input_had_focus: false,
            confirm_clear_history: false,
            history,
            active_tab: MainTab::Calculator,
            active_section: KeyboardSection::Operators,
            dual_keyboard: prefs.dual_keyboard,
            show_hex_row: prefs.show_hex_row,
            keyboard_scale: prefs.keyboard_scale,
            german_ui: prefs.german_ui,
            theme_mode: prefs.theme_mode,
            accent_seed: prefs.accent_seed,
            keyboard_slots: prefs.keyboard_slots.clone(),
            selected_slot_to_assign: 0,
            constants_search: String::new(),
            var_edit_name: "a".to_string(),
            var_edit_value: String::new(),
            var_status: None,
            const_status: None,
            plot_notice: None,
            last_saved_prefs: None,
            window_title_de: None,
            plot_functions: vec![
                PlotFunction {
                    expr: mathview::to_display_syntax("sin(x) * cos(x/2)"),
                    visible: true,
                    color_idx: 0,
                },
                PlotFunction {
                    expr: mathview::to_display_syntax("x^2 / 4 - 2"),
                    visible: true,
                    color_idx: 1,
                },
                PlotFunction {
                    expr: mathview::to_display_syntax("exp(-x^2 / 3) * 2"),
                    visible: true,
                    color_idx: 2,
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
        };
        app.last_saved_prefs = Some(app.ui_prefs());
        app
    }

    /// Aktuelle Bedien-Einstellungen für die Zustandsdatei.
    fn ui_prefs(&self) -> UiPrefs {
        UiPrefs {
            theme_mode: self.theme_mode,
            accent_seed: self.accent_seed,
            german_ui: self.german_ui,
            dual_keyboard: self.dual_keyboard,
            show_hex_row: self.show_hex_row,
            keyboard_scale: self.keyboard_scale,
            keyboard_slots: self.keyboard_slots.clone(),
            show_exact_first: self.engine.show_exact_first,
            auto_ans_insertion: self.engine.auto_ans_insertion,
        }
    }

    fn save_state(&mut self) {
        let prefs = self.ui_prefs();
        if self.screenshot_path.is_none() {
            state_json::save_state_to_disk(&self.engine, &self.history, &prefs);
        }
        self.last_saved_prefs = Some(prefs);
    }

    fn mark_state_dirty(&mut self) {
        self.eval_revision = self.eval_revision.wrapping_add(1);
        self.save_state();
    }

    /// Geänderte Bedien-Einstellungen sofort speichern.
    fn persist_prefs_if_changed(&mut self) {
        if self.last_saved_prefs.as_ref() != Some(&self.ui_prefs()) {
            self.save_state();
        }
    }

    /// Eingabe in Engine-Schreibweise (`√(2)÷2` → `sqrt(2)/2`).
    fn engine_input(&self) -> String {
        mathview::to_engine_syntax(self.input_expr.trim())
    }

    fn set_cursor(&mut self, pos: usize) {
        self.input_sel = (pos, pos);
        self.pending_cursor = Some(pos);
    }

    fn clear_input(&mut self) {
        self.input_expr.clear();
        self.set_cursor(0);
    }

    /// Auswahl, auf die aktuelle Länge begrenzt und sortiert.
    fn selection(&self) -> (usize, usize) {
        let len = self.input_expr.chars().count();
        let (a, b) = self.input_sel;
        (a.min(b).min(len), a.max(b).min(len))
    }

    /// Text links vom Cursor bzw. von der Auswahl.
    fn text_before_cursor(&self) -> &str {
        let end = mathview::char_to_byte(&self.input_expr, self.selection().0);
        &self.input_expr[..end]
    }

    /// Eingabe in Anzeige-Schreibweise bringen und den Cursor mitführen.
    fn normalize_input(&mut self, cursor: usize) {
        let (normalized, map) = mathview::to_display_syntax_with_map(&self.input_expr);
        let pos = map.get(cursor).copied().unwrap_or(normalized.chars().count());
        self.input_expr = normalized;
        self.set_cursor(pos);
    }

    /// Token an der Cursorposition einfügen; eine Auswahl wird ersetzt.
    fn insert_token(&mut self, token: &str) {
        let mut text = String::new();
        if self.input_expr.is_empty()
            && self.engine.auto_ans_insertion
            && matches!(token, "+" | "-" | "*" | "/" | "^")
        {
            text.push_str("ans");
        }
        text.push_str(token);
        let text = mathview::to_display_syntax(&text);
        let (start, end) = self.selection();
        let b0 = mathview::char_to_byte(&self.input_expr, start);
        let b1 = mathview::char_to_byte(&self.input_expr, end);
        self.input_expr.replace_range(b0..b1, &text);
        self.normalize_input(start + text.chars().count());
    }

    /// Löschen links vom Cursor; mehrteilige Funktionsnamen wie `sin(` am Stück.
    /// Eine Auswahl wird komplett gelöscht.
    fn backspace(&mut self) {
        let (start, end) = self.selection();
        if start != end {
            let b0 = mathview::char_to_byte(&self.input_expr, start);
            let b1 = mathview::char_to_byte(&self.input_expr, end);
            self.input_expr.replace_range(b0..b1, "");
            self.set_cursor(start);
            return;
        }
        if start == 0 {
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
        let before = self.text_before_cursor().to_string();
        let mut remove = 1;
        for tok in MULTI_CHAR_TOKENS {
            let disp = mathview::to_display_syntax(tok);
            if before.ends_with(&disp) {
                remove = disp.chars().count();
                break;
            }
        }
        let new_start = start - remove.min(start);
        let b0 = mathview::char_to_byte(&self.input_expr, new_start);
        let b1 = mathview::char_to_byte(&self.input_expr, start);
        self.input_expr.replace_range(b0..b1, "");
        self.set_cursor(new_start);
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
                    is_exact: out.is_exact,
                    showing_exact: out.is_exact && self.engine.show_exact_first,
                    is_error: false,
                    integration_report: out.integration_report,
                });
                self.clear_input();
            }
            Err(err_msg) => {
                self.history.push(HistoryItem {
                    raw_input: trimmed,
                    reusable_result: String::new(),
                    exact_result: err_msg.clone(),
                    decimal_result: err_msg,
                    has_distinct_exact: false,
                    is_exact: false,
                    showing_exact: false,
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
        let trimmed = self.engine_input();
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
        // Escape leert die Formelzeile. egui nimmt ihr bei Escape schon zu
        // Beginn des Frames den Fokus; da sie ihn gleich unten zurückbekommt,
        // sähe das Textfeld selbst den Fokusverlust nie.
        if self.input_had_focus && ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            self.clear_input();
        }
        // Hat kein Widget den Fokus, bekommt ihn die Formelzeile. Sie übernimmt
        // dann Tippen, Cursor, Auswahl, Einfügen und Enter selbst.
        if ctx.memory(|m| m.focused().is_none()) {
            ctx.memory_mut(|m| m.request_focus(egui::Id::new(INPUT_FIELD_ID)));
        }
    }

    fn handle_screenshot(&mut self, ctx: &egui::Context) {
        let Some(path) = self.screenshot_path.clone() else {
            return;
        };

        self.frame_count += 1;
        ctx.request_repaint();

        if self.frame_count >= 120 && (!self.screenshot_requested || self.frame_count.is_multiple_of(15)) {
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
        // Schmale Fenster: kurzer Titel, Knöpfe nur mit Symbol. Akzentfarbe und
        // Tastaturlayout stehen nur noch in den Einstellungen.
        let compact = ui.available_width() < 640.0;
        let de = self.german_ui;
        ui.horizontal(|ui| {
            ui.label(
                RichText::new(match (compact, de) {
                    (true, _) => "Niob",
                    (false, true) => "Niob Rechner",
                    (false, false) => "Niob Calculator",
                })
                .font(FontId::proportional(17.5))
                .strong()
                .color(pal.on_surface),
            );

            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(if de { "DE" } else { "EN" })
                                .font(FontId::proportional(12.0))
                                .strong(),
                        )
                        .corner_radius(CornerRadius::same(10)),
                    )
                    .on_hover_text(if de {
                        "Sprache: Deutsch – klicken für Englisch"
                    } else {
                        "Language: English – click for German"
                    })
                    .clicked()
                {
                    self.german_ui = !de;
                }

                let icon = match self.theme_mode {
                    ThemeMode::Dark => "🌙",
                    ThemeMode::Light => "☀",
                    ThemeMode::Amoled => "⬛",
                };
                let mode_label = if compact {
                    icon.to_string()
                } else {
                    format!("{icon} {}", self.theme_mode.short_label(de))
                };
                if ui
                    .add(
                        egui::Button::new(RichText::new(mode_label).font(FontId::proportional(12.0)))
                            .corner_radius(CornerRadius::same(10)),
                    )
                    .on_hover_text(if de {
                        "Farbmodus wechseln (Dunkel → Hell → AMOLED)"
                    } else {
                        "Switch color mode (Dark → Light → AMOLED)"
                    })
                    .clicked()
                {
                    self.theme_mode = match self.theme_mode {
                        ThemeMode::Dark => ThemeMode::Light,
                        ThemeMode::Light => ThemeMode::Amoled,
                        ThemeMode::Amoled => ThemeMode::Dark,
                    };
                }

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
                    .on_hover_text(if de {
                        "Winkelmaß: Bogenmaß (RAD) / Gradmaß (DEG) umschalten"
                    } else {
                        "Angle unit: toggle radians (RAD) / degrees (DEG)"
                    })
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

    fn tab_label(&self, tab: MainTab, compact: bool) -> &'static str {
        match (tab, self.german_ui, compact) {
            (MainTab::Calculator, true, _) => "Rechner",
            (MainTab::Calculator, false, _) => "Calculator",
            (MainTab::Plotter, true, false) => "2D-Plotter",
            (MainTab::Plotter, true, true) => "Plotter",
            (MainTab::Plotter, false, _) => "Graphing",
            (MainTab::VariablesAndConstants, true, false) => "Konstanten & Einheiten",
            (MainTab::VariablesAndConstants, true, true) => "Konstanten",
            (MainTab::VariablesAndConstants, false, false) => "Constants & Units",
            (MainTab::VariablesAndConstants, false, true) => "Constants",
            (MainTab::Settings, true, _) => "Einstellungen",
            (MainTab::Settings, false, _) => "Settings",
        }
    }

    fn render_bottom_nav(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let compact = ui.available_width() < 640.0;
        let tabs = [
            MainTab::Calculator,
            MainTab::Plotter,
            MainTab::VariablesAndConstants,
            MainTab::Settings,
        ];
        let titles = tabs.map(|t| (self.tab_label(t, compact), self.tab_label(t, false)));
        let font = if compact { 12.5 } else { 13.5 };
        ui.columns(tabs.len(), |cols| {
            for (i, tab) in tabs.iter().enumerate() {
                let is_active = self.active_tab == *tab;
                let (fill, text_col) = if is_active {
                    (pal.primary_container, pal.on_primary_container)
                } else {
                    (Color32::TRANSPARENT, pal.on_surface_variant)
                };
                cols[i].vertical_centered(|ui| {
                    ui.spacing_mut().button_padding.x = if compact { 4.0 } else { 10.0 };
                    // Nie mitten im Wort umbrechen („Einstellunge|n“), notfalls kürzen
                    let btn = egui::Button::new(
                        RichText::new(titles[i].0)
                            .font(FontId::proportional(font))
                            .strong()
                            .color(text_col),
                    )
                    .wrap_mode(egui::TextWrapMode::Truncate)
                    .fill(fill)
                    .stroke(if is_active {
                        Stroke::new(1.5_f32, pal.primary)
                    } else {
                        Stroke::NONE
                    })
                    .corner_radius(CornerRadius::same(18))
                    .min_size(egui::vec2(ui.available_width() * 0.94, 34.0));
                    if ui.add(btn).on_hover_text(titles[i].1).clicked() {
                        self.active_tab = *tab;
                    }
                });
            }
        });
    }

    fn render_calculator_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        // Platzaufteilung: Eingabekarte und Tastatur haben Vorrang. Der Verlauf
        // bekommt nur, was übrig bleibt, und wird bei sehr kleinen Fenstern
        // ganz ausgeblendet, damit alle Rechentasten sichtbar bleiben.
        const GAP: f32 = 6.0;
        const HISTORY_FRAME_EXTRA: f32 = 22.0; // Innenabstand 2 × 10 + Rand
        const HISTORY_MIN_BODY: f32 = 70.0; // Kopfzeile + ein Eintrag
        const KEYBOARD_MIN_CARD: f32 = 225.0;
        let total_h = ui.available_height();
        let kb_target = (total_h * 0.47 * self.keyboard_scale).clamp(KEYBOARD_MIN_CARD, 440.0);
        let history_body =
            total_h - self.last_input_bar_height - kb_target - 2.0 * GAP - HISTORY_FRAME_EXTRA;
        let show_history = history_body >= HISTORY_MIN_BODY;

        if show_history {
            self.render_history(ui, pal, history_body);
            ui.add_space(GAP);
        }

        let input_card = Frame::new()
            .fill(pal.surface_container)
            .corner_radius(CornerRadius::same(16))
            .inner_margin(egui::Margin::symmetric(12, 8))
            .stroke(Stroke::new(1.5_f32, pal.primary))
            .show(ui, |ui| {
                self.render_input_line(ui, pal);
                self.render_preview_line(ui, pal);
            });
        self.last_input_bar_height = input_card.response.rect.height();

        ui.add_space(GAP);

        let de = self.german_ui;
        Frame::new()
            .fill(pal.surface_container)
            .corner_radius(CornerRadius::same(KEYBOARD_CARD_CORNER_RADIUS as u8))
            .inner_margin(egui::Margin::same(10))
            .stroke(Stroke::new(1.0_f32, pal.outline_variant))
            .show(ui, |ui| {
                // Im Dual-Modus ist der Ziffernblock immer rechts zu sehen; sein
                // Reiter entfällt, damit nur ein Reiter als aktiv erscheint.
                if self.dual_keyboard && self.active_section == KeyboardSection::Numbers {
                    self.active_section = KeyboardSection::Operators;
                }
                let sections: Vec<KeyboardSection> = KeyboardSection::ALL
                    .into_iter()
                    .filter(|s| !(self.dual_keyboard && *s == KeyboardSection::Numbers))
                    .collect();
                ui.allocate_ui_with_layout(
                    egui::vec2(ui.available_width(), KEYBOARD_SECTION_BAR_HEIGHT),
                    Layout::left_to_right(Align::Center),
                    |ui| {
                        ui.columns(sections.len(), |cols| {
                            for (i, sec) in sections.iter().enumerate() {
                                let active = self.active_section == *sec;
                                let (fill, fg) = if active {
                                    (pal.primary, pal.on_primary)
                                } else {
                                    (pal.surface_container_high, pal.on_surface)
                                };
                                cols[i].vertical_centered(|ui| {
                                    let btn = egui::Button::new(egui::WidgetText::from(mathview::rich_job(
                                        sec.label(),
                                        FontId::proportional(13.0),
                                        fg,
                                    )))
                                    .wrap_mode(egui::TextWrapMode::Extend)
                                    .fill(fill)
                                    .corner_radius(CornerRadius::same(14))
                                    .min_size(egui::vec2(
                                        ui.available_width() * 0.95,
                                        KEYBOARD_SECTION_BAR_HEIGHT - 4.0,
                                    ));
                                    if ui.add(btn).on_hover_text(sec.tooltip(de)).clicked() {
                                        self.active_section = *sec;
                                    }
                                });
                            }
                        });
                    },
                );

                ui.add_space(4.0);

                // Hex-Zeile über die volle Breite, damit beide Tastenhälften gleich
                // hoch bleiben und ihre Reihen bündig abschließen.
                if self.show_hex_row && (self.dual_keyboard || self.active_section == KeyboardSection::Numbers) {
                    let h = (ui.available_height() * 0.16).clamp(26.0, 44.0);
                    self.render_hex_row(ui, h, pal);
                }

                if self.dual_keyboard {
                    let sci_sec = self.active_section;
                    ui.columns(2, |cols| {
                        self.render_keyboard_section(&mut cols[0], sci_sec, pal);
                        self.render_keyboard_section(&mut cols[1], KeyboardSection::Numbers, pal);
                    });
                } else {
                    self.render_keyboard_section(ui, self.active_section, pal);
                }
            });
    }

    /// Hex-Ziffern A–F (`keyboard_hex_number_section.xml`).
    fn render_hex_row(&mut self, ui: &mut Ui, h: f32, pal: &MaterialYouPalette) {
        ui.columns(6, |cols| {
            for (i, hex_ch) in ["A", "B", "C", "D", "E", "F"].iter().enumerate() {
                if Self::key_btn(&mut cols[i], hex_ch, h, pal.tertiary_container, pal.on_tertiary_container) {
                    let before = self.text_before_cursor();
                    if !before.ends_with("0x")
                        && !before
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
    }

    /// Rechenverlauf mit Formelsatz für Eingaben und exakte Ergebnisse.
    fn render_history(&mut self, ui: &mut Ui, pal: &MaterialYouPalette, body_height: f32) {
        let de = self.german_ui;
        Frame::new()
            .fill(pal.surface_container_low)
            .corner_radius(CornerRadius::same(16))
            .inner_margin(egui::Margin::same(10))
            .stroke(Stroke::new(1.0_f32, pal.outline_variant))
            .show(ui, |ui| {
                ui.set_height(body_height);
                ui.set_min_width(ui.available_width());
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(if de { "Rechenverlauf" } else { "History" })
                            .font(FontId::proportional(12.5))
                            .strong()
                            .color(pal.on_surface_variant),
                    )
                    .on_hover_text(if de {
                        "Klick auf Ausdruck oder Ergebnis übernimmt es in die Eingabe. „exakt“/„dezimal“ wechselt die Darstellung."
                    } else {
                        "Click an expression or result to reuse it. “exact”/“decimal” switches the format."
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if self.history.is_empty() {
                            self.confirm_clear_history = false;
                        } else if self.confirm_clear_history {
                            if ui.small_button(if de { "Abbrechen" } else { "Cancel" }).clicked() {
                                self.confirm_clear_history = false;
                            }
                            if ui
                                .small_button(
                                    RichText::new(if de { "Ja, leeren" } else { "Yes, clear" }).color(pal.error),
                                )
                                .clicked()
                            {
                                self.history.clear();
                                self.confirm_clear_history = false;
                                self.mark_state_dirty();
                            }
                            ui.label(
                                RichText::new(if de { "Gesamten Verlauf löschen?" } else { "Delete entire history?" })
                                    .font(FontId::proportional(11.5))
                                    .color(pal.error),
                            );
                        } else if ui
                            .small_button(if de { "Verlauf leeren" } else { "Clear history" })
                            .clicked()
                        {
                            self.confirm_clear_history = true;
                        }
                    });
                });
                ui.add_space(4.0);

                if self.history.is_empty() {
                    ui.add_space(12.0);
                    ui.vertical_centered(|ui| {
                        ui.label(
                            RichText::new(if de { "Noch keine Berechnungen" } else { "No calculations yet" })
                                .font(FontId::proportional(14.5))
                                .color(pal.on_surface_variant),
                        );
                        ui.label(
                            RichText::new(if de {
                                "Ausdruck eingeben und Enter oder „=“ drücken. Später übernimmt ein Klick auf einen Eintrag ihn wieder in die Eingabe."
                            } else {
                                "Type an expression and press Enter or “=”. Later, click an entry to reuse it."
                            })
                            .font(FontId::proportional(12.0))
                            .color(pal.on_surface_variant),
                        );
                    });
                    return;
                }

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
                                    ui.set_min_width(ui.available_width());
                                    // Zeile 1: Eingabe links, Aktionen rechts
                                    ui.horizontal(|ui| {
                                        let pretty_in = MathEngine::prettify_expression(&item.raw_input);
                                        let max_w = (ui.available_width() - 110.0).max(60.0);
                                        if mathview::show_math(
                                            ui,
                                            &item.raw_input,
                                            &pretty_in,
                                            HISTORY_TERM_FONT_SIZE - 2.0,
                                            pal.on_surface,
                                            max_w,
                                            Sense::click(),
                                        )
                                        .on_hover_cursor(egui::CursorIcon::PointingHand)
                                        .on_hover_text(if de {
                                            "Klicken, um den Ausdruck in die Eingabe zu übernehmen"
                                        } else {
                                            "Click to paste the expression into the input"
                                        })
                                        .clicked()
                                        {
                                            paste_text = Some(item.raw_input.clone());
                                        }

                                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                            if ui
                                                .add(
                                                    egui::Button::new(
                                                        RichText::new("×")
                                                            .font(FontId::proportional(14.0))
                                                            .color(pal.on_surface_variant),
                                                    )
                                                    .min_size(egui::vec2(24.0, 22.0))
                                                    .corner_radius(CornerRadius::same(8)),
                                                )
                                                .on_hover_text(if de { "Eintrag löschen" } else { "Delete entry" })
                                                .clicked()
                                            {
                                                delete_idx = Some(idx);
                                            }

                                            if !item.is_error
                                                && plottable_in_x(&item.raw_input)
                                                && ui
                                                    .small_button(if de { "Plotten" } else { "Plot" })
                                                    .on_hover_text(if de {
                                                        "Im 2D-Plotter als neue Funktion zeichnen"
                                                    } else {
                                                        "Draw as a new function in the 2D plotter"
                                                    })
                                                    .clicked()
                                            {
                                                plot_expr = Some(item.raw_input.clone());
                                            }
                                        });
                                    });

                                    // Zeile 2: Umschalter exakt/dezimal und „=“ links, Ergebnis rechts
                                    let exact_disp = if item.is_exact && !item.is_error {
                                        format_exact_history_display(&item.exact_result, self.engine.number_format)
                                    } else {
                                        String::new()
                                    };
                                    let dec_disp = if item.is_error {
                                        i18n::localize_error(&item.decimal_result, de)
                                    } else {
                                        format_decimal_history_display(&item.decimal_result, self.engine.number_format)
                                    };
                                    let is_matrix =
                                        item.exact_result.contains("[[") || item.decimal_result.contains("[[");
                                    let can_toggle =
                                        !item.is_error && item.is_exact && !is_matrix && exact_disp != dec_disp;
                                    let use_exact = if can_toggle {
                                        item.showing_exact
                                    } else {
                                        item.is_exact && !item.is_error
                                    };
                                    let shown_res: &str = if use_exact { &exact_disp } else { &dec_disp };
                                    // Matrizen als Matrix setzen, exakte Ergebnisse mit
                                    // Bruchstrich/Wurzel, alles andere als Text.
                                    let matrix_rows = if is_matrix && !item.is_error {
                                        mathview::parse_matrix_literal(shown_res)
                                    } else {
                                        None
                                    };
                                    // Zeilenhöhe vorab bestimmen, damit „=“ und der Umschalter
                                    // auch neben hohen Ergebnissen (Matrix, Bruch) mittig stehen.
                                    let row_h = match &matrix_rows {
                                        Some(rows) => mathview::matrix_size(ui, rows, HISTORY_TERM_FONT_SIZE - 2.0).y,
                                        None if use_exact => mathview::formula_height(ui, shown_res, HISTORY_TERM_FONT_SIZE),
                                        None => 0.0,
                                    }
                                    .max(ui.spacing().interact_size.y);

                                    ui.allocate_ui_with_layout(
                                        egui::vec2(ui.available_width(), row_h),
                                        Layout::left_to_right(Align::Center),
                                        |ui| {
                                        if can_toggle {
                                            let badge_txt = match (de, item.showing_exact) {
                                                (true, true) => "exakt",
                                                (true, false) => "dezimal",
                                                (false, true) => "exact",
                                                (false, false) => "decimal",
                                            };
                                            let (pill_fill, pill_fg) = if item.showing_exact {
                                                (pal.primary_container, pal.on_primary_container)
                                            } else {
                                                (pal.secondary_container, pal.on_secondary_container)
                                            };
                                            let btn = egui::Button::new(
                                                RichText::new(badge_txt)
                                                    .font(FontId::proportional(11.5))
                                                    .strong()
                                                    .color(pill_fg),
                                            )
                                            .fill(pill_fill)
                                            .corner_radius(CornerRadius::same(10));
                                            let tooltip = if de {
                                                format!("Exakt: {}  |  Dezimal: {}", exact_disp, dec_disp)
                                            } else {
                                                format!("Exact: {}  |  Decimal: {}", exact_disp, dec_disp)
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

                                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                            let res_col = if item.is_error { pal.error } else { pal.primary };
                                            let hint = if de {
                                                "Klicken, um das Ergebnis einzufügen"
                                            } else {
                                                "Click to insert the result"
                                            };
                                            let resp = if let Some(rows) = &matrix_rows {
                                                mathview::matrix_widget(
                                                    ui,
                                                    rows,
                                                    HISTORY_TERM_FONT_SIZE - 2.0,
                                                    res_col,
                                                    Sense::click(),
                                                )
                                            } else {
                                                let parse_src = if use_exact && !item.is_error { shown_res } else { "" };
                                                let max_w = ui.available_width();
                                                mathview::show_math(
                                                    ui,
                                                    parse_src,
                                                    shown_res,
                                                    HISTORY_TERM_FONT_SIZE,
                                                    res_col,
                                                    max_w,
                                                    Sense::click(),
                                                )
                                            };
                                            if resp.on_hover_text(hint).clicked() && !item.is_error {
                                                paste_text = Some(item.reusable_result.clone());
                                            }
                                        });
                                    },
                                    );

                                    if let Some(ref rep) = item.integration_report {
                                        let (info, color) = match (rep.converged, de) {
                                            (true, true) => (
                                                format!(
                                                    "✓ konvergiert | Fehler ≈ {:.1e} | {} Teilintervalle",
                                                    rep.abs_err, rep.subintervals
                                                ),
                                                pal.on_surface_variant,
                                            ),
                                            (true, false) => (
                                                format!(
                                                    "✓ converged | error ≈ {:.1e} | {} subintervals",
                                                    rep.abs_err, rep.subintervals
                                                ),
                                                pal.on_surface_variant,
                                            ),
                                            (false, true) => (
                                                format!("⚠ nicht konvergiert | {} Teilintervalle", rep.subintervals),
                                                pal.error,
                                            ),
                                            (false, false) => (
                                                format!("⚠ not converged | {} subintervals", rep.subintervals),
                                                pal.error,
                                            ),
                                        };
                                        ui.label(RichText::new(info).font(FontId::proportional(11.0)).color(color));
                                    }
                                });
                        }

                        if let Some(txt) = paste_text {
                            self.insert_token(&txt);
                        }
                        if let Some(p_expr) = plot_expr {
                            self.plot_expression(&p_expr);
                        }
                        if let Some(i) = delete_idx {
                            self.history.remove(i);
                            self.mark_state_dirty();
                        }
                    });
            });
    }

    /// Ausdruck aus dem Verlauf zeichnen, ohne eine vorhandene Funktion
    /// stillschweigend zu überschreiben: erst ein leerer Platz, sonst ein
    /// neuer, und erst wenn alle Plätze belegt sind, wird der letzte ersetzt.
    fn plot_expression(&mut self, raw: &str) {
        let expr = mathview::to_display_syntax(raw);
        let de = self.german_ui;
        self.plot_notice = None;
        if let Some(pf) = self.plot_functions.iter_mut().find(|pf| pf.expr == expr) {
            pf.visible = true;
        } else if let Some(pf) = self.plot_functions.iter_mut().find(|pf| pf.expr.trim().is_empty()) {
            pf.expr = expr;
            pf.visible = true;
        } else if self.plot_functions.len() < MAX_PLOT_FUNCTIONS {
            self.add_plot_function(expr);
        } else if let Some(last) = self.plot_functions.last_mut() {
            last.expr = expr;
            last.visible = true;
            let name = format!("f{}", subscript_number(MAX_PLOT_FUNCTIONS));
            self.plot_notice = Some(if de {
                format!("Alle {MAX_PLOT_FUNCTIONS} Plätze waren belegt – {name} wurde ersetzt.")
            } else {
                format!("All {MAX_PLOT_FUNCTIONS} slots were in use – {name} was replaced.")
            });
        }
        self.active_tab = MainTab::Plotter;
    }

    fn add_plot_function(&mut self, expr: String) {
        let used: Vec<usize> = self.plot_functions.iter().map(|p| p.color_idx).collect();
        let color_idx = (0..PLOT_COLOR_COUNT)
            .find(|i| !used.contains(i))
            .unwrap_or(self.plot_functions.len() % PLOT_COLOR_COUNT);
        self.plot_functions.push(PlotFunction { expr, visible: true, color_idx });
    }

    /// Formelzeile: echtes Textfeld mit Cursor, Auswahl, Einfügen und Rückgängig.
    /// Angezeigt wird die Anzeige-Schreibweise (`√(2)÷2`), Exponenten angehoben.
    fn render_input_line(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let input_id = egui::Id::new(INPUT_FIELD_ID);
        if let Some(pos) = self.pending_cursor.take() {
            let mut state = egui::text_edit::TextEditState::load(ui.ctx(), input_id).unwrap_or_default();
            state
                .cursor
                .set_char_range(Some(egui::text::CCursorRange::one(egui::text::CCursor::new(pos))));
            state.store(ui.ctx(), input_id);
            ui.memory_mut(|m| m.request_focus(input_id));
        }

        ui.horizontal(|ui| {
            let mut clear_clicked = false;
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                clear_clicked = ui
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
                    .clicked();

                let font_sz = if self.input_expr.chars().count() > 32 {
                    INPUT_TERM_MIN_FONT_SIZE
                } else {
                    INPUT_TERM_FONT_SIZE
                };
                let font = FontId::proportional(font_sz);
                let text_col = pal.on_surface;
                let layout_font = font.clone();
                let mut layouter = move |ui: &Ui, buf: &dyn egui::TextBuffer, _wrap: f32| {
                    let job = mathview::edit_layout_job(buf.as_str(), layout_font.clone(), text_col);
                    ui.fonts_mut(|f| f.layout_job(job))
                };
                let hint = RichText::new(if self.german_ui {
                    "Ausdruck eingeben oder über Tastatur wählen…"
                } else {
                    "Enter expression or use keyboard below…"
                })
                .font(FontId::proportional(INPUT_TERM_MIN_FONT_SIZE))
                .color(pal.on_surface_variant);

                let out = egui::TextEdit::singleline(&mut self.input_expr)
                    .id(input_id)
                    .font(font)
                    .frame(Frame::NONE)
                    .hint_text(hint)
                    .desired_width(ui.available_width())
                    .layouter(&mut layouter)
                    .show(ui);

                if let Some(range) = out.cursor_range {
                    let r = range.as_sorted_char_range();
                    self.input_sel = (r.start, r.end);
                }
                if out.response.changed() {
                    // Getippte oder eingefügte ASCII-Operatoren in Anzeige-Schreibweise
                    let cursor = self.input_sel.1;
                    let (normalized, map) = mathview::to_display_syntax_with_map(&self.input_expr);
                    if normalized != self.input_expr {
                        let pos = map.get(cursor).copied().unwrap_or(normalized.chars().count());
                        self.input_expr = normalized;
                        self.input_sel = (pos, pos);
                        let mut state = out.state.clone();
                        state.cursor.set_char_range(Some(egui::text::CCursorRange::one(
                            egui::text::CCursor::new(pos),
                        )));
                        state.store(ui.ctx(), input_id);
                    }
                }
                self.input_had_focus = out.response.has_focus();
                if out.response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                    let ctx = ui.ctx().clone();
                    self.evaluate_current(Some(&ctx));
                    ui.memory_mut(|m| m.request_focus(input_id));
                }
            });
            if clear_clicked {
                self.clear_input();
            }
        });
    }

    /// Live-Vorschau unter der Formelzeile.
    fn render_preview_line(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        ui.horizontal(|ui| {
            let raw = self.engine_input();
            if raw.is_empty() {
                let ans_s = self.engine.number_format.format_f64(self.engine.ans.re);
                ui.label(
                    RichText::new(format!("ANS = {}", ans_s))
                    .font(FontId::proportional(12.5))
                    .color(pal.on_surface_variant),
                );
                return;
            }
            let calculating = |ui: &mut Ui, de: bool| {
                ui.label(
                    RichText::new(if de { "… wird berechnet" } else { "… calculating" })
                        .font(FontId::proportional(12.5))
                        .color(pal.on_surface_variant),
                );
            };
            let key: PreviewKey = (
                raw.clone(),
                self.engine.angle_mode,
                self.engine.modulo_always_positive,
                self.eval_revision,
            );
            if self.commit_pending {
                calculating(ui, self.german_ui);
                return;
            }
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
                calculating(ui, self.german_ui);
                return;
            }
            match self.preview_cache_result.as_ref().unwrap() {
                Ok(preview) => {
                    let nf = self.engine.number_format;
                    let dec_disp = format_decimal_history_display(&preview.decimal_text, nf);
                    // Matrizen wie im Verlauf als Matrix setzen; „=“ und „≈“
                    // stehen mittig neben der Matrix.
                    let shown = if preview.is_exact {
                        format_exact_history_display(&preview.exact_text, nf)
                    } else {
                        dec_disp.clone()
                    };
                    if let Some(rows) = mathview::parse_matrix_literal(&shown) {
                        let size = mathview::matrix_size(ui, &rows, 15.0);
                        let dec_rows = if preview.is_exact && shown != dec_disp {
                            mathview::parse_matrix_literal(&dec_disp)
                        } else {
                            None
                        };
                        let dec_size = dec_rows.as_ref().map(|r| mathview::matrix_size(ui, r, 13.5));
                        let h = size.y.max(dec_size.map_or(0.0, |s| s.y));
                        ui.allocate_ui_with_layout(
                            egui::vec2(ui.available_width(), h),
                            Layout::left_to_right(Align::Center),
                            |ui| {
                                ui.label(RichText::new("=").font(FontId::proportional(15.0)).color(pal.primary));
                                mathview::matrix_widget(ui, &rows, 15.0, pal.primary, Sense::hover());
                                if let (Some(r), Some(s)) = (&dec_rows, dec_size) {
                                    if s.x + 30.0 <= ui.available_width() {
                                        ui.label(
                                            RichText::new("≈")
                                                .font(FontId::proportional(15.0))
                                                .color(pal.on_surface_variant),
                                        );
                                        mathview::matrix_widget(ui, r, 13.5, pal.on_surface_variant, Sense::hover());
                                    }
                                }
                            },
                        );
                        return;
                    }
                    // Formelspiegel: Die Eingabezeile ist ein Textfeld und setzt
                    // Brüche und Wurzeln einzeilig. Hier steht die Eingabe
                    // zweidimensional, wenn sie Brüche oder Wurzeln enthält.
                    let mirror = mathview::parse_math(&raw)
                        .filter(|n| n.is_two_dimensional())
                        .filter(|n| mathview::math_width(ui, n, 15.0) <= ui.available_width() * 0.5);
                    if let Some(node) = &mirror {
                        mathview::math_widget(ui, node, 15.0, pal.on_surface, Sense::hover());
                    }
                    if preview.is_exact {
                        let ex_disp = format_exact_history_display(&preview.exact_text, nf);
                        let label = if mirror.is_some() {
                            "="
                        } else if self.german_ui {
                            "exakt:"
                        } else {
                            "exact:"
                        };
                        ui.label(RichText::new(label).font(FontId::proportional(13.5)).color(pal.primary));
                        let max_w = ui.available_width() * 0.6;
                        mathview::show_math(ui, &ex_disp, &ex_disp, 15.0, pal.primary, max_w, Sense::hover());
                        if ex_disp != dec_disp {
                            ui.separator();
                            let text = if mirror.is_some() {
                                format!("≈ {}", dec_disp)
                            } else {
                                format!("{}: ≈ {}", if self.german_ui { "dezimal" } else { "decimal" }, dec_disp)
                            };
                            ui.label(mathview::rich_job(&text, FontId::proportional(13.5), pal.on_surface_variant));
                        }
                    } else {
                        ui.label(mathview::rich_job(
                            &format!("= {}", dec_disp),
                            FontId::proportional(13.5),
                            pal.primary,
                        ));
                    }
                }
                Err(err) => {
                    let (text, color) = match classify_preview_error(err, &raw) {
                        PreviewError::Show => (format!("⚠ {}", i18n::localize_error(err, self.german_ui)), pal.error),
                        PreviewError::Incomplete => (
                            if self.german_ui {
                                "… Ausdruck wird vervollständigt".to_string()
                            } else {
                                "… completing expression".to_string()
                            },
                            pal.on_surface_variant,
                        ),
                    };
                    ui.label(RichText::new(text).font(FontId::proportional(12.5)).color(color));
                }
            }
        });
    }

    fn render_keyboard_section(
        &mut self,
        ui: &mut Ui,
        section: KeyboardSection,
        pal: &MaterialYouPalette,
    ) {
        let avail_h = ui.available_height().max(100.0);
        let sp = ui.spacing().item_spacing.y;
        // Alle Bereiche haben vier gleich hohe Reihen, damit linke und rechte
        // Tastenhälfte im Dual-Modus Reihe für Reihe bündig sind.
        let row_h = ((avail_h - 3.0 * sp - 6.0) / 4.0).max(22.0);
        let de = self.german_ui;

        match section {
            KeyboardSection::Numbers => {
                // Links drei Spalten Ziffern (`7 8 9` … `0 . =`), rechts fünf
                // Operatoren (`⌫ ÷ × + −`) über dieselbe Höhe.
                let op_row_h = ((4.0 * row_h - sp) / 5.0).max(16.0);
                let dec_lbl = match self.engine.number_format {
                    NumberFormatMode::SpaceComma | NumberFormatMode::PointComma | NumberFormatMode::NoneComma => ",",
                    NumberFormatMode::SpacePoint | NumberFormatMode::CommaPoint | NumberFormatMode::NonePoint => ".",
                };
                let ctx_clone = ui.ctx().clone();
                ui.columns(4, |cols| {
                    let digit_cols = [["7", "4", "1", "0"], ["8", "5", "2", dec_lbl], ["9", "6", "3", "="]];
                    for (c_idx, col_keys) in digit_cols.iter().enumerate() {
                        for &lbl in col_keys {
                            let is_enter = lbl == "=";
                            let (fill, fg) = if is_enter {
                                (pal.primary, pal.on_primary)
                            } else {
                                (pal.surface_container_high, pal.on_surface)
                            };
                            if Self::key_btn(&mut cols[c_idx], lbl, row_h, fill, fg) {
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

                    // `⌫` gibt es nur in System-Symbolschriften; sonst Pfeil.
                    let del_lbl = if ctx_clone.fonts_mut(|f| f.has_glyph(&FontId::proportional(15.0), '⌫')) {
                        "⌫"
                    } else {
                        "←"
                    };
                    for (op_lbl, op_tok) in [(del_lbl, "DEL"), ("÷", "/"), ("×", "*"), ("+", "+"), ("−", "-")] {
                        if Self::key_btn(&mut cols[3], op_lbl, op_row_h, pal.secondary_container, pal.on_secondary_container) {
                            if op_tok == "DEL" {
                                self.backspace();
                            } else {
                                self.insert_token(op_tok);
                            }
                        }
                    }
                });
            }

            KeyboardSection::Variables => {
                // 11 belegbare Plätze + „:=“/Konstanten in einem 4×3-Raster
                let slots = self.keyboard_slots.clone();
                for r in 0..4 {
                    ui.columns(3, |cols| {
                        for (c, col) in cols.iter_mut().enumerate() {
                            let idx = r * 3 + c;
                            if idx < ASSIGNABLE_VARIABLE_SLOTS {
                                let sym = &slots[idx];
                                let badge = if let Some(val) = self.engine.variables.get(sym) {
                                    format!("{} ({})", sym, val)
                                } else {
                                    sym.clone()
                                };
                                if Self::key_btn(col, &badge, row_h, pal.surface_container_high, pal.on_surface) {
                                    self.insert_token(sym);
                                }
                            } else {
                                let lbl = if de { ":= / Konst." } else { ":= / more" };
                                let clicked = Self::key_btn_with_tip(
                                    col,
                                    lbl,
                                    row_h,
                                    pal.primary_container,
                                    pal.on_primary_container,
                                    if de {
                                        "Steht nur ein Variablenname in der Eingabe: „:=“ anhängen. Sonst: Konstanten & Variablen öffnen."
                                    } else {
                                        "If the input is just a variable name: append “:=”. Otherwise open constants & variables."
                                    },
                                );
                                if clicked {
                                    if !self.input_expr.is_empty() && USER_VARIABLES.contains(&self.input_expr.trim()) {
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

            other => {
                // Je 4 Reihen × 3 Tasten
                let grid: [[(&str, &str); 3]; 4] = match other {
                    KeyboardSection::Operators => [
                        [("(", "("), (")", ")"), ("π", "pi")],
                        [("x²", "^2"), ("xⁿ", "^("), ("e", "e")],
                        [("√x", "sqrt("), ("ⁿ√x", "nthroot(3, "), ("10ⁿ", "10^(")],
                        [("ln", "ln("), ("log₁₀", "log(10, "), ("ANS", "ans")],
                    ],
                    KeyboardSection::Trigonometry => [
                        [("sin", "sin("), ("cos", "cos("), ("tan", "tan(")],
                        [("sin⁻¹", "asin("), ("cos⁻¹", "acos("), ("tan⁻¹", "atan(")],
                        [("sinh", "sinh("), ("cosh", "cosh("), ("tanh", "tanh(")],
                        [("sinh⁻¹", "asinh("), ("cosh⁻¹", "acosh("), ("tanh⁻¹", "atanh(")],
                    ],
                    _ => [
                        [("∑", "sum(k, k, 1, 10)"), ("∏", "prod(k, k, 1, 5)"), ("n!", "!")],
                        [("∫", "int(x^2, x, 0, 1)"), ("d/dx", "deriv(x^3, x, 2)"), ("nCr", "binomial(5, 2)")],
                        [("mod", " mod "), ("i", "i"), ("[ Matrix ]", "[[")],
                        [("A⁻¹", "inv("), ("Aᵀ", "trans("), ("det", "det(")],
                    ],
                };
                for row in grid {
                    ui.columns(3, |cols| {
                        for (i, (label, tok)) in row.iter().enumerate() {
                            let accent = matches!(*label, "π" | "e" | "ANS");
                            let (fill, fg) = if accent {
                                (pal.primary_container, pal.on_primary_container)
                            } else {
                                (pal.surface_container_high, pal.on_surface)
                            };
                            if Self::key_btn(&mut cols[i], label, row_h, fill, fg) {
                                self.insert_token(tok);
                            }
                        }
                    });
                }
            }
        }
    }

    fn key_btn(ui: &mut Ui, label: &str, height: f32, fill: Color32, fg: Color32) -> bool {
        Self::key_button(ui, label, height, fill, fg).clicked()
    }

    fn key_btn_with_tip(ui: &mut Ui, label: &str, height: f32, fill: Color32, fg: Color32, tip: &str) -> bool {
        Self::key_button(ui, label, height, fill, fg).on_hover_text(tip).clicked()
    }

    fn key_button(ui: &mut Ui, label: &str, height: f32, fill: Color32, fg: Color32) -> egui::Response {
        let w = ui.available_width();
        let short = label.chars().count() <= 6;
        // Beschriftung wächst mit der Taste (große Fenster), bleibt aber in
        // schmalen Tasten bei 15 px.
        let size = if short {
            (height * 0.38).min(w * 0.3).clamp(15.0, 26.0)
        } else {
            15.0
        };
        // Hochzahlen (xⁿ, sin⁻¹, Aᵀ) als angehobenen Text setzen – die
        // eingebaute Schrift hat für ⁿ, ⁻ und ᵀ keine Zeichen.
        let btn = egui::Button::new(egui::WidgetText::from(mathview::rich_job(
            label,
            FontId::proportional(size),
            fg,
        )))
        .fill(fill)
        .corner_radius(CornerRadius::same(14))
        .min_size(egui::vec2(w, height));
        // Kurze Tastenbeschriftungen nie umbrechen („AN|S“, „log|10“)
        let btn = if short { btn.wrap_mode(egui::TextWrapMode::Extend) } else { btn };
        // Der Button richtet seine Beschriftung nach dem umgebenden Layout aus;
        // in `vertical_centered` steht sie mittig statt linksbündig.
        ui.vertical_centered(|ui| ui.add(btn)).inner
    }

    fn render_plotter_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let de = self.german_ui;
        let mut remove_idx: Option<usize> = None;
        let mut add_function = false;
        let (scale_min, scale_max) = PLOT_SCALE_RANGE;

        // Funktionsliste
        Frame::new()
            .fill(pal.surface_container)
            .corner_radius(CornerRadius::same(16))
            .inner_margin(egui::Margin::same(10))
            .show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                // Schmale Fenster: kurzer Titel, Knöpfe nur mit Symbol
                let narrow = ui.available_width() < 720.0;
                let hint = if de {
                    "Ziehen zum Verschieben, Mausrad zum Zoomen"
                } else {
                    "Drag to pan, scroll to zoom"
                };
                ui.horizontal(|ui| {
                    let title = match (narrow, de) {
                        (true, true) => "2D-Plotter".to_string(),
                        (true, false) => "2D Plotter".to_string(),
                        (false, true) => format!("2D-Funktionsplotter ({hint})"),
                        (false, false) => format!("2D Function Plotter ({hint})"),
                    };
                    ui.label(RichText::new(title).font(FontId::proportional(14.5)).strong())
                        .on_hover_text(hint);
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        let reset_label = match (narrow, de) {
                            (true, _) => "⟳",
                            (false, true) => "⟳ Ansicht zurücksetzen",
                            (false, false) => "⟳ Reset view",
                        };
                        if ui
                            .small_button(reset_label)
                            .on_hover_text(if de {
                                "Ursprung zentrieren und Zoom zurücksetzen"
                            } else {
                                "Center the origin and reset the zoom"
                            })
                            .clicked()
                        {
                            self.plot_center_x = 0.0;
                            self.plot_center_y = 0.0;
                            self.plot_scale = 48.0;
                        }
                        if ui.small_button("🔍+").on_hover_text(if de { "Vergrößern" } else { "Zoom in" }).clicked() {
                            self.plot_scale = (self.plot_scale * 1.25).clamp(scale_min, scale_max);
                        }
                        if ui.small_button("🔍−").on_hover_text(if de { "Verkleinern" } else { "Zoom out" }).clicked() {
                            self.plot_scale = (self.plot_scale / 1.25).clamp(scale_min, scale_max);
                        }
                        if self.plot_functions.len() < MAX_PLOT_FUNCTIONS {
                            let add_label = match (narrow, de) {
                                (true, _) => "+ f",
                                (false, true) => "+ Funktion",
                                (false, false) => "+ Function",
                            };
                            if ui
                                .small_button(add_label)
                                .on_hover_text(if de { "Weitere Funktion hinzufügen" } else { "Add another function" })
                                .clicked()
                            {
                                add_function = true;
                            }
                        }
                    });
                });
                if let Some(note) = &self.plot_notice {
                    ui.label(RichText::new(note).font(FontId::proportional(12.0)).color(pal.on_surface_variant));
                }

                ui.add_space(4.0);
                let trace_x = self.plot_trace_x;
                // Breite Fenster: Statuszeile neben dem Feld, sonst darunter
                let wide = ui.available_width() >= 820.0;
                for (i, pf) in self.plot_functions.iter_mut().enumerate() {
                    let color = pal.plot_color(pf.color_idx);
                    let mut readout = None;
                    ui.horizontal(|ui| {
                        ui.checkbox(&mut pf.visible, "")
                            .on_hover_text(if de { "Kurve ein-/ausblenden" } else { "Show/hide curve" });
                        ui.label(mathview::rich_job(
                            &format!("f{}(x) =", subscript_number(i + 1)),
                            FontId::proportional(13.5),
                            color,
                        ));
                        // Gleiche Schreibweise wie in der Formelzeile des Rechners
                        let field_font = FontId::proportional(15.0);
                        let field_col = pal.on_surface;
                        let layout_font = field_font.clone();
                        let mut layouter = move |ui: &Ui, buf: &dyn egui::TextBuffer, _wrap: f32| {
                            let job = mathview::edit_layout_job(buf.as_str(), layout_font.clone(), field_col);
                            ui.fonts_mut(|f| f.layout_job(job))
                        };
                        let field_id = ui.id().with(("plot_fn", i));
                        let out = egui::TextEdit::singleline(&mut pf.expr)
                            .id(field_id)
                            .desired_width(ui.available_width().clamp(120.0, 220.0))
                            .font(field_font)
                            .hint_text(if de { "z. B. sin(x)" } else { "e.g. sin(x)" })
                            .layouter(&mut layouter)
                            .show(ui);
                        if out.response.changed() {
                            let cursor = out
                                .cursor_range
                                .map(|r| r.primary.index)
                                .unwrap_or(pf.expr.chars().count());
                            let (normalized, map) = mathview::to_display_syntax_with_map(&pf.expr);
                            if normalized != pf.expr {
                                let pos = map.get(cursor).copied().unwrap_or(normalized.chars().count());
                                pf.expr = normalized;
                                let mut state = out.state.clone();
                                state.cursor.set_char_range(Some(egui::text::CCursorRange::one(
                                    egui::text::CCursor::new(pos),
                                )));
                                state.store(ui.ctx(), field_id);
                            }
                        }
                        if ui
                            .add(egui::Button::new("×").min_size(egui::vec2(22.0, 22.0)))
                            .on_hover_text(if de { "Funktion entfernen" } else { "Remove function" })
                            .clicked()
                        {
                            remove_idx = Some(i);
                        }
                        // Formelspiegel mit Bruchstrichen und Wurzeln, wenn Platz ist
                        let readout_room = if wide { 340.0 } else { 0.0 };
                        if let Some(node) = mathview::parse_math(&mathview::to_engine_syntax(&pf.expr))
                            .filter(|n| n.is_two_dimensional())
                        {
                            if mathview::math_width(ui, &node, 14.0) + 8.0 <= ui.available_width() - readout_room {
                                ui.add_space(4.0);
                                mathview::math_widget(ui, &node, 14.0, pal.on_surface_variant, Sense::hover());
                            }
                        }
                        let name = format!("f{}", subscript_number(i + 1));
                        let (text, is_err) = plot_readout_named(&self.engine, &pf.expr, trace_x, de, &name);
                        let job = mathview::rich_job(
                            &text,
                            FontId::proportional(12.5),
                            if is_err { pal.error } else { pal.on_surface_variant },
                        );
                        if wide {
                            ui.label(job);
                        } else {
                            readout = Some(job);
                        }
                    });
                    if let Some(mut job) = readout {
                        if !job.text.is_empty() {
                            ui.horizontal(|ui| {
                                ui.add_space(28.0);
                                job.wrap.max_width = ui.available_width();
                                ui.label(job);
                            });
                        }
                    }
                }
            });

        if let Some(i) = remove_idx {
            if self.plot_functions.len() > 1 {
                self.plot_functions.remove(i);
            } else if let Some(pf) = self.plot_functions.first_mut() {
                pf.expr.clear();
            }
            self.plot_notice = None;
        }
        if add_function {
            self.add_plot_function(String::new());
            self.plot_notice = None;
        }

        ui.add_space(6.0);

        // Zeichenfläche (`FunctionPlotting2DView.onDraw`). Nie höher als der
        // verfügbare Platz, sonst rutscht sie unter die Navigationsleiste.
        let avail = ui.available_size();
        let (rect, response) =
            ui.allocate_exact_size(egui::vec2(avail.x, avail.y.max(60.0)), Sense::click_and_drag());
        response.widget_info(|| {
            egui::WidgetInfo::labeled(
                egui::WidgetType::Other,
                true,
                if de { "Funktionsgraph" } else { "Function graph" },
            )
        });

        if response.dragged() {
            let delta = response.drag_delta();
            self.plot_center_x -= (delta.x as f64) / self.plot_scale;
            self.plot_center_y += (delta.y as f64) / self.plot_scale;
        }
        if let Some(hover_pos) = response.hover_pos() {
            let scroll = ui.input(|i| i.smooth_scroll_delta.y);
            if scroll.abs() > 0.1 {
                let factor = (1.0 + scroll as f64 * 0.002).clamp(0.8, 1.25);
                self.plot_scale = (self.plot_scale * factor).clamp(scale_min, scale_max);
            }
            self.plot_trace_x = self.plot_center_x + ((hover_pos.x - rect.center().x) as f64) / self.plot_scale;
        }

        let painter = ui.painter_at(rect);
        painter.rect_filled(rect, CornerRadius::same(16), pal.surface_container_low);

        let cx = rect.center().x;
        let cy = rect.center().y;
        let scale = self.plot_scale as f32;

        // Gitterabstand passend zum Zoom (1, 2, 5 × 10ⁿ)
        let raw_step = 80.0 / self.plot_scale;
        let mag = 10f64.powf(raw_step.log10().floor());
        let major_step = if raw_step / mag < 2.0 {
            mag
        } else if raw_step / mag < 5.0 {
            2.0 * mag
        } else {
            5.0 * mag
        };
        let minor_step = major_step / 5.0;
        let decimals = axis_decimals(major_step);

        let x_min = self.plot_center_x - ((rect.width() * 0.5) as f64) / self.plot_scale;
        let x_max = self.plot_center_x + ((rect.width() * 0.5) as f64) / self.plot_scale;
        let y_min = self.plot_center_y - ((rect.height() * 0.5) as f64) / self.plot_scale;
        let y_max = self.plot_center_y + ((rect.height() * 0.5) as f64) / self.plot_scale;

        // Nebengitter (`PLOTTER_MINOR_GRID_STROKE = 1.0` px)
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

        // Hauptgitter & Achsenbeschriftung. Beschriftungen, die über den Rand
        // ragen würden, entfallen (sonst stand dort „0“ statt „−10“).
        let label_font = FontId::proportional((PLOTTER_AXIS_LABEL_SIZE * 0.65).max(11.5));
        let label_col = pal.on_surface_variant;
        let mut mx = (x_min / major_step).floor() * major_step;
        while mx <= x_max {
            let sx = cx + ((mx - self.plot_center_x) as f32) * scale;
            painter.line_segment(
                [Pos2::new(sx, rect.top()), Pos2::new(sx, rect.bottom())],
                Stroke::new(PLOTTER_MAJOR_GRID_STROKE, pal.plotter_major_grid),
            );
            if mx.abs() > major_step * 1e-6 {
                let y_axis_screen =
                    (cy - ((-self.plot_center_y) as f32) * scale).clamp(rect.top() + 14.0, rect.bottom() - 18.0);
                let galley = painter.layout_no_wrap(axis_label(mx, decimals), label_font.clone(), label_col);
                let pos = Pos2::new(sx + 3.0, y_axis_screen + 4.0);
                if pos.x >= rect.left() + 2.0 && pos.x + galley.size().x <= rect.right() - 2.0 {
                    painter.galley(pos, galley, label_col);
                }
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
            if my.abs() > major_step * 1e-6 {
                let x_axis_screen =
                    (cx + ((-self.plot_center_x) as f32) * scale).clamp(rect.left() + 6.0, rect.right() - 40.0);
                let galley = painter.layout_no_wrap(axis_label(my, decimals), label_font.clone(), label_col);
                let size = galley.size();
                let pos = Pos2::new(x_axis_screen + 5.0, sy - 2.0 - size.y);
                if pos.y >= rect.top() + 2.0
                    && pos.y + size.y <= rect.bottom() - 2.0
                    && pos.x + size.x <= rect.right() - 2.0
                {
                    painter.galley(pos, galley, label_col);
                }
            }
            my += major_step;
        }

        // Achsen
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

        // Kurven
        let samples = (rect.width() as usize).clamp(200, 900);
        for pf in &self.plot_functions {
            if !pf.visible || pf.expr.trim().is_empty() {
                continue;
            }
            let color = pal.plot_color(pf.color_idx);
            let expr = mathview::to_engine_syntax(&pf.expr);
            let mut prev_pt: Option<Pos2> = None;
            let mut prev_val: Option<(f64, f64)> = None;

            for s in 0..=samples {
                let t = (s as f32) / (samples as f32);
                let sx = rect.left() + t * rect.width();
                let x_val = self.plot_center_x + ((sx - cx) as f64) / self.plot_scale;
                if let Some(y_val) = self.engine.eval_for_x(&expr, x_val) {
                    let sy = cy - ((y_val - self.plot_center_y) as f32) * scale;
                    let cur_pt = Pos2::new(sx, sy);
                    if sy >= rect.top() - 200.0 && sy <= rect.bottom() + 200.0 {
                        if let Some(p) = prev_pt {
                            if (cur_pt.y - p.y).abs() < rect.height() * 0.9 {
                                painter.line_segment([p, cur_pt], Stroke::new(2.4_f32, color));
                            }
                        }
                        prev_pt = Some(cur_pt);
                    } else {
                        prev_pt = None;
                    }

                    // Nullstellen markieren
                    if let Some((px, py)) = prev_val {
                        if (py <= 0.0 && y_val >= 0.0 || py >= 0.0 && y_val <= 0.0) && (y_val - py).abs() < 5.0 {
                            let root_x = px - py * (x_val - px) / (y_val - py);
                            let rx = cx + ((root_x - self.plot_center_x) as f32) * scale;
                            if y0_screen >= rect.top() && y0_screen <= rect.bottom() {
                                painter.circle_filled(Pos2::new(rx, y0_screen), 4.0, color);
                            }
                        }
                    }
                    prev_val = Some((x_val, y_val));
                } else {
                    prev_pt = None;
                    prev_val = None;
                }
            }

            // Spurpunkt bei `self.plot_trace_x`
            if let Some(ty) = self.engine.eval_for_x(&expr, self.plot_trace_x) {
                let tx_s = cx + ((self.plot_trace_x - self.plot_center_x) as f32) * scale;
                let ty_s = cy - ((ty - self.plot_center_y) as f32) * scale;
                if rect.contains(Pos2::new(tx_s, ty_s)) {
                    painter.circle_filled(Pos2::new(tx_s, ty_s), 5.5, color);
                    painter.circle_stroke(Pos2::new(tx_s, ty_s), 5.5, Stroke::new(1.5_f32, pal.bg_surface));
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

    /// Reiter „Konstanten & Einheiten“: Variablen, Einheitenrechner, Naturkonstanten.
    fn render_variables_and_constants_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            self.render_variables_card(ui, pal);
            ui.add_space(8.0);
            self.render_unit_converter_card(ui, pal);
            ui.add_space(8.0);
            self.render_constants_card(ui, pal);
        });
    }

    fn render_variables_card(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let de = self.german_ui;
        card(ui, pal, |ui| {
            card_title(ui, pal, if de { "📌 Variablen" } else { "📌 Variables" });
            ui.label(
                RichText::new(if de {
                    "11 Speicherplätze (a, b, c, d, k, l, m, n, x, y, z). Klick auf eine Variable wählt sie zum Bearbeiten aus."
                } else {
                    "11 memory slots (a, b, c, d, k, l, m, n, x, y, z). Click a variable to edit it."
                })
                .font(FontId::proportional(12.0))
                .color(pal.on_surface_variant),
            );
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                for &vname in &USER_VARIABLES {
                    let scalar = self.engine.variables.get(vname).copied();
                    let is_matrix = self.engine.matrix_variables.contains_key(vname);
                    let txt = match scalar {
                        Some(v) => format!("{} = {}", vname, self.engine.number_format.format_f64(v.to_f64())),
                        None if is_matrix => format!("{} = [Matrix]", vname),
                        None => format!("{} ({})", vname, if de { "leer" } else { "empty" }),
                    };
                    let filled = scalar.is_some() || is_matrix;
                    let selected = self.var_edit_name == vname;
                    let btn = egui::Button::new(
                        RichText::new(txt)
                            .font(FontId::proportional(13.0))
                            .strong()
                            .color(if filled { pal.on_primary_container } else { pal.on_surface_variant }),
                    )
                    .fill(if filled { pal.primary_container } else { pal.surface_container_high })
                    .stroke(if selected { Stroke::new(2.0_f32, pal.primary) } else { Stroke::NONE })
                    .corner_radius(CornerRadius::same(12));
                    if ui
                        .add(btn)
                        .on_hover_text(if de { "Zum Bearbeiten auswählen" } else { "Select for editing" })
                        .clicked()
                    {
                        self.select_variable(vname);
                    }
                }
            });

            ui.add_space(6.0);
            ui.horizontal_wrapped(|ui| {
                ui.label("Variable");
                let before = self.var_edit_name.clone();
                egui::ComboBox::from_id_salt("var_select_combo")
                    .selected_text(&self.var_edit_name)
                    .width(56.0)
                    .show_ui(ui, |ui| {
                        for &v in &USER_VARIABLES {
                            ui.selectable_value(&mut self.var_edit_name, v.to_string(), v);
                        }
                    });
                if self.var_edit_name != before {
                    let name = self.var_edit_name.clone();
                    self.select_variable(&name);
                }
                ui.label(":=");
                let field = ui.add(
                    egui::TextEdit::singleline(&mut self.var_edit_value)
                        .desired_width(150.0)
                        .font(FontId::monospace(13.5))
                        .hint_text(if de { "Wert oder Ausdruck" } else { "value or expression" }),
                );
                let enter = field.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                if ui.button(if de { "💾 Speichern" } else { "💾 Save" }).clicked() || enter {
                    self.save_variable();
                }
                if ui
                    .button(if de { "Leeren" } else { "Clear" })
                    .on_hover_text(if de { "Wert der Variablen löschen" } else { "Remove the variable's value" })
                    .clicked()
                {
                    let name = self.var_edit_name.clone();
                    self.engine.variables.remove(&name);
                    self.engine.matrix_variables.remove(&name);
                    self.var_edit_value.clear();
                    self.mark_state_dirty();
                    self.var_status = Some((
                        if de { format!("✓ {name} ist jetzt leer.") } else { format!("✓ {name} is now empty.") },
                        false,
                    ));
                }
                if ui
                    .button(if de { "➕ In Rechner" } else { "➕ Insert" })
                    .on_hover_text(if de { "Variable in die Eingabe einfügen" } else { "Insert the variable into the input" })
                    .clicked()
                {
                    let name = self.var_edit_name.clone();
                    self.insert_token(&name);
                    self.active_tab = MainTab::Calculator;
                }
            });
            if let Some((msg, is_err)) = &self.var_status {
                ui.label(
                    RichText::new(msg)
                        .font(FontId::proportional(12.5))
                        .color(if *is_err { pal.error } else { pal.primary }),
                );
            }
        });
    }

    fn select_variable(&mut self, name: &str) {
        self.var_edit_name = name.to_string();
        self.var_edit_value = self
            .engine
            .variables
            .get(name)
            .map(|v| v.to_string())
            .unwrap_or_default();
        self.var_status = None;
    }

    /// Wert aus dem Variablen-Editor übernehmen – mit Rückmeldung statt stillem Scheitern.
    fn save_variable(&mut self) {
        let de = self.german_ui;
        let name = self.var_edit_name.clone();
        let raw = mathview::to_engine_syntax(self.var_edit_value.trim());
        if raw.is_empty() {
            self.var_status = Some((
                (if de { "⚠ Bitte einen Wert eingeben." } else { "⚠ Please enter a value." }).to_string(),
                true,
            ));
            return;
        }
        match self.engine.evaluate_preview(&raw, false) {
            Ok(out) => match out.val.or_else(|| out.numeric_value.map(Value::from_complex)) {
                Some(val) => {
                    self.engine.variables.insert(name.clone(), val);
                    self.engine.matrix_variables.remove(&name);
                    self.mark_state_dirty();
                    let shown = self.engine.number_format.format_f64(val.to_f64());
                    self.var_status = Some((
                        if de { format!("✓ {name} = {shown} gespeichert.") } else { format!("✓ Saved {name} = {shown}.") },
                        false,
                    ));
                }
                None => {
                    self.var_status = Some((
                        if de {
                            format!("⚠ Kein Zahlenwert. Matrizen bitte im Rechner mit „{name} := …“ zuweisen.")
                        } else {
                            format!("⚠ Not a number. Assign matrices in the calculator with “{name} := …”.")
                        },
                        true,
                    ));
                }
            },
            Err(err) => self.var_status = Some((format!("⚠ {}", i18n::localize_error(&err, de)), true)),
        }
    }

    fn render_unit_converter_card(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let de = self.german_ui;
        card(ui, pal, |ui| {
            card_title(ui, pal, if de { "🔄 Einheiten umrechnen" } else { "🔄 Unit converter" });
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                for cat in UnitCategory::ALL {
                    if ui.selectable_label(self.unit_cat == cat, cat.name(de)).clicked() && self.unit_cat != cat {
                        self.unit_cat = cat;
                        self.unit_from_idx = 0;
                        self.unit_to_idx = 1;
                    }
                }
            });

            let units = self.unit_cat.units();
            self.unit_from_idx = self.unit_from_idx.min(units.len() - 1);
            self.unit_to_idx = self.unit_to_idx.min(units.len() - 1);
            let unit_name = |i: usize| if de { units[i].0 } else { units[i].1 };

            ui.add_space(6.0);
            ui.horizontal_wrapped(|ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.unit_input_str)
                        .desired_width(120.0)
                        .font(FontId::monospace(14.5))
                        .hint_text(if de { "Wert" } else { "value" }),
                );
                egui::ComboBox::from_id_salt("unit_from_cb")
                    .selected_text(unit_name(self.unit_from_idx))
                    .show_ui(ui, |ui| {
                        for i in 0..units.len() {
                            ui.selectable_value(&mut self.unit_from_idx, i, unit_name(i));
                        }
                    });
                ui.label("➡");
                egui::ComboBox::from_id_salt("unit_to_cb")
                    .selected_text(unit_name(self.unit_to_idx))
                    .show_ui(ui, |ui| {
                        for i in 0..units.len() {
                            ui.selectable_value(&mut self.unit_to_idx, i, unit_name(i));
                        }
                    });
            });

            let converted = parse_user_number(&self.unit_input_str, self.engine.number_format).map(|v| {
                if self.unit_cat == UnitCategory::Temperature {
                    convert_temperature(v, self.unit_from_idx, self.unit_to_idx)
                } else {
                    v * units[self.unit_from_idx].2 / units[self.unit_to_idx].2
                }
            });
            ui.horizontal_wrapped(|ui| match converted {
                Some(c) => {
                    ui.label(
                        RichText::new(format!("= {}", self.engine.number_format.format_f64(c)))
                            .font(FontId::proportional(16.0))
                            .strong()
                            .color(pal.primary),
                    );
                    if ui
                        .small_button(if de { "In Rechner übernehmen" } else { "Send to calculator" })
                        .clicked()
                    {
                        self.insert_token(&engine_number(c));
                        self.active_tab = MainTab::Calculator;
                    }
                }
                None => {
                    ui.label(
                        RichText::new(if self.unit_input_str.trim().is_empty() {
                            if de { "Wert eingeben, z. B. 1,5" } else { "Enter a value, e.g. 1.5" }
                        } else if de {
                            "⚠ Keine gültige Zahl"
                        } else {
                            "⚠ Not a valid number"
                        })
                        .color(if self.unit_input_str.trim().is_empty() { pal.on_surface_variant } else { pal.error }),
                    );
                }
            });
        });
    }

    fn slot_label(&self, i: usize) -> String {
        format!("{} {} ({})", if self.german_ui { "Platz" } else { "Slot" }, i + 1, self.keyboard_slots[i])
    }

    fn render_constants_card(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let de = self.german_ui;
        card(ui, pal, |ui| {
            card_title(ui, pal, if de { "Naturkonstanten (CODATA 2022)" } else { "Physical constants (CODATA 2022)" });
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                ui.label("🔍");
                ui.add(
                    egui::TextEdit::singleline(&mut self.constants_search)
                        .desired_width(220.0)
                        .hint_text(if de { "Suchen: c₀, Planck, Avogadro …" } else { "Search: c₀, Planck, Avogadro …" }),
                );
                ui.separator();
                ui.label(if de { "„Auf Tastatur“ belegt:" } else { "“Pin” replaces:" });
                let current = self.slot_label(self.selected_slot_to_assign);
                let labels: Vec<String> = (0..ASSIGNABLE_VARIABLE_SLOTS).map(|i| self.slot_label(i)).collect();
                egui::ComboBox::from_id_salt("slot_pin_combo")
                    .selected_text(current)
                    .show_ui(ui, |ui| {
                        for (i, lbl) in labels.iter().enumerate() {
                            ui.selectable_value(&mut self.selected_slot_to_assign, i, lbl);
                        }
                    });
            });
            if let Some(msg) = &self.const_status {
                ui.label(RichText::new(msg).font(FontId::proportional(12.5)).color(pal.primary));
            }
            ui.add_space(6.0);

            let query = self.constants_search.trim().to_lowercase();
            let matches = |sc: &crate::constants::ScientificConstant| {
                let name = if de { sc.name_de } else { sc.name_en };
                query.is_empty()
                    || name.to_lowercase().contains(&query)
                    || sc.symbol.to_lowercase().contains(&query)
                    || sc.token.to_lowercase().contains(&query)
            };
            if !SCIENTIFIC_CONSTANTS.iter().any(&matches) {
                ui.label(
                    RichText::new(if de {
                        format!("Keine Konstante gefunden für „{}“.", self.constants_search.trim())
                    } else {
                        format!("No constant matches “{}”.", self.constants_search.trim())
                    })
                    .color(pal.on_surface_variant),
                );
                return;
            }

            let narrow = ui.available_width() < 560.0;
            let nf = self.engine.number_format;
            for cat in [
                ConstantCategory::Universal,
                ConstantCategory::Electromagnetic,
                ConstantCategory::Chemical,
                ConstantCategory::Mathematical,
            ] {
                let items: Vec<&crate::constants::ScientificConstant> =
                    SCIENTIFIC_CONSTANTS.iter().filter(|c| c.category == cat && matches(c)).collect();
                if items.is_empty() {
                    continue;
                }
                ui.label(
                    RichText::new(if de { cat.title_de() } else { cat.title_en() })
                        .font(FontId::proportional(14.5))
                        .strong()
                        .color(pal.primary),
                );
                ui.add_space(2.0);
                for sc in items {
                    let mut insert = false;
                    let mut pin = false;
                    Frame::new()
                        .fill(pal.surface_container_low)
                        .corner_radius(CornerRadius::same(10))
                        .inner_margin(egui::Margin::symmetric(10, 6))
                        .show(ui, |ui| {
                            ui.set_min_width(ui.available_width());
                            let mut actions = |ui: &mut Ui| {
                                insert |= ui.button(if de { "➕ In Rechner" } else { "➕ Insert" }).clicked();
                                pin |= ui
                                    .small_button(if de { "📌 Auf Tastatur" } else { "📌 Pin to keyboard" })
                                    .on_hover_text(if de {
                                        "Auf den oben gewählten Platz der „a b c“-Tastatur legen"
                                    } else {
                                        "Put on the keyboard slot selected above (“a b c” section)"
                                    })
                                    .clicked();
                            };
                            if narrow {
                                ui.horizontal(|ui| constant_info(ui, sc, pal, de, nf));
                                ui.horizontal(|ui| actions(ui));
                            } else {
                                ui.horizontal(|ui| {
                                    constant_info(ui, sc, pal, de, nf);
                                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| actions(ui));
                                });
                            }
                        });
                    if insert {
                        self.insert_token(sc.token);
                        self.active_tab = MainTab::Calculator;
                    }
                    if pin {
                        let slot = self.selected_slot_to_assign;
                        self.keyboard_slots[slot] = sc.token.to_string();
                        self.const_status = Some(if de {
                            format!("✓ {} liegt jetzt auf Platz {} der „a b c“-Tastatur.", sc.symbol, slot + 1)
                        } else {
                            format!("✓ {} is now on slot {} of the “a b c” keyboard.", sc.symbol, slot + 1)
                        });
                    }
                }
                ui.add_space(6.0);
            }
        });
    }

    fn render_settings_tab(&mut self, ui: &mut Ui, pal: &MaterialYouPalette) {
        let de = self.german_ui;
        ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            card(ui, pal, |ui| {
                card_title(ui, pal, if de { "🎨 Darstellung" } else { "🎨 Appearance" });
                ui.add_space(6.0);
                ui.horizontal_wrapped(|ui| {
                    ui.label(if de { "Farbmodus:" } else { "Color mode:" });
                    for mode in [ThemeMode::Dark, ThemeMode::Light, ThemeMode::Amoled] {
                        ui.selectable_value(&mut self.theme_mode, mode, if de { mode.label_de() } else { mode.label_en() });
                    }
                });
                ui.add_space(4.0);
                ui.horizontal_wrapped(|ui| {
                    ui.label(if de { "Akzentfarbe:" } else { "Accent color:" });
                    for seed in AccentSeed::ALL {
                        let selected = self.accent_seed == seed;
                        let (fill, fg, dot) = if selected {
                            let c = seed.seed_color();
                            (c, readable_on(c), readable_on(c))
                        } else {
                            (pal.surface_container_high, pal.on_surface, seed.seed_color())
                        };
                        let font = FontId::proportional(13.5);
                        let mut job = egui::text::LayoutJob::default();
                        job.append("● ", 0.0, egui::TextFormat { font_id: font.clone(), color: dot, ..Default::default() });
                        job.append(seed.name(de), 0.0, egui::TextFormat { font_id: font, color: fg, ..Default::default() });
                        let btn = egui::Button::new(egui::WidgetText::from(job))
                            .fill(fill)
                            .corner_radius(CornerRadius::same(12));
                        let resp = ui.add(btn);
                        resp.widget_info(|| {
                            egui::WidgetInfo::selected(egui::WidgetType::RadioButton, true, selected, seed.name(de))
                        });
                        if resp.clicked() {
                            self.accent_seed = seed;
                        }
                    }
                });
                ui.add_space(4.0);
                ui.horizontal_wrapped(|ui| {
                    ui.label(if de { "Sprache:" } else { "Language:" });
                    ui.selectable_value(&mut self.german_ui, true, "Deutsch");
                    ui.selectable_value(&mut self.german_ui, false, "English");
                });
                ui.add_space(4.0);
                ui.horizontal_wrapped(|ui| {
                    ui.label(if de { "Tastaturgröße:" } else { "Keyboard size:" });
                    for (scale, name_de, name_en) in [
                        (KEYBOARD_SCALES[0], "Klein", "Small"),
                        (KEYBOARD_SCALES[1], "Standard", "Default"),
                        (KEYBOARD_SCALES[2], "Groß", "Large"),
                    ] {
                        ui.selectable_value(&mut self.keyboard_scale, scale, if de { name_de } else { name_en });
                    }
                });
            });

            ui.add_space(10.0);
            card(ui, pal, |ui| {
                card_title(ui, pal, if de { "∑ Rechenwerk & Zahlenformat" } else { "∑ Math engine & number format" });
                ui.add_space(6.0);

                // Dezimalzeichen und Tausendertrennung sind zwei unabhängige
                // Fragen (vorher steuerten zwei Zeilen denselben Zustand).
                let prev_nf = self.engine.number_format;
                let (comma, grouping) = nf_parts(prev_nf);
                ui.horizontal_wrapped(|ui| {
                    ui.label(if de { "Dezimalzeichen:" } else { "Decimal separator:" });
                    if ui.selectable_label(comma, if de { "Komma (1,5)" } else { "Comma (1,5)" }).clicked() {
                        self.engine.number_format = nf_from(true, grouping);
                    }
                    if ui.selectable_label(!comma, if de { "Punkt (1.5)" } else { "Point (1.5)" }).clicked() {
                        self.engine.number_format = nf_from(false, grouping);
                    }
                });
                let (comma, grouping) = nf_parts(self.engine.number_format);
                ui.horizontal_wrapped(|ui| {
                    ui.label(if de { "Tausendertrennung:" } else { "Digit grouping:" });
                    let mark = match (comma, de) {
                        (true, true) => "Punkt (1.234)",
                        (true, false) => "Point (1.234)",
                        (false, true) => "Komma (1,234)",
                        (false, false) => "Comma (1,234)",
                    };
                    for (g, label) in [
                        (Grouping::Space, if de { "Leerzeichen (1 234)" } else { "Space (1 234)" }),
                        (Grouping::Mark, mark),
                        (Grouping::None, if de { "Keine (1234)" } else { "None (1234)" }),
                    ] {
                        if ui.selectable_label(grouping == g, label).clicked() {
                            self.engine.number_format = nf_from(comma, g);
                        }
                    }
                });
                ui.label(
                    RichText::new(format!(
                        "{} {}",
                        if de { "Beispiel:" } else { "Example:" },
                        self.engine.number_format.example()
                    ))
                    .font(FontId::proportional(12.0))
                    .color(pal.on_surface_variant),
                );
                if self.engine.number_format != prev_nf {
                    self.mark_state_dirty();
                }

                ui.add_space(6.0);
                material_switch(
                    ui,
                    pal,
                    &mut self.engine.show_exact_first,
                    if de {
                        "Exakte Ergebnisse (Brüche, π, Wurzeln) im Verlauf zuerst anzeigen"
                    } else {
                        "Show exact results (fractions, π, surds) first in history"
                    },
                );
                if material_switch(
                    ui,
                    pal,
                    &mut self.engine.modulo_always_positive,
                    if de { "Modulo-Ergebnis immer positiv (a mod b ≥ 0)" } else { "Modulo result always positive (a mod b ≥ 0)" },
                )
                .changed()
                {
                    self.mark_state_dirty();
                }
                material_switch(
                    ui,
                    pal,
                    &mut self.engine.auto_ans_insertion,
                    if de {
                        "ANS automatisch vor Operatoren einfügen (+, −, ×, ÷, ^) bei leerer Eingabe"
                    } else {
                        "Insert ANS before operators (+, −, ×, ÷, ^) when the input is empty"
                    },
                );
            });

            ui.add_space(10.0);
            card(ui, pal, |ui| {
                card_title(ui, pal, if de { "⌨ Tastatur" } else { "⌨ Keyboard" });
                ui.add_space(6.0);
                material_switch(
                    ui,
                    pal,
                    &mut self.dual_keyboard,
                    if de {
                        "Zweiteilige Tastatur: Funktionen links, Ziffernblock rechts"
                    } else {
                        "Split keyboard: functions on the left, number pad on the right"
                    },
                );
                material_switch(
                    ui,
                    pal,
                    &mut self.show_hex_row,
                    if de { "Hex-Ziffern (A–F) über der Tastatur anzeigen" } else { "Show hex digits (A–F) above the keyboard" },
                );
            });
        });
    }
}

/// Karte mit voller Spaltenbreite (vorher schrumpften Karten auf ihren Inhalt
/// und hatten unterschiedlich breite Ränder).
fn card<R>(ui: &mut Ui, pal: &MaterialYouPalette, add: impl FnOnce(&mut Ui) -> R) -> R {
    Frame::new()
        .fill(pal.surface_container)
        .corner_radius(CornerRadius::same(16))
        .inner_margin(egui::Margin::same(12))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            add(ui)
        })
        .inner
}

fn card_title(ui: &mut Ui, pal: &MaterialYouPalette, text: &str) {
    ui.label(RichText::new(text).font(FontId::proportional(15.0)).strong().color(pal.primary));
}

/// Symbol, Name und Wert einer Konstante.
fn constant_info(
    ui: &mut Ui,
    sc: &crate::constants::ScientificConstant,
    pal: &MaterialYouPalette,
    de: bool,
    nf: NumberFormatMode,
) {
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
    let value = if de { sc.display_value.to_string() } else { sc.display_value.replace("(exakt)", "(exact)") };
    // Dezimalzeichen wie im eingestellten Zahlenformat
    let value = if decimal_and_group_seps(nf).1 == ',' { value.replace('.', ",") } else { value };
    // Dimensionslose Größen ohne angehängte „1“
    let value = if sc.unit == "1" { value } else { format!("{value} {}", sc.unit) };
    ui.vertical(|ui| {
        ui.label(
            RichText::new(if de { sc.name_de } else { sc.name_en })
                .font(FontId::proportional(13.5))
                .strong(),
        );
        ui.label(RichText::new(value).font(FontId::monospace(12.0)).color(pal.on_surface_variant));
    });
}

/// Spalte mit begrenzter Breite, waagerecht zentriert. Auf großen Fenstern
/// liegen Eingabe, Ergebnis und Tasten so nah beieinander.
fn centered_column<R>(ui: &mut Ui, max_width: f32, add: impl FnOnce(&mut Ui) -> R) -> R {
    let avail = ui.available_rect_before_wrap();
    let w = avail.width().min(max_width);
    let rect = egui::Rect::from_min_size(
        egui::pos2(avail.center().x - w / 2.0, avail.top()),
        egui::vec2(w, avail.height()),
    );
    ui.scope_builder(egui::UiBuilder::new().max_rect(rect), add).inner
}

fn window_title(de: bool) -> &'static str {
    if de {
        "Niob — Wissenschaftlicher Rechner"
    } else {
        "Niob — Scientific Calculator"
    }
}

/// Kommt im Ausdruck ein freies `x` vor (kein `exp`, kein `0x1F`, keine
/// Laufvariable wie in `int(x^2, x, 0, 3)`)? Nur dann gibt es „Plotten“.
fn plottable_in_x(raw: &str) -> bool {
    let compact: String = raw.chars().filter(|c| !c.is_whitespace()).collect();
    if compact.contains(",x,") {
        return false;
    }
    let mut ident = String::new();
    for ch in compact.chars().chain(std::iter::once(' ')) {
        if ch.is_ascii_digit() && ident.is_empty() {
            continue; // Zahl, z. B. die 2 in „2x“
        }
        if ch.is_alphanumeric() || ch == '_' {
            ident.push(ch);
        } else {
            if ident == "x" {
                return true;
            }
            ident.clear();
        }
    }
    false
}

/// Eingabe des Einheitenrechners lesen. Akzeptiert Dezimalkomma und -punkt
/// sowie Tausendertrennung passend zum gewählten Zahlenformat.
fn parse_user_number(input: &str, nf: NumberFormatMode) -> Option<f64> {
    let t: String = input
        .trim()
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '\'')
        .map(|c| if c == '−' { '-' } else { c })
        .collect();
    if t.is_empty() {
        return None;
    }
    let comma_decimal = decimal_and_group_seps(nf).1 == ',';
    let groups_of_three = |sep: char| t.split(sep).skip(1).all(|g| g.len() == 3);
    let normalized = if t.contains(',') && t.contains('.') {
        if comma_decimal {
            t.replace('.', "").replace(',', ".")
        } else {
            t.replace(',', "")
        }
    } else if t.contains(',') {
        if !comma_decimal && groups_of_three(',') {
            t.replace(',', "")
        } else {
            t.replace(',', ".")
        }
    } else if t.contains('.') && comma_decimal && groups_of_three('.') && t.matches('.').count() > 1 {
        t.replace('.', "")
    } else {
        t
    };
    normalized.parse::<f64>().ok().filter(|v| v.is_finite())
}

/// Zahl in Engine-Schreibweise mit höchstens 12 gültigen Stellen
/// (`1.2500000000000002` → `1.25`); negative Werte in Klammern.
fn engine_number(v: f64) -> String {
    let rounded: f64 = format!("{:.11e}", v).parse().unwrap_or(v);
    let s = format!("{rounded:?}");
    let s = s.strip_suffix(".0").map(str::to_string).unwrap_or(s);
    if rounded < 0.0 {
        format!("({s})")
    } else {
        s
    }
}

/// Nachkommastellen der Achsenbeschriftung für einen Gitterabstand von 1, 2 oder 5 × 10ⁿ.
fn axis_decimals(step: f64) -> usize {
    (-step.log10()).ceil().clamp(0.0, 12.0) as usize
}

/// Achsenbeschriftung; sehr große und sehr kleine Werte in Exponentenschreibweise.
fn axis_label(v: f64, decimals: usize) -> String {
    let a = v.abs();
    if a >= 1e6 || (a > 0.0 && a < 1e-4) {
        let s = format!("{:.2e}", v);
        let (mant, exp) = s.split_once('e').unwrap_or((&s, "0"));
        let mant = if mant.contains('.') { mant.trim_end_matches('0').trim_end_matches('.') } else { mant };
        return format!("{mant}e{exp}");
    }
    let s = format!("{:.*}", decimals, v);
    match s.strip_prefix('-') {
        Some(rest) if rest.chars().all(|c| c == '0' || c == '.') => rest.to_string(),
        _ => s,
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Grouping {
    Space,
    /// Punkt bei Dezimalkomma, Komma bei Dezimalpunkt
    Mark,
    None,
}

fn nf_parts(nf: NumberFormatMode) -> (bool, Grouping) {
    match nf {
        NumberFormatMode::SpacePoint => (false, Grouping::Space),
        NumberFormatMode::CommaPoint => (false, Grouping::Mark),
        NumberFormatMode::NonePoint => (false, Grouping::None),
        NumberFormatMode::SpaceComma => (true, Grouping::Space),
        NumberFormatMode::PointComma => (true, Grouping::Mark),
        NumberFormatMode::NoneComma => (true, Grouping::None),
    }
}

fn nf_from(comma_decimal: bool, grouping: Grouping) -> NumberFormatMode {
    match (comma_decimal, grouping) {
        (false, Grouping::Space) => NumberFormatMode::SpacePoint,
        (false, Grouping::Mark) => NumberFormatMode::CommaPoint,
        (false, Grouping::None) => NumberFormatMode::NonePoint,
        (true, Grouping::Space) => NumberFormatMode::SpaceComma,
        (true, Grouping::Mark) => NumberFormatMode::PointComma,
        (true, Grouping::None) => NumberFormatMode::NoneComma,
    }
}

fn material_switch(
    ui: &mut Ui,
    pal: &MaterialYouPalette,
    on: &mut bool,
    label: &str,
) -> egui::Response {
    ui.horizontal(|ui| {
        let desired_size = egui::vec2(46.0, 26.0);
        let (rect, mut response) = ui.allocate_exact_size(desired_size, Sense::click());
        let lbl_resp = ui.add(
            egui::Label::new(RichText::new(label).color(pal.on_surface))
                .wrap()
                .sense(Sense::click()),
        );
        if response.clicked() || lbl_resp.clicked() {
            *on = !*on;
            response.mark_changed();
        }
        // Für Screenreader: Schalter mit Beschriftung und Zustand
        let state = *on;
        response.widget_info(|| egui::WidgetInfo::selected(egui::WidgetType::Checkbox, true, state, label));
        if ui.is_rect_visible(rect) {
            let how_on = ui.ctx().animate_bool_responsive(response.id, *on);
            let radius = rect.height() * 0.5;
            let track_fill = if *on {
                pal.primary
            } else {
                pal.surface_container_highest
            };
            let track_stroke = if *on {
                Stroke::NONE
            } else {
                Stroke::new(1.5_f32, pal.on_surface_variant)
            };
            ui.painter()
                .rect(rect, CornerRadius::same(radius as u8), track_fill, track_stroke, StrokeKind::Inside);
            let thumb_r = radius - 3.5 - (1.0 - how_on) * 2.0;
            let circle_x = egui::lerp((rect.left() + radius)..=(rect.right() - radius), how_on);
            let center = Pos2::new(circle_x, rect.center().y);
            let thumb_col = if *on {
                pal.on_primary
            } else {
                pal.on_surface_variant
            };
            ui.painter().circle_filled(center, thumb_r, thumb_col);
        }
        response
    })
    .inner
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
        self.draw(ui);
    }
}

impl NiobCalculatorApp {
    /// Ein Frame der gesamten Oberfläche (auch ohne eframe aufrufbar, z. B. in Tests).
    fn draw(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        self.poll_async_eval();
        let pal = MaterialYouPalette::generate(self.theme_mode, self.accent_seed);
        pal.apply(&ctx, self.theme_mode);

        self.handle_keyboard_input(&ctx);

        if self.window_title_de != Some(self.german_ui) {
            self.window_title_de = Some(self.german_ui);
            ctx.send_viewport_cmd(egui::ViewportCommand::Title(window_title(self.german_ui).to_string()));
        }

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
                MainTab::Calculator => centered_column(ui, 1100.0, |ui| self.render_calculator_tab(ui, &pal)),
                MainTab::Plotter => self.render_plotter_tab(ui, &pal),
                MainTab::VariablesAndConstants => {
                    centered_column(ui, 1000.0, |ui| self.render_variables_and_constants_tab(ui, &pal))
                }
                MainTab::Settings => centered_column(ui, 1000.0, |ui| self.render_settings_tab(ui, &pal)),
            });

        self.persist_prefs_if_changed();
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
            .with_title(window_title(true))
            .with_inner_size([960.0, 680.0])
            .with_min_inner_size([420.0, 520.0]),
        ..Default::default()
    };

    eframe::run_native(
        "Niob",
        options,
        Box::new(move |cc| {
            install_fonts(&cc.egui_ctx);
            Ok(Box::new(NiobCalculatorApp::new(screenshot_path)))
        }),
    )
}

/// Ersatzschriften für mathematische Zeichen, die der eingebauten
/// Proportionalschrift fehlen (z. B. `⁻`, `←`, `⌫`, `✓`).
fn install_fonts(ctx: &egui::Context) {
    use egui::{FontData, FontDefinitions, FontFamily};
    let mut fonts = FontDefinitions::default();
    // Die mitgelieferte Hack-Schrift deckt Hochzeichen und Pfeile ab.
    if let Some(list) = fonts.families.get_mut(&FontFamily::Proportional) {
        if !list.iter().any(|n| n == "Hack") {
            list.push("Hack".to_owned());
        }
    }
    // Symbolschrift des Systems, falls vorhanden (⌫, ✓ …).
    const SYSTEM_SYMBOL_FONTS: [&str; 3] = [
        "C:\\Windows\\Fonts\\seguisym.ttf",
        "/System/Library/Fonts/Apple Symbols.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ];
    for path in SYSTEM_SYMBOL_FONTS {
        if let Ok(bytes) = std::fs::read(path) {
            fonts
                .font_data
                .insert("system-symbols".to_owned(), std::sync::Arc::new(FontData::from_owned(bytes)));
            for family in [FontFamily::Proportional, FontFamily::Monospace] {
                if let Some(list) = fonts.families.get_mut(&family) {
                    list.push("system-symbols".to_owned());
                }
            }
            break;
        }
    }
    ctx.set_fonts(fonts);
}

#[cfg(test)]
mod ui_tests {
    use super::*;

    fn preview_kind(input: &str) -> Option<PreviewError> {
        let raw = mathview::to_engine_syntax(input);
        let mut engine = MathEngine::default();
        engine.evaluate_preview(&raw, false).err().map(|e| classify_preview_error(&e, &raw))
    }

    #[test]
    fn preview_separates_typos_from_unfinished_input() {
        // Befund aus dem UI-Test: beide meldeten „Ausdruck wird vervollständigt“
        assert_eq!(preview_kind("2+"), Some(PreviewError::Incomplete));
        assert_eq!(preview_kind("fao(2)"), Some(PreviewError::Show));
        assert_eq!(preview_kind("sin("), Some(PreviewError::Incomplete));
        assert_eq!(preview_kind("nthroot(3, "), Some(PreviewError::Incomplete));
        // Befund: log() mit fehlendem Argument
        assert_eq!(preview_kind("log()"), Some(PreviewError::Show));
        assert_eq!(preview_kind("1÷0"), Some(PreviewError::Show));
        // Unbekannter Name: am Ende wird er womöglich noch getippt, sonst Tippfehler
        let unknown = "Unbekannte Variable oder Konstante 'fa'";
        assert_eq!(classify_preview_error(unknown, "2+fa"), PreviewError::Incomplete);
        assert_eq!(classify_preview_error(unknown, "fa+2"), PreviewError::Show);
        assert_eq!(classify_preview_error(unknown, "2+sofa"), PreviewError::Show);
        assert_eq!(preview_kind("√(2)÷2"), None);
    }

    #[test]
    fn plot_readout_reports_missing_derivatives_and_input_errors() {
        let engine = MathEngine::default();
        // Befunde aus dem UI-Test: f'(0) = 0 bei √x und |x| war falsch
        let (txt, err) = plot_readout(&engine, "√(x)", 0.0, true);
        assert!(txt.contains("f'(0.00) existiert nicht") && !err, "{txt}");
        let (txt, err) = plot_readout(&engine, "abs(x)", 0.0, true);
        assert!(txt.contains("f'(0.00) existiert nicht") && !err, "{txt}");
        // Unbekannte Funktion wird gemeldet statt still ignoriert
        let (txt, err) = plot_readout(&engine, "fao(2)", 0.0, true);
        assert!(err && txt.contains("Unbekannte Funktion 'fao'"), "{txt}");
        // Außerhalb des Definitionsbereichs: kein Eingabefehler
        let (txt, err) = plot_readout(&engine, "√(x)", -1.0, true);
        assert!(!err && txt.contains("nicht definiert"), "{txt}");
        // Befund: Definitionslücke ist kein Eingabefehler …
        let (txt, err) = plot_readout(&engine, "√(x−100)", 1.0, true);
        assert!(!err && txt == "f(1.00) nicht definiert", "{txt}");
        // … und ein Pol verdeckt keinen unbekannten Namen mehr
        let (txt, err) = plot_readout(&engine, "1÷(x−1)+fao(x)", 1.0, true);
        assert!(err && txt.contains("Unbekannte Funktion 'fao'"), "{txt}");
        let (txt, err) = plot_readout(&engine, "q×x", 2.0, true);
        assert!(err && txt.contains("Unbekannte Variable"), "{txt}");
        let (txt, err) = plot_readout(&engine, "sum(k^2, k, 1, 3)+x", 0.0, true);
        assert!(!err && txt.starts_with("f(0.00) = 14.0000"), "{txt}");
        // Befund: Laufvariable von defint ist keine unbekannte Variable …
        let (txt, err) = plot_readout(&engine, "defint(t,t,0,x)", 1.0, true);
        assert!(!err && txt.starts_with("f(1.00) = 0.5000"), "{txt}");
        let (txt, err) = plot_readout(&engine, "int(t^2,t,0,x)+sum(k,k,1,3)", 1.0, true);
        assert!(!err, "{txt}");
        // … und ein fehlendes Argument ist keine Definitionslücke
        let (txt, err) = plot_readout(&engine, "log()", 1.0, true);
        assert!(err && txt.contains("benötigt mindestens 1 Argument"), "{txt}");
        let (txt, err) = plot_readout(&engine, "nthroot(x)", 1.0, true);
        assert!(err && txt.contains("nthroot(n, x) benötigt 2 Argumente"), "{txt}");
        // Wertebereichsfehler bleibt Definitionslücke, kein Eingabefehler
        let (txt, err) = plot_readout(&engine, "binomial(x, 2)", 0.5, true);
        assert!(!err, "{txt}");
        // Befund: det mit x zeichnete konstant 2 (gespeichertes x statt Spurwert)
        let (txt, err) = plot_readout(&engine, "det([[x,2],[3,4]])", 1.0, true);
        assert!(!err && txt == "f(1.00) = -2.0000   |   f'(1.00) = 4.0000", "{txt}");
        assert_eq!(engine.eval_for_x("det([[x,2],[3,4]])", -3.25), Some(-19.0));
        assert_eq!(engine.eval_for_x("sum(det([[k,1],[0,x]]),k,1,3)", 2.0), Some(12.0));
        assert_eq!(engine.eval_for_x("det([[2x]])", -3.25), Some(-6.5));
        assert_eq!(engine.eval_for_x("det([[sum(x,x,1,3)]])", 2.0), Some(6.0));
        let (txt, err) = plot_readout(&engine, "det(inv([[x,0],[0,1]]))", 4.0, true);
        assert!(!err && txt == "f(4.00) = 0.2500   |   f'(4.00) = -0.0625", "{txt}");
        let (txt, err) = plot_readout(&engine, "det([[x]]+[[x]])", 2.0, true);
        assert!(!err && txt == "f(2.00) = 4.0000   |   f'(2.00) = 2.0000", "{txt}");
        // Befund: fehlende Argumente bei sqrt und mod
        for (expr, msg) in [
            ("sqrt()", "benötigt mindestens 1 Argument"),
            ("mod(x)", "mod(a, b) benötigt 2 Argumente"),
            ("factorial(x)", "Unbekannte Funktion 'factorial'"),
            ("[[1,2],[3,4]]", "nur innerhalb von det"),
        ] {
            let (txt, err) = plot_readout(&engine, expr, 1.0, true);
            assert!(err && txt.contains(msg), "{expr}: {txt}");
        }
        for expr in ["√(x)", "mod(x,3)", "x mod 3", "x!"] {
            let (txt, err) = plot_readout(&engine, expr, 1.0, true);
            assert!(!err, "{expr}: {txt}");
        }
        // Befund: schnell schwingende Funktion
        let (txt, _) = plot_readout(&engine, "sin(628318.5307179586×x)", 0.0, true);
        assert!(txt.ends_with("f'(0.00) = 628318.53"), "{txt}");
        // Befund: Ableitung bei kleinen Werten
        let (txt, _) = plot_readout(&engine, "10^−6×abs(x)", 0.0, true);
        assert!(txt.ends_with("f'(0.00) existiert nicht"), "{txt}");
        let (txt, _) = plot_readout(&engine, "√(x)", 1e-7, true);
        assert!(txt.contains("f(1.00e-7) = 3.1623e-4") && txt.contains("f'(1.00e-7) = 1581.13"), "{txt}");
        // Normalfall
        let (txt, _) = plot_readout(&engine, "x^2", 1.0, true);
        assert_eq!(txt, "f(1.00) = 1.0000   |   f'(1.00) = 2.0000");
    }

    #[test]
    fn keypad_and_cursor_editing() {
        // Testinstanz ohne Zugriff auf die echte Zustandsdatei
        let mut app = NiobCalculatorApp::new(Some("review-fixture".into()));
        app.history.clear();
        app.clear_input();
        app.engine.auto_ans_insertion = false;
        for t in ["1", "2", "3", "4", "5"] {
            app.insert_token(t);
        }
        // Befund aus dem UI-Test: nach zweimal ← löschte ⌫ trotzdem die letzte Ziffer
        app.input_sel = (3, 3);
        app.backspace();
        assert_eq!(app.input_expr, "1245");
        assert_eq!(app.input_sel, (2, 2));
        // Einfügen an der Cursorposition, ASCII wird zu Anzeige-Schreibweise
        app.insert_token("*pi");
        assert_eq!(app.input_expr, "12×π45");
        assert_eq!(app.engine_input(), "12*pi45");
        // Auswahl wird ersetzt
        app.input_sel = (0, 2);
        app.insert_token("sqrt(");
        assert_eq!(app.input_expr, "√(×π45");
        // Funktionsnamen am Stück löschen
        app.input_sel = (2, 2);
        app.backspace();
        assert_eq!(app.input_expr, "×π45");
    }

    /// Befund: „Plot“ erschien bei jedem Ausdruck mit einem „x“, auch bei exp(2).
    #[test]
    fn plot_button_only_for_a_free_x() {
        for (expr, want) in [
            ("x^2", true),
            ("2x+1", true),
            ("sin(x)", true),
            ("sum(k, k, 1, x)", true),
            ("exp(2)", false),
            ("0x1F", false),
            ("max", false),
            ("int(x^2, x, 0, 3)", false),
            ("deriv(x^3, x, 2)", false),
        ] {
            assert_eq!(plottable_in_x(expr), want, "{expr}");
        }
    }

    /// Befund: „Plot“ im Verlauf überschrieb f₁ ohne Rückfrage.
    #[test]
    fn plotting_from_history_does_not_overwrite_silently() {
        let mut app = NiobCalculatorApp::new(Some("review-fixture".into()));
        let before: Vec<String> = app.plot_functions.iter().map(|p| p.expr.clone()).collect();
        app.plot_expression("x^3");
        assert_eq!(app.plot_functions.len(), before.len() + 1);
        assert!(before.iter().zip(&app.plot_functions).all(|(b, p)| *b == p.expr));
        app.plot_expression("x^3");
        assert_eq!(app.plot_functions.len(), before.len() + 1, "kein Duplikat");
        while app.plot_functions.len() < MAX_PLOT_FUNCTIONS {
            let n = app.plot_functions.len();
            app.plot_expression(&format!("x+{n}"));
        }
        assert!(app.plot_notice.is_none());
        app.plot_expression("x-1");
        assert_eq!(app.plot_functions.len(), MAX_PLOT_FUNCTIONS);
        assert!(app.plot_notice.is_some(), "Ersetzen wird angezeigt");
        let mut colors: Vec<usize> = app.plot_functions.iter().map(|p| p.color_idx).collect();
        colors.dedup();
        assert_eq!(colors.len(), MAX_PLOT_FUNCTIONS, "jede Kurve hat eine eigene Farbe");
    }

    /// Befund: Einheitenrechner machte aus „1,5“ stillschweigend 0.
    #[test]
    fn unit_converter_reads_locale_numbers() {
        use NumberFormatMode::*;
        assert_eq!(parse_user_number("1,5", SpaceComma), Some(1.5));
        assert_eq!(parse_user_number("1,5", SpacePoint), Some(1.5));
        assert_eq!(parse_user_number("1.5", SpaceComma), Some(1.5));
        assert_eq!(parse_user_number("1,250", CommaPoint), Some(1250.0));
        assert_eq!(parse_user_number("1.250,75", PointComma), Some(1250.75));
        assert_eq!(parse_user_number("1.250.000", PointComma), Some(1_250_000.0));
        assert_eq!(parse_user_number("1 250.5", SpacePoint), Some(1250.5));
        assert_eq!(parse_user_number("−3", SpacePoint), Some(-3.0));
        assert_eq!(parse_user_number("abc", SpacePoint), None);
        assert_eq!(parse_user_number("  ", SpacePoint), None);
        assert_eq!(engine_number(1.25 + 2e-16), "1.25");
        assert_eq!(engine_number(1250.0), "1250");
        assert_eq!(engine_number(-0.5), "(-0.5)");
        assert_eq!(engine_number(1e-20), "1e-20");
    }

    /// Befund: am linken Rand stand „0“ statt „−10“; bei starkem Zoom leere Beschriftungen.
    #[test]
    fn axis_labels_follow_the_grid_step() {
        assert_eq!(axis_label(-10.0, axis_decimals(2.0)), "-10");
        assert_eq!(axis_label(0.05, axis_decimals(0.05)), "0.05");
        assert_eq!(axis_label(0.1, axis_decimals(0.05)), "0.10");
        assert_eq!(axis_label(0.0003, axis_decimals(0.0001)), "0.0003");
        assert_eq!(axis_label(2e-6, axis_decimals(1e-6)), "2e-6");
        assert_eq!(axis_label(5e6, axis_decimals(1e6)), "5e6");
        assert_eq!(axis_label(-0.30000000004, axis_decimals(0.1)), "-0.3");
        assert_eq!(axis_label(-0.00000000001, axis_decimals(0.5)), "-1e-11");
    }

    /// Befund: frische Installation hatte a = 5, b = 3, x = 2, sodass `x+1` 3 ergab.
    #[test]
    fn fresh_start_has_no_preset_variables() {
        let app = NiobCalculatorApp::new(Some("review-fixture".into()));
        assert!(app.engine.variables.is_empty());
    }

    /// Befund: Alle Statuszeilen im Plotter begannen mit „f(…)“; Fehler blieben im
    /// Englisch-Modus deutsch.
    #[test]
    fn plot_readout_names_the_curve_and_follows_the_language() {
        let engine = MathEngine::default();
        let (txt, err) = plot_readout_named(&engine, "x^2", 1.0, true, "f₂");
        assert!(!err);
        assert_eq!(txt, "f₂(1.00) = 1.0000   |   f₂'(1.00) = 2.0000");
        let (txt, err) = plot_readout_named(&engine, "fao(2)", 0.0, false, "f₁");
        assert!(err && txt == "⚠ Unknown function 'fao'", "{txt}");
        let (txt, _) = plot_readout_named(&engine, "√(x)", -1.0, false, "f₃");
        assert_eq!(txt, "f₃(-1.00) undefined");
    }

    /// Befund aus dem Live-Test: Escape leerte die Formelzeile nicht, weil egui
    /// ihr den Fokus schon vor dem Textfeld entzog.
    #[test]
    fn escape_clears_the_formula_input() {
        let mut app = NiobCalculatorApp::new(Some("review-fixture".into()));
        let ctx = egui::Context::default();
        let frame = |app: &mut NiobCalculatorApp, events: Vec<egui::Event>| {
            let input = egui::RawInput {
                screen_rect: Some(egui::Rect::from_min_size(egui::Pos2::ZERO, egui::vec2(960.0, 680.0))),
                events,
                focused: true,
                ..Default::default()
            };
            let _ = ctx.run_ui(input, |ui| app.draw(ui));
        };
        for _ in 0..3 {
            frame(&mut app, vec![]);
        }
        assert!(!app.input_expr.is_empty());
        let escape = egui::Event::Key {
            key: egui::Key::Escape,
            physical_key: None,
            pressed: true,
            repeat: false,
            modifiers: egui::Modifiers::NONE,
        };
        frame(&mut app, vec![escape]);
        frame(&mut app, vec![]);
        assert!(app.input_expr.is_empty(), "{}", app.input_expr);
    }

    #[test]
    fn number_format_parts_round_trip() {
        for nf in NumberFormatMode::ALL {
            let (comma, grouping) = nf_parts(nf);
            assert_eq!(nf_from(comma, grouping), nf);
        }
    }
}
