package com.niobcalculator.materialyou;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** JVM tests of the same CalculatorEngine compiled into the APK. No Android SDK or JUnit needed. */
public final class CalculatorRegressionTest {
    private interface Check { void run() throws Exception; }
    private static final Map<String, Check> tests = new LinkedHashMap<>();

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void close(double actual, double expected, double absolute, double relative) {
        double tolerance = absolute + relative * Math.abs(expected);
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                "expected " + expected + ", got " + actual + "; tolerance " + tolerance);
    }

    private static String normalized(String text) {
        return text.replaceAll("\\s+", "").replace('·', '*').replace('−', '-');
    }

    private static double displayedNumber(String text) {
        String value = normalized(text);
        int scientific = value.indexOf("×10");
        if (scientific >= 0) {
            String exponent = value.substring(scientific + 3);
            String superscript = "⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺";
            String digits = "0123456789-+";
            for (int i = 0; i < superscript.length(); i++) {
                exponent = exponent.replace(superscript.charAt(i), digits.charAt(i));
            }
            return Double.parseDouble(value.substring(0, scientific)) * Math.pow(10, Integer.parseInt(exponent));
        }
        return Double.parseDouble(value);
    }

    private static void number(CalculatorEngine.Result result, double expected, double absolute, double relative) {
        close(result.value, expected, absolute, relative);
        close(displayedNumber(result.decimalText), expected, absolute, relative);
    }

    private static void rejects(Check calculation) throws Exception {
        try {
            calculation.run();
        } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException bug) {
            throw new AssertionError("implementation failure is not a calculation error", bug);
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected an explicit calculation error");
    }

    private static void add(String name, Check check) {
        require(!tests.containsKey(name), "duplicate case: " + name);
        tests.put(name, check);
    }

    private static void fixture(String[] row) throws Exception {
        CalculatorEngine engine = new CalculatorEngine();
        String expression = row[1];
        switch (row[3]) {
            case "error":
                rejects(() -> engine.evaluate(expression));
                break;
            case "assignment":
                engine.evaluateAndCommit(expression);
                number(engine.evaluate("m*4"), Double.parseDouble(row[4]),
                        Double.parseDouble(row[5]), Double.parseDouble(row[6]));
                break;
            case "number":
                number(engine.evaluate(expression), Double.parseDouble(row[4]),
                        Double.parseDouble(row[5]), Double.parseDouble(row[6]));
                break;
            case "symbolic_samples":
                String symbolic = engine.evaluate(expression).exactText;
                for (String sample : row[4].split(";")) {
                    String[] values = sample.split(":", -1);
                    require(values.length == 2, "expected x:expected sample");
                    double x = Double.parseDouble(values[0]);
                    close(engine.evaluateForX(symbolic, x), Double.parseDouble(values[1]),
                            Double.parseDouble(row[5]), Double.parseDouble(row[6]));
                }
                break;
            case "exact":
                String actual = engine.evaluate(expression).exactText;
                for (String expected : row[4].split("\\|")) {
                    if (normalized(expected).equals(normalized(actual))) return;
                }
                throw new AssertionError("expected exact " + row[4] + ", got " + actual);
            default:
                throw new IllegalArgumentException("unsupported fixture check: " + row[3]);
        }
    }

    private static void stateTests() {
        add("complex_variable_preserves_value", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("m:=i");
            number(engine.evaluate("m*m"), -1, 1e-12, 0);
        });
        add("division_by_zero_does_not_change_ans", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("42");
            rejects(() -> engine.evaluateAndCommit("1/0"));
            number(engine.evaluate("ans"), 42, 0, 0);
        });
        add("preview_does_not_assign_or_change_ans", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("m:=7");
            engine.evaluate("m:=99");
            number(engine.evaluate("m"), 7, 0, 0);
            number(engine.evaluate("ans"), 7, 0, 0);
        });
        add("plot_rejects_extra_closing_parenthesis", () ->
                rejects(() -> new CalculatorEngine().evaluateForX("x^2)", 2)));
        add("plot_rejects_extra_bracket", () ->
                rejects(() -> new CalculatorEngine().evaluateForX("2+3]", 2)));
        add("plot_override_does_not_change_saved_variable", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            // x=2 is the existing default. A plot point must not overwrite it.
            close(engine.evaluateForX("x^2", 3), 9, 1e-12, 0);
            number(engine.evaluate("x"), 2, 0, 0);
        });
        add("plot_logarithm_preserves_base_and_argument", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            close(engine.evaluateForX("log(2,8)", 1), 3, 1e-12, 0);
            close(engine.evaluateForX("log(2,x)", 16), 4, 1e-12, 0);
        });
        add("plot_evaluation_follows_angle_mode", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            close(engine.evaluateForX("sin(x)", 30), Math.sin(30), 1e-12, 0);
            engine.setRadiansMode(false);
            close(engine.evaluateForX("sin(x)", 30), 0.5, 1e-12, 0);
        });
        add("plot_notation_preserves_power_and_fraction_precedence", () -> {
            CalculatorEngine.AstNode expression = CalculatorEngine.AstParser.tryParseNotation("x^2 / 4 - 2");
            require(expression instanceof CalculatorEngine.AstSub, "subtraction must be outside the fraction");
            CalculatorEngine.AstNode left = ((CalculatorEngine.AstSub) expression).left;
            require(left instanceof CalculatorEngine.AstDiv, "division must group the power and its denominator");
            CalculatorEngine.AstDiv fraction = (CalculatorEngine.AstDiv) left;
            require(fraction.left instanceof CalculatorEngine.AstPow, "the numerator must contain the power");
            require(((CalculatorEngine.AstNum) fraction.right).literal.equals("4"), "the denominator must remain 4");
        });
        add("plot_notation_keeps_literals_and_waits_for_complete_input", () -> {
            CalculatorEngine.AstNode expression = CalculatorEngine.AstParser.tryParseNotation("x^(1e - 3)");
            require(expression instanceof CalculatorEngine.AstPow, "scientific exponent must remain in the power");
            CalculatorEngine.AstNum exponent = (CalculatorEngine.AstNum) ((CalculatorEngine.AstPow) expression).exp;
            require(exponent.literal.equals("1e-3"), "scientific literal must remain exact");
            require(CalculatorEngine.AstParser.tryParseNotation("(x^2") == null,
                    "an unfinished parenthesis must remain editable");
        });
        add("degrees_forward_and_inverse", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.setRadiansMode(false);
            number(engine.evaluate("sin(30)"), 0.5, 1e-8, 1e-8);
            number(engine.evaluate("asin(0.5)"), 30, 1e-8, 1e-8);
        });
        add("preview_does_not_change_ans", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("42");
            engine.evaluate("99");
            number(engine.evaluate("ans"), 42, 0, 0);
        });
    }

    private static void noRealPlotPoint(String expression) {
        double value;
        try {
            value = new CalculatorEngine().evaluateForX(expression, 2);
        } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException bug) {
            throw new AssertionError("implementation failure is not plot rejection", bug);
        } catch (RuntimeException rejected) {
            return;
        }
        require(!Double.isFinite(value), "non-real point " + expression + " was plotted as real value " + value);
    }

    private static void structuralTests() {
        add("integration_report_matches_successful_result", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.Result result = engine.evaluate("int(x^2,x,0,1)");
            CalculatorEngine.IntegrationReport report = engine.getLastIntegrationReport();
            require(report != null, "integration report is missing");
            require(Double.isFinite(report.absErr) && report.absErr >= 0, "invalid error estimate");
            require(report.converged, "smooth polynomial should converge: " + report.message);
            require(report.evaluations > 0 && report.subintervals > 0, "missing work statistics");
            close(report.value, result.value, 1e-14, 1e-14);
            close(report.value, 1.0 / 3.0, 1e-10, 1e-8);
            require(report.absErr <= Math.max(1e-10, 1e-8 * Math.abs(report.value)),
                    "reported convergence exceeds configured tolerance");
        });
        add("assigned_variable_values_are_used", () -> {
            for (int index = 0; index < CalculatorEngine.USER_VARIABLES.length; index++) {
                String name = CalculatorEngine.USER_VARIABLES[index];
                double value = index + 3;
                CalculatorEngine engine = new CalculatorEngine();
                engine.evaluateAndCommit(name + ":=" + value);
                CalculatorEngine.Result result = engine.evaluate(name + "+" + name);
                require(Double.isFinite(result.value) && result.value == 2 * value,
                        "bound variable " + name + ": expected " + (2 * value) + ", got " + result.exactText);
                number(result, 2 * value, 0, 0);
            }
        });
        add("complex_ans_preserves_value", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("i");
            number(engine.evaluate("ans^2"), -1, 1e-12, 0);
        });
        add("complex_ans_with_real_part", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("3+4*i");
            number(engine.evaluate("(ans-3)^2"), -16, 1e-10, 0);
        });
        add("preview_preserves_complex_ans", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("i");
            engine.evaluate("99");
            number(engine.evaluate("ans^2"), -1, 1e-12, 0);
        });
        add("exact_ans_preserves_large_integer", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("9007199254740993");
            number(engine.evaluate("ans-9007199254740992"), 1, 0, 0);
        });
        add("matrix_result_updates_ans", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("42");
            engine.evaluateAndCommit("det([[4,2],[1,3]])");
            number(engine.evaluate("ans"), 10, 0, 0);
        });
        add("matrix_cell_accepts_multi_argument_function", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            number(engine.evaluate("1+det([[log(2,8),1],[1,2]])"), 6, 0, 0);
        });
        add("matrix_subtraction_is_left_associative", () -> {
            CalculatorEngine.Result result = new CalculatorEngine().evaluate("[[7]]−[[3]]−[[1]]");
            require(result.decimalText.equals("[[3]]"), result.decimalText);
        });
        add("matrix_inverse_composes_with_addition", () -> {
            CalculatorEngine.Result result = new CalculatorEngine().evaluate("inv([[2]])+[[0.5]]");
            require(result.decimalText.equals("[[1]]"), result.decimalText);
        });
        add("indexed_root_uses_real_odd_root", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            number(engine.evaluate("nthroot(3,-8)"), -2, 0, 0);
            rejects(() -> engine.evaluate("nthroot(0,8)"));
        });
        add("discrete_bounds_at_long_limits", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            number(engine.evaluate("sum(1,x,9223372036854775807,9223372036854775807)"), 1, 0, 0);
            rejects(() -> engine.evaluate("prod(1,x,-9223372036854775808,9223372036854775807)"));
        });
        add("modulo_keeps_decimal_operands", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            number(engine.evaluate("mod(5.5,2)"), 1.5, 0, 0);
        });
        add("complex_exact_display_keeps_imaginary_part", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.Result result = engine.evaluate("1/2+i");
            number(engine.evaluate("(" + result.exactText + ")-i"), 0.5, 0, 0);
        });
        add("invalid_assignment_is_atomic", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("m:=7");
            engine.evaluateAndCommit("42");
            rejects(() -> engine.evaluateAndCommit("m:=1/0"));
            number(engine.evaluate("m"), 7, 0, 0);
            number(engine.evaluate("ans"), 42, 0, 0);
        });
        add("plot_rejects_pure_imaginary_points", () -> noRealPlotPoint("sqrt(-1)"));
        add("plot_rejects_complex_points_with_real_part", () -> noRealPlotPoint("2+i"));
        add("exact_integer_translation_family", () -> {
            for (int offset = -8; offset <= 8; offset++) {
                String expression = "(9007199254740992+(" + offset + "))-9007199254740992";
                number(new CalculatorEngine().evaluate(expression), offset, 0, 0);
            }
        });
        add("rational_cancellation_family", () -> {
            // ((a/7)+(c/13))*91 = 13*a + 7*c, checked with small integer arithmetic.
            for (int a = -5; a <= 5; a++) {
                int c = 2 * a + 1;
                String expression = "((" + a + "/7)+(" + c + "/13))*91";
                number(new CalculatorEngine().evaluate(expression), 13 * a + 7 * c, 0, 0);
            }
        });
        add("polynomial_integral_family", () -> {
            for (int degree = 1; degree <= 6; degree++) {
                String expression = "int(x^" + degree + ",x,0,1)";
                number(new CalculatorEngine().evaluate(expression), 1.0 / (degree + 1), 1e-8, 1e-8);
            }
        });
        add("modulo_setting_changes_negative_remainder", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.setModuloAlwaysPositive(false);
            number(engine.evaluate("-7 mod 3"), -1, 0, 0);
            engine.setModuloAlwaysPositive(true);
            number(engine.evaluate("-7 mod 3"), 2, 0, 0);
        });
        add("modulo_large_integer_both_notations", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            number(engine.evaluate("9007199254740993 mod 2"), 1, 0, 0);
            number(engine.evaluate("mod(9007199254740993,2)"), 1, 0, 0);
            rejects(() -> engine.evaluate("9007199254740993 mod 0"));
            rejects(() -> engine.evaluate("mod(9007199254740993,0)"));
        });
        add("exact_mode_does_not_falsely_round_approximation", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.Result result = engine.evaluate("sqrt(2)+1e-10");
            require(!result.exactText.contains("√2"), "Approximation must not be formatted as √2: " + result.exactText);
            require(result.exactText.contains("1.414213562") || result.exactText.contains("1,414213562"), "Must show decimal value: " + result.exactText);
            CalculatorEngine.Result pureRoot = engine.evaluate("sqrt(2)");
            require(pureRoot.exactText.equals("√2"), "Pure sqrt(2) should format as √2: " + pureRoot.exactText);
            CalculatorEngine.Result frac = engine.evaluate("1/3");
            require(frac.exactText.equals("1 / 3"), "1/3 should format as 1 / 3: " + frac.exactText);
        });
        add("symbolic_derivative_handles_scientific_notation_with_spaces", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            number(engine.evaluate("d(1e-3*x,x)"), 0.001, 1e-12, 1e-12);
            number(engine.evaluate("d(1e - 3*x,x)"), 0.001, 1e-12, 1e-12);
            number(engine.evaluate("d(1e 3*x,x)"), 1000, 1e-12, 1e-12);
        });
        add("plotter_syntax_validation_allows_domain_gaps", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.validatePlotSyntax("sqrt(x-2)");
            engine.validatePlotSyntax("1/(x-1)");
            rejects(() -> engine.validatePlotSyntax("sqrt(x-"));
            rejects(() -> engine.validatePlotSyntax("unknown_fn(x)"));
            require(Double.isNaN(engine.evaluateForX("sqrt(x-2)", 1.0)), "Should be NaN at x=1");
            require(Math.abs(engine.evaluateForX("sqrt(x-2)", 3.0) - 1.0) < 1e-9, "Should be 1.0 at x=3");
            engine.evaluate("1");
            engine.evaluateForX("defint(t,t,0,x)", 2.0);
            require(engine.getLastIntegrationReport() == null, "Plot sampling must not overwrite lastIntegrationReport");
        });
        add("unary_function_rejects_empty_and_multiple_arguments", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            rejects(() -> engine.evaluate("sin()"));
            rejects(() -> engine.evaluate("sin(0,1/0)"));
            rejects(() -> engine.evaluate("cos(0,1)"));
        });
    }

    private static CalculatorEngine restoreAnswer(CalculatorEngine source) {
        CalculatorEngine restored = new CalculatorEngine();
        restored.setLastAns(restored.deserializeValue(source.serializeValue(source.getLastAns())));
        return restored;
    }

    private static CalculatorEngine restoreVariables(CalculatorEngine source) {
        CalculatorEngine restored = new CalculatorEngine();
        for (Map.Entry<String, CalculatorEngine.Value> entry : source.getUserVariables().entrySet()) {
            restored.setUserVariable(entry.getKey(),
                    restored.deserializeValue(source.serializeValue(entry.getValue())));
        }
        return restored;
    }

    private static void persistenceTests() {
        add("persistence_planck_ans", () -> {
            CalculatorEngine source = new CalculatorEngine();
            source.evaluateAndCommit("h");
            // A restart must preserve tiny nonzero values, without an absolute tolerance floor.
            number(restoreAnswer(source).evaluate("ans"), 6.6261e-34, 0, 1e-12);
        });
        add("persistence_tiny_variable", () -> {
            CalculatorEngine source = new CalculatorEngine();
            source.evaluateAndCommit("m:=h");
            number(restoreVariables(source).evaluate("m/h"), 1, 0, 1e-12);
        });
        add("persistence_near_integer_float", () -> {
            CalculatorEngine source = new CalculatorEngine();
            source.evaluateAndCommit("1+ln(1.000000000005)");
            double expected = 1 + Math.log(1.000000000005);
            require(expected > 1, "test requires a non-integer floating-point value");
            close(restoreAnswer(source).getLastAnswer(), expected, 1e-15, 0);
        });
        add("persistence_exact_ans", () -> {
            CalculatorEngine source = new CalculatorEngine();
            source.evaluateAndCommit("9007199254740993");
            number(restoreAnswer(source).evaluate("ans-9007199254740992"), 1, 0, 0);
            source.evaluateAndCommit("1/3");
            require(normalized(restoreAnswer(source).evaluate("ans*3-1").exactText).equals("0"),
                    "restored rational answer lost exact cancellation");
        });
        add("persistence_complex_ans", () -> {
            CalculatorEngine source = new CalculatorEngine();
            source.evaluateAndCommit("3+4*i");
            number(restoreAnswer(source).evaluate("(ans-3)^2"), -16, 1e-10, 0);
        });
        add("persistence_complex_variable", () -> {
            CalculatorEngine source = new CalculatorEngine();
            source.evaluateAndCommit("m:=3+4*i");
            number(restoreVariables(source).evaluate("(m-3)^2"), -16, 1e-10, 0);
        });
        add("plot_compile_mod_large_int", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.PlotFunction fn = engine.compilePlotFunction("mod(9007199254740993,2)+x");
            require(fn != null, "plot fn is null");
            close(fn.evaluate(2.0), 3.0, 1e-12, 0);
        });
        add("plot_compile_sum_and_defint", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.PlotFunction sumFn = engine.compilePlotFunction("sum(k*x,k,1,3)");
            require(sumFn != null, "sum plot fn is null");
            close(sumFn.evaluate(2.0), 12.0, 1e-12, 0);
            CalculatorEngine.PlotFunction intFn = engine.compilePlotFunction("defint(t,t,0,x)");
            require(intFn != null, "defint plot fn is null");
            close(intFn.evaluate(2.0), 2.0, 1e-8, 1e-8);
        });
        add("plot_compile_ans_and_factorial", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("7");
            CalculatorEngine.PlotFunction ansFn = engine.compilePlotFunction("ans*x");
            require(ansFn != null, "ans plot fn is null");
            close(ansFn.evaluate(3.0), 21.0, 1e-12, 0);
            CalculatorEngine.PlotFunction factFn = engine.compilePlotFunction("x!");
            require(factFn != null, "factorial plot fn is null");
            close(factFn.evaluate(3.0), 6.0, 1e-12, 0);
        });
        add("plot_compile_keeps_matrix_cells_and_context", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.evaluateAndCommit("a := 7");
            CalculatorEngine.PlotFunction function =
                    engine.compilePlotFunction("det(trans([[a,0],[x,2]]))+ans+sin(x)");
            CalculatorEngine.PlotFunction snapshot = function.withContext(engine.snapshotForPlot());
            engine.setRadiansMode(false);
            engine.evaluateAndCommit("a := 42");
            close(snapshot.evaluate(2), 21 + Math.sin(2), 1e-12, 0);
            close(function.evaluate(2), 126 + Math.sin(Math.toRadians(2)), 1e-12, 0);
            close(engine.compilePlotFunction("det([[x,2],[3,4]]+[[1,0],[0,1]])").evaluate(2), 9, 1e-12, 0);
        });
        add("plot_validate_rejects_masked_unknown_func", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.validatePlotSyntax("1/(x-1)");
            boolean threw = false;
            try {
                engine.validatePlotSyntax("1/(x-1)+foo(x)");
            } catch (RuntimeException expected) {
                threw = true;
            }
            require(threw, "validatePlotSyntax must reject 1/(x-1)+foo(x)");
            require(Double.isNaN(engine.compilePlotFunction("1/(x-1)").evaluate(1)), "pole must produce a gap");
        });
        add("exact_display_no_float_guessing_and_reusable_text", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.Result near = engine.evaluate("sqrt(2)+1e-15");
            require(!normalized(near.exactText).equals("√2"), "sqrt(2)+1e-15 must not be √2");
            CalculatorEngine.Result reused = engine.evaluate(near.reusableText);
            require(reused.value == near.value, "the reusable expression must preserve the full double");
            CalculatorEngine.Result near10 = engine.evaluate("sqrt(2)+1e-10");
            require(!normalized(near10.exactText).equals("√2"), "sqrt(2)+1e-10 must not be √2");
            require(normalized(engine.evaluate("sqrt(2)").exactText).equals("√2"), "sqrt(2) must be √2");
            require(normalized(engine.evaluate("sqrt(8)").exactText).equals("2√2"), "sqrt(8) must be 2√2");
            CalculatorEngine.Result surd = engine.evaluate("sqrt(8)");
            close(engine.evaluate(surd.reusableText).value, surd.value, 1e-15, 0);
            require(normalized(engine.evaluate("9223372036854775809/sqrt(2)").exactText)
                    .equals("9223372036854775809√2/2"), "rationalizing a large coefficient must not truncate it");
            require(normalized(engine.evaluate("sqrt(2305843009213693951)").exactText)
                    .equals("√2305843009213693951"), "a large prime can remain under the radical");
            CalculatorEngine.Result matrix = engine.evaluate("[[1.000000000001]]");
            require(engine.evaluate("det(" + matrix.reusableText + ")").value == 1.000000000001,
                    "matrix reuse and determinant must keep the difference from 1");
        });
        add("complex_principal_branch", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.Result lnNeg1 = engine.evaluate("ln(-1)");
            require(!normalized(lnNeg1.decimalText).startsWith("-"),
                    "ln(-1) must have positive imaginary part: " + lnNeg1.decimalText);
            number(engine.evaluate("ln(-1)/i"), Math.PI, 1e-10, 1e-10);
            number(engine.evaluate("(-1)^(1/2)/i"), 1.0, 1e-10, 1e-10);
            number(engine.evaluate("((-8)^(1/3)-1)/i"), Math.sqrt(3.0), 1e-10, 1e-10);
        });
        add("degrees_symbolic_derivative", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            engine.setRadiansMode(false);
            CalculatorEngine.Result res = engine.evaluate("deriv(sin(x),x)");
            require(normalized(res.exactText).contains("pi/180"),
                    "DEG derivative must contain pi/180: " + res.exactText);
            close(engine.evaluateForX(res.exactText, 0), Math.PI / 180.0, 1e-12, 1e-12);
        });
        add("float_display_trims_noise_and_fast_harmonic", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.Result num = engine.evaluate("1234567.891");
            require(num.decimalText.equals("1234567.891"),
                    "expected 1234567.891, got " + num.decimalText);
            long t0 = System.nanoTime();
            CalculatorEngine.Result sum20k = engine.evaluate("sum(1/k,k,1,20000)");
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            close(sum20k.value, 10.4807282173, 1e-8, 1e-8);
            require(elapsedMs < 3000, "20k harmonic sum took too long: " + elapsedMs + " ms");
        });
        add("matrix_3x3_multiply_and_inverse", () -> {
            CalculatorEngine engine = new CalculatorEngine();
            CalculatorEngine.Result prod = engine.evaluate("[[1,2,3],[0,1,4],[5,6,0]]*[[1,0,2],[0,1,0],[1,1,1]]");
            require(normalized(prod.exactText).equals("[[4,5,5],[4,5,4],[5,6,10]]"),
                    "3x3 product mismatch: " + prod.exactText);
            CalculatorEngine.Result exactInvProd = engine.evaluate("[[2,0,0],[0,4,0],[0,0,8]]*inv([[2,0,0],[0,4,0],[0,0,8]])");
            require(normalized(exactInvProd.exactText).equals("[[1,0,0],[0,1,0],[0,0,1]]"),
                    "diagonal 3x3 A*inv(A) must be identity: " + exactInvProd.exactText);
            number(engine.evaluate("det([[1,2,3],[0,1,4],[5,6,0]]*inv([[1,2,3],[0,1,4],[5,6,0]]))"), 1.0, 1e-10, 1e-10);
        });
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <regressions.tsv> <filter-or-empty> <junit.xml>");
        List<String> rows = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        for (String line : rows) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            final String[] row = line.split("\t", -1);
            require(row.length == 7, "expected seven fixture columns: " + line);
            add(row[0], () -> fixture(row));
        }
        require(!tests.isEmpty(), "shared regression fixture is empty");
        stateTests();
        structuralTests();
        persistenceTests();

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element suite = document.createElement("testsuite");
        suite.setAttribute("name", "Android CalculatorEngine");
        document.appendChild(suite);
        int executed = 0;
        int failed = 0;
        long start = System.nanoTime();
        for (Map.Entry<String, Check> entry : tests.entrySet()) {
            if (!entry.getKey().contains(args[1])) continue;
            executed++;
            long caseStart = System.nanoTime();
            Element result = document.createElement("testcase");
            result.setAttribute("name", entry.getKey());
            result.setAttribute("classname", "com.niobcalculator.materialyou.CalculatorEngine");
            suite.appendChild(result);
            try {
                entry.getValue().run();
                System.out.println("PASS " + entry.getKey());
            } catch (AssertionError | Exception error) {
                failed++;
                Element failure = document.createElement("failure");
                failure.setAttribute("message", error.toString());
                failure.setTextContent(error.toString());
                result.appendChild(failure);
                System.out.println("FAIL " + entry.getKey() + ": " + error);
            }
            result.setAttribute("time", Double.toString((System.nanoTime() - caseStart) / 1e9));
        }
        require(executed > 0, "filter matched no tests: " + args[1]);
        suite.setAttribute("tests", Integer.toString(executed));
        suite.setAttribute("failures", Integer.toString(failed));
        suite.setAttribute("errors", "0");
        suite.setAttribute("time", Double.toString((System.nanoTime() - start) / 1e9));
        Path report = Paths.get(args[2]);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(report.toFile()));
        System.out.println("Android: " + (executed - failed) + " passed, " + failed + " failed, " + executed + " total");
        System.exit(failed == 0 ? 0 : 1);
    }
}
