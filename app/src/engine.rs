//! 100% Offline Symbolic, Rational, Complex, Matrix & Calculus Evaluation Engine
//! Implements the mathematical capabilities of Niob Scientific Calculator
//! (`Lc/a/a/e/d;`, `Lc/a/a/e/e;`, `Lc/a/a/e/f;`, `Lc/a/a/e/g;`, `Lc/a/a/e/h;`).

use std::cell::RefCell;
use std::collections::HashMap;
use std::fmt;
use std::ops::Deref;
use crate::constants::{
    SCIENTIFIC_CONSTANTS, USER_VARIABLES,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AngleMode {
    Radians,
    Degrees,
}

impl AngleMode {
    pub fn to_radians(self, v: f64) -> f64 {
        match self {
            Self::Radians => v,
            Self::Degrees => v * (std::f64::consts::PI / 180.0),
        }
    }

    pub fn from_radians(self, rad: f64) -> f64 {
        match self {
            Self::Radians => rad,
            Self::Degrees => rad * (180.0 / std::f64::consts::PI),
        }
    }
}

/// Number formatting modes from `Lc/a/a/a;->a(Landroid/content/Context;Ljava/lang/String;)V`
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NumberFormatMode {
    SpacePoint, // 123 456.789
    CommaPoint, // 123,456.789
    NonePoint,  // 123456.789
    SpaceComma, // 123 456,789
    PointComma, // 123.456,789
    NoneComma,  // 123456,789
}

impl NumberFormatMode {
    pub const ALL: [NumberFormatMode; 6] = [
        Self::SpacePoint,
        Self::CommaPoint,
        Self::NonePoint,
        Self::SpaceComma,
        Self::PointComma,
        Self::NoneComma,
    ];

    pub fn example(self) -> &'static str {
        match self {
            Self::SpacePoint => "123 456.789",
            Self::CommaPoint => "123,456.789",
            Self::NonePoint => "123456.789",
            Self::SpaceComma => "123 456,789",
            Self::PointComma => "123.456,789",
            Self::NoneComma => "123456,789",
        }
    }

    pub fn format_f64(self, val: f64) -> String {
        if val.is_nan() {
            return "NaN".to_string();
        }
        if val.is_infinite() {
            return if val.is_sign_positive() {
                "∞".to_string()
            } else {
                "-∞".to_string()
            };
        }
        let abs_v = val.abs();
        if abs_v == 0.0 {
            return "0".to_string();
        }
        // Scientific notation for very large or very small numbers
        if abs_v >= 1e12 || (abs_v > 0.0 && abs_v < 1e-6) {
            let exp = abs_v.log10().floor() as i32;
            let mantissa = val / 10f64.powi(exp);
            let m_str = trim_float(mantissa, 8);
            let m_formatted = self.apply_separators(&m_str);
            return format!("{} × 10{}", m_formatted, to_superscript(exp));
        }

        let raw = trim_float(val, 10);
        self.apply_separators(&raw)
    }

    fn apply_separators(self, s: &str) -> String {
        let (group_sep, dec_sep) = match self {
            Self::SpacePoint => (" ", '.'),
            Self::CommaPoint => (",", '.'),
            Self::NonePoint => ("", '.'),
            Self::SpaceComma => (" ", ','),
            Self::PointComma => (".", ','),
            Self::NoneComma => ("", ','),
        };

        let (sign, rest) = if let Some(stripped) = s.strip_prefix('-') {
            ("-", stripped)
        } else {
            ("", s)
        };

        let mut parts = rest.splitn(2, '.');
        let int_part = parts.next().unwrap_or("");
        let frac_part = parts.next();

        let grouped_int = if group_sep.is_empty() || int_part.len() <= 3 {
            int_part.to_string()
        } else {
            let chars: Vec<char> = int_part.chars().collect();
            let mut out = String::new();
            for (i, ch) in chars.iter().enumerate() {
                if i > 0 && (chars.len() - i) % 3 == 0 {
                    out.push_str(group_sep);
                }
                out.push(*ch);
            }
            out
        };

        if let Some(frac) = frac_part {
            if frac.is_empty() {
                format!("{}{}", sign, grouped_int)
            } else {
                format!("{}{}{}{}", sign, grouped_int, dec_sep, frac)
            }
        } else {
            format!("{}{}", sign, grouped_int)
        }
    }
}

fn trim_float(val: f64, decimals: usize) -> String {
    let rounded = if (val - val.round()).abs() < 1e-12 && val.abs() < 1e15 {
        val.round()
    } else {
        val
    };
    let s = format!("{:.*}", decimals, rounded);
    if s.contains('.') {
        let s = s.trim_end_matches('0').trim_end_matches('.');
        if s == "-0" || s.is_empty() {
            "0".to_string()
        } else {
            s.to_string()
        }
    } else {
        s
    }
}

pub fn to_superscript(n: i32) -> String {
    n.to_string()
        .chars()
        .map(|c| match c {
            '-' => '⁻',
            '0' => '⁰',
            '1' => '¹',
            '2' => '²',
            '3' => '³',
            '4' => '⁴',
            '5' => '⁵',
            '6' => '⁶',
            '7' => '⁷',
            '8' => '⁸',
            '9' => '⁹',
            _ => c,
        })
        .collect()
}

pub fn format_unchained_superscripts(s: &str) -> String {
    let chars: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let n = chars.len();
    let mut i = 0;
    while i < n {
        if chars[i] == '^' && i + 1 < n {
            let mut k = i as isize - 1;
            while k >= 0
                && (chars[k as usize].is_alphanumeric()
                    || chars[k as usize] == '.'
                    || chars[k as usize] == '_')
            {
                k -= 1;
            }
            let preceded_by_caret = k >= 0 && chars[k as usize] == '^';

            let mut exp_len = 0usize;
            let mut sup: Option<&str> = None;
            let rem: String = chars[i + 1..].iter().collect();
            if rem.starts_with("(-1)") || rem.starts_with("(−1)") {
                exp_len = 4;
                sup = Some("⁻¹");
            } else if rem.starts_with("(-2)") || rem.starts_with("(−2)") {
                exp_len = 4;
                sup = Some("⁻²");
            } else if rem.starts_with("(-3)") || rem.starts_with("(−3)") {
                exp_len = 4;
                sup = Some("⁻³");
            } else if (chars[i + 1] == '2' || chars[i + 1] == '3' || chars[i + 1] == '4')
                && (i + 2 >= n || (!chars[i + 2].is_alphanumeric() && chars[i + 2] != '.' && chars[i + 2] != '_'))
            {
                exp_len = 1;
                sup = match chars[i + 1] {
                    '2' => Some("²"),
                    '3' => Some("³"),
                    _ => Some("⁴"),
                };
            }

            if let Some(sup_str) = sup {
                let after = i + 1 + exp_len;
                let followed_by_caret = after < n && chars[after] == '^';
                if !preceded_by_caret && !followed_by_caret {
                    out.push_str(sup_str);
                    i = after;
                    continue;
                }
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

// --- Exact Rational Arithmetic ---

fn gcd_i128(mut a: i128, mut b: i128) -> i128 {
    while b != 0 {
        let t = b;
        b = a % b;
        a = t;
    }
    a.abs()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExactRat {
    pub num: i128,
    pub den: i128, // den > 0 always
}

impl ExactRat {
    pub fn new(mut num: i128, mut den: i128) -> Option<Self> {
        if den == 0 {
            return None;
        }
        if den < 0 {
            num = -num;
            den = -den;
        }
        let g = gcd_i128(num, den);
        let g = if g == 0 { 1 } else { g };
        Some(Self {
            num: num / g,
            den: den / g,
        })
    }

    pub fn from_int(n: i128) -> Self {
        Self { num: n, den: 1 }
    }

    pub fn add(self, o: Self) -> Option<Self> {
        let num = self.num.checked_mul(o.den)?.checked_add(o.num.checked_mul(self.den)?)?;
        let den = self.den.checked_mul(o.den)?;
        Self::new(num, den)
    }

    pub fn sub(self, o: Self) -> Option<Self> {
        let num = self.num.checked_mul(o.den)?.checked_sub(o.num.checked_mul(self.den)?)?;
        let den = self.den.checked_mul(o.den)?;
        Self::new(num, den)
    }

    pub fn mul(self, o: Self) -> Option<Self> {
        let num = self.num.checked_mul(o.num)?;
        let den = self.den.checked_mul(o.den)?;
        Self::new(num, den)
    }

    pub fn div(self, o: Self) -> Option<Self> {
        if o.num == 0 {
            return None;
        }
        let num = self.num.checked_mul(o.den)?;
        let den = self.den.checked_mul(o.num)?;
        Self::new(num, den)
    }

    pub fn pow(self, exp: i32) -> Option<Self> {
        if exp >= 0 {
            let num = self.num.checked_pow(exp as u32)?;
            let den = self.den.checked_pow(exp as u32)?;
            Self::new(num, den)
        } else {
            let exp = (-exp) as u32;
            let num = self.den.checked_pow(exp)?;
            let den = self.num.checked_pow(exp)?;
            Self::new(num, den)
        }
    }

    pub fn to_f64(self) -> f64 {
        (self.num as f64) / (self.den as f64)
    }
}

// --- Complex Numbers with Full Trigonometric & Analytic Support ---

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Complex {
    pub re: f64,
    pub im: f64,
}

impl Complex {
    pub fn real(re: f64) -> Self {
        Self { re, im: 0.0 }
    }

    pub fn i() -> Self {
        Self { re: 0.0, im: 1.0 }
    }

    pub fn is_real(self) -> bool {
        self.im.abs() < 1e-12
    }

    pub fn abs(self) -> f64 {
        self.re.hypot(self.im)
    }

    pub fn arg(self) -> f64 {
        self.im.atan2(self.re)
    }

    pub fn add(self, o: Self) -> Self {
        Self {
            re: self.re + o.re,
            im: self.im + o.im,
        }
    }

    pub fn sub(self, o: Self) -> Self {
        Self {
            re: self.re - o.re,
            im: self.im - o.im,
        }
    }

    pub fn mul(self, o: Self) -> Self {
        Self {
            re: self.re * o.re - self.im * o.im,
            im: self.re * o.im + self.im * o.re,
        }
    }

    pub fn div(self, o: Self) -> Self {
        let denom = o.re * o.re + o.im * o.im;
        Self {
            re: (self.re * o.re + self.im * o.im) / denom,
            im: (self.im * o.re - self.re * o.im) / denom,
        }
    }

    pub fn exp(self) -> Self {
        let r = self.re.exp();
        Self {
            re: r * self.im.cos(),
            im: r * self.im.sin(),
        }
    }

    pub fn ln(self) -> Self {
        Self {
            re: self.abs().ln(),
            im: self.arg(),
        }
    }

    pub fn pow(self, exp: Self) -> Self {
        if self.is_real() && exp.is_real() && self.re >= 0.0 {
            return Self::real(self.re.powf(exp.re));
        }
        if self.abs() == 0.0 {
            return Self::real(0.0);
        }
        self.ln().mul(exp).exp()
    }

    pub fn sqrt(self) -> Self {
        if self.is_real() {
            if self.re >= 0.0 {
                Self::real(self.re.sqrt())
            } else {
                Self {
                    re: 0.0,
                    im: (-self.re).sqrt(),
                }
            }
        } else {
            let r = self.abs().sqrt();
            let theta = self.arg() * 0.5;
            Self {
                re: r * theta.cos(),
                im: r * theta.sin(),
            }
        }
    }

    pub fn sin(self) -> Self {
        // sin(x + iy) = sin(x)cosh(y) + i cos(x)sinh(y)
        Self {
            re: self.re.sin() * self.im.cosh(),
            im: self.re.cos() * self.im.sinh(),
        }
    }

    pub fn cos(self) -> Self {
        // cos(x + iy) = cos(x)cosh(y) - i sin(x)sinh(y)
        Self {
            re: self.re.cos() * self.im.cosh(),
            im: -self.re.sin() * self.im.sinh(),
        }
    }

    pub fn tan(self) -> Self {
        self.sin().div(self.cos())
    }

    pub fn sinh(self) -> Self {
        // sinh(x + iy) = sinh(x)cos(y) + i cosh(x)sin(y)
        Self {
            re: self.re.sinh() * self.im.cos(),
            im: self.re.cosh() * self.im.sin(),
        }
    }

    pub fn cosh(self) -> Self {
        // cosh(x + iy) = cosh(x)cos(y) + i sinh(x)sin(y)
        Self {
            re: self.re.cosh() * self.im.cos(),
            im: self.re.sinh() * self.im.sin(),
        }
    }

    pub fn tanh(self) -> Self {
        self.sinh().div(self.cosh())
    }
}

// --- Combined Arithmetic Value (Exact + Float + Complex) ---

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Value {
    pub c: Complex,
    pub rat: Option<ExactRat>,
}

impl Deref for Value {
    type Target = Complex;
    fn deref(&self) -> &Self::Target {
        &self.c
    }
}

impl Value {
    pub fn from_complex(c: Complex) -> Self {
        Self { c, rat: None }
    }

    pub fn from_f64(v: f64) -> Self {
        Self {
            c: Complex::real(v),
            rat: None,
        }
    }

    pub fn from_int(n: i128) -> Self {
        Self {
            c: Complex::real(n as f64),
            rat: Some(ExactRat::from_int(n)),
        }
    }

    pub fn from_rat(num: i128, den: i128) -> Option<Self> {
        let r = ExactRat::new(num, den)?;
        Some(Self {
            c: Complex::real(r.to_f64()),
            rat: Some(r),
        })
    }

    pub fn to_f64(self) -> f64 {
        self.c.re
    }

    pub fn add(self, o: Self) -> Self {
        let rat = match (self.rat, o.rat) {
            (Some(a), Some(b)) => a.add(b),
            _ => None,
        };
        let c = self.c.add(o.c);
        let c = if let Some(r) = rat {
            if c.is_real() { Complex::real(r.to_f64()) } else { c }
        } else {
            c
        };
        Self { c, rat }
    }

    pub fn sub(self, o: Self) -> Self {
        let rat = match (self.rat, o.rat) {
            (Some(a), Some(b)) => a.sub(b),
            _ => None,
        };
        let c = self.c.sub(o.c);
        let c = if let Some(r) = rat {
            if c.is_real() { Complex::real(r.to_f64()) } else { c }
        } else {
            c
        };
        Self { c, rat }
    }

    pub fn mul(self, o: Self) -> Self {
        let rat = match (self.rat, o.rat) {
            (Some(a), Some(b)) => a.mul(b),
            _ => None,
        };
        let c = self.c.mul(o.c);
        let c = if let Some(r) = rat {
            if c.is_real() { Complex::real(r.to_f64()) } else { c }
        } else {
            c
        };
        Self { c, rat }
    }

    pub fn div(self, o: Self) -> Result<Self, String> {
        if o.c.abs() == 0.0 || (o.c.re == 0.0 && o.c.im == 0.0) {
            return Err("Division durch Null".to_string());
        }
        let rat = match (self.rat, o.rat) {
            (Some(a), Some(b)) => a.div(b),
            _ => None,
        };
        let c = self.c.div(o.c);
        if !c.re.is_finite() || !c.im.is_finite() {
            return Err("Division durch Null".to_string());
        }
        let c = if let Some(r) = rat {
            if c.is_real() { Complex::real(r.to_f64()) } else { c }
        } else {
            c
        };
        Ok(Self { c, rat })
    }

    pub fn pow(self, exp: Self) -> Self {
        if let (Some(r), Some(er)) = (self.rat, exp.rat) {
            if er.den == 1 && er.num >= -20 && er.num <= 20 {
                if let Some(res_r) = r.pow(er.num as i32) {
                    return Self {
                        c: Complex::real(res_r.to_f64()),
                        rat: Some(res_r),
                    };
                }
            }
        }
        Self {
            c: self.c.pow(exp.c),
            rat: None,
        }
    }

    pub fn sqrt(self) -> Self {
        if let Some(r) = self.rat {
            if r.num >= 0 && r.den == 1 {
                let s = (r.num as f64).sqrt().round() as i128;
                if s * s == r.num {
                    return Self::from_int(s);
                }
            }
        }
        Self {
            c: self.c.sqrt(),
            rat: None,
        }
    }
}

impl fmt::Display for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(r) = self.rat {
            if r.den == 1 {
                write!(f, "{}", r.num)
            } else {
                write!(f, "{}/{}", r.num, r.den)
            }
        } else if self.c.is_real() {
            write!(f, "{}", self.c.re)
        } else {
            write!(f, "{} + {}i", self.c.re, self.c.im)
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct IntegrationReport {
    pub value: f64,
    pub abs_err: f64,
    pub converged: bool,
    pub subintervals: usize,
    pub evaluations: usize,
    pub message: String,
}

#[derive(Clone, Debug)]
pub struct EvalOutput {
    pub exact_text: String,
    pub decimal_text: String,
    pub has_distinct_exact: bool,
    pub numeric_value: Option<Complex>,
    pub val: Option<Value>,
    pub integration_report: Option<IntegrationReport>,
}

#[derive(Clone, Debug)]
pub struct MathEngine {
    pub variables: HashMap<String, Value>,
    pub ans: Value,
    pub angle_mode: AngleMode,
    pub number_format: NumberFormatMode,
    pub modulo_always_positive: bool,
    pub show_exact_first: bool,
    pub auto_ans_insertion: bool,
    pub last_integration_report: RefCell<Option<IntegrationReport>>,
}

impl Default for MathEngine {
    fn default() -> Self {
        let mut variables = HashMap::new();
        variables.insert("a".to_string(), Value::from_int(5));
        variables.insert("b".to_string(), Value::from_int(3));
        variables.insert("x".to_string(), Value::from_int(2));
        Self {
            variables,
            ans: Value::from_int(0),
            angle_mode: AngleMode::Radians,
            number_format: NumberFormatMode::SpacePoint,
            modulo_always_positive: true,
            show_exact_first: true,
            auto_ans_insertion: true,
            last_integration_report: RefCell::new(None),
        }
    }
}

impl MathEngine {
    pub fn get_last_integration_report(&self) -> Option<IntegrationReport> {
        self.last_integration_report.borrow().clone()
    }
    /// Beautify an input expression for natural display (similar to `TermView`)
    pub fn prettify_expression(expr: &str) -> String {
        let s = expr
            .replace('*', " × ")
            .replace('/', " ÷ ")
            .replace('-', "−")
            .replace("sqrt(", "√(")
            .replace("nthroot(", "ⁿ√(")
            .replace("sum(", "∑(")
            .replace("prod(", "∏(")
            .replace("int(", "∫(")
            .replace("deriv(", "d/dx(")
            .replace("asinh(", "sinh⁻¹(")
            .replace("acosh(", "cosh⁻¹(")
            .replace("atanh(", "tanh⁻¹(")
            .replace("asin(", "sin⁻¹(")
            .replace("acos(", "cos⁻¹(")
            .replace("atan(", "tan⁻¹(")
            .replace("pi", "π")
            .replace("ANS", "ans");
        format_unchained_superscripts(&s)
    }

    pub fn evaluate_and_commit(&mut self, raw_input: &str) -> Result<EvalOutput, String> {
        let res = self.evaluate_preview(raw_input, true)?;
        if let Some(val) = res.val {
            self.ans = val;
        }
        Ok(res)
    }

    pub fn evaluate_preview(&mut self, raw_input: &str, allow_mutation: bool) -> Result<EvalOutput, String> {
        self.last_integration_report.replace(None);
        let trimmed = raw_input.trim();
        if trimmed.is_empty() {
            return Err("Leerer Ausdruck".to_string());
        }

        // Check `unset(var)` or original Algebrite `~=var` / `var~=` (`Lc/a/a/e/d;->a` line 672)
        let unset_candidate = trimmed
            .strip_prefix("unset(")
            .and_then(|s| s.strip_suffix(')'))
            .or_else(|| trimmed.strip_prefix("~="))
            .or_else(|| trimmed.strip_suffix("~="));
        if let Some(inner) = unset_candidate {
            let vname = inner.trim();
            if USER_VARIABLES.contains(&vname) {
                if allow_mutation {
                    self.variables.remove(vname);
                }
                return Ok(EvalOutput {
                    exact_text: format!("{} gelöscht (unset)", vname),
                    decimal_text: format!("{} gelöscht (unset)", vname),
                    has_distinct_exact: false,
                    numeric_value: None,
                    val: None,
                    integration_report: None,
                });
            }
        }

        // Check variable assignment `a := expr`
        if let Some((lhs, rhs)) = trimmed.split_once(":=") {
            let var_name = lhs.trim();
            if !USER_VARIABLES.contains(&var_name) {
                return Err(format!(
                    "Unbekannte Variable '{}' (erlaubt: a, b, c, d, k, l, m, n, x, y, z)",
                    var_name
                ));
            }
            let rhs_out = self.evaluate_preview(rhs, false)?;
            if let Some(val) = rhs_out.val {
                if allow_mutation {
                    self.variables.insert(var_name.to_string(), val);
                }
                return Ok(EvalOutput {
                    exact_text: format!("{} := {}", var_name, rhs_out.exact_text),
                    decimal_text: format!("{} := {}", var_name, rhs_out.decimal_text),
                    has_distinct_exact: rhs_out.has_distinct_exact,
                    numeric_value: Some(val.c),
                    val: Some(val),
                    integration_report: None,
                });
            } else {
                return Err("Zuweisung erfordert numerischen Wert".to_string());
            }
        }

        // Check Matrix expressions: det([[...]]), inv([[...]]), trans([[...]]), transpose([[...]]), or [[...]]
        if trimmed.starts_with("det(")
            || trimmed.starts_with("inv(")
            || trimmed.starts_with("trans(")
            || trimmed.starts_with("transpose(")
            || trimmed.starts_with("[[")
        {
            return self.evaluate_matrix_expression(trimmed);
        }

        // Parse expression into AST for structural symbolic evaluation
        let parsed_ast = AstParser::parse(trimmed).ok();

        // Check symbolic calculus first (indefinite integral, symbolic derivative) on AST
        if let Some(ref ast) = parsed_ast {
            if let Some(sym_out) = self.try_evaluate_symbolic_calculus(ast) {
                return Ok(sym_out);
            }
        }

        // Evaluate via recursive descent parser
        let mut parser = ExprParser::new(trimmed, self, None);
        let parse_res = parser.parse_expr();

        match parse_res {
            Ok(val) => {
                parser.skip_ws();
                if parser.pos < parser.chars.len() {
                    return Err(format!(
                        "Unerwartetes Zeichen '{}'",
                        parser.chars[parser.pos]
                    ));
                }

                if !val.c.re.is_finite() || !val.c.im.is_finite() {
                    return Err("Ungültiges Rechenergebnis".to_string());
                }

                let dec_str = self.format_value_decimal(val);
                let exact_opt = self.try_format_exact_val(trimmed, val);
                let (exact_text, has_distinct_exact) = match exact_opt {
                    Some(ex) if ex != dec_str => (ex, true),
                    _ => (dec_str.clone(), false),
                };

                Ok(EvalOutput {
                    exact_text,
                    decimal_text: dec_str,
                    has_distinct_exact,
                    numeric_value: Some(val.c),
                    val: Some(val),
                    integration_report: self.get_last_integration_report(),
                })
            }
            Err(err) => {
                // If parsing failed due to an unbound variable, try symbolic polynomial simplification on AST!
                if err.contains("Unbekannte Variable") {
                    if let Some(ref ast) = parsed_ast {
                        if let Some(sym) = self.try_simplify_symbolic(ast) {
                            return Ok(sym);
                        }
                    }
                }
                Err(err)
            }
        }
    }

    pub fn eval_for_x(&self, expr: &str, x_val: f64) -> Option<f64> {
        let mut ov = HashMap::new();
        ov.insert("x".to_string(), Value::from_f64(x_val));
        let mut parser = ExprParser::new(expr, self, Some(&ov));
        let val = parser.parse_expr().ok()?;
        parser.skip_ws();
        if parser.pos < parser.chars.len() {
            return None;
        }
        if val.c.is_real() && val.c.re.is_finite() {
            Some(val.c.re)
        } else {
            None
        }
    }

    fn try_evaluate_symbolic_calculus(&self, ast: &AstNode) -> Option<EvalOutput> {
        match ast {
            AstNode::Func(name, args) if name == "integral" && args.len() == 2 => {
                if let AstNode::Var(var) = &args[1] {
                    if let Some(res_ast) = args[0].integrate(var) {
                        let res = res_ast.simplify().to_display_string();
                        return Some(EvalOutput {
                            exact_text: res.clone(),
                            decimal_text: res,
                            has_distinct_exact: true,
                            numeric_value: None,
                            val: None,
                            integration_report: None,
                        });
                    }
                }
            }
            AstNode::Func(name, args) if (name == "d" || name == "deriv" || name == "diff") && args.len() == 2 => {
                if let AstNode::Var(var) = &args[1] {
                    if let Some(diff_ast) = args[0].diff(var) {
                        let res_ast = diff_ast.simplify();
                        let res = res_ast.to_display_string();
                        return Some(EvalOutput {
                            exact_text: res.clone(),
                            decimal_text: res,
                            has_distinct_exact: true,
                            numeric_value: None,
                            val: None,
                            integration_report: None,
                        });
                    }
                }
            }
            _ => {}
        }
        None
    }

    fn try_simplify_symbolic(&self, ast: &AstNode) -> Option<EvalOutput> {
        if let Some(simplified_ast) = simplify_polynomial_ast(ast) {
            let res = simplified_ast.to_display_string();
            return Some(EvalOutput {
                exact_text: res.clone(),
                decimal_text: res,
                has_distinct_exact: true,
                numeric_value: None,
                val: None,
                integration_report: None,
            });
        }
        None
    }

    fn format_value_decimal(&self, val: Value) -> String {
        if let Some(r) = val.rat {
            if r.den == 1 {
                return r.num.to_string();
            }
        }
        self.format_complex_decimal(val.c)
    }

    fn format_complex_decimal(&self, c: Complex) -> String {
        if c.is_real() {
            self.number_format.format_f64(c.re)
        } else if c.re.abs() < 1e-12 {
            if (c.im - 1.0).abs() < 1e-12 {
                "i".to_string()
            } else if (c.im + 1.0).abs() < 1e-12 {
                "-i".to_string()
            } else {
                format!("{} i", self.number_format.format_f64(c.im))
            }
        } else {
            let re_s = self.number_format.format_f64(c.re);
            let im_abs = self.number_format.format_f64(c.im.abs());
            let sign = if c.im >= 0.0 { "+" } else { "-" };
            if (c.im.abs() - 1.0).abs() < 1e-12 {
                format!("{} {} i", re_s, sign)
            } else {
                format!("{} {} {} i", re_s, sign, im_abs)
            }
        }
    }

    fn try_format_exact_val(&self, _expr: &str, val: Value) -> Option<String> {
        if let Some(r) = val.rat {
            if r.den > 1 {
                return Some(format!("{} / {}", r.num, r.den));
            }
            if r.den == 1 {
                return None; // Integer
            }
        }
        self.try_format_exact(_expr, val.c)
    }

    fn try_format_exact(&self, _expr: &str, c: Complex) -> Option<String> {
        if !c.is_real() {
            let re_ex = self.try_real_exact(c.re).unwrap_or_else(|| self.number_format.format_f64(c.re));
            let im_ex = self.try_real_exact(c.im.abs()).unwrap_or_else(|| self.number_format.format_f64(c.im.abs()));
            if c.re.abs() < 1e-12 {
                return Some(if (c.im - 1.0).abs() < 1e-12 {
                    "i".to_string()
                } else if (c.im + 1.0).abs() < 1e-12 {
                    "-i".to_string()
                } else {
                    format!("{} · i", self.try_real_exact(c.im).unwrap_or(im_ex))
                });
            }
            let sign = if c.im >= 0.0 { "+" } else { "-" };
            return Some(format!("{} {} {} · i", re_ex, sign, im_ex));
        }

        self.try_real_exact(c.re)
    }

    fn try_real_exact(&self, x: f64) -> Option<String> {
        if !x.is_finite() {
            return None;
        }
        if (x - x.round()).abs() < 1e-11 {
            return None; // Already an integer
        }

        // 1. Check exact rational fraction p / q with strict tolerance
        if let Some((p, q)) = approximate_fraction(x, 100_000, 1e-12) {
            if q > 1 && q <= 10_000 {
                return Some(format!("{} / {}", p, q));
            }
        }

        // 2. Check rational multiple of π: (p / q) * π
        let over_pi = x / std::f64::consts::PI;
        if let Some((p, q)) = approximate_fraction(over_pi, 720, 1e-10) {
            if p != 0 && q <= 360 {
                return Some(match (p, q) {
                    (1, 1) => "π".to_string(),
                    (-1, 1) => "-π".to_string(),
                    (p, 1) => format!("{} π", p),
                    (1, q) => format!("π / {}", q),
                    (-1, q) => format!("-π / {}", q),
                    (p, q) => format!("({} / {}) π", p, q),
                });
            }
        }

        // 3. Check rational multiple of √2, √3, √5, √6, etc.
        for radicand in [2, 3, 5, 6, 7, 10] {
            let root = (radicand as f64).sqrt();
            let ratio = x / root;
            if let Some((p, q)) = approximate_fraction(ratio, 100, 1e-10) {
                if p != 0 && q <= 60 {
                    return Some(match (p, q) {
                        (1, 1) => format!("√{}", radicand),
                        (-1, 1) => format!("-√{}", radicand),
                        (p, 1) => format!("{} √{}", p, radicand),
                        (1, q) => format!("√{} / {}", radicand, q),
                        (-1, q) => format!("-√{} / {}", radicand, q),
                        (p, q) => format!("({} √{}) / {}", p, radicand, q),
                    });
                }
            }
        }

        // 4. Check exact power of e
        let ln_x = if x > 0.0 { x.ln() } else { -1000.0 };
        if ln_x > -20.0 && (ln_x - ln_x.round()).abs() < 1e-10 {
            let k = ln_x.round() as i32;
            if k == 1 {
                return Some("e".to_string());
            } else if k != 0 && k.abs() <= 12 {
                return Some(format!("e{}", to_superscript(k)));
            }
        }

        None
    }

    fn evaluate_matrix_expression(&self, expr: &str) -> Result<EvalOutput, String> {
        let trimmed = expr.trim();
        if let Some(inner) = trimmed.strip_prefix("det(").and_then(|s| s.strip_suffix(')')) {
            let mat = self.eval_matrix_expr(inner)?;
            let det = matrix_det(&mat)?;
            let dec = self.number_format.format_f64(det);
            let ex = self.try_real_exact(det).unwrap_or_else(|| dec.clone());
            let val = if (det - det.round()).abs() < 1e-12 {
                Value::from_int(det.round() as i128)
            } else {
                Value::from_f64(det)
            };
            return Ok(EvalOutput {
                has_distinct_exact: ex != dec,
                exact_text: ex,
                decimal_text: dec,
                numeric_value: Some(Complex::real(det)),
                val: Some(val),
                integration_report: None,
            });
        }

        let mat = self.eval_matrix_expr(trimmed)?;
        let formatted = self.format_matrix(&mat, true);
        let formatted_dec = self.format_matrix(&mat, false);
        Ok(EvalOutput {
            has_distinct_exact: formatted != formatted_dec,
            exact_text: formatted,
            decimal_text: formatted_dec,
            numeric_value: None,
            val: None,
            integration_report: None,
        })
    }

    fn eval_matrix_expr(&self, s: &str) -> Result<Vec<Vec<f64>>, String> {
        let s = s.trim();
        let mut depth = 0;
        let mut split_pos = None;
        let mut op = '+';
        for (i, ch) in s.char_indices() {
            if ch == '[' || ch == '(' { depth += 1; }
            else if ch == ']' || ch == ')' { depth -= 1; }
            else if depth == 0 && (ch == '+' || ch == '-') && i > 0 {
                split_pos = Some(i);
                op = ch;
                break;
            }
        }
        if let Some(pos) = split_pos {
            let left = self.eval_matrix_expr(&s[..pos])?;
            let right = self.eval_matrix_expr(&s[pos + 1..])?;
            if left.len() != right.len() || left[0].len() != right[0].len() {
                return Err("Incompatible matrix dimensions".to_string());
            }
            let rows = left.len();
            let cols = left[0].len();
            let mut res = vec![vec![0.0; cols]; rows];
            for r in 0..rows {
                for c in 0..cols {
                    res[r][c] = if op == '+' { left[r][c] + right[r][c] } else { left[r][c] - right[r][c] };
                }
            }
            return Ok(res);
        }
        if s.starts_with('[') {
            return self.parse_matrix(s);
        }
        if let Some(inner) = s.strip_prefix("inv(").and_then(|x| x.strip_suffix(')')) {
            let mat = self.eval_matrix_expr(inner)?;
            return matrix_inv(&mat);
        }
        let trans_inner = s
            .strip_prefix("trans(")
            .or_else(|| s.strip_prefix("transpose("))
            .and_then(|x| x.strip_suffix(')'));
        if let Some(inner) = trans_inner {
            let mat = self.eval_matrix_expr(inner)?;
            let rows = mat.len();
            let cols = mat[0].len();
            let mut t = vec![vec![0.0; rows]; cols];
            for r in 0..rows {
                for c in 0..cols {
                    t[c][r] = mat[r][c];
                }
            }
            return Ok(t);
        }
        Err("Ungültiger Matrix-Ausdruck".to_string())
    }

    fn parse_matrix(&self, s: &str) -> Result<Vec<Vec<f64>>, String> {
        let s = s.trim();
        let inner = s
            .strip_prefix('[')
            .and_then(|x| x.strip_suffix(']'))
            .ok_or_else(|| "Matrix muss mit [[...]] umschlossen sein".to_string())?
            .trim();
        let mut rows = Vec::new();
        let mut cur = String::new();
        let mut depth = 0i32;
        for ch in inner.chars() {
            match ch {
                '[' => {
                    depth += 1;
                    if depth > 1 {
                        cur.push(ch);
                    }
                }
                ']' => {
                    depth -= 1;
                    if depth == 0 {
                        let mut row_vals = Vec::new();
                        for cell in cur.split(',') {
                            let mut p = ExprParser::new(cell.trim(), self, None);
                            let v = p.parse_expr()?;
                            row_vals.push(v.c.re);
                        }
                        rows.push(row_vals);
                        cur.clear();
                    } else {
                        cur.push(ch);
                    }
                }
                ',' if depth == 0 => {}
                _ => {
                    if depth >= 1 {
                        cur.push(ch);
                    }
                }
            }
        }
        if rows.is_empty() || rows[0].is_empty() {
            return Err("Leere Matrix".to_string());
        }
        let cols = rows[0].len();
        if rows.iter().any(|r| r.len() != cols) {
            return Err("Incompatible matrix dimensions".to_string());
        }
        Ok(rows)
    }

    fn format_matrix(&self, mat: &[Vec<f64>], exact: bool) -> String {
        let row_strs: Vec<String> = mat
            .iter()
            .map(|r| {
                let cells: Vec<String> = r
                    .iter()
                    .map(|&v| {
                        if exact {
                            self.try_real_exact(v)
                                .unwrap_or_else(|| self.number_format.format_f64(v))
                        } else {
                            self.number_format.format_f64(v)
                        }
                    })
                    .collect();
                format!("[{}]", cells.join(", "))
            })
            .collect();
        format!("[{}]", row_strs.join(", "))
    }
}

fn matrix_det(m: &[Vec<f64>]) -> Result<f64, String> {
    let n = m.len();
    if n == 0 || m[0].len() != n {
        return Err("Determinante erfordert eine quadratische Matrix".to_string());
    }
    if n == 1 {
        return Ok(m[0][0]);
    }
    if n == 2 {
        return Ok(m[0][0] * m[1][1] - m[0][1] * m[1][0]);
    }
    if n == 3 {
        return Ok(
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]),
        );
    }
    // General NxN via LU / Gaussian elimination with partial pivoting
    let mut a = m.to_vec();
    let mut det = 1.0;
    let mut sign = 1.0;
    for i in 0..n {
        let mut pivot_row = i;
        let mut max_val = a[i][i].abs();
        for k in (i + 1)..n {
            if a[k][i].abs() > max_val {
                max_val = a[k][i].abs();
                pivot_row = k;
            }
        }
        if max_val < 1e-15 {
            return Ok(0.0);
        }
        if pivot_row != i {
            a.swap(i, pivot_row);
            sign = -sign;
        }
        let pivot = a[i][i];
        det *= pivot;
        for k in (i + 1)..n {
            let factor = a[k][i] / pivot;
            for j in (i + 1)..n {
                a[k][j] -= factor * a[i][j];
            }
        }
    }
    Ok(det * sign)
}

fn matrix_inv(m: &[Vec<f64>]) -> Result<Vec<Vec<f64>>, String> {
    let det = matrix_det(m)?;
    if det.abs() < 1e-14 {
        return Err("Matrix ist singulär (det = 0)".to_string());
    }
    let n = m.len();
    if n == 2 {
        Ok(vec![
            vec![m[1][1] / det, -m[0][1] / det],
            vec![-m[1][0] / det, m[0][0] / det],
        ])
    } else if n == 3 {
        let mut inv = vec![vec![0.0; 3]; 3];
        for r in 0..3 {
            for c in 0..3 {
                let r1 = (r + 1) % 3;
                let r2 = (r + 2) % 3;
                let c1 = (c + 1) % 3;
                let c2 = (c + 2) % 3;
                inv[c][r] = (m[r1][c1] * m[r2][c2] - m[r1][c2] * m[r2][c1]) / det;
            }
        }
        Ok(inv)
    } else {
        Err("Matrix-Invertierung wird für 2×2 und 3×3 unterstützt".to_string())
    }
}

pub fn approximate_fraction(x: f64, max_denom: i64, tol: f64) -> Option<(i64, i64)> {
    let sign = if x < 0.0 { -1 } else { 1 };
    let mut v = x.abs();
    let mut h1: i64 = 1;
    let mut h2: i64 = 0;
    let mut k1: i64 = 0;
    let mut k2: i64 = 1;

    for _ in 0..32 {
        let a = v.floor() as i64;
        let h = a.checked_mul(h1)?.checked_add(h2)?;
        let k = a.checked_mul(k1)?.checked_add(k2)?;
        if k > max_denom {
            break;
        }
        h2 = h1;
        h1 = h;
        k2 = k1;
        k1 = k;

        if k1 > 0 && ((h1 as f64 / k1 as f64) - x.abs()).abs() <= tol {
            return Some((sign * h1, k1));
        }
        let rem = v - a as f64;
        if rem.abs() < 1e-14 {
            break;
        }
        v = 1.0 / rem;
    }
    None
}

// --- Adaptive Gauss-Legendre Quadrature with Error Control & Convergence Assertion ---

pub fn adaptive_gauss_integrate<F>(
    f: F,
    a: f64,
    b: f64,
    initial_subintervals: usize,
    eps_abs: f64,
    eps_rel: f64,
) -> Result<IntegrationReport, String>
where
    F: Fn(f64) -> Result<f64, String>,
{
    const NODES5: [f64; 5] = [
        0.0,
        0.5384693101056831,
        -0.5384693101056831,
        0.9061798459386640,
        -0.9061798459386640,
    ];
    const WEIGHTS5: [f64; 5] = [
        0.5688888888888889,
        0.4786286704993665,
        0.4786286704993665,
        0.2369268850561891,
        0.2369268850561891,
    ];

    const NODES3: [f64; 3] = [
        0.0,
        0.7745966692414834,
        -0.7745966692414834,
    ];
    const WEIGHTS3: [f64; 3] = [
        0.8888888888888888,
        0.5555555555555556,
        0.5555555555555556,
    ];

    struct Subinterval {
        a: f64,
        b: f64,
        val5: f64,
        err: f64,
    }

    let eval_sub = |ia: f64, ib: f64| -> Result<Subinterval, String> {
        let h = ib - ia;
        let half_h = h * 0.5;
        let mid = ia + half_h;

        let mut sum5 = 0.0;
        for j in 0..5 {
            let x = mid + half_h * NODES5[j];
            let y = f(x)?;
            if !y.is_finite() {
                return Err("Integrand ist im Integrationsbereich nicht endlich".to_string());
            }
            sum5 += WEIGHTS5[j] * y;
        }
        let q5 = sum5 * half_h;

        let mut sum3 = 0.0;
        for j in 0..3 {
            let x = mid + half_h * NODES3[j];
            let y = f(x)?;
            if !y.is_finite() {
                return Err("Integrand ist im Integrationsbereich nicht endlich".to_string());
            }
            sum3 += WEIGHTS3[j] * y;
        }
        let q3 = sum3 * half_h;

        let err = (q5 - q3).abs();
        Ok(Subinterval { a: ia, b: ib, val5: q5, err })
    };

    let mut intervals = Vec::with_capacity(initial_subintervals * 2);
    let h = (b - a) / (initial_subintervals as f64);
    let mut total_val = 0.0;
    let mut total_err = 0.0;

    for i in 0..initial_subintervals {
        let ia = a + (i as f64) * h;
        let ib = if i == initial_subintervals - 1 { b } else { ia + h };
        let sub = eval_sub(ia, ib)?;
        total_val += sub.val5;
        total_err += sub.err;
        intervals.push(sub);
    }

    let mut evals = initial_subintervals * 7;
    let max_evals = 100_000;
    let target_tol = eps_abs.max(eps_rel * total_val.abs());

    while total_err > target_tol && evals < max_evals {
        let mut worst_idx = 0;
        let mut worst_err = intervals[0].err;
        for (idx, iv) in intervals.iter().enumerate().skip(1) {
            if iv.err > worst_err {
                worst_err = iv.err;
                worst_idx = idx;
            }
        }

        if worst_err <= 1e-15 {
            break;
        }

        let worst = intervals.swap_remove(worst_idx);
        total_val -= worst.val5;
        total_err -= worst.err;

        let mid = (worst.a + worst.b) * 0.5;
        let left = eval_sub(worst.a, mid)?;
        let right = eval_sub(mid, worst.b)?;

        total_val += left.val5 + right.val5;
        total_err += left.err + right.err;

        intervals.push(left);
        intervals.push(right);
        evals += 14;

        if total_err <= target_tol {
            break;
        }
    }

    let converged = total_err <= target_tol;
    let message = if converged {
        format!(
            "Konvergiert: Fehlerabschätzung {:.2e} <= Toleranz {:.2e} ({} Auswertungen)",
            total_err, target_tol, evals
        )
    } else {
        format!(
            "Konvergenz nicht erreicht: Fehlerabschätzung {:.2e} > Toleranz {:.2e} (Budget erschöpft: {} Auswertungen)",
            total_err, target_tol, evals
        )
    };

    Ok(IntegrationReport {
        value: total_val,
        abs_err: total_err,
        converged,
        subintervals: intervals.len(),
        evaluations: evals,
        message,
    })
}

// --- AST for Symbolic Calculus and Algebraic Manipulation ---

#[derive(Clone, Debug, PartialEq)]
pub enum AstNode {
    Num(Value),
    Var(String),
    Add(Box<AstNode>, Box<AstNode>),
    Sub(Box<AstNode>, Box<AstNode>),
    Mul(Box<AstNode>, Box<AstNode>),
    Div(Box<AstNode>, Box<AstNode>),
    Pow(Box<AstNode>, Box<AstNode>),
    Neg(Box<AstNode>),
    Func(String, Vec<AstNode>),
}

impl AstNode {
    pub fn is_zero(&self) -> bool {
        match self.const_value() {
            Some(v) => v.c.re == 0.0 && v.c.im == 0.0,
            None => false,
        }
    }

    pub fn is_one(&self) -> bool {
        match self.const_value() {
            Some(v) => v.c.re == 1.0 && v.c.im == 0.0,
            None => false,
        }
    }

    pub fn is_minus_one(&self) -> bool {
        match self.const_value() {
            Some(v) => v.c.re == -1.0 && v.c.im == 0.0,
            None => false,
        }
    }

    pub fn const_value(&self) -> Option<Value> {
        match self {
            AstNode::Num(v) => Some(*v),
            AstNode::Neg(inner) => {
                let v = inner.const_value()?;
                Some(Value {
                    c: Complex {
                        re: -v.c.re,
                        im: -v.c.im,
                    },
                    rat: v.rat.map(|r| ExactRat {
                        num: -r.num,
                        den: r.den,
                    }),
                })
            }
            _ => None,
        }
    }

    pub fn contains_var(&self, var: &str) -> bool {
        match self {
            AstNode::Num(_) => false,
            AstNode::Var(v) => v == var,
            AstNode::Add(a, b)
            | AstNode::Sub(a, b)
            | AstNode::Mul(a, b)
            | AstNode::Div(a, b)
            | AstNode::Pow(a, b) => a.contains_var(var) || b.contains_var(var),
            AstNode::Neg(a) => a.contains_var(var),
            AstNode::Func(_, args) => args.iter().any(|arg| arg.contains_var(var)),
        }
    }

    pub fn diff(&self, var: &str) -> Option<AstNode> {
        match self {
            AstNode::Num(_) => Some(AstNode::Num(Value::from_int(0))),
            AstNode::Var(v) => {
                if v == var {
                    Some(AstNode::Num(Value::from_int(1)))
                } else {
                    Some(AstNode::Num(Value::from_int(0)))
                }
            }
            AstNode::Add(a, b) => Some(AstNode::Add(Box::new(a.diff(var)?), Box::new(b.diff(var)?))),
            AstNode::Sub(a, b) => Some(AstNode::Sub(Box::new(a.diff(var)?), Box::new(b.diff(var)?))),
            AstNode::Mul(a, b) => {
                // Product rule: (a * b)' = a' * b + a * b'
                let da = a.diff(var)?;
                let db = b.diff(var)?;
                Some(AstNode::Add(
                    Box::new(AstNode::Mul(Box::new(da), b.clone())),
                    Box::new(AstNode::Mul(a.clone(), Box::new(db))),
                ))
            }
            AstNode::Div(a, b) => {
                if b.is_zero() {
                    return None;
                }
                // Quotient rule: (a / b)' = (a' * b - a * b') / b^2
                let da = a.diff(var)?;
                let db = b.diff(var)?;
                Some(AstNode::Div(
                    Box::new(AstNode::Sub(
                        Box::new(AstNode::Mul(Box::new(da), b.clone())),
                        Box::new(AstNode::Mul(a.clone(), Box::new(db))),
                    )),
                    Box::new(AstNode::Pow(
                        b.clone(),
                        Box::new(AstNode::Num(Value::from_int(2))),
                    )),
                ))
            }
            AstNode::Pow(base, exp) => {
                let base_has_var = base.contains_var(var);
                let exp_has_var = exp.contains_var(var);

                if !base_has_var && !exp_has_var {
                    return Some(AstNode::Num(Value::from_int(0)));
                }

                if base_has_var && !exp_has_var {
                    // Power rule: (u^c)' = c * u^(c - 1) * u'
                    let u_prime = base.diff(var)?;
                    if let Some(c_val) = exp.const_value() {
                        if !c_val.c.is_real() {
                            return None;
                        }
                        if c_val.c.re == 0.0 {
                            return Some(AstNode::Num(Value::from_int(0)));
                        }
                        if c_val.c.re == 1.0 {
                            return Some(u_prime);
                        }
                        let c_minus_1 = c_val.sub(Value::from_int(1));
                        let power_node = if c_minus_1.c.re == 1.0 && c_minus_1.c.im == 0.0 {
                            (**base).clone()
                        } else {
                            AstNode::Pow(base.clone(), Box::new(AstNode::Num(c_minus_1)))
                        };
                        return Some(AstNode::Mul(
                            Box::new(AstNode::Mul(
                                Box::new(AstNode::Num(c_val)),
                                Box::new(power_node),
                            )),
                            Box::new(u_prime),
                        ));
                    } else {
                        let exp_minus_1 = AstNode::Sub(
                            exp.clone(),
                            Box::new(AstNode::Num(Value::from_int(1))),
                        );
                        return Some(AstNode::Mul(
                            Box::new(AstNode::Mul(
                                exp.clone(),
                                Box::new(AstNode::Pow(base.clone(), Box::new(exp_minus_1))),
                            )),
                            Box::new(u_prime),
                        ));
                    }
                }

                if !base_has_var && exp_has_var {
                    // Exponential rule: (c^v)' = c^v * ln(c) * v'
                    if base.is_zero() {
                        return None;
                    }
                    let v_prime = exp.diff(var)?;
                    return Some(AstNode::Mul(
                        Box::new(AstNode::Mul(
                            Box::new(self.clone()),
                            Box::new(AstNode::Func("ln".to_string(), vec![(**base).clone()])),
                        )),
                        Box::new(v_prime),
                    ));
                }

                // General power rule (u > 0): (u^v)' = u^v * (v' * ln(u) + (v * u') / u)
                let u_prime = base.diff(var)?;
                let v_prime = exp.diff(var)?;
                let term1 = AstNode::Mul(
                    Box::new(v_prime),
                    Box::new(AstNode::Func("ln".to_string(), vec![(**base).clone()])),
                );
                let term2 = AstNode::Div(
                    Box::new(AstNode::Mul(exp.clone(), Box::new(u_prime))),
                    base.clone(),
                );
                Some(AstNode::Mul(
                    Box::new(self.clone()),
                    Box::new(AstNode::Add(Box::new(term1), Box::new(term2))),
                ))
            }
            AstNode::Neg(a) => Some(AstNode::Neg(Box::new(a.diff(var)?))),
            AstNode::Func(name, args) if args.len() == 1 => {
                let u = &args[0];
                if !u.contains_var(var) {
                    return Some(AstNode::Num(Value::from_int(0)));
                }
                let u_prime = u.diff(var)?;
                match name.as_str() {
                    "sin" => Some(AstNode::Mul(
                        Box::new(u_prime),
                        Box::new(AstNode::Func("cos".to_string(), vec![u.clone()])),
                    )),
                    "cos" => Some(AstNode::Neg(Box::new(AstNode::Mul(
                        Box::new(u_prime),
                        Box::new(AstNode::Func("sin".to_string(), vec![u.clone()])),
                    )))),
                    "tan" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Pow(
                            Box::new(AstNode::Func("cos".to_string(), vec![u.clone()])),
                            Box::new(AstNode::Num(Value::from_int(2))),
                        )),
                    )),
                    "sinh" => Some(AstNode::Mul(
                        Box::new(u_prime),
                        Box::new(AstNode::Func("cosh".to_string(), vec![u.clone()])),
                    )),
                    "cosh" => Some(AstNode::Mul(
                        Box::new(u_prime),
                        Box::new(AstNode::Func("sinh".to_string(), vec![u.clone()])),
                    )),
                    "tanh" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Pow(
                            Box::new(AstNode::Func("cosh".to_string(), vec![u.clone()])),
                            Box::new(AstNode::Num(Value::from_int(2))),
                        )),
                    )),
                    "asin" | "arcsin" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Func(
                            "sqrt".to_string(),
                            vec![AstNode::Sub(
                                Box::new(AstNode::Num(Value::from_int(1))),
                                Box::new(AstNode::Pow(
                                    Box::new(u.clone()),
                                    Box::new(AstNode::Num(Value::from_int(2))),
                                )),
                            )],
                        )),
                    )),
                    "acos" | "arccos" => Some(AstNode::Neg(Box::new(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Func(
                            "sqrt".to_string(),
                            vec![AstNode::Sub(
                                Box::new(AstNode::Num(Value::from_int(1))),
                                Box::new(AstNode::Pow(
                                    Box::new(u.clone()),
                                    Box::new(AstNode::Num(Value::from_int(2))),
                                )),
                            )],
                        )),
                    )))),
                    "atan" | "arctan" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Add(
                            Box::new(AstNode::Num(Value::from_int(1))),
                            Box::new(AstNode::Pow(
                                Box::new(u.clone()),
                                Box::new(AstNode::Num(Value::from_int(2))),
                            )),
                        )),
                    )),
                    "asinh" | "arcsinh" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Func(
                            "sqrt".to_string(),
                            vec![AstNode::Add(
                                Box::new(AstNode::Pow(
                                    Box::new(u.clone()),
                                    Box::new(AstNode::Num(Value::from_int(2))),
                                )),
                                Box::new(AstNode::Num(Value::from_int(1))),
                            )],
                        )),
                    )),
                    "acosh" | "arccosh" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Func(
                            "sqrt".to_string(),
                            vec![AstNode::Sub(
                                Box::new(AstNode::Pow(
                                    Box::new(u.clone()),
                                    Box::new(AstNode::Num(Value::from_int(2))),
                                )),
                                Box::new(AstNode::Num(Value::from_int(1))),
                            )],
                        )),
                    )),
                    "atanh" | "arctanh" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Sub(
                            Box::new(AstNode::Num(Value::from_int(1))),
                            Box::new(AstNode::Pow(
                                Box::new(u.clone()),
                                Box::new(AstNode::Num(Value::from_int(2))),
                            )),
                        )),
                    )),
                    "sqrt" => Some(AstNode::Div(
                        Box::new(u_prime),
                        Box::new(AstNode::Mul(
                            Box::new(AstNode::Num(Value::from_int(2))),
                            Box::new(AstNode::Func("sqrt".to_string(), vec![u.clone()])),
                        )),
                    )),
                    "exp" => Some(AstNode::Mul(
                        Box::new(u_prime),
                        Box::new(AstNode::Func("exp".to_string(), vec![u.clone()])),
                    )),
                    "ln" => Some(AstNode::Div(Box::new(u_prime), Box::new(u.clone()))),
                    _ => None,
                }
            }
            AstNode::Func(name, args) if args.len() == 2 => {
                if !self.contains_var(var) {
                    return Some(AstNode::Num(Value::from_int(0)));
                }
                match name.as_str() {
                    "power" | "pow" => AstNode::Pow(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff(var),
                    "addition" => AstNode::Add(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff(var),
                    "subtraction" => AstNode::Sub(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff(var),
                    "multiplication" => AstNode::Mul(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff(var),
                    "division" | "fraction" => AstNode::Div(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff(var),
                    _ => None,
                }
            }
            _ => {
                if !self.contains_var(var) {
                    Some(AstNode::Num(Value::from_int(0)))
                } else {
                    None
                }
            }
        }
    }

    pub fn integrate(&self, var: &str) -> Option<AstNode> {
        match self {
            AstNode::Var(v) if v == var => {
                // \int x dx = x^2 / 2
                Some(AstNode::Div(
                    Box::new(AstNode::Pow(
                        Box::new(AstNode::Var(var.to_string())),
                        Box::new(AstNode::Num(Value::from_int(2))),
                    )),
                    Box::new(AstNode::Num(Value::from_int(2))),
                ))
            }
            AstNode::Num(v) if v.c.re == 1.0 && v.c.im == 0.0 => {
                Some(AstNode::Var(var.to_string()))
            }
            AstNode::Num(v) => {
                Some(AstNode::Mul(
                    Box::new(AstNode::Num(*v)),
                    Box::new(AstNode::Var(var.to_string())),
                ))
            }
            AstNode::Div(a, b) if a.is_one() && **b == AstNode::Var(var.to_string()) => {
                Some(AstNode::Func(
                    "ln".to_string(),
                    vec![AstNode::Var(var.to_string())],
                ))
            }
            AstNode::Pow(base, exp) if **base == AstNode::Var(var.to_string()) => {
                let n_opt = match **exp {
                    AstNode::Num(n_val) => Some(n_val.c.re),
                    AstNode::Neg(ref inner) => match inner.as_ref() {
                        AstNode::Num(n_val) => Some(-n_val.c.re),
                        _ => None,
                    },
                    _ => None,
                };
                if let Some(re) = n_opt {
                    if (re - re.round()).abs() < 1e-11 {
                        let n = re.round() as i128;
                        if n == -1 {
                            return Some(AstNode::Func(
                                "ln".to_string(),
                                vec![AstNode::Var(var.to_string())],
                            ));
                        } else {
                            let new_p = n + 1;
                            return Some(AstNode::Div(
                                Box::new(AstNode::Pow(
                                    Box::new(AstNode::Var(var.to_string())),
                                    Box::new(AstNode::Num(Value::from_int(new_p))),
                                )),
                                Box::new(AstNode::Num(Value::from_int(new_p))),
                            ));
                        }
                    }
                }
                None
            }
            AstNode::Func(name, args) if args.len() == 1 => {
                if args[0] == AstNode::Var(var.to_string()) {
                    match name.as_str() {
                        "exp" => Some(AstNode::Func("exp".to_string(), vec![AstNode::Var(var.to_string())])),
                        "sin" => Some(AstNode::Neg(Box::new(AstNode::Func("cos".to_string(), vec![AstNode::Var(var.to_string())])))),
                        "cos" => Some(AstNode::Func("sin".to_string(), vec![AstNode::Var(var.to_string())])),
                        "sinh" => Some(AstNode::Func("cosh".to_string(), vec![AstNode::Var(var.to_string())])),
                        "cosh" => Some(AstNode::Func("sinh".to_string(), vec![AstNode::Var(var.to_string())])),
                        _ => None,
                    }
                } else {
                    None
                }
            }
            AstNode::Add(a, b) => {
                let ia = a.integrate(var)?;
                let ib = b.integrate(var)?;
                Some(AstNode::Add(Box::new(ia), Box::new(ib)))
            }
            AstNode::Sub(a, b) => {
                let ia = a.integrate(var)?;
                let ib = b.integrate(var)?;
                Some(AstNode::Sub(Box::new(ia), Box::new(ib)))
            }
            AstNode::Mul(a, b) => {
                if let AstNode::Num(c) = **a {
                    let ib = b.integrate(var)?;
                    Some(AstNode::Mul(Box::new(AstNode::Num(c)), Box::new(ib)))
                } else {
                    None
                }
            }
            _ => None,
        }
    }

    pub fn simplify(&self) -> AstNode {
        match self {
            AstNode::Num(_) | AstNode::Var(_) => self.clone(),
            AstNode::Neg(a) => {
                let sa = a.simplify();
                if let AstNode::Neg(inner) = sa {
                    *inner
                } else if sa.is_zero() {
                    AstNode::Num(Value::from_int(0))
                } else if let AstNode::Num(v) = sa {
                    AstNode::Num(Value {
                        c: Complex {
                            re: -v.c.re,
                            im: -v.c.im,
                        },
                        rat: v.rat.map(|r| ExactRat {
                            num: -r.num,
                            den: r.den,
                        }),
                    })
                } else {
                    AstNode::Neg(Box::new(sa))
                }
            }
            AstNode::Add(a, b) => {
                let sa = a.simplify();
                let sb = b.simplify();
                if sa.is_zero() {
                    sb
                } else if sb.is_zero() {
                    sa
                } else {
                    AstNode::Add(Box::new(sa), Box::new(sb))
                }
            }
            AstNode::Sub(a, b) => {
                let sa = a.simplify();
                let sb = b.simplify();
                if sb.is_zero() {
                    sa
                } else if sa.is_zero() {
                    AstNode::Neg(Box::new(sb)).simplify()
                } else {
                    AstNode::Sub(Box::new(sa), Box::new(sb))
                }
            }
            AstNode::Mul(a, b) => {
                let sa = a.simplify();
                let sb = b.simplify();
                if sa.is_zero() || sb.is_zero() {
                    AstNode::Num(Value::from_int(0))
                } else if sa.is_one() {
                    sb
                } else if sb.is_one() {
                    sa
                } else if sa.is_minus_one() {
                    AstNode::Neg(Box::new(sb)).simplify()
                } else if sb.is_minus_one() {
                    AstNode::Neg(Box::new(sa)).simplify()
                } else if let AstNode::Neg(inner_b) = sb {
                    AstNode::Neg(Box::new(AstNode::Mul(Box::new(sa), inner_b).simplify()))
                } else if let AstNode::Neg(inner_a) = sa {
                    AstNode::Neg(Box::new(AstNode::Mul(inner_a, Box::new(sb)).simplify()))
                } else {
                    AstNode::Mul(Box::new(sa), Box::new(sb))
                }
            }
            AstNode::Div(a, b) => {
                let sa = a.simplify();
                let sb = b.simplify();
                if sa.is_zero() {
                    AstNode::Num(Value::from_int(0))
                } else if sb.is_one() {
                    sa
                } else if sa == sb {
                    AstNode::Num(Value::from_int(1))
                } else {
                    AstNode::Div(Box::new(sa), Box::new(sb))
                }
            }
            AstNode::Pow(base, exp) => {
                let sbase = base.simplify();
                let sexp = exp.simplify();
                if sexp.is_zero() {
                    AstNode::Num(Value::from_int(1))
                } else if sexp.is_one() {
                    sbase
                } else {
                    AstNode::Pow(Box::new(sbase), Box::new(sexp))
                }
            }
            AstNode::Func(name, args) => {
                let sargs: Vec<AstNode> = args.iter().map(|arg| arg.simplify()).collect();
                AstNode::Func(name.clone(), sargs)
            }
        }
    }

    fn prec(&self) -> u8 {
        match self {
            AstNode::Add(_, _) | AstNode::Sub(_, _) => 1,
            AstNode::Mul(_, _) | AstNode::Div(_, _) => 2,
            AstNode::Neg(_) => 3,
            AstNode::Num(v) => {
                if let Some(r) = v.rat {
                    if r.den != 1 {
                        return 2;
                    }
                    if r.num < 0 {
                        return 3;
                    }
                    4
                } else if v.c.re < 0.0 {
                    3
                } else {
                    4
                }
            }
            AstNode::Pow(_, _) => 3,
            AstNode::Var(_) | AstNode::Func(_, _) => 4,
        }
    }

    fn is_negative_display(&self) -> bool {
        match self {
            AstNode::Neg(_) => true,
            AstNode::Num(v) => {
                if let Some(r) = v.rat {
                    r.num < 0
                } else {
                    v.c.re < 0.0
                }
            }
            _ => false,
        }
    }

    pub fn to_display_string(&self) -> String {
        match self {
            AstNode::Num(v) => {
                if let Some(r) = v.rat {
                    if r.den == 1 {
                        return format!("{}", r.num);
                    } else {
                        return format!("{}/{}", r.num, r.den);
                    }
                }
                format!("{}", v.c.re)
            }
            AstNode::Var(v) => v.clone(),
            AstNode::Add(a, b) => {
                let sa = a.to_display_string();
                let sb = if b.is_negative_display() {
                    format!("({})", b.to_display_string())
                } else {
                    b.to_display_string()
                };
                format!("{}+{}", sa, sb)
            }
            AstNode::Sub(a, b) => {
                let sa = a.to_display_string();
                let sb = if b.prec() <= 1 || b.is_negative_display() {
                    format!("({})", b.to_display_string())
                } else {
                    b.to_display_string()
                };
                format!("{}-{}", sa, sb)
            }
            AstNode::Mul(a, b) => {
                let sa = if a.prec() < 2 {
                    format!("({})", a.to_display_string())
                } else {
                    a.to_display_string()
                };
                let sb = if b.prec() < 2 || b.is_negative_display() {
                    format!("({})", b.to_display_string())
                } else {
                    b.to_display_string()
                };
                format!("{}*{}", sa, sb)
            }
            AstNode::Div(a, b) => {
                let sa = if a.prec() < 2 {
                    format!("({})", a.to_display_string())
                } else {
                    a.to_display_string()
                };
                let sb = if b.prec() <= 2 || b.is_negative_display() {
                    format!("({})", b.to_display_string())
                } else {
                    b.to_display_string()
                };
                format!("{}/{}", sa, sb)
            }
            AstNode::Pow(base, exp) => {
                let sbase = if base.prec() < 4 || base.is_negative_display() {
                    format!("({})", base.to_display_string())
                } else {
                    base.to_display_string()
                };
                let sexp = if exp.prec() < 4 || exp.is_negative_display() {
                    format!("({})", exp.to_display_string())
                } else {
                    exp.to_display_string()
                };
                format!("{}^{}", sbase, sexp)
            }
            AstNode::Neg(a) => {
                let sa = if a.prec() < 2 || a.is_negative_display() {
                    format!("({})", a.to_display_string())
                } else {
                    a.to_display_string()
                };
                format!("-{}", sa)
            }
            AstNode::Func(name, args) => {
                let arg_strs: Vec<String> = args.iter().map(|a| a.to_display_string()).collect();
                format!("{}({})", name, arg_strs.join(", "))
            }
        }
    }
}

fn flatten_ast_terms(node: &AstNode, sign: i128, terms: &mut Vec<(i128, AstNode)>) {
    match node {
        AstNode::Add(a, b) => {
            flatten_ast_terms(a, sign, terms);
            flatten_ast_terms(b, sign, terms);
        }
        AstNode::Sub(a, b) => {
            flatten_ast_terms(a, sign, terms);
            flatten_ast_terms(b, -sign, terms);
        }
        _ => {
            terms.push((sign, node.clone()));
        }
    }
}

pub fn simplify_polynomial_ast(node: &AstNode) -> Option<AstNode> {
    let mut terms = Vec::new();
    flatten_ast_terms(node, 1, &mut terms);
    if terms.len() < 2 {
        return None;
    }

    let mut map: Vec<(AstNode, i128)> = Vec::new();
    for (sign, t) in terms {
        let (coeff, factor) = match t {
            AstNode::Mul(box_left, box_right) => {
                if let AstNode::Num(v) = *box_left {
                    if let Some(r) = v.rat {
                        if r.den == 1 {
                            (r.num * sign, *box_right)
                        } else {
                            (sign, AstNode::Mul(box_left, box_right))
                        }
                    } else {
                        (sign, AstNode::Mul(box_left, box_right))
                    }
                } else {
                    (sign, AstNode::Mul(box_left, box_right))
                }
            }
            _ => (sign, t),
        };

        if let Some(entry) = map.iter_mut().find(|(existing, _)| existing == &factor) {
            entry.1 += coeff;
        } else {
            map.push((factor, coeff));
        }
    }

    let mut result_ast: Option<AstNode> = None;
    for (factor, coeff) in map {
        if coeff == 0 {
            continue;
        }
        let term_ast = if coeff.abs() == 1 {
            factor
        } else {
            AstNode::Mul(
                Box::new(AstNode::Num(Value::from_int(coeff.abs()))),
                Box::new(factor),
            )
        };

        result_ast = match result_ast {
            None => {
                if coeff < 0 {
                    Some(AstNode::Neg(Box::new(term_ast)))
                } else {
                    Some(term_ast)
                }
            }
            Some(accum) => {
                if coeff > 0 {
                    Some(AstNode::Add(Box::new(accum), Box::new(term_ast)))
                } else {
                    Some(AstNode::Sub(Box::new(accum), Box::new(term_ast)))
                }
            }
        };
    }

    result_ast
}

pub struct AstParser {
    chars: Vec<char>,
    pos: usize,
}

impl AstParser {
    pub fn parse(input: &str) -> Result<AstNode, String> {
        let normalized = input
            .replace('×', "*")
            .replace('·', "*")
            .replace('÷', "/")
            .replace('−', "-")
            .replace("²", "^2")
            .replace("³", "^3")
            .replace("⁻¹", "^(-1)");
        let mut p = AstParser {
            chars: normalized.chars().collect(),
            pos: 0,
        };
        p.skip_ws();
        let node = p.parse_expr()?;
        p.skip_ws();
        if p.pos < p.chars.len() {
            return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
        }
        Ok(node)
    }

    fn skip_ws(&mut self) {
        while self.pos < self.chars.len() && self.chars[self.pos].is_whitespace() {
            self.pos += 1;
        }
    }

    fn peek(&self) -> Option<char> {
        self.chars.get(self.pos).copied()
    }

    fn eat(&mut self, c: char) -> bool {
        self.skip_ws();
        if self.peek() == Some(c) {
            self.pos += 1;
            true
        } else {
            false
        }
    }

    fn parse_expr(&mut self) -> Result<AstNode, String> {
        let mut left = self.parse_term()?;
        loop {
            self.skip_ws();
            if self.eat('+') {
                let right = self.parse_term()?;
                left = AstNode::Add(Box::new(left), Box::new(right));
            } else if self.eat('-') {
                let right = self.parse_term()?;
                left = AstNode::Sub(Box::new(left), Box::new(right));
            } else {
                break;
            }
        }
        Ok(left)
    }

    fn parse_term(&mut self) -> Result<AstNode, String> {
        let mut left = self.parse_unary()?;
        loop {
            self.skip_ws();
            if self.eat('*') {
                let right = self.parse_unary()?;
                left = AstNode::Mul(Box::new(left), Box::new(right));
            } else if self.eat('/') {
                let right = self.parse_unary()?;
                left = AstNode::Div(Box::new(left), Box::new(right));
            } else if let Some(ch) = self.peek() {
                if ch == '(' || ch == '√' || ch.is_alphabetic() || ch == '_' {
                    // Implicit multiplication
                    let right = self.parse_power()?;
                    left = AstNode::Mul(Box::new(left), Box::new(right));
                } else {
                    break;
                }
            } else {
                break;
            }
        }
        Ok(left)
    }

    fn parse_unary(&mut self) -> Result<AstNode, String> {
        self.skip_ws();
        if self.eat('+') {
            self.parse_unary()
        } else if self.eat('-') {
            let u = self.parse_unary()?;
            Ok(AstNode::Neg(Box::new(u)))
        } else if self.eat('√') {
            let u = self.parse_unary()?;
            Ok(AstNode::Func("sqrt".to_string(), vec![u]))
        } else {
            self.parse_power()
        }
    }

    fn parse_power(&mut self) -> Result<AstNode, String> {
        let mut base = self.parse_postfix()?;
        self.skip_ws();
        if self.eat('^') {
            let exp = self.parse_unary()?;
            base = AstNode::Pow(Box::new(base), Box::new(exp));
        }
        Ok(base)
    }

    fn parse_postfix(&mut self) -> Result<AstNode, String> {
        let mut node = self.parse_primary()?;
        loop {
            self.skip_ws();
            if self.eat('!') {
                node = AstNode::Func("factorial".to_string(), vec![node]);
            } else if self.eat('%') {
                node = AstNode::Mul(
                    Box::new(node),
                    Box::new(AstNode::Num(Value::from_rat(1, 100).unwrap())),
                );
            } else {
                break;
            }
        }
        Ok(node)
    }

    fn parse_primary(&mut self) -> Result<AstNode, String> {
        self.skip_ws();
        let ch = match self.peek() {
            Some(c) => c,
            None => return Err("Unerwartetes Zeilenende".to_string()),
        };

        if self.eat('(') {
            let inner = self.parse_expr()?;
            if !self.eat(')') {
                return Err("Fehlende schließende Klammer ')'".to_string());
            }
            return Ok(inner);
        }

        if self.eat('%') {
            return Ok(AstNode::Num(Value::from_rat(1, 100).unwrap()));
        }

        // Hex number 0x...
        if ch == '0' && self.pos + 1 < self.chars.len() && (self.chars[self.pos + 1] == 'x' || self.chars[self.pos + 1] == 'X') {
            self.pos += 2;
            let start = self.pos;
            while self.pos < self.chars.len() && self.chars[self.pos].is_ascii_hexdigit() {
                self.pos += 1;
            }
            let s: String = self.chars[start..self.pos].iter().collect();
            let n = i128::from_str_radix(&s, 16).map_err(|e| e.to_string())?;
            return Ok(AstNode::Num(Value::from_int(n)));
        }

        // Decimal or Integer
        if ch.is_ascii_digit() || ch == '.' {
            let start = self.pos;
            let mut has_dot = false;
            let mut has_exp = false;
            while self.pos < self.chars.len() {
                let c = self.chars[self.pos];
                if c == '.' {
                    has_dot = true;
                    self.pos += 1;
                } else if c == 'e' || c == 'E' {
                    has_exp = true;
                    self.pos += 1;
                    if self.pos < self.chars.len() && (self.chars[self.pos] == '+' || self.chars[self.pos] == '-') {
                        self.pos += 1;
                    }
                } else if c.is_ascii_digit() {
                    self.pos += 1;
                } else {
                    break;
                }
            }
            let s: String = self.chars[start..self.pos].iter().collect();
            if !has_dot && !has_exp {
                if let Ok(n) = s.parse::<i128>() {
                    return Ok(AstNode::Num(Value::from_int(n)));
                }
            }
            let f = s.parse::<f64>().map_err(|e| e.to_string())?;
            return Ok(AstNode::Num(Value::from_f64(f)));
        }

        // Identifier or Function call
        if ch.is_alphabetic() || ch == '_' {
            let start = self.pos;
            while self.pos < self.chars.len() && (self.chars[self.pos].is_alphanumeric() || self.chars[self.pos] == '_') {
                self.pos += 1;
            }
            let ident: String = self.chars[start..self.pos].iter().collect();

            self.skip_ws();
            if self.eat('(') {
                let mut args = Vec::new();
                self.skip_ws();
                if !self.eat(')') {
                    loop {
                        let arg = self.parse_expr()?;
                        args.push(arg);
                        self.skip_ws();
                        if self.eat(',') {
                            continue;
                        } else if self.eat(')') {
                            break;
                        } else {
                            return Err("Erwarte ',' oder ')' in Funktionsaufruf".to_string());
                        }
                    }
                }
                return Ok(AstNode::Func(ident, args));
            } else {
                return Ok(AstNode::Var(ident));
            }
        }

        Err(format!("Unerwartetes Zeichen '{}'", ch))
    }
}

struct ExprParser<'a> {
    chars: Vec<char>,
    pos: usize,
    engine: &'a MathEngine,
    overrides: Option<&'a HashMap<String, Value>>,
}

impl<'a> ExprParser<'a> {
    fn new(
        input: &str,
        engine: &'a MathEngine,
        overrides: Option<&'a HashMap<String, Value>>,
    ) -> Self {
        let normalized = input
            .replace('×', "*")
            .replace('·', "*")
            .replace('÷', "/")
            .replace('−', "-")
            .replace("²", "^2")
            .replace("³", "^3")
            .replace("⁻¹", "^(-1)");
        Self {
            chars: normalized.chars().collect(),
            pos: 0,
            engine,
            overrides,
        }
    }

    fn skip_ws(&mut self) {
        while self.pos < self.chars.len() && self.chars[self.pos].is_whitespace() {
            self.pos += 1;
        }
    }

    fn peek(&mut self) -> Option<char> {
        self.skip_ws();
        self.chars.get(self.pos).copied()
    }

    fn parse_expr(&mut self) -> Result<Value, String> {
        let mut left = self.parse_term()?;
        loop {
            match self.peek() {
                Some('+') => {
                    self.pos += 1;
                    let right = self.parse_term()?;
                    left = left.add(right);
                }
                Some('-') => {
                    self.pos += 1;
                    let right = self.parse_term()?;
                    left = left.sub(right);
                }
                _ => break,
            }
        }
        Ok(left)
    }

    fn parse_term(&mut self) -> Result<Value, String> {
        let mut left = self.parse_unary()?;
        loop {
            self.skip_ws();
            if self.pos + 3 <= self.chars.len() {
                let slice: String = self.chars[self.pos..self.pos + 3].iter().collect();
                if slice == "mod"
                    && (self.pos + 3 == self.chars.len()
                        || !self.chars[self.pos + 3].is_alphanumeric())
                {
                    self.pos += 3;
                    let right = self.parse_unary()?;
                    left = self.apply_modulo_val(left, right)?;
                    continue;
                }
            }
            match self.peek() {
                Some('*') => {
                    self.pos += 1;
                    let right = self.parse_unary()?;
                    left = left.mul(right);
                }
                Some('/') => {
                    self.pos += 1;
                    let right = self.parse_unary()?;
                    left = left.div(right)?;
                }
                // Implicit multiplication: `2x`, `2(3+4)`, `(1+2)(3+4)`, `2π`, `2√2`
                Some(c)
                    if c == '('
                        || c == '√'
                        || c.is_alphabetic()
                        || c == 'π'
                        || c == 'Φ'
                        || c == 'μ'
                        || c == 'ε'
                        || c == 'α'
                        || c == 'σ' =>
                {
                    let right = self.parse_power()?;
                    left = left.mul(right);
                }
                _ => break,
            }
        }
        Ok(left)
    }

    fn parse_unary(&mut self) -> Result<Value, String> {
        match self.peek() {
            Some('+') => {
                self.pos += 1;
                self.parse_unary()
            }
            Some('-') => {
                self.pos += 1;
                let v = self.parse_unary()?;
                Ok(Value {
                    c: Complex {
                        re: -v.c.re,
                        im: -v.c.im,
                    },
                    rat: v.rat.map(|r| ExactRat { num: -r.num, den: r.den }),
                })
            }
            Some('√') => {
                self.pos += 1;
                let v = self.parse_unary()?;
                Ok(v.sqrt())
            }
            _ => self.parse_power(),
        }
    }

    fn parse_power(&mut self) -> Result<Value, String> {
        let mut base = self.parse_postfix()?;
        if self.peek() == Some('^') {
            self.pos += 1;
            let exp = self.parse_unary()?;
            base = base.pow(exp);
        }
        Ok(base)
    }

    fn parse_postfix(&mut self) -> Result<Value, String> {
        let mut val = self.parse_primary()?;
        loop {
            match self.peek() {
                Some('!') => {
                    self.pos += 1;
                    if !val.c.is_real() || val.c.re < 0.0 || (val.c.re - val.c.re.round()).abs() > 1e-11 {
                        return Err("Fakultät ist nur für nicht-negative ganze Zahlen definiert".to_string());
                    }
                    if val.c.re > 170.0 {
                        return Err("Fakultät übersteigt Rechenbereich".to_string());
                    }
                    val = Value::from_f64(factorial_or_gamma(val.c.re));
                }
                Some('%') => {
                    self.pos += 1;
                    val = val.mul(Value::from_rat(1, 100).unwrap());
                }
                _ => break,
            }
        }
        Ok(val)
    }

    fn parse_primary(&mut self) -> Result<Value, String> {
        self.skip_ws();
        let ch = self
            .peek()
            .ok_or_else(|| "Unerwartetes Ende des Ausdrucks".to_string())?;

        if ch == '%' {
            self.pos += 1;
            return Ok(Value::from_rat(1, 100).unwrap());
        }

        if ch == '(' {
            self.pos += 1;
            let v = self.parse_expr()?;
            if self.peek() == Some(')') {
                self.pos += 1;
            }
            return Ok(v);
        }

        // Hex number `0x...` or number literal
        if ch.is_ascii_digit() || ch == '.' {
            if ch == '0'
                && self.pos + 2 < self.chars.len()
                && (self.chars[self.pos + 1] == 'x' || self.chars[self.pos + 1] == 'X')
            {
                self.pos += 2;
                let start = self.pos;
                while self.pos < self.chars.len() && self.chars[self.pos].is_ascii_hexdigit() {
                    self.pos += 1;
                }
                let hex_s: String = self.chars[start..self.pos].iter().collect();
                let v = u64::from_str_radix(&hex_s, 16)
                    .map_err(|_| format!("Ungültige Hex-Zahl 0x{}", hex_s))?;
                return Ok(Value::from_int(v as i128));
            }

            let start = self.pos;
            let mut has_dot = false;
            let mut has_exp = false;
            while self.pos < self.chars.len()
                && (self.chars[self.pos].is_ascii_digit() || self.chars[self.pos] == '.')
            {
                if self.chars[self.pos] == '.' {
                    has_dot = true;
                }
                self.pos += 1;
            }
            if self.pos < self.chars.len()
                && (self.chars[self.pos] == 'e' || self.chars[self.pos] == 'E')
                && self.pos + 1 < self.chars.len()
                && (self.chars[self.pos + 1].is_ascii_digit()
                    || self.chars[self.pos + 1] == '+'
                    || self.chars[self.pos + 1] == '-')
            {
                has_exp = true;
                self.pos += 1;
                if self.chars[self.pos] == '+' || self.chars[self.pos] == '-' {
                    self.pos += 1;
                }
                while self.pos < self.chars.len() && self.chars[self.pos].is_ascii_digit() {
                    self.pos += 1;
                }
            }
            let num_s: String = self.chars[start..self.pos].iter().collect();
            if !has_dot && !has_exp {
                if let Ok(int_val) = num_s.parse::<i128>() {
                    return Ok(Value::from_int(int_val));
                }
            }
            let v: f64 = num_s
                .parse()
                .map_err(|_| format!("Ungültige Zahl '{}'", num_s))?;
            return Ok(Value::from_f64(v));
        }

        // Identifier, Constant, or Function call
        let start = self.pos;
        while self.pos < self.chars.len()
            && (self.chars[self.pos].is_alphanumeric()
                || matches!(
                    self.chars[self.pos],
                    '_' | '₀' | '₁' | '₂' | '∞' | '⁻' | 'π' | 'μ' | 'ε' | 'α' | 'Φ' | 'σ'
                ))
        {
            self.pos += 1;
        }
        if self.pos == start {
            return Err(format!("Unerwartetes Symbol '{}'", ch));
        }
        let ident: String = self.chars[start..self.pos].iter().collect();

        // Check if followed by '(' -> Function call
        self.skip_ws();
        if self.peek() == Some('(') {
            self.pos += 1; // consume '('
            return self.eval_function(&ident);
        }

        // Otherwise resolve variable or constant
        self.resolve_identifier(&ident)
    }

    fn read_raw_args(&mut self) -> Result<Vec<String>, String> {
        let mut args = Vec::new();
        let mut cur = String::new();
        let mut depth = 0i32;
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            self.pos += 1;
            match c {
                '(' | '[' => {
                    depth += 1;
                    cur.push(c);
                }
                ')' if depth == 0 => {
                    if !cur.trim().is_empty() {
                        args.push(cur.trim().to_string());
                    }
                    return Ok(args);
                }
                ')' | ']' => {
                    depth -= 1;
                    cur.push(c);
                }
                ',' if depth == 0 => {
                    args.push(cur.trim().to_string());
                    cur.clear();
                }
                _ => cur.push(c),
            }
        }
        if !cur.trim().is_empty() {
            args.push(cur.trim().to_string());
        }
        Ok(args)
    }

    fn eval_arg(&self, s: &str, local_ov: Option<&HashMap<String, Value>>) -> Result<Value, String> {
        let mut p = ExprParser::new(s, self.engine, local_ov.or(self.overrides));
        p.parse_expr()
    }

    fn eval_function(&mut self, name: &str) -> Result<Value, String> {
        let raw_args = self.read_raw_args()?;
        let mode = self.engine.angle_mode;

        // Higher-order calculus functions (`sum`, `prod`, `int`, `deriv`)
        match name {
            "sum" | "∑" => {
                if raw_args.len() != 4 {
                    return Err("sum(Ausdruck, Variable, Start, Ende) erwartet 4 Argumente".to_string());
                }
                let var = raw_args[1].trim();
                let a = self.eval_arg(&raw_args[2], None)?.c.re.round() as i64;
                let b = self.eval_arg(&raw_args[3], None)?.c.re.round() as i64;
                if b - a > 100_000 {
                    return Err("Summationsbereich zu groß (max. 100 000 Schritte)".to_string());
                }
                let mut acc = Value::from_int(0);
                let mut ov = self.overrides.cloned().unwrap_or_default();
                for k in a..=b {
                    ov.insert(var.to_string(), Value::from_int(k as i128));
                    acc = acc.add(self.eval_arg(&raw_args[0], Some(&ov))?);
                }
                return Ok(acc);
            }
            "prod" | "product" | "∏" => {
                if raw_args.len() != 4 {
                    return Err("prod(Ausdruck, Variable, Start, Ende) erwartet 4 Argumente".to_string());
                }
                let var = raw_args[1].trim();
                let a = self.eval_arg(&raw_args[2], None)?.c.re.round() as i64;
                let b = self.eval_arg(&raw_args[3], None)?.c.re.round() as i64;
                if b - a > 100_000 {
                    return Err("Produktbereich zu groß".to_string());
                }
                let mut acc = Value::from_int(1);
                let mut ov = self.overrides.cloned().unwrap_or_default();
                for k in a..=b {
                    ov.insert(var.to_string(), Value::from_int(k as i128));
                    acc = acc.mul(self.eval_arg(&raw_args[0], Some(&ov))?);
                }
                return Ok(acc);
            }
            "int" | "defint" | "∫" => {
                if raw_args.len() == 2 {
                    return Err("Symbolic integral handled in preview".to_string());
                }
                if raw_args.len() != 4 {
                    return Err("int(Ausdruck, x, a, b) erwartet 4 Argumente".to_string());
                }
                let var = raw_args[1].trim().to_string();
                let a_val = self.eval_arg(&raw_args[2], None)?;
                let b_val = self.eval_arg(&raw_args[3], None)?;
                if !a_val.c.is_real() || !b_val.c.is_real() || !a_val.c.re.is_finite() || !b_val.c.re.is_finite() {
                    return Err("Integrationsgrenzen müssen endliche reelle Zahlen sein".to_string());
                }
                let a = a_val.c.re;
                let b = b_val.c.re;
                let base_ov = self.overrides.cloned().unwrap_or_default();
                let expr_str = raw_args[0].clone();
                let engine_ref = self.engine;

                let report = adaptive_gauss_integrate(
                    |x| {
                        let mut local = base_ov.clone();
                        local.insert(var.clone(), Value::from_f64(x));
                        let mut p = ExprParser::new(&expr_str, engine_ref, Some(&local));
                        let v = p.parse_expr()?;
                        p.skip_ws();
                        if p.pos < p.chars.len() {
                            return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
                        }
                        if !v.c.is_real() || !v.c.re.is_finite() {
                            return Err("Integrand liefert nicht-reellen oder unendlichen Wert".to_string());
                        }
                        Ok(v.c.re)
                    },
                    a,
                    b,
                    2500,
                    1e-10,
                    1e-8,
                )?;
                engine_ref.last_integration_report.replace(Some(report.clone()));
                return Ok(Value::from_f64(report.value));
            }
            "deriv" | "diff" | "d" => {
                if raw_args.len() < 2 || raw_args.len() > 3 {
                    return Err("deriv(Ausdruck, x, [x0]) erwartet 2 oder 3 Argumente".to_string());
                }
                if raw_args.len() == 2 {
                    return Err("Symbolic derivative handled in preview".to_string());
                }
                let var = raw_args[1].trim();
                let x0 = self.eval_arg(&raw_args[2], None)?.c.re;
                let h = if x0.abs() > 1e10 {
                    1e-3 * x0.abs()
                } else if x0.abs() > 1e4 {
                    1e-3
                } else {
                    1e-4
                };
                let mut ov = self.overrides.cloned().unwrap_or_default();
                ov.insert(var.to_string(), Value::from_f64(x0 + 2.0 * h));
                let f_p2 = self.eval_arg(&raw_args[0], Some(&ov))?.c.re;
                ov.insert(var.to_string(), Value::from_f64(x0 + h));
                let f_p1 = self.eval_arg(&raw_args[0], Some(&ov))?.c.re;
                ov.insert(var.to_string(), Value::from_f64(x0 - h));
                let f_m1 = self.eval_arg(&raw_args[0], Some(&ov))?.c.re;
                ov.insert(var.to_string(), Value::from_f64(x0 - 2.0 * h));
                let f_m2 = self.eval_arg(&raw_args[0], Some(&ov))?.c.re;
                let deriv_val = (-f_p2 + 8.0 * f_p1 - 8.0 * f_m1 + f_m2) / (12.0 * h);
                return Ok(Value::from_f64(deriv_val));
            }
            _ => {}
        }

        // Standard numeric/complex functions
        let mut vals = Vec::with_capacity(raw_args.len());
        for a in &raw_args {
            vals.push(self.eval_arg(a, None)?);
        }

        if name == "ans" {
            return Ok(self.engine.ans);
        }

        let v0 = vals
            .first()
            .copied()
            .ok_or_else(|| format!("Funktion '{}' benötigt mindestens 1 Argument", name))?;

        match name {
            "sin" => {
                if v0.c.is_real() {
                    Ok(Value::from_f64(mode.to_radians(v0.c.re).sin()))
                } else {
                    Ok(Value::from_complex(v0.c.sin()))
                }
            }
            "cos" => {
                if v0.c.is_real() {
                    Ok(Value::from_f64(mode.to_radians(v0.c.re).cos()))
                } else {
                    Ok(Value::from_complex(v0.c.cos()))
                }
            }
            "tan" => {
                if v0.c.is_real() {
                    Ok(Value::from_f64(mode.to_radians(v0.c.re).tan()))
                } else {
                    Ok(Value::from_complex(v0.c.tan()))
                }
            }
            "asin" | "arcsin" => Ok(Value::from_f64(mode.from_radians(v0.c.re.asin()))),
            "acos" | "arccos" => Ok(Value::from_f64(mode.from_radians(v0.c.re.acos()))),
            "atan" | "arctan" => Ok(Value::from_f64(mode.from_radians(v0.c.re.atan()))),
            "sinh" => {
                if v0.c.is_real() {
                    Ok(Value::from_f64(v0.c.re.sinh()))
                } else {
                    Ok(Value::from_complex(v0.c.sinh()))
                }
            }
            "cosh" => {
                if v0.c.is_real() {
                    Ok(Value::from_f64(v0.c.re.cosh()))
                } else {
                    Ok(Value::from_complex(v0.c.cosh()))
                }
            }
            "tanh" => {
                if v0.c.is_real() {
                    Ok(Value::from_f64(v0.c.re.tanh()))
                } else {
                    Ok(Value::from_complex(v0.c.tanh()))
                }
            }
            "asinh" | "arcsinh" => Ok(Value::from_f64(v0.c.re.asinh())),
            "acosh" | "arccosh" => Ok(Value::from_f64(v0.c.re.acosh())),
            "atanh" | "arctanh" => Ok(Value::from_f64(v0.c.re.atanh())),
            "sqrt" => Ok(v0.sqrt()),
            "fraction" | "division" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "fraction(a, b) benötigt 2 Argumente".to_string())?;
                v0.div(v1)
            }
            "multiplication" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "multiplication(a, b) benötigt 2 Argumente".to_string())?;
                Ok(v0.mul(v1))
            }
            "addition" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "addition(a, b) benötigt 2 Argumente".to_string())?;
                Ok(v0.add(v1))
            }
            "subtraction" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "subtraction(a, b) benötigt 2 Argumente".to_string())?;
                Ok(v0.sub(v1))
            }
            "nthroot" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "nthroot(n, x) benötigt 2 Argumente".to_string())?;
                if !v0.c.is_real() || !v0.c.re.is_finite() || v0.c.re == 0.0 {
                    return Err("Wurzelindex muss eine reelle Zahl ungleich null sein".to_string());
                }
                let n = v0.c.re;
                if v1.c.is_real() && v1.c.re < 0.0 && (n % 2.0).abs() == 1.0 {
                    return Ok(Value::from_f64(-(-v1.c.re).powf(1.0 / n)));
                }
                Ok(v1.pow(Value::from_f64(1.0 / v0.c.re)))
            }
            "power" | "pow" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "power(x, n) benötigt 2 Argumente".to_string())?;
                Ok(v0.pow(v1))
            }
            "ln" => Ok(Value::from_complex(v0.c.ln())),
            "log10" | "lg" => Ok(Value::from_f64(v0.c.re.log10())),
            "log" => {
                if vals.len() == 2 {
                    Ok(Value::from_f64(vals[1].c.re.ln() / v0.c.re.ln()))
                } else {
                    Ok(Value::from_f64(v0.c.re.log10()))
                }
            }
            "exp" => Ok(Value::from_complex(v0.c.exp())),
            "abs" => Ok(Value::from_f64(v0.c.abs())),
            "arg" => Ok(Value::from_f64(mode.from_radians(v0.c.arg()))),
            "re" => Ok(Value::from_f64(v0.c.re)),
            "im" => Ok(Value::from_f64(v0.c.im)),
            "binomial" | "nCr" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "binomial(n, k) benötigt 2 Argumente".to_string())?;
                Ok(Value::from_f64(binomial_coeff(v0.c.re, v1.c.re)))
            }
            "mod" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "mod(a, b) benötigt 2 Argumente".to_string())?;
                self.apply_modulo_val(v0, v1)
            }
            "gcd" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "gcd(a, b) benötigt 2 Argumente".to_string())?;
                Ok(Value::from_int(gcd_i128(v0.c.re.round() as i128, v1.c.re.round() as i128)))
            }
            "lcm" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "lcm(a, b) benötigt 2 Argumente".to_string())?;
                let a = v0.c.re.round() as i128;
                let b = v1.c.re.round() as i128;
                let g = gcd_i128(a, b).max(1);
                Ok(Value::from_int(((a / g) * b).abs()))
            }
            _ => Err(format!("Unbekannte Funktion '{}'", name)),
        }
    }

    fn resolve_identifier(&self, ident: &str) -> Result<Value, String> {
        if let Some(ov) = self.overrides {
            if let Some(&v) = ov.get(ident) {
                return Ok(v);
            }
        }
        match ident {
            "i" => return Ok(Value::from_complex(Complex::i())),
            "π" | "pi" => return Ok(Value::from_f64(std::f64::consts::PI)),
            "e" => return Ok(Value::from_f64(std::f64::consts::E)),
            "ans" | "ANS" => return Ok(self.engine.ans),
            _ => {}
        }
        if let Some(&v) = self.engine.variables.get(ident) {
            return Ok(v);
        }
        for c in SCIENTIFIC_CONSTANTS {
            if c.token == ident || c.symbol == ident || c.key == ident {
                return Ok(Value::from_f64(c.value));
            }
        }
        Err(format!("Unbekannte Variable oder Konstante '{}'", ident))
    }

    fn apply_modulo_val(&self, left: Value, right: Value) -> Result<Value, String> {
        if !left.c.is_real() || !right.c.is_real() || !left.c.re.is_finite() || !right.c.re.is_finite() {
            return Err("Modulo erfordert endliche reelle Zahlen".to_string());
        }
        if let (Some(l_rat), Some(r_rat)) = (left.rat, right.rat) {
            if l_rat.den == 1 && r_rat.den == 1 {
                if r_rat.num == 0 {
                    return Err("Modulo durch Null".to_string());
                }
                let mut rem = l_rat.num % r_rat.num;
                if self.engine.modulo_always_positive && rem < 0 {
                    rem += r_rat.num.abs();
                }
                return Ok(Value::from_int(rem));
            }
        }
        if right.c.re == 0.0 {
            return Err("Modulo durch Null".to_string());
        }
        let mut r = left.c.re % right.c.re;
        if self.engine.modulo_always_positive && r < 0.0 {
            r += right.c.re.abs();
        }
        Ok(Value::from_f64(r))
    }
}

fn factorial_or_gamma(n: f64) -> f64 {
    if n >= 0.0 && (n - n.round()).abs() < 1e-11 && n <= 170.0 {
        let k = n.round() as u64;
        let mut p = 1.0_f64;
        for i in 2..=k {
            p *= i as f64;
        }
        p
    } else {
        lanczos_gamma(n + 1.0)
    }
}

fn lanczos_gamma(z: f64) -> f64 {
    const P: [f64; 8] = [
        676.5203681218851,
        -1259.1392167224028,
        771.3234287776531,
        -176.6150291621406,
        12.507343278686905,
        -0.13857109526572012,
        9.984369578019572e-6,
        1.5056327351493116e-7,
    ];
    if z < 0.5 {
        return std::f64::consts::PI / ((std::f64::consts::PI * z).sin() * lanczos_gamma(1.0 - z));
    }
    let z = z - 1.0;
    let mut x = 0.9999999999998099;
    for (i, &p) in P.iter().enumerate() {
        x += p / (z + (i as f64) + 1.0);
    }
    let t = z + 7.5;
    (2.0 * std::f64::consts::PI).sqrt() * t.powf(z + 0.5) * (-t).exp() * x
}

fn binomial_coeff(n: f64, k: f64) -> f64 {
    if k < 0.0 || k > n {
        return 0.0;
    }
    let n_u = n.round() as u64;
    let k_u = k.round() as u64;
    let k_u = k_u.min(n_u - k_u);
    let mut res = 1.0_f64;
    for i in 1..=k_u {
        res = res * ((n_u - k_u + i) as f64) / (i as f64);
    }
    res
}
