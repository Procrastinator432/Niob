package com.niobcalculator.materialyou;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The calculation code shared by the Android app and the JVM regression suite.
 * 100% Offline Exact Rational, Complex, AST Symbolic, Matrix & Adaptive Calculus Engine.
 */
public final class CalculatorEngine {
    // Exact mathematical constants in Niob Scientific Calculator
    public static final double SPEED_OF_LIGHT = 299790000.0;
    public static final double PERMEABILITY_VACUUM = 0.0000012566;
    public static final double PERMITTIVITY_VACUUM = 8.8542e-12;
    public static final double GRAVITATION_CONST = 6.6738e-11;
    public static final double PLANCK_CONSTANT = 6.6261e-34;
    public static final double FINE_STRUCTURE_CONST = 0.0072974;
    public static final double BOLTZMANN_CONST = 1.3806e-23;
    public static final double GRAVITATIONAL_ACCELERATION = 9.8067;
    public static final double PLANCK_MASS = 2.1765e-8;
    public static final double PLANCK_TIME = 5.3911e-44;
    public static final double PLANCK_LENGTH = 1.6162e-35;
    public static final double ELECTRON_CHARGE = 1.6022e-19;
    public static final double IMPEDANCE_VACUUM = 376.73;
    public static final double MAGNETIC_FLUX_QUANTUM = 2.0678e-15;
    public static final double JOSEPHSON_CONST = 4.835e14;
    public static final double VON_KLITZING_CONST = 25813.0;
    public static final double CONDUCTANCE_QUANTUM = 0.000077481;
    public static final double STEFAN_BOLTZMANN_CONST = 5.6704e-8;
    public static final double RYDBERG_CONST = 10974000.0;
    public static final double HARTREE_ENERGY = 4.3597e-18;
    public static final double BOHR_MAGNETON = 13996000000.0;
    public static final double ELECTRON_MAGNETIC_MOMENT = -9.2848e-24;
    public static final double AVOGADRO_CONST = 6.0221e23;
    public static final double ATOMIC_MASS_CONST = 1.6605e-27;
    public static final double FARADAY_CONST = 96485.0;
    public static final double MOLAR_GAS_CONSTANT = 8.3145;
    public static final double MASS_ELECTRON = 9.1094e-31;
    public static final double MASS_PROTON = 1.6749e-24;
    public static final double MASS_NEUTRON = 1.6726e-24;
    public static final double PERCENT_VAR = 0.01;

    public static final String[] USER_VARIABLES = {
            "a", "b", "c", "d", "k", "l", "m", "n", "x", "y", "z"
    };

    private final Map<String, Value> variables = new HashMap<>();
    private Value lastAns = Value.fromInt(0);
    private boolean radiansMode = true;
    private boolean moduloAlwaysPositive = true;
    private IntegrationReport lastIntegrationReport = null;

    public CalculatorEngine() {
        initConstantsAndVariables();
    }

    public static final class IntegrationReport {
        public final double value;
        public final double absErr;
        public final boolean converged;
        public final int subintervals;
        public final int evaluations;
        public final String message;

        public IntegrationReport(double value, double absErr, boolean converged, int subintervals, int evaluations, String message) {
            this.value = value;
            this.absErr = absErr;
            this.converged = converged;
            this.subintervals = subintervals;
            this.evaluations = evaluations;
            this.message = message;
        }
    }

    public static final class Result {
        public final double value;
        public final String exactText;
        public final String decimalText;

        public Result(double value, String exactText, String decimalText) {
            this.value = value;
            this.exactText = exactText;
            this.decimalText = decimalText;
        }
    }

    public Result evaluate(String expression) {
        return evaluateInternal(expression, false);
    }

    public Result evaluateAndCommit(String expression) {
        return evaluateInternal(expression, true);
    }

    private Result evaluateInternal(String rawExpr, boolean commit) {
        lastIntegrationReport = null;
        if (rawExpr == null || rawExpr.trim().isEmpty()) {
            throw new RuntimeException("Leerer Ausdruck");
        }
        String trimmed = rawExpr.trim();

        // 1. Variable Assignment `var := expr`
        int assignIdx = trimmed.indexOf(":=");
        if (assignIdx >= 0) {
            String vname = trimmed.substring(0, assignIdx).trim();
            if (!isUserVariable(vname)) {
                throw new RuntimeException("Unbekannte Variable: " + vname);
            }
            String rhs = trimmed.substring(assignIdx + 2).trim();
            Value rhsVal = evalExpression(rhs, null);
            if (!Double.isFinite(rhsVal.c.re) || !Double.isFinite(rhsVal.c.im)) {
                throw new RuntimeException("Ungültiges Rechenergebnis (Division durch Null oder Überlauf)");
            }
            String decimal = formatDecimalVal(rhsVal);
            String exact = formatExactVal(rhsVal, decimal);
            if (commit) {
                variables.put(vname, rhsVal);
                lastAns = rhsVal;
            }
            return new Result(rhsVal.c.re, vname + " := " + exact, vname + " := " + decimal);
        }

        // 2. Matrix Expressions
        if (trimmed.startsWith("det(")
                || trimmed.startsWith("inv(")
                || trimmed.startsWith("trans(")
                || trimmed.startsWith("transpose(")
                || trimmed.startsWith("[[")) {
            return evaluateMatrix(trimmed, commit);
        }

        // 3. Symbolic calculus functions via AST
        AstNode parsedAst = AstParser.tryParse(trimmed);
        if (parsedAst != null) {
            Result symCalc = tryEvaluateSymbolicCalculus(parsedAst);
            if (symCalc != null) {
                return symCalc;
            }
        }

        // 4. Numeric evaluation via Recursive Descent
        Value val;
        try {
            val = evalExpression(trimmed, null);
        } catch (RuntimeException ex) {
            // If unknown variable, try symbolic simplification via AST (e.g. y+y -> 2*y)
            if (ex.getMessage() != null && ex.getMessage().contains("Unknown var:")) {
                if (parsedAst != null) {
                    Result sym = tryEvaluateSymbolicPoly(parsedAst);
                    if (sym != null) {
                        return sym;
                    }
                }
            }
            throw ex;
        }

        if (!Double.isFinite(val.c.re) || !Double.isFinite(val.c.im)) {
            throw new RuntimeException("Ungültiges Rechenergebnis (Division durch Null oder Überlauf)");
        }
        double numericVal = val.c.re;
        String decimal = formatDecimalVal(val);
        String exact = formatExactVal(val, decimal);

        if (commit) {
            lastAns = val;
        }
        return new Result(numericVal, exact, decimal);
    }

    public double evaluateForX(String expression, double x) {
        Map<String, Value> overrides = new HashMap<>();
        overrides.put("x", Value.fromFloat(x));
        Value val = evalExpression(expression, overrides);
        if (!val.c.isReal() || !Double.isFinite(val.c.re)) {
            return Double.NaN;
        }
        return val.c.re;
    }

    public boolean isRadiansMode() { return radiansMode; }
    public void setRadiansMode(boolean radians) { radiansMode = radians; }
    public boolean isModuloAlwaysPositive() { return moduloAlwaysPositive; }
    public void setModuloAlwaysPositive(boolean pos) { moduloAlwaysPositive = pos; }
    public double getLastAnswer() { return lastAns.c.re; }
    public Value getLastAns() { return lastAns; }
    public void setLastAns(Value val) { if (val != null) this.lastAns = val; }
    public IntegrationReport getLastIntegrationReport() { return lastIntegrationReport; }

    public void setVariable(String name, double val) {
        variables.put(name, Value.fromFloat(val));
    }

    public void unsetVariable(String name) {
        if (name != null) {
            variables.remove(name);
        }
    }

    public void setUserVariable(String name, Value val) {
        if (name != null && val != null) {
            variables.put(name, val);
        }
    }

    public Map<String, Value> getUserVariables() {
        Map<String, Value> map = new HashMap<>();
        for (String v : USER_VARIABLES) {
            if (variables.containsKey(v)) {
                map.put(v, variables.get(v));
            }
        }
        return map;
    }

    public String serializeValue(Value val) {
        if (val == null) return "Z";
        if (val.rat != null) {
            return "R:" + val.rat.num.toString() + "/" + val.rat.den.toString();
        }
        if (val.c != null) {
            if (!val.c.isReal()) {
                return "C:" + Double.toString(val.c.re) + ":" + Double.toString(val.c.im);
            } else {
                return "F:" + Double.toString(val.c.re);
            }
        }
        return "Z";
    }

    public Value deserializeValue(String s) {
        if (s == null || s.isEmpty() || s.equals("Z")) {
            return Value.fromInt(0);
        }
        try {
            if (s.startsWith("R:")) {
                String[] p = s.substring(2).split("/");
                BigInteger num = new BigInteger(p[0]);
                BigInteger den = new BigInteger(p[1]);
                ExactRat r = new ExactRat(num, den);
                return new Value(Complex.real(r.toDouble()), r);
            } else if (s.startsWith("C:")) {
                String[] p = s.substring(2).split(":");
                double re = Double.parseDouble(p[0]);
                double im = Double.parseDouble(p[1]);
                return new Value(new Complex(re, im), null);
            } else if (s.startsWith("F:")) {
                double d = Double.parseDouble(s.substring(2));
                return Value.fromFloat(d);
            }
        } catch (Exception ignored) {}
        return Value.fromInt(0);
    }

    public String formatAns(boolean exact) {
        if (exact && lastAns.rat != null) {
            if (lastAns.rat.den.equals(BigInteger.ONE)) {
                return lastAns.rat.num.toString();
            }
            return lastAns.rat.num.toString() + "/" + lastAns.rat.den.toString();
        }
        return formatDecimalVal(lastAns);
    }

    public Double getVariable(String name) {
        Value v = variables.get(name);
        return v != null ? v.c.re : null;
    }

    private boolean isUserVariable(String name) {
        for (String v : USER_VARIABLES) {
            if (v.equals(name)) return true;
        }
        return false;
    }

    private void initConstantsAndVariables() {
        variables.put("a", Value.fromInt(5));
        variables.put("b", Value.fromInt(3));
        variables.put("x", Value.fromInt(2));

        variables.put("c0", Value.fromFloat(SPEED_OF_LIGHT));
        variables.put("mu0", Value.fromFloat(PERMEABILITY_VACUUM));
        variables.put("eps0", Value.fromFloat(PERMITTIVITY_VACUUM));
        variables.put("G", Value.fromFloat(GRAVITATION_CONST));
        variables.put("G_const", Value.fromFloat(GRAVITATION_CONST));
        variables.put("h", Value.fromFloat(PLANCK_CONSTANT));
        variables.put("h_planck", Value.fromFloat(PLANCK_CONSTANT));
        variables.put("alpha_fs", Value.fromFloat(FINE_STRUCTURE_CONST));
        variables.put("k_B", Value.fromFloat(BOLTZMANN_CONST));
        variables.put("g", Value.fromFloat(GRAVITATIONAL_ACCELERATION));
        variables.put("g_earth", Value.fromFloat(GRAVITATIONAL_ACCELERATION));
        variables.put("m_P", Value.fromFloat(PLANCK_MASS));
        variables.put("t_P", Value.fromFloat(PLANCK_TIME));
        variables.put("l_P", Value.fromFloat(PLANCK_LENGTH));

        variables.put("e_charge", Value.fromFloat(ELECTRON_CHARGE));
        variables.put("Z0", Value.fromFloat(IMPEDANCE_VACUUM));
        variables.put("Phi0", Value.fromFloat(MAGNETIC_FLUX_QUANTUM));
        variables.put("K_J", Value.fromFloat(JOSEPHSON_CONST));
        variables.put("R_K", Value.fromFloat(VON_KLITZING_CONST));
        variables.put("G0", Value.fromFloat(CONDUCTANCE_QUANTUM));
        variables.put("sigma", Value.fromFloat(STEFAN_BOLTZMANN_CONST));
        variables.put("R_inf", Value.fromFloat(RYDBERG_CONST));
        variables.put("E_h", Value.fromFloat(HARTREE_ENERGY));
        variables.put("mu_B", Value.fromFloat(BOHR_MAGNETON));
        variables.put("mu_e", Value.fromFloat(ELECTRON_MAGNETIC_MOMENT));

        variables.put("N_A", Value.fromFloat(AVOGADRO_CONST));
        variables.put("m_u", Value.fromFloat(ATOMIC_MASS_CONST));
        variables.put("F", Value.fromFloat(FARADAY_CONST));
        variables.put("R", Value.fromFloat(MOLAR_GAS_CONSTANT));
        variables.put("m_e", Value.fromFloat(MASS_ELECTRON));
        variables.put("m_p", Value.fromFloat(MASS_PROTON));
        variables.put("m_n", Value.fromFloat(MASS_NEUTRON));
    }

    // --- Exact Rational Arithmetic ---
    static final class ExactRat {
        final BigInteger num;
        final BigInteger den;

        ExactRat(BigInteger num, BigInteger den) {
            if (den.signum() == 0) throw new ArithmeticException("Division by zero");
            if (den.signum() < 0) {
                num = num.negate();
                den = den.negate();
            }
            BigInteger g = num.gcd(den);
            if (!g.equals(BigInteger.ZERO)) {
                num = num.divide(g);
                den = den.divide(g);
            }
            this.num = num;
            this.den = den;
        }

        static ExactRat of(BigInteger n) {
            return new ExactRat(n, BigInteger.ONE);
        }

        static ExactRat of(long n) {
            return new ExactRat(BigInteger.valueOf(n), BigInteger.ONE);
        }

        ExactRat add(ExactRat o) {
            return new ExactRat(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
        }

        ExactRat sub(ExactRat o) {
            return new ExactRat(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
        }

        ExactRat mul(ExactRat o) {
            return new ExactRat(num.multiply(o.num), den.multiply(o.den));
        }

        ExactRat div(ExactRat o) {
            if (o.num.signum() == 0) throw new ArithmeticException("Division by zero");
            return new ExactRat(num.multiply(o.den), den.multiply(o.num));
        }

        double toDouble() {
            return num.doubleValue() / den.doubleValue();
        }
    }

    // --- Complex Numbers ---
    static final class Complex {
        final double re;
        final double im;

        Complex(double re, double im) {
            this.re = re;
            this.im = im;
        }

        static Complex real(double r) { return new Complex(r, 0.0); }
        static Complex i() { return new Complex(0.0, 1.0); }
        boolean isReal() { return Math.abs(im) < 1e-12; }
        double abs() { return Math.hypot(re, im); }
        double arg() { return Math.atan2(im, re); }

        Complex negate() { return new Complex(-re, -im); }
        Complex add(Complex o) { return new Complex(re + o.re, im + o.im); }
        Complex sub(Complex o) { return new Complex(re - o.re, im - o.im); }
        Complex mul(Complex o) { return new Complex(re * o.re - im * o.im, re * o.im + im * o.re); }
        Complex div(Complex o) {
            double d = o.re * o.re + o.im * o.im;
            if (d == 0.0) throw new ArithmeticException("Division by zero");
            return new Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
        }
        Complex sqrt() {
            if (isReal()) {
                if (re >= 0.0) return real(Math.sqrt(re));
                return new Complex(0.0, Math.sqrt(-re));
            }
            double r = Math.sqrt(abs());
            double theta = arg() * 0.5;
            return new Complex(r * Math.cos(theta), r * Math.sin(theta));
        }
        Complex exp() {
            double r = Math.exp(re);
            return new Complex(r * Math.cos(im), r * Math.sin(im));
        }
        Complex ln() {
            return new Complex(Math.log(abs()), arg());
        }
        Complex pow(Complex exp) {
            if (isReal() && exp.isReal() && re >= 0.0) return real(Math.pow(re, exp.re));
            if (abs() == 0.0) return real(0.0);
            return ln().mul(exp).exp();
        }
        Complex sin() {
            return new Complex(Math.sin(re) * Math.cosh(im), Math.cos(re) * Math.sinh(im));
        }
        Complex cos() {
            return new Complex(Math.cos(re) * Math.cosh(im), -Math.sin(re) * Math.sinh(im));
        }
        Complex tan() {
            return sin().div(cos());
        }
        Complex sinh() {
            return new Complex(Math.sinh(re) * Math.cos(im), Math.cosh(re) * Math.sin(im));
        }
        Complex cosh() {
            return new Complex(Math.cosh(re) * Math.cos(im), Math.sinh(re) * Math.sin(im));
        }
        Complex tanh() {
            return sinh().div(cosh());
        }
    }

    // --- Combined Value ---
    static final class Value {
        final Complex c;
        final ExactRat rat;

        Value(Complex c, ExactRat rat) {
            this.c = c;
            this.rat = rat;
        }

        static Value fromInt(BigInteger n) {
            return new Value(Complex.real(n.doubleValue()), ExactRat.of(n));
        }

        static Value fromInt(long n) {
            return new Value(Complex.real(n), ExactRat.of(n));
        }

        static Value fromRat(long num, long den) {
            ExactRat r = new ExactRat(BigInteger.valueOf(num), BigInteger.valueOf(den));
            return new Value(Complex.real(r.toDouble()), r);
        }

        static Value fromFloat(double d) {
            return new Value(Complex.real(d), null);
        }

        static Value fromComplex(Complex c) {
            if (c.isReal()) {
                return fromFloat(c.re);
            }
            return new Value(c, null);
        }

        Value add(Value o) {
            ExactRat r = (rat != null && o.rat != null) ? rat.add(o.rat) : null;
            Complex res = c.add(o.c);
            if (r != null && res.isReal()) res = Complex.real(r.toDouble());
            return new Value(res, r);
        }

        Value sub(Value o) {
            ExactRat r = (rat != null && o.rat != null) ? rat.sub(o.rat) : null;
            Complex res = c.sub(o.c);
            if (r != null && res.isReal()) res = Complex.real(r.toDouble());
            return new Value(res, r);
        }

        Value mul(Value o) {
            ExactRat r = (rat != null && o.rat != null) ? rat.mul(o.rat) : null;
            Complex res = c.mul(o.c);
            if (r != null && res.isReal()) res = Complex.real(r.toDouble());
            return new Value(res, r);
        }

        Value div(Value o) {
            if (o.c.abs() == 0.0 || (o.c.re == 0.0 && o.c.im == 0.0)) {
                throw new ArithmeticException("Division by zero");
            }
            ExactRat r = (rat != null && o.rat != null) ? rat.div(o.rat) : null;
            Complex res = c.div(o.c);
            if (!Double.isFinite(res.re) || !Double.isFinite(res.im)) {
                throw new ArithmeticException("Division by zero");
            }
            if (r != null && res.isReal()) res = Complex.real(r.toDouble());
            return new Value(res, r);
        }

        Value pow(Value exp) {
            if (rat != null && exp.rat != null && exp.rat.den.equals(BigInteger.ONE)) {
                try {
                    int p = exp.rat.num.intValueExact();
                    if (p >= -20 && p <= 20) {
                        if (p >= 0) {
                            ExactRat r = new ExactRat(rat.num.pow(p), rat.den.pow(p));
                            return new Value(Complex.real(r.toDouble()), r);
                        } else {
                            int ap = -p;
                            ExactRat r = new ExactRat(rat.den.pow(ap), rat.num.pow(ap));
                            return new Value(Complex.real(r.toDouble()), r);
                        }
                    }
                } catch (ArithmeticException ignored) {}
            }
            return new Value(c.pow(exp.c), null);
        }

        Value sqrt() {
            if (rat != null && rat.den.equals(BigInteger.ONE) && rat.num.signum() >= 0) {
                try {
                    long n = rat.num.longValueExact();
                    long s = Math.round(Math.sqrt(n));
                    if (s * s == n) {
                        return Value.fromInt(s);
                    }
                } catch (ArithmeticException ignored) {}
            }
            return new Value(c.sqrt(), null);
        }
    }

    // --- Expression Evaluator ---
    private Value evalExpression(String expr, Map<String, Value> overrides) {
        String s = expr.replace("×", "*").replace("·", "*").replace("÷", "/")
                .replace("π", "pi").replace("−", "-")
                .replace("²", "^2").replace("³", "^3").replace("⁻¹", "^(-1)");
        return new ExprEvaluator(s, overrides).parse();
    }

    private class ExprEvaluator {
        final String s;
        final Map<String, Value> overrides;
        int pos = -1, ch;

        ExprEvaluator(String s, Map<String, Value> overrides) {
            this.s = s;
            this.overrides = overrides;
        }

        void nextChar() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        Value parse() {
            nextChar();
            Value x = parseExpression();
            while (ch == ' ') nextChar();
            if (pos < s.length()) {
                throw new RuntimeException("Unexpected: " + (char) ch);
            }
            return x;
        }

        Value parseExpression() {
            Value x = parseTerm();
            for (;;) {
                if (eat('+')) x = x.add(parseTerm());
                else if (eat('-')) x = x.sub(parseTerm());
                else return x;
            }
        }

        Value parseTerm() {
            Value x = parseUnary();
            for (;;) {
                while (ch == ' ') nextChar();
                // Check "mod"
                if (pos + 2 < s.length() && s.startsWith("mod", pos)) {
                    int next = pos + 3 < s.length() ? s.charAt(pos + 3) : -1;
                    if (next == ' ' || next == '(' || Character.isDigit(next) || next == '-' || next == '+') {
                        pos += 2;
                        nextChar();
                        Value right = parseUnary();
                        double a = x.c.re;
                        double b = right.c.re;
                        double r = a % b;
                        if (moduloAlwaysPositive && r < 0.0) r += Math.abs(b);
                        x = Value.fromFloat(r);
                        continue;
                    }
                }

                if (eat('*')) {
                    x = x.mul(parseUnary());
                } else if (eat('/')) {
                    Value right = parseUnary();
                    x = x.div(right);
                } else if (ch == '(' || ch == '√' || Character.isLetter(ch) || ch == '_') {
                    // Implicit multiplication
                    Value right = parsePower();
                    x = x.mul(right);
                } else {
                    return x;
                }
            }
        }

        Value parseUnary() {
            if (eat('+')) return parseUnary();
            if (eat('-')) {
                Value v = parseUnary();
                return new Value(new Complex(-v.c.re, -v.c.im),
                        v.rat != null ? new ExactRat(v.rat.num.negate(), v.rat.den) : null);
            }
            if (eat('√')) {
                Value v = parseUnary();
                return v.sqrt();
            }
            return parsePower();
        }

        Value parsePower() {
            Value base = parsePostfix();
            if (eat('^')) {
                Value exp = parseUnary();
                base = base.pow(exp);
            }
            return base;
        }

        Value parsePostfix() {
            Value val = parsePrimary();
            for (;;) {
                if (eat('!')) {
                    if (!val.c.isReal() || val.c.re < 0.0 || Math.abs(val.c.re - Math.round(val.c.re)) > 1e-11) {
                        throw new RuntimeException("Fakultät ist nur für nicht-negative ganze Zahlen definiert");
                    }
                    if (val.c.re > 170.0) {
                        throw new RuntimeException("Fakultät übersteigt Rechenbereich");
                    }
                    long k = Math.round(val.c.re);
                    BigInteger prod = BigInteger.ONE;
                    for (long i = 2; i <= k; i++) prod = prod.multiply(BigInteger.valueOf(i));
                    val = Value.fromInt(prod);
                } else if (eat('%')) {
                    val = val.mul(Value.fromRat(1, 100));
                } else {
                    break;
                }
            }
            return val;
        }

        Value parsePrimary() {
            while (ch == ' ') nextChar();
            int startPos = this.pos;

            if (eat('%')) {
                return Value.fromRat(1, 100);
            }

            if (eat('(')) {
                Value x = parseExpression();
                eat(')');
                return x;
            }

            // Hex number 0x...
            if (ch == '0' && pos + 1 < s.length() && (s.charAt(pos + 1) == 'x' || s.charAt(pos + 1) == 'X')) {
                nextChar();
                nextChar();
                int hexStart = pos;
                while (Character.digit(ch, 16) != -1) nextChar();
                String hexStr = s.substring(hexStart, pos);
                BigInteger bi = new BigInteger(hexStr, 16);
                return Value.fromInt(bi);
            }

            // Number
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                boolean hasDot = false;
                boolean hasExp = false;
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    if (ch == '.') hasDot = true;
                    nextChar();
                }
                if (ch == 'e' || ch == 'E') {
                    hasExp = true;
                    nextChar();
                    if (ch == '+' || ch == '-') nextChar();
                    while (ch >= '0' && ch <= '9') nextChar();
                }
                String numStr = s.substring(startPos, pos);
                if (!hasDot && !hasExp) {
                    try {
                        BigInteger bi = new BigInteger(numStr);
                        return Value.fromInt(bi);
                    } catch (Exception ignored) {}
                }
                double d = Double.parseDouble(numStr);
                return Value.fromFloat(d);
            }

            // Identifier or Function call
            if (Character.isLetter(ch) || ch == '_') {
                while (Character.isLetterOrDigit(ch) || ch == '_') nextChar();
                String ident = s.substring(startPos, pos);
                if (eat('(')) {
                    List<String> rawArgs = new ArrayList<>();
                    int depth = 0;
                    StringBuilder sb = new StringBuilder();
                    while (ch != -1) {
                        if (ch == '(') {
                            depth++;
                            sb.append((char) ch);
                        } else if (ch == ')') {
                            if (depth == 0) {
                                nextChar();
                                break;
                            }
                            depth--;
                            sb.append((char) ch);
                        } else if (ch == ',' && depth == 0) {
                            rawArgs.add(sb.toString().trim());
                            sb.setLength(0);
                        } else {
                            sb.append((char) ch);
                        }
                        nextChar();
                    }
                    if (sb.length() > 0) rawArgs.add(sb.toString().trim());
                    return applyFunction(ident, rawArgs, overrides);
                } else {
                    return resolveVar(ident, overrides);
                }
            }

            throw new RuntimeException("Unexpected: " + (ch == -1 ? "EOF" : (char) ch));
        }
    }

    private Value resolveVar(String name, Map<String, Value> ov) {
        if (ov != null && ov.containsKey(name)) return ov.get(name);
        if (name.equals("i")) return Value.fromComplex(Complex.i());
        if (name.equals("pi")) return Value.fromFloat(Math.PI);
        if (name.equals("e")) return Value.fromFloat(Math.E);
        if (name.equals("ans") || name.equals("ANS")) return lastAns;
        if (variables.containsKey(name)) return variables.get(name);
        throw new RuntimeException("Unknown var: " + name);
    }

    // --- Adaptive Gauss-Legendre Quadrature with Error Control ---
    private static final double[] GAUSS5_NODES = {
        0.0,
        0.5384693101056831,
        -0.5384693101056831,
        0.9061798459386640,
        -0.9061798459386640
    };
    private static final double[] GAUSS5_WEIGHTS = {
        0.5688888888888889,
        0.4786286704993665,
        0.4786286704993665,
        0.2369268850561891,
        0.2369268850561891
    };

    private static final double[] GAUSS3_NODES = {
        0.0,
        0.7745966692414834,
        -0.7745966692414834
    };
    private static final double[] GAUSS3_WEIGHTS = {
        0.8888888888888888,
        0.5555555555555556,
        0.5555555555555556
    };

    private interface IntegrandFunc {
        double eval(double x);
    }

    private static final class Subinterval {
        final double a, b, val5, err;
        Subinterval(double a, double b, double val5, double err) {
            this.a = a; this.b = b; this.val5 = val5; this.err = err;
        }
    }

    private static Subinterval evalSubinterval(IntegrandFunc f, double ia, double ib) {
        double h = ib - ia;
        double halfH = h * 0.5;
        double mid = ia + halfH;

        double sum5 = 0.0;
        for (int j = 0; j < 5; j++) {
            double x = mid + halfH * GAUSS5_NODES[j];
            double y = f.eval(x);
            if (!Double.isFinite(y)) {
                throw new RuntimeException("Integrand ist im Integrationsbereich nicht endlich");
            }
            sum5 += GAUSS5_WEIGHTS[j] * y;
        }
        double q5 = sum5 * halfH;

        double sum3 = 0.0;
        for (int j = 0; j < 3; j++) {
            double x = mid + halfH * GAUSS3_NODES[j];
            double y = f.eval(x);
            if (!Double.isFinite(y)) {
                throw new RuntimeException("Integrand ist im Integrationsbereich nicht endlich");
            }
            sum3 += GAUSS3_WEIGHTS[j] * y;
        }
        double q3 = sum3 * halfH;

        double err = Math.abs(q5 - q3);
        return new Subinterval(ia, ib, q5, err);
    }

    private static IntegrationReport adaptiveGaussIntegrate(IntegrandFunc f, double a, double b, int initialSubintervals, double epsAbs, double epsRel) {
        List<Subinterval> intervals = new ArrayList<>(initialSubintervals * 2);
        double h = (b - a) / (double) initialSubintervals;
        double totalVal = 0.0;
        double totalErr = 0.0;

        for (int i = 0; i < initialSubintervals; i++) {
            double ia = a + i * h;
            double ib = (i == initialSubintervals - 1) ? b : (ia + h);
            Subinterval sub = evalSubinterval(f, ia, ib);
            totalVal += sub.val5;
            totalErr += sub.err;
            intervals.add(sub);
        }

        int evals = initialSubintervals * 7;
        int maxEvals = 100000;
        double targetTol = Math.max(epsAbs, epsRel * Math.abs(totalVal));

        while (totalErr > targetTol && evals < maxEvals) {
            int worstIdx = 0;
            double worstErr = intervals.get(0).err;
            for (int i = 1; i < intervals.size(); i++) {
                if (intervals.get(i).err > worstErr) {
                    worstErr = intervals.get(i).err;
                    worstIdx = i;
                }
            }

            if (worstErr <= 1e-15) {
                break;
            }

            Subinterval worst = intervals.remove(worstIdx);
            totalVal -= worst.val5;
            totalErr -= worst.err;

            double mid = (worst.a + worst.b) * 0.5;
            Subinterval left = evalSubinterval(f, worst.a, mid);
            Subinterval right = evalSubinterval(f, mid, worst.b);

            totalVal += left.val5 + right.val5;
            totalErr += left.err + right.err;

            intervals.add(left);
            intervals.add(right);
            evals += 14;

            if (totalErr <= targetTol) {
                break;
            }
        }

        boolean converged = totalErr <= targetTol;
        String message = converged
                ? String.format(Locale.US, "Konvergiert: Fehlerabschätzung %.2e <= Toleranz %.2e (%d Auswertungen)", totalErr, targetTol, evals)
                : String.format(Locale.US, "Konvergenz nicht erreicht: Fehlerabschätzung %.2e > Toleranz %.2e (Budget erschöpft: %d Auswertungen)", totalErr, targetTol, evals);

        return new IntegrationReport(totalVal, totalErr, converged, intervals.size(), evals, message);
    }

    private Value applyFunction(String fn, List<String> args, Map<String, Value> ov) {
        if (fn.equals("sum") && args.size() == 4) {
            String v = args.get(1).trim();
            long a = Math.round(evalExpression(args.get(2), ov).c.re);
            long b = Math.round(evalExpression(args.get(3), ov).c.re);
            if (b - a > 100000) throw new RuntimeException("Summationsbereich zu groß");
            Value sum = Value.fromInt(0);
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            for (long k = a; k <= b; k++) {
                local.put(v, Value.fromInt(k));
                sum = sum.add(evalExpression(args.get(0), local));
            }
            return sum;
        }

        if ((fn.equals("prod") || fn.equals("product")) && args.size() == 4) {
            String v = args.get(1).trim();
            long a = Math.round(evalExpression(args.get(2), ov).c.re);
            long b = Math.round(evalExpression(args.get(3), ov).c.re);
            if (b - a > 100000) throw new RuntimeException("Produktbereich zu groß");
            Value prod = Value.fromInt(1);
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            for (long k = a; k <= b; k++) {
                local.put(v, Value.fromInt(k));
                prod = prod.mul(evalExpression(args.get(0), local));
            }
            return prod;
        }

        if ((fn.equals("int") || fn.equals("defint")) && args.size() == 4) {
            String v = args.get(1).trim();
            Value aVal = evalExpression(args.get(2), ov);
            Value bVal = evalExpression(args.get(3), ov);
            if (!aVal.c.isReal() || !bVal.c.isReal() || !Double.isFinite(aVal.c.re) || !Double.isFinite(bVal.c.re)) {
                throw new RuntimeException("Integrationsgrenzen müssen endliche reelle Zahlen sein");
            }
            double a = aVal.c.re;
            double b = bVal.c.re;
            String exprStr = args.get(0);
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            IntegrationReport report = adaptiveGaussIntegrate(x -> {
                local.put(v, Value.fromFloat(x));
                Value val = evalExpression(exprStr, local);
                if (!val.c.isReal() || !Double.isFinite(val.c.re)) {
                    throw new RuntimeException("Integrand liefert nicht-reellen oder unendlichen Wert");
                }
                return val.c.re;
            }, a, b, 2500, 1e-10, 1e-8);
            lastIntegrationReport = report;
            return Value.fromFloat(report.value);
        }

        if ((fn.equals("deriv") || fn.equals("diff") || fn.equals("d")) && args.size() >= 2) {
            if (args.size() == 2) {
                throw new RuntimeException("Symbolische Ableitung erforderlich");
            }
            String v = args.get(1).trim();
            double x0 = evalExpression(args.get(2), ov).c.re;
            double h = (Math.abs(x0) > 1e10) ? (1e-3 * Math.abs(x0)) : ((Math.abs(x0) > 1e4) ? 1e-3 : 1e-4);

            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            local.put(v, Value.fromFloat(x0 + 2.0 * h));
            double fp2 = evalExpression(args.get(0), local).c.re;
            local.put(v, Value.fromFloat(x0 + h));
            double fp1 = evalExpression(args.get(0), local).c.re;
            local.put(v, Value.fromFloat(x0 - h));
            double fm1 = evalExpression(args.get(0), local).c.re;
            local.put(v, Value.fromFloat(x0 - 2.0 * h));
            double fm2 = evalExpression(args.get(0), local).c.re;

            double deriv = (-fp2 + 8.0 * fp1 - 8.0 * fm1 + fm2) / (12.0 * h);
            return Value.fromFloat(deriv);
        }

        if (fn.equals("sqrt") && args.size() == 1) {
            return evalExpression(args.get(0), ov).sqrt();
        }

        Value v0 = evalExpression(args.get(0), ov);
        Complex c = v0.c;
        if (!radiansMode && c.isReal()) {
            c = Complex.real(Math.toRadians(c.re));
        }

        switch (fn) {
            case "sin": return Value.fromComplex(c.sin());
            case "cos": return Value.fromComplex(c.cos());
            case "tan": return Value.fromComplex(c.tan());
            case "asin": {
                if (v0.c.isReal() && Math.abs(v0.c.re) <= 1.0) {
                    double r = Math.asin(v0.c.re);
                    return Value.fromFloat(radiansMode ? r : Math.toDegrees(r));
                }
                Complex z = v0.c;
                Complex inside = Complex.i().mul(z).add(Complex.real(1).sub(z.mul(z)).sqrt());
                Complex res = Complex.i().negate().mul(inside.ln());
                return Value.fromComplex(res);
            }
            case "acos": {
                if (v0.c.isReal() && Math.abs(v0.c.re) <= 1.0) {
                    double r = Math.acos(v0.c.re);
                    return Value.fromFloat(radiansMode ? r : Math.toDegrees(r));
                }
                Complex z = v0.c;
                Complex inside = Complex.i().mul(z).add(Complex.real(1).sub(z.mul(z)).sqrt());
                Complex asinZ = Complex.i().negate().mul(inside.ln());
                Complex res = Complex.real(Math.PI / 2.0).sub(asinZ);
                return Value.fromComplex(res);
            }
            case "atan": {
                if (v0.c.isReal()) {
                    double r = Math.atan(v0.c.re);
                    return Value.fromFloat(radiansMode ? r : Math.toDegrees(r));
                }
                Complex iz = Complex.i().mul(v0.c);
                Complex quot = Complex.real(1).sub(iz).div(Complex.real(1).add(iz));
                Complex res = new Complex(0, 0.5).mul(quot.ln());
                return Value.fromComplex(res);
            }
            case "sinh": return Value.fromComplex(v0.c.sinh());
            case "cosh": return Value.fromComplex(v0.c.cosh());
            case "tanh": return Value.fromComplex(v0.c.tanh());
            case "asinh": {
                Complex z = v0.c;
                return Value.fromComplex((z.add(z.mul(z).add(Complex.real(1)).sqrt())).ln());
            }
            case "acosh": {
                Complex z = v0.c;
                return Value.fromComplex((z.add(z.mul(z).sub(Complex.real(1)).sqrt())).ln());
            }
            case "atanh": {
                Complex z = v0.c;
                return Value.fromComplex(Complex.real(0.5).mul(((Complex.real(1).add(z)).div(Complex.real(1).sub(z))).ln()));
            }
            case "ln": return Value.fromComplex(v0.c.ln());
            case "exp": return Value.fromComplex(v0.c.exp());
            case "abs": {
                if (v0.rat != null) {
                    ExactRat r = new ExactRat(v0.rat.num.abs(), v0.rat.den);
                    return new Value(Complex.real(r.toDouble()), r);
                }
                return Value.fromFloat(v0.c.abs());
            }
            case "floor": {
                if (!v0.c.isReal()) throw new RuntimeException("floor erfordert reelle Zahl");
                if (v0.rat != null) {
                    BigInteger[] qr = v0.rat.num.divideAndRemainder(v0.rat.den);
                    BigInteger q = qr[0];
                    if (qr[1].signum() < 0) q = q.subtract(BigInteger.ONE);
                    return Value.fromInt(q);
                }
                return Value.fromInt(Math.round(Math.floor(v0.c.re)));
            }
            case "ceil": {
                if (!v0.c.isReal()) throw new RuntimeException("ceil erfordert reelle Zahl");
                if (v0.rat != null) {
                    BigInteger[] qr = v0.rat.num.divideAndRemainder(v0.rat.den);
                    BigInteger q = qr[0];
                    if (qr[1].signum() > 0) q = q.add(BigInteger.ONE);
                    return Value.fromInt(q);
                }
                return Value.fromInt(Math.round(Math.ceil(v0.c.re)));
            }
            case "round": {
                if (!v0.c.isReal()) throw new RuntimeException("round erfordert reelle Zahl");
                return Value.fromInt(Math.round(v0.c.re));
            }
            case "sign":
            case "sgn": {
                if (!v0.c.isReal()) throw new RuntimeException("sign erfordert reelle Zahl");
                long sgn = v0.c.re > 0 ? 1 : (v0.c.re < 0 ? -1 : 0);
                return Value.fromInt(sgn);
            }
            case "gcd": {
                if (args.size() != 2) throw new RuntimeException("gcd erfordert 2 Argumente");
                Value v1 = evalExpression(args.get(1), ov);
                BigInteger a = (v0.rat != null && v0.rat.den.equals(BigInteger.ONE))
                        ? v0.rat.num.abs()
                        : BigInteger.valueOf(Math.round(Math.abs(v0.c.re)));
                BigInteger b = (v1.rat != null && v1.rat.den.equals(BigInteger.ONE))
                        ? v1.rat.num.abs()
                        : BigInteger.valueOf(Math.round(Math.abs(v1.c.re)));
                return Value.fromInt(a.gcd(b));
            }
            case "lcm": {
                if (args.size() != 2) throw new RuntimeException("lcm erfordert 2 Argumente");
                Value v1 = evalExpression(args.get(1), ov);
                BigInteger a = (v0.rat != null && v0.rat.den.equals(BigInteger.ONE))
                        ? v0.rat.num.abs()
                        : BigInteger.valueOf(Math.round(Math.abs(v0.c.re)));
                BigInteger b = (v1.rat != null && v1.rat.den.equals(BigInteger.ONE))
                        ? v1.rat.num.abs()
                        : BigInteger.valueOf(Math.round(Math.abs(v1.c.re)));
                if (a.equals(BigInteger.ZERO) || b.equals(BigInteger.ZERO)) return Value.fromInt(0);
                return Value.fromInt(a.divide(a.gcd(b)).multiply(b));
            }
            case "mod": {
                if (args.size() != 2) throw new RuntimeException("mod erfordert 2 Argumente");
                Value v1 = evalExpression(args.get(1), ov);
                BigInteger a = (v0.rat != null && v0.rat.den.equals(BigInteger.ONE))
                        ? v0.rat.num
                        : BigInteger.valueOf(Math.round(v0.c.re));
                BigInteger b = (v1.rat != null && v1.rat.den.equals(BigInteger.ONE))
                        ? v1.rat.num
                        : BigInteger.valueOf(Math.round(v1.c.re));
                if (b.equals(BigInteger.ZERO)) throw new RuntimeException("Modulo durch Null");
                BigInteger rem = a.remainder(b);
                if (moduloAlwaysPositive && rem.signum() < 0) {
                    rem = rem.add(b.abs());
                }
                return Value.fromInt(rem);
            }
            case "nthroot": {
                Value v1 = evalExpression(args.get(1), ov);
                return v1.pow(Value.fromFloat(1.0 / v0.c.re));
            }
            case "log": {
                if (args.size() == 2) {
                    Value v1 = evalExpression(args.get(1), ov);
                    return Value.fromFloat(Math.log(v1.c.re) / Math.log(v0.c.re));
                }
                return Value.fromFloat(Math.log10(v0.c.re));
            }
            case "binomial":
            case "nCr": {
                Value v1 = evalExpression(args.get(1), ov);
                return Value.fromFloat(binomialCoeff(v0.c.re, v1.c.re));
            }
            default:
                throw new RuntimeException("Unknown func: " + fn);
        }
    }

    private double binomialCoeff(double n, double k) {
        if (k < 0.0 || k > n) return 0.0;
        long nu = Math.round(n);
        long ku = Math.round(k);
        ku = Math.min(ku, nu - ku);
        double res = 1.0;
        for (long i = 1; i <= ku; i++) {
            res = res * (nu - ku + i) / (double) i;
        }
        return res;
    }

    // --- Matrix Support ---
    private Result evaluateMatrix(String expr, boolean commit) {
        String trimmed = expr.trim();
        if (trimmed.startsWith("det(") && trimmed.endsWith(")")) {
            String inner = trimmed.substring(4, trimmed.length() - 1).trim();
            double[][] mat = evalMatrixExpr(inner);
            double det = matrixDet(mat);
            String dec = formatDecimal(det);
            String ex = formatExact(det, dec);
            if (commit) {
                if (Math.abs(det - Math.round(det)) < 1e-11) {
                    lastAns = Value.fromInt(Math.round(det));
                } else {
                    lastAns = Value.fromFloat(det);
                }
            }
            return new Result(det, ex, dec);
        }

        if (trimmed.startsWith("inv(") && trimmed.endsWith(")")) {
            String inner = trimmed.substring(4, trimmed.length() - 1).trim();
            double[][] mat = evalMatrixExpr(inner);
            double[][] inv = matrixInv(mat);
            String formatted = formatMatrix(inv);
            return new Result(0.0, formatted, formatted);
        }

        if ((trimmed.startsWith("trans(") || trimmed.startsWith("transpose(")) && trimmed.endsWith(")")) {
            int prefixLen = trimmed.startsWith("transpose(") ? 10 : 6;
            String inner = trimmed.substring(prefixLen, trimmed.length() - 1).trim();
            double[][] mat = evalMatrixExpr(inner);
            int rows = mat.length;
            int cols = mat[0].length;
            double[][] res = new double[cols][rows];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    res[c][r] = mat[r][c];
                }
            }
            String formatted = formatMatrix(res);
            return new Result(0.0, formatted, formatted);
        }

        double[][] mat = evalMatrixExpr(trimmed);
        String formatted = formatMatrix(mat);
        return new Result(0.0, formatted, formatted);
    }

    private double[][] matrixInv(double[][] m) {
        int n = m.length;
        if (n == 0 || m[0].length != n) throw new RuntimeException("Inverse erfordert quadratische Matrix");
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1.0;
        }
        for (int i = 0; i < n; i++) {
            int pivotRow = i;
            double maxVal = Math.abs(aug[i][i]);
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(aug[k][i]) > maxVal) {
                    maxVal = Math.abs(aug[k][i]);
                    pivotRow = k;
                }
            }
            if (maxVal < 1e-14) throw new RuntimeException("Matrix ist singulär (nicht invertierbar)");
            if (pivotRow != i) {
                double[] tmp = aug[i];
                aug[i] = aug[pivotRow];
                aug[pivotRow] = tmp;
            }
            double pivot = aug[i][i];
            for (int j = 0; j < 2 * n; j++) {
                aug[i][j] /= pivot;
            }
            for (int k = 0; k < n; k++) {
                if (k != i) {
                    double factor = aug[k][i];
                    for (int j = 0; j < 2 * n; j++) {
                        aug[k][j] -= factor * aug[i][j];
                    }
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(aug[i], n, inv[i], 0, n);
        }
        return inv;
    }

    private double[][] evalMatrixExpr(String s) {
        s = s.trim();
        int depth = 0;
        int splitPos = -1;
        char op = '+';
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '[' || ch == '(') depth++;
            else if (ch == ']' || ch == ')') depth--;
            else if (depth == 0 && (ch == '+' || ch == '-')) {
                splitPos = i;
                op = ch;
                break;
            }
        }
        if (splitPos >= 0) {
            double[][] left = evalMatrixExpr(s.substring(0, splitPos));
            double[][] right = evalMatrixExpr(s.substring(splitPos + 1));
            int rows = left.length;
            int cols = left[0].length;
            if (rows != right.length || cols != right[0].length) {
                throw new RuntimeException("Matrixdimensionen stimmen nicht überein");
            }
            double[][] res = new double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    res[r][c] = (op == '+') ? (left[r][c] + right[r][c]) : (left[r][c] - right[r][c]);
                }
            }
            return res;
        }

        if (s.startsWith("[")) {
            return parseMatrix(s);
        }
        throw new RuntimeException("Ungültiger Matrix-Ausdruck: " + s);
    }

    private double[][] parseMatrix(String s) {
        s = s.trim();
        if (!s.startsWith("[") || !s.endsWith("]")) {
            throw new RuntimeException("Matrix muss mit [[...]] umschlossen sein");
        }
        String inner = s.substring(1, s.length() - 1).trim();
        List<double[]> rows = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '[') {
                depth++;
                if (depth > 1) cur.append(ch);
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    String[] cells = cur.toString().split(",");
                    double[] rowVals = new double[cells.length];
                    for (int j = 0; j < cells.length; j++) {
                        rowVals[j] = evalExpression(cells[j].trim(), null).c.re;
                    }
                    rows.add(rowVals);
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            } else if (ch == ',' && depth == 0) {
                // Separator between rows
            } else {
                if (depth >= 1) cur.append(ch);
            }
        }
        if (rows.isEmpty()) throw new RuntimeException("Leere Matrix");
        int cols = rows.get(0).length;
        double[][] res = new double[rows.size()][cols];
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).length != cols) throw new RuntimeException("Matrixzeilen ungleich lang");
            res[i] = rows.get(i);
        }
        return res;
    }

    private double matrixDet(double[][] m) {
        int n = m.length;
        if (n == 0 || m[0].length != n) throw new RuntimeException("Determinante erfordert quadratische Matrix");
        if (n == 1) return m[0][0];
        if (n == 2) return m[0][0] * m[1][1] - m[0][1] * m[1][0];
        if (n == 3) {
            return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                    - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                    + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        }
        // General NxN via LU / Gaussian elimination with partial pivoting
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(m[i], 0, a[i], 0, n);
        double det = 1.0;
        double sign = 1.0;
        for (int i = 0; i < n; i++) {
            int pivotRow = i;
            double maxVal = Math.abs(a[i][i]);
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(a[k][i]) > maxVal) {
                    maxVal = Math.abs(a[k][i]);
                    pivotRow = k;
                }
            }
            if (maxVal < 1e-15) return 0.0;
            if (pivotRow != i) {
                double[] tmp = a[i];
                a[i] = a[pivotRow];
                a[pivotRow] = tmp;
                sign = -sign;
            }
            double pivot = a[i][i];
            det *= pivot;
            for (int k = i + 1; k < n; k++) {
                double factor = a[k][i] / pivot;
                for (int j = i + 1; j < n; j++) {
                    a[k][j] -= factor * a[i][j];
                }
            }
        }
        return det * sign;
    }

    private String formatMatrix(double[][] mat) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int r = 0; r < mat.length; r++) {
            if (r > 0) sb.append(", ");
            sb.append("[");
            for (int c = 0; c < mat[r].length; c++) {
                if (c > 0) sb.append(", ");
                sb.append(formatDecimal(mat[r][c]));
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    // --- Abstract Syntax Tree (AST) for Symbolic & Exact Calculations ---
    abstract static class AstNode {
        abstract AstNode diff(String var);
        abstract AstNode integrate(String var);
        abstract AstNode simplify();
        abstract String toDisplayString();
        abstract boolean containsVar(String var);
        abstract int prec();

        Value constValue() { return null; }

        boolean isZero() {
            Value v = constValue();
            return v != null && v.c.re == 0.0 && v.c.im == 0.0;
        }

        boolean isOne() {
            Value v = constValue();
            return v != null && v.c.re == 1.0 && v.c.im == 0.0;
        }

        boolean isMinusOne() {
            Value v = constValue();
            return v != null && v.c.re == -1.0 && v.c.im == 0.0;
        }

        boolean isNegativeDisplay() { return false; }

        static String wrapIf(AstNode node, boolean cond) {
            String s = node.toDisplayString();
            return cond ? "(" + s + ")" : s;
        }
    }

    static final class AstNum extends AstNode {
        final Value val;
        AstNum(Value val) { this.val = val; }
        AstNum(long n) { this.val = Value.fromInt(n); }
        AstNum(BigInteger n) { this.val = Value.fromInt(n); }
        @Override Value constValue() { return val; }
        @Override boolean containsVar(String var) { return false; }
        @Override int prec() {
            if (val.rat != null) {
                if (!val.rat.den.equals(BigInteger.ONE)) return 2;
                if (val.rat.num.signum() < 0) return 3;
                return 4;
            }
            return val.c.re < 0.0 ? 3 : 4;
        }
        @Override boolean isNegativeDisplay() {
            if (val.rat != null) return val.rat.num.signum() < 0;
            return val.c.re < 0.0;
        }
        @Override AstNode diff(String var) { return new AstNum(0); }
        @Override AstNode integrate(String var) {
            if (isOne()) return new AstVar(var);
            return new AstMul(this, new AstVar(var));
        }
        @Override AstNode simplify() { return this; }
        @Override String toDisplayString() {
            if (val.rat != null) {
                if (val.rat.den.equals(BigInteger.ONE)) return val.rat.num.toString();
                return val.rat.num.toString() + "/" + val.rat.den.toString();
            }
            return String.valueOf(val.c.re);
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstNum)) return false;
            AstNum other = (AstNum) o;
            if (val.rat != null && other.val.rat != null) {
                return val.rat.num.equals(other.val.rat.num) && val.rat.den.equals(other.val.rat.den);
            }
            return Double.compare(val.c.re, other.val.c.re) == 0 && Double.compare(val.c.im, other.val.c.im) == 0;
        }
        @Override public int hashCode() { return val.rat != null ? val.rat.num.hashCode() : Double.hashCode(val.c.re); }
    }

    static final class AstVar extends AstNode {
        final String name;
        AstVar(String name) { this.name = name; }
        @Override boolean containsVar(String var) { return name.equals(var); }
        @Override int prec() { return 4; }
        @Override AstNode diff(String var) { return name.equals(var) ? new AstNum(1) : new AstNum(0); }
        @Override AstNode integrate(String var) {
            if (name.equals(var)) {
                return new AstDiv(new AstPow(new AstVar(var), new AstNum(2)), new AstNum(2));
            }
            return new AstMul(this, new AstVar(var));
        }
        @Override AstNode simplify() { return this; }
        @Override String toDisplayString() { return name; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstVar)) return false;
            return name.equals(((AstVar) o).name);
        }
        @Override public int hashCode() { return name.hashCode(); }
    }

    static final class AstAdd extends AstNode {
        final AstNode left, right;
        AstAdd(AstNode left, AstNode right) { this.left = left; this.right = right; }
        @Override boolean containsVar(String var) { return left.containsVar(var) || right.containsVar(var); }
        @Override int prec() { return 1; }
        @Override AstNode diff(String var) {
            AstNode dl = left.diff(var);
            AstNode dr = right.diff(var);
            return (dl != null && dr != null) ? new AstAdd(dl, dr) : null;
        }
        @Override AstNode integrate(String var) {
            AstNode il = left.integrate(var);
            AstNode ir = right.integrate(var);
            return (il != null && ir != null) ? new AstAdd(il, ir) : null;
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sl.isZero()) return sr;
            if (sr.isZero()) return sl;
            return new AstAdd(sl, sr);
        }
        @Override String toDisplayString() {
            return left.toDisplayString() + "+" + wrapIf(right, right.isNegativeDisplay());
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstAdd)) return false;
            AstAdd a = (AstAdd) o;
            return left.equals(a.left) && right.equals(a.right);
        }
        @Override public int hashCode() { return left.hashCode() * 31 + right.hashCode(); }
    }

    static final class AstSub extends AstNode {
        final AstNode left, right;
        AstSub(AstNode left, AstNode right) { this.left = left; this.right = right; }
        @Override boolean containsVar(String var) { return left.containsVar(var) || right.containsVar(var); }
        @Override int prec() { return 1; }
        @Override AstNode diff(String var) {
            AstNode dl = left.diff(var);
            AstNode dr = right.diff(var);
            return (dl != null && dr != null) ? new AstSub(dl, dr) : null;
        }
        @Override AstNode integrate(String var) {
            AstNode il = left.integrate(var);
            AstNode ir = right.integrate(var);
            return (il != null && ir != null) ? new AstSub(il, ir) : null;
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sr.isZero()) return sl;
            if (sl.isZero()) return new AstNeg(sr).simplify();
            return new AstSub(sl, sr);
        }
        @Override String toDisplayString() {
            return left.toDisplayString() + "-" + wrapIf(right, right.prec() <= 1 || right.isNegativeDisplay());
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstSub)) return false;
            AstSub s = (AstSub) o;
            return left.equals(s.left) && right.equals(s.right);
        }
        @Override public int hashCode() { return left.hashCode() * 31 + right.hashCode(); }
    }

    static final class AstMul extends AstNode {
        final AstNode left, right;
        AstMul(AstNode left, AstNode right) { this.left = left; this.right = right; }
        @Override boolean containsVar(String var) { return left.containsVar(var) || right.containsVar(var); }
        @Override int prec() { return 2; }
        @Override AstNode diff(String var) {
            // (a * b)' = a' * b + a * b'
            AstNode dl = left.diff(var);
            AstNode dr = right.diff(var);
            if (dl == null || dr == null) return null;
            return new AstAdd(new AstMul(dl, right), new AstMul(left, dr));
        }
        @Override AstNode integrate(String var) {
            if (left instanceof AstNum) {
                AstNode ir = right.integrate(var);
                return ir != null ? new AstMul(left, ir) : null;
            }
            return null;
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sl.isZero() || sr.isZero()) return new AstNum(0);
            if (sl.isOne()) return sr;
            if (sr.isOne()) return sl;
            if (sl.isMinusOne()) return new AstNeg(sr).simplify();
            if (sr.isMinusOne()) return new AstNeg(sl).simplify();
            if (sr instanceof AstNeg) return new AstNeg(new AstMul(sl, ((AstNeg) sr).inner).simplify());
            if (sl instanceof AstNeg) return new AstNeg(new AstMul(((AstNeg) sl).inner, sr).simplify());
            return new AstMul(sl, sr);
        }
        @Override String toDisplayString() {
            return wrapIf(left, left.prec() < 2) + "*" + wrapIf(right, right.prec() < 2 || right.isNegativeDisplay());
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstMul)) return false;
            AstMul m = (AstMul) o;
            return left.equals(m.left) && right.equals(m.right);
        }
        @Override public int hashCode() { return left.hashCode() * 31 + right.hashCode(); }
    }

    static final class AstDiv extends AstNode {
        final AstNode left, right;
        AstDiv(AstNode left, AstNode right) { this.left = left; this.right = right; }
        @Override boolean containsVar(String var) { return left.containsVar(var) || right.containsVar(var); }
        @Override int prec() { return 2; }
        @Override AstNode diff(String var) {
            if (right.isZero()) return null;
            // (a / b)' = (a' * b - a * b') / b^2
            AstNode dl = left.diff(var);
            AstNode dr = right.diff(var);
            if (dl == null || dr == null) return null;
            return new AstDiv(
                new AstSub(new AstMul(dl, right), new AstMul(left, dr)),
                new AstPow(right, new AstNum(2))
            );
        }
        @Override AstNode integrate(String var) {
            if (left.isOne() && right instanceof AstVar && ((AstVar) right).name.equals(var)) {
                return new AstFunc("ln", java.util.Collections.singletonList(new AstVar(var)));
            }
            return null;
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sl.isZero()) return new AstNum(0);
            if (sr.isOne()) return sl;
            if (sl.equals(sr)) return new AstNum(1);
            return new AstDiv(sl, sr);
        }
        @Override String toDisplayString() {
            return wrapIf(left, left.prec() < 2) + "/" + wrapIf(right, right.prec() <= 2 || right.isNegativeDisplay());
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstDiv)) return false;
            AstDiv d = (AstDiv) o;
            return left.equals(d.left) && right.equals(d.right);
        }
        @Override public int hashCode() { return left.hashCode() * 31 + right.hashCode(); }
    }

    static final class AstPow extends AstNode {
        final AstNode base, exp;
        AstPow(AstNode base, AstNode exp) { this.base = base; this.exp = exp; }
        @Override boolean containsVar(String var) { return base.containsVar(var) || exp.containsVar(var); }
        @Override int prec() { return 3; }
        @Override AstNode diff(String var) {
            boolean baseHasVar = base.containsVar(var);
            boolean expHasVar = exp.containsVar(var);

            if (!baseHasVar && !expHasVar) {
                return new AstNum(0);
            }

            if (baseHasVar && !expHasVar) {
                AstNode uPrime = base.diff(var);
                if (uPrime == null) return null;
                Value cVal = exp.constValue();
                if (cVal != null) {
                    if (!cVal.c.isReal()) return null;
                    if (cVal.c.re == 0.0) return new AstNum(0);
                    if (cVal.c.re == 1.0) return uPrime;
                    Value cMinus1 = cVal.sub(Value.fromInt(1));
                    AstNode powNode = (cMinus1.c.re == 1.0 && cMinus1.c.im == 0.0)
                            ? base
                            : new AstPow(base, new AstNum(cMinus1));
                    return new AstMul(new AstMul(new AstNum(cVal), powNode), uPrime);
                } else {
                    AstNode expMinus1 = new AstSub(exp, new AstNum(1));
                    return new AstMul(new AstMul(exp, new AstPow(base, expMinus1)), uPrime);
                }
            }

            if (!baseHasVar && expHasVar) {
                if (base.isZero()) return null;
                AstNode vPrime = exp.diff(var);
                if (vPrime == null) return null;
                return new AstMul(
                        new AstMul(this, new AstFunc("ln", java.util.Collections.singletonList(base))),
                        vPrime
                );
            }

            // General power rule (u > 0): (u^v)' = u^v * (v' * ln(u) + (v * u') / u)
            AstNode uPrime = base.diff(var);
            AstNode vPrime = exp.diff(var);
            if (uPrime == null || vPrime == null) return null;
            AstNode term1 = new AstMul(vPrime, new AstFunc("ln", java.util.Collections.singletonList(base)));
            AstNode term2 = new AstDiv(new AstMul(exp, uPrime), base);
            return new AstMul(this, new AstAdd(term1, term2));
        }
        @Override AstNode integrate(String var) {
            if (base instanceof AstVar && ((AstVar) base).name.equals(var)) {
                Value cVal = exp.constValue();
                if (cVal != null && cVal.c.isReal() && Math.abs(cVal.c.re - Math.round(cVal.c.re)) < 1e-11) {
                    long n = Math.round(cVal.c.re);
                    if (n == -1) {
                        return new AstFunc("ln", java.util.Collections.singletonList(new AstVar(var)));
                    } else {
                        long newP = n + 1;
                        return new AstDiv(new AstPow(new AstVar(var), new AstNum(newP)), new AstNum(newP));
                    }
                }
            }
            return null;
        }
        @Override AstNode simplify() {
            AstNode sb = base.simplify();
            AstNode se = exp.simplify();
            if (se.isZero()) return new AstNum(1);
            if (se.isOne()) return sb;
            return new AstPow(sb, se);
        }
        @Override String toDisplayString() {
            return wrapIf(base, base.prec() < 4 || base.isNegativeDisplay())
                    + "^"
                    + wrapIf(exp, exp.prec() < 4 || exp.isNegativeDisplay());
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstPow)) return false;
            AstPow p = (AstPow) o;
            return base.equals(p.base) && exp.equals(p.exp);
        }
        @Override public int hashCode() { return base.hashCode() * 31 + exp.hashCode(); }
    }

    static final class AstNeg extends AstNode {
        final AstNode inner;
        AstNeg(AstNode inner) { this.inner = inner; }
        @Override Value constValue() {
            Value v = inner.constValue();
            if (v == null) return null;
            ExactRat r = v.rat != null ? new ExactRat(v.rat.num.negate(), v.rat.den) : null;
            return new Value(v.c.negate(), r);
        }
        @Override boolean containsVar(String var) { return inner.containsVar(var); }
        @Override int prec() { return 3; }
        @Override boolean isNegativeDisplay() { return true; }
        @Override AstNode diff(String var) {
            AstNode d = inner.diff(var);
            return d != null ? new AstNeg(d) : null;
        }
        @Override AstNode integrate(String var) {
            AstNode i = inner.integrate(var);
            return i != null ? new AstNeg(i) : null;
        }
        @Override AstNode simplify() {
            AstNode si = inner.simplify();
            if (si instanceof AstNeg) return ((AstNeg) si).inner;
            if (si.isZero()) return new AstNum(0);
            if (si instanceof AstNum) {
                Value v = ((AstNum) si).val;
                ExactRat r = v.rat != null ? new ExactRat(v.rat.num.negate(), v.rat.den) : null;
                return new AstNum(new Value(v.c.negate(), r));
            }
            return new AstNeg(si);
        }
        @Override String toDisplayString() {
            return "-" + wrapIf(inner, inner.prec() < 2 || inner.isNegativeDisplay());
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstNeg)) return false;
            return inner.equals(((AstNeg) o).inner);
        }
        @Override public int hashCode() { return -inner.hashCode(); }
    }

    static final class AstFunc extends AstNode {
        final String name;
        final List<AstNode> args;
        AstFunc(String name, List<AstNode> args) { this.name = name; this.args = args; }
        @Override boolean containsVar(String var) {
            for (AstNode a : args) {
                if (a.containsVar(var)) return true;
            }
            return false;
        }
        @Override int prec() { return 4; }
        @Override AstNode diff(String var) {
            if (!containsVar(var)) return new AstNum(0);
            if (args.size() == 1) {
                AstNode u = args.get(0);
                AstNode uPrime = u.diff(var);
                if (uPrime == null) return null;
                switch (name) {
                    case "sin": return new AstMul(uPrime, new AstFunc("cos", java.util.Collections.singletonList(u)));
                    case "cos": return new AstNeg(new AstMul(uPrime, new AstFunc("sin", java.util.Collections.singletonList(u))));
                    case "tan": return new AstDiv(uPrime, new AstPow(new AstFunc("cos", java.util.Collections.singletonList(u)), new AstNum(2)));
                    case "sinh": return new AstMul(uPrime, new AstFunc("cosh", java.util.Collections.singletonList(u)));
                    case "cosh": return new AstMul(uPrime, new AstFunc("sinh", java.util.Collections.singletonList(u)));
                    case "tanh": return new AstDiv(uPrime, new AstPow(new AstFunc("cosh", java.util.Collections.singletonList(u)), new AstNum(2)));
                    case "asin":
                    case "arcsin": return new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstSub(new AstNum(1), new AstPow(u, new AstNum(2))))));
                    case "acos":
                    case "arccos": return new AstNeg(new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstSub(new AstNum(1), new AstPow(u, new AstNum(2)))))));
                    case "atan":
                    case "arctan": return new AstDiv(uPrime, new AstAdd(new AstNum(1), new AstPow(u, new AstNum(2))));
                    case "asinh":
                    case "arcsinh": return new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstAdd(new AstPow(u, new AstNum(2)), new AstNum(1)))));
                    case "acosh":
                    case "arccosh": return new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstSub(new AstPow(u, new AstNum(2)), new AstNum(1)))));
                    case "atanh":
                    case "arctanh": return new AstDiv(uPrime, new AstSub(new AstNum(1), new AstPow(u, new AstNum(2))));
                    case "sqrt": return new AstDiv(uPrime, new AstMul(new AstNum(2), new AstFunc("sqrt", java.util.Collections.singletonList(u))));
                    case "exp": return new AstMul(uPrime, new AstFunc("exp", java.util.Collections.singletonList(u)));
                    case "ln": return new AstDiv(uPrime, u);
                    default: return null;
                }
            }
            if (args.size() == 2) {
                switch (name) {
                    case "power":
                    case "pow": return new AstPow(args.get(0), args.get(1)).diff(var);
                    case "addition": return new AstAdd(args.get(0), args.get(1)).diff(var);
                    case "subtraction": return new AstSub(args.get(0), args.get(1)).diff(var);
                    case "multiplication": return new AstMul(args.get(0), args.get(1)).diff(var);
                    case "division":
                    case "fraction": return new AstDiv(args.get(0), args.get(1)).diff(var);
                    default: return null;
                }
            }
            return null;
        }
        @Override AstNode integrate(String var) {
            if (args.size() == 1 && args.get(0) instanceof AstVar && ((AstVar) args.get(0)).name.equals(var)) {
                switch (name) {
                    case "exp": return new AstFunc("exp", java.util.Collections.singletonList(new AstVar(var)));
                    case "sin": return new AstNeg(new AstFunc("cos", java.util.Collections.singletonList(new AstVar(var))));
                    case "cos": return new AstFunc("sin", java.util.Collections.singletonList(new AstVar(var)));
                    case "sinh": return new AstFunc("cosh", java.util.Collections.singletonList(new AstVar(var)));
                    case "cosh": return new AstFunc("sinh", java.util.Collections.singletonList(new AstVar(var)));
                }
            }
            return null;
        }
        @Override AstNode simplify() {
            List<AstNode> sargs = new ArrayList<>();
            for (AstNode a : args) sargs.add(a.simplify());
            return new AstFunc(name, sargs);
        }
        @Override String toDisplayString() {
            StringBuilder sb = new StringBuilder(name).append("(");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i).toDisplayString());
            }
            sb.append(")");
            return sb.toString();
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AstFunc)) return false;
            AstFunc f = (AstFunc) o;
            return name.equals(f.name) && args.equals(f.args);
        }
        @Override public int hashCode() { return name.hashCode() * 31 + args.hashCode(); }
    }

    private static void flattenAstTerms(AstNode node, long sign, List<Map.Entry<Long, AstNode>> terms) {
        if (node instanceof AstAdd) {
            flattenAstTerms(((AstAdd) node).left, sign, terms);
            flattenAstTerms(((AstAdd) node).right, sign, terms);
        } else if (node instanceof AstSub) {
            flattenAstTerms(((AstSub) node).left, sign, terms);
            flattenAstTerms(((AstSub) node).right, -sign, terms);
        } else {
            terms.add(new java.util.AbstractMap.SimpleEntry<>(sign, node));
        }
    }

    public static AstNode simplifyPolynomialAst(AstNode node) {
        List<Map.Entry<Long, AstNode>> terms = new ArrayList<>();
        flattenAstTerms(node, 1L, terms);
        if (terms.size() < 2) return null;

        List<AstNode> factors = new ArrayList<>();
        List<Long> coeffs = new ArrayList<>();

        for (Map.Entry<Long, AstNode> entry : terms) {
            long sign = entry.getKey();
            AstNode t = entry.getValue();
            long coeff = sign;
            AstNode factor = t;

            if (t instanceof AstMul) {
                AstMul m = (AstMul) t;
                if (m.left instanceof AstNum) {
                    AstNum num = (AstNum) m.left;
                    if (num.val.rat != null && num.val.rat.den.equals(BigInteger.ONE)) {
                        coeff = num.val.rat.num.longValueExact() * sign;
                        factor = m.right;
                    }
                }
            }

            int foundIdx = -1;
            for (int i = 0; i < factors.size(); i++) {
                if (factors.get(i).equals(factor)) {
                    foundIdx = i;
                    break;
                }
            }
            if (foundIdx >= 0) {
                coeffs.set(foundIdx, coeffs.get(foundIdx) + coeff);
            } else {
                factors.add(factor);
                coeffs.add(coeff);
            }
        }

        AstNode result = null;
        for (int i = 0; i < factors.size(); i++) {
            long c = coeffs.get(i);
            if (c == 0) continue;
            AstNode factor = factors.get(i);
            AstNode termNode = (Math.abs(c) == 1) ? factor : new AstMul(new AstNum(Math.abs(c)), factor);

            if (result == null) {
                result = (c < 0) ? new AstNeg(termNode) : termNode;
            } else {
                result = (c > 0) ? new AstAdd(result, termNode) : new AstSub(result, termNode);
            }
        }
        return result;
    }

    // --- AST Parser ---
    static final class AstParser {
        private final String s;
        private int pos = -1, ch;

        private AstParser(String s) {
            this.s = s.replace("×", "*").replace("·", "*").replace("÷", "/")
                    .replace("π", "pi").replace("−", "-")
                    .replace("²", "^2").replace("³", "^3").replace("⁻¹", "^(-1)");
        }

        static AstNode tryParse(String expr) {
            try {
                AstParser p = new AstParser(expr);
                p.nextChar();
                AstNode node = p.parseExpression();
                while (p.ch == ' ') p.nextChar();
                if (p.pos < p.s.length()) return null;
                return node;
            } catch (Exception e) {
                return null;
            }
        }

        private void nextChar() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        private AstNode parseExpression() {
            AstNode x = parseTerm();
            for (;;) {
                if (eat('+')) x = new AstAdd(x, parseTerm());
                else if (eat('-')) x = new AstSub(x, parseTerm());
                else return x;
            }
        }

        private AstNode parseTerm() {
            AstNode x = parseUnary();
            for (;;) {
                while (ch == ' ') nextChar();
                if (eat('*')) {
                    x = new AstMul(x, parseUnary());
                } else if (eat('/')) {
                    x = new AstDiv(x, parseUnary());
                } else if (ch == '(' || ch == '√' || Character.isLetter(ch) || ch == '_') {
                    AstNode right = parsePower();
                    x = new AstMul(x, right);
                } else {
                    return x;
                }
            }
        }

        private AstNode parseUnary() {
            if (eat('+')) return parseUnary();
            if (eat('-')) return new AstNeg(parseUnary());
            if (eat('√')) return new AstFunc("sqrt", java.util.Collections.singletonList(parseUnary()));
            return parsePower();
        }

        private AstNode parsePower() {
            AstNode base = parsePostfix();
            if (eat('^')) {
                AstNode exp = parseUnary();
                base = new AstPow(base, exp);
            }
            return base;
        }

        private AstNode parsePostfix() {
            AstNode val = parsePrimary();
            for (;;) {
                if (eat('!')) {
                    val = new AstFunc("factorial", java.util.Collections.singletonList(val));
                } else if (eat('%')) {
                    val = new AstMul(val, new AstNum(Value.fromRat(1, 100)));
                } else {
                    break;
                }
            }
            return val;
        }

        private AstNode parsePrimary() {
            while (ch == ' ') nextChar();
            int startPos = this.pos;

            if (eat('%')) {
                return new AstNum(Value.fromRat(1, 100));
            }

            if (eat('(')) {
                AstNode x = parseExpression();
                eat(')');
                return x;
            }

            // Hex number 0x...
            if (ch == '0' && pos + 1 < s.length() && (s.charAt(pos + 1) == 'x' || s.charAt(pos + 1) == 'X')) {
                nextChar();
                nextChar();
                int hexStart = pos;
                while (Character.digit(ch, 16) != -1) nextChar();
                String hexStr = s.substring(hexStart, pos);
                BigInteger bi = new BigInteger(hexStr, 16);
                return new AstNum(bi);
            }

            // Number
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                boolean hasDot = false;
                boolean hasExp = false;
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    if (ch == '.') hasDot = true;
                    nextChar();
                }
                if (ch == 'e' || ch == 'E') {
                    hasExp = true;
                    nextChar();
                    if (ch == '+' || ch == '-') nextChar();
                    while (ch >= '0' && ch <= '9') nextChar();
                }
                String numStr = s.substring(startPos, pos);
                if (!hasDot && !hasExp) {
                    try {
                        BigInteger bi = new BigInteger(numStr);
                        return new AstNum(bi);
                    } catch (Exception ignored) {}
                }
                double d = Double.parseDouble(numStr);
                return new AstNum(Value.fromFloat(d));
            }

            // Identifier or Function call
            if (Character.isLetter(ch) || ch == '_') {
                while (Character.isLetterOrDigit(ch) || ch == '_') nextChar();
                String ident = s.substring(startPos, pos);
                if (eat('(')) {
                    List<AstNode> args = new ArrayList<>();
                    if (!eat(')')) {
                        for (;;) {
                            args.add(parseExpression());
                            if (eat(',')) continue;
                            if (eat(')')) break;
                            throw new RuntimeException("Expected ',' or ')'");
                        }
                    }
                    return new AstFunc(ident, args);
                } else {
                    return new AstVar(ident);
                }
            }

            throw new RuntimeException("Unexpected: " + (ch == -1 ? "EOF" : (char) ch));
        }
    }

    private Result tryEvaluateSymbolicCalculus(AstNode ast) {
        if (ast instanceof AstFunc) {
            AstFunc f = (AstFunc) ast;
            if (f.name.equals("integral") && f.args.size() == 2) {
                if (f.args.get(1) instanceof AstVar) {
                    String var = ((AstVar) f.args.get(1)).name;
                    AstNode resAst = f.args.get(0).integrate(var);
                    if (resAst != null) {
                        String res = resAst.simplify().toDisplayString();
                        return new Result(0.0, res, res);
                    }
                }
            }
            if ((f.name.equals("d") || f.name.equals("deriv") || f.name.equals("diff")) && f.args.size() == 2) {
                if (f.args.get(1) instanceof AstVar) {
                    String var = ((AstVar) f.args.get(1)).name;
                    AstNode diffAst = f.args.get(0).diff(var);
                    if (diffAst != null) {
                        String res = diffAst.simplify().toDisplayString();
                        return new Result(0.0, res, res);
                    }
                }
            }
        }
        return null;
    }

    private Result tryEvaluateSymbolicPoly(AstNode ast) {
        AstNode simplified = simplifyPolynomialAst(ast);
        if (simplified != null) {
            String res = simplified.toDisplayString();
            return new Result(0.0, res, res);
        }
        return null;
    }

    // --- Formatting ---
    public String formatDecimal(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "Infinity" : "-Infinity";
        double absV = Math.abs(v);
        if (absV == 0.0) return "0";

        // Scientific notation for very small (< 1e-6) or large (>= 1e12) values
        if (absV >= 1e12 || absV < 1e-6) {
            String s = String.format(Locale.US, "%.8g", v);
            if (s.contains("e") || s.contains("E")) {
                String[] p = s.split("[eE]");
                String mantissa = trimTrailingZeros(p[0]);
                int exp = Integer.parseInt(p[1]);
                return mantissa + "e" + exp;
            }
            return trimTrailingZeros(s);
        }

        // Integer format if close to whole number and within range
        if (Math.abs(v - Math.round(v)) < 1e-11) {
            return String.valueOf(Math.round(v));
        }

        return trimTrailingZeros(String.format(Locale.US, "%.10f", v));
    }

    private String formatDecimalVal(Value val) {
        if (val.rat != null && val.rat.den.equals(BigInteger.ONE)) {
            return val.rat.num.toString();
        }
        if (!val.c.isReal()) {
            if (Math.abs(val.c.re) < 1e-12) {
                if (Math.abs(val.c.im - 1.0) < 1e-12) return "i";
                if (Math.abs(val.c.im + 1.0) < 1e-12) return "-i";
                return formatDecimal(val.c.im) + " i";
            }
            return formatDecimal(val.c.re) + (val.c.im >= 0 ? " + " : " - ") + formatDecimal(Math.abs(val.c.im)) + " i";
        }
        return formatDecimal(val.c.re);
    }

    private String trimTrailingZeros(String s) {
        if (!s.contains(".")) return s;
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String formatExactVal(Value val, String decFallback) {
        if (val.rat != null) {
            if (val.rat.den.equals(BigInteger.ONE)) {
                return val.rat.num.toString();
            }
            return val.rat.num.toString() + " / " + val.rat.den.toString();
        }
        if (!val.c.isReal()) {
            if (Math.abs(val.c.re) < 1e-12 && Math.abs(val.c.im - 1.0) < 1e-12) return "i";
        }
        return formatExact(val.c.re, decFallback);
    }

    private String formatExact(double v, String decFallback) {
        if (!Double.isFinite(v)) return decFallback;
        if (Math.abs(v - Math.round(v)) < 1e-11 && Math.abs(v) >= 1e-6) {
            return decFallback;
        }

        // Surds check (√2, √3, √5, √6, √7, √8, etc.)
        for (int rad : new int[]{2, 3, 5, 6, 7, 8, 10}) {
            double root = Math.sqrt(rad);
            double ratio = v / root;
            if (Math.abs(ratio - 0.5) < 1e-9) return "√" + rad + " / 2";
            if (Math.abs(ratio - 1.0) < 1e-9) return "√" + rad;
            if (Math.abs(ratio - 2.0) < 1e-9 && rad == 2) return "2√2";
            if (rad == 8 && Math.abs(ratio - 1.0) < 1e-9) return "2√2";
        }

        return decFallback;
    }
}
