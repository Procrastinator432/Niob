package com.jadecalculator.materialyou;

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
            result.setAttribute("classname", "com.jadecalculator.materialyou.CalculatorEngine");
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
