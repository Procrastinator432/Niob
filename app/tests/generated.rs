//! Generated cases with independent reference values (see tools/generate_test_cases.py).
//! The same fixture files are checked by the Android adapter GeneratedCases.java, so both
//! engines are compared against the same references. This imports the production modules.
#![allow(dead_code)]

#[path = "../src/constants.rs"]
mod constants;
#[path = "../src/engine.rs"]
mod engine;

use engine::{AngleMode, EvalOutput, MathEngine, NumberFormatMode};

const DERIVATIVES: &str = include_str!("data/generated/derivatives.tsv");
const INTEGRALS: &str = include_str!("data/generated/integrals.tsv");
const MATRICES: &str = include_str!("data/generated/matrices.tsv");
const EXACT_NUMBERS: &str = include_str!("data/generated/exact_numbers.tsv");
const ANTIDERIVATIVES: &str = include_str!("data/generated/antiderivatives.tsv");
const POLYNOMIALS: &str = include_str!("data/generated/polynomials.tsv");

/// Generous per-case time limit so that slow debug builds do not turn into timeouts.
const TIMEOUT_MS: u64 = 30_000;
const SHOWN_FAILURES: usize = 30;

struct Row<'a> {
    id: &'a str,
    expr: &'a str,
    check: &'a str,
    expected: &'a str,
    abs_tol: f64,
    rel_tol: f64,
    degrees: bool,
}

fn rows(table: &str) -> Vec<Row<'_>> {
    table
        .lines()
        .filter(|line| !line.trim().is_empty() && !line.starts_with('#'))
        .map(|line| {
            let c: Vec<&str> = line.split('\t').collect();
            assert_eq!(c.len(), 8, "fixture schema: {line}");
            Row {
                id: c[0],
                expr: c[2],
                check: c[3],
                expected: c[4],
                abs_tol: c[5].parse().expect("absolute tolerance"),
                rel_tol: c[6].parse().expect("relative tolerance"),
                degrees: c[7] == "deg",
            }
        })
        .collect()
}

fn new_engine(degrees: bool) -> MathEngine {
    let mut engine = MathEngine::default();
    engine.number_format = NumberFormatMode::NonePoint;
    if degrees {
        engine.angle_mode = AngleMode::Degrees;
    }
    engine
}

fn eval(engine: &mut MathEngine, expr: &str) -> Result<EvalOutput, String> {
    engine.evaluate_with_timeout(expr, false, TIMEOUT_MS)
}

fn compact(text: &str) -> String {
    text.chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>()
        .replace('·', "*")
        .replace('−', "-")
}

/// Parses a displayed number ("1.5", "1e-20", "1.2 × 10⁻²⁰", "3 / 2") and returns the value
/// together with half a unit of its last displayed digit.
fn parse_display(text: &str) -> Option<(f64, f64)> {
    let t = compact(text);
    if let Some((n, d)) = t.split_once('/') {
        let n: f64 = n.parse().ok()?;
        let d: f64 = d.parse().ok()?;
        return Some((n / d, 0.0));
    }
    let (mantissa, exponent) = if let Some((m, e)) = t.split_once("×10") {
        let e: String = e
            .chars()
            .map(|ch| match ch {
                '⁰' => '0', '¹' => '1', '²' => '2', '³' => '3', '⁴' => '4', '⁵' => '5',
                '⁶' => '6', '⁷' => '7', '⁸' => '8', '⁹' => '9', '⁻' => '-', '⁺' => '+',
                other => other,
            })
            .collect();
        (m.to_string(), e.parse::<i32>().ok()?)
    } else if let Some(pos) = t.find(|c| c == 'e' || c == 'E') {
        (t[..pos].to_string(), t[pos + 1..].parse::<i32>().ok()?)
    } else {
        (t.clone(), 0)
    };
    let m: f64 = mantissa.parse().ok()?;
    let decimals = mantissa.split_once('.').map(|(_, f)| f.len() as i32).unwrap_or(0);
    let scale = 10f64.powi(exponent);
    let value = m * scale;
    if !value.is_finite() {
        return None;
    }
    Some((value, 0.5 * 10f64.powi(-decimals) * scale))
}

fn number_problem(out: &EvalOutput, expected: f64, abs_tol: f64, rel_tol: f64) -> Option<String> {
    let Some(c) = out.numeric_value else {
        return Some(format!("kein Zahlenergebnis: {}", out.decimal_text));
    };
    let tol = abs_tol + rel_tol * expected.abs();
    if !c.re.is_finite() || (c.re - expected).abs() > tol {
        return Some(format!("erwartet {expected:e}, erhalten {:e}", c.re));
    }
    if c.im.abs() > 1e-12 * c.re.abs().max(1.0) {
        return Some(format!("nicht reell: {}", out.decimal_text));
    }
    match parse_display(&out.decimal_text) {
        None => Some(format!("Anzeige ist keine Zahl: {}", out.decimal_text)),
        Some((shown, rounding)) if (shown - expected).abs() > tol + rounding => {
            Some(format!("Wert stimmt, Anzeige {} weicht ab (erwartet {expected:e})", out.decimal_text))
        }
        _ => None,
    }
}

/// Replaces the variables in `text` by parenthesised numbers so that the engine can evaluate it.
fn substitute(text: &str, values: &[(&str, f64)]) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    while i < chars.len() {
        let ch = chars[i];
        if ch.is_alphabetic() || ch == '_' {
            let start = i;
            while i < chars.len() && (chars[i].is_alphanumeric() || chars[i] == '_') {
                i += 1;
            }
            let ident: String = chars[start..i].iter().collect();
            if let Some((_, v)) = values.iter().find(|(name, _)| *name == ident) {
                if out.ends_with(|c: char| c.is_ascii_digit() || c == ')' || c == '.') {
                    out.push('*');
                }
                out.push_str(&format!("({v:?})"));
            } else {
                out.push_str(&ident);
            }
        } else {
            out.push(ch);
            i += 1;
        }
    }
    out
}

fn real_value(engine: &mut MathEngine, text: &str) -> Option<f64> {
    let out = eval(engine, text).ok()?;
    let c = out.numeric_value?;
    if c.re.is_finite() && c.im.abs() <= 1e-9 * c.re.abs().max(1.0) {
        Some(c.re)
    } else {
        None
    }
}

fn parse_matrix(text: &str) -> Option<Vec<Vec<(f64, f64)>>> {
    let t = text.trim();
    let inner = t.strip_prefix('[')?.strip_suffix(']')?;
    let mut result = Vec::new();
    for row in inner.split(']') {
        let row = row.trim().trim_start_matches(',').trim();
        if row.is_empty() {
            continue;
        }
        let row = row.strip_prefix('[')?;
        result.push(row.split(',').map(parse_display).collect::<Option<Vec<_>>>()?);
    }
    Some(result)
}

fn parse_float_matrix(text: &str) -> Vec<Vec<f64>> {
    let inner = &text[1..text.len() - 1];
    inner
        .split("],[")
        .map(|r| r.trim_matches(|c| c == '[' || c == ']').split(',').map(|v| v.parse().unwrap()).collect())
        .collect()
}

enum Outcome {
    Pass,
    Fail(String),
    Unsupported,
    Unchecked,
}

fn check_row(row: &Row) -> Outcome {
    let mut engine = new_engine(row.degrees);
    let result = eval(&mut engine, row.expr);
    let fail = |msg: String| Outcome::Fail(msg);
    match row.check {
        "number" | "number_or_error" => match result {
            Err(_) if row.check == "number_or_error" => Outcome::Pass,
            Err(e) => fail(format!("Fehler: {e}")),
            Ok(out) => match number_problem(&out, row.expected.parse().unwrap(), row.abs_tol, row.rel_tol) {
                None => Outcome::Pass,
                Some(p) => fail(p),
            },
        },
        "error" => match result {
            Err(_) => Outcome::Pass,
            Ok(out) => fail(format!("Fehlermeldung erwartet, erhalten {}", out.decimal_text)),
        },
        "integral" => match result {
            Err(_) => Outcome::Pass,
            Ok(out) => {
                if out.integration_report.as_ref().is_some_and(|r| !r.converged) {
                    return Outcome::Pass;
                }
                match number_problem(&out, row.expected.parse().unwrap(), row.abs_tol, row.rel_tol) {
                    None => Outcome::Pass,
                    Some(p) => fail(format!("{p} (als konvergiert gemeldet)")),
                }
            }
        },
        "exact_integer" => match result {
            Err(e) => fail(format!("Fehler: {e}")),
            Ok(out) if compact(&out.exact_text) == row.expected => Outcome::Pass,
            Ok(out) => fail(format!("erwartet exakt {}, erhalten {}", short(row.expected), short(&out.exact_text))),
        },
        "exact_fraction" => match result {
            Err(e) => fail(format!("Fehler: {e}")),
            Ok(out) => {
                if compact(&out.exact_text) == row.expected {
                    return Outcome::Pass;
                }
                let (n, d) = row.expected.split_once('/').unwrap();
                let expected = n.parse::<f64>().unwrap() / d.parse::<f64>().unwrap();
                match number_problem(&out, expected, 0.0, row.rel_tol) {
                    None => Outcome::Pass,
                    Some(p) => fail(format!("{p}; exakt erwartet {}", short(row.expected))),
                }
            }
        },
        "matrix" => match result {
            Err(e) => fail(format!("Fehler: {e}")),
            Ok(out) => {
                let (exp_txt, bound_txt) = row.expected.split_once(';').unwrap();
                let expected = parse_float_matrix(exp_txt);
                let bound = parse_float_matrix(bound_txt);
                let Some(actual) = parse_matrix(&out.decimal_text) else {
                    return fail(format!("keine Matrix: {}", out.decimal_text));
                };
                if actual.len() != expected.len() || actual.iter().zip(&expected).any(|(a, e)| a.len() != e.len()) {
                    return fail(format!("falsche Form: {}", out.decimal_text));
                }
                for i in 0..expected.len() {
                    for j in 0..expected[i].len() {
                        let (v, rounding) = actual[i][j];
                        let e = expected[i][j];
                        let tol = row.rel_tol * e.abs() + 1e4 * f64::EPSILON * bound[i][j] + rounding;
                        if (v - e).abs() > tol {
                            return fail(format!("Eintrag ({},{}) erwartet {e:e}, erhalten {v:e}", i + 1, j + 1));
                        }
                    }
                }
                Outcome::Pass
            }
        },
        "antiderivative" => match result {
            Err(e) if e.contains("nicht möglich") => Outcome::Unsupported,
            Err(e) => fail(format!("Fehler: {e}")),
            Ok(out) => {
                let f_text = out.exact_text.clone();
                let mut checked = 0;
                for sample in row.expected.split(';') {
                    let (x0, fx) = sample.split_once(':').unwrap();
                    let x0: f64 = x0.parse().unwrap();
                    let fx: f64 = fx.parse().unwrap();
                    // Five-point difference of F at several step sizes: a correct F matches at one of
                    // them, also close to a pole where the largest step is too coarse.
                    let mut evaluated = false;
                    let mut matched = false;
                    let mut last = f64::NAN;
                    for step in [1e-3, 2.5e-4, 6.25e-5, 1e-5] {
                        let h = step * x0.abs().max(1.0);
                        let mut values = Vec::new();
                        for k in [-2.0, -1.0, 1.0, 2.0] {
                            match real_value(&mut engine, &substitute(&f_text, &[("x", x0 + k * h)])) {
                                Some(v) => values.push(v),
                                None => break,
                            }
                        }
                        if values.len() != 4 {
                            continue;
                        }
                        evaluated = true;
                        let deriv = (8.0 * (values[2] - values[1]) - (values[3] - values[0])) / (12.0 * h);
                        let big = values.iter().fold(0.0_f64, |m, v| m.max(v.abs()));
                        let tol = row.abs_tol + row.rel_tol * fx.abs() + 1e3 * f64::EPSILON * big / h;
                        last = deriv;
                        if (deriv - fx).abs() <= tol {
                            matched = true;
                            break;
                        }
                    }
                    if !evaluated {
                        continue;
                    }
                    checked += 1;
                    if !matched {
                        return fail(format!("F = {} hat bei x = {x0} die Ableitung {last:e}, erwartet {fx:e}", short(&f_text)));
                    }
                }
                if checked == 0 {
                    Outcome::Unchecked
                } else {
                    Outcome::Pass
                }
            }
        },
        "polynomial" => match result {
            Err(e) => fail(format!("Fehler: {e}")),
            Ok(out) => {
                let text = compact(&out.exact_text);
                if text.contains(")^") || text.contains(")*(") || text.contains(")(") {
                    return fail(format!("nicht ausmultipliziert: {}", short(&text)));
                }
                for sample in row.expected.split(';') {
                    let (assign, value) = sample.split_once(':').unwrap();
                    let value: f64 = value.parse().unwrap();
                    let vars: Vec<(&str, f64)> = assign
                        .split(',')
                        .map(|a| {
                            let (n, v) = a.split_once('=').unwrap();
                            (n, v.parse().unwrap())
                        })
                        .collect();
                    let Some(actual) = real_value(&mut engine, &substitute(&text, &vars)) else {
                        return fail(format!("Ergebnis {} nicht auswertbar", short(&text)));
                    };
                    if (actual - value).abs() > row.abs_tol + row.rel_tol * value.abs() {
                        return fail(format!("{} ergibt bei {assign} {actual:e}, erwartet {value:e}", short(&text)));
                    }
                }
                Outcome::Pass
            }
        },
        other => panic!("unbekannte Prüfung {other}"),
    }
}

fn short(text: &str) -> String {
    if text.chars().count() > 90 {
        format!("{}…", text.chars().take(90).collect::<String>())
    } else {
        text.to_string()
    }
}

fn run_table(name: &str, table: &str) {
    let rows = rows(table);
    assert!(!rows.is_empty(), "{name}: keine Fälle");
    let mut failures = Vec::new();
    let (mut unsupported, mut unchecked) = (0, 0);
    for row in &rows {
        match check_row(row) {
            Outcome::Pass => {}
            Outcome::Unsupported => unsupported += 1,
            Outcome::Unchecked => unchecked += 1,
            Outcome::Fail(msg) => failures.push(format!("  {} {}: {msg}", row.id, short(row.expr))),
        }
    }
    let mut summary = format!("{name}: {} Fälle, {} falsch", rows.len(), failures.len());
    if unsupported > 0 {
        summary.push_str(&format!(", {unsupported} nicht unterstützt"));
    }
    if unchecked > 0 {
        summary.push_str(&format!(", {unchecked} ohne prüfbare Stelle"));
    }
    println!("{summary}");
    if !failures.is_empty() {
        // NIOB_GENERATED_ALL=1 lists every failing case instead of the first few.
        let limit = if std::env::var_os("NIOB_GENERATED_ALL").is_some() { usize::MAX } else { SHOWN_FAILURES };
        let shown: Vec<String> = failures.iter().take(limit).cloned().collect();
        let more = if failures.len() > limit {
            format!("\n  … und {} weitere (alle mit NIOB_GENERATED_ALL=1)", failures.len() - limit)
        } else {
            String::new()
        };
        panic!("{summary}\n{}{more}", shown.join("\n"));
    }
}

#[test]
fn generated_derivatives() {
    run_table("generated_derivatives", DERIVATIVES);
}

#[test]
fn generated_integrals() {
    run_table("generated_integrals", INTEGRALS);
}

#[test]
fn generated_matrices() {
    run_table("generated_matrices", MATRICES);
}

#[test]
fn generated_exact_numbers() {
    run_table("generated_exact_numbers", EXACT_NUMBERS);
}

#[test]
fn generated_antiderivatives() {
    run_table("generated_antiderivatives", ANTIDERIVATIVES);
}

#[test]
fn generated_polynomials() {
    run_table("generated_polynomials", POLYNOMIALS);
}
