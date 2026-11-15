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
    invalid_integrand_propagates_error,
    sum_harmonic_1000,
    nested_integral,
    large_int_power_cancel,
    matrix_multiply,
    symbolic_sin_2x_integral,
    symbolic_sqrt_integral,
    binomial_fractional,
    sum_non_integer_bound,
    deriv_domain_boundary,
    multivar_poly_expand,
    proton_less_than_neutron,
    integral_wide_exp,
    integral_very_wide_exp,
    integral_gaussian_wide,
    integral_steep_exp,
    integral_abs_exp,
    integral_narrow_gaussian,
    integral_abs_sin_100,
    symbolic_reciprocal_square_integral,
    symbolic_linear_denom_integral,
    symbolic_ln_integral,
    symbolic_exp_neg_x_integral,
    symbolic_half_x_integral,
    poly_expand_deg9,
    poly_expand_half_sq,
    poly_mul_powers,
    round_half_neg,
    floor_huge,
    huge_pow2_cancel,
    factorial_ratio_100_98,
    codata_c0_mu0_eps0,
    deriv_sqrt_tiny,
    deriv_sqrt_small,
    matrix_power_10,
    codata_na_kb_r,
    codata_sigma,
    codata_amu,
    deriv_tiny_sin,
    deriv_tiny_exp,
    deriv_pow3_cancel,
    deriv_sqrt_1e_neg8,
    deriv_ln_1e6,
    matrix_overflow_err,
    matrix_det_sq,
    matrix_illcond_inv_det,
    integral_narrow_peak_far,
    integral_osc_40000pi,
    integral_nested_gaussian,
    bigint_pow_mod,
    oversized_int_2_200,
    symbolic_int_recip_sqrt,
    symbolic_int_tan,
    symbolic_int_sin_sq,
    symbolic_int_x_exp_x,
    poly_const_fold_2y3,
    poly_cancel_y_div_y
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

#[test]
fn exact_display_rejects_float_proximity() {
    let mut engine = new_engine();
    let near10 = engine.evaluate_preview("sqrt(2)+1e-10", false).unwrap();
    assert_ne!(normalized(&near10.exact_text), "√2");
    let near15 = engine.evaluate_preview("sqrt(2)+1e-15", false).unwrap();
    assert_ne!(normalized(&near15.exact_text), "√2");
    for original in [near10, near15] {
        let reused = engine.evaluate_preview(&original.reusable_expression(), false).unwrap();
        assert_eq!(
            reused.numeric_value.unwrap().re.to_bits(),
            original.numeric_value.unwrap().re.to_bits()
        );
    }
    assert_eq!(
        normalized(&engine.evaluate_preview("sqrt(2)", false).unwrap().exact_text),
        "√2"
    );
    assert_eq!(
        normalized(&engine.evaluate_preview("sqrt(8)", false).unwrap().exact_text),
        "2√2"
    );
    assert_eq!(
        normalized(&engine.evaluate_preview("sin(pi/3)", false).unwrap().exact_text),
        "√3/2"
    );
    assert_eq!(
        normalized(&engine.evaluate_preview("sqrt(2305843009213693951)", false).unwrap().exact_text),
        "√2305843009213693951"
    );
    let matrix = engine.evaluate_preview("[[1.000000000001]]", false).unwrap();
    let determinant = engine
        .evaluate_preview(&format!("det({})", matrix.reusable_expression()), false)
        .unwrap();
    assert_eq!(determinant.numeric_value.unwrap().re, 1.000000000001);
}

#[test]
fn symbolic_derivative_with_spaced_scientific_notation() {
    let mut engine = new_engine();
    let output = engine.evaluate_preview("d(1e - 3*x,x)", false).unwrap();
    assert_eq!(output.exact_text, "0.001");
    let numeric = engine.evaluate_preview(&output.exact_text, false).unwrap();
    check_number(numeric, 0.001, 1e-12, 1e-12);
}

#[test]
fn complex_principal_branch_and_asin() {
    let mut engine = new_engine();
    let ln_neg1 = engine.evaluate_preview("ln(-1)", false).unwrap().numeric_value.unwrap();
    close(ln_neg1.re, 0.0, 1e-12, 0.0);
    close(ln_neg1.im, std::f64::consts::PI, 1e-12, 1e-12);

    let sqrt_neg1 = engine.evaluate_preview("(-1)^(1/2)", false).unwrap().numeric_value.unwrap();
    close(sqrt_neg1.re, 0.0, 1e-12, 0.0);
    close(sqrt_neg1.im, 1.0, 1e-12, 0.0);

    let cube_neg8 = engine.evaluate_preview("(-8)^(1/3)", false).unwrap().numeric_value.unwrap();
    close(cube_neg8.re, 1.0, 1e-12, 0.0);
    close(cube_neg8.im, 3.0_f64.sqrt(), 1e-12, 1e-12);

    let asin_i = engine.evaluate_preview("asin(i)", false).unwrap().numeric_value.unwrap();
    close(asin_i.re, 0.0, 1e-12, 0.0);
    close(asin_i.im, 1.0_f64.asinh(), 1e-10, 1e-10);
}

#[test]
fn rounding_and_sign_functions() {
    let mut engine = new_engine();
    check_number(engine.evaluate_preview("floor(-2.3)", false).unwrap(), -3.0, 0.0, 0.0);
    check_number(engine.evaluate_preview("ceil(-2.3)", false).unwrap(), -2.0, 0.0, 0.0);
    check_number(engine.evaluate_preview("round(2.5)", false).unwrap(), 3.0, 0.0, 0.0);
    check_number(engine.evaluate_preview("round(-2.5)", false).unwrap(), -3.0, 0.0, 0.0);
    check_number(engine.evaluate_preview("sign(-5)", false).unwrap(), -1.0, 0.0, 0.0);
    check_number(engine.evaluate_preview("sgn(0)", false).unwrap(), 0.0, 0.0, 0.0);
    check_number(engine.evaluate_preview("sign(4)", false).unwrap(), 1.0, 0.0, 0.0);
}

#[test]
fn float_display_trims_binary_noise() {
    let mut engine = new_engine();
    let out = engine.evaluate_preview("1234567.891", false).unwrap();
    assert_eq!(out.decimal_text, "1234567.891");
}

#[test]
fn degrees_symbolic_derivative_scales_by_pi_over_180() {
    let mut engine = new_engine();
    engine.angle_mode = AngleMode::Degrees;
    let out = engine.evaluate_preview("d(sin(x),x)", false).unwrap();
    assert!(
        normalized(&out.exact_text).contains("pi/180"),
        "expected DEG scaling factor pi/180 in {}",
        out.exact_text
    );
    let val = engine.eval_for_x(&out.exact_text, 0.0).unwrap();
    close(val, std::f64::consts::PI / 180.0, 1e-12, 1e-12);
}

#[test]
fn degrees_symbolic_integral_scales_by_180_over_pi() {
    let mut engine = new_engine();
    engine.angle_mode = AngleMode::Degrees;
    let out = engine.evaluate_preview("integral(sin(x),x)", false).unwrap();
    let norm = normalized(&out.exact_text);
    assert!(
        norm.starts_with('-') && norm.contains("180") && norm.contains("pi") && norm.contains("cos(x)"),
        "expected -180/pi*cos(x) in DEG mode, got {}",
        out.exact_text
    );
    let h = 1e-5;
    let f1 = engine.eval_for_x(&out.exact_text, 30.0 + h).unwrap();
    let f0 = engine.eval_for_x(&out.exact_text, 30.0 - h).unwrap();
    close((f1 - f0) / (2.0 * h), 0.5, 1e-6, 1e-6);
}

#[test]
fn large_negative_odd_power_remains_real() {
    let mut engine = new_engine();
    let out = engine.evaluate_preview("(-2)^1001", false).unwrap();
    let val = out.numeric_value.expect("numeric value expected");
    assert_eq!(val.im, 0.0, "(-2)^1001 must have zero imaginary part");
    assert!(!out.decimal_text.contains('i'), "decimal_text must not contain i: {}", out.decimal_text);
    close(val.re, -2.14301721437253e301, 0.0, 1e-10);
}

#[test]
fn matrix_inverse_avoids_float_noise() {
    let mut engine = new_engine();
    let inv_out = engine.evaluate_preview("inv([[1,2],[3,4]])", false).unwrap();
    assert!(!inv_out.exact_text.contains("e-"), "unexpected scientific noise in inv: {}", inv_out.exact_text);
    let prod = engine.evaluate_preview("[[1,2],[3,4]]*inv([[1,2],[3,4]])", false).unwrap();
    assert_eq!(normalized(&prod.exact_text), "[[1,0],[0,1]]");
}

#[test]
fn watchdog_timeout_returns_zeitlimit() {
    let mut engine = new_engine();
    let err = engine
        .evaluate_with_timeout("sum(sum(sin(j+k),j,1,1000),k,1,1000)", false, 10)
        .expect_err("slow nested sum should time out");
    assert!(
        err.contains("Zeitlimit") || err.contains("Zeitüberschreitung"),
        "expected Zeitlimit error, got {err}"
    );
}

#[test]
fn deep_nesting_returns_error_without_overflow() {
    let mut engine = new_engine();
    let parens = format!("{}1{}", "(".repeat(2000), ")".repeat(2000));
    assert!(
        engine.evaluate_preview(&parens, false).is_err(),
        "2000 nested parens must return error cleanly"
    );
    let sins = format!("{}0{}", "sin(".repeat(300), ")".repeat(300));
    assert!(
        engine.evaluate_preview(&sins, false).is_err(),
        "300 nested sin() calls must return error cleanly"
    );
}

#[test]
fn self_checking_derivative_sweep_matches_symbolic() {
    let mut engine = new_engine();
    let specs: &[(&str, &[f64], f64)] = &[
        ("sin(x)", &[1e-9, 1e-6, 1e-4, 0.5, 2.5, 100.0, 1e4, 1e6], 1e-5),
        ("cos(x)", &[1e-9, 1e-6, 1e-4, 0.5, 2.5, 100.0, 1e4, 1e6], 1e-5),
        ("exp(x)", &[1e-9, 1e-5, 0.5, 2.5, 5.0], 1e-6),
        ("ln(x)", &[1e-4, 0.5, 2.5, 100.0, 1e4, 1e6], 1e-6),
        ("sqrt(x)", &[1e-6, 1e-4, 0.5, 100.0, 1e4, 1e6], 1e-6),
        ("x^3", &[1e-4, 1e-2, 0.5, 2.5, 100.0, 1e4], 1e-6),
        ("tan(x)", &[1e-6, 0.25, 0.75, 1.2], 1e-6),
    ];
    for &(expr, points, rel_tol) in specs {
        let sym_out = engine
            .evaluate_preview(&format!("d({expr},x)"), false)
            .unwrap_or_else(|e| panic!("symbolic diff {expr}: {e}"));
        for &x0 in points {
            let num_out = engine
                .evaluate_preview(&format!("d({expr},x,{x0})"), false)
                .unwrap_or_else(|e| panic!("numeric diff {expr} at {x0}: {e}"));
            let actual = num_out.numeric_value.expect("numeric deriv").re;
            let expected = engine
                .eval_for_x(&sym_out.exact_text, x0)
                .unwrap_or_else(|| panic!("eval symbolic deriv {} at {x0}", sym_out.exact_text));
            close(actual, expected, 1e-11, rel_tol);
        }
    }
}

#[test]
fn self_checking_matrix_identity_and_variables() {
    let mut engine = new_engine();
    let matrices = [
        "[[1,2],[3,4]]",
        "[[1e20,0],[0,1e-20]]",
        "[[1e-20,0],[0,1e20]]",
        "[[1e8,1],[1,2e-8]]",
        "[[1e10,1],[1,0]]",
    ];
    for mat in matrices {
        let prod = engine
            .evaluate_preview(&format!("{mat}*inv({mat})"), false)
            .unwrap_or_else(|e| panic!("A*inv(A) failed for {mat}: {e}"));
        assert_eq!(normalized(&prod.decimal_text), "[[1,0],[0,1]]", "for {mat}");
        let det = engine
            .evaluate_preview(&format!("det({mat}*inv({mat}))"), false)
            .unwrap();
        check_number(det, 1.0, 1e-9, 1e-9);
    }

    // Matrix ANS and variable persistence + overwrite + unset
    engine.evaluate_and_commit("[[1,2],[3,4]]").unwrap();
    let ans_det = engine.evaluate_and_commit("det(ans)").unwrap();
    check_number(ans_det, -2.0, 1e-12, 0.0);

    engine.evaluate_and_commit("m := [[2,1],[5,3]]").unwrap();
    let sym_prod = engine.evaluate_preview("m * inv(m)", false).unwrap();
    assert_eq!(normalized(&sym_prod.decimal_text), "[[1,0],[0,1]]");
    let ans_echo = engine.evaluate_preview("ans", false).unwrap();
    assert_eq!(normalized(&ans_echo.decimal_text), "[[2,1],[5,3]]");

    engine.evaluate_and_commit("unset(m)").unwrap();
    assert!(!engine.matrix_variables.contains_key("m"));
    engine.evaluate_and_commit("m := [[1,0],[0,2]]").unwrap();
    engine.evaluate_and_commit("m := 7").unwrap();
    assert!(!engine.matrix_variables.contains_key("m"));
    check_number(engine.evaluate_preview("m", false).unwrap(), 7.0, 0.0, 0.0);
}

#[test]
fn self_checking_integral_additivity_and_calculus_checks() {
    let mut engine = new_engine();
    let additivity_cases: &[(&str, f64, f64, f64, f64)] = &[
        ("exp(-x^2)", 0.0, 1.3, 5.0, 1e-8),
        ("sin(x)^2", 0.0, 0.37, 1.0, 1e-8),
        ("1/(1+x^2)", 0.0, 1.0, 5.0, 1e-8),
        ("abs(x-1.3)", 0.0, 1.3, 4.0, 1e-7),
    ];
    for &(f, a, c, b, tol) in additivity_cases {
        let i_ab = engine
            .evaluate_preview(&format!("defint({f},x,{a},{b})"), false)
            .unwrap()
            .numeric_value
            .unwrap()
            .re;
        let i_ac = engine
            .evaluate_preview(&format!("defint({f},x,{a},{c})"), false)
            .unwrap()
            .numeric_value
            .unwrap()
            .re;
        let i_cb = engine
            .evaluate_preview(&format!("defint({f},x,{c},{b})"), false)
            .unwrap()
            .numeric_value
            .unwrap()
            .re;
        close(i_ac + i_cb, i_ab, tol, tol);
    }

    // Verify symbolic antiderivatives by numerical differentiation
    let sym_integrands: &[(&str, f64)] = &[
        ("tan(x)", 0.4),
        ("sin(x)^2", 0.7),
        ("cos(x)^2", 0.7),
        ("x*exp(x)", 0.8),
        ("3/sqrt(2*x+1)", 1.5),
    ];
    for &(f, x0) in sym_integrands {
        let anti = engine
            .evaluate_preview(&format!("integral({f},x)"), false)
            .unwrap_or_else(|e| panic!("integral({f},x): {e}"))
            .exact_text;
        let h = 1e-6;
        let fp = engine.eval_for_x(&anti, x0 + h).unwrap();
        let fm = engine.eval_for_x(&anti, x0 - h).unwrap();
        let target = engine.eval_for_x(f, x0).unwrap();
        close((fp - fm) / (2.0 * h), target, 1e-6, 1e-6);
    }

    // Verify (x+1)^33 expansion quantitatively via x variable evaluation
    engine.variables.remove("x");
    let expanded = engine.evaluate_preview("(x+1)^33", false).unwrap().exact_text;
    assert!(!expanded.contains('('), "(x+1)^33 should expand cleanly: {expanded}");
    assert!(expanded.contains("x^33"), "(x+1)^33 should contain x^33: {expanded}");
    close(engine.eval_for_x(&expanded, 1.0).unwrap(), 8589934592.0, 1.0, 1e-10);
}

#[test]
fn self_checking_nesting_depth_64_and_unset() {
    let mut engine = new_engine();
    let ok64 = format!("{}1{}", "(".repeat(60), ")".repeat(60));
    check_number(engine.evaluate_preview(&ok64, false).unwrap(), 1.0, 0.0, 0.0);
    let err65 = format!("{}1{}", "(".repeat(65), ")".repeat(65));
    assert!(
        engine.evaluate_preview(&err65, false).is_err(),
        "65 nested parens must exceed depth 64 limit"
    );

    engine.evaluate_and_commit("k := 12").unwrap();
    check_number(engine.evaluate_preview("k+1", false).unwrap(), 13.0, 0.0, 0.0);
    let unset_out = engine.evaluate_and_commit("unset(k)").unwrap();
    assert!(unset_out.exact_text.contains("k"), "unexpected unset text: {}", unset_out.exact_text);
    let sym = engine.evaluate_preview("k+k", false).unwrap();
    assert_eq!(normalized(&sym.exact_text), "2*k");
}
