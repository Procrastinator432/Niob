//! Desired behavior, including currently failing cases from the APK comparison.
//! This imports the production modules; no copied or replacement engine is used.
#![allow(dead_code)]

#[path = "../src/constants.rs"]
mod constants;
#[path = "../src/engine.rs"]
mod engine;

use engine::{AngleMode, EvalOutput, MathEngine, NumberFormatMode};

const CASES: &str = include_str!("data/regressions.tsv");

fn new_engine() -> MathEngine {
    let mut engine = MathEngine::default();
    engine.number_format = NumberFormatMode::NonePoint;
    engine
}

fn close(actual: f64, expected: f64, absolute: f64, relative: f64) {
    let tolerance = absolute + relative * expected.abs();
    assert!(
        actual.is_finite() && (actual - expected).abs() <= tolerance,
        "expected {expected}, got {actual}; tolerance {tolerance}"
    );
}

fn normalized(text: &str) -> String {
    text.chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>()
        .replace('·', "*")
}

fn displayed_number(text: &str) -> f64 {
    let text = normalized(text).replace('−', "-");
    if let Some((mantissa, exponent)) = text.split_once("×10") {
        let exponent: String = exponent
            .chars()
            .map(|ch| match ch {
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
                '⁻' => '-',
                '⁺' => '+',
                other => other,
            })
            .collect();
        return mantissa.parse::<f64>().expect("numeric mantissa")
            * 10_f64.powi(exponent.parse().expect("numeric exponent"));
    }
    text.parse()
        .unwrap_or_else(|_| panic!("decimal display is not a number: {text}"))
}

fn check_number(output: EvalOutput, expected: f64, absolute: f64, relative: f64) {
    let value = output.numeric_value.expect("numeric result required");
    close(value.re, expected, absolute, relative);
    close(value.im, 0.0, 1e-12, 0.0);
    // Check the user-visible decimal result as well as the internal value.
    close(
        displayed_number(&output.decimal_text),
        expected,
        absolute,
        relative,
    );
}

fn run_case(id: &str) {
    let row = CASES
        .lines()
        .filter(|line| !line.starts_with('#'))
        .map(|line| line.split('\t').collect::<Vec<_>>())
        .find(|columns| columns.first() == Some(&id))
        .expect("test case exists");
    assert_eq!(row.len(), 7, "fixture schema for {id}");
    let mut engine = new_engine();
    let expression = row[2];
    match row[3] {
        "error" => assert!(
            engine.evaluate_preview(expression, false).is_err(),
            "{expression}: expected an error ({})",
            row[4]
        ),
        "assignment" => {
            engine
                .evaluate_and_commit(expression)
                .expect("assignment must succeed");
            check_number(
                engine
                    .evaluate_preview("m*4", false)
                    .expect("assigned variable"),
                row[4].parse().unwrap(),
                row[5].parse().unwrap(),
                row[6].parse().unwrap(),
            );
        }
        "number" => check_number(
            engine
                .evaluate_preview(expression, false)
                .unwrap_or_else(|error| panic!("{expression}: {error}")),
            row[4].parse().unwrap(),
            row[5].parse().unwrap(),
            row[6].parse().unwrap(),
        ),
        "symbolic_samples" => {
            let output = engine
                .evaluate_preview(expression, false)
                .unwrap_or_else(|error| panic!("{expression}: {error}"));
            for sample in row[4].split(';') {
                let (x, expected) = sample.split_once(':').expect("x:expected sample");
                let x: f64 = x.parse().unwrap();
                let actual = engine.eval_for_x(&output.exact_text, x).unwrap_or_else(|| {
                    panic!(
                        "symbolic result cannot be reused at x={x}: {}",
                        output.exact_text
                    )
                });
                close(
                    actual,
                    expected.parse().unwrap(),
                    row[5].parse().unwrap(),
                    row[6].parse().unwrap(),
                );
            }
        }
        "exact" => {
            let output = engine
                .evaluate_preview(expression, false)
                .unwrap_or_else(|error| panic!("{expression}: {error}"));
            assert!(
                row[4]
                    .split('|')
                    .any(|expected| normalized(expected) == normalized(&output.exact_text)),
                "{expression}: expected exact {}, got {}",
                row[4],
                output.exact_text
            );
        }
        other => panic!("unsupported fixture check: {other}"),
    }
}

macro_rules! cases {
    ($($name:ident),+ $(,)?) => {
        const DECLARED_CASES: &[&str] = &[$(stringify!($name)),+];
        $(#[test] fn $name() { run_case(stringify!($name)); })+
    };
}

cases!(
    basic,
    power,
    unary,
    fraction,
    trig,
    sum,
    product,
    integral,
    derivative,
    implicit,
    scientific,
    asinh,
    acosh,
    atanh,
    complex_i,
    complex_root,
    matrix_det,
    matrix_add,
    modulo,
    assignment,
    standalone_percent,
    symbolic,
    symbolic_derivative,
    indefinite_integral,
    small_number,
    planck,
    tiny_product,
    large_integer,
    false_exact,
    root_exact,
    binomial_large,
    factorial_fractional,
    factorial_negative,
    divide_zero,
    derivative_large,
    derivative_oscillating,
    integral_oscillating,
    symbolic_unknown,
    history_reuse,
    matrix_4x4,
    integral_alias_1000,
    integral_alias_2000,
    integral_alias_4000,
    integral_inverse_power,
    symbolic_sine_derivative,
    symbolic_chain_rule,
    symbolic_exponential_integral,
    matrix_singular_inverse,
    matrix_incompatible_dimensions,
    complex_sine_identity,
    complex_euler_identity,
    symbolic_cosine_derivative,
    symbolic_sinh_derivative,
    symbolic_cosh_derivative,
    symbolic_sin_integral,
    symbolic_cos_integral,
    symbolic_variable_power_samples,
    symbolic_fractional_power_samples,
    symbolic_negative_power_samples,
    symbolic_chain_serialization_samples,
    invalid_integrand_propagates_error
);

#[test]
fn fixture_covers_every_declared_case() {
    let rows: Vec<_> = CASES
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .collect();
    assert_eq!(rows.len(), DECLARED_CASES.len());
    assert!(!rows.is_empty());
    let mut ids = std::collections::HashSet::new();
    for row in rows {
        let columns: Vec<_> = row.split('\t').collect();
        assert_eq!(columns.len(), 7);
        assert!(ids.insert(columns[0]), "duplicate fixture ID");
    }
    // The macro must execute each fixture, rather than silently leaving new rows untested.
    let declared: std::collections::HashSet<_> = DECLARED_CASES.iter().copied().collect();
    assert_eq!(ids, declared);
}

#[test]
fn complex_variable_preserves_value() {
    let mut engine = new_engine();
    engine
        .evaluate_and_commit("m:=i")
        .expect("complex assignment");
    check_number(
        engine.evaluate_preview("m*m", false).unwrap(),
        -1.0,
        1e-12,
        0.0,
    );
}

#[test]
fn division_by_zero_does_not_change_ans() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("42").unwrap();
    assert!(
        engine.evaluate_and_commit("1/0").is_err(),
        "invalid operation must fail"
    );
    check_number(
        engine.evaluate_preview("ans", false).unwrap(),
        42.0,
        0.0,
        0.0,
    );
}

#[test]
fn preview_does_not_assign_or_change_ans() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("m:=7").unwrap();
    engine.evaluate_preview("m:=99", false).unwrap();
    check_number(engine.evaluate_preview("m", false).unwrap(), 7.0, 0.0, 0.0);
    check_number(
        engine.evaluate_preview("ans", false).unwrap(),
        7.0,
        0.0,
        0.0,
    );
}

#[test]
fn plot_rejects_extra_closing_parenthesis() {
    assert!(new_engine().eval_for_x("x^2)", 2.0).is_none());
}

#[test]
fn plot_rejects_extra_bracket() {
    assert!(new_engine().eval_for_x("2+3]", 2.0).is_none());
}

#[test]
fn plot_override_does_not_change_saved_variable() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("x:=7").unwrap();
    close(
        engine.eval_for_x("x^2", 3.0).expect("plot point"),
        9.0,
        1e-12,
        0.0,
    );
    check_number(engine.evaluate_preview("x", false).unwrap(), 7.0, 0.0, 0.0);
}

#[test]
fn degrees_forward_and_inverse() {
    let mut engine = new_engine();
    engine.angle_mode = AngleMode::Degrees;
    check_number(
        engine.evaluate_preview("sin(30)", false).unwrap(),
        0.5,
        1e-10,
        1e-10,
    );
    check_number(
        engine.evaluate_preview("asin(0.5)", false).unwrap(),
        30.0,
        1e-8,
        1e-8,
    );
}

#[test]
fn modulo_setting_changes_negative_remainder() {
    let mut engine = new_engine();
    engine.modulo_always_positive = false;
    check_number(
        engine.evaluate_preview("-7 mod 3", false).unwrap(),
        -1.0,
        0.0,
        0.0,
    );
    engine.modulo_always_positive = true;
    check_number(
        engine.evaluate_preview("-7 mod 3", false).unwrap(),
        2.0,
        0.0,
        0.0,
    );
}

#[test]
fn modulo_large_integer_both_notations() {
    let mut engine = new_engine();
    check_number(
        engine.evaluate_preview("9007199254740993 mod 2", false).unwrap(),
        1.0,
        0.0,
        0.0,
    );
    check_number(
        engine.evaluate_preview("mod(9007199254740993,2)", false).unwrap(),
        1.0,
        0.0,
        0.0,
    );
    assert!(engine.evaluate_preview("9007199254740993 mod 0", false).is_err());
    assert!(engine.evaluate_preview("mod(9007199254740993,0)", false).is_err());
}

#[test]
fn nthroot_real_odd_root_and_zero_rejection() {
    let mut engine = new_engine();
    check_number(
        engine.evaluate_preview("nthroot(3,-8)", false).unwrap(),
        -2.0,
        0.0,
        0.0,
    );
    check_number(
        engine.evaluate_preview("nthroot(3,8)", false).unwrap(),
        2.0,
        0.0,
        0.0,
    );
    assert!(engine.evaluate_preview("nthroot(0,8)", false).is_err());
}

#[test]
fn assigned_variable_values_are_used() {
    // Renaming a bound variable must not change whether it is evaluated numerically.
    for (index, name) in constants::USER_VARIABLES.iter().enumerate() {
        let mut engine = new_engine();
        let value = index as f64 + 3.0;
        engine
            .evaluate_and_commit(&format!("{name}:={value}"))
            .unwrap();
        let output = engine
            .evaluate_preview(&format!("{name}+{name}"), false)
            .unwrap();
        assert!(
            output.numeric_value.is_some(),
            "bound variable {name}: got {} instead of {}",
            output.exact_text,
            2.0 * value
        );
        check_number(output, 2.0 * value, 0.0, 0.0);
    }
}

#[test]
fn complex_ans_preserves_value() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("i").unwrap();
    check_number(
        engine.evaluate_preview("ans^2", false).unwrap(),
        -1.0,
        1e-12,
        0.0,
    );
}

#[test]
fn complex_ans_with_real_part() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("3+4*i").unwrap();
    check_number(
        engine.evaluate_preview("(ans-3)^2", false).unwrap(),
        -16.0,
        1e-10,
        0.0,
    );
}

#[test]
fn preview_preserves_complex_ans() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("i").unwrap();
    engine.evaluate_preview("99", false).unwrap();
    check_number(
        engine.evaluate_preview("ans^2", false).unwrap(),
        -1.0,
        1e-12,
        0.0,
    );
}

#[test]
fn exact_ans_preserves_large_integer() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("9007199254740993").unwrap();
    check_number(
        engine
            .evaluate_preview("ans-9007199254740992", false)
            .unwrap(),
        1.0,
        0.0,
        0.0,
    );
}

#[test]
fn matrix_result_updates_ans() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("42").unwrap();
    engine.evaluate_and_commit("det([[4,2],[1,3]])").unwrap();
    check_number(
        engine.evaluate_preview("ans", false).unwrap(),
        10.0,
        0.0,
        0.0,
    );
}

#[test]
fn invalid_assignment_is_atomic() {
    let mut engine = new_engine();
    engine.evaluate_and_commit("m:=7").unwrap();
    engine.evaluate_and_commit("42").unwrap();
    assert!(engine.evaluate_and_commit("m:=1/0").is_err());
    check_number(engine.evaluate_preview("m", false).unwrap(), 7.0, 0.0, 0.0);
    check_number(
        engine.evaluate_preview("ans", false).unwrap(),
        42.0,
        0.0,
        0.0,
    );
}

#[test]
fn plot_rejects_pure_imaginary_points() {
    assert!(new_engine().eval_for_x("sqrt(-1)", 2.0).is_none());
}

#[test]
fn plot_rejects_complex_points_with_real_part() {
    assert!(new_engine().eval_for_x("2+i", 2.0).is_none());
}

#[test]
fn exact_integer_translation_family() {
    // The integer oracle is small and independent of the production rational code.
    for offset in -8..=8 {
        let expression = format!("(9007199254740992+({offset}))-9007199254740992");
        check_number(
            new_engine().evaluate_preview(&expression, false).unwrap(),
            offset as f64,
            0.0,
            0.0,
        );
    }
}

#[test]
fn rational_cancellation_family() {
    // ((a/7)+(c/13))*91 = 13*a + 7*c, with exact integer expected values.
    for a in -5..=5 {
        let c = 2 * a + 1;
        let expression = format!("(({a}/7)+({c}/13))*91");
        check_number(
            new_engine().evaluate_preview(&expression, false).unwrap(),
            (13 * a + 7 * c) as f64,
            0.0,
            0.0,
        );
    }
}

#[test]
fn polynomial_integral_family() {
    for degree in 1..=6 {
        let expression = format!("defint(x^{degree},x,0,1)");
        check_number(
            new_engine().evaluate_preview(&expression, false).unwrap(),
            1.0 / (degree + 1) as f64,
            1e-8,
            1e-8,
        );
    }
}

#[test]
fn integration_report_matches_successful_result() {
    let mut engine = new_engine();
    let output = engine.evaluate_preview("defint(x^2,x,0,1)", false).unwrap();
    let value = output.numeric_value.expect("numeric integral");
    let report = engine
        .get_last_integration_report()
        .expect("integration report");
    assert!(report.abs_err.is_finite() && report.abs_err >= 0.0);
    assert!(
        report.converged,
        "smooth polynomial should converge: {}",
        report.message
    );
    assert!(report.evaluations > 0 && report.subintervals > 0);
    close(report.value, value.re, 1e-14, 1e-14);
    close(report.value, 1.0 / 3.0, 1e-10, 1e-8);
    assert!(
        report.abs_err <= 1e-10_f64.max(1e-8 * report.value.abs()),
        "reported convergence exceeds configured tolerance"
    );
}
