package com.niobcalculator.materialyou;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generated cases with independent reference values (see tools/generate_test_cases.py).
 * The Rust test app/tests/generated.rs reads the same files, so both engines are compared
 * against the same references. One test per file; a failure lists the first wrong cases.
 */
final class GeneratedCases {
    interface Check { void run() throws Exception; }

    private static final long TIMEOUT_MS = 30000;
    private static final int SHOWN_FAILURES = 30;
    /** integral_split: the parts must add up within this multiple of the reported error estimates. */
    private static final double SPLIT_DELTA_FACTOR = 10.0;
    private static final double EPS = Math.ulp(1.0);
    private static final String[] FILES = {
        "derivatives", "integrals", "matrices", "exact_numbers", "antiderivatives", "polynomials",
        "integral_families", "derivative_families", "determinants", "integral_splits",
        "integral_forms", "derivative_forms", "determinant_forms", "split_forms",
        "integral_forms2", "derivative_forms2", "split_forms2", "integral_forms3",
        "integral_forms4"
    };

    private GeneratedCases() {}

    static Map<String, Check> load(final Path dir) {
        Map<String, Check> checks = new LinkedHashMap<>();
        for (final String file : FILES) {
            checks.put("generated_" + file, () -> runTable("generated_" + file, dir.resolve(file + ".tsv")));
        }
        return checks;
    }

    private static final class Row {
        String id, expr, check, expected;
        double absTol, relTol;
        boolean degrees;
    }

    private static final class Fail extends Exception {
        Fail(String message) { super(message); }
    }

    /** REFUSED: an explicit error where one is allowed; NOT_CONVERGED: marked as not converged. Both are counted. */
    private static final int PASS = 0, UNSUPPORTED = 1, UNCHECKED = 2, REFUSED = 3, NOT_CONVERGED = 4;

    private static List<Row> rows(Path path) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            if (c.length != 8) throw new AssertionError("fixture schema: " + line);
            Row row = new Row();
            row.id = c[0];
            row.expr = c[1];
            row.check = c[3];
            row.expected = c[4];
            row.absTol = Double.parseDouble(c[5]);
            row.relTol = Double.parseDouble(c[6]);
            row.degrees = c[7].equals("deg");
            rows.add(row);
        }
        return rows;
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "").replace('·', '*').replace('−', '-');
    }

    private static String shortText(String text) {
        return text.length() > 90 ? text.substring(0, 90) + "…" : text;
    }

    /** Value of a displayed number and half a unit of its last displayed digit, or null. */
    private static double[] parseDisplay(String text) {
        String t = compact(text);
        try {
            int slash = t.indexOf('/');
            if (slash > 0) {
                return new double[] {Double.parseDouble(t.substring(0, slash)) / Double.parseDouble(t.substring(slash + 1)), 0};
            }
            String mantissa = t;
            int exponent = 0;
            int times = t.indexOf("×10");
            if (times >= 0) {
                String e = t.substring(times + 3);
                String sup = "⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺", digits = "0123456789-+";
                for (int i = 0; i < sup.length(); i++) e = e.replace(sup.charAt(i), digits.charAt(i));
                mantissa = t.substring(0, times);
                exponent = Integer.parseInt(e);
            } else {
                int pos = Math.max(t.indexOf('e'), t.indexOf('E'));
                if (pos > 0) {
                    mantissa = t.substring(0, pos);
                    exponent = Integer.parseInt(t.substring(pos + 1).replace("+", ""));
                }
            }
            double m = Double.parseDouble(mantissa);
            int dot = mantissa.indexOf('.');
            int decimals = dot >= 0 ? mantissa.length() - dot - 1 : 0;
            double scale = Math.pow(10, exponent);
            double value = m * scale;
            if (!Double.isFinite(value)) return null;
            return new double[] {value, 0.5 * Math.pow(10, -decimals) * scale};
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private static boolean isComplexText(String text) {
        return text.indexOf('i') >= 0;
    }

    private static void requireNumber(CalculatorEngine.Result r, double expected, double absTol, double relTol) throws Fail {
        if (isComplexText(r.decimalText)) throw new Fail("nicht reell: " + r.decimalText);
        double tol = absTol + relTol * Math.abs(expected);
        if (!Double.isFinite(r.value) || Math.abs(r.value - expected) > tol) {
            throw new Fail("erwartet " + expected + ", erhalten " + r.value);
        }
        double[] shown = parseDisplay(r.decimalText);
        if (shown == null) throw new Fail("Anzeige ist keine Zahl: " + r.decimalText);
        if (Math.abs(shown[0] - expected) > tol + shown[1]) {
            throw new Fail("Wert stimmt, Anzeige " + r.decimalText + " weicht ab (erwartet " + expected + ")");
        }
    }

    /** Replaces the variables in text by parenthesised numbers so that the engine can evaluate it. */
    static String substitute(String text, Map<String, Double> values) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
                String ident = text.substring(start, i);
                Double v = values.get(ident);
                if (v != null) {
                    char prev = out.length() > 0 ? out.charAt(out.length() - 1) : ' ';
                    if (Character.isDigit(prev) || prev == ')' || prev == '.') out.append('*');
                    out.append('(').append(Double.toString(v)).append(')');
                } else {
                    out.append(ident);
                }
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static Double realValue(CalculatorEngine engine, String text) {
        try {
            CalculatorEngine.Result r = engine.evaluateWithTimeout(text, TIMEOUT_MS);
            if (isComplexText(r.decimalText) || !Double.isFinite(r.value) || r.decimalText.startsWith("[")) return null;
            return r.value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double[][] parseFloatMatrix(String text) {
        String inner = text.substring(1, text.length() - 1);
        String[] rowTexts = inner.split("\\],\\[");
        double[][] m = new double[rowTexts.length][];
        for (int i = 0; i < rowTexts.length; i++) {
            String[] cells = rowTexts[i].replace("[", "").replace("]", "").split(",");
            m[i] = new double[cells.length];
            for (int j = 0; j < cells.length; j++) m[i][j] = Double.parseDouble(cells[j]);
        }
        return m;
    }

    private static double[][][] parseMatrix(String text) {
        String t = text.trim();
        if (!t.startsWith("[[") || !t.endsWith("]]")) return null;
        String inner = t.substring(1, t.length() - 1);
        List<double[][]> rows = new ArrayList<>();
        for (String row : inner.split("\\]")) {
            row = row.trim();
            while (row.startsWith(",")) row = row.substring(1).trim();
            if (row.isEmpty()) continue;
            if (!row.startsWith("[")) return null;
            String[] cells = row.substring(1).split(",");
            double[][] parsed = new double[cells.length][];
            for (int j = 0; j < cells.length; j++) {
                parsed[j] = parseDisplay(cells[j]);
                if (parsed[j] == null) return null;
            }
            rows.add(parsed);
        }
        return rows.toArray(new double[0][][]);
    }

    private static int checkRow(Row row) throws Fail {
        CalculatorEngine engine = new CalculatorEngine();
        engine.setRadiansMode(!row.degrees);
        CalculatorEngine.Result result = null;
        String error = null;
        try {
            result = engine.evaluateWithTimeout(row.expr, TIMEOUT_MS);
        } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException bug) {
            throw new Fail("Implementierungsfehler statt Rechenfehler: " + bug);
        } catch (RuntimeException e) {
            error = String.valueOf(e.getMessage());
        }
        switch (row.check) {
            case "number":
            case "number_or_error":
                if (error != null) {
                    if (row.check.equals("number_or_error")) return REFUSED;
                    throw new Fail("Fehler: " + error);
                }
                requireNumber(result, Double.parseDouble(row.expected), row.absTol, row.relTol);
                return PASS;
            case "error":
                if (error != null) return PASS;
                throw new Fail("Fehlermeldung erwartet, erhalten " + result.decimalText);
            case "integral": {
                if (error != null) return REFUSED;
                CalculatorEngine.IntegrationReport report = engine.getLastIntegrationReport();
                if (report != null && !report.converged) return NOT_CONVERGED;
                try {
                    requireNumber(result, Double.parseDouble(row.expected), row.absTol, row.relTol);
                } catch (Fail f) {
                    throw new Fail(f.getMessage() + " (als konvergiert gemeldet)");
                }
                return PASS;
            }
            case "integral_value": {
                // A finite integral: a value or "not converged" is required, an error message fails.
                if (error != null) throw new Fail("Fehlermeldung statt Wert: " + error);
                CalculatorEngine.IntegrationReport report = engine.getLastIntegrationReport();
                if (report != null && !report.converged) return NOT_CONVERGED;
                try {
                    requireNumber(result, Double.parseDouble(row.expected), row.absTol, row.relTol);
                } catch (Fail f) {
                    throw new Fail(f.getMessage() + " (als konvergiert gemeldet)");
                }
                return PASS;
            }
            case "diverges": {
                // A divergent integral: an error or "not converged" is required, a converged value fails.
                if (error != null) return REFUSED;
                CalculatorEngine.IntegrationReport report = engine.getLastIntegrationReport();
                if (report != null && !report.converged) return NOT_CONVERGED;
                throw new Fail("divergentes Integral als Wert " + result.decimalText + " ohne Warnung");
            }
            case "exact_integer":
                if (error != null) throw new Fail("Fehler: " + error);
                if (compact(result.exactText).equals(row.expected)) return PASS;
                throw new Fail("erwartet exakt " + shortText(row.expected) + ", erhalten " + shortText(result.exactText));
            case "exact_fraction": {
                if (error != null) throw new Fail("Fehler: " + error);
                if (compact(result.exactText).equals(row.expected)) return PASS;
                // The expected text is a fraction "p/q" or a plain integer.
                int slash = row.expected.indexOf('/');
                double expected = slash < 0 ? Double.parseDouble(row.expected)
                        : Double.parseDouble(row.expected.substring(0, slash)) / Double.parseDouble(row.expected.substring(slash + 1));
                try {
                    requireNumber(result, expected, 0, row.relTol);
                } catch (Fail f) {
                    throw new Fail(f.getMessage() + "; exakt erwartet " + shortText(row.expected));
                }
                return PASS;
            }
            case "matrix": {
                if (error != null) throw new Fail("Fehler: " + error);
                int split = row.expected.indexOf(';');
                double[][] expected = parseFloatMatrix(row.expected.substring(0, split));
                double[][] bound = parseFloatMatrix(row.expected.substring(split + 1));
                double[][][] actual = parseMatrix(result.decimalText);
                if (actual == null) throw new Fail("keine Matrix: " + result.decimalText);
                if (actual.length != expected.length) throw new Fail("falsche Form: " + result.decimalText);
                for (int i = 0; i < expected.length; i++) {
                    if (actual[i].length != expected[i].length) throw new Fail("falsche Form: " + result.decimalText);
                    for (int j = 0; j < expected[i].length; j++) {
                        double v = actual[i][j][0];
                        double e = expected[i][j];
                        double tol = row.relTol * Math.abs(e) + 1e4 * EPS * bound[i][j] + actual[i][j][1];
                        if (Math.abs(v - e) > tol) {
                            throw new Fail("Eintrag (" + (i + 1) + "," + (j + 1) + ") erwartet " + e + ", erhalten " + v);
                        }
                    }
                }
                return PASS;
            }
            case "antiderivative": {
                if (error != null) {
                    if (error.contains("nicht möglich")) return UNSUPPORTED;
                    throw new Fail("Fehler: " + error);
                }
                String f = result.exactText;
                int checked = 0;
                for (String sample : row.expected.split(";")) {
                    int colon = sample.indexOf(':');
                    double x0 = Double.parseDouble(sample.substring(0, colon));
                    double fx = Double.parseDouble(sample.substring(colon + 1));
                    // Five-point difference of F at several step sizes: a correct F matches at one of
                    // them, also close to a pole where the largest step is too coarse.
                    boolean evaluated = false;
                    boolean matched = false;
                    double last = Double.NaN;
                    for (double step : new double[] {1e-3, 2.5e-4, 6.25e-5, 1e-5}) {
                        double h = step * Math.max(1.0, Math.abs(x0));
                        double[] values = new double[4];
                        double[] offsets = {-2, -1, 1, 2};
                        boolean ok = true;
                        for (int k = 0; k < 4 && ok; k++) {
                            Map<String, Double> sub = new LinkedHashMap<>();
                            sub.put("x", x0 + offsets[k] * h);
                            Double v = realValue(engine, substitute(f, sub));
                            if (v == null) ok = false; else values[k] = v;
                        }
                        if (!ok) continue;
                        evaluated = true;
                        double deriv = (8 * (values[2] - values[1]) - (values[3] - values[0])) / (12 * h);
                        double big = 0;
                        for (double v : values) big = Math.max(big, Math.abs(v));
                        double tol = row.absTol + row.relTol * Math.abs(fx) + 1e3 * EPS * big / h;
                        last = deriv;
                        if (Math.abs(deriv - fx) <= tol) {
                            matched = true;
                            break;
                        }
                    }
                    if (!evaluated) continue;
                    checked++;
                    if (!matched) {
                        throw new Fail("F = " + shortText(f) + " hat bei x = " + x0 + " die Ableitung " + last + ", erwartet " + fx);
                    }
                }
                return checked == 0 ? UNCHECKED : PASS;
            }
            case "polynomial": {
                if (error != null) throw new Fail("Fehler: " + error);
                String text = compact(result.exactText);
                if (text.contains(")^") || text.contains(")*(") || text.contains(")(")) {
                    throw new Fail("nicht ausmultipliziert: " + shortText(text));
                }
                for (String sample : row.expected.split(";")) {
                    int colon = sample.indexOf(':');
                    double value = Double.parseDouble(sample.substring(colon + 1));
                    Map<String, Double> vars = new LinkedHashMap<>();
                    for (String a : sample.substring(0, colon).split(",")) {
                        int eq = a.indexOf('=');
                        vars.put(a.substring(0, eq), Double.parseDouble(a.substring(eq + 1)));
                    }
                    Double actual = realValue(engine, substitute(text, vars));
                    if (actual == null) throw new Fail("Ergebnis " + shortText(text) + " nicht auswertbar");
                    if (Math.abs(actual - value) > row.absTol + row.relTol * Math.abs(value)) {
                        throw new Fail(shortText(text) + " ergibt bei " + sample.substring(0, colon) + " " + actual + ", erwartet " + value);
                    }
                }
                return PASS;
            }
            case "integral_split": {
                // Self-check without a reference: int(f,a,b) = int(f,a,m) + int(f,m,b) within the
                // reported error estimates. Each integral runs on a fresh engine.
                int semicolon = row.expected.indexOf(';');
                String[] exprs = {row.expr, row.expected.substring(0, semicolon), row.expected.substring(semicolon + 1)};
                double[] values = new double[3];
                double[] deltas = new double[3];
                for (int k = 0; k < 3; k++) {
                    CalculatorEngine part = new CalculatorEngine();
                    part.setRadiansMode(!row.degrees);
                    CalculatorEngine.Result r;
                    try {
                        r = part.evaluateWithTimeout(exprs[k], TIMEOUT_MS);
                    } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException bug) {
                        throw new Fail("Implementierungsfehler statt Rechenfehler: " + bug);
                    } catch (RuntimeException e) {
                        return REFUSED;
                    }
                    CalculatorEngine.IntegrationReport report = part.getLastIntegrationReport();
                    if (report != null && !report.converged) return NOT_CONVERGED;
                    if (isComplexText(r.decimalText) || !Double.isFinite(r.value)) {
                        throw new Fail(shortText(exprs[k]) + ": kein endliches Zahlenergebnis: " + r.decimalText);
                    }
                    values[k] = r.value;
                    deltas[k] = report == null ? 0 : Double.isNaN(report.absErr) ? Double.POSITIVE_INFINITY : Math.abs(report.absErr);
                }
                double gap = Math.abs(values[0] - values[1] - values[2]);
                double size = Math.max(Math.abs(values[0]), Math.max(Math.abs(values[1]), Math.abs(values[2])));
                double allowed = SPLIT_DELTA_FACTOR * (deltas[0] + deltas[1] + deltas[2]) + row.absTol + row.relTol * size;
                if (gap > allowed) {
                    throw new Fail(String.format(java.util.Locale.ROOT,
                            "ganz %s (Δ %.1e), Teile %s + %s (Δ %.1e, %.1e): Abweichung %.2e",
                            values[0], deltas[0], values[1], values[2], deltas[1], deltas[2], gap));
                }
                return PASS;
            }
            default:
                throw new IllegalArgumentException("unbekannte Prüfung " + row.check);
        }
    }

    private static void runTable(String name, Path path) throws IOException {
        List<Row> rows = rows(path);
        if (rows.isEmpty()) throw new AssertionError(name + ": keine Fälle");
        List<String> failures = new ArrayList<>();
        int unsupported = 0, unchecked = 0, refused = 0, notConverged = 0;
        for (Row row : rows) {
            try {
                int outcome = checkRow(row);
                if (outcome == UNSUPPORTED) unsupported++;
                if (outcome == UNCHECKED) unchecked++;
                if (outcome == REFUSED) refused++;
                if (outcome == NOT_CONVERGED) notConverged++;
            } catch (Fail f) {
                failures.add("  " + row.id + " " + shortText(row.expr) + ": " + f.getMessage());
            }
        }
        StringBuilder summary = new StringBuilder(name + ": " + rows.size() + " Fälle, " + failures.size() + " falsch");
        if (unsupported > 0) summary.append(", ").append(unsupported).append(" nicht unterstützt");
        if (unchecked > 0) summary.append(", ").append(unchecked).append(" ohne prüfbare Stelle");
        if (refused > 0) summary.append(", ").append(refused).append(" mit Fehlermeldung");
        if (notConverged > 0) summary.append(", ").append(notConverged).append(" als nicht konvergiert gemeldet");
        System.out.println(summary);
        if (!failures.isEmpty()) {
            // NIOB_GENERATED_ALL=1 lists every failing case instead of the first few.
            int limit = System.getenv("NIOB_GENERATED_ALL") != null ? Integer.MAX_VALUE : SHOWN_FAILURES;
            StringBuilder message = new StringBuilder(summary);
            for (int i = 0; i < failures.size() && i < limit; i++) message.append('\n').append(failures.get(i));
            if (failures.size() > limit) {
                message.append("\n  … und ").append(failures.size() - limit).append(" weitere (alle mit NIOB_GENERATED_ALL=1)");
            }
            throw new AssertionError(message.toString());
        }
    }
}
