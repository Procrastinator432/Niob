//! 100% Offline Symbolic, Rational, Complex, Matrix & Calculus Evaluation Engine
//! Implements the mathematical capabilities of Niob Scientific Calculator
//! (`Lc/a/a/e/d;`, `Lc/a/a/e/e;`, `Lc/a/a/e/f;`, `Lc/a/a/e/g;`, `Lc/a/a/e/h;`).

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, HashMap};
use std::fmt;
use std::ops::Deref;

const MAX_NESTING_DEPTH: usize = 64;

thread_local! {
    static INTEGRATION_DEPTH: Cell<usize> = const { Cell::new(0) };
    static EVAL_DEADLINE: Cell<Option<std::time::Instant>> = const { Cell::new(None) };
    static EVAL_DEPTH: Cell<usize> = const { Cell::new(0) };
}

fn check_eval_deadline() -> Result<(), String> {
    if let Some(deadline) = EVAL_DEADLINE.with(|d| d.get()) {
        if std::time::Instant::now() > deadline {
            return Err("Zeitlimit überschritten".to_string());
        }
    }
    Ok(())
}

fn check_bracket_depth(input: &str) -> Result<(), String> {
    let mut depth = 0usize;
    for ch in input.chars() {
        match ch {
            '(' | '[' => {
                depth += 1;
                if depth > MAX_NESTING_DEPTH {
                    return Err("Maximale Verschachtelungstiefe überschritten".to_string());
                }
            }
            ')' | ']' => {
                depth = depth.saturating_sub(1);
            }
            _ => {}
        }
    }
    Ok(())
}

fn decimal_to_exact_rat(v: f64) -> Option<ExactRat> {
    if !v.is_finite() || v.abs() > 1e15 {
        return None;
    }
    if (v - v.trunc()).abs() == 0.0 {
        return Some(ExactRat::from_int(v.trunc() as i128));
    }
    let mut ten_k: i128 = 1;
    for _ in 1..=8 {
        ten_k *= 10;
        let scaled = (v * (ten_k as f64)).round();
        if scaled.is_finite() && (scaled / (ten_k as f64) - v).abs() == 0.0 && scaled.abs() < 1e18 {
            return ExactRat::new(scaled as i128, ten_k);
        }
    }
    None
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BigUInt {
    limbs: Vec<u32>,
}

#[allow(dead_code)]
impl BigUInt {
    fn zero() -> Self {
        Self { limbs: Vec::new() }
    }

    fn one() -> Self {
        Self { limbs: vec![1] }
    }

    fn trim(&mut self) {
        while matches!(self.limbs.last(), Some(&0)) {
            self.limbs.pop();
        }
    }

    fn is_zero(&self) -> bool {
        self.limbs.is_empty()
    }

    fn is_one(&self) -> bool {
        self.limbs.len() == 1 && self.limbs[0] == 1
    }

    fn from_u128(mut v: u128) -> Self {
        let mut limbs = Vec::new();
        while v > 0 {
            limbs.push((v & 0xFFFF_FFFF) as u32);
            v >>= 32;
        }
        Self { limbs }
    }

    fn from_decimal_str(s: &str) -> Option<Self> {
        if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        let mut res = Self::zero();
        let bytes = s.as_bytes();
        let mut i = 0;
        while i < bytes.len() {
            let end = (i + 18).min(bytes.len());
            let chunk_val = s[i..end].parse::<u128>().ok()?;
            let pow10 = 10u128.pow((end - i) as u32);
            res = res.mul(&Self::from_u128(pow10)).add(&Self::from_u128(chunk_val));
            i = end;
        }
        Some(res)
    }

    fn to_u128(&self) -> Option<u128> {
        if self.limbs.len() > 4 {
            return None;
        }
        let mut v = 0u128;
        for (i, &limb) in self.limbs.iter().enumerate() {
            v |= u128::from(limb) << (i * 32);
        }
        Some(v)
    }

    fn bit_len(&self) -> usize {
        if let Some(&top) = self.limbs.last() {
            (self.limbs.len() - 1) * 32 + (32 - top.leading_zeros() as usize)
        } else {
            0
        }
    }

    fn cmp_mag(&self, other: &Self) -> std::cmp::Ordering {
        self.limbs.len().cmp(&other.limbs.len()).then_with(|| {
            for i in (0..self.limbs.len()).rev() {
                let ord = self.limbs[i].cmp(&other.limbs[i]);
                if ord != std::cmp::Ordering::Equal {
                    return ord;
                }
            }
            std::cmp::Ordering::Equal
        })
    }

    fn add(&self, other: &Self) -> Self {
        let n = self.limbs.len().max(other.limbs.len());
        let mut out = Vec::with_capacity(n + 1);
        let mut carry = 0u64;
        for i in 0..n {
            let a = u64::from(*self.limbs.get(i).unwrap_or(&0));
            let b = u64::from(*other.limbs.get(i).unwrap_or(&0));
            let sum = a + b + carry;
            out.push((sum & 0xFFFF_FFFF) as u32);
            carry = sum >> 32;
        }
        if carry > 0 {
            out.push(carry as u32);
        }
        let mut res = Self { limbs: out };
        res.trim();
        res
    }

    fn sub(&self, other: &Self) -> Self {
        let mut out = Vec::with_capacity(self.limbs.len());
        let mut borrow = 0i64;
        for i in 0..self.limbs.len() {
            let a = i64::from(self.limbs[i]);
            let b = i64::from(*other.limbs.get(i).unwrap_or(&0));
            let mut diff = a - b - borrow;
            if diff < 0 {
                diff += 1i64 << 32;
                borrow = 1;
            } else {
                borrow = 0;
            }
            out.push(diff as u32);
        }
        let mut res = Self { limbs: out };
        res.trim();
        res
    }

    fn mul(&self, other: &Self) -> Self {
        if self.is_zero() || other.is_zero() {
            return Self::zero();
        }
        let mut out = vec![0u32; self.limbs.len() + other.limbs.len()];
        for (i, &a) in self.limbs.iter().enumerate() {
            let mut carry = 0u64;
            for (j, &b) in other.limbs.iter().enumerate() {
                let prod = u64::from(a) * u64::from(b) + u64::from(out[i + j]) + carry;
                out[i + j] = (prod & 0xFFFF_FFFF) as u32;
                carry = prod >> 32;
            }
            if carry > 0 {
                out[i + other.limbs.len()] = carry as u32;
            }
        }
        let mut res = Self { limbs: out };
        res.trim();
        res
    }

    fn shl1_set_lsb(&mut self, bit: u32) {
        let mut carry = bit & 1;
        for limb in &mut self.limbs {
            let next_carry = *limb >> 31;
            *limb = (*limb << 1) | carry;
            carry = next_carry;
        }
        if carry != 0 {
            self.limbs.push(carry);
        }
    }

    fn div_rem(&self, d: &Self) -> (Self, Self) {
        if self.cmp_mag(d) == std::cmp::Ordering::Less {
            return (Self::zero(), self.clone());
        }
        if d.is_one() {
            return (self.clone(), Self::zero());
        }
        let mut q = vec![0u32; self.limbs.len()];
        let mut r = Self::zero();
        for bit in (0..self.bit_len()).rev() {
            let bit_val = (self.limbs[bit / 32] >> (bit % 32)) & 1;
            r.shl1_set_lsb(bit_val);
            if r.cmp_mag(d) != std::cmp::Ordering::Less {
                r = r.sub(d);
                q[bit / 32] |= 1u32 << (bit % 32);
            }
        }
        let mut q_res = Self { limbs: q };
        q_res.trim();
        (q_res, r)
    }

    fn gcd(mut a: Self, mut b: Self) -> Self {
        while !b.is_zero() {
            let (_, r) = a.div_rem(&b);
            a = b;
            b = r;
        }
        a
    }

    fn pow_u32(&self, mut exp: u32) -> Self {
        let mut res = Self::one();
        let mut base = self.clone();
        while exp > 0 {
            if (exp & 1) != 0 {
                res = res.mul(&base);
            }
            exp >>= 1;
            if exp > 0 {
                base = base.mul(&base);
            }
        }
        res
    }

    fn div_rem_u32(&self, d: u32) -> (Self, u32) {
        if d == 0 {
            panic!("division by zero");
        }
        let mut q = vec![0u32; self.limbs.len()];
        let mut rem = 0u64;
        let divisor = u64::from(d);
        for i in (0..self.limbs.len()).rev() {
            let cur = (rem << 32) | u64::from(self.limbs[i]);
            q[i] = (cur / divisor) as u32;
            rem = cur % divisor;
        }
        let mut res = Self { limbs: q };
        res.trim();
        (res, rem as u32)
    }

    fn to_decimal_string(&self) -> String {
        if self.is_zero() {
            return "0".to_string();
        }
        let mut cur = self.clone();
        let mut chunks: Vec<u32> = Vec::new();
        while !cur.is_zero() {
            let (q, r) = cur.div_rem_u32(1_000_000_000);
            chunks.push(r);
            cur = q;
        }
        let mut out = String::with_capacity(chunks.len() * 9);
        if let Some(&top) = chunks.last() {
            out.push_str(&top.to_string());
        }
        for &chunk in chunks.iter().rev().skip(1) {
            use std::fmt::Write;
            let _ = write!(&mut out, "{:09}", chunk);
        }
        out
    }

    fn to_f64(&self) -> f64 {
        if self.is_zero() {
            return 0.0;
        }
        let start = self.limbs.len().saturating_sub(3);
        let mut acc = 0.0f64;
        for &limb in self.limbs[start..].iter().rev() {
            acc = acc * 4294967296.0 + f64::from(limb);
        }
        acc * 2.0f64.powi((start * 32) as i32)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BigInt {
    neg: bool,
    mag: BigUInt,
}

#[allow(dead_code)]
impl BigInt {
    fn from_decimal_str(s: &str) -> Option<Self> {
        let mag = BigUInt::from_decimal_str(s)?;
        Some(Self::from_u32_parts(false, mag))
    }

    fn from_i128(v: i128) -> Self {
        if v == 0 {
            Self { neg: false, mag: BigUInt::zero() }
        } else {
            Self { neg: v < 0, mag: BigUInt::from_u128(v.unsigned_abs()) }
        }
    }

    fn from_u32_parts(neg: bool, mag: BigUInt) -> Self {
        if mag.is_zero() {
            Self { neg: false, mag }
        } else {
            Self { neg, mag }
        }
    }

    fn negate(&self) -> Self {
        Self::from_u32_parts(!self.neg, self.mag.clone())
    }

    fn add(&self, o: &Self) -> Self {
        if self.neg == o.neg {
            Self::from_u32_parts(self.neg, self.mag.add(&o.mag))
        } else {
            match self.mag.cmp_mag(&o.mag) {
                std::cmp::Ordering::Equal => Self::from_i128(0),
                std::cmp::Ordering::Greater => Self::from_u32_parts(self.neg, self.mag.sub(&o.mag)),
                std::cmp::Ordering::Less => Self::from_u32_parts(o.neg, o.mag.sub(&self.mag)),
            }
        }
    }

    fn sub(&self, o: &Self) -> Self {
        self.add(&o.negate())
    }

    fn mul(&self, o: &Self) -> Self {
        Self::from_u32_parts(self.neg ^ o.neg, self.mag.mul(&o.mag))
    }

    fn to_i128(&self) -> Option<i128> {
        let u = self.mag.to_u128()?;
        if self.neg {
            if u == (1u128 << 127) {
                Some(i128::MIN)
            } else if u < (1u128 << 127) {
                Some(-(u as i128))
            } else {
                None
            }
        } else {
            i128::try_from(u).ok()
        }
    }

    fn to_decimal_string(&self) -> String {
        if self.mag.is_zero() {
            return "0".to_string();
        }
        let s = self.mag.to_decimal_string();
        if self.neg {
            format!("-{}", s)
        } else {
            s
        }
    }

    fn to_f64(&self) -> f64 {
        let v = self.mag.to_f64();
        if self.neg && v != 0.0 {
            -v
        } else {
            v
        }
    }

    fn rem_mod(&self, rhs: &Self, always_positive: bool) -> Result<Self, String> {
        if rhs.mag.is_zero() {
            return Err("Modulo durch Null".to_string());
        }
        let (_, r_mag) = self.mag.div_rem(&rhs.mag);
        let mut rem = Self::from_u32_parts(self.neg, r_mag);
        if always_positive && rem.neg {
            rem = rem.add(&Self::from_u32_parts(false, rhs.mag.clone()));
        }
        Ok(rem)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct BigRat {
    num: BigInt,
    den: BigUInt,
}

impl BigRat {
    fn from_exact_rat(r: ExactRat) -> Self {
        Self {
            num: BigInt::from_i128(r.num),
            den: BigUInt::from_u128(r.den.unsigned_abs()),
        }
    }

    fn reduce(num: BigInt, den: BigUInt) -> Result<Self, String> {
        if den.is_zero() {
            return Err("Division durch Null".to_string());
        }
        if num.mag.is_zero() {
            return Ok(Self {
                num: BigInt::from_i128(0),
                den: BigUInt::one(),
            });
        }
        let g = BigUInt::gcd(num.mag.clone(), den.clone());
        if g.is_one() {
            Ok(Self { num, den })
        } else {
            let (qn, _) = num.mag.div_rem(&g);
            let (qd, _) = den.div_rem(&g);
            Ok(Self {
                num: BigInt::from_u32_parts(num.neg, qn),
                den: qd,
            })
        }
    }

    fn negate(&self) -> Self {
        Self { num: self.num.negate(), den: self.den.clone() }
    }

    fn add(&self, o: &Self) -> Result<Self, String> {
        let num = self.num.mul(&BigInt::from_u32_parts(false, o.den.clone()))
            .add(&o.num.mul(&BigInt::from_u32_parts(false, self.den.clone())));
        let den = self.den.mul(&o.den);
        Self::reduce(num, den)
    }

    fn sub(&self, o: &Self) -> Result<Self, String> {
        self.add(&o.negate())
    }

    fn mul(&self, o: &Self) -> Result<Self, String> {
        Self::reduce(self.num.mul(&o.num), self.den.mul(&o.den))
    }

    fn div(&self, o: &Self) -> Result<Self, String> {
        if o.num.mag.is_zero() {
            return Err("Division durch Null".to_string());
        }
        let neg = self.num.neg ^ o.num.neg;
        Self::reduce(
            BigInt::from_u32_parts(neg, self.num.mag.mul(&o.den)),
            self.den.mul(&o.num.mag),
        )
    }

    fn pow(&self, p: i32) -> Result<Self, String> {
        if p == 0 {
            return Ok(Self { num: BigInt::from_i128(1), den: BigUInt::one() });
        }
        if p < 0 {
            if self.num.mag.is_zero() {
                return Err("Division durch Null".to_string());
            }
            let ap = p.unsigned_abs();
            let neg = self.num.neg && (ap % 2 == 1);
            let num = BigInt::from_u32_parts(neg, self.den.pow_u32(ap));
            let den = self.num.mag.pow_u32(ap);
            return Self::reduce(num, den);
        }
        let ap = p as u32;
        let neg = self.num.neg && (ap % 2 == 1);
        let num = BigInt::from_u32_parts(neg, self.num.mag.pow_u32(ap));
        let den = self.den.pow_u32(ap);
        Self::reduce(num, den)
    }

    fn to_exact_rat(&self) -> Option<ExactRat> {
        let num = self.num.to_i128()?;
        let den = BigInt::from_u32_parts(false, self.den.clone()).to_i128()?;
        ExactRat::new(num, den)
    }

    fn to_exact_string(&self) -> String {
        if self.den.is_one() {
            self.num.to_decimal_string()
        } else {
            format!("{} / {}", self.num.to_decimal_string(), self.den.to_decimal_string())
        }
    }

    fn to_f64(&self) -> f64 {
        if self.num.mag.is_zero() {
            return 0.0;
        }
        let n = self.num.to_f64();
        let d = self.den.to_f64();
        if n.is_finite() && d.is_finite() && d != 0.0 {
            return n / d;
        }
        let nb = self.num.mag.bit_len() as i32;
        let db = self.den.bit_len() as i32;
        let top_scaled = |mag: &BigUInt| -> f64 {
            let start = mag.limbs.len().saturating_sub(3);
            let mut acc = 0.0f64;
            for &limb in mag.limbs[start..].iter().rev() {
                acc = acc * 4294967296.0 + f64::from(limb);
            }
            let norm_bits = mag.bit_len() as i32 - (start * 32) as i32;
            acc * 2.0f64.powi(-norm_bits)
        };
        let mant_ratio = top_scaled(&self.num.mag) / top_scaled(&self.den);
        let res = mant_ratio * 2.0f64.powi(nb - db);
        if self.num.neg { -res } else { res }
    }
}

fn try_eval_bigrat_ast(node: &AstNode, engine: &MathEngine) -> Result<Option<BigRat>, String> {
    check_eval_deadline()?;
    match node {
        AstNode::Num(v) => Ok(v.rat.map(BigRat::from_exact_rat)),
        AstNode::BigInt(bi) => Ok(Some(BigRat {
            num: bi.clone(),
            den: BigUInt::one(),
        })),
        AstNode::Var(name) => {
            if name == "ans" || name == "ANS" {
                Ok(engine.ans.rat.map(BigRat::from_exact_rat))
            } else if USER_VARIABLES.contains(&name.as_str()) {
                Ok(engine.variables.get(name).and_then(|v| v.rat).map(BigRat::from_exact_rat))
            } else {
                Ok(None)
            }
        }
        AstNode::Neg(inner) => Ok(try_eval_bigrat_ast(inner, engine)?.map(|r| r.negate())),
        AstNode::Add(l, r) => match (try_eval_bigrat_ast(l, engine)?, try_eval_bigrat_ast(r, engine)?) {
            (Some(a), Some(b)) => Ok(Some(a.add(&b)?)),
            _ => Ok(None),
        },
        AstNode::Sub(l, r) => match (try_eval_bigrat_ast(l, engine)?, try_eval_bigrat_ast(r, engine)?) {
            (Some(a), Some(b)) => Ok(Some(a.sub(&b)?)),
            _ => Ok(None),
        },
        AstNode::Mul(l, r) => match (try_eval_bigrat_ast(l, engine)?, try_eval_bigrat_ast(r, engine)?) {
            (Some(a), Some(b)) => Ok(Some(a.mul(&b)?)),
            _ => Ok(None),
        },
        AstNode::Div(l, r) => match (try_eval_bigrat_ast(l, engine)?, try_eval_bigrat_ast(r, engine)?) {
            (Some(a), Some(b)) => Ok(Some(a.div(&b)?)),
            _ => Ok(None),
        },
        AstNode::Pow(base, exp) => match (try_eval_bigrat_ast(base, engine)?, try_eval_bigrat_ast(exp, engine)?) {
            (Some(b), Some(e)) if e.den.is_one() => {
                let Some(p) = e.num.to_i128() else { return Ok(None); };
                let ap = p.unsigned_abs();
                if ap > 10_000 || (p < 0 && ap > 1000) || (!b.den.is_one() && ap > 1000) {
                    return Ok(None);
                }
                let est_bits = b.num.mag.bit_len().max(b.den.bit_len()).saturating_mul(ap as usize);
                let max_bits = if b.den.is_one() { 65536 } else { 32768 };
                if est_bits <= max_bits {
                    Ok(Some(b.pow(p as i32)?))
                } else {
                    Ok(None)
                }
            }
            _ => Ok(None),
        },
        AstNode::Func(name, args) if name == "mod" && args.len() == 2 => {
            if let (Some(a), Some(b)) = (
                try_eval_bigrat_ast(&args[0], engine)?,
                try_eval_bigrat_ast(&args[1], engine)?,
            ) {
                if a.den.is_one() && b.den.is_one() {
                    let rem = a.num.rem_mod(&b.num, engine.modulo_always_positive)?;
                    return Ok(Some(BigRat {
                        num: rem,
                        den: BigUInt::one(),
                    }));
                }
            }
            Ok(None)
        }
        AstNode::Func(name, args) if (name == "factorial" || name == "fact") && args.len() == 1 => {
            if let Some(arg) = try_eval_bigrat_ast(&args[0], engine)? {
                if arg.den.is_one() && !arg.num.neg {
                    if let Some(k) = arg.num.to_i128() {
                        if k <= 1000 {
                            let mut prod = BigUInt::one();
                            for i in 2..=(k as u128) {
                                prod = prod.mul(&BigUInt::from_u128(i));
                            }
                            return Ok(Some(BigRat {
                                num: BigInt::from_u32_parts(false, prod),
                                den: BigUInt::one(),
                            }));
                        }
                    }
                }
            }
            Ok(None)
        }
        _ => Ok(None),
    }
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
            let mut exp = abs_v.log10().floor() as i32;
            let mut mantissa = if exp < -290 {
                (val * 1e200) * 10f64.powi(-(exp + 200))
            } else {
                val / 10f64.powi(exp)
            };
            if mantissa.abs() >= 10.0 {
                mantissa /= 10.0;
                exp += 1;
            } else if mantissa.abs() > 0.0 && mantissa.abs() < 1.0 {
                mantissa *= 10.0;
                exp -= 1;
            }
            let m_str = trim_float(mantissa, 11);
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
        if o.re == 0.0 && o.im == 0.0 {
            return Self::real(f64::NAN);
        }
        if o.re.abs() >= o.im.abs() {
            let r = o.im / o.re;
            let den = o.re + o.im * r;
            Self::new((self.re + self.im * r) / den, (self.im - self.re * r) / den)
        } else {
            let r = o.re / o.im;
            let den = o.re * r + o.im;
            Self::new((self.re * r + self.im) / den, (self.im * r - self.re) / den)
        }
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
        if self.re.abs() > 20.0 {
            return Self::real(self.re.signum());
        }
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
    pub matrix_variables: HashMap<String, Vec<Vec<Value>>>,
    pub ans: Value,
    pub matrix_ans: Option<Vec<Vec<Value>>>,
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
            matrix_variables: HashMap::new(),
            ans: Value::from_int(0),
            matrix_ans: None,
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
            self.matrix_ans = None;
        }
        Ok(res)
    }

    #[allow(dead_code)]
    pub fn evaluate_with_timeout(&mut self, raw_input: &str, allow_mutation: bool, timeout_ms: u64) -> Result<EvalOutput, String> {
        let prev = EVAL_DEADLINE.with(|d| {
            let old = d.get();
            d.set(Some(std::time::Instant::now() + std::time::Duration::from_millis(timeout_ms)));
            old
        });
        struct DeadlineGuard(Option<std::time::Instant>);
        impl Drop for DeadlineGuard {
            fn drop(&mut self) {
                EVAL_DEADLINE.with(|d| d.set(self.0));
            }
        }
        let _guard = DeadlineGuard(prev);
        self.evaluate_preview(raw_input, allow_mutation)
    }

    pub fn evaluate_preview(&mut self, raw_input: &str, allow_mutation: bool) -> Result<EvalOutput, String> {
        let prev_deadline = EVAL_DEADLINE.with(|d| d.get());
        struct DefaultDeadlineGuard(Option<std::time::Instant>, bool);
        impl Drop for DefaultDeadlineGuard {
            fn drop(&mut self) {
                if self.1 {
                    EVAL_DEADLINE.with(|d| d.set(self.0));
                }
            }
        }
        let installed = prev_deadline.is_none();
        if installed {
            let ms = crate::constants::SYMBOLIC_WATCHDOG_TIMEOUT_MS as u64;
            EVAL_DEADLINE.with(|d| {
                d.set(Some(std::time::Instant::now() + std::time::Duration::from_millis(ms)));
            });
        }
        let _deadline_guard = DefaultDeadlineGuard(prev_deadline, installed);
        self.last_integration_report.replace(None);
        let trimmed = raw_input.trim();
        if trimmed.is_empty() {
            return Err("Leerer Ausdruck".to_string());
        }
        check_bracket_depth(trimmed)?;

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
                    self.matrix_variables.remove(vname);
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
            let rhs_trimmed = rhs.trim();
            let rhs_ast = AstParser::parse(rhs_trimmed).ok();
            if rhs_trimmed.starts_with("inv(")
                || rhs_trimmed.starts_with("trans(")
                || rhs_trimmed.starts_with("transpose(")
                || rhs_trimmed.starts_with("[[")
                || rhs_ast.as_ref().is_some_and(|ast| self.contains_matrix_expression(ast))
            {
                let mat = self.eval_matrix_expr(rhs_trimmed)?;
                let decimal = Self::format_matrix(&mat, |value| self.format_value_decimal(value));
                let exact = Self::format_matrix(&mat, |value| {
                    Self::format_exact_real(value).unwrap_or_else(|| self.format_value_decimal(value))
                });
                let reusable = Self::format_matrix(&mat, Value::reusable_expression);
                if allow_mutation {
                    self.matrix_variables.insert(var_name.to_string(), mat.clone());
                    self.variables.remove(var_name);
                    self.matrix_ans = Some(mat);
                }
                return Ok(EvalOutput {
                    exact_text: format!("{} := {}", var_name, exact),
                    decimal_text: format!("{} := {}", var_name, decimal),
                    has_distinct_exact: exact != decimal,
                    numeric_value: None,
                    val: None,
                    integration_report: None,
                    matrix_expression: Some(reusable),
                });
            }
            let rhs_out = self.evaluate_preview(rhs, false)?;
            if let Some(val) = rhs_out.val {
                if allow_mutation {
                    self.variables.insert(var_name.to_string(), val);
                    self.matrix_variables.remove(var_name);
                    self.matrix_ans = None;
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
            || parsed_ast.as_ref().is_some_and(|ast| self.contains_matrix_expression(ast))
        {
            let (out, mat_opt) = self.evaluate_matrix_expression_with_value(trimmed)?;
            if allow_mutation {
                if let Some(mat) = mat_opt {
                    self.matrix_ans = Some(mat);
                }
            }
            return Ok(out);
        }

        // Check symbolic calculus first (indefinite integral, symbolic derivative) on AST
        if let Some(ref ast) = parsed_ast {
            if let Some(sym_out) = self.try_evaluate_symbolic_calculus(ast) {
                return Ok(sym_out);
            }
        }

        // Exact arbitrary-precision rational evaluation fallback (handles e.g. 2^127+1-2^127, 2^200-1, 100!/98!, (3^300) mod 1000007)
        if let Some(ref ast) = parsed_ast {
            if let Some(br) = try_eval_bigrat_ast(ast, self)? {
                if let Some(er) = br.to_exact_rat() {
                    let val = Value {
                        c: Complex::real(er.to_f64()),
                        rat: Some(er),
                    };
                    if val.c.re.is_finite() {
                        let dec_str = self.format_value_decimal(val);
                        let exact_opt = self.try_format_exact_val(trimmed, val);
                        let (exact_text, has_distinct_exact) = match exact_opt {
                            Some(ex) if ex != dec_str => (ex, true),
                            _ => (dec_str.clone(), false),
                        };
                        return Ok(EvalOutput {
                            exact_text,
                            decimal_text: dec_str,
                            has_distinct_exact,
                            numeric_value: Some(val.c),
                            val: Some(val),
                            integration_report: None,
                            matrix_expression: None,
                        });
                    }
                } else {
                    let approx = br.to_f64();
                    let exact_str = br.to_exact_string();
                    let dec_str = if br.den.is_one() && exact_str.len() <= 120 {
                        exact_str.clone()
                    } else if approx.is_finite() {
                        self.number_format.format_f64(approx)
                    } else if br.den.is_one() {
                        let (neg, mag) = if let Some(rest) = exact_str.strip_prefix('-') {
                            (true, rest)
                        } else {
                            (false, exact_str.as_str())
                        };
                        if mag.len() <= 1 {
                            exact_str.clone()
                        } else {
                            let exp = (mag.len() - 1) as i32;
                            let lead = &mag[0..1];
                            let frac_end = mag.len().min(12);
                            let frac = mag[1..frac_end].trim_end_matches('0');
                            let raw_m = if frac.is_empty() {
                                format!("{}{}", if neg { "-" } else { "" }, lead)
                            } else {
                                format!("{}{}.{}", if neg { "-" } else { "" }, lead, frac)
                            };
                            let m_formatted = self.number_format.apply_separators(&raw_m);
                            format!("{} × 10{}", m_formatted, to_superscript(exp))
                        }
                    } else {
                        exact_str.clone()
                    };
                    let has_distinct_exact = exact_str != dec_str;
                    return Ok(EvalOutput {
                        exact_text: exact_str,
                        decimal_text: dec_str,
                        has_distinct_exact,
                        numeric_value: approx.is_finite().then(|| Complex::real(approx)),
                        val: approx.is_finite().then(|| Value::from_f64(approx)),
                        integration_report: None,
                        matrix_expression: None,
                    });
                }
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
                    if let Some(res_ast) = args[0].integrate_with_mode(var, self.angle_mode) {
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

    fn evaluate_matrix_expression_with_value(&self, expr: &str) -> Result<(EvalOutput, Option<Vec<Vec<Value>>>), String> {
        let trimmed = expr.trim();
        if let Ok(ast) = AstParser::parse(trimmed) {
            if let MatrixOrScalar::Scalar(val) = self.eval_matrix_or_scalar_ast(&ast)? {
                let dec = self.format_value_decimal(val);
                let exact = Self::format_exact_real(val).unwrap_or_else(|| dec.clone());
                return Ok((EvalOutput {
                    has_distinct_exact: exact != dec,
                    exact_text: exact,
                    decimal_text: dec,
                    numeric_value: Some(val.c),
                    val: Some(val),
                    integration_report: None,
                    matrix_expression: None,
                }, None));
            }
        }

        if let Some(inner) = trimmed.strip_prefix("det(").and_then(|s| s.strip_suffix(')')) {
            let mat = self.eval_matrix_expr(inner)?;
            let det = matrix_det(&mat)?;
            let dec = self.format_value_decimal(det);
            let exact = Self::format_exact_real(det).unwrap_or_else(|| dec.clone());
            return Ok((EvalOutput {
                has_distinct_exact: exact != dec,
                exact_text: exact,
                decimal_text: dec,
                numeric_value: Some(det.c),
                val: Some(det),
                integration_report: None,
                matrix_expression: None,
            }, None));
        }

        let mat = self.eval_matrix_expr(trimmed)?;
        let decimal = Self::format_matrix(&mat, |value| self.format_value_decimal(value));
        let exact = Self::format_matrix(&mat, |value| {
            Self::format_exact_real(value).unwrap_or_else(|| self.format_value_decimal(value))
        });
        let reusable = Self::format_matrix(&mat, Value::reusable_expression);
        Ok((EvalOutput {
            has_distinct_exact: exact != decimal,
            exact_text: exact,
            decimal_text: decimal,
            numeric_value: None,
            val: None,
            integration_report: None,
            matrix_expression: Some(reusable),
        }, Some(mat)))
    }

    fn format_exact_real(value: Value) -> Option<String> {
        let rat = value.rat?;
        Some(if rat.den == 1 { rat.num.to_string() } else { format!("{} / {}", rat.num, rat.den) })
    }

    fn is_entire_antiderivative(node: &AstNode, var: &str) -> bool {
        match node {
            AstNode::Num(_) | AstNode::BigInt(_) | AstNode::Var(_) => true,
            AstNode::Neg(inner) => Self::is_entire_antiderivative(inner, var),
            AstNode::Add(a, b) | AstNode::Sub(a, b) | AstNode::Mul(a, b) => {
                Self::is_entire_antiderivative(a, var) && Self::is_entire_antiderivative(b, var)
            }
            AstNode::Div(a, b) => {
                !b.contains_var(var)
                    && Self::is_entire_antiderivative(a, var)
                    && Self::is_entire_antiderivative(b, var)
            }
            AstNode::Pow(base, exp) => {
                if exp.contains_var(var) || !Self::is_entire_antiderivative(exp, var) {
                    return false;
                }
                if !base.contains_var(var) {
                    return Self::is_entire_antiderivative(base, var);
                }
                let Some(ev) = exp.const_value() else {
                    return false;
                };
                ev.rat.map_or(false, |r| r.den == 1 && r.num > 0)
                    && Self::is_entire_antiderivative(base, var)
            }
            AstNode::Func(name, args) => {
                args.len() == 1
                    && matches!(name.as_str(), "sin" | "cos" | "sinh" | "cosh" | "exp" | "atan" | "arctan")
                    && Self::is_entire_antiderivative(&args[0], var)
            }
        }
    }

    fn is_antiderivative_valid_on(
        &self,
        node: &AstNode,
        var: &str,
        lo: f64,
        hi: f64,
        ov: Option<&HashMap<String, Value>>,
    ) -> bool {
        let eval_at = |target: &AstNode, x: f64| -> Option<f64> {
            let mut local = ov.cloned().unwrap_or_default();
            local.insert(var.to_string(), Value::from_f64(x));
            let val = self.eval_ast_scalar_with_overrides(target, Some(&local)).ok()?;
            if val.c.is_real() && val.c.re.is_finite() {
                Some(val.c.re)
            } else {
                None
            }
        };
        match node {
            AstNode::Num(_) | AstNode::BigInt(_) | AstNode::Var(_) => true,
            AstNode::Neg(inner) => self.is_antiderivative_valid_on(inner, var, lo, hi, ov),
            AstNode::Add(a, b) | AstNode::Sub(a, b) | AstNode::Mul(a, b) => {
                self.is_antiderivative_valid_on(a, var, lo, hi, ov)
                    && self.is_antiderivative_valid_on(b, var, lo, hi, ov)
            }
            AstNode::Div(a, b) => {
                !b.contains_var(var)
                    && self.is_antiderivative_valid_on(a, var, lo, hi, ov)
                    && self.is_antiderivative_valid_on(b, var, lo, hi, ov)
            }
            AstNode::Pow(base, exp) => {
                if Self::is_entire_antiderivative(node, var) {
                    return true;
                }
                if exp.contains_var(var) || !self.is_antiderivative_valid_on(exp, var, lo, hi, ov) {
                    return false;
                }
                if !base.contains_var(var) {
                    return self.is_antiderivative_valid_on(base, var, lo, hi, ov);
                }
                if let Ok(ev) = self.eval_ast_scalar_with_overrides(exp, ov) {
                    if ev.c.is_real() && ev.c.re > 0.0 && ev.c.re.is_finite() && base.match_linear(var).is_some() {
                        if let (Some(v_lo), Some(v_hi)) = (eval_at(base, lo), eval_at(base, hi)) {
                            return v_lo >= 0.0 && v_hi >= 0.0;
                        }
                    }
                }
                false
            }
            AstNode::Func(name, args) => {
                if args.len() != 1 {
                    return false;
                }
                match name.as_str() {
                    "sin" | "cos" | "sinh" | "cosh" | "exp" | "atan" | "arctan" => {
                        self.is_antiderivative_valid_on(&args[0], var, lo, hi, ov)
                    }
                    "asin" | "arcsin" | "acos" | "arccos" => {
                        if args[0].match_linear(var).is_some() {
                            if let (Some(v_lo), Some(v_hi)) = (eval_at(&args[0], lo), eval_at(&args[0], hi)) {
                                return v_lo.abs() <= 1.0 && v_hi.abs() <= 1.0;
                            }
                        }
                        false
                    }
                    "sqrt" => {
                        if args[0].match_linear(var).is_some() {
                            if let (Some(v_lo), Some(v_hi)) = (eval_at(&args[0], lo), eval_at(&args[0], hi)) {
                                return v_lo >= 0.0 && v_hi >= 0.0;
                            }
                        } else if let Some((qa, _, _)) = args[0].match_quadratic(var) {
                            if let Ok(qav) = self.eval_ast_scalar_with_overrides(&qa, ov) {
                                if qav.c.is_real() && qav.c.re <= 0.0 {
                                    if let (Some(v_lo), Some(v_hi)) = (eval_at(&args[0], lo), eval_at(&args[0], hi)) {
                                        return v_lo >= 0.0 && v_hi >= 0.0;
                                    }
                                }
                            }
                        }
                        false
                    }
                    _ => false,
                }
            }
        }
    }

    fn eval_ast_scalar_with_overrides(
        &self,
        node: &AstNode,
        ov: Option<&HashMap<String, Value>>,
    ) -> Result<Value, String> {
        match node {
            AstNode::Num(v) => Ok(*v),
            AstNode::BigInt(bi) => Ok(Value::from_f64(bi.to_f64())),
            AstNode::Var(v) => {
                let p = ExprParser::new(v, self, ov);
                p.resolve_identifier(v)
            }
            AstNode::Neg(a) => {
                let v = self.eval_ast_scalar_with_overrides(a, ov)?;
                Ok(Value {
                    c: Complex::new(-v.c.re, -v.c.im),
                    rat: v.rat.and_then(|r| Some(ExactRat { num: r.num.checked_neg()?, den: r.den })),
                })
            }
            AstNode::Add(a, b) => Ok(self
                .eval_ast_scalar_with_overrides(a, ov)?
                .add(self.eval_ast_scalar_with_overrides(b, ov)?)),
            AstNode::Sub(a, b) => Ok(self
                .eval_ast_scalar_with_overrides(a, ov)?
                .sub(self.eval_ast_scalar_with_overrides(b, ov)?)),
            AstNode::Mul(a, b) => Ok(self
                .eval_ast_scalar_with_overrides(a, ov)?
                .mul(self.eval_ast_scalar_with_overrides(b, ov)?)),
            AstNode::Div(a, b) => self
                .eval_ast_scalar_with_overrides(a, ov)?
                .div(self.eval_ast_scalar_with_overrides(b, ov)?),
            AstNode::Pow(a, b) => Ok(self
                .eval_ast_scalar_with_overrides(a, ov)?
                .pow(self.eval_ast_scalar_with_overrides(b, ov)?)),
            AstNode::Func(name, args) if name == "det" && args.len() == 1 && ov.is_none() => {
                let mat = self.eval_matrix_ast(&args[0])?;
                matrix_det(&mat)
            }
            AstNode::Func(name, args) if matches!(name.as_str(), "d" | "deriv" | "diff") && args.len() == 2 => {
                let var_name = match &args[1] {
                    AstNode::Var(v) => v.clone(),
                    other => other.to_display_string(),
                };
                if let Some(d_ast) = args[0].diff_with_mode(&var_name, self.angle_mode) {
                    return self.eval_ast_scalar_with_overrides(&d_ast.simplify(), ov);
                }
                let expr = node.to_display_string();
                let mut p = ExprParser::new(&expr, self, ov);
                let v = p.parse_expr()?;
                p.skip_ws();
                if p.pos < p.chars.len() {
                    return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
                }
                Ok(v)
            }
            AstNode::Func(name, args) if args.len() == 1 => {
                let v0 = self.eval_ast_scalar_with_overrides(&args[0], ov)?;
                match name.as_str() {
                    "sin" => Ok(if v0.c.is_real() {
                        Value::from_f64(self.angle_mode.to_radians(v0.c.re).sin())
                    } else {
                        Value::from_complex(v0.c.sin())
                    }),
                    "cos" => Ok(if v0.c.is_real() {
                        Value::from_f64(self.angle_mode.to_radians(v0.c.re).cos())
                    } else {
                        Value::from_complex(v0.c.cos())
                    }),
                    "tan" => Ok(if v0.c.is_real() {
                        Value::from_f64(self.angle_mode.to_radians(v0.c.re).tan())
                    } else {
                        Value::from_complex(v0.c.tan())
                    }),
                    "asin" | "arcsin" => {
                        if v0.c.is_real() && v0.c.re.abs() <= 1.0 {
                            Ok(Value::from_f64(self.angle_mode.from_radians(v0.c.re.asin())))
                        } else {
                            let z = v0.c;
                            let one = Complex::real(1.0);
                            let inside = Complex::i().mul(z).add(one.sub(z.mul(z)).sqrt());
                            Ok(Value::from_complex(Complex::new(0.0, -1.0).mul(inside.ln())))
                        }
                    }
                    "acos" | "arccos" => {
                        if v0.c.is_real() && v0.c.re.abs() <= 1.0 {
                            Ok(Value::from_f64(self.angle_mode.from_radians(v0.c.re.acos())))
                        } else {
                            let z = v0.c;
                            let one = Complex::real(1.0);
                            let inside = Complex::i().mul(z).add(one.sub(z.mul(z)).sqrt());
                            let asin_z = Complex::new(0.0, -1.0).mul(inside.ln());
                            Ok(Value::from_complex(Complex::real(std::f64::consts::FRAC_PI_2).sub(asin_z)))
                        }
                    }
                    "atan" | "arctan" => {
                        if v0.c.is_real() {
                            Ok(Value::from_f64(self.angle_mode.from_radians(v0.c.re.atan())))
                        } else {
                            let iz = Complex::i().mul(v0.c);
                            let one = Complex::real(1.0);
                            let quot = one.sub(iz).div(one.add(iz));
                            Ok(Value::from_complex(Complex::new(0.0, 0.5).mul(quot.ln())))
                        }
                    }
                    "sinh" => Ok(if v0.c.is_real() {
                        Value::from_f64(v0.c.re.sinh())
                    } else {
                        Value::from_complex(v0.c.sinh())
                    }),
                    "cosh" => Ok(if v0.c.is_real() {
                        Value::from_f64(v0.c.re.cosh())
                    } else {
                        Value::from_complex(v0.c.cosh())
                    }),
                    "tanh" => Ok(if v0.c.is_real() {
                        Value::from_f64(v0.c.re.tanh())
                    } else {
                        Value::from_complex(v0.c.tanh())
                    }),
                    "exp" => Ok(if v0.c.is_real() {
                        Value::from_f64(v0.c.re.exp())
                    } else {
                        Value::from_complex(v0.c.exp())
                    }),
                    "sqrt" => Ok(v0.sqrt()),
                    "ln" => Ok(Value::from_complex(v0.c.ln())),
                    "log10" | "lg" => Ok(Value::from_f64(v0.c.re.log10())),
                    "abs" => Ok(Value::from_f64(v0.c.abs())),
                    _ => {
                        let expr = node.to_display_string();
                        let mut p = ExprParser::new(&expr, self, ov);
                        let v = p.parse_expr()?;
                        p.skip_ws();
                        if p.pos < p.chars.len() {
                            return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
                        }
                        Ok(v)
                    }
                }
            }
            _ => {
                let expr = node.to_display_string();
                let mut p = ExprParser::new(&expr, self, ov);
                let v = p.parse_expr()?;
                p.skip_ws();
                if p.pos < p.chars.len() {
                    return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
                }
                Ok(v)
            }
        }
    }

    fn eval_ast_with_cancel_err(
        &self,
        node: &AstNode,
        ov: Option<&HashMap<String, Value>>,
    ) -> Result<(Value, f64), String> {
        match node {
            AstNode::Num(_) | AstNode::BigInt(_) | AstNode::Var(_) => {
                Ok((self.eval_ast_scalar_with_overrides(node, ov)?, 0.0))
            }
            AstNode::Neg(a) => {
                let (va, ea) = self.eval_ast_with_cancel_err(a, ov)?;
                let neg = Value {
                    c: Complex::new(-va.c.re, -va.c.im),
                    rat: va.rat.and_then(|r| Some(ExactRat { num: r.num.checked_neg()?, den: r.den })),
                };
                Ok((neg, ea))
            }
            AstNode::Add(a, b) => {
                let (va, ea) = self.eval_ast_with_cancel_err(a, ov)?;
                let (vb, eb) = self.eval_ast_with_cancel_err(b, ov)?;
                let res = va.add(vb);
                let step = 8.0 * f64::EPSILON * (va.c.abs() + vb.c.abs());
                Ok((res, ea + eb + step))
            }
            AstNode::Sub(a, b) => {
                let (va, ea) = self.eval_ast_with_cancel_err(a, ov)?;
                let (vb, eb) = self.eval_ast_with_cancel_err(b, ov)?;
                let res = va.sub(vb);
                let step = 8.0 * f64::EPSILON * (va.c.abs() + vb.c.abs());
                Ok((res, ea + eb + step))
            }
            AstNode::Mul(a, b) => {
                let (va, ea) = self.eval_ast_with_cancel_err(a, ov)?;
                let (vb, eb) = self.eval_ast_with_cancel_err(b, ov)?;
                Ok((va.mul(vb), va.c.abs() * eb + vb.c.abs() * ea))
            }
            AstNode::Div(a, b) => {
                let (va, ea) = self.eval_ast_with_cancel_err(a, ov)?;
                let (vb, eb) = self.eval_ast_with_cancel_err(b, ov)?;
                let q = va.div(vb)?;
                let bd = vb.c.abs().max(f64::MIN_POSITIVE);
                Ok((q, (ea + q.c.abs() * eb) / bd))
            }
            AstNode::Pow(base, exp) => {
                let (vb, eb) = self.eval_ast_with_cancel_err(base, ov)?;
                let (ve, ee) = self.eval_ast_with_cancel_err(exp, ov)?;
                let p = vb.pow(ve);
                let babs = vb.c.abs();
                let pabs = p.c.abs();
                let err = if eb == 0.0 && ee == 0.0 {
                    0.0
                } else if babs > 0.0 {
                    pabs * ((ve.c.abs() * eb / babs) + babs.ln().abs() * ee)
                } else {
                    0.0
                };
                Ok((p, err))
            }
            AstNode::Func(name, args) if args.len() == 1 => {
                let (vu, eu) = self.eval_ast_with_cancel_err(&args[0], ov)?;
                let v = self.eval_ast_scalar_with_overrides(node, ov)?;
                let scale = match name.as_str() {
                    "sin" | "cos" => 1.0,
                    "tan" => 1.0 + v.c.abs() * v.c.abs(),
                    "exp" => v.c.abs(),
                    "ln" => 1.0 / vu.c.abs().max(f64::MIN_POSITIVE),
                    "sqrt" => 0.5 / v.c.abs().max(f64::MIN_POSITIVE),
                    _ => 1.0 + v.c.abs() / vu.c.abs().max(1.0),
                };
                Ok((v, scale * eu))
            }
            _ => Ok((self.eval_ast_scalar_with_overrides(node, ov)?, 0.0)),
        }
    }

    fn eval_ast_with_x_default_zero(
        &self,
        node: &AstNode,
        var: &str,
        x_val: Option<f64>,
        ov: Option<&HashMap<String, Value>>,
    ) -> Option<f64> {
        let mut local = ov.cloned().unwrap_or_default();
        for uv in USER_VARIABLES {
            if uv != var && !self.variables.contains_key(uv) && !local.contains_key(uv) {
                local.insert(uv.to_string(), Value::from_f64(0.0));
            }
        }
        if let Some(x) = x_val {
            local.insert(var.to_string(), Value::from_f64(x));
        }
        let v = self.eval_ast_scalar_with_overrides(node, Some(&local)).ok()?;
        if v.c.is_real() && v.c.re.is_finite() {
            Some(v.c.re)
        } else {
            None
        }
    }

    fn push_critical_point(out: &mut Vec<f64>, root: f64, lo: f64, hi: f64) {
        if out.len() < 64 && root.is_finite() && root > lo && root < hi {
            out.push(root);
        }
    }

    fn push_linear_periodic_roots(
        &self,
        arg: &AstNode,
        var: &str,
        base_sol: f64,
        period: f64,
        lo: f64,
        hi: f64,
        ov: Option<&HashMap<String, Value>>,
        out: &mut Vec<f64>,
    ) {
        if let Some((a_node, b_node)) = arg.match_linear(var) {
            if let (Some(a), Some(b)) = (
                self.eval_ast_with_x_default_zero(&a_node, var, None, ov),
                self.eval_ast_with_x_default_zero(&b_node, var, None, ov),
            ) {
                if a != 0.0 && period > 0.0 {
                    let u_lo = a * lo + b;
                    let u_hi = a * hi + b;
                    let m_min = ((u_lo.min(u_hi) - base_sol) / period).floor() as i64 - 1;
                    let m_max = ((u_lo.max(u_hi) - base_sol) / period).ceil() as i64 + 1;
                    let start = m_min.max(-32);
                    let end = m_max.min(32);
                    for m in start..=end {
                        if out.len() >= 64 {
                            break;
                        }
                        let root = (base_sol + (m as f64) * period - b) / a;
                        Self::push_critical_point(out, root, lo, hi);
                    }
                }
            }
        } else {
            let shifted = AstNode::Sub(
                Box::new(arg.clone()),
                Box::new(AstNode::Num(Value::from_f64(base_sol))),
            );
            self.collect_critical_points(&shifted, var, lo, hi, ov, out);
        }
    }

    fn collect_critical_points(
        &self,
        node: &AstNode,
        var: &str,
        lo: f64,
        hi: f64,
        ov: Option<&HashMap<String, Value>>,
        out: &mut Vec<f64>,
    ) {
        if out.len() >= 64 || !node.contains_var(var) {
            return;
        }
        if let Some((a_node, b_node)) = node.match_linear(var) {
            if let (Some(av), Some(bv)) = (
                self.eval_ast_with_x_default_zero(&a_node, var, None, ov),
                self.eval_ast_with_x_default_zero(&b_node, var, None, ov),
            ) {
                if av != 0.0 {
                    Self::push_critical_point(out, -bv / av, lo, hi);
                }
            }
        } else if let (Some(f0), Some(f1), Some(fm1), Some(f2)) = (
            self.eval_ast_with_x_default_zero(node, var, Some(0.0), ov),
            self.eval_ast_with_x_default_zero(node, var, Some(1.0), ov),
            self.eval_ast_with_x_default_zero(node, var, Some(-1.0), ov),
            self.eval_ast_with_x_default_zero(node, var, Some(2.0), ov),
        ) {
            let qa = 0.5 * (f1 + fm1) - f0;
            let qb = 0.5 * (f1 - fm1);
            let qc = f0;
            let f2_pred = 4.0 * qa + 2.0 * qb + qc;
            if qa.abs() > 1e-12 && (f2_pred - f2).abs() <= 1e-6 * (1.0 + f2.abs() + f0.abs()) {
                let xv = -qb / (2.0 * qa);
                Self::push_critical_point(out, xv, lo, hi);
                let disc = qb * qb - 4.0 * qa * qc;
                if disc >= 0.0 {
                    let s = disc.sqrt();
                    Self::push_critical_point(out, (-qb - s) / (2.0 * qa), lo, hi);
                    Self::push_critical_point(out, (-qb + s) / (2.0 * qa), lo, hi);
                } else {
                    // Nearby complex root still sharpens peak near vertex
                    Self::push_critical_point(out, xv, lo, hi);
                }
            } else if let (Some(fm2), Some(f05)) = (
                self.eval_ast_with_x_default_zero(node, var, Some(-2.0), ov),
                self.eval_ast_with_x_default_zero(node, var, Some(0.5), ov),
            ) {
                if (f1 - fm1).abs() <= 1e-9 * (1.0 + f1.abs()) && (f2 - fm2).abs() <= 1e-9 * (1.0 + f2.abs()) {
                    let u1 = f1 - f0;
                    let u2 = f2 - f0;
                    let ba = (u2 - 4.0 * u1) / 12.0;
                    let bb = (16.0 * u1 - u2) / 12.0;
                    let bc = f0;
                    let f05_pred = ba * 0.0625 + bb * 0.25 + bc;
                    if ba.abs() > 1e-12 && (f05_pred - f05).abs() <= 1e-6 * (1.0 + f05.abs() + f0.abs()) {
                        let u_vert = -bb / (2.0 * ba);
                        if u_vert > 0.0 {
                            let s = u_vert.sqrt();
                            Self::push_critical_point(out, s, lo, hi);
                            Self::push_critical_point(out, -s, lo, hi);
                        }
                        let disc = bb * bb - 4.0 * ba * bc;
                        if disc >= 0.0 {
                            let s = disc.sqrt();
                            for u_root in [(-bb - s) / (2.0 * ba), (-bb + s) / (2.0 * ba)] {
                                if u_root >= 0.0 {
                                    let xr = u_root.sqrt();
                                    Self::push_critical_point(out, xr, lo, hi);
                                    Self::push_critical_point(out, -xr, lo, hi);
                                }
                            }
                        }
                    }
                }
            }
        }
        if let AstNode::Add(l, r) | AstNode::Sub(l, r) = node {
            let is_sub = matches!(node, AstNode::Sub(_, _));
            let pair: Option<(&AstNode, f64)> = if !r.contains_var(var) {
                self.eval_ast_with_x_default_zero(r, var, None, ov)
                    .map(|k| (l.as_ref(), if is_sub { k } else { -k }))
            } else if !l.contains_var(var) {
                self.eval_ast_with_x_default_zero(l, var, None, ov)
                    .map(|k| (r.as_ref(), if is_sub { k } else { -k }))
            } else {
                None
            };
            if let Some((AstNode::Func(fname, fargs), target)) = pair {
                if fargs.len() == 1 {
                    let arg = &fargs[0];
                    let pi = std::f64::consts::PI;
                    match fname.as_str() {
                        "sin" if target.abs() <= 1.0 => {
                            let u0 = self.angle_mode.from_radians(target.asin());
                            let half = if self.angle_mode == AngleMode::Degrees { 180.0 } else { pi };
                            let full = 2.0 * half;
                            self.push_linear_periodic_roots(arg, var, u0, full, lo, hi, ov, out);
                            self.push_linear_periodic_roots(arg, var, half - u0, full, lo, hi, ov, out);
                        }
                        "cos" if target.abs() <= 1.0 => {
                            let u0 = self.angle_mode.from_radians(target.acos());
                            let full = if self.angle_mode == AngleMode::Degrees { 360.0 } else { 2.0 * pi };
                            self.push_linear_periodic_roots(arg, var, u0, full, lo, hi, ov, out);
                            self.push_linear_periodic_roots(arg, var, -u0, full, lo, hi, ov, out);
                        }
                        "tan" => {
                            let u0 = self.angle_mode.from_radians(target.atan());
                            let half = if self.angle_mode == AngleMode::Degrees { 180.0 } else { pi };
                            self.push_linear_periodic_roots(arg, var, u0, half, lo, hi, ov, out);
                        }
                        "exp" if target > 0.0 => {
                            let shifted = AstNode::Sub(
                                Box::new(arg.clone()),
                                Box::new(AstNode::Num(Value::from_f64(target.ln()))),
                            );
                            self.collect_critical_points(&shifted, var, lo, hi, ov, out);
                        }
                        "ln" => {
                            let shifted = AstNode::Sub(
                                Box::new(arg.clone()),
                                Box::new(AstNode::Num(Value::from_f64(target.exp()))),
                            );
                            self.collect_critical_points(&shifted, var, lo, hi, ov, out);
                        }
                        "sqrt" if target >= 0.0 => {
                            let shifted = AstNode::Sub(
                                Box::new(arg.clone()),
                                Box::new(AstNode::Num(Value::from_f64(target * target))),
                            );
                            self.collect_critical_points(&shifted, var, lo, hi, ov, out);
                        }
                        "abs" if target >= 0.0 => {
                            let s1 = AstNode::Sub(
                                Box::new(arg.clone()),
                                Box::new(AstNode::Num(Value::from_f64(target))),
                            );
                            let s2 = AstNode::Add(
                                Box::new(arg.clone()),
                                Box::new(AstNode::Num(Value::from_f64(target))),
                            );
                            self.collect_critical_points(&s1, var, lo, hi, ov, out);
                            self.collect_critical_points(&s2, var, lo, hi, ov, out);
                        }
                        _ => {}
                    }
                }
            }
        }
        match node {
            AstNode::Neg(inner) => self.collect_critical_points(inner, var, lo, hi, ov, out),
            AstNode::Add(l, r)
            | AstNode::Sub(l, r)
            | AstNode::Mul(l, r)
            | AstNode::Div(l, r) => {
                self.collect_critical_points(l, var, lo, hi, ov, out);
                self.collect_critical_points(r, var, lo, hi, ov, out);
            }
            AstNode::Pow(base, _) => self.collect_critical_points(base, var, lo, hi, ov, out),
            AstNode::Func(_, args) => {
                for a in args {
                    self.collect_critical_points(a, var, lo, hi, ov, out);
                }
            }
            _ => {}
        }
    }

    fn eval_ast_scalar(&self, node: &AstNode) -> Result<Value, String> {
        match node {
            AstNode::Num(v) => Ok(*v),
            AstNode::BigInt(bi) => Ok(Value::from_f64(bi.to_f64())),
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
                MatrixOrScalar::Matrix(m) => Ok(MatrixOrScalar::Matrix(scale_matrix(&m, Value::from_int(-1))?)),
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
                | (MatrixOrScalar::Scalar(s), MatrixOrScalar::Matrix(m)) => Ok(MatrixOrScalar::Matrix(scale_matrix(&m, s)?)),
                (MatrixOrScalar::Scalar(a), MatrixOrScalar::Scalar(b)) => Ok(MatrixOrScalar::Scalar(a.mul(b))),
            },
            AstNode::Div(l, r) => match (self.eval_matrix_or_scalar_ast(l)?, self.eval_matrix_or_scalar_ast(r)?) {
                (MatrixOrScalar::Matrix(m), MatrixOrScalar::Scalar(s)) => {
                    let mut scaled = Vec::with_capacity(m.len());
                    for row in m {
                        let mut out_row = Vec::with_capacity(row.len());
                        for cell in row {
                            let q = cell.div(s)?;
                            if !q.c.is_real() || !q.c.re.is_finite() {
                                return Err("Matrix-Überlauf".to_string());
                            }
                            out_row.push(q);
                        }
                        scaled.push(out_row);
                    }
                    Ok(MatrixOrScalar::Matrix(scaled))
                }
                (MatrixOrScalar::Scalar(a), MatrixOrScalar::Scalar(b)) => Ok(MatrixOrScalar::Scalar(a.div(b)?)),
                _ => Err("Ungültige Matrixdivision".to_string()),
            },
            AstNode::Pow(l, r) => match (self.eval_matrix_or_scalar_ast(l)?, self.eval_matrix_or_scalar_ast(r)?) {
                (MatrixOrScalar::Matrix(m), MatrixOrScalar::Scalar(s)) => {
                    Ok(MatrixOrScalar::Matrix(matrix_pow(&m, s)?))
                }
                (MatrixOrScalar::Scalar(a), MatrixOrScalar::Scalar(b)) => {
                    Ok(MatrixOrScalar::Scalar(a.pow(b)))
                }
                _ => Err("Ungültige Matrixpotenz".to_string()),
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
            AstNode::Var(name) => {
                if name.eq_ignore_ascii_case("ans") {
                    if let Some(ref m) = self.matrix_ans {
                        return Ok(MatrixOrScalar::Matrix(m.clone()));
                    }
                }
                if let Some(m) = self.matrix_variables.get(name) {
                    return Ok(MatrixOrScalar::Matrix(m.clone()));
                }
                Ok(MatrixOrScalar::Scalar(self.eval_ast_scalar(node)?))
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
            return add_sub_matrices(&left, &right, op == '+');
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

    fn contains_matrix_expression(&self, node: &AstNode) -> bool {
        match node {
            AstNode::Var(name) => {
                (name.eq_ignore_ascii_case("ans") && self.matrix_ans.is_some())
                    || self.matrix_variables.contains_key(name)
            }
            AstNode::Neg(a) => self.contains_matrix_expression(a),
            AstNode::Add(a, b)
            | AstNode::Sub(a, b)
            | AstNode::Mul(a, b)
            | AstNode::Div(a, b)
            | AstNode::Pow(a, b) => {
                self.contains_matrix_expression(a) || self.contains_matrix_expression(b)
            }
            AstNode::Func(name, args) => {
                if name == "det" {
                    return false;
                }
                if name == "[]" || name == "inv" || name == "trans" || name == "transpose" {
                    return true;
                }
                args.iter().any(|arg| self.contains_matrix_expression(arg))
            }
            _ => false,
        }
    }
}

enum MatrixOrScalar {
    Matrix(Vec<Vec<Value>>),
    Scalar(Value),
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
            let val = if is_add {
                left[r][c].add(right[r][c])
            } else {
                left[r][c].sub(right[r][c])
            };
            if !val.c.is_real() || !val.c.re.is_finite() {
                return Err("Matrix-Überlauf".to_string());
            }
            res[r][c] = val;
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

fn scale_matrix(mat: &[Vec<Value>], scalar: Value) -> Result<Vec<Vec<Value>>, String> {
    let mut res = Vec::with_capacity(mat.len());
    for row in mat {
        let mut out_row = Vec::with_capacity(row.len());
        for cell in row {
            let val = cell.mul(scalar);
            if !val.c.is_real() || !val.c.re.is_finite() {
                return Err("Matrix-Überlauf".to_string());
            }
            out_row.push(val);
        }
        res.push(out_row);
    }
    Ok(res)
}

fn double_to_bigrat(v: f64) -> Option<BigRat> {
    if v == 0.0 {
        return Some(BigRat {
            num: BigInt::from_i128(0),
            den: BigUInt::one(),
        });
    }
    if !v.is_finite() || v.abs() > 1e30 || v.abs() < 1e-30 {
        return None;
    }
    let neg = v < 0.0;
    let x = v.abs();
    if x < 1e9 {
        let mut p0: i128 = 0;
        let mut q0: i128 = 1;
        let mut p1: i128 = 1;
        let mut q1: i128 = 0;
        let mut rem = x;
        for _ in 0..32 {
            let a = rem.floor();
            if !a.is_finite() || a > 1e9 {
                break;
            }
            let ai = a as i128;
            let Some(p2) = ai.checked_mul(p1).and_then(|t| t.checked_add(p0)) else { break; };
            let Some(q2) = ai.checked_mul(q1).and_then(|t| t.checked_add(q0)) else { break; };
            if q2 > 100_000 {
                break;
            }
            if p2 != 0 {
                let approx = (p2 as f64) / (q2 as f64);
                if (approx - x).abs() <= 2e-15 * x {
                    let num = if neg { -p2 } else { p2 };
                    if let Some(er) = ExactRat::new(num, q2) {
                        return Some(BigRat::from_exact_rat(er));
                    }
                }
            }
            let frac = rem - a;
            if frac <= 1e-15 * rem.max(1.0) {
                break;
            }
            p0 = p1;
            q0 = q1;
            p1 = p2;
            q1 = q2;
            rem = 1.0 / frac;
        }
    }
    let s = format!("{:.15e}", x);
    let (mant_str, exp_str) = s.split_once('e')?;
    let exp = exp_str.parse::<i32>().ok()?;
    let mut digits = String::with_capacity(mant_str.len());
    let mut frac_digits = 0i32;
    let mut seen_dot = false;
    for ch in mant_str.chars() {
        if ch == '.' {
            seen_dot = true;
        } else if ch.is_ascii_digit() {
            digits.push(ch);
            if seen_dot {
                frac_digits += 1;
            }
        }
    }
    let trimmed = digits.trim_end_matches('0');
    let dropped = (digits.len() - trimmed.len()) as i32;
    let sig_digits = if trimmed.is_empty() { "0" } else { trimmed };
    let scale = exp - frac_digits + dropped;
    let mag = BigUInt::from_decimal_str(sig_digits)?;
    let num_bi = BigInt::from_u32_parts(neg, mag);
    if scale >= 0 {
        let pow10 = BigInt::from_u32_parts(false, BigUInt::from_u128(10).pow_u32(scale as u32));
        Some(BigRat {
            num: num_bi.mul(&pow10),
            den: BigUInt::one(),
        })
    } else {
        let den = BigUInt::from_u128(10).pow_u32((-scale) as u32);
        BigRat::reduce(num_bi, den).ok()
    }
}

fn value_to_bigrat(v: Value) -> Option<BigRat> {
    if let Some(er) = v.rat {
        return Some(BigRat::from_exact_rat(er));
    }
    if v.c.is_real() {
        return double_to_bigrat(v.c.re);
    }
    None
}

fn try_exact_matrix_mul(left: &[Vec<Value>], right: &[Vec<Value>]) -> Result<Option<Vec<Vec<Value>>>, String> {
    let rows = left.len();
    let inner = left[0].len();
    let cols = right[0].len();
    let mut l_rat = Vec::with_capacity(rows);
    for row in left {
        let mut rr = Vec::with_capacity(inner);
        for &cell in row {
            let Some(br) = value_to_bigrat(cell) else { return Ok(None); };
            rr.push(br);
        }
        l_rat.push(rr);
    }
    let mut r_rat = Vec::with_capacity(inner);
    for row in right {
        let mut rr = Vec::with_capacity(cols);
        for &cell in row {
            let Some(br) = value_to_bigrat(cell) else { return Ok(None); };
            rr.push(br);
        }
        r_rat.push(rr);
    }
    let zero = BigRat {
        num: BigInt::from_i128(0),
        den: BigUInt::one(),
    };
    let mut res = Vec::with_capacity(rows);
    for r in 0..rows {
        let mut out_row = Vec::with_capacity(cols);
        for c in 0..cols {
            check_eval_deadline()?;
            let mut sum = zero.clone();
            for k in 0..inner {
                let prod = l_rat[r][k].mul(&r_rat[k][c])?;
                sum = sum.add(&prod)?;
                if sum.num.mag.bit_len().max(sum.den.bit_len()) > 16384 {
                    return Ok(None);
                }
            }
            let f = sum.to_f64();
            if !f.is_finite() {
                return Err("Matrix-Überlauf".to_string());
            }
            out_row.push(Value {
                c: Complex::real(f),
                rat: sum.to_exact_rat(),
            });
        }
        res.push(out_row);
    }
    Ok(Some(res))
}

fn try_exact_matrix_inv(m: &[Vec<Value>]) -> Result<Option<Vec<Vec<Value>>>, String> {
    let n = m.len();
    let zero = BigRat {
        num: BigInt::from_i128(0),
        den: BigUInt::one(),
    };
    let one = BigRat {
        num: BigInt::from_i128(1),
        den: BigUInt::one(),
    };
    let mut aug = Vec::with_capacity(n);
    for r in 0..n {
        let mut row = Vec::with_capacity(2 * n);
        for c in 0..n {
            let Some(br) = value_to_bigrat(m[r][c]) else { return Ok(None); };
            row.push(br);
        }
        for c in 0..n {
            row.push(if r == c { one.clone() } else { zero.clone() });
        }
        aug.push(row);
    }
    for i in 0..n {
        check_eval_deadline()?;
        let mut pivot_row = None;
        for k in i..n {
            if !aug[k][i].num.mag.is_zero() {
                pivot_row = Some(k);
                break;
            }
        }
        let Some(pr) = pivot_row else {
            return Err("Matrix ist singulär (nicht invertierbar)".to_string());
        };
        if pr != i {
            aug.swap(i, pr);
        }
        let pivot = aug[i][i].clone();
        for col in i..(2 * n) {
            aug[i][col] = aug[i][col].div(&pivot)?;
            if aug[i][col].num.mag.bit_len().max(aug[i][col].den.bit_len()) > 16384 {
                return Ok(None);
            }
        }
        for r in 0..n {
            if r != i && !aug[r][i].num.mag.is_zero() {
                check_eval_deadline()?;
                let factor = aug[r][i].clone();
                aug[r][i] = zero.clone();
                for col in (i + 1)..(2 * n) {
                    if !aug[i][col].num.mag.is_zero() {
                        let sub_term = factor.mul(&aug[i][col])?;
                        aug[r][col] = aug[r][col].sub(&sub_term)?;
                        if aug[r][col].num.mag.bit_len().max(aug[r][col].den.bit_len()) > 16384 {
                            return Ok(None);
                        }
                    }
                }
            }
        }
    }
    let mut inv = Vec::with_capacity(n);
    for r in 0..n {
        let mut out_row = Vec::with_capacity(n);
        for c in 0..n {
            let br = &aug[r][n + c];
            let f = br.to_f64();
            if !f.is_finite() {
                return Ok(None);
            }
            out_row.push(Value {
                c: Complex::real(f),
                rat: br.to_exact_rat(),
            });
        }
        inv.push(out_row);
    }
    Ok(Some(inv))
}

fn multiply_matrices(left: &[Vec<Value>], right: &[Vec<Value>]) -> Result<Vec<Vec<Value>>, String> {
    let rows = left.len();
    let inner = left[0].len();
    if right.len() != inner {
        return Err("Incompatible matrix dimensions".to_string());
    }
    if let Some(exact) = try_exact_matrix_mul(left, right)? {
        return Ok(exact);
    }
    let cols = right[0].len();
    let mut res = vec![vec![Value::from_int(0); cols]; rows];
    for r in 0..rows {
        for c in 0..cols {
            let mut sum = Value::from_int(0);
            for k in 0..inner {
                let prod = left[r][k].mul(right[k][c]);
                if !prod.c.is_real() || !prod.c.re.is_finite() {
                    return Err("Matrix-Überlauf".to_string());
                }
                sum = sum.add(prod);
                if !sum.c.is_real() || !sum.c.re.is_finite() {
                    return Err("Matrix-Überlauf".to_string());
                }
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
    if let Some(exact) = try_exact_matrix_inv(m)? {
        return Ok(exact);
    }
    let mut row_scale = vec![0.0_f64; n];
    let mut col_scale = vec![0.0_f64; n];
    let zero = Value::from_int(0);
    let one = Value::from_int(1);
    let mut aug = vec![vec![zero; 2 * n]; n];
    for r in 0..n {
        let mut r_max = 0.0_f64;
        for c in 0..n {
            let cell = m[r][c];
            if !cell.c.is_real() || !cell.c.re.is_finite() {
                return Err("Matrix-Überlauf".to_string());
            }
            let a = cell.c.re.abs();
            r_max = r_max.max(a);
            col_scale[c] = col_scale[c].max(a);
            aug[r][c] = cell;
        }
        if r_max == 0.0 {
            return Err("Matrix ist singulär (nicht invertierbar)".to_string());
        }
        row_scale[r] = r_max;
        aug[r][n + r] = one;
    }
    for i in 0..n {
        let mut pivot_row = i;
        let active_scale = |row: &[Value]| -> f64 {
            let s = (i..n).map(|col| row[col].c.abs()).fold(0.0_f64, f64::max);
            if s > 0.0 { s } else { 1.0 }
        };
        let mut best_ratio = aug[i][i].c.abs() / active_scale(&aug[i]);
        for k in (i + 1)..n {
            let ratio = aug[k][i].c.abs() / active_scale(&aug[k]);
            if ratio > best_ratio {
                best_ratio = ratio;
                pivot_row = k;
            }
        }
        let pivot_zero_rat = aug[pivot_row][i].rat.map_or(false, |rat| rat.num == 0);
        let cond_denom = row_scale[pivot_row].min(col_scale[i]);
        let cond_ratio = if cond_denom > 0.0 {
            aug[pivot_row][i].c.abs() / cond_denom
        } else {
            0.0
        };
        if pivot_zero_rat || !best_ratio.is_finite() || cond_ratio <= 1e-14 {
            return Err("Matrix ist singulär (nicht invertierbar)".to_string());
        }
        if pivot_row != i {
            aug.swap(i, pivot_row);
            row_scale.swap(i, pivot_row);
        }
        let pivot = aug[i][i];
        for j in 0..(2 * n) {
            let q = aug[i][j].div(pivot)?;
            if !q.c.is_real() || !q.c.re.is_finite() {
                return Err("Matrix-Überlauf".to_string());
            }
            aug[i][j] = q;
        }
        for k in 0..n {
            if k != i {
                let factor = aug[k][i];
                if factor.c.re != 0.0 || factor.c.im != 0.0 {
                    for j in 0..(2 * n) {
                        let old = aug[k][j];
                        let sub_term = factor.mul(aug[i][j]);
                        if !sub_term.c.is_real() || !sub_term.c.re.is_finite() {
                            return Err("Matrix-Überlauf".to_string());
                        }
                        let updated = old.sub(sub_term);
                        if !updated.c.is_real() || !updated.c.re.is_finite() {
                            return Err("Matrix-Überlauf".to_string());
                        }
                        aug[k][j] = updated;
                    }
                }
            }
        }
    }
    let mut inv = vec![vec![zero; n]; n];
    for i in 0..n {
        for j in 0..n {
            let cell = aug[i][n + j];
            if !cell.c.is_real() || !cell.c.re.is_finite() {
                return Err("Matrix-Überlauf".to_string());
            }
            inv[i][j] = cell;
        }
    }
    Ok(inv)
}

fn matrix_pow(m: &[Vec<Value>], exp_val: Value) -> Result<Vec<Vec<Value>>, String> {
    let n = m.len();
    if n == 0 || m[0].len() != n {
        return Err("Matrixpotenz erfordert quadratische Matrix".to_string());
    }
    let exp: i32 = if let Some(r) = exp_val.rat {
        if r.den != 1 || r.num.abs() > 1000 {
            return Err("Matrixexponent muss eine ganze Zahl zwischen -1000 und 1000 sein".to_string());
        }
        r.num as i32
    } else {
        if !exp_val.c.is_real()
            || !exp_val.c.re.is_finite()
            || (exp_val.c.re - exp_val.c.re.round()).abs() > 1e-12
            || exp_val.c.re.abs() > 1000.0
        {
            return Err("Matrixexponent muss eine ganze Zahl zwischen -1000 und 1000 sein".to_string());
        }
        exp_val.c.re.round() as i32
    };
    let mut res = vec![vec![Value::from_int(0); n]; n];
    for i in 0..n {
        res[i][i] = Value::from_int(1);
    }
    if exp == 0 {
        return Ok(res);
    }
    let mut base = if exp < 0 { matrix_inv(m)? } else { m.to_vec() };
    let mut p = exp.unsigned_abs();
    while p > 0 {
        check_eval_deadline()?;
        if (p & 1) != 0 {
            res = multiply_matrices(&res, &base)?;
        }
        p >>= 1;
        if p > 0 {
            base = multiply_matrices(&base, &base)?;
        }
    }
    Ok(res)
}

// --- Adaptive Gauss-Legendre Quadrature with Error Control & Convergence Assertion ---

#[allow(dead_code)]
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
    adaptive_gauss_integrate_with_anchors(f, a, b, initial_subintervals, eps_abs, eps_rel, &[])
}

pub fn adaptive_gauss_integrate_with_anchors<F>(
    f: F,
    a: f64,
    b: f64,
    initial_subintervals: usize,
    eps_abs: f64,
    eps_rel: f64,
    extra_anchors: &[f64],
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
        check_eval_deadline()?;
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

    if a == b {
        return Ok(IntegrationReport {
            value: 0.0,
            abs_err: 0.0,
            converged: true,
            subintervals: 0,
            evaluations: 0,
            message: "Konvergiert: Fehlerabschätzung 0.00e0 <= Toleranz 1.00e-10 (0 Auswertungen)".to_string(),
        });
    }

    let mut intervals = Vec::with_capacity(initial_subintervals * 2 + 128);
    let mut total_val = 0.0;
    let mut total_err = 0.0;
    let mut evals: usize;

    let lo = a.min(b);
    let hi = a.max(b);
    let span = hi - lo;
    let step = span / (initial_subintervals as f64);
    let mut pts = Vec::with_capacity(initial_subintervals + 128);
    for i in 0..=initial_subintervals {
        pts.push(if i == initial_subintervals { hi } else { lo + (i as f64) * step });
    }

    if initial_subintervals >= 500 {
        let mid = 0.5 * (lo + hi);
        let mut anchors: Vec<f64> = Vec::with_capacity(extra_anchors.len() + 4);
        anchors.push(lo);
        if lo < 0.0 && hi > 0.0 {
            anchors.push(0.0);
        }
        anchors.push(mid);
        anchors.push(hi);
        for &ea in extra_anchors {
            if ea.is_finite() && ea > lo && ea < hi {
                anchors.push(ea);
            }
        }
        let offsets = [
            1e-7, 5e-7, 1e-6, 3e-6, 1e-5, 3e-5, 1e-4, 3e-4, 1e-3, 3e-3,
            1e-2, 3e-2, 1e-1, 1.0, 4.0, 16.0, 64.0, 256.0, 1024.0,
        ];
        for anchor in anchors {
            if anchor > lo && anchor < hi {
                pts.push(anchor);
            }
            for &off in &offsets {
                if off >= span * 0.25 {
                    break;
                }
                if anchor - off > lo {
                    pts.push(anchor - off);
                }
                if anchor + off < hi {
                    pts.push(anchor + off);
                }
            }
        }
    } else {
        let mut anchors: Vec<f64> = Vec::with_capacity(extra_anchors.len() + 1);
        if lo < 0.0 && hi > 0.0 {
            anchors.push(0.0);
        }
        for &ea in extra_anchors {
            if ea.is_finite() && ea > lo && ea < hi {
                anchors.push(ea);
            }
        }
        let nested_offsets = [1e-4, 5e-4, 2e-3, 5e-3, 1e-2, 2e-2, 5e-2, 0.25, 1.0, 4.0];
        for anchor in anchors {
            pts.push(anchor);
            for &off in &nested_offsets {
                if off >= span * 0.25 {
                    break;
                }
                if anchor - off > lo {
                    pts.push(anchor - off);
                }
                if anchor + off < hi {
                    pts.push(anchor + off);
                }
            }
        }
    }

    pts.sort_by(f64::total_cmp);
    let mut dedup: Vec<f64> = Vec::with_capacity(pts.len());
    for p in pts {
        if let Some(&last) = dedup.last() {
            if p - last <= 1e-12 * p.abs().max(1.0) {
                if p == hi {
                    *dedup.last_mut().unwrap() = hi;
                }
                continue;
            }
        }
        dedup.push(p);
    }
    if a < b {
        for w in dedup.windows(2) {
            let sub = eval_sub(w[0], w[1])?;
            total_val += sub.val5;
            total_err += sub.err;
            intervals.push(sub);
        }
    } else {
        for w in dedup.windows(2).rev() {
            let sub = eval_sub(w[1], w[0])?;
            total_val += sub.val5;
            total_err += sub.err;
            intervals.push(sub);
        }
    }
    evals = intervals.len() * 7;

    let max_evals = 100_000;
    let mut target_tol = eps_abs.max(eps_rel * total_val.abs());

    while total_err > target_tol && evals < max_evals {
        check_eval_deadline()?;
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
        target_tol = eps_abs.max(eps_rel * total_val.abs());

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
    BigInt(BigInt),
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
            AstNode::BigInt(bi) => Some(Value::from_f64(bi.to_f64())),
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
            AstNode::Num(_) | AstNode::BigInt(_) => false,
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

    fn contains_step_function(&self) -> bool {
        match self {
            AstNode::Num(_) | AstNode::BigInt(_) | AstNode::Var(_) => false,
            AstNode::Neg(a) => a.contains_step_function(),
            AstNode::Add(a, b)
            | AstNode::Sub(a, b)
            | AstNode::Mul(a, b)
            | AstNode::Div(a, b)
            | AstNode::Pow(a, b) => a.contains_step_function() || b.contains_step_function(),
            AstNode::Func(name, args) => {
                matches!(name.as_str(), "floor" | "ceil" | "round" | "sign" | "sgn" | "mod")
                    || args.iter().any(|arg| arg.contains_step_function())
            }
        }
    }

    #[allow(dead_code)]
    pub fn diff(&self, var: &str) -> Option<AstNode> {
        self.diff_with_mode(var, AngleMode::Radians)
    }

    pub fn diff_with_mode(&self, var: &str, mode: AngleMode) -> Option<AstNode> {
        match self {
            AstNode::Num(_) | AstNode::BigInt(_) => Some(AstNode::Num(Value::from_int(0))),
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
                    "abs" => Some(AstNode::Div(
                        Box::new(AstNode::Mul(Box::new(u_prime), Box::new(u.clone()))),
                        Box::new(AstNode::Func("abs".to_string(), vec![u.clone()])),
                    )),
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

    fn match_quadratic(&self, var: &str) -> Option<(AstNode, AstNode, AstNode)> {
        let zero = || AstNode::Num(Value::from_int(0));
        if !self.contains_var(var) {
            return Some((zero(), zero(), self.clone()));
        }
        if let Some((a, b)) = self.match_linear(var) {
            return Some((zero(), a, b));
        }
        match self {
            AstNode::Neg(inner) => {
                let (a, b, c) = inner.match_quadratic(var)?;
                Some((
                    AstNode::Neg(Box::new(a)).simplify(),
                    AstNode::Neg(Box::new(b)).simplify(),
                    AstNode::Neg(Box::new(c)).simplify(),
                ))
            }
            AstNode::Add(l, r) => {
                let (la, lb, lc) = l.match_quadratic(var)?;
                let (ra, rb, rc) = r.match_quadratic(var)?;
                Some((
                    AstNode::Add(Box::new(la), Box::new(ra)).simplify(),
                    AstNode::Add(Box::new(lb), Box::new(rb)).simplify(),
                    AstNode::Add(Box::new(lc), Box::new(rc)).simplify(),
                ))
            }
            AstNode::Sub(l, r) => {
                let (la, lb, lc) = l.match_quadratic(var)?;
                let (ra, rb, rc) = r.match_quadratic(var)?;
                Some((
                    AstNode::Sub(Box::new(la), Box::new(ra)).simplify(),
                    AstNode::Sub(Box::new(lb), Box::new(rb)).simplify(),
                    AstNode::Sub(Box::new(lc), Box::new(rc)).simplify(),
                ))
            }
            AstNode::Mul(l, r) => {
                if !l.contains_var(var) {
                    let (ra, rb, rc) = r.match_quadratic(var)?;
                    return Some((
                        AstNode::Mul(l.clone(), Box::new(ra)).simplify(),
                        AstNode::Mul(l.clone(), Box::new(rb)).simplify(),
                        AstNode::Mul(l.clone(), Box::new(rc)).simplify(),
                    ));
                }
                if !r.contains_var(var) {
                    let (la, lb, lc) = l.match_quadratic(var)?;
                    return Some((
                        AstNode::Mul(Box::new(la), r.clone()).simplify(),
                        AstNode::Mul(Box::new(lb), r.clone()).simplify(),
                        AstNode::Mul(Box::new(lc), r.clone()).simplify(),
                    ));
                }
                let (la, lb) = l.match_linear(var)?;
                let (ra, rb) = r.match_linear(var)?;
                let qa = AstNode::Mul(Box::new(la.clone()), Box::new(ra.clone())).simplify();
                let qb = AstNode::Add(
                    Box::new(AstNode::Mul(Box::new(la), Box::new(rb.clone())).simplify()),
                    Box::new(AstNode::Mul(Box::new(lb.clone()), Box::new(ra)).simplify()),
                )
                .simplify();
                let qc = AstNode::Mul(Box::new(lb), Box::new(rb)).simplify();
                Some((qa, qb, qc))
            }
            AstNode::Div(l, r) if !r.contains_var(var) => {
                let (la, lb, lc) = l.match_quadratic(var)?;
                Some((
                    AstNode::Div(Box::new(la), r.clone()).simplify(),
                    AstNode::Div(Box::new(lb), r.clone()).simplify(),
                    AstNode::Div(Box::new(lc), r.clone()).simplify(),
                ))
            }
            AstNode::Pow(base, exp) if !exp.contains_var(var) => {
                let is_two = match try_poly_rat_ast(exp) {
                    Some(er) => er.den == 1 && er.num == 2,
                    None => exp.const_value().is_some_and(|cv| cv.c.is_real() && (cv.c.re - 2.0).abs() < 1e-12),
                };
                if is_two {
                    let (p, q) = base.match_linear(var)?;
                    let qa = AstNode::Mul(Box::new(p.clone()), Box::new(p.clone())).simplify();
                    let qb = AstNode::Mul(
                        Box::new(AstNode::Num(Value::from_int(2))),
                        Box::new(AstNode::Mul(Box::new(p), Box::new(q.clone())).simplify()),
                    )
                    .simplify();
                    let qc = AstNode::Mul(Box::new(q.clone()), Box::new(q)).simplify();
                    return Some((qa, qb, qc));
                }
                None
            }
            _ => None,
        }
    }

    fn expand_derivatives(&self, mode: AngleMode) -> AstNode {
        match self {
            AstNode::Num(_) | AstNode::BigInt(_) | AstNode::Var(_) => self.clone(),
            AstNode::Neg(a) => AstNode::Neg(Box::new(a.expand_derivatives(mode))).simplify(),
            AstNode::Add(a, b) => AstNode::Add(
                Box::new(a.expand_derivatives(mode)),
                Box::new(b.expand_derivatives(mode)),
            )
            .simplify(),
            AstNode::Sub(a, b) => AstNode::Sub(
                Box::new(a.expand_derivatives(mode)),
                Box::new(b.expand_derivatives(mode)),
            )
            .simplify(),
            AstNode::Mul(a, b) => AstNode::Mul(
                Box::new(a.expand_derivatives(mode)),
                Box::new(b.expand_derivatives(mode)),
            )
            .simplify(),
            AstNode::Div(a, b) => AstNode::Div(
                Box::new(a.expand_derivatives(mode)),
                Box::new(b.expand_derivatives(mode)),
            )
            .simplify(),
            AstNode::Pow(a, b) => AstNode::Pow(
                Box::new(a.expand_derivatives(mode)),
                Box::new(b.expand_derivatives(mode)),
            )
            .simplify(),
            AstNode::Func(name, args) => {
                if matches!(name.as_str(), "d" | "deriv" | "diff") && args.len() == 2 {
                    let inner = args[0].expand_derivatives(mode);
                    let var_name = match &args[1] {
                        AstNode::Var(v) => v.clone(),
                        other => other.to_display_string(),
                    };
                    if let Some(d_ast) = inner.diff_with_mode(&var_name, mode) {
                        return d_ast.simplify();
                    }
                }
                let eargs = args.iter().map(|a| a.expand_derivatives(mode)).collect();
                AstNode::Func(name.clone(), eargs)
            }
        }
    }

    #[allow(dead_code)]
    pub fn integrate(&self, var: &str) -> Option<AstNode> {
        self.integrate_with_mode(var, AngleMode::Radians)
    }

    pub fn integrate_with_mode(&self, var: &str, mode: AngleMode) -> Option<AstNode> {
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
            AstNode::Neg(inner) => Some(AstNode::Neg(Box::new(inner.integrate_with_mode(var, mode)?)).simplify()),
            AstNode::Div(a, b) => {
                if !b.contains_var(var) {
                    let ia = a.integrate_with_mode(var, mode)?;
                    return Some(AstNode::Div(Box::new(ia), b.clone()).simplify());
                }
                if !a.contains_var(var) {
                    let sb = b.simplify();
                    if let AstNode::Neg(inner_b) = &sb {
                        if let Some(res) = AstNode::Div(
                            Box::new(AstNode::Neg(a.clone()).simplify()),
                            inner_b.clone(),
                        )
                        .integrate_with_mode(var, mode)
                        {
                            return Some(res);
                        }
                    }
                    if let AstNode::Mul(m_l, m_r) = &sb {
                        if !m_l.contains_var(var) {
                            if let Some(res) = AstNode::Div(
                                Box::new(AstNode::Div(a.clone(), m_l.clone()).simplify()),
                                m_r.clone(),
                            )
                            .integrate_with_mode(var, mode)
                            {
                                return Some(res);
                            }
                        } else if !m_r.contains_var(var) {
                            if let Some(res) = AstNode::Div(
                                Box::new(AstNode::Div(a.clone(), m_r.clone()).simplify()),
                                m_l.clone(),
                            )
                            .integrate_with_mode(var, mode)
                            {
                                return Some(res);
                            }
                        }
                    }
                    if let Some((lin_a, lin_b)) = sb.match_linear(var) {
                        if !lin_a.is_zero() {
                            let ln_inner = if lin_b.is_zero() {
                                AstNode::Var(var.to_string())
                            } else {
                                sb
                            };
                            let ln = AstNode::Func(
                                "ln".to_string(),
                                vec![AstNode::Func("abs".to_string(), vec![ln_inner])],
                            );
                            let num = if a.is_one() {
                                ln
                            } else {
                                AstNode::Mul(a.clone(), Box::new(ln)).simplify()
                            };
                            return Some(if lin_a.is_one() {
                                num
                            } else {
                                AstNode::Div(Box::new(num), Box::new(lin_a)).simplify()
                            });
                        }
                    }
                    if let AstNode::Pow(base, exp) = &sb {
                        if !exp.contains_var(var) {
                            let neg_pow = AstNode::Pow(
                                base.clone(),
                                Box::new(AstNode::Neg(exp.clone()).simplify()),
                            );
                            if let Some(ip) = neg_pow.integrate_with_mode(var, mode) {
                                return Some(if a.is_one() {
                                    ip
                                } else {
                                    AstNode::Mul(a.clone(), Box::new(ip)).simplify()
                                });
                            }
                        }
                    }
                    if let AstNode::Func(fname, fargs) = &sb {
                        if fname == "sqrt" && fargs.len() == 1 {
                            if let Some((sq_a, _sq_b)) = fargs[0].match_linear(var) {
                                if !sq_a.is_zero() {
                                    let two = AstNode::Num(Value::from_int(2));
                                    let scaled = if a.is_one() {
                                        two
                                    } else {
                                        AstNode::Mul(Box::new(two), a.clone()).simplify()
                                    };
                                    let num = AstNode::Mul(Box::new(scaled), Box::new(sb.clone())).simplify();
                                    return Some(if sq_a.is_one() {
                                        num
                                    } else {
                                        AstNode::Div(Box::new(num), Box::new(sq_a)).simplify()
                                    });
                                }
                            } else if let Some((sq_a, sq_b, sq_c)) = fargs[0].match_quadratic(var) {
                                if sq_b.is_zero() {
                                    if let (Some(av), Some(cv)) = (sq_a.const_value(), sq_c.const_value()) {
                                        if av.c.is_real() && cv.c.is_real() && av.c.re < 0.0 && cv.c.re > 0.0 {
                                            let neg_a = -av.c.re;
                                            let c_val = cv.c.re;
                                            let arg = if (neg_a - c_val).abs() < 1e-12 {
                                                AstNode::Var(var.to_string())
                                            } else {
                                                let k = (neg_a / c_val).sqrt();
                                                AstNode::Mul(
                                                    Box::new(AstNode::Num(Value::from_f64(k))),
                                                    Box::new(AstNode::Var(var.to_string())),
                                                )
                                                .simplify()
                                            };
                                            let mut asin_node = AstNode::Func("asin".to_string(), vec![arg]);
                                            if mode == AngleMode::Degrees {
                                                let pi_over_180 = AstNode::Div(
                                                    Box::new(AstNode::Var("pi".to_string())),
                                                    Box::new(AstNode::Num(Value::from_int(180))),
                                                );
                                                asin_node = AstNode::Mul(Box::new(pi_over_180), Box::new(asin_node)).simplify();
                                            }
                                            let num = if a.is_one() {
                                                asin_node
                                            } else {
                                                AstNode::Mul(a.clone(), Box::new(asin_node)).simplify()
                                            };
                                            return Some(if (neg_a - 1.0).abs() < 1e-12 {
                                                num
                                            } else {
                                                AstNode::Div(
                                                    Box::new(num),
                                                    Box::new(AstNode::Num(Value::from_f64(neg_a.sqrt()))),
                                                )
                                                .simplify()
                                            });
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if let Some((q_a, q_b, q_c)) = sb.match_quadratic(var) {
                        let isqrt = |n: i128| -> Option<i128> {
                            if n < 0 {
                                return None;
                            }
                            let s = (n as f64).sqrt().round() as i128;
                            if s >= 0 && s.checked_mul(s) == Some(n) {
                                Some(s)
                            } else {
                                None
                            }
                        };
                        let rat_sqrt = |r: ExactRat| -> Option<Value> {
                            if r.num <= 0 || r.den <= 0 {
                                return None;
                            }
                            let sn = isqrt(r.num)?;
                            let sd = isqrt(r.den)?;
                            let sr = ExactRat::new(sn, sd)?;
                            Some(Value {
                                c: Complex::real(sr.to_f64()),
                                rat: Some(sr),
                            })
                        };
                        if let (Some(av), Some(cv)) = (q_a.const_value(), q_c.const_value()) {
                            let bv_opt = q_b.const_value();
                            let bv = bv_opt.map_or(0.0, |v| v.c.re);
                            let b_is_real = bv_opt.map_or(q_b.is_zero(), |v| v.c.is_real());
                            if av.c.is_real() && cv.c.is_real() && b_is_real && av.c.re > 0.0 {
                                if q_b.is_zero() && cv.c.re > 0.0 {
                                    let arg = if (av.c.re - cv.c.re).abs() < 1e-12 {
                                        AstNode::Var(var.to_string())
                                    } else {
                                        let ratio_val = av
                                            .rat
                                            .zip(cv.rat)
                                            .and_then(|(ar, cr)| ar.div(cr))
                                            .and_then(rat_sqrt)
                                            .unwrap_or_else(|| Value::from_f64((av.c.re / cv.c.re).sqrt()));
                                        AstNode::Mul(
                                            Box::new(AstNode::Num(ratio_val)),
                                            Box::new(AstNode::Var(var.to_string())),
                                        )
                                        .simplify()
                                    };
                                    let mut atan_node = AstNode::Func("atan".to_string(), vec![arg]);
                                    if mode == AngleMode::Degrees {
                                        let pi_over_180 = AstNode::Div(
                                            Box::new(AstNode::Var("pi".to_string())),
                                            Box::new(AstNode::Num(Value::from_int(180))),
                                        );
                                        atan_node = AstNode::Mul(Box::new(pi_over_180), Box::new(atan_node)).simplify();
                                    }
                                    let num = if a.is_one() {
                                        atan_node
                                    } else {
                                        AstNode::Mul(a.clone(), Box::new(atan_node)).simplify()
                                    };
                                    let prod = av.c.re * cv.c.re;
                                    return Some(if (prod - 1.0).abs() < 1e-12 {
                                        num
                                    } else {
                                        let scale_val = av
                                            .rat
                                            .zip(cv.rat)
                                            .and_then(|(ar, cr)| ar.mul(cr))
                                            .and_then(rat_sqrt)
                                            .unwrap_or_else(|| Value::from_f64(prod.sqrt()));
                                        AstNode::Div(Box::new(num), Box::new(AstNode::Num(scale_val))).simplify()
                                    });
                                } else {
                                    let disc = 4.0 * av.c.re * cv.c.re - bv * bv;
                                    if disc > 1e-12 * (av.c.re * cv.c.re.abs() + bv * bv + 1.0) {
                                        let rt_disc = disc.sqrt();
                                        let lin_top = AstNode::Add(
                                            Box::new(AstNode::Mul(
                                                Box::new(AstNode::Num(Value::from_f64(2.0 * av.c.re))),
                                                Box::new(AstNode::Var(var.to_string())),
                                            ).simplify()),
                                            Box::new(q_b),
                                        )
                                        .simplify();
                                        let arg = AstNode::Div(
                                            Box::new(lin_top),
                                            Box::new(AstNode::Num(Value::from_f64(rt_disc))),
                                        )
                                        .simplify();
                                        let mut atan_node = AstNode::Func("atan".to_string(), vec![arg]);
                                        if mode == AngleMode::Degrees {
                                            let pi_over_180 = AstNode::Div(
                                                Box::new(AstNode::Var("pi".to_string())),
                                                Box::new(AstNode::Num(Value::from_int(180))),
                                            );
                                            atan_node = AstNode::Mul(Box::new(pi_over_180), Box::new(atan_node)).simplify();
                                        }
                                        let two_a = AstNode::Mul(
                                            Box::new(AstNode::Num(Value::from_int(2))),
                                            a.clone(),
                                        )
                                        .simplify();
                                        let num = AstNode::Mul(Box::new(two_a), Box::new(atan_node)).simplify();
                                        return Some(
                                            AstNode::Div(
                                                Box::new(num),
                                                Box::new(AstNode::Num(Value::from_f64(rt_disc))),
                                            )
                                            .simplify(),
                                        );
                                    } else if disc.abs() <= 1e-12 * (av.c.re * cv.c.re.abs() + bv * bv + 1.0) {
                                        let lin_bot = AstNode::Add(
                                            Box::new(AstNode::Mul(
                                                Box::new(AstNode::Num(Value::from_f64(2.0 * av.c.re))),
                                                Box::new(AstNode::Var(var.to_string())),
                                            ).simplify()),
                                            Box::new(q_b),
                                        )
                                        .simplify();
                                        let two_a = AstNode::Mul(
                                            Box::new(AstNode::Num(Value::from_int(2))),
                                            a.clone(),
                                        )
                                        .simplify();
                                        return Some(
                                            AstNode::Neg(Box::new(
                                                AstNode::Div(Box::new(two_a), Box::new(lin_bot)).simplify(),
                                            ))
                                            .simplify(),
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
                None
            }
            AstNode::Pow(base, exp) if !exp.contains_var(var) => {
                if let AstNode::Func(fname, fargs) = base.as_ref() {
                    if (fname == "sin" || fname == "cos") && fargs.len() == 1 {
                        let is_sq = match try_poly_rat_ast(exp) {
                            Some(er) => er.den == 1 && er.num == 2,
                            None => exp.const_value().is_some_and(|cv| cv.c.is_real() && (cv.c.re - 2.0).abs() < 1e-11),
                        };
                        if is_sq {
                            let u = &fargs[0];
                            if let Some((u_a, _u_b)) = u.match_linear(var) {
                                if !u_a.is_zero() {
                                    let half_x = AstNode::Div(
                                        Box::new(AstNode::Var(var.to_string())),
                                        Box::new(AstNode::Num(Value::from_int(2))),
                                    );
                                    let two_u = AstNode::Mul(
                                        Box::new(AstNode::Num(Value::from_int(2))),
                                        Box::new(u.clone()),
                                    ).simplify();
                                    let sin_two_u = AstNode::Func("sin".to_string(), vec![two_u]);
                                    let four_a = if u_a.is_one() {
                                        AstNode::Num(Value::from_int(4))
                                    } else {
                                        AstNode::Mul(
                                            Box::new(AstNode::Num(Value::from_int(4))),
                                            Box::new(u_a),
                                        ).simplify()
                                    };
                                    let osc = AstNode::Div(Box::new(sin_two_u), Box::new(four_a)).simplify();
                                    let scaled_osc = if mode == AngleMode::Degrees {
                                        let inv_deg = AstNode::Div(
                                            Box::new(AstNode::Num(Value::from_int(180))),
                                            Box::new(AstNode::Var("pi".to_string())),
                                        );
                                        AstNode::Mul(Box::new(inv_deg), Box::new(osc)).simplify()
                                    } else {
                                        osc
                                    };
                                    return Some(if fname == "sin" {
                                        AstNode::Sub(Box::new(half_x), Box::new(scaled_osc)).simplify()
                                    } else {
                                        AstNode::Add(Box::new(half_x), Box::new(scaled_osc)).simplify()
                                    });
                                }
                            }
                        }
                    }
                }
                let (lin_a, _lin_b) = base.match_linear(var)?;
                if lin_a.is_zero() {
                    return None;
                }
                let unit_a = lin_a.is_one();
                if let Some(er) = try_poly_rat_ast(exp) {
                    if er.den == 1 && er.num == -1 {
                        let ln_node = AstNode::Func(
                            "ln".to_string(),
                            vec![AstNode::Func("abs".to_string(), vec![(**base).clone()])],
                        );
                        return Some(if unit_a {
                            ln_node
                        } else {
                            AstNode::Div(Box::new(ln_node), Box::new(lin_a)).simplify()
                        });
                    }
                    let new_exp = er.add(ExactRat::from_int(1))?;
                    if new_exp.den == 1 && new_exp.num < 0 {
                        let pos_k = new_exp.num.abs();
                        let pow_node = if pos_k == 1 {
                            (**base).clone()
                        } else {
                            AstNode::Pow(base.clone(), Box::new(AstNode::Num(Value::from_int(pos_k))))
                        };
                        let factor_node = AstNode::Num(Value::from_int(pos_k));
                        let denom = if pos_k == 1 {
                            if unit_a {
                                pow_node
                            } else {
                                AstNode::Mul(Box::new(lin_a), Box::new(pow_node)).simplify()
                            }
                        } else {
                            let coeff_node = if unit_a {
                                factor_node
                            } else {
                                AstNode::Mul(Box::new(factor_node), Box::new(lin_a)).simplify()
                            };
                            AstNode::Mul(Box::new(coeff_node), Box::new(pow_node)).simplify()
                        };
                        return Some(
                            AstNode::Neg(Box::new(
                                AstNode::Div(Box::new(AstNode::Num(Value::from_int(1))), Box::new(denom)).simplify(),
                            ))
                            .simplify(),
                        );
                    }
                    let new_exp_val = Value { c: Complex::real(new_exp.to_f64()), rat: Some(new_exp) };
                    let pow_node = if new_exp.den == 1 && new_exp.num == 1 {
                        (**base).clone()
                    } else {
                        AstNode::Pow(base.clone(), Box::new(AstNode::Num(new_exp_val)))
                    };
                    if new_exp.den == 1 {
                        let denom = if unit_a {
                            AstNode::Num(new_exp_val)
                        } else {
                            AstNode::Mul(Box::new(AstNode::Num(new_exp_val)), Box::new(lin_a)).simplify()
                        };
                        return Some(AstNode::Div(Box::new(pow_node), Box::new(denom)).simplify());
                    }
                    let coeff = ExactRat::from_int(1).div(new_exp)?;
                    let coeff_val = Value { c: Complex::real(coeff.to_f64()), rat: Some(coeff) };
                    let base_res = AstNode::Mul(Box::new(AstNode::Num(coeff_val)), Box::new(pow_node)).simplify();
                    return Some(if unit_a {
                        base_res
                    } else {
                        AstNode::Div(Box::new(base_res), Box::new(lin_a)).simplify()
                    });
                }
                if let Some(c_val) = exp.const_value() {
                    if c_val.c.is_real() {
                        if (c_val.c.re + 1.0).abs() < 1e-11 {
                            let ln_node = AstNode::Func(
                                "ln".to_string(),
                                vec![AstNode::Func("abs".to_string(), vec![(**base).clone()])],
                            );
                            return Some(if unit_a {
                                ln_node
                            } else {
                                AstNode::Div(Box::new(ln_node), Box::new(lin_a)).simplify()
                            });
                        }
                        if (c_val.c.re - c_val.c.re.round()).abs() < 1e-11 {
                            let new_p = c_val.c.re.round() as i128 + 1;
                            if new_p < 0 {
                                let pos_k = new_p.abs();
                                let pow_node = if pos_k == 1 {
                                    (**base).clone()
                                } else {
                                    AstNode::Pow(base.clone(), Box::new(AstNode::Num(Value::from_int(pos_k))))
                                };
                                let factor_node = AstNode::Num(Value::from_int(pos_k));
                                let denom = if pos_k == 1 {
                                    if unit_a {
                                        pow_node
                                    } else {
                                        AstNode::Mul(Box::new(lin_a), Box::new(pow_node)).simplify()
                                    }
                                } else {
                                    let coeff_node = if unit_a {
                                        factor_node
                                    } else {
                                        AstNode::Mul(Box::new(factor_node), Box::new(lin_a)).simplify()
                                    };
                                    AstNode::Mul(Box::new(coeff_node), Box::new(pow_node)).simplify()
                                };
                                return Some(
                                    AstNode::Neg(Box::new(
                                        AstNode::Div(Box::new(AstNode::Num(Value::from_int(1))), Box::new(denom)).simplify(),
                                    ))
                                    .simplify(),
                                );
                            }
                            let pow_node = AstNode::Pow(base.clone(), Box::new(AstNode::Num(Value::from_int(new_p))));
                            let denom = if unit_a {
                                AstNode::Num(Value::from_int(new_p))
                            } else {
                                AstNode::Mul(Box::new(AstNode::Num(Value::from_int(new_p))), Box::new(lin_a)).simplify()
                            };
                            return Some(AstNode::Div(Box::new(pow_node), Box::new(denom)).simplify());
                        }
                        let new_p = c_val.c.re + 1.0;
                        let denom = if unit_a {
                            AstNode::Num(Value::from_f64(new_p))
                        } else {
                            AstNode::Mul(Box::new(AstNode::Num(Value::from_f64(new_p))), Box::new(lin_a)).simplify()
                        };
                        return Some(AstNode::Div(
                            Box::new(AstNode::Pow(base.clone(), Box::new(AstNode::Num(Value::from_f64(new_p))))),
                            Box::new(denom),
                        ).simplify());
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
                let inv_deg_scale = || AstNode::Div(
                    Box::new(AstNode::Num(Value::from_int(180))),
                    Box::new(AstNode::Var("pi".to_string())),
                );
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
                        Some(if unit_a { base } else { AstNode::Div(Box::new(base), Box::new(a)).simplify() })
                    }
                    "ln" => {
                        let base = AstNode::Sub(
                            Box::new(AstNode::Mul(
                                Box::new(u.clone()),
                                Box::new(AstNode::Func("ln".to_string(), vec![u.clone()])),
                            )),
                            Box::new(u.clone()),
                        );
                        Some(if unit_a { base } else { AstNode::Div(Box::new(base), Box::new(a)).simplify() })
                    }
                    "sin" => {
                        let cos_u = AstNode::Func("cos".to_string(), vec![u.clone()]);
                        let base = if unit_a {
                            AstNode::Neg(Box::new(cos_u))
                        } else if let AstNode::Neg(inner_a) = a {
                            AstNode::Div(Box::new(cos_u), inner_a).simplify()
                        } else if a.is_negative_display() {
                            AstNode::Div(Box::new(cos_u), Box::new(AstNode::Neg(Box::new(a)).simplify())).simplify()
                        } else {
                            AstNode::Neg(Box::new(AstNode::Div(Box::new(cos_u), Box::new(a)).simplify()))
                        };
                        Some(if mode == AngleMode::Degrees {
                            AstNode::Mul(Box::new(inv_deg_scale()), Box::new(base)).simplify()
                        } else {
                            base
                        })
                    }
                    "cos" => {
                        let sin_u = AstNode::Func("sin".to_string(), vec![u.clone()]);
                        let base = if unit_a {
                            sin_u
                        } else {
                            AstNode::Div(Box::new(sin_u), Box::new(a)).simplify()
                        };
                        Some(if mode == AngleMode::Degrees {
                            AstNode::Mul(Box::new(inv_deg_scale()), Box::new(base)).simplify()
                        } else {
                            base
                        })
                    }
                    "tan" => {
                        let ln_cos_u = AstNode::Func(
                            "ln".to_string(),
                            vec![AstNode::Func("cos".to_string(), vec![u.clone()])],
                        );
                        let base = if unit_a {
                            AstNode::Neg(Box::new(ln_cos_u))
                        } else if let AstNode::Neg(inner_a) = a {
                            AstNode::Div(Box::new(ln_cos_u), inner_a).simplify()
                        } else if a.is_negative_display() {
                            AstNode::Div(Box::new(ln_cos_u), Box::new(AstNode::Neg(Box::new(a)).simplify())).simplify()
                        } else {
                            AstNode::Neg(Box::new(AstNode::Div(Box::new(ln_cos_u), Box::new(a)).simplify()))
                        };
                        Some(if mode == AngleMode::Degrees {
                            AstNode::Mul(Box::new(inv_deg_scale()), Box::new(base)).simplify()
                        } else {
                            base
                        })
                    }
                    "sinh" => {
                        let cosh_u = AstNode::Func("cosh".to_string(), vec![u.clone()]);
                        Some(if unit_a { cosh_u } else { AstNode::Div(Box::new(cosh_u), Box::new(a)).simplify() })
                    }
                    "cosh" => {
                        let sinh_u = AstNode::Func("sinh".to_string(), vec![u.clone()]);
                        Some(if unit_a { sinh_u } else { AstNode::Div(Box::new(sinh_u), Box::new(a)).simplify() })
                    }
                    _ => None,
                }
            }
            AstNode::Add(a, b) => {
                let ia = a.integrate_with_mode(var, mode)?;
                let ib = b.integrate_with_mode(var, mode)?;
                Some(AstNode::Add(Box::new(ia), Box::new(ib)))
            }
            AstNode::Sub(a, b) => {
                let ia = a.integrate_with_mode(var, mode)?;
                let ib = b.integrate_with_mode(var, mode)?;
                Some(AstNode::Sub(Box::new(ia), Box::new(ib)))
            }
            AstNode::Mul(a, b) => {
                if !a.contains_var(var) {
                    let ib = b.integrate_with_mode(var, mode)?;
                    Some(AstNode::Mul(a.clone(), Box::new(ib)).simplify())
                } else if !b.contains_var(var) {
                    let ia = a.integrate_with_mode(var, mode)?;
                    Some(AstNode::Mul(Box::new(ia), b.clone()).simplify())
                } else {
                    for (lin_node, fn_node) in [(a.as_ref(), b.as_ref()), (b.as_ref(), a.as_ref())] {
                        if let AstNode::Func(fname, fargs) = fn_node {
                            if matches!(fname.as_str(), "exp" | "sin" | "cos") && fargs.len() == 1 {
                                if let (Some((lin_a, _)), Some((c, _))) =
                                    (lin_node.match_linear(var), fargs[0].match_linear(var))
                                {
                                    if !lin_a.is_zero() && !c.is_zero() {
                                        let is_deg_trig = mode == AngleMode::Degrees && fname != "exp";
                                        let k = if is_deg_trig {
                                            AstNode::Div(
                                                Box::new(AstNode::Mul(
                                                    Box::new(c),
                                                    Box::new(AstNode::Var("pi".to_string())),
                                                ).simplify()),
                                                Box::new(AstNode::Num(Value::from_int(180))),
                                            )
                                            .simplify()
                                        } else {
                                            c
                                        };
                                        let term1 = if k.is_one() {
                                            lin_node.clone()
                                        } else {
                                            AstNode::Div(Box::new(lin_node.clone()), Box::new(k.clone())).simplify()
                                        };
                                        let term2 = if k.is_one() {
                                            lin_a
                                        } else {
                                            AstNode::Div(
                                                Box::new(AstNode::Div(Box::new(lin_a), Box::new(k.clone())).simplify()),
                                                Box::new(k),
                                            )
                                            .simplify()
                                        };
                                        return match fname.as_str() {
                                            "exp" => {
                                                let poly_factor = AstNode::Sub(Box::new(term1), Box::new(term2)).simplify();
                                                Some(AstNode::Mul(Box::new(poly_factor), Box::new(fn_node.clone())).simplify())
                                            }
                                            "sin" => {
                                                let sin_u = fn_node.clone();
                                                let cos_u = AstNode::Func("cos".to_string(), fargs.clone());
                                                let p1 = AstNode::Mul(Box::new(term2), Box::new(sin_u)).simplify();
                                                let p2 = AstNode::Mul(Box::new(term1), Box::new(cos_u)).simplify();
                                                Some(AstNode::Sub(Box::new(p1), Box::new(p2)).simplify())
                                            }
                                            "cos" => {
                                                let sin_u = AstNode::Func("sin".to_string(), fargs.clone());
                                                let cos_u = fn_node.clone();
                                                let p1 = AstNode::Mul(Box::new(term1), Box::new(sin_u)).simplify();
                                                let p2 = AstNode::Mul(Box::new(term2), Box::new(cos_u)).simplify();
                                                Some(AstNode::Add(Box::new(p1), Box::new(p2)).simplify())
                                            }
                                            _ => None,
                                        };
                                    }
                                }
                            }
                        }
                    }
                    None
                }
            }
            _ => None,
        }
    }

    pub fn simplify(&self) -> AstNode {
        match self {
            AstNode::Num(_) | AstNode::BigInt(_) | AstNode::Var(_) => self.clone(),
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
                } else if let AstNode::BigInt(bi) = sa {
                    AstNode::BigInt(bi.negate())
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
                } else if sb.is_neg_term() {
                    AstNode::Sub(Box::new(sa), Box::new(AstNode::Neg(Box::new(sb)).simplify())).simplify()
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
                } else if sb.is_neg_term() {
                    AstNode::Add(Box::new(sa), Box::new(AstNode::Neg(Box::new(sb)).simplify())).simplify()
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
                    AstNode::Neg(Box::new(AstNode::Mul(Box::new(sa), inner_b).simplify())).simplify()
                } else if let AstNode::Neg(inner_a) = sa {
                    AstNode::Neg(Box::new(AstNode::Mul(inner_a, Box::new(sb)).simplify())).simplify()
                } else if let (AstNode::Num(lv), AstNode::Num(rv)) = (&sa, &sb) {
                    if let (Some(lr), Some(rr)) = (lv.rat, rv.rat) {
                        if let Some(prod) = lr.mul(rr) {
                            return AstNode::Num(Value { c: Complex::real(prod.to_f64()), rat: Some(prod) });
                        }
                    }
                    AstNode::Mul(Box::new(sa), Box::new(sb))
                } else if let (AstNode::Num(lv), AstNode::Mul(rl, rr)) = (&sa, &sb) {
                    if let (Some(lr), AstNode::Num(rlv)) = (lv.rat, rl.as_ref()) {
                        if let Some(rlr) = rlv.rat {
                            if let Some(comb) = lr.mul(rlr) {
                                return AstNode::Mul(
                                    Box::new(AstNode::Num(Value { c: Complex::real(comb.to_f64()), rat: Some(comb) })),
                                    rr.clone(),
                                )
                                .simplify();
                            }
                        }
                    }
                    AstNode::Mul(Box::new(sa), Box::new(sb))
                } else if let (AstNode::Num(lv), AstNode::Div(dnum, dden)) = (&sa, &sb) {
                    if dnum.is_one() {
                        return AstNode::Div(Box::new(AstNode::Num(*lv)), dden.clone()).simplify();
                    }
                    if let (Some(lr), AstNode::Num(drv)) = (lv.rat, dden.as_ref()) {
                        if let Some(drr) = drv.rat {
                            if drr.num != 0 {
                                if let Some(factor) = lr.div(drr) {
                                    if factor.den == 1 {
                                        return AstNode::Mul(
                                            Box::new(AstNode::Num(Value { c: Complex::real(factor.to_f64()), rat: Some(factor) })),
                                            dnum.clone(),
                                        )
                                        .simplify();
                                    }
                                    let num_node = if factor.num == 1 {
                                        (**dnum).clone()
                                    } else {
                                        AstNode::Mul(
                                            Box::new(AstNode::Num(Value::from_int(factor.num))),
                                            dnum.clone(),
                                        )
                                        .simplify()
                                    };
                                    return AstNode::Div(
                                        Box::new(num_node),
                                        Box::new(AstNode::Num(Value::from_int(factor.den))),
                                    )
                                    .simplify();
                                }
                            }
                        }
                    }
                    AstNode::Mul(Box::new(sa), Box::new(sb))
                } else if let (AstNode::Div(dnum, dden), AstNode::Num(rv)) = (&sa, &sb) {
                    if dnum.is_one() {
                        return AstNode::Div(Box::new(AstNode::Num(*rv)), dden.clone()).simplify();
                    }
                    if let (Some(rr), AstNode::Num(drv)) = (rv.rat, dden.as_ref()) {
                        if let Some(drr) = drv.rat {
                            if drr.num != 0 {
                                if let Some(factor) = rr.div(drr) {
                                    if factor.den == 1 {
                                        return AstNode::Mul(
                                            Box::new(AstNode::Num(Value { c: Complex::real(factor.to_f64()), rat: Some(factor) })),
                                            dnum.clone(),
                                        )
                                        .simplify();
                                    }
                                    let num_node = if factor.num == 1 {
                                        (**dnum).clone()
                                    } else {
                                        AstNode::Mul(
                                            Box::new(AstNode::Num(Value::from_int(factor.num))),
                                            dnum.clone(),
                                        )
                                        .simplify()
                                    };
                                    return AstNode::Div(
                                        Box::new(num_node),
                                        Box::new(AstNode::Num(Value::from_int(factor.den))),
                                    )
                                    .simplify();
                                }
                            }
                        }
                    }
                    AstNode::Mul(Box::new(sa), Box::new(sb))
                } else if sa.is_neg_real_num() {
                    AstNode::Neg(Box::new(
                        AstNode::Mul(Box::new(AstNode::Neg(Box::new(sa)).simplify()), Box::new(sb)).simplify(),
                    ))
                    .simplify()
                } else if sb.is_neg_real_num() {
                    AstNode::Neg(Box::new(
                        AstNode::Mul(Box::new(sa), Box::new(AstNode::Neg(Box::new(sb)).simplify())).simplify(),
                    ))
                    .simplify()
                } else {
                    AstNode::Mul(Box::new(sa), Box::new(sb))
                }
            }
            AstNode::Div(a, b) => {
                let sa = a.simplify();
                let sb = b.simplify();
                let num_is_neg = |n: &AstNode| -> bool {
                    matches!(n, AstNode::Num(v) if v.rat.map_or(v.c.re < 0.0, |r| r.num < 0))
                };
                if sa.is_zero() {
                    AstNode::Num(Value::from_int(0))
                } else if sb.is_one() {
                    sa
                } else if sb.is_minus_one() {
                    AstNode::Neg(Box::new(sa)).simplify()
                } else if let AstNode::Neg(inner_b) = sb {
                    AstNode::Neg(Box::new(AstNode::Div(Box::new(sa), inner_b).simplify())).simplify()
                } else if num_is_neg(&sb) {
                    AstNode::Neg(Box::new(AstNode::Div(Box::new(sa), Box::new(AstNode::Neg(Box::new(sb)).simplify())).simplify())).simplify()
                } else if let AstNode::Neg(inner_a) = sa {
                    AstNode::Neg(Box::new(AstNode::Div(inner_a, Box::new(sb)).simplify())).simplify()
                } else if num_is_neg(&sa) {
                    AstNode::Neg(Box::new(AstNode::Div(Box::new(AstNode::Neg(Box::new(sa)).simplify()), Box::new(sb)).simplify())).simplify()
                } else if sa == sb {
                    AstNode::Num(Value::from_int(1))
                } else if let (AstNode::Num(lv), AstNode::Num(rv)) = (&sa, &sb) {
                    if let (Some(lr), Some(rr)) = (lv.rat, rv.rat) {
                        if rr.num != 0 {
                            if let Some(q) = lr.div(rr) {
                                return AstNode::Num(Value { c: Complex::real(q.to_f64()), rat: Some(q) });
                            }
                        }
                    }
                    AstNode::Div(Box::new(sa), Box::new(sb))
                } else if let AstNode::Div(inner_a, inner_b) = sa {
                    AstNode::Div(
                        inner_a,
                        Box::new(AstNode::Mul(inner_b, Box::new(sb)).simplify()),
                    )
                    .simplify()
                } else if let AstNode::Div(inner_a, inner_b) = sb {
                    AstNode::Div(
                        Box::new(AstNode::Mul(Box::new(sa), inner_b).simplify()),
                        inner_a,
                    )
                    .simplify()
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
            AstNode::BigInt(bi) => {
                if bi.neg { 3 } else { 4 }
            }
            AstNode::Pow(_, _) => 3,
            AstNode::Var(_) | AstNode::Func(_, _) => 4,
        }
    }

    fn is_neg_real_num(&self) -> bool {
        match self {
            AstNode::Num(v) => v.rat.map_or(v.c.im == 0.0 && v.c.re < 0.0, |r| r.num < 0),
            AstNode::BigInt(bi) => bi.neg,
            _ => false,
        }
    }

    fn is_neg_term(&self) -> bool {
        matches!(self, AstNode::Neg(_)) || self.is_neg_real_num()
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
            AstNode::BigInt(bi) => bi.neg,
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
            AstNode::BigInt(bi) => bi.to_decimal_string(),
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

    fn div_by(&self, other: &Self) -> Option<Self> {
        let mut vars = self.vars.clone();
        for (k, &den_exp) in &other.vars {
            let num_exp = *vars.get(k).unwrap_or(&0);
            if num_exp < den_exp {
                return None;
            }
            if num_exp == den_exp {
                vars.remove(k);
            } else {
                vars.insert(k.clone(), num_exp - den_exp);
            }
        }
        Some(Self { vars })
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
    if l.len().saturating_mul(r.len()) > 4096 {
        return None;
    }
    let mut res: BTreeMap<PolyMonomial, ExactRat> = BTreeMap::new();
    for (ml, &cl) in l {
        if check_eval_deadline().is_err() {
            return None;
        }
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

fn try_poly_rat_ast(node: &AstNode) -> Option<ExactRat> {
    match node {
        AstNode::Num(v) => v.rat.or_else(|| decimal_to_exact_rat(v.c.re)),
        AstNode::Neg(inner) => try_poly_rat_ast(inner).and_then(|r| ExactRat::new(-r.num, r.den)),
        AstNode::Add(a, b) => try_poly_rat_ast(a)?.add(try_poly_rat_ast(b)?),
        AstNode::Sub(a, b) => try_poly_rat_ast(a)?.sub(try_poly_rat_ast(b)?),
        AstNode::Mul(a, b) => try_poly_rat_ast(a)?.mul(try_poly_rat_ast(b)?),
        AstNode::Div(a, b) => try_poly_rat_ast(a)?.div(try_poly_rat_ast(b)?),
        _ => None,
    }
}

fn ast_to_poly(node: &AstNode) -> Option<BTreeMap<PolyMonomial, ExactRat>> {
    match node {
        AstNode::Num(v) => {
            let r = v.rat.or_else(|| decimal_to_exact_rat(v.c.re))?;
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
            if let Some(sr) = try_poly_rat_ast(r) {
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
            } else {
                let pr = ast_to_poly(r)?;
                if pr.len() != 1 {
                    return None;
                }
                let (den_mon, &den_coeff) = pr.iter().next()?;
                if den_coeff.num == 0 {
                    return None;
                }
                let mut res = BTreeMap::new();
                for (num_mon, num_coeff) in pl {
                    let q_mon = num_mon.div_by(den_mon)?;
                    let c = num_coeff.div(den_coeff)?;
                    if c.num != 0 {
                        res.insert(q_mon, c);
                    }
                }
                Some(res)
            }
        }
        AstNode::Pow(base, exp) => {
            let er = try_poly_rat_ast(exp)?;
            if er.den != 1 || er.num < 0 || er.num > 64 {
                return None;
            }
            let base_poly = ast_to_poly(base)?;
            let mut res = BTreeMap::new();
            res.insert(PolyMonomial::empty(), ExactRat::from_int(1));
            for _ in 0..er.num {
                if check_eval_deadline().is_err() {
                    return None;
                }
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
        AstNode::Num(_) | AstNode::BigInt(_) | AstNode::Var(_) => false,
    }
}

fn count_var_occurrences(node: &AstNode) -> usize {
    match node {
        AstNode::Var(name) => usize::from(name != "i"),
        AstNode::Neg(inner) => count_var_occurrences(inner),
        AstNode::Add(l, r)
        | AstNode::Sub(l, r)
        | AstNode::Mul(l, r)
        | AstNode::Div(l, r)
        | AstNode::Pow(l, r) => count_var_occurrences(l) + count_var_occurrences(r),
        AstNode::Func(_, args) => args.iter().map(count_var_occurrences).sum(),
        AstNode::Num(_) | AstNode::BigInt(_) => 0,
    }
}

fn has_compound_power_base(node: &AstNode) -> bool {
    match node {
        AstNode::Pow(base, exp) => {
            let compound = matches!(
                base.as_ref(),
                AstNode::Mul(_, _) | AstNode::Div(_, _) | AstNode::Pow(_, _)
            );
            (compound && count_var_occurrences(base) > 0)
                || has_compound_power_base(base)
                || has_compound_power_base(exp)
        }
        AstNode::Mul(l, r) | AstNode::Div(l, r) => {
            has_compound_power_base(l) || has_compound_power_base(r)
        }
        AstNode::Neg(inner) => has_compound_power_base(inner),
        _ => false,
    }
}

fn count_numeric_literals(node: &AstNode) -> usize {
    match node {
        AstNode::Num(_) | AstNode::BigInt(_) => 1,
        AstNode::Neg(inner) => count_numeric_literals(inner),
        AstNode::Add(l, r)
        | AstNode::Sub(l, r)
        | AstNode::Mul(l, r)
        | AstNode::Div(l, r)
        | AstNode::Pow(l, r) => count_numeric_literals(l) + count_numeric_literals(r),
        AstNode::Func(_, args) => args.iter().map(count_numeric_literals).sum(),
        AstNode::Var(_) => 0,
    }
}

fn can_simplify_as_polynomial(node: &AstNode) -> bool {
    contains_add_or_sub(node)
        || count_var_occurrences(node) >= 2
        || has_compound_power_base(node)
        || count_numeric_literals(node) >= 2
        || (count_var_occurrences(node) >= 1 && !matches!(node, AstNode::Var(_)))
}

pub fn simplify_polynomial_ast(node: &AstNode) -> Option<AstNode> {
    if can_simplify_as_polynomial(node) {
        if let Some(poly) = ast_to_poly(node) {
            let expanded = poly_to_ast(&poly);
            if poly.len() > 1 || &expanded != node || count_var_occurrences(node) >= 1 {
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
    depth: usize,
}

impl AstParser {
    pub fn parse(input: &str) -> Result<AstNode, String> {
        check_bracket_depth(input)?;
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
            depth: 0,
        };
        p.skip_ws();
        let node = p.parse_expr()?;
        p.skip_ws();
        if p.pos < p.chars.len() {
            return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
        }
        Ok(node)
    }

    fn enter_depth(&mut self) -> Result<(), String> {
        self.depth += 1;
        if self.depth > MAX_NESTING_DEPTH {
            self.depth -= 1;
            return Err("Maximale Verschachtelungstiefe überschritten".to_string());
        }
        Ok(())
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
            if self.pos + 3 <= self.chars.len() {
                let slice: String = self.chars[self.pos..self.pos + 3].iter().collect();
                if slice == "mod"
                    && (self.pos + 3 == self.chars.len()
                        || (!self.chars[self.pos + 3].is_alphanumeric()
                            && self.chars[self.pos + 3] != '_'))
                {
                    self.pos += 3;
                    let right = self.parse_unary()?;
                    left = AstNode::Func("mod".to_string(), vec![left, right]);
                    continue;
                }
            }
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
            self.enter_depth()?;
            let res = self.parse_unary();
            self.depth -= 1;
            res
        } else if self.eat('-') {
            self.enter_depth()?;
            let res = self.parse_unary();
            self.depth -= 1;
            Ok(AstNode::Neg(Box::new(res?)))
        } else if self.eat('√') {
            self.enter_depth()?;
            let res = self.parse_unary();
            self.depth -= 1;
            Ok(AstNode::Func("sqrt".to_string(), vec![res?]))
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
            self.enter_depth()?;
            let inner = self.parse_expr();
            self.depth -= 1;
            let inner = inner?;
            if !self.eat(')') {
                return Err("Fehlende schließende Klammer ')'".to_string());
            }
            return Ok(inner);
        }

        if self.eat('[') {
            self.enter_depth()?;
            let mut entries = Vec::new();
            self.skip_ws();
            if !self.eat(']') {
                loop {
                    let item = self.parse_expr();
                    if let Err(e) = item {
                        self.depth -= 1;
                        return Err(e);
                    }
                    entries.push(item.unwrap());
                    self.skip_ws();
                    if self.eat(',') {
                        continue;
                    } else if self.eat(']') {
                        break;
                    } else {
                        self.depth -= 1;
                        return Err("Erwarte ',' oder ']' in Matrix".to_string());
                    }
                }
            }
            self.depth -= 1;
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
                if let Some(bi) = BigInt::from_decimal_str(&s) {
                    return Ok(AstNode::BigInt(bi));
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
                self.enter_depth()?;
                let mut args = Vec::new();
                self.skip_ws();
                if !self.eat(')') {
                    loop {
                        let arg = self.parse_expr();
                        if let Err(e) = arg {
                            self.depth -= 1;
                            return Err(e);
                        }
                        args.push(arg.unwrap());
                        self.skip_ws();
                        if self.eat(',') {
                            continue;
                        } else if self.eat(')') {
                            break;
                        } else {
                            self.depth -= 1;
                            return Err("Erwarte ',' oder ')' in Funktionsaufruf".to_string());
                        }
                    }
                }
                self.depth -= 1;
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
    depth: usize,
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
            depth: 0,
            engine,
            overrides,
        }
    }

    fn enter_depth(&mut self) -> Result<(), String> {
        self.depth += 1;
        if self.depth > MAX_NESTING_DEPTH {
            self.depth -= 1;
            return Err("Maximale Verschachtelungstiefe überschritten".to_string());
        }
        Ok(())
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
                self.enter_depth()?;
                let res = self.parse_unary();
                self.depth -= 1;
                res
            }
            Some('-') => {
                self.pos += 1;
                self.enter_depth()?;
                let res = self.parse_unary();
                self.depth -= 1;
                let v = res?;
                Ok(Value {
                    c: Complex::new(-v.c.re, -v.c.im),
                    rat: v.rat.map(|r| ExactRat { num: -r.num, den: r.den }),
                })
            }
            Some('√') => {
                self.pos += 1;
                self.enter_depth()?;
                let res = self.parse_unary();
                self.depth -= 1;
                Ok(res?.sqrt())
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
            self.enter_depth()?;
            let res = self.parse_expr();
            self.depth -= 1;
            let v = res?;
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
        let prev = EVAL_DEPTH.with(|d| d.get());
        if prev >= MAX_NESTING_DEPTH {
            return Err("Maximale Verschachtelungstiefe überschritten".to_string());
        }
        EVAL_DEPTH.with(|d| d.set(prev + 1));
        struct EvalDepthGuard(usize);
        impl Drop for EvalDepthGuard {
            fn drop(&mut self) {
                EVAL_DEPTH.with(|d| d.set(self.0));
            }
        }
        let _guard = EvalDepthGuard(prev);
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
                let mut iter = 0usize;
                for k in a..=b {
                    if (iter & 63) == 0 {
                        check_eval_deadline()?;
                    }
                    iter += 1;
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
                let mut iter = 0usize;
                for k in a..=b {
                    if (iter & 63) == 0 {
                        check_eval_deadline()?;
                    }
                    iter += 1;
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
                let parsed = AstParser::parse(&expr_str)
                    .ok()
                    .map(|p| p.expand_derivatives(engine_ref.angle_mode));
                if prev_depth == 0 && !has_inner_int {
                    if let Some(ref ast) = parsed {
                        if let Some(anti) = ast.integrate_with_mode(&var, engine_ref.angle_mode) {
                            if engine_ref.is_antiderivative_valid_on(
                                &anti,
                                &var,
                                a.min(b),
                                a.max(b),
                                Some(&base_ov),
                            ) {
                                let mut local_b = base_ov.clone();
                                local_b.insert(var.clone(), Value::from_f64(b));
                                let mut local_a = base_ov.clone();
                                local_a.insert(var.clone(), Value::from_f64(a));
                                if let (Ok(fb_val), Ok(fa_val)) = (
                                    engine_ref.eval_ast_scalar_with_overrides(&anti, Some(&local_b)),
                                    engine_ref.eval_ast_scalar_with_overrides(&anti, Some(&local_a)),
                                ) {
                                    if fb_val.c.is_real()
                                        && fa_val.c.is_real()
                                        && fb_val.c.re.is_finite()
                                        && fa_val.c.re.is_finite()
                                    {
                                        let fa = fa_val.c.re;
                                        let fb = fb_val.c.re;
                                        let diff = fb - fa;
                                        let scale = fa.abs().max(fb.abs());
                                        let severe_cancellation = scale > 0.0
                                            && diff.abs() <= 1e-5 * scale
                                            && (b - a).abs() <= 10.0;
                                        if diff.is_finite() && !severe_cancellation {
                                            let report = IntegrationReport {
                                                value: diff,
                                                abs_err: 0.0,
                                                converged: true,
                                                subintervals: 1,
                                                evaluations: 2,
                                                message: "Konvergiert: Exakte Stammfunktion ausgewertet (2 Auswertungen)".to_string(),
                                            };
                                            engine_ref.last_integration_report.replace(Some(report));
                                            return Ok(Value::from_f64(diff));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                let mut critical_anchors = Vec::new();
                if let Some(ref ast) = parsed {
                    engine_ref.collect_critical_points(
                        ast,
                        &var,
                        a.min(b),
                        a.max(b),
                        Some(&base_ov),
                        &mut critical_anchors,
                    );
                    if prev_depth == 0 {
                        if let Some(d_ast) = ast.diff_with_mode(&var, engine_ref.angle_mode) {
                            engine_ref.collect_critical_points(
                                &d_ast,
                                &var,
                                a.min(b),
                                a.max(b),
                                Some(&base_ov),
                                &mut critical_anchors,
                            );
                        }
                    }
                }
                let initial_subintervals = if prev_depth > 0 || has_inner_int {
                    16
                } else {
                    2500
                };
                let (eps_abs, eps_rel) = if prev_depth > 0 || has_inner_int {
                    (1e-12, 1e-9)
                } else {
                    (1e-13, 1e-9)
                };

                let report = adaptive_gauss_integrate_with_anchors(
                    |x| {
                        let mut local = base_ov.clone();
                        local.insert(var.clone(), Value::from_f64(x));
                        let v = if !has_inner_int {
                            if let Some(ref ast) = parsed {
                                engine_ref.eval_ast_scalar_with_overrides(ast, Some(&local))?
                            } else {
                                let mut p = ExprParser::new(&expr_str, engine_ref, Some(&local));
                                let v = p.parse_expr()?;
                                p.skip_ws();
                                if p.pos < p.chars.len() {
                                    return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
                                }
                                v
                            }
                        } else {
                            let mut p = ExprParser::new(&expr_str, engine_ref, Some(&local));
                            let v = p.parse_expr()?;
                            p.skip_ws();
                            if p.pos < p.chars.len() {
                                return Err(format!("Unerwartetes Zeichen '{}'", p.chars[p.pos]));
                            }
                            v
                        };
                        if !v.c.is_real() || !v.c.re.is_finite() {
                            return Err("Integrand liefert nicht-reellen oder unendlichen Wert".to_string());
                        }
                        Ok(v.c.re)
                    },
                    a,
                    b,
                    initial_subintervals,
                    eps_abs,
                    eps_rel,
                    &critical_anchors,
                )?;
                engine_ref.last_integration_report.replace(Some(report.clone()));
                return Ok(Value::from_f64(report.value));
            }
            "deriv" | "diff" | "d" => {
                if raw_args.len() < 2 || raw_args.len() > 3 {
                    return Err("deriv(Ausdruck, x, [x0]) erwartet 2 oder 3 Argumente".to_string());
                }
                if raw_args.len() == 2 {
                    let var = raw_args[1].trim();
                    if let Ok(ast) = AstParser::parse(&raw_args[0]) {
                        if let Some(d_ast) = ast.diff_with_mode(var, self.engine.angle_mode) {
                            return self.engine.eval_ast_scalar_with_overrides(&d_ast.simplify(), self.overrides);
                        }
                    }
                    return Err("Symbolic derivative handled in preview".to_string());
                }
                let var = raw_args[1].trim();
                let x0_val = self.eval_arg(&raw_args[2], None)?;
                if !x0_val.c.is_real() || !x0_val.c.re.is_finite() {
                    return Err("Ableitungsstelle muss eine endliche reelle Zahl sein".to_string());
                }
                let x0 = x0_val.c.re;
                let base_ov = self.overrides.cloned().unwrap_or_default();
                let eval_real_at = |x: f64| -> Result<f64, String> {
                    let mut local = base_ov.clone();
                    local.insert(var.to_string(), Value::from_f64(x));
                    let val = self.eval_arg(&raw_args[0], Some(&local))?;
                    if !val.c.is_real() || !val.c.re.is_finite() {
                        return Err("Funktion an der Stelle nicht reell differenzierbar".to_string());
                    }
                    Ok(val.c.re)
                };
                let f0 = eval_real_at(x0)?;
                if let Ok(ast) = AstParser::parse(&raw_args[0]) {
                    if !ast.contains_step_function() {
                        if let Some(d_ast) = ast.diff_with_mode(var, self.engine.angle_mode) {
                            let mut local = base_ov.clone();
                            local.insert(var.to_string(), Value::from_f64(x0));
                            if let Ok((d_val, cancel_err)) = self
                                .engine
                                .eval_ast_with_cancel_err(&d_ast.simplify(), Some(&local))
                            {
                                if d_val.c.is_real()
                                    && d_val.c.re.is_finite()
                                    && cancel_err <= 1e-3 * d_val.c.re.abs()
                                {
                                    return Ok(Value::from_f64(d_val.c.re));
                                }
                            }
                        }
                    }
                }
                let h1 = 1e-7 * x0.abs().max(1.0);
                let h2 = 1e-5 * x0.abs().max(1.0);
                if let (Ok(fp1_k), Ok(fm1_k), Ok(fp2_k), Ok(fm2_k)) = (
                    eval_real_at(x0 + h1),
                    eval_real_at(x0 - h1),
                    eval_real_at(x0 + h2),
                    eval_real_at(x0 - h2),
                ) {
                    let jump1 = (fp1_k - fm1_k).abs();
                    let jump2 = (fp2_k - fm2_k).abs();
                    let val_scale = f0.abs().max(fp1_k.abs()).max(fm1_k.abs()).max(1.0);
                    if jump1 > 1e-4 * val_scale && jump2 < 2.5 * jump1 {
                        return Err("Funktion an der Stelle nicht stetig / nicht differenzierbar".to_string());
                    }
                    let dr1 = (fp1_k - f0) / h1;
                    let dl1 = (f0 - fm1_k) / h1;
                    let dr2 = (fp2_k - f0) / h2;
                    let dl2 = (f0 - fm2_k) / h2;
                    let slope_scale = dr1.abs().max(dl1.abs()).max(1.0);
                    if (dr1 - dr2).abs() <= 1e-2 * slope_scale
                        && (dl1 - dl2).abs() <= 1e-2 * slope_scale
                        && (dr1 - dl1).abs() > 1e-3 * slope_scale
                    {
                        return Err("Funktion an der Stelle nicht differenzierbar".to_string());
                    }
                    if fp1_k == f0 && fm1_k == f0 && fp2_k == f0 && fm2_k == f0 {
                        return Ok(Value::from_f64(0.0));
                    }
                }
                let mut h = 1e-4 * x0.abs().max(1.0);
                let mut adjusted = false;
                let mut stencil = None;
                for _ in 0..40 {
                    if let (Ok(fp2), Ok(fp1), Ok(fm1), Ok(fm2)) = (
                        eval_real_at(x0 + 2.0 * h),
                        eval_real_at(x0 + h),
                        eval_real_at(x0 - h),
                        eval_real_at(x0 - 2.0 * h),
                    ) {
                        stencil = Some((fp2, fp1, fm1, fm2));
                        break;
                    }
                    h *= 0.5;
                    adjusted = true;
                    if !h.is_finite() || h == 0.0 || x0 + h == x0 || x0 - h == x0 {
                        return Err("Funktion an der Stelle nicht reell differenzierbar".to_string());
                    }
                }
                let (mut fp2, mut fp1, mut fm1, mut fm2) = stencil
                    .ok_or_else(|| "Funktion an der Stelle nicht reell differenzierbar".to_string())?;
                if adjusted {
                    let safe_h = h * 0.25;
                    if safe_h > 0.0 && x0 + safe_h != x0 && x0 - safe_h != x0 {
                        if let (Ok(sp2), Ok(sp1), Ok(sm1), Ok(sm2)) = (
                            eval_real_at(x0 + 2.0 * safe_h),
                            eval_real_at(x0 + safe_h),
                            eval_real_at(x0 - safe_h),
                            eval_real_at(x0 - 2.0 * safe_h),
                        ) {
                            h = safe_h;
                            fp2 = sp2;
                            fp1 = sp1;
                            fm1 = sm1;
                            fm2 = sm2;
                        }
                    }
                } else if f0.abs() > 0.0 && (fp1 - fm1).abs() < 1e-7 * f0.abs() {
                    let h_wide = 1e-2 * x0.abs().max(1.0);
                    if h_wide > h && x0 + h_wide != x0 && x0 - h_wide != x0 {
                        if let (Ok(wp2), Ok(wp1), Ok(wm1), Ok(wm2)) = (
                            eval_real_at(x0 + 2.0 * h_wide),
                            eval_real_at(x0 + h_wide),
                            eval_real_at(x0 - h_wide),
                            eval_real_at(x0 - 2.0 * h_wide),
                        ) {
                            h = h_wide;
                            fp2 = wp2;
                            fp1 = wp1;
                            fm1 = wm1;
                            fm2 = wm2;
                        }
                    }
                }
                let h_eff = 0.5 * ((x0 + h) - (x0 - h));
                let denom = 12.0 * if h_eff > 0.0 { h_eff } else { h };
                let mut deriv_cur = (8.0 * (fp1 - fm1) - (fp2 - fm2)) / denom;
                let mut best = deriv_cur;
                let mut scale = f0.abs().max(fp1.abs()).max(fm1.abs()).max(fp2.abs()).max(fm2.abs());
                let mut cur_h = h;
                let x_ulp = x0.abs() * f64::EPSILON;
                for _ in 0..20 {
                    let h_half = cur_h * 0.5;
                    if h_half <= 0.0 || x0 + h_half == x0 || x0 - h_half == x0 {
                        break;
                    }
                    let Ok(hp2) = eval_real_at(x0 + 2.0 * h_half) else { break; };
                    let Ok(hp1) = eval_real_at(x0 + h_half) else { break; };
                    let Ok(hm1) = eval_real_at(x0 - h_half) else { break; };
                    let Ok(hm2) = eval_real_at(x0 - 2.0 * h_half) else { break; };
                    let h_half_eff = 0.5 * ((x0 + h_half) - (x0 - h_half));
                    let denom_half = 12.0 * if h_half_eff > 0.0 { h_half_eff } else { h_half };
                    let deriv_half = (8.0 * (hp1 - hm1) - (hp2 - hm2)) / denom_half;
                    scale = scale.max(hp1.abs()).max(hm1.abs()).max(hp2.abs()).max(hm2.abs());
                    let noise_bound = (64.0 * f64::EPSILON * scale + 8.0 * deriv_cur.abs() * x_ulp) / h_half;
                    let diff = (deriv_half - deriv_cur).abs();
                    if !deriv_half.is_finite() || diff <= 4.0 * noise_bound {
                        break;
                    }
                    let richardson = (16.0 * deriv_half - deriv_cur) / 15.0;
                    if !richardson.is_finite() {
                        break;
                    }
                    best = richardson;
                    if diff <= 1e-9 * richardson.abs().max(1.0) {
                        break;
                    }
                    deriv_cur = deriv_half;
                    cur_h = h_half;
                }
                return Ok(Value::from_f64(best));
            }
            "integral" => {
                return Err("Symbolische Integration nicht möglich".to_string());
            }
            "det" => {
                if raw_args.len() != 1 {
                    return Err("det(Matrix) benötigt genau 1 Argument".to_string());
                }
                let mat = self.engine.eval_matrix_expr(&raw_args[0])?;
                return matrix_det(&mat);
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
                if v0.c.re.abs() >= 9.0e15 {
                    return Ok(Value::from_f64(v0.c.re.floor()));
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
                if v0.c.re.abs() >= 9.0e15 {
                    return Ok(Value::from_f64(v0.c.re.ceil()));
                }
                Ok(Value::from_int(v0.c.re.ceil() as i128))
            }
            "round" => {
                if !v0.c.is_real() || !v0.c.re.is_finite() {
                    return Err("round erfordert endliche reelle Zahl".to_string());
                }
                if let Some(r) = v0.rat {
                    let q = r.num / r.den;
                    let rem = (r.num % r.den).abs();
                    let rounded = if rem.saturating_mul(2) >= r.den {
                        if r.num >= 0 { q + 1 } else { q - 1 }
                    } else {
                        q
                    };
                    return Ok(Value::from_int(rounded));
                }
                if v0.c.re.abs() >= 9.0e15 {
                    return Ok(Value::from_f64(v0.c.re.round()));
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
        match ident {
            "sigma" => return Ok(Value::from_f64(crate::constants::STEFAN_BOLTZMANN_CONST)),
            "G0" => return Ok(Value::from_f64(crate::constants::CONDUCTANCE_QUANTUM)),
            "E_h" => return Ok(Value::from_f64(crate::constants::HARTREE_ENERGY)),
            "m_u" => return Ok(Value::from_f64(crate::constants::ATOMIC_MASS_CONST)),
            "Phi0" => return Ok(Value::from_f64(crate::constants::MAGNETIC_FLUX_QUANTUM)),
            "m_mu" => return Ok(Value::from_f64(crate::constants::MASS_MUON)),
            "r_e" => return Ok(Value::from_f64(crate::constants::CLASSICAL_ELECTRON_RADIUS)),
            "lambda_C" => return Ok(Value::from_f64(crate::constants::COMPTON_WAVELENGTH)),
            "a_0" => return Ok(Value::from_f64(crate::constants::BOHR_RADIUS)),
            "mu_N" => return Ok(Value::from_f64(crate::constants::NUCLEAR_MAGNETON)),
            _ => {}
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
