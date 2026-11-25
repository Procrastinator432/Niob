//! Gemeinsame mathematische Darstellung der Desktop-Oberfläche.
//!
//! Rechner, Verlauf und Plotter zeigen Formeln über dieses Modul an:
//!
//! * [`to_display_syntax`] / [`to_engine_syntax`]: Umwandlung zwischen der
//!   Engine-Schreibweise (`sqrt(2)/2*pi`) und der Schreibweise in Eingabefeldern
//!   (`√(2)÷2×π`). Die Engine bekommt immer die ASCII-Form.
//! * [`edit_layout_job`]: Layout für editierbare Felder. Exponenten werden
//!   angehoben, der Text bleibt aber Zeichen für Zeichen erhalten, damit Cursor,
//!   Auswahl und Einfügen unverändert funktionieren.
//! * [`rich_job`]: setzt Unicode-Hoch-/Tiefzahlen (`10⁻²⁰`, `sin⁻¹`, `xⁿ`) als
//!   angehobenen bzw. abgesenkten Text – unabhängig davon, ob die Schrift die
//!   Sonderzeichen enthält.
//! * [`parse_math`] + [`show_math`]: zweidimensionaler Formelsatz mit
//!   Bruchstrichen, Wurzelzeichen und Exponenten. Die Grammatik entspricht der
//!   Engine (Punkt- vor Strichrechnung, implizite Multiplikation, `^` rechts-
//!   assoziativ, `√` und Vorzeichen binden schwächer als `^`).

use std::sync::Arc;

use egui::text::LayoutJob;
use egui::{Align, Color32, FontId, Galley, Pos2, Response, Sense, Shape, Stroke, TextFormat, Ui, Vec2};

// ---------------------------------------------------------------------------
// Schreibweise in Eingabefeldern
// ---------------------------------------------------------------------------

fn is_ident_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

/// Engine-Schreibweise → Anzeige in Eingabefeldern (`*`→`×`, `/`→`÷`, `-`→`−`,
/// `pi`→`π`, `sqrt(`→`√(`). Alles andere bleibt unverändert.
pub fn to_display_syntax(raw: &str) -> String {
    to_display_syntax_with_map(raw).0
}

/// Wie [`to_display_syntax`], liefert zusätzlich für jede Zeichenposition im
/// Original (inklusive Ende) die zugehörige Zeichenposition im Ergebnis.
/// Damit bleibt der Cursor nach dem Umwandeln an der richtigen Stelle.
pub fn to_display_syntax_with_map(raw: &str) -> (String, Vec<usize>) {
    let chars: Vec<char> = raw.chars().collect();
    let mut out = String::with_capacity(raw.len());
    let mut out_len = 0usize;
    let mut map = Vec::with_capacity(chars.len() + 1);
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let prev_ident = i > 0 && is_ident_char(chars[i - 1]);
        if c.is_alphabetic() && !prev_ident {
            let mut j = i;
            while j < chars.len() && is_ident_char(chars[j]) {
                j += 1;
            }
            let ident: String = chars[i..j].iter().collect();
            let next_paren = j < chars.len() && chars[j] == '(';
            let replacement: Option<char> = match ident.as_str() {
                "pi" => Some('π'),
                "sqrt" if next_paren => Some('√'),
                _ => None,
            };
            match replacement {
                Some(r) => {
                    // Erste Position zeigt vor das Symbol, alle weiteren dahinter.
                    map.push(out_len);
                    for _ in i + 1..j {
                        map.push(out_len + 1);
                    }
                    out.push(r);
                    out_len += 1;
                }
                None => {
                    for ch in ident.chars() {
                        map.push(out_len);
                        out.push(ch);
                        out_len += 1;
                    }
                }
            }
            i = j;
            continue;
        }
        map.push(out_len);
        out.push(match c {
            '*' | '·' => '×',
            '/' => '÷',
            '-' => '−',
            other => other,
        });
        out_len += 1;
        i += 1;
    }
    map.push(out_len);
    (out, map)
}

/// Anzeige-Schreibweise → Engine-Schreibweise. Umkehrung von [`to_display_syntax`].
pub fn to_engine_syntax(display: &str) -> String {
    let chars: Vec<char> = display.chars().collect();
    let mut out = String::with_capacity(display.len() + 8);
    for (i, &c) in chars.iter().enumerate() {
        match c {
            '×' | '·' => out.push('*'),
            '÷' => out.push('/'),
            '−' => out.push('-'),
            'π' => out.push_str("pi"),
            '√' if chars.get(i + 1) == Some(&'(') => out.push_str("sqrt"),
            other => out.push(other),
        }
    }
    out
}

/// Zeichenindex → Byte-Offset (für `String::insert_str` usw.).
pub fn char_to_byte(s: &str, char_idx: usize) -> usize {
    s.char_indices().nth(char_idx).map(|(b, _)| b).unwrap_or(s.len())
}

// ---------------------------------------------------------------------------
// Hoch- und Tiefzahlen als Layout
// ---------------------------------------------------------------------------

fn superscript_base(c: char) -> Option<char> {
    Some(match c {
        '⁰' => '0',
        '¹' => '1',
        '²' => '2',
        '³' => '3',
        '⁴' => '4',
        '⁵' => '5',
        '⁶' => '6',
        '⁷' => '7',
        '⁸' => '8',
        '⁹' => '9',
        '⁻' => '−',
        '⁺' => '+',
        '⁽' => '(',
        '⁾' => ')',
        'ⁿ' => 'n',
        'ᵀ' => 'T',
        'ˣ' => 'x',
        _ => return None,
    })
}

fn subscript_base(c: char) -> Option<char> {
    Some(match c {
        '₀' => '0',
        '₁' => '1',
        '₂' => '2',
        '₃' => '3',
        '₄' => '4',
        '₅' => '5',
        '₆' => '6',
        '₇' => '7',
        '₈' => '8',
        '₉' => '9',
        'ₙ' => 'n',
        _ => return None,
    })
}

const SCRIPT_SCALE: f32 = 0.68;

fn fmt(font: &FontId, color: Color32, valign: Align) -> TextFormat {
    TextFormat {
        font_id: font.clone(),
        color,
        valign,
        ..Default::default()
    }
}

/// Text mit Unicode-Hoch-/Tiefzahlen als Layout: `10⁻²⁰` wird zu `10` mit
/// angehobenem, kleinerem `−20`. So braucht die Schrift die Zeichen `⁻`, `ⁿ`
/// usw. nicht, und alle Exponenten sehen gleich aus.
pub fn rich_job(text: &str, font: FontId, color: Color32) -> LayoutJob {
    let small = FontId::new(font.size * SCRIPT_SCALE, font.family.clone());
    let mut job = LayoutJob::default();
    let mut buf = String::new();
    let mut mode = 0u8; // 0 normal, 1 hoch, 2 tief
    let flush = |job: &mut LayoutJob, buf: &mut String, mode: u8| {
        if buf.is_empty() {
            return;
        }
        let f = match mode {
            1 => fmt(&small, color, Align::TOP),
            2 => fmt(&small, color, Align::BOTTOM),
            _ => fmt(&font, color, Align::BOTTOM),
        };
        job.append(buf, 0.0, f);
        buf.clear();
    };
    for c in text.chars() {
        let (m, ch) = if let Some(b) = superscript_base(c) {
            (1, b)
        } else if let Some(b) = subscript_base(c) {
            (2, b)
        } else {
            (0, c)
        };
        if m != mode {
            flush(&mut job, &mut buf, mode);
            mode = m;
        }
        buf.push(ch);
    }
    flush(&mut job, &mut buf, mode);
    job
}

/// Layout für editierbare Formelfelder. Jedes Zeichen des Puffers bleibt im
/// Layout erhalten (Cursor und Auswahl bleiben korrekt), aber:
/// * `^` vor einem Exponenten wird unsichtbar,
/// * der Exponent wird klein und angehoben gesetzt,
/// * Klammern, die nur den Exponenten umschließen, werden ausgeblendet.
pub fn edit_layout_job(text: &str, font: FontId, color: Color32) -> LayoutJob {
    #[derive(Clone, Copy, PartialEq)]
    enum St {
        Normal(u8),
        Hidden,
    }
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut style = vec![St::Normal(0); n];

    fn matching_paren(chars: &[char], open: usize, end: usize) -> Option<usize> {
        let mut depth = 0i32;
        for (k, &c) in chars.iter().enumerate().take(end).skip(open) {
            if c == '(' {
                depth += 1;
            } else if c == ')' {
                depth -= 1;
                if depth == 0 {
                    return Some(k);
                }
            }
        }
        None
    }

    // Bereich [start, end) mit Grundstufe `level` auszeichnen.
    fn mark(chars: &[char], style: &mut [St], start: usize, end: usize, level: u8) {
        let mut i = start;
        while i < end {
            if chars[i] == '^' && i + 1 < end {
                let lvl = level.saturating_add(1).min(2);
                if chars[i + 1] == '(' {
                    if let Some(close) = matching_paren(chars, i + 1, end) {
                        if close > i + 2 {
                            style[i] = St::Hidden;
                            style[i + 1] = St::Hidden;
                            style[close] = St::Hidden;
                            mark(chars, style, i + 2, close, lvl);
                            i = close + 1;
                            continue;
                        }
                    } else {
                        // Offene Klammer: sichtbar lassen, Rest anheben.
                        style[i] = St::Hidden;
                        for s in style.iter_mut().take(end).skip(i + 1) {
                            *s = St::Normal(lvl);
                        }
                        mark(chars, style, i + 2, end, lvl);
                        return;
                    }
                } else {
                    let mut j = i + 1;
                    if j < end && (chars[j] == '−' || chars[j] == '-') {
                        j += 1;
                    }
                    let run_start = j;
                    while j < end && (chars[j].is_alphanumeric() || chars[j] == '.' || chars[j] == '_') {
                        j += 1;
                    }
                    if j > run_start {
                        style[i] = St::Hidden;
                        for s in style.iter_mut().take(j).skip(i + 1) {
                            *s = St::Normal(lvl);
                        }
                        i = j;
                        continue;
                    }
                }
            }
            if style[i] == St::Normal(0) && level > 0 {
                style[i] = St::Normal(level);
            }
            i += 1;
        }
    }
    mark(&chars, &mut style, 0, n, 0);

    let small = FontId::new(font.size * SCRIPT_SCALE, font.family.clone());
    let hidden = FontId::new(1.0, font.family.clone());
    let mut job = LayoutJob::default();
    let mut buf = String::new();
    let mut cur: Option<St> = None;
    let flush = |job: &mut LayoutJob, buf: &mut String, st: Option<St>| {
        if buf.is_empty() {
            return;
        }
        let f = match st {
            Some(St::Hidden) => fmt(&hidden, Color32::TRANSPARENT, Align::TOP),
            Some(St::Normal(0)) | None => fmt(&font, color, Align::BOTTOM),
            Some(St::Normal(_)) => fmt(&small, color, Align::TOP),
        };
        job.append(buf, 0.0, f);
        buf.clear();
    };
    for (i, &c) in chars.iter().enumerate() {
        if cur != Some(style[i]) {
            flush(&mut job, &mut buf, cur);
            cur = Some(style[i]);
        }
        buf.push(c);
    }
    flush(&mut job, &mut buf, cur);
    if job.sections.is_empty() {
        // Leerer Puffer: Zeilenhöhe trotzdem aus der normalen Schrift.
        job.append("", 0.0, fmt(&font, color, Align::BOTTOM));
    }
    job
}

// ---------------------------------------------------------------------------
// Formelsatz: Baum
// ---------------------------------------------------------------------------

/// Darstellungsbaum für den Formelsatz.
#[derive(Clone, Debug, PartialEq)]
pub enum MathNode {
    Text(String),
    /// Abstand in Vielfachen der Schriftgröße.
    Space(f32),
    Row(Vec<MathNode>),
    Frac(Box<MathNode>, Box<MathNode>),
    Pow(Box<MathNode>, Box<MathNode>),
    Sub(Box<MathNode>, Box<MathNode>),
    Sqrt(Box<MathNode>),
    Root(Box<MathNode>, Box<MathNode>),
    Paren(Box<MathNode>),
    Abs(Box<MathNode>),
}

impl MathNode {
    fn text(s: &str) -> Self {
        MathNode::Text(s.to_string())
    }

    /// Enthält der Baum etwas, das zweidimensional gesetzt wird?
    pub fn is_structured(&self) -> bool {
        match self {
            MathNode::Text(_) | MathNode::Space(_) => false,
            MathNode::Row(v) => v.iter().any(|n| n.is_structured()),
            MathNode::Paren(n) | MathNode::Abs(n) => n.is_structured(),
            MathNode::Frac(..)
            | MathNode::Pow(..)
            | MathNode::Sub(..)
            | MathNode::Sqrt(_)
            | MathNode::Root(..) => true,
        }
    }
}

fn row(items: Vec<MathNode>) -> MathNode {
    let mut flat = Vec::with_capacity(items.len());
    for it in items {
        match it {
            MathNode::Row(inner) => flat.extend(inner),
            other => flat.push(other),
        }
    }
    if flat.len() == 1 {
        flat.pop().unwrap()
    } else {
        MathNode::Row(flat)
    }
}

fn strip_paren(n: MathNode) -> MathNode {
    match n {
        MathNode::Paren(inner) => *inner,
        other => other,
    }
}

// ---------------------------------------------------------------------------
// Formelsatz: Parser (gleiche Grammatik wie die Engine)
// ---------------------------------------------------------------------------

struct MathParser {
    c: Vec<char>,
    i: usize,
}

impl MathParser {
    fn peek(&self) -> Option<char> {
        self.c.get(self.i).copied()
    }

    fn skip_ws(&mut self) -> bool {
        let start = self.i;
        while self.peek().is_some_and(|c| c.is_whitespace()) {
            self.i += 1;
        }
        self.i > start
    }

    fn eat(&mut self, ch: char) -> bool {
        self.skip_ws();
        if self.peek() == Some(ch) {
            self.i += 1;
            true
        } else {
            false
        }
    }

    fn expr(&mut self, depth: usize) -> Option<MathNode> {
        if depth > 64 {
            return None;
        }
        let mut items = vec![self.term(depth)?];
        loop {
            self.skip_ws();
            match self.peek() {
                Some('+') => {
                    self.i += 1;
                    items.push(MathNode::text(" + "));
                }
                Some('-') | Some('−') => {
                    self.i += 1;
                    items.push(MathNode::text(" − "));
                }
                _ => break,
            }
            items.push(self.term(depth)?);
        }
        Some(row(items))
    }

    fn at_mod_keyword(&self) -> bool {
        let rest = &self.c[self.i..];
        rest.len() >= 3
            && rest[0] == 'm'
            && rest[1] == 'o'
            && rest[2] == 'd'
            && rest.get(3).is_none_or(|c| !is_ident_char(*c))
    }

    fn term(&mut self, depth: usize) -> Option<MathNode> {
        let mut acc = self.unary(depth)?;
        loop {
            let had_ws = self.skip_ws();
            if self.at_mod_keyword() {
                self.i += 3;
                let rhs = self.unary(depth)?;
                acc = row(vec![acc, MathNode::text(" mod "), rhs]);
                continue;
            }
            match self.peek() {
                Some('*') | Some('×') => {
                    self.i += 1;
                    let rhs = self.unary(depth)?;
                    acc = row(vec![acc, MathNode::text(" × "), rhs]);
                }
                Some('·') => {
                    self.i += 1;
                    let rhs = self.unary(depth)?;
                    acc = row(vec![acc, MathNode::text(" · "), rhs]);
                }
                Some('/') | Some('÷') => {
                    self.i += 1;
                    let rhs = self.unary(depth)?;
                    acc = MathNode::Frac(Box::new(strip_paren(acc)), Box::new(strip_paren(rhs)));
                }
                Some(c) if c == '(' || c == '√' || c.is_alphabetic() => {
                    let rhs = self.unary(depth)?;
                    acc = if had_ws {
                        row(vec![acc, MathNode::Space(0.22), rhs])
                    } else {
                        row(vec![acc, rhs])
                    };
                }
                _ => break,
            }
        }
        Some(acc)
    }

    fn unary(&mut self, depth: usize) -> Option<MathNode> {
        self.skip_ws();
        match self.peek() {
            Some('+') => {
                self.i += 1;
                self.unary(depth + 1)
            }
            Some('-') | Some('−') => {
                self.i += 1;
                let inner = self.unary(depth + 1)?;
                Some(row(vec![MathNode::text("−"), inner]))
            }
            Some('√') => {
                self.i += 1;
                let inner = self.unary(depth + 1)?;
                Some(MathNode::Sqrt(Box::new(strip_paren(inner))))
            }
            _ => self.power(depth),
        }
    }

    fn power(&mut self, depth: usize) -> Option<MathNode> {
        let base = self.postfix(depth)?;
        // Unicode-Hochzahlen direkt hinter der Basis (z. B. `10⁻²⁰`, `x²`).
        let mut sup = String::new();
        while let Some(b) = self.peek().and_then(superscript_base) {
            sup.push(b);
            self.i += 1;
        }
        if !sup.is_empty() {
            return Some(MathNode::Pow(Box::new(base), Box::new(MathNode::Text(sup))));
        }
        self.skip_ws();
        if self.peek() == Some('^') {
            self.i += 1;
            let exp = self.unary(depth + 1)?;
            return Some(MathNode::Pow(Box::new(base), Box::new(strip_paren(exp))));
        }
        Some(base)
    }

    fn postfix(&mut self, depth: usize) -> Option<MathNode> {
        let mut node = self.primary(depth)?;
        loop {
            self.skip_ws();
            match self.peek() {
                Some('!') => {
                    self.i += 1;
                    node = row(vec![node, MathNode::text("!")]);
                }
                Some('%') => {
                    self.i += 1;
                    node = row(vec![node, MathNode::text("%")]);
                }
                _ => break,
            }
        }
        Some(node)
    }

    fn number(&mut self) -> MathNode {
        let start = self.i;
        if self.peek() == Some('0') && matches!(self.c.get(self.i + 1), Some('x') | Some('X')) {
            self.i += 2;
            while self.peek().is_some_and(|c| c.is_ascii_hexdigit()) {
                self.i += 1;
            }
            return MathNode::Text(self.c[start..self.i].iter().collect());
        }
        let mut mantissa = String::new();
        let mut exponent: Option<String> = None;
        while let Some(c) = self.peek() {
            if c.is_ascii_digit() || c == '.' {
                mantissa.push(c);
                self.i += 1;
            } else if (c == 'e' || c == 'E') && exponent.is_none() {
                let mut look = self.i + 1;
                let mut exp = String::new();
                if matches!(self.c.get(look), Some('+') | Some('-') | Some('−')) {
                    if self.c[look] != '+' {
                        exp.push('−');
                    }
                    look += 1;
                }
                let digits_start = look;
                while self.c.get(look).is_some_and(|d| d.is_ascii_digit()) {
                    exp.push(self.c[look]);
                    look += 1;
                }
                if look == digits_start {
                    break;
                }
                self.i = look;
                exponent = Some(exp);
            } else {
                break;
            }
        }
        match exponent {
            // `1.5e-5` wird als 1.5 × 10⁻⁵ gesetzt.
            Some(exp) => row(vec![
                MathNode::Text(mantissa),
                MathNode::text(" × "),
                MathNode::Pow(Box::new(MathNode::text("10")), Box::new(MathNode::Text(exp))),
            ]),
            None => MathNode::Text(mantissa),
        }
    }

    fn primary(&mut self, depth: usize) -> Option<MathNode> {
        if depth > 64 {
            return None;
        }
        self.skip_ws();
        let ch = self.peek()?;
        if ch == '(' {
            self.i += 1;
            let inner = self.expr(depth + 1)?;
            if !self.eat(')') {
                return None;
            }
            return Some(MathNode::Paren(Box::new(inner)));
        }
        if ch.is_ascii_digit() || ch == '.' {
            return Some(self.number());
        }
        if ch.is_alphabetic() {
            let start = self.i;
            while self.peek().is_some_and(is_ident_char) {
                self.i += 1;
            }
            let ident: String = self.c[start..self.i].iter().collect();
            let save = self.i;
            if self.eat('(') {
                let mut args = Vec::new();
                if !self.eat(')') {
                    loop {
                        args.push(self.expr(depth + 1)?);
                        if self.eat(',') {
                            continue;
                        }
                        if self.eat(')') {
                            break;
                        }
                        return None;
                    }
                }
                return Some(function_node(&ident, args));
            }
            self.i = save;
            return Some(identifier_node(&ident));
        }
        None
    }
}

/// Bekannte Namen hübsch setzen: `pi`→π, `mu0`→μ₀, `k_B`→k_B (tiefgestellt).
fn identifier_node(name: &str) -> MathNode {
    fn greek(s: &str) -> Option<&'static str> {
        Some(match s {
            "pi" => "π",
            "mu" => "μ",
            "eps" => "ε",
            "alpha" => "α",
            "sigma" => "σ",
            "Phi" => "Φ",
            "lambda" => "λ",
            _ => return None,
        })
    }
    match name {
        "ANS" | "ans" => return MathNode::text("ans"),
        "R_inf" => {
            return MathNode::Sub(Box::new(MathNode::text("R")), Box::new(MathNode::text("∞")))
        }
        _ => {}
    }
    if let Some(g) = greek(name) {
        return MathNode::text(g);
    }
    // Basis + Index: `k_B`, `mu_B`, `c0`, `eps0`, `Phi0`
    let (base, sub) = if let Some((b, s)) = name.split_once('_') {
        (b, s)
    } else {
        let split = name.trim_end_matches(|c: char| c.is_ascii_digit());
        if split.is_empty() || split.len() == name.len() {
            (name, "")
        } else {
            (split, &name[split.len()..])
        }
    };
    if !sub.is_empty() && sub.chars().count() <= 2 && !base.is_empty() {
        let base_txt = greek(base).unwrap_or(base);
        return MathNode::Sub(Box::new(MathNode::text(base_txt)), Box::new(MathNode::text(sub)));
    }
    MathNode::text(name)
}

fn args_row(args: Vec<MathNode>) -> MathNode {
    let mut items = Vec::new();
    for (k, a) in args.into_iter().enumerate() {
        if k > 0 {
            items.push(MathNode::text(", "));
        }
        items.push(a);
    }
    if items.is_empty() {
        MathNode::text("")
    } else {
        row(items)
    }
}

fn function_node(name: &str, mut args: Vec<MathNode>) -> MathNode {
    let inverse = |f: &str| {
        MathNode::Pow(Box::new(MathNode::text(f)), Box::new(MathNode::text("−1")))
    };
    let head = match name {
        "sqrt" if args.len() == 1 => return MathNode::Sqrt(Box::new(args.pop().unwrap())),
        "nthroot" if args.len() == 2 => {
            let x = args.pop().unwrap();
            let n = args.pop().unwrap();
            return MathNode::Root(Box::new(n), Box::new(x));
        }
        "abs" if args.len() == 1 => return MathNode::Abs(Box::new(args.pop().unwrap())),
        "log" if args.len() == 2 => {
            let x = args.pop().unwrap();
            let b = args.pop().unwrap();
            return row(vec![
                MathNode::Sub(Box::new(MathNode::text("log")), Box::new(b)),
                MathNode::Paren(Box::new(x)),
            ]);
        }
        "log10" | "lg" => MathNode::Sub(Box::new(MathNode::text("log")), Box::new(MathNode::text("10"))),
        "asin" | "arcsin" => inverse("sin"),
        "acos" | "arccos" => inverse("cos"),
        "atan" | "arctan" => inverse("tan"),
        "asinh" | "arcsinh" => inverse("sinh"),
        "acosh" | "arccosh" => inverse("cosh"),
        "atanh" | "arctanh" => inverse("tanh"),
        "sum" => MathNode::text("∑"),
        "prod" => MathNode::text("∏"),
        "int" | "integral" => MathNode::text("∫"),
        "deriv" | "diff" => MathNode::Frac(Box::new(MathNode::text("d")), Box::new(MathNode::text("dx"))),
        "inv" => {
            return MathNode::Pow(
                Box::new(MathNode::Paren(Box::new(args_row(args)))),
                Box::new(MathNode::text("−1")),
            )
        }
        "trans" | "transpose" => {
            return MathNode::Pow(
                Box::new(MathNode::Paren(Box::new(args_row(args)))),
                Box::new(MathNode::text("T")),
            )
        }
        other => MathNode::text(other),
    };
    row(vec![head, MathNode::Paren(Box::new(args_row(args)))])
}

/// Engine- oder Ergebnistext in einen Darstellungsbaum übersetzen.
/// `None`, wenn der Text nicht vollständig erkannt wird (dann zeigt die
/// Oberfläche ihn als normalen Text an).
pub fn parse_math(src: &str) -> Option<MathNode> {
    let src = src.trim();
    if src.is_empty() || src.contains('[') {
        return None;
    }
    if let Some((lhs, rhs)) = src.split_once(":=") {
        let rhs_node = parse_math(rhs)?;
        return Some(row(vec![
            identifier_node(lhs.trim()),
            MathNode::text(" := "),
            rhs_node,
        ]));
    }
    let mut p = MathParser {
        c: src.chars().collect(),
        i: 0,
    };
    let node = p.expr(0)?;
    p.skip_ws();
    if p.i < p.c.len() {
        return None;
    }
    Some(node)
}

// ---------------------------------------------------------------------------
// Formelsatz: Layout und Zeichnen
// ---------------------------------------------------------------------------

enum Prim {
    /// Galley mit linker oberer Ecke relativ zu (Ursprung, Grundlinie).
    Text(Vec2, Arc<Galley>),
    Line(Vec2, Vec2, f32),
    Path(Vec<Vec2>, f32),
}

struct Laid {
    w: f32,
    asc: f32,
    desc: f32,
    prims: Vec<Prim>,
}

impl Laid {
    fn empty() -> Self {
        Laid {
            w: 0.0,
            asc: 0.0,
            desc: 0.0,
            prims: Vec::new(),
        }
    }

    fn h(&self) -> f32 {
        self.asc + self.desc
    }

    /// Kind-Box an Position (dx, Grundlinie + dy) übernehmen.
    fn place(&mut self, child: Laid, dx: f32, dy: f32) {
        let off = Vec2::new(dx, dy);
        for p in child.prims {
            self.prims.push(match p {
                Prim::Text(o, g) => Prim::Text(o + off, g),
                Prim::Line(a, b, w) => Prim::Line(a + off, b + off, w),
                Prim::Path(pts, w) => Prim::Path(pts.into_iter().map(|q| q + off).collect(), w),
            });
        }
    }
}

struct Layouter<'a> {
    ui: &'a Ui,
    color: Color32,
}

impl Layouter<'_> {
    fn text(&self, s: &str, size: f32) -> Laid {
        let galley = self
            .ui
            .painter()
            .layout_no_wrap(s.to_string(), FontId::proportional(size), self.color);
        let height = galley.size().y.max(size);
        let baseline = galley
            .rows
            .first()
            .and_then(|r| r.row.glyphs.first().map(|g| r.pos.y + g.pos.y))
            .unwrap_or(height * 0.8);
        Laid {
            w: galley.size().x,
            asc: baseline,
            desc: (height - baseline).max(0.0),
            prims: vec![Prim::Text(Vec2::new(0.0, -baseline), galley)],
        }
    }

    fn layout(&self, node: &MathNode, size: f32) -> Laid {
        let thick = (size / 17.0).max(1.0);
        match node {
            MathNode::Text(s) => self.text(s, size),
            MathNode::Space(em) => Laid {
                w: em * size,
                ..Laid::empty()
            },
            MathNode::Row(items) => {
                let mut out = Laid::empty();
                for it in items {
                    let child = self.layout(it, size);
                    let x = out.w;
                    out.w += child.w;
                    out.asc = out.asc.max(child.asc);
                    out.desc = out.desc.max(child.desc);
                    out.place(child, x, 0.0);
                }
                out
            }
            MathNode::Frac(num, den) => {
                let s = (size * 0.9).max(10.0);
                let n = self.layout(num, s);
                let d = self.layout(den, s);
                let pad = size * 0.12;
                let gap = size * 0.1;
                let axis = size * 0.3;
                let w = n.w.max(d.w) + 2.0 * pad;
                let num_base = -axis - gap - thick * 0.5 - n.desc;
                let den_base = -axis + gap + thick * 0.5 + d.asc;
                let mut out = Laid {
                    w: w + size * 0.08,
                    asc: -(num_base - n.asc),
                    desc: (den_base + d.desc).max(0.0),
                    prims: Vec::new(),
                };
                let (nw, dw) = (n.w, d.w);
                out.place(n, size * 0.04 + (w - nw) * 0.5, num_base);
                out.place(d, size * 0.04 + (w - dw) * 0.5, den_base);
                out.prims.push(Prim::Line(
                    Vec2::new(size * 0.04, -axis),
                    Vec2::new(size * 0.04 + w, -axis),
                    thick,
                ));
                out
            }
            MathNode::Pow(base, exp) => {
                let b = self.layout(base, size);
                let es = (size * SCRIPT_SCALE).max(8.0);
                let e = self.layout(exp, es);
                let shift = (size * 0.42).max(b.asc - e.asc * 0.55);
                let mut out = Laid {
                    w: b.w + e.w + size * 0.04,
                    asc: b.asc.max(shift + e.asc),
                    desc: b.desc.max(e.desc - shift),
                    prims: Vec::new(),
                };
                let bw = b.w;
                out.place(b, 0.0, 0.0);
                out.place(e, bw + size * 0.04, -shift);
                out
            }
            MathNode::Sub(base, sub) => {
                let b = self.layout(base, size);
                let ss = (size * SCRIPT_SCALE).max(8.0);
                let s = self.layout(sub, ss);
                let shift = size * 0.22;
                let mut out = Laid {
                    w: b.w + s.w + size * 0.02,
                    asc: b.asc.max(s.asc - shift),
                    desc: b.desc.max(s.desc + shift),
                    prims: Vec::new(),
                };
                let bw = b.w;
                out.place(b, 0.0, 0.0);
                out.place(s, bw + size * 0.02, shift);
                out
            }
            MathNode::Sqrt(body) => self.radical(None, body, size, thick),
            MathNode::Root(index, body) => self.radical(Some(index), body, size, thick),
            MathNode::Paren(inner) => {
                let i = self.layout(inner, size);
                self.fenced(i, size, thick, '(')
            }
            MathNode::Abs(inner) => {
                let i = self.layout(inner, size);
                self.fenced(i, size, thick, '|')
            }
        }
    }

    fn radical(&self, index: Option<&MathNode>, body: &MathNode, size: f32, thick: f32) -> Laid {
        let b = self.layout(body, size);
        let gap = size * 0.1;
        let top = -(b.asc + gap + thick * 0.5);
        let bottom = b.desc;
        let sign_w = size * 0.5;
        let mid = bottom - (bottom - top) * 0.42;
        let idx = index.map(|n| self.layout(n, (size * 0.55).max(8.0)));
        let lead = idx.as_ref().map_or(0.0, |i| (i.w - sign_w * 0.45).max(0.0));
        let x0 = lead;
        let pts = vec![
            Vec2::new(x0, mid + size * 0.04),
            Vec2::new(x0 + sign_w * 0.22, mid - size * 0.03),
            Vec2::new(x0 + sign_w * 0.5, bottom),
            Vec2::new(x0 + sign_w, top),
            Vec2::new(x0 + sign_w + b.w + size * 0.12, top),
        ];
        let mut out = Laid {
            w: x0 + sign_w + b.w + size * 0.16,
            asc: -top + thick,
            desc: b.desc + thick,
            prims: vec![Prim::Path(pts, thick)],
        };
        out.place(b, x0 + sign_w + size * 0.06, 0.0);
        if let Some(i) = idx {
            let iw = i.w;
            let i_base = mid - size * 0.12;
            out.asc = out.asc.max(-(i_base - i.asc));
            out.place(i, x0 + sign_w * 0.45 - iw, i_base);
        }
        out
    }

    fn fenced(&self, inner: Laid, size: f32, thick: f32, kind: char) -> Laid {
        let plain = self.text("(", size);
        // Nur bei deutlich höherem Inhalt (Brüche) gezeichnete Klammern
        let tall = inner.h() > plain.h() * 1.45;
        if !tall {
            let (l, r) = if kind == '|' { ("|", "|") } else { ("(", ")") };
            let lt = self.text(l, size);
            let rt = self.text(r, size);
            let mut out = Laid {
                w: lt.w + inner.w + rt.w,
                asc: inner.asc.max(lt.asc),
                desc: inner.desc.max(lt.desc),
                prims: Vec::new(),
            };
            let (lw, iw) = (lt.w, inner.w);
            out.place(lt, 0.0, 0.0);
            out.place(inner, lw, 0.0);
            out.place(rt, lw + iw, 0.0);
            return out;
        }
        let top = -inner.asc - size * 0.05;
        let bottom = inner.desc + size * 0.05;
        let fw = size * 0.32;
        let mut out = Laid {
            w: inner.w + 2.0 * fw,
            asc: -top + thick,
            desc: bottom + thick,
            prims: Vec::new(),
        };
        let height = bottom - top;
        let mut left = Vec::new();
        let mut right = Vec::new();
        if kind == '|' {
            left.push(Vec2::new(fw * 0.5, top));
            left.push(Vec2::new(fw * 0.5, bottom));
            right.push(Vec2::new(fw * 1.5 + inner.w, top));
            right.push(Vec2::new(fw * 1.5 + inner.w, bottom));
        } else {
            let steps = 12;
            for k in 0..=steps {
                let t = k as f32 / steps as f32;
                let y = top + t * height;
                let bulge = (t * std::f32::consts::PI).sin();
                left.push(Vec2::new(fw * 0.75 - fw * 0.5 * bulge, y));
                right.push(Vec2::new(fw * 1.25 + inner.w + fw * 0.5 * bulge, y));
            }
        }
        out.prims.push(Prim::Path(left, thick));
        out.prims.push(Prim::Path(right, thick));
        out.place(inner, fw, 0.0);
        out
    }
}

/// Darstellungsbaum zeichnen. Liefert die Antwort des belegten Bereichs.
pub fn math_widget(ui: &mut Ui, node: &MathNode, size: f32, color: Color32, sense: Sense) -> Response {
    let laid = Layouter { ui, color }.layout(node, size);
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(laid.w.ceil(), laid.h().ceil()), sense);
    if ui.is_rect_visible(rect) {
        let origin = Pos2::new(rect.left(), rect.top() + laid.asc);
        let painter = ui.painter();
        for p in laid.prims {
            match p {
                Prim::Text(o, g) => painter.galley(origin + o, g, color),
                Prim::Line(a, b, w) => {
                    painter.line_segment([origin + a, origin + b], Stroke::new(w, color));
                }
                Prim::Path(pts, w) => {
                    painter.add(Shape::line(
                        pts.into_iter().map(|q| origin + q).collect(),
                        Stroke::new(w, color),
                    ));
                }
            }
        }
    }
    resp
}

/// Breite, die ein Baum in dieser Schriftgröße belegt.
pub fn math_width(ui: &Ui, node: &MathNode, size: f32) -> f32 {
    Layouter {
        ui,
        color: Color32::WHITE,
    }
    .layout(node, size)
    .w
}

/// `src` als Formel setzen. Fällt auf `fallback` als normalen (umbrechenden)
/// Text zurück, wenn `src` keine Formelstruktur hat, nicht erkannt wird oder
/// breiter als `max_width` wäre.
pub fn show_math(
    ui: &mut Ui,
    src: &str,
    fallback: &str,
    size: f32,
    color: Color32,
    max_width: f32,
    sense: Sense,
) -> Response {
    if let Some(node) = parse_math(src) {
        if node.is_structured() && math_width(ui, &node, size) <= max_width {
            return math_widget(ui, &node, size, color, sense);
        }
    }
    let mut job = rich_job(fallback, FontId::proportional(size), color);
    job.wrap.max_width = max_width.max(40.0);
    ui.add(egui::Label::new(job).sense(sense))
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn t(s: &str) -> MathNode {
        MathNode::text(s)
    }

    #[test]
    fn display_and_engine_syntax_round_trip() {
        for raw in [
            "sqrt(2)/2 + sin(pi/4)",
            "2*pi-1",
            "(x+1)^(12)",
            "nthroot(3, 27)",
            "1e-5",
            "0x1F + 2",
            "x := 3",
            "asin(1/2)",
            "[[1, -2], [3, 4]]",
            "sqrt 4",
        ] {
            let disp = to_display_syntax(raw);
            assert_eq!(to_engine_syntax(&disp), raw, "Rundweg für {raw} (Anzeige {disp})");
        }
        assert_eq!(to_display_syntax("sqrt(2)/2*pi-1"), "√(2)÷2×π−1");
        // `pi` nur als eigenständiger Name, nicht innerhalb längerer Namen
        assert_eq!(to_display_syntax("pix + api"), "pix + api");
    }

    #[test]
    fn cursor_map_follows_replacements() {
        let (out, map) = to_display_syntax_with_map("2*sqrt(x)");
        assert_eq!(out, "2×√(x)");
        assert_eq!(map.len(), "2*sqrt(x)".chars().count() + 1);
        assert_eq!(map[0], 0); // vor 2
        assert_eq!(map[2], 2); // vor sqrt → vor √
        assert_eq!(map[6], 3); // vor ( → hinter √
        assert_eq!(*map.last().unwrap(), out.chars().count());
    }

    #[test]
    fn parse_matches_engine_precedence() {
        // 1/2x ist (1/2)·x – wie in der Engine
        assert_eq!(
            parse_math("1/2x"),
            Some(MathNode::Row(vec![
                MathNode::Frac(Box::new(t("1")), Box::new(t("2"))),
                t("x"),
            ]))
        );
        // Klammern um den Exponenten verschwinden, um die Basis bleiben sie
        assert_eq!(
            parse_math("(x+1)^(12)"),
            Some(MathNode::Pow(
                Box::new(MathNode::Paren(Box::new(MathNode::Row(vec![t("x"), t(" + "), t("1")])))),
                Box::new(t("12"))
            ))
        );
        // Ergebnistext der Engine
        assert_eq!(
            parse_math("2√3 / 3"),
            Some(MathNode::Frac(
                Box::new(MathNode::Row(vec![t("2"), MathNode::Sqrt(Box::new(t("3")))])),
                Box::new(t("3"))
            ))
        );
        // Unicode-Hochzahlen aus der Dezimalanzeige
        assert_eq!(
            parse_math("1 × 10⁻²⁰"),
            Some(MathNode::Row(vec![
                t("1"),
                t(" × "),
                MathNode::Pow(Box::new(t("10")), Box::new(t("−20"))),
            ]))
        );
        // -x^2 ist −(x²)
        assert_eq!(
            parse_math("-x^2"),
            Some(MathNode::Row(vec![t("−"), MathNode::Pow(Box::new(t("x")), Box::new(t("2")))]))
        );
        // 2^3^2 ist rechtsassoziativ
        assert_eq!(
            parse_math("2^3^2"),
            Some(MathNode::Pow(
                Box::new(t("2")),
                Box::new(MathNode::Pow(Box::new(t("3")), Box::new(t("2"))))
            ))
        );
    }

    #[test]
    fn parse_functions_and_fallbacks() {
        assert!(matches!(parse_math("sqrt(2)/2"), Some(MathNode::Frac(..))));
        assert!(matches!(parse_math("nthroot(3, 27)"), Some(MathNode::Root(..))));
        assert!(parse_math("sin(pi/4)").unwrap().is_structured());
        assert!(!parse_math("sin(x) + 2").unwrap().is_structured());
        assert_eq!(parse_math("[[1, 2], [3, 4]]"), None);
        assert_eq!(parse_math("2+"), None);
        assert_eq!(parse_math("(1+2"), None);
        assert!(parse_math("fao(2)").is_some());
        assert_eq!(parse_math("mu_B"), Some(MathNode::Sub(Box::new(t("μ")), Box::new(t("B")))));
        assert_eq!(parse_math("eps0"), Some(MathNode::Sub(Box::new(t("ε")), Box::new(t("0")))));
    }

    #[test]
    fn rich_job_replaces_unicode_scripts() {
        let job = rich_job("10⁻²⁰", FontId::proportional(14.0), Color32::WHITE);
        assert_eq!(job.text, "10−20");
        assert_eq!(job.sections.len(), 2);
        assert_eq!(job.sections[1].format.valign, Align::TOP);
    }

    #[test]
    fn edit_layout_keeps_every_character() {
        for s in ["(x+1)^(12)", "x^2+1", "2^(", "x^", "e^(−x^2)", "", "√(2)÷2"] {
            let job = edit_layout_job(s, FontId::proportional(20.0), Color32::WHITE);
            assert_eq!(job.text, s);
        }
        let job = edit_layout_job("x^2", FontId::proportional(20.0), Color32::WHITE);
        // `^` unsichtbar, `2` angehoben
        assert_eq!(job.sections.len(), 3);
        assert_eq!(job.sections[1].format.color, Color32::TRANSPARENT);
        assert_eq!(job.sections[2].format.valign, Align::TOP);
    }
}
