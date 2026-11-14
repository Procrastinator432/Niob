//! 100% Offline Symbolic, Rational, Complex, Matrix & Calculus Evaluation Engine
//! Implements the mathematical capabilities of Niob Scientific Calculator
//! (`Lc/a/a/e/d;`, `Lc/a/a/e/e;`, `Lc/a/a/e/f;`, `Lc/a/a/e/g;`, `Lc/a/a/e/h;`).

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, HashMap};
use std::fmt;
use std::ops::Deref;

thread_local! {
    static INTEGRATION_DEPTH: Cell<usize> = const { Cell::new(0) };
}
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

        let int_digits = if abs_v < 1.0 {
            1
        } else {
            (abs_v.log10().floor() as usize) + 1
        };
        let max_decimals = 15usize.saturating_sub(int_digits).min(10);
        let raw = trim_float(val, max_decimals);
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
    } else if s == "-0" {
        "0".to_string()
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
    pub fn new(re: f64, im: f64) -> Self {
        Self {
            re: if re == 0.0 { 0.0 } else { re },
            im: if im == 0.0 { 0.0 } else { im },
        }
    }

    pub fn real(re: f64) -> Self {
        Self::new(re, 0.0)
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
        let im = if self.im.abs() == 0.0 { 0.0 } else { self.im };
        let re = if self.re == 0.0 { 0.0 } else { self.re };
        im.atan2(re)
    }

    pub fn add(self, o: Self) -> Self {
        Self::new(self.re + o.re, self.im + o.im)
    }

    pub fn sub(self, o: Self) -> Self {
        Self::new(self.re - o.re, self.im - o.im)
    }

    pub fn mul(self, o: Self) -> Self {
        Self::new(self.re * o.re - self.im * o.im, self.re * o.im + self.im * o.re)
    }

    pub fn div(self, o: Self) -> Self {
        let denom = o.re * o.re + o.im * o.im;
        Self::new(
            (self.re * o.re + self.im * o.im) / denom,
            (self.im * o.re - self.re * o.im) / denom,
        )
    }

    pub fn exp(self) -> Self {
        let r = self.re.exp();
        Self::new(r * self.im.cos(), r * self.im.sin())
    }

    pub fn ln(self) -> Self {
        Self::new(self.abs().ln(), self.arg())
    }

    pub fn pow(self, exp: Self) -> Self {
        if self.is_real() && exp.is_real() {
            if self.re >= 0.0 {
                return Self::real(self.re.powf(exp.re));
            }
            if (exp.re - exp.re.round()).abs() == 0.0 {
                return Self::real(self.re.powf(exp.re));
            }
        }
        if self.abs() == 0.0 {
            return if exp.re < 0.0 { Self::real(f64::NAN) } else { Self::real(0.0) };
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
            if er.den == 1 && er.num >= -126 && er.num <= 126 {
                if er.num < 0 && r.num == 0 {
                    return Self::from_f64(f64::NAN);
                }
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
    matrix_expression: Option<String>,
}

impl EvalOutput {
    pub fn reusable_expression(&self) -> String {
        self.val.map(Value::reusable_expression).unwrap_or_else(|| {
            self.matrix_expression.as_ref().unwrap_or(&self.exact_text).clone()
        })
    }
}

impl Value {
    fn reusable_expression(self) -> String {
        if let Some(rat) = self.rat {
            if rat.den != 1 {
                return format!("({}/{})", rat.num, rat.den);
            }
            return if rat.num < 0 {
                format!("({})", rat.num)
            } else {
                rat.num.to_string()
            };
        }
        let Complex { re, im } = self.c;
        if im != 0.0 {
            let sign = if im < 0.0 { '-' } else { '+' };
            return format!("({re:e}{sign}{:e}*i)", im.abs());
        }
        if re < 0.0 { format!("({re:e})") } else { format!("{re:e}") }
    }
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
                    matrix_expression: None,
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
                    matrix_expression: None,
                });
            } else {
                return Err("Zuweisung erfordert numerischen Wert".to_string());
            }
        }

        // Parse expression into AST for structural symbolic evaluation and general matrix expressions
        let parsed_ast = AstParser::parse(trimmed).ok();

        // Check Matrix expressions: det([[...]]), inv([[...]]), trans([[...]]), transpose([[...]]), [[...]], or matrix products/sums
        if trimmed.starts_with("det(")
            || trimmed.starts_with("inv(")
            || trimmed.starts_with("trans(")
            || trimmed.starts_with("transpose(")
            || trimmed.starts_with("[[")
            || parsed_ast.as_ref().is_some_and(contains_matrix_expression)
        {
            return self.evaluate_matrix_expression(trimmed);
        }

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
                    matrix_expression: None,
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
            AstNode::Func(name, args) if (name == "integral" || name == "int") && args.len() == 2 => {
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
                            matrix_expression: None,
                        });
                    }
                }
            }
            AstNode::Func(name, args) if (name == "d" || name == "deriv" || name == "diff") && args.len() == 2 => {
                if let AstNode::Var(var) = &args[1] {
                    if let Some(diff_ast) = args[0].diff_with_mode(var, self.angle_mode) {
                        let res_ast = diff_ast.simplify();
                        let res = res_ast.to_display_string();
                        return Some(EvalOutput {
                            exact_text: res.clone(),
                            decimal_text: res,
                            has_distinct_exact: true,
                            numeric_value: None,
                            val: None,
                            integration_report: None,
                            matrix_expression: None,
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
                matrix_expression: None,
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

    fn try_format_exact_val(&self, expr: &str, val: Value) -> Option<String> {
        if let Some(r) = val.rat {
            if r.den > 1 {
                return Some(format!("{} / {}", r.num, r.den));
            }
            if r.den == 1 {
                return None; // Integer
            }
        }
        if !val.c.is_real() {
            return self.try_format_exact(expr, val.c);
        }
        if let Ok(ast) = AstParser::parse(expr) {
            return self.try_exact_from_ast(&ast);
        }
        None
    }

    fn try_format_exact(&self, _expr: &str, c: Complex) -> Option<String> {
        if !c.is_real() {
            let re_ex = self.number_format.format_f64(c.re);
            let im_ex = self.number_format.format_f64(c.im.abs());
            if c.re.abs() < 1e-12 {
                return Some(if (c.im - 1.0).abs() < 1e-12 {
                    "i".to_string()
                } else if (c.im + 1.0).abs() < 1e-12 {
                    "-i".to_string()
                } else {
                    format!("{} · i", self.number_format.format_f64(c.im))
                });
            }
            let sign = if c.im >= 0.0 { "+" } else { "-" };
            return Some(format!("{} {} {} · i", re_ex, sign, im_ex));
        }
        None
    }

    fn try_exact_from_ast(&self, ast: &AstNode) -> Option<String> {
        match ast {
            AstNode::Neg(inner) => {
                let s = self.try_exact_from_ast(inner)?;
                if let Some(stripped) = s.strip_prefix('-') {
                    Some(stripped.to_string())
                } else {
                    Some(format!("-{}", s))
                }
            }
            AstNode::Func(name, args) if name == "sqrt" && args.len() == 1 => {
                let r = try_exact_rat_ast(&args[0])?;
                if r.num > 0 && r.den == 1 && r.num <= i64::MAX as i128 {
                    return Some(format_surd_rust(1, r.num as i64, 1));
                }
                None
            }
            AstNode::Func(name, args)
                if (name == "sin" || name == "cos" || name == "tan") && args.len() == 1 =>
            {
                self.try_exact_trig_ast(name, &args[0])
            }
            AstNode::Func(name, args)
                if (name == "asin" || name == "arcsin" || name == "acos" || name == "arccos"
                    || name == "atan" || name == "arctan")
                    && args.len() == 1 =>
            {
                self.try_exact_inv_trig_ast(name, &args[0])
            }
            AstNode::Div(left, right) => {
                if let (Some(num), AstNode::Func(name, args)) = (try_exact_rat_ast(left), right.as_ref()) {
                    if name == "sqrt" && args.len() == 1 && num.den == 1 {
                        if let Some(r) = try_exact_rat_ast(&args[0]) {
                            if r.num > 0 && r.den == 1 && r.num <= 1_000_000_000 {
                                return Some(format_surd_rust(i64::try_from(num.num).ok()?, r.num as i64, r.num as i64));
                            }
                        }
                    }
                }
                if let (AstNode::Func(name, args), Some(den)) = (left.as_ref(), try_exact_rat_ast(right)) {
                    if name == "sqrt" && args.len() == 1 && den.num != 0 && den.den == 1 {
                        if let Some(r) = try_exact_rat_ast(&args[0]) {
                            if r.num > 0 && r.den == 1 && r.num <= i64::MAX as i128 {
                                return Some(format_surd_rust(1, r.num as i64, i64::try_from(den.num).ok()?));
                            }
                        }
                    }
                }
                None
            }
            AstNode::Mul(left, right) => {
                if let (Some(k), AstNode::Func(name, args)) = (try_exact_rat_ast(left), right.as_ref()) {
                    if name == "sqrt" && args.len() == 1 && k.den == 1 {
                        if let Some(r) = try_exact_rat_ast(&args[0]) {
                            if r.num > 0 && r.den == 1 && r.num <= i64::MAX as i128 {
                                return Some(format_surd_rust(i64::try_from(k.num).ok()?, r.num as i64, 1));
                            }
                        }
                    }
                }
                if let (AstNode::Func(name, args), Some(k)) = (left.as_ref(), try_exact_rat_ast(right)) {
                    if name == "sqrt" && args.len() == 1 && k.den == 1 {
                        if let Some(r) = try_exact_rat_ast(&args[0]) {
                            if r.num > 0 && r.den == 1 && r.num <= i64::MAX as i128 {
                                return Some(format_surd_rust(i64::try_from(k.num).ok()?, r.num as i64, 1));
                            }
                        }
                    }
                }
                None
            }
            _ => None,
        }
    }

    fn try_exact_trig_ast(&self, fn_name: &str, arg: &AstNode) -> Option<String> {
        let deg_rat = if matches!(self.angle_mode, AngleMode::Radians) {
            let pi_coeff = try_pi_coeff_ast(arg).or_else(|| {
                let z = try_exact_rat_ast(arg)?;
                if z.num == 0 { Some(z) } else { None }
            })?;
            pi_coeff.mul(ExactRat::from_int(180))?
        } else {
            try_exact_rat_ast(arg)?
        };
        if deg_rat.den != 1 {
            return None;
        }
        let deg = ((deg_rat.num % 360) + 360) % 360;
        let res = match fn_name {
            "sin" => match deg {
                0 | 180 => Some("0"),
                90 => Some("1"),
                270 => Some("-1"),
                30 | 150 => Some("1 / 2"),
                210 | 330 => Some("-1 / 2"),
                45 | 135 => Some("√2 / 2"),
                225 | 315 => Some("-√2 / 2"),
                60 | 120 => Some("√3 / 2"),
                240 | 300 => Some("-√3 / 2"),
                _ => None,
            },
            "cos" => match deg {
                90 | 270 => Some("0"),
                0 => Some("1"),
                180 => Some("-1"),
                60 | 300 => Some("1 / 2"),
                120 | 240 => Some("-1 / 2"),
                45 | 315 => Some("√2 / 2"),
                135 | 225 => Some("-√2 / 2"),
                30 | 330 => Some("√3 / 2"),
                150 | 210 => Some("-√3 / 2"),
                _ => None,
            },
            "tan" => match deg {
                0 | 180 => Some("0"),
                45 | 225 => Some("1"),
                135 | 315 => Some("-1"),
                30 | 210 => Some("√3 / 3"),
                150 | 330 => Some("-√3 / 3"),
                60 | 240 => Some("√3"),
                120 | 300 => Some("-√3"),
                _ => None,
            },
            _ => None,
        };
        res.map(|s| s.to_string())
    }

    fn try_exact_inv_trig_ast(&self, fn_name: &str, arg: &AstNode) -> Option<String> {
        if !matches!(self.angle_mode, AngleMode::Radians) {
            return None;
        }
        let val = if let Some(r) = try_exact_rat_ast(arg) {
            Some((r.num as f64) / (r.den as f64))
        } else if let AstNode::Num(v) = arg {
            if v.c.is_real() { Some(v.c.re) } else { None }
        } else {
            None
        }?;
        let res = match fn_name {
            "asin" | "arcsin" => {
                if val == 0.5 { Some("π / 6") }
                else if val == -0.5 { Some("-π / 6") }
                else if val == 1.0 { Some("π / 2") }
                else if val == -1.0 { Some("-π / 2") }
                else { None }
            }
            "acos" | "arccos" => {
                if val == 0.5 { Some("π / 3") }
                else if val == -0.5 { Some("(2 / 3) π") }
                else if val == 0.0 { Some("π / 2") }
                else if val == -1.0 { Some("π") }
                else { None }
            }
            "atan" | "arctan" => {
                if val == 1.0 { Some("π / 4") }
                else if val == -1.0 { Some("-π / 4") }
                else { None }
            }
            _ => None,
        };
        res.map(|s| s.to_string())
    }

    fn evaluate_matrix_expression(&self, expr: &str) -> Result<EvalOutput, String> {
        let trimmed = expr.trim();
        if let Some(inner) = trimmed.strip_prefix("det(").and_then(|s| s.strip_suffix(')')) {
            let mat = self.eval_matrix_expr(inner)?;
            let det = matrix_det(&mat)?;
            let dec = self.format_value_decimal(det);
            let exact = Self::format_exact_real(det).unwrap_or_else(|| dec.clone());
            return Ok(EvalOutput {
                has_distinct_exact: exact != dec,
                exact_text: exact,
                decimal_text: dec,
                numeric_value: Some(det.c),
                val: Some(det),
                integration_report: None,
                matrix_expression: None,
            });
        }

        if let Ok(ast) = AstParser::parse(trimmed) {
            if let MatrixOrScalar::Scalar(val) = self.eval_matrix_or_scalar_ast(&ast)? {
                let dec = self.format_value_decimal(val);
                let exact = Self::format_exact_real(val).unwrap_or_else(|| dec.clone());
                return Ok(EvalOutput {
                    has_distinct_exact: exact != dec,
                    exact_text: exact,
                    decimal_text: dec,
                    numeric_value: Some(val.c),
                    val: Some(val),
                    integration_report: None,
                    matrix_expression: None,
                });
            }
        }

        let mat = self.eval_matrix_expr(trimmed)?;
        let decimal = Self::format_matrix(&mat, |value| self.format_value_decimal(value));
        let exact = Self::format_matrix(&mat, |value| {
            Self::format_exact_real(value).unwrap_or_else(|| self.format_value_decimal(value))
        });
        Ok(EvalOutput {
            has_distinct_exact: exact != decimal,
            exact_text: exact,
            decimal_text: decimal,
            numeric_value: None,
            val: None,
            integration_report: None,
            matrix_expression: Some(Self::format_matrix(&mat, Value::reusable_expression)),
        })
    }

    fn format_exact_real(value: Value) -> Option<String> {
        let rat = value.rat?;
        Some(if rat.den == 1 { rat.num.to_string() } else { format!("{} / {}", rat.num, rat.den) })
    }

    fn eval_ast_scalar(&self, node: &AstNode) -> Result<Value, String> {
        match node {
            AstNode::Num(v) => Ok(*v),
            AstNode::Neg(a) => {
                let v = self.eval_ast_scalar(a)?;
                Ok(Value {
                    c: Complex::new(-v.c.re, -v.c.im),
                    rat: v.rat.map(|r| ExactRat { num: -r.num, den: r.den }),
                })
            }
            AstNode::Add(a, b) => Ok(self.eval_ast_scalar(a)?.add(self.eval_ast_scalar(b)?)),
            AstNode::Sub(a, b) => Ok(self.eval_ast_scalar(a)?.sub(self.eval_ast_scalar(b)?)),
            AstNode::Mul(a, b) => Ok(self.eval_ast_scalar(a)?.mul(self.eval_ast_scalar(b)?)),
            AstNode::Div(a, b) => self.eval_ast_scalar(a)?.div(self.eval_ast_scalar(b)?),
            AstNode::Pow(a, b) => Ok(self.eval_ast_scalar(a)?.pow(self.eval_ast_scalar(b)?)),
            AstNode::Func(name, args) if name == "det" && args.len() == 1 => {
                let mat = self.eval_matrix_ast(&args[0])?;
                matrix_det(&mat)
            }
            _ => {
                let expr = node.to_display_string();
                let mut p = ExprParser::new(&expr, self, None);
                let v = p.parse_expr()?;
                p.skip_ws();
                if p.pos < p.chars.len() {
                    return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
                }
                Ok(v)
            }
        }
    }

    fn eval_matrix_ast(&self, node: &AstNode) -> Result<Vec<Vec<Value>>, String> {
        match self.eval_matrix_or_scalar_ast(node)? {
            MatrixOrScalar::Matrix(m) => Ok(m),
            MatrixOrScalar::Scalar(_) => Err("Matrix erwartet".to_string()),
        }
    }

    fn eval_matrix_or_scalar_ast(&self, node: &AstNode) -> Result<MatrixOrScalar, String> {
        match node {
            AstNode::Neg(inner) => match self.eval_matrix_or_scalar_ast(inner)? {
                MatrixOrScalar::Matrix(m) => Ok(MatrixOrScalar::Matrix(scale_matrix(&m, Value::from_int(-1)))),
                MatrixOrScalar::Scalar(s) => Ok(MatrixOrScalar::Scalar(Value {
                    c: Complex::new(-s.c.re, -s.c.im),
                    rat: s.rat.map(|r| ExactRat { num: -r.num, den: r.den }),
                })),
            },
            AstNode::Add(l, r) => match (self.eval_matrix_or_scalar_ast(l)?, self.eval_matrix_or_scalar_ast(r)?) {
                (MatrixOrScalar::Matrix(a), MatrixOrScalar::Matrix(b)) => Ok(MatrixOrScalar::Matrix(add_sub_matrices(&a, &b, true)?)),
                (MatrixOrScalar::Scalar(a), MatrixOrScalar::Scalar(b)) => Ok(MatrixOrScalar::Scalar(a.add(b))),
                _ => Err("Incompatible matrix dimensions".to_string()),
            },
            AstNode::Sub(l, r) => match (self.eval_matrix_or_scalar_ast(l)?, self.eval_matrix_or_scalar_ast(r)?) {
                (MatrixOrScalar::Matrix(a), MatrixOrScalar::Matrix(b)) => Ok(MatrixOrScalar::Matrix(add_sub_matrices(&a, &b, false)?)),
                (MatrixOrScalar::Scalar(a), MatrixOrScalar::Scalar(b)) => Ok(MatrixOrScalar::Scalar(a.sub(b))),
                _ => Err("Incompatible matrix dimensions".to_string()),
            },
            AstNode::Mul(l, r) => match (self.eval_matrix_or_scalar_ast(l)?, self.eval_matrix_or_scalar_ast(r)?) {
                (MatrixOrScalar::Matrix(a), MatrixOrScalar::Matrix(b)) => Ok(MatrixOrScalar::Matrix(multiply_matrices(&a, &b)?)),
                (MatrixOrScalar::Matrix(m), MatrixOrScalar::Scalar(s))
                | (MatrixOrScalar::Scalar(s), MatrixOrScalar::Matrix(m)) => Ok(MatrixOrScalar::Matrix(scale_matrix(&m, s))),
                (MatrixOrScalar::Scalar(a), MatrixOrScalar::Scalar(b)) => Ok(MatrixOrScalar::Scalar(a.mul(b))),
            },
            AstNode::Div(l, r) => match (self.eval_matrix_or_scalar_ast(l)?, self.eval_matrix_or_scalar_ast(r)?) {
                (MatrixOrScalar::Matrix(m), MatrixOrScalar::Scalar(s)) => {
                    let scaled = m
                        .into_iter()
                        .map(|row| row.into_iter().map(|cell| cell.div(s)).collect::<Result<Vec<_>, _>>())
                        .collect::<Result<Vec<_>, _>>()?;
                    Ok(MatrixOrScalar::Matrix(scaled))
                }
                (MatrixOrScalar::Scalar(a), MatrixOrScalar::Scalar(b)) => Ok(MatrixOrScalar::Scalar(a.div(b)?)),
                _ => Err("Ungültige Matrixdivision".to_string()),
            },
            AstNode::Func(name, args) if name == "inv" && args.len() == 1 => {
                let mat = self.eval_matrix_ast(&args[0])?;
                Ok(MatrixOrScalar::Matrix(matrix_inv(&mat)?))
            }
            AstNode::Func(name, args) if (name == "trans" || name == "transpose") && args.len() == 1 => {
                let mat = self.eval_matrix_ast(&args[0])?;
                Ok(MatrixOrScalar::Matrix(transpose_matrix(&mat)))
            }
            AstNode::Func(name, args) if name == "det" && args.len() == 1 => {
                let mat = self.eval_matrix_ast(&args[0])?;
                Ok(MatrixOrScalar::Scalar(matrix_det(&mat)?))
            }
            AstNode::Func(name, args) if name == "[]" => {
                if args.is_empty() {
                    return Err("Leere Matrix".to_string());
                }
                let mut rows = Vec::with_capacity(args.len());
                let mut expected_cols: Option<usize> = None;
                for row_node in args {
                    let AstNode::Func(rname, cells) = row_node else {
                        return Err("Matrix muss mit [[...]] umschlossen sein".to_string());
                    };
                    if rname != "[]" {
                        return Err("Matrix muss mit [[...]] umschlossen sein".to_string());
                    }
                    if cells.is_empty() {
                        return Err("Leere Matrix".to_string());
                    }
                    match expected_cols {
                        None => expected_cols = Some(cells.len()),
                        Some(ec) if ec != cells.len() => return Err("Incompatible matrix dimensions".to_string()),
                        _ => {}
                    }
                    let mut row_vals = Vec::with_capacity(cells.len());
                    for cell in cells {
                        let v = self.eval_ast_scalar(cell)?;
                        if !v.c.is_real() || !v.c.re.is_finite() {
                            return Err("Matrixzellen müssen endliche reelle Zahlen sein".to_string());
                        }
                        row_vals.push(v);
                    }
                    rows.push(row_vals);
                }
                Ok(MatrixOrScalar::Matrix(rows))
            }
            _ => Ok(MatrixOrScalar::Scalar(self.eval_ast_scalar(node)?)),
        }
    }

    fn eval_matrix_expr(&self, s: &str) -> Result<Vec<Vec<Value>>, String> {
        let s = s.trim();
        if let Ok(ast) = AstParser::parse(s) {
            return self.eval_matrix_ast(&ast);
        }
        let mut depth = 0;
        let mut split_pos = None;
        let mut op = '+';
        for (i, ch) in s.char_indices() {
            if ch == '[' || ch == '(' { depth += 1; }
            else if ch == ']' || ch == ')' { depth -= 1; }
            else if depth == 0 && (ch == '+' || ch == '-') && i > 0 {
                split_pos = Some(i);
                op = ch;
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
            let mut res = vec![vec![Value::from_int(0); cols]; rows];
            for r in 0..rows {
                for c in 0..cols {
                    res[r][c] = if op == '+' {
                        left[r][c].add(right[r][c])
                    } else {
                        left[r][c].sub(right[r][c])
                    };
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
            let mut t = vec![vec![Value::from_int(0); rows]; cols];
            for r in 0..rows {
                for c in 0..cols {
                    t[c][r] = mat[r][c];
                }
            }
            return Ok(t);
        }
        Err("Ungültiger Matrix-Ausdruck".to_string())
    }

    fn parse_matrix(&self, s: &str) -> Result<Vec<Vec<Value>>, String> {
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
                        let mut nested = 0;
                        let cells = cur.split(|ch| {
                            match ch {
                                '(' | '[' => nested += 1,
                                ')' | ']' => nested -= 1,
                                _ => {}
                            }
                            ch == ',' && nested == 0
                        });
                        for cell in cells {
                            let mut p = ExprParser::new(cell.trim(), self, None);
                            let v = p.parse_expr()?;
                            p.skip_ws();
                            if p.pos < p.chars.len() || !v.c.is_real() || !v.c.re.is_finite() {
                                return Err("Matrixzellen müssen endliche reelle Zahlen sein".to_string());
                            }
                            row_vals.push(v);
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

    fn format_matrix(mat: &[Vec<Value>], format_cell: impl Fn(Value) -> String) -> String {
        let row_strs: Vec<String> = mat
            .iter()
            .map(|r| {
                let cells: Vec<String> = r
                    .iter()
                    .map(|&value| format_cell(value))
                    .collect();
                format!("[{}]", cells.join(", "))
            })
            .collect();
        format!("[{}]", row_strs.join(", "))
    }
}

enum MatrixOrScalar {
    Matrix(Vec<Vec<Value>>),
    Scalar(Value),
}

fn contains_matrix_expression(node: &AstNode) -> bool {
    match node {
        AstNode::Neg(a) => contains_matrix_expression(a),
        AstNode::Add(a, b)
        | AstNode::Sub(a, b)
        | AstNode::Mul(a, b)
        | AstNode::Div(a, b)
        | AstNode::Pow(a, b) => contains_matrix_expression(a) || contains_matrix_expression(b),
        AstNode::Func(name, args) => {
            if name == "det" {
                return false;
            }
            if name == "[]" || name == "inv" || name == "trans" || name == "transpose" {
                return true;
            }
            args.iter().any(contains_matrix_expression)
        }
        _ => false,
    }
}

fn add_sub_matrices(left: &[Vec<Value>], right: &[Vec<Value>], is_add: bool) -> Result<Vec<Vec<Value>>, String> {
    if left.len() != right.len() || left[0].len() != right[0].len() {
        return Err("Incompatible matrix dimensions".to_string());
    }
    let rows = left.len();
    let cols = left[0].len();
    let mut res = vec![vec![Value::from_int(0); cols]; rows];
    for r in 0..rows {
        for c in 0..cols {
            res[r][c] = if is_add {
                left[r][c].add(right[r][c])
            } else {
                left[r][c].sub(right[r][c])
            };
        }
    }
    Ok(res)
}

fn transpose_matrix(mat: &[Vec<Value>]) -> Vec<Vec<Value>> {
    let rows = mat.len();
    let cols = mat[0].len();
    let mut t = vec![vec![Value::from_int(0); rows]; cols];
    for r in 0..rows {
        for c in 0..cols {
            t[c][r] = mat[r][c];
        }
    }
    t
}

fn scale_matrix(mat: &[Vec<Value>], scalar: Value) -> Vec<Vec<Value>> {
    mat.iter()
        .map(|row| row.iter().map(|cell| cell.mul(scalar)).collect())
        .collect()
}

fn multiply_matrices(left: &[Vec<Value>], right: &[Vec<Value>]) -> Result<Vec<Vec<Value>>, String> {
    let rows = left.len();
    let inner = left[0].len();
    if right.len() != inner {
        return Err("Incompatible matrix dimensions".to_string());
    }
    let cols = right[0].len();
    let mut res = vec![vec![Value::from_int(0); cols]; rows];
    for r in 0..rows {
        for c in 0..cols {
            let mut sum = Value::from_int(0);
            for k in 0..inner {
                sum = sum.add(left[r][k].mul(right[k][c]));
            }
            res[r][c] = sum;
        }
    }
    Ok(res)
}

fn matrix_minor(a: Value, b: Value, c: Value, d: Value) -> Value {
    a.mul(d).sub(b.mul(c))
}

fn matrix_det(m: &[Vec<Value>]) -> Result<Value, String> {
    let n = m.len();
    if n == 0 || m[0].len() != n {
        return Err("Determinante erfordert eine quadratische Matrix".to_string());
    }
    if n == 1 {
        return Ok(m[0][0]);
    }
    if n == 2 {
        return Ok(matrix_minor(m[0][0], m[0][1], m[1][0], m[1][1]));
    }
    if n == 3 {
        return Ok(
            m[0][0].mul(matrix_minor(m[1][1], m[1][2], m[2][1], m[2][2]))
                .sub(m[0][1].mul(matrix_minor(m[1][0], m[1][2], m[2][0], m[2][2])))
                .add(m[0][2].mul(matrix_minor(m[1][0], m[1][1], m[2][0], m[2][1]))),
        );
    }
    // General NxN via LU / Gaussian elimination with partial pivoting
    let mut a = m.to_vec();
    let mut det = Value::from_int(1);
    let mut sign = 1;
    for i in 0..n {
        let mut pivot_row = i;
        let mut max_val = a[i][i].c.re.abs();
        for k in (i + 1)..n {
            if a[k][i].c.re.abs() > max_val {
                max_val = a[k][i].c.re.abs();
                pivot_row = k;
            }
        }
        if max_val == 0.0 {
            return Ok(Value::from_int(0));
        }
        if pivot_row != i {
            a.swap(i, pivot_row);
            sign = -sign;
        }
        let pivot = a[i][i];
        det = det.mul(pivot);
        for k in (i + 1)..n {
            let factor = a[k][i].div(pivot)?;
            for j in (i + 1)..n {
                a[k][j] = a[k][j].sub(factor.mul(a[i][j]));
            }
        }
    }
    Ok(det.mul(Value::from_int(sign)))
}

fn matrix_inv(m: &[Vec<Value>]) -> Result<Vec<Vec<Value>>, String> {
    let n = m.len();
    if n == 0 || m[0].len() != n {
        return Err("Matrix-Invertierung erfordert quadratische Matrix".to_string());
    }
    let det = matrix_det(m)?;
    if det.rat.map_or(det.c.re.abs() < 1e-14, |rat| rat.num == 0) {
        return Err("Matrix ist singulär (det = 0)".to_string());
    }
    let zero = Value::from_int(0);
    let one = Value::from_int(1);
    let mut aug = vec![vec![zero; 2 * n]; n];
    for r in 0..n {
        for c in 0..n {
            aug[r][c] = m[r][c];
        }
        aug[r][n + r] = one;
    }
    for i in 0..n {
        let mut pivot_row = i;
        let mut max_val = aug[i][i].c.abs();
        for k in (i + 1)..n {
            let v = aug[k][i].c.abs();
            if v > max_val {
                max_val = v;
                pivot_row = k;
            }
        }
        if max_val < 1e-14 {
            return Err("Matrix ist singulär (nicht invertierbar)".to_string());
        }
        if pivot_row != i {
            aug.swap(i, pivot_row);
        }
        let pivot = aug[i][i];
        for j in 0..(2 * n) {
            aug[i][j] = aug[i][j].div(pivot)?;
        }
        for k in 0..n {
            if k != i {
                let factor = aug[k][i];
                for j in 0..(2 * n) {
                    let sub_term = factor.mul(aug[i][j]);
                    aug[k][j] = aug[k][j].sub(sub_term);
                }
            }
        }
    }
    let mut inv = vec![vec![zero; n]; n];
    for i in 0..n {
        inv[i].copy_from_slice(&aug[i][n..(2 * n)]);
    }
    Ok(inv)
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
                    c: Complex::new(-v.c.re, -v.c.im),
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

    #[allow(dead_code)]
    pub fn diff(&self, var: &str) -> Option<AstNode> {
        self.diff_with_mode(var, AngleMode::Radians)
    }

    pub fn diff_with_mode(&self, var: &str, mode: AngleMode) -> Option<AstNode> {
        match self {
            AstNode::Num(_) => Some(AstNode::Num(Value::from_int(0))),
            AstNode::Var(v) => {
                if v == var {
                    Some(AstNode::Num(Value::from_int(1)))
                } else {
                    Some(AstNode::Num(Value::from_int(0)))
                }
            }
            AstNode::Add(a, b) => Some(AstNode::Add(
                Box::new(a.diff_with_mode(var, mode)?),
                Box::new(b.diff_with_mode(var, mode)?),
            )),
            AstNode::Sub(a, b) => Some(AstNode::Sub(
                Box::new(a.diff_with_mode(var, mode)?),
                Box::new(b.diff_with_mode(var, mode)?),
            )),
            AstNode::Mul(a, b) => {
                // Product rule: (a * b)' = a' * b + a * b'
                let da = a.diff_with_mode(var, mode)?;
                let db = b.diff_with_mode(var, mode)?;
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
                let da = a.diff_with_mode(var, mode)?;
                let db = b.diff_with_mode(var, mode)?;
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
                    let u_prime = base.diff_with_mode(var, mode)?;
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
                    let v_prime = exp.diff_with_mode(var, mode)?;
                    return Some(AstNode::Mul(
                        Box::new(AstNode::Mul(
                            Box::new(self.clone()),
                            Box::new(AstNode::Func("ln".to_string(), vec![(**base).clone()])),
                        )),
                        Box::new(v_prime),
                    ));
                }

                // General power rule (u > 0): (u^v)' = u^v * (v' * ln(u) + (v * u') / u)
                let u_prime = base.diff_with_mode(var, mode)?;
                let v_prime = exp.diff_with_mode(var, mode)?;
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
            AstNode::Neg(a) => Some(AstNode::Neg(Box::new(a.diff_with_mode(var, mode)?))),
            AstNode::Func(name, args) if args.len() == 1 => {
                let u = &args[0];
                if !u.contains_var(var) {
                    return Some(AstNode::Num(Value::from_int(0)));
                }
                let u_prime = u.diff_with_mode(var, mode)?;
                let deg_scale = || AstNode::Div(
                    Box::new(AstNode::Var("pi".to_string())),
                    Box::new(AstNode::Num(Value::from_int(180))),
                );
                let inv_deg_scale = || AstNode::Div(
                    Box::new(AstNode::Num(Value::from_int(180))),
                    Box::new(AstNode::Var("pi".to_string())),
                );
                match name.as_str() {
                    "sin" => {
                        let base = AstNode::Mul(
                            Box::new(u_prime),
                            Box::new(AstNode::Func("cos".to_string(), vec![u.clone()])),
                        );
                        if mode == AngleMode::Degrees {
                            Some(AstNode::Mul(Box::new(deg_scale()), Box::new(base.simplify())))
                        } else {
                            Some(base)
                        }
                    }
                    "cos" => {
                        let base = AstNode::Mul(
                            Box::new(u_prime),
                            Box::new(AstNode::Func("sin".to_string(), vec![u.clone()])),
                        );
                        if mode == AngleMode::Degrees {
                            Some(AstNode::Neg(Box::new(AstNode::Mul(
                                Box::new(deg_scale()),
                                Box::new(base.simplify()),
                            ))))
                        } else {
                            Some(AstNode::Neg(Box::new(base)))
                        }
                    }
                    "tan" => {
                        let denom = Box::new(AstNode::Pow(
                            Box::new(AstNode::Func("cos".to_string(), vec![u.clone()])),
                            Box::new(AstNode::Num(Value::from_int(2))),
                        ));
                        if mode == AngleMode::Degrees {
                            Some(AstNode::Div(
                                Box::new(AstNode::Mul(Box::new(deg_scale()), Box::new(u_prime))),
                                denom,
                            ))
                        } else {
                            Some(AstNode::Div(Box::new(u_prime), denom))
                        }
                    }
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
                    "asin" | "arcsin" => {
                        let base = AstNode::Div(
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
                        );
                        Some(if mode == AngleMode::Degrees {
                            AstNode::Mul(Box::new(inv_deg_scale()), Box::new(base))
                        } else {
                            base
                        })
                    }
                    "acos" | "arccos" => {
                        let base = AstNode::Div(
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
                        );
                        Some(AstNode::Neg(Box::new(if mode == AngleMode::Degrees {
                            AstNode::Mul(Box::new(inv_deg_scale()), Box::new(base))
                        } else {
                            base
                        })))
                    }
                    "atan" | "arctan" => {
                        let base = AstNode::Div(
                            Box::new(u_prime),
                            Box::new(AstNode::Add(
                                Box::new(AstNode::Num(Value::from_int(1))),
                                Box::new(AstNode::Pow(
                                    Box::new(u.clone()),
                                    Box::new(AstNode::Num(Value::from_int(2))),
                                )),
                            )),
                        );
                        Some(if mode == AngleMode::Degrees {
                            AstNode::Mul(Box::new(inv_deg_scale()), Box::new(base))
                        } else {
                            base
                        })
                    }
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
                    .diff_with_mode(var, mode),
                    "addition" => AstNode::Add(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff_with_mode(var, mode),
                    "subtraction" => AstNode::Sub(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff_with_mode(var, mode),
                    "multiplication" => AstNode::Mul(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff_with_mode(var, mode),
                    "division" | "fraction" => AstNode::Div(
                        Box::new(args[0].clone()),
                        Box::new(args[1].clone()),
                    )
                    .diff_with_mode(var, mode),
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

    fn match_linear(&self, var: &str) -> Option<(AstNode, AstNode)> {
        if !self.contains_var(var) {
            return None;
        }
        match self {
            AstNode::Var(v) if v == var => {
                Some((AstNode::Num(Value::from_int(1)), AstNode::Num(Value::from_int(0))))
            }
            AstNode::Neg(inner) => {
                let (a, b) = inner.match_linear(var)?;
                Some((AstNode::Neg(Box::new(a)).simplify(), AstNode::Neg(Box::new(b)).simplify()))
            }
            AstNode::Add(l, r) => {
                if !r.contains_var(var) {
                    let (a, b) = l.match_linear(var)?;
                    Some((a, AstNode::Add(Box::new(b), r.clone()).simplify()))
                } else if !l.contains_var(var) {
                    let (a, b) = r.match_linear(var)?;
                    Some((a, AstNode::Add(l.clone(), Box::new(b)).simplify()))
                } else {
                    None
                }
            }
            AstNode::Sub(l, r) => {
                if !r.contains_var(var) {
                    let (a, b) = l.match_linear(var)?;
                    Some((a, AstNode::Sub(Box::new(b), r.clone()).simplify()))
                } else if !l.contains_var(var) {
                    let (a, b) = r.match_linear(var)?;
                    Some((AstNode::Neg(Box::new(a)).simplify(), AstNode::Sub(l.clone(), Box::new(b)).simplify()))
                } else {
                    None
                }
            }
            AstNode::Mul(l, r) => {
                if !l.contains_var(var) {
                    let (a, b) = r.match_linear(var)?;
                    Some((
                        AstNode::Mul(l.clone(), Box::new(a)).simplify(),
                        AstNode::Mul(l.clone(), Box::new(b)).simplify(),
                    ))
                } else if !r.contains_var(var) {
                    let (a, b) = l.match_linear(var)?;
                    Some((
                        AstNode::Mul(r.clone(), Box::new(a)).simplify(),
                        AstNode::Mul(r.clone(), Box::new(b)).simplify(),
                    ))
                } else {
                    None
                }
            }
            AstNode::Div(l, r) if !r.contains_var(var) => {
                let (a, b) = l.match_linear(var)?;
                Some((
                    AstNode::Div(Box::new(a), r.clone()).simplify(),
                    AstNode::Div(Box::new(b), r.clone()).simplify(),
                ))
            }
            _ => None,
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
            AstNode::Var(v) => Some(AstNode::Mul(
                Box::new(AstNode::Var(v.clone())),
                Box::new(AstNode::Var(var.to_string())),
            )),
            AstNode::Num(v) if v.c.re == 1.0 && v.c.im == 0.0 => {
                Some(AstNode::Var(var.to_string()))
            }
            AstNode::Num(v) => {
                Some(AstNode::Mul(
                    Box::new(AstNode::Num(*v)),
                    Box::new(AstNode::Var(var.to_string())),
                ))
            }
            AstNode::Neg(inner) => Some(AstNode::Neg(Box::new(inner.integrate(var)?))),
            AstNode::Div(a, b) => {
                if !b.contains_var(var) {
                    let ia = a.integrate(var)?;
                    return Some(AstNode::Div(Box::new(ia), b.clone()).simplify());
                }
                if !a.contains_var(var) && **b == AstNode::Var(var.to_string()) {
                    let ln = AstNode::Func("ln".to_string(), vec![AstNode::Var(var.to_string())]);
                    return Some(if a.is_one() {
                        ln
                    } else {
                        AstNode::Mul(a.clone(), Box::new(ln))
                    });
                }
                None
            }
            AstNode::Pow(base, exp) if **base == AstNode::Var(var.to_string()) && !exp.contains_var(var) => {
                if let Some(er) = try_exact_rat_ast(exp) {
                    if er.den == 1 && er.num == -1 {
                        return Some(AstNode::Func(
                            "ln".to_string(),
                            vec![AstNode::Var(var.to_string())],
                        ));
                    }
                    let new_exp = er.add(ExactRat::from_int(1))?;
                    let new_exp_val = Value { c: Complex::real(new_exp.to_f64()), rat: Some(new_exp) };
                    if new_exp.den == 1 {
                        return Some(AstNode::Div(
                            Box::new(AstNode::Pow(
                                Box::new(AstNode::Var(var.to_string())),
                                Box::new(AstNode::Num(new_exp_val)),
                            )),
                            Box::new(AstNode::Num(new_exp_val)),
                        ));
                    }
                    let coeff = ExactRat::from_int(1).div(new_exp)?;
                    let coeff_val = Value { c: Complex::real(coeff.to_f64()), rat: Some(coeff) };
                    return Some(AstNode::Mul(
                        Box::new(AstNode::Num(coeff_val)),
                        Box::new(AstNode::Pow(
                            Box::new(AstNode::Var(var.to_string())),
                            Box::new(AstNode::Num(new_exp_val)),
                        )),
                    ));
                }
                if let Some(c_val) = exp.const_value() {
                    if c_val.c.is_real() {
                        if (c_val.c.re + 1.0).abs() < 1e-11 {
                            return Some(AstNode::Func(
                                "ln".to_string(),
                                vec![AstNode::Var(var.to_string())],
                            ));
                        }
                        if (c_val.c.re - c_val.c.re.round()).abs() < 1e-11 {
                            let new_p = c_val.c.re.round() as i128 + 1;
                            return Some(AstNode::Div(
                                Box::new(AstNode::Pow(
                                    Box::new(AstNode::Var(var.to_string())),
                                    Box::new(AstNode::Num(Value::from_int(new_p))),
                                )),
                                Box::new(AstNode::Num(Value::from_int(new_p))),
                            ));
                        }
                        let new_p = c_val.c.re + 1.0;
                        return Some(AstNode::Div(
                            Box::new(AstNode::Pow(
                                Box::new(AstNode::Var(var.to_string())),
                                Box::new(AstNode::Num(Value::from_f64(new_p))),
                            )),
                            Box::new(AstNode::Num(Value::from_f64(new_p))),
                        ));
                    }
                }
                None
            }
            AstNode::Func(name, args) if args.len() == 1 => {
                let u = &args[0];
                if !u.contains_var(var) {
                    return Some(AstNode::Mul(
                        Box::new(self.clone()),
                        Box::new(AstNode::Var(var.to_string())),
                    ));
                }
                let (a, _b) = u.match_linear(var)?;
                if a.is_zero() {
                    return None;
                }
                let unit_a = a.is_one();
                match name.as_str() {
                    "sqrt" => {
                        let three_halves = ExactRat::new(3, 2)?;
                        let exp_val = Value { c: Complex::real(1.5), rat: Some(three_halves) };
                        let exp_node = AstNode::Num(exp_val);
                        if unit_a {
                            let two_thirds = ExactRat::new(2, 3)?;
                            let coeff_val = Value { c: Complex::real(2.0 / 3.0), rat: Some(two_thirds) };
                            Some(AstNode::Mul(
                                Box::new(AstNode::Num(coeff_val)),
                                Box::new(AstNode::Pow(Box::new(u.clone()), Box::new(exp_node))),
                            ))
                        } else {
                            let denom = AstNode::Mul(
                                Box::new(AstNode::Num(Value::from_int(3))),
                                Box::new(a),
                            )
                            .simplify();
                            Some(AstNode::Div(
                                Box::new(AstNode::Mul(
                                    Box::new(AstNode::Num(Value::from_int(2))),
                                    Box::new(AstNode::Pow(Box::new(u.clone()), Box::new(exp_node))),
                                )),
                                Box::new(denom),
                            ))
                        }
                    }
                    "exp" => {
                        let base = AstNode::Func("exp".to_string(), vec![u.clone()]);
                        Some(if unit_a { base } else { AstNode::Div(Box::new(base), Box::new(a)) })
                    }
                    "sin" => {
                        let cos_u = AstNode::Func("cos".to_string(), vec![u.clone()]);
                        if unit_a {
                            Some(AstNode::Neg(Box::new(cos_u)))
                        } else if let AstNode::Neg(inner_a) = a {
                            Some(AstNode::Div(Box::new(cos_u), inner_a))
                        } else if a.is_negative_display() {
                            Some(AstNode::Div(Box::new(cos_u), Box::new(AstNode::Neg(Box::new(a)).simplify())))
                        } else {
                            Some(AstNode::Neg(Box::new(AstNode::Div(Box::new(cos_u), Box::new(a)))))
                        }
                    }
                    "cos" => {
                        let sin_u = AstNode::Func("sin".to_string(), vec![u.clone()]);
                        Some(if unit_a { sin_u } else { AstNode::Div(Box::new(sin_u), Box::new(a)) })
                    }
                    "sinh" => {
                        let cosh_u = AstNode::Func("cosh".to_string(), vec![u.clone()]);
                        Some(if unit_a { cosh_u } else { AstNode::Div(Box::new(cosh_u), Box::new(a)) })
                    }
                    "cosh" => {
                        let sinh_u = AstNode::Func("sinh".to_string(), vec![u.clone()]);
                        Some(if unit_a { sinh_u } else { AstNode::Div(Box::new(sinh_u), Box::new(a)) })
                    }
                    _ => None,
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
                if !a.contains_var(var) {
                    let ib = b.integrate(var)?;
                    Some(AstNode::Mul(a.clone(), Box::new(ib)).simplify())
                } else if !b.contains_var(var) {
                    let ia = a.integrate(var)?;
                    Some(AstNode::Mul(Box::new(ia), b.clone()).simplify())
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
                        c: Complex::new(-v.c.re, -v.c.im),
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

#[derive(Clone, Debug, PartialEq, Eq)]
struct PolyMonomial {
    vars: BTreeMap<String, i32>,
}

impl PolyMonomial {
    fn empty() -> Self {
        Self { vars: BTreeMap::new() }
    }

    fn total_degree(&self) -> i32 {
        self.vars.values().sum()
    }

    fn mul(&self, other: &Self) -> Self {
        let mut vars = self.vars.clone();
        for (k, &v) in &other.vars {
            *vars.entry(k.clone()).or_insert(0) += v;
        }
        Self { vars }
    }
}

impl Ord for PolyMonomial {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        other.total_degree().cmp(&self.total_degree()).then_with(|| {
            let mut it1 = self.vars.iter();
            let mut it2 = other.vars.iter();
            loop {
                match (it1.next(), it2.next()) {
                    (Some((k1, v1)), Some((k2, v2))) => {
                        let c = k1.cmp(k2).then_with(|| v2.cmp(v1));
                        if c != std::cmp::Ordering::Equal {
                            return c;
                        }
                    }
                    (Some(_), None) => return std::cmp::Ordering::Less,
                    (None, Some(_)) => return std::cmp::Ordering::Greater,
                    (None, None) => return std::cmp::Ordering::Equal,
                }
            }
        })
    }
}

impl PartialOrd for PolyMonomial {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

fn add_poly(l: &BTreeMap<PolyMonomial, ExactRat>, r: &BTreeMap<PolyMonomial, ExactRat>, sign: i128) -> Option<BTreeMap<PolyMonomial, ExactRat>> {
    let mut res = l.clone();
    for (m, &c) in r {
        let addend = ExactRat::new(c.num.checked_mul(sign)?, c.den)?;
        let next = match res.get(m) {
            Some(&cur) => cur.add(addend)?,
            None => addend,
        };
        if next.num == 0 {
            res.remove(m);
        } else {
            res.insert(m.clone(), next);
        }
    }
    Some(res)
}

fn mul_poly(l: &BTreeMap<PolyMonomial, ExactRat>, r: &BTreeMap<PolyMonomial, ExactRat>) -> Option<BTreeMap<PolyMonomial, ExactRat>> {
    if l.len().saturating_mul(r.len()) > 128 {
        return None;
    }
    let mut res: BTreeMap<PolyMonomial, ExactRat> = BTreeMap::new();
    for (ml, &cl) in l {
        for (mr, &cr) in r {
            let m = ml.mul(mr);
            let prod = cl.mul(cr)?;
            let next = match res.get(&m) {
                Some(&cur) => cur.add(prod)?,
                None => prod,
            };
            if next.num == 0 {
                res.remove(&m);
            } else {
                res.insert(m, next);
            }
        }
    }
    Some(res)
}

fn ast_to_poly(node: &AstNode) -> Option<BTreeMap<PolyMonomial, ExactRat>> {
    match node {
        AstNode::Num(v) => {
            let r = v.rat?;
            let mut m = BTreeMap::new();
            if r.num != 0 {
                m.insert(PolyMonomial::empty(), r);
            }
            Some(m)
        }
        AstNode::Var(name) => {
            if name == "i" {
                return None;
            }
            let mut vars = BTreeMap::new();
            vars.insert(name.clone(), 1);
            let mut m = BTreeMap::new();
            m.insert(PolyMonomial { vars }, ExactRat::from_int(1));
            Some(m)
        }
        AstNode::Neg(inner) => {
            let p = ast_to_poly(inner)?;
            let mut res = BTreeMap::new();
            for (k, v) in p {
                res.insert(k, ExactRat::new(v.num.checked_neg()?, v.den)?);
            }
            Some(res)
        }
        AstNode::Add(l, r) => add_poly(&ast_to_poly(l)?, &ast_to_poly(r)?, 1),
        AstNode::Sub(l, r) => add_poly(&ast_to_poly(l)?, &ast_to_poly(r)?, -1),
        AstNode::Mul(l, r) => mul_poly(&ast_to_poly(l)?, &ast_to_poly(r)?),
        AstNode::Div(l, r) => {
            let pl = ast_to_poly(l)?;
            let sr = try_exact_rat_ast(r)?;
            if sr.num == 0 {
                return None;
            }
            let mut res = BTreeMap::new();
            for (k, v) in pl {
                let c = v.div(sr)?;
                if c.num != 0 {
                    res.insert(k, c);
                }
            }
            Some(res)
        }
        AstNode::Pow(base, exp) => {
            let er = try_exact_rat_ast(exp)?;
            if er.den != 1 || er.num < 0 || er.num > 8 {
                return None;
            }
            let base_poly = ast_to_poly(base)?;
            let mut res = BTreeMap::new();
            res.insert(PolyMonomial::empty(), ExactRat::from_int(1));
            for _ in 0..er.num {
                res = mul_poly(&res, &base_poly)?;
            }
            Some(res)
        }
        _ => None,
    }
}

fn poly_to_ast(poly: &BTreeMap<PolyMonomial, ExactRat>) -> AstNode {
    if poly.is_empty() {
        return AstNode::Num(Value::from_int(0));
    }
    let mut result: Option<AstNode> = None;
    for (mon, &coeff) in poly {
        let neg = coeff.num < 0;
        let abs_c = ExactRat { num: coeff.num.abs(), den: coeff.den };
        let c_val = Value { c: Complex::real(abs_c.to_f64()), rat: Some(abs_c) };

        let mut mon_ast: Option<AstNode> = None;
        for (var, &exp) in &mon.vars {
            let factor = if exp == 1 {
                AstNode::Var(var.clone())
            } else {
                AstNode::Pow(
                    Box::new(AstNode::Var(var.clone())),
                    Box::new(AstNode::Num(Value::from_int(i128::from(exp)))),
                )
            };
            mon_ast = Some(match mon_ast {
                None => factor,
                Some(acc) => AstNode::Mul(Box::new(acc), Box::new(factor)),
            });
        }

        let term_ast = match mon_ast {
            None => AstNode::Num(c_val),
            Some(m) => {
                if abs_c.num == 1 && abs_c.den == 1 {
                    m
                } else {
                    AstNode::Mul(Box::new(AstNode::Num(c_val)), Box::new(m))
                }
            }
        };

        result = Some(match result {
            None => {
                if neg {
                    AstNode::Neg(Box::new(term_ast))
                } else {
                    term_ast
                }
            }
            Some(acc) => {
                if neg {
                    AstNode::Sub(Box::new(acc), Box::new(term_ast))
                } else {
                    AstNode::Add(Box::new(acc), Box::new(term_ast))
                }
            }
        });
    }
    result.unwrap_or(AstNode::Num(Value::from_int(0)))
}

fn contains_add_or_sub(node: &AstNode) -> bool {
    match node {
        AstNode::Add(_, _) | AstNode::Sub(_, _) => true,
        AstNode::Mul(l, r) | AstNode::Div(l, r) | AstNode::Pow(l, r) => {
            contains_add_or_sub(l) || contains_add_or_sub(r)
        }
        AstNode::Neg(inner) => contains_add_or_sub(inner),
        AstNode::Func(_, args) => args.iter().any(contains_add_or_sub),
        AstNode::Num(_) | AstNode::Var(_) => false,
    }
}

pub fn simplify_polynomial_ast(node: &AstNode) -> Option<AstNode> {
    if contains_add_or_sub(node) {
        if let Some(poly) = ast_to_poly(node) {
            let expanded = poly_to_ast(&poly);
            if poly.len() > 1 || &expanded != node {
                return Some(expanded);
            }
            return None;
        }
    }

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
                    let right = self.parse_unary()?;
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

        if self.eat('[') {
            let mut entries = Vec::new();
            self.skip_ws();
            if !self.eat(']') {
                loop {
                    entries.push(self.parse_expr()?);
                    self.skip_ws();
                    if self.eat(',') {
                        continue;
                    } else if self.eat(']') {
                        break;
                    } else {
                        return Err("Erwarte ',' oder ']' in Matrix".to_string());
                    }
                }
            }
            return Ok(AstNode::Func("[]".to_string(), entries));
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
            let mut s = String::new();
            let mut has_dot = false;
            let mut has_exp = false;
            while self.pos < self.chars.len() {
                let c = self.chars[self.pos];
                if c == '.' {
                    has_dot = true;
                    s.push(c);
                    self.pos += 1;
                } else if c == 'e' || c == 'E' {
                    let mut look = self.pos + 1;
                    while look < self.chars.len() && self.chars[look].is_whitespace() {
                        look += 1;
                    }
                    if look < self.chars.len()
                        && (self.chars[look].is_ascii_digit()
                            || self.chars[look] == '+'
                            || self.chars[look] == '-')
                    {
                        has_exp = true;
                        s.push(c);
                        self.pos = look;
                        if self.chars[self.pos] == '+' || self.chars[self.pos] == '-' {
                            s.push(self.chars[self.pos]);
                            self.pos += 1;
                            while self.pos < self.chars.len() && self.chars[self.pos].is_whitespace() {
                                self.pos += 1;
                            }
                        }
                        while self.pos < self.chars.len() && self.chars[self.pos].is_ascii_digit() {
                            s.push(self.chars[self.pos]);
                            self.pos += 1;
                        }
                    } else {
                        break;
                    }
                } else if c.is_ascii_digit() {
                    s.push(c);
                    self.pos += 1;
                } else {
                    break;
                }
            }
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
                    let right = self.parse_unary()?;
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
                    c: Complex::new(-v.c.re, -v.c.im),
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
                let a = require_integer_bound(self.eval_arg(&raw_args[2], None)?, "sum")?;
                let b = require_integer_bound(self.eval_arg(&raw_args[3], None)?, "sum")?;
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
                let a = require_integer_bound(self.eval_arg(&raw_args[2], None)?, "prod")?;
                let b = require_integer_bound(self.eval_arg(&raw_args[3], None)?, "prod")?;
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

                let prev_depth = INTEGRATION_DEPTH.with(|d| {
                    let prev = d.get();
                    d.set(prev + 1);
                    prev
                });
                struct IntegrationDepthGuard(usize);
                impl Drop for IntegrationDepthGuard {
                    fn drop(&mut self) {
                        INTEGRATION_DEPTH.with(|d| d.set(self.0));
                    }
                }
                let _guard = IntegrationDepthGuard(prev_depth);
                let has_inner_int = expr_str.contains("int(") || expr_str.contains("defint(");
                let has_trig = expr_str.contains("sin") || expr_str.contains("cos") || expr_str.contains("tan");
                let initial_subintervals = if prev_depth > 0 || has_inner_int || !has_trig {
                    16
                } else {
                    500
                };

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
                    initial_subintervals,
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
                let x0_val = self.eval_arg(&raw_args[2], None)?;
                if !x0_val.c.is_real() || !x0_val.c.re.is_finite() {
                    return Err("Ableitungsstelle muss eine endliche reelle Zahl sein".to_string());
                }
                let x0 = x0_val.c.re;
                let h = if x0.abs() > 1e10 {
                    1e-3 * x0.abs()
                } else if x0.abs() > 1e4 {
                    1e-3
                } else {
                    1e-4
                };
                let mut ov = self.overrides.cloned().unwrap_or_default();
                let mut eval_real_at = |x: f64| -> Result<f64, String> {
                    ov.insert(var.to_string(), Value::from_f64(x));
                    let val = self.eval_arg(&raw_args[0], Some(&ov))?;
                    if !val.c.is_real() || !val.c.re.is_finite() {
                        return Err("Funktion an der Stelle nicht reell differenzierbar".to_string());
                    }
                    Ok(val.c.re)
                };
                eval_real_at(x0)?;
                let f_p2 = eval_real_at(x0 + 2.0 * h)?;
                let f_p1 = eval_real_at(x0 + h)?;
                let f_m1 = eval_real_at(x0 - h)?;
                let f_m2 = eval_real_at(x0 - 2.0 * h)?;
                let deriv_val = (-f_p2 + 8.0 * f_p1 - 8.0 * f_m1 + f_m2) / (12.0 * h);
                return Ok(Value::from_f64(deriv_val));
            }
            "integral" => {
                return Err("Symbolische Integration nicht möglich".to_string());
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
            "asin" | "arcsin" => {
                if v0.c.is_real() && v0.c.re.abs() <= 1.0 {
                    Ok(Value::from_f64(mode.from_radians(v0.c.re.asin())))
                } else {
                    let z = v0.c;
                    let one = Complex::real(1.0);
                    let inside = Complex::i().mul(z).add(one.sub(z.mul(z)).sqrt());
                    let res = Complex::new(0.0, -1.0).mul(inside.ln());
                    Ok(Value::from_complex(res))
                }
            }
            "acos" | "arccos" => {
                if v0.c.is_real() && v0.c.re.abs() <= 1.0 {
                    Ok(Value::from_f64(mode.from_radians(v0.c.re.acos())))
                } else {
                    let z = v0.c;
                    let one = Complex::real(1.0);
                    let inside = Complex::i().mul(z).add(one.sub(z.mul(z)).sqrt());
                    let asin_z = Complex::new(0.0, -1.0).mul(inside.ln());
                    let res = Complex::real(std::f64::consts::FRAC_PI_2).sub(asin_z);
                    Ok(Value::from_complex(res))
                }
            }
            "atan" | "arctan" => {
                if v0.c.is_real() {
                    Ok(Value::from_f64(mode.from_radians(v0.c.re.atan())))
                } else {
                    let iz = Complex::i().mul(v0.c);
                    let one = Complex::real(1.0);
                    let quot = one.sub(iz).div(one.add(iz));
                    let res = Complex::new(0.0, 0.5).mul(quot.ln());
                    Ok(Value::from_complex(res))
                }
            }
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
            "asinh" | "arcsinh" => {
                let z = v0.c;
                Ok(Value::from_complex(
                    z.add(z.mul(z).add(Complex::real(1.0)).sqrt()).ln(),
                ))
            }
            "acosh" | "arccosh" => {
                let z = v0.c;
                Ok(Value::from_complex(
                    z.add(z.mul(z).sub(Complex::real(1.0)).sqrt()).ln(),
                ))
            }
            "atanh" | "arctanh" => {
                let z = v0.c;
                let one = Complex::real(1.0);
                Ok(Value::from_complex(
                    Complex::real(0.5).mul(one.add(z).div(one.sub(z)).ln()),
                ))
            }
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
            "floor" => {
                if !v0.c.is_real() || !v0.c.re.is_finite() {
                    return Err("floor erfordert endliche reelle Zahl".to_string());
                }
                if let Some(r) = v0.rat {
                    return Ok(Value::from_int(r.num.div_euclid(r.den)));
                }
                Ok(Value::from_int(v0.c.re.floor() as i128))
            }
            "ceil" => {
                if !v0.c.is_real() || !v0.c.re.is_finite() {
                    return Err("ceil erfordert endliche reelle Zahl".to_string());
                }
                if let Some(r) = v0.rat {
                    let q = r.num.div_euclid(r.den);
                    let rem = r.num.rem_euclid(r.den);
                    return Ok(Value::from_int(if rem > 0 { q + 1 } else { q }));
                }
                Ok(Value::from_int(v0.c.re.ceil() as i128))
            }
            "round" => {
                if !v0.c.is_real() || !v0.c.re.is_finite() {
                    return Err("round erfordert endliche reelle Zahl".to_string());
                }
                Ok(Value::from_int(v0.c.re.round() as i128))
            }
            "sign" | "sgn" => {
                if !v0.c.is_real() || !v0.c.re.is_finite() {
                    return Err("sign erfordert endliche reelle Zahl".to_string());
                }
                let s = if v0.c.re > 0.0 {
                    1
                } else if v0.c.re < 0.0 {
                    -1
                } else {
                    0
                };
                Ok(Value::from_int(s))
            }
            "binomial" | "nCr" => {
                let v1 = vals
                    .get(1)
                    .copied()
                    .ok_or_else(|| "binomial(n, k) benötigt 2 Argumente".to_string())?;
                if !v0.c.is_real() || !v1.c.is_real() {
                    return Err("binomial erfordert endliche reelle Argumente".to_string());
                }
                Ok(Value::from_f64(binomial_coeff(v0.c.re, v1.c.re)?))
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

fn require_integer_bound(v: Value, name: &str) -> Result<i64, String> {
    if !v.c.is_real() || !v.c.re.is_finite() {
        return Err(format!("{}-Grenze muss eine endliche ganze Zahl sein", name));
    }
    if let Some(r) = v.rat {
        if r.den != 1 {
            return Err(format!("{}-Grenze muss ganzzahlig sein", name));
        }
        return i64::try_from(r.num).map_err(|_| format!("{}-Grenze außerhalb des Bereichs", name));
    }
    let rounded = v.c.re.round();
    if (v.c.re - rounded).abs() > 1e-9 || rounded.abs() > 9e15 {
        return Err(format!("{}-Grenze muss ganzzahlig sein", name));
    }
    Ok(rounded as i64)
}

fn binomial_coeff(n: f64, k: f64) -> Result<f64, String> {
    let k_rounded = k.round();
    if !n.is_finite() || !k.is_finite() || (k - k_rounded).abs() > 1e-9 || k_rounded < 0.0 {
        return Err("binomial erfordert nicht-negatives ganzzahliges k".to_string());
    }
    let ku = k_rounded as u64;
    if ku == 0 {
        return Ok(1.0);
    }
    if ku > 100_000 {
        return Err("binomial: k zu groß".to_string());
    }
    let n_rounded = n.round();
    if (n - n_rounded).abs() <= 1e-9 && n_rounded >= 0.0 {
        let nu = n_rounded as u64;
        if ku > nu {
            return Ok(0.0);
        }
        let ku = ku.min(nu - ku);
        let mut res = 1.0_f64;
        for i in 1..=ku {
            res = res * ((nu - ku + i) as f64) / (i as f64);
            if !res.is_finite() {
                break;
            }
        }
        return Ok(res);
    }
    let mut res = 1.0_f64;
    for i in 1..=ku {
        res = res * (n - (ku as f64) + (i as f64)) / (i as f64);
        if !res.is_finite() {
            break;
        }
    }
    Ok(res)
}

fn try_exact_rat_ast(node: &AstNode) -> Option<ExactRat> {
    match node {
        AstNode::Num(v) => v.rat,
        AstNode::Neg(inner) => try_exact_rat_ast(inner).and_then(|r| ExactRat::new(-r.num, r.den)),
        AstNode::Add(a, b) => try_exact_rat_ast(a)?.add(try_exact_rat_ast(b)?),
        AstNode::Sub(a, b) => try_exact_rat_ast(a)?.sub(try_exact_rat_ast(b)?),
        AstNode::Mul(a, b) => try_exact_rat_ast(a)?.mul(try_exact_rat_ast(b)?),
        AstNode::Div(a, b) => try_exact_rat_ast(a)?.div(try_exact_rat_ast(b)?),
        _ => None,
    }
}

fn try_pi_coeff_ast(node: &AstNode) -> Option<ExactRat> {
    match node {
        AstNode::Var(n) if n == "pi" || n == "π" => Some(ExactRat::from_int(1)),
        AstNode::Neg(inner) => try_pi_coeff_ast(inner).and_then(|r| ExactRat::new(-r.num, r.den)),
        AstNode::Mul(l, r) => {
            if let (Some(a), Some(b)) = (try_exact_rat_ast(l), try_pi_coeff_ast(r)) {
                return a.mul(b);
            }
            if let (Some(a), Some(b)) = (try_pi_coeff_ast(l), try_exact_rat_ast(r)) {
                return a.mul(b);
            }
            None
        }
        AstNode::Div(l, r) => try_pi_coeff_ast(l)?.div(try_exact_rat_ast(r)?),
        _ => None,
    }
}

fn format_surd_rust(coeff: i64, radicand: i64, den: i64) -> String {
    if coeff == 0 {
        return "0".to_string();
    }
    let mut coeff = i128::from(coeff);
    let mut den = i128::from(den);
    if den < 0 {
        coeff = -coeff;
        den = -den;
    }
    let mut out = 1i64;
    let mut rem = radicand;
    let mut p = 2i64;
    // Any square factors left in the radicand preserve the value.
    while p <= 10_000 && p <= rem / p {
        while rem % (p * p) == 0 {
            out *= p;
            rem /= p * p;
        }
        p += 1;
    }
    let root = (rem as f64).sqrt() as i64;
    if i128::from(root) * i128::from(root) == i128::from(rem) {
        out *= root;
        rem = 1;
    }
    let mut num_coeff = coeff * i128::from(out);
    let g = gcd_i128(num_coeff.abs(), den);
    num_coeff /= g;
    den /= g;
    if rem == 1 {
        return if den == 1 {
            num_coeff.to_string()
        } else {
            format!("{} / {}", num_coeff, den)
        };
    }
    let sign = if num_coeff < 0 { "-" } else { "" };
    let abs_coeff = num_coeff.abs();
    let top = if abs_coeff == 1 {
        format!("√{}", rem)
    } else {
        format!("{}√{}", abs_coeff, rem)
    };
    if den == 1 {
        format!("{}{}", sign, top)
    } else {
        format!("{}{} / {}", sign, top, den)
    }
}
