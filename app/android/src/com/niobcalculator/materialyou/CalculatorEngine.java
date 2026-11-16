package com.niobcalculator.materialyou;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * The calculation code shared by the Android app and the JVM regression suite.
 * 100% Offline Exact Rational, Complex, AST Symbolic, Matrix & Adaptive Calculus Engine.
 */
public final class CalculatorEngine {
    // Exact mathematical constants in Niob Scientific Calculator
    public static final double SPEED_OF_LIGHT = 299792458.0;
    public static final double PERMEABILITY_VACUUM = 1.25663706212e-6;
    public static final double PERMITTIVITY_VACUUM = 8.8541878128e-12;
    public static final double GRAVITATION_CONST = 6.67430e-11;
    public static final double PLANCK_CONSTANT = 6.62607015e-34;
    public static final double FINE_STRUCTURE_CONST = 7.2973525693e-3;
    public static final double BOLTZMANN_CONST = 1.380649e-23;
    public static final double GRAVITATIONAL_ACCELERATION = 9.80665;
    public static final double PLANCK_MASS = 2.176434e-8;
    public static final double PLANCK_TIME = 5.391247e-44;
    public static final double PLANCK_LENGTH = 1.616255e-35;
    public static final double ELECTRON_CHARGE = 1.602176634e-19;
    public static final double IMPEDANCE_VACUUM = 376.730313668;
    public static final double MAGNETIC_FLUX_QUANTUM = 2.067833848e-15;
    public static final double JOSEPHSON_CONST = 483597.8484e9;
    public static final double VON_KLITZING_CONST = 25812.80745;
    public static final double CONDUCTANCE_QUANTUM = 7.748091729e-5;
    public static final double STEFAN_BOLTZMANN_CONST = 5.670374419e-8;
    public static final double RYDBERG_CONST = 10973731.568160;
    public static final double HARTREE_ENERGY = 4.3597447222071e-18;
    public static final double BOHR_MAGNETON = 1.39962449361e10;
    public static final double ELECTRON_MAGNETIC_MOMENT = -9.2847647043e-24;
    public static final double AVOGADRO_CONST = 6.02214076e23;
    public static final double ATOMIC_MASS_CONST = 1.66053906660e-27;
    public static final double FARADAY_CONST = 96485.3321233100184;
    public static final double MOLAR_GAS_CONSTANT = 8.31446261815324;
    public static final double MASS_ELECTRON = 9.1093837015e-31;
    public static final double MASS_PROTON = 1.67262192369e-27;
    public static final double MASS_NEUTRON = 1.67492749804e-27;
    public static final double MASS_MUON = 1.883531627e-28;
    public static final double CLASSICAL_ELECTRON_RADIUS = 2.8179403262e-15;
    public static final double COMPTON_WAVELENGTH = 2.42631023867e-12;
    public static final double BOHR_RADIUS = 5.29177210903e-11;
    public static final double NUCLEAR_MAGNETON = 5.0507837461e-27;
    public static final double PERCENT_VAR = 0.01;

    public static final String[] USER_VARIABLES = {
            "a", "b", "c", "d", "k", "l", "m", "n", "x", "y", "z"
    };

    private static final ThreadLocal<Long> EVAL_DEADLINE_NS = new ThreadLocal<>();
    private static final ThreadLocal<Integer> INTEGRATION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        Long deadline = EVAL_DEADLINE_NS.get();
        if (deadline != null && System.nanoTime() > deadline) {
            throw new RuntimeException("Zeitlimit überschritten");
        }
    }

    private final Map<String, Value> variables = new HashMap<>();
    private final Map<String, double[][]> matrixVariables = new HashMap<>();
    private Value lastAns = Value.fromInt(0);
    private double[][] lastMatrixAns = null;
    private boolean radiansMode = true;
    private boolean moduloAlwaysPositive = true;
    private IntegrationReport lastIntegrationReport = null;

    private static double[][] cloneMatrix(double[][] m) {
        if (m == null) return null;
        double[][] copy = new double[m.length][];
        for (int i = 0; i < m.length; i++) {
            copy[i] = m[i].clone();
        }
        return copy;
    }

    public CalculatorEngine() {
        initConstantsAndVariables();
    }

    CalculatorEngine snapshotForPlot() {
        return new CalculatorEngine(this);
    }

    public CalculatorEngine(CalculatorEngine source) {
        variables.putAll(source.variables);
        for (Map.Entry<String, double[][]> entry : source.matrixVariables.entrySet()) {
            matrixVariables.put(entry.getKey(), cloneMatrix(entry.getValue()));
        }
        lastAns = source.lastAns;
        lastMatrixAns = cloneMatrix(source.lastMatrixAns);
        radiansMode = source.radiansMode;
        moduloAlwaysPositive = source.moduloAlwaysPositive;
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
        public final String reusableText;

        public Result(double value, String exactText, String decimalText) {
            this(value, exactText, decimalText, exactText);
        }

        public Result(double value, String exactText, String decimalText, String reusableText) {
            this.value = value;
            this.exactText = exactText;
            this.decimalText = decimalText;
            this.reusableText = reusableText;
        }
    }

    public Result evaluate(String expression) {
        return evaluateInternal(expression, false);
    }

    public Result evaluateAndCommit(String expression) {
        return evaluateInternal(expression, true);
    }

    public Result evaluateWithTimeout(String expression, long timeoutMs) {
        return evaluateWithTimeoutInternal(expression, false, timeoutMs);
    }

    public Result evaluateAndCommitWithTimeout(String expression, long timeoutMs) {
        return evaluateWithTimeoutInternal(expression, true, timeoutMs);
    }

    private Result evaluateWithTimeoutInternal(String expression, boolean commit, long timeoutMs) {
        Long prev = EVAL_DEADLINE_NS.get();
        long deadline = System.nanoTime() + Math.max(1L, timeoutMs) * 1_000_000L;
        EVAL_DEADLINE_NS.set(prev != null ? Math.min(prev, deadline) : deadline);
        try {
            return evaluateInternal(expression, commit);
        } finally {
            if (prev == null) {
                EVAL_DEADLINE_NS.remove();
            } else {
                EVAL_DEADLINE_NS.set(prev);
            }
        }
    }

    private Result evaluateInternal(String rawExpr, boolean commit) {
        lastIntegrationReport = null;
        if (rawExpr == null || rawExpr.trim().isEmpty()) {
            throw new RuntimeException("Leerer Ausdruck");
        }
        String trimmed = rawExpr.trim();
        checkBracketDepth(trimmed);
        AstNode parsedAst = AstParser.tryParse(trimmed);

        // 0. Unset variables: `unset(var)`, `~=var`, `var~=`
        String unsetInner = null;
        if (trimmed.startsWith("unset(") && trimmed.endsWith(")")) {
            unsetInner = trimmed.substring(6, trimmed.length() - 1).trim();
        } else if (trimmed.startsWith("~=")) {
            unsetInner = trimmed.substring(2).trim();
        } else if (trimmed.endsWith("~=")) {
            unsetInner = trimmed.substring(0, trimmed.length() - 2).trim();
        }
        if (unsetInner != null && isUserVariable(unsetInner)) {
            if (commit) {
                variables.remove(unsetInner);
                matrixVariables.remove(unsetInner);
            }
            String msg = unsetInner + " gelöscht (unset)";
            return new Result(0.0, msg, msg, unsetInner);
        }

        // 1. Variable Assignment `var := expr`
        int assignIdx = trimmed.indexOf(":=");
        if (assignIdx >= 0) {
            String vname = trimmed.substring(0, assignIdx).trim();
            if (!isUserVariable(vname)) {
                throw new RuntimeException("Unbekannte Variable: " + vname);
            }
            String rhs = trimmed.substring(assignIdx + 2).trim();
            AstNode rhsAst = AstParser.tryParse(rhs);
            if (rhs.startsWith("inv(")
                    || rhs.startsWith("trans(")
                    || rhs.startsWith("transpose(")
                    || rhs.startsWith("[")
                    || (rhsAst != null && containsMatrixExpression(rhsAst))) {
                double[][] rhsMatrix = evalMatrixExpr(rhs, null);
                String display = formatMatrix(rhsMatrix, false);
                String reusable = formatMatrix(rhsMatrix, true);
                if (commit) {
                    matrixVariables.put(vname, cloneMatrix(rhsMatrix));
                    variables.remove(vname);
                    lastMatrixAns = cloneMatrix(rhsMatrix);
                }
                return new Result(0.0, vname + " := " + display, vname + " := " + display, reusable);
            }
            Value rhsVal = evalScalar(rhs, rhsAst);
            if (!Double.isFinite(rhsVal.c.re) || !Double.isFinite(rhsVal.c.im)) {
                throw new RuntimeException("Ungültiges Rechenergebnis (Division durch Null oder Überlauf)");
            }
            String decimal = formatDecimalVal(rhsVal);
            String symbolic = symbolicResult(rhsAst, rhsVal);
            String exact = formatExactVal(rhsVal, decimal, symbolic);
            String reusable = formatReusableVal(rhsVal, symbolic);
            if (commit) {
                variables.put(vname, rhsVal);
                matrixVariables.remove(vname);
                lastAns = rhsVal;
                lastMatrixAns = null;
            }
            return new Result(rhsVal.c.re, vname + " := " + exact, vname + " := " + decimal, reusable);
        }

        // 2. Matrix Expressions
        if (trimmed.startsWith("inv(")
                || trimmed.startsWith("trans(")
                || trimmed.startsWith("transpose(")
                || trimmed.startsWith("[")
                || (parsedAst != null && containsMatrixExpression(parsedAst))) {
            double[][] matrix = evalMatrixExpr(trimmed, null);
            String display = formatMatrix(matrix, false);
            if (commit) {
                lastMatrixAns = cloneMatrix(matrix);
            }
            return new Result(0.0, display, display, formatMatrix(matrix, true));
        }

        // 3. Symbolic calculus functions via AST
        if (parsedAst != null) {
            Result symCalc = tryEvaluateSymbolicCalculus(parsedAst);
            if (symCalc != null) {
                return symCalc;
            }
        }

        // 4. Numeric evaluation uses the same syntax tree as the plotter.
        Value val;
        try {
            val = evalScalar(trimmed, parsedAst);
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
        String symbolic = symbolicResult(parsedAst, val);
        String exact = formatExactVal(val, decimal, symbolic);
        String reusable = formatReusableVal(val, symbolic);

        if (commit) {
            lastAns = val;
            lastMatrixAns = null;
        }
        return new Result(numericVal, exact, decimal, reusable);
    }

    private Value evalScalar(String source, AstNode ast) {
        // The input preview historically allows an unfinished parenthesis.
        return ast != null ? evalAst(ast, null) : evalExpression(source, null);
    }

    public static final class PlotFunction {
        private final AstNode expression;
        private final CalculatorEngine engine;

        private PlotFunction(AstNode expression, CalculatorEngine engine) {
            this.expression = expression;
            this.engine = engine;
        }

        PlotFunction withContext(CalculatorEngine context) {
            return new PlotFunction(expression, context);
        }

        public double evaluate(double x) {
            return engine.evaluatePlotAst(expression, x);
        }
    }

    public double evaluateForX(String expression, double x) {
        PlotFunction function = compilePlotFunction(expression);
        return function == null ? Double.NaN : function.evaluate(x);
    }

    public void validatePlotSyntax(String expression) {
        if (compilePlotFunction(expression) == null) {
            throw new RuntimeException("Leerer Plot-Ausdruck");
        }
    }

    void validatePlotFunction(PlotFunction function) {
        Set<String> scope = new HashSet<>(variables.keySet());
        scope.add("x");
        scope.add("pi");
        scope.add("π");
        scope.add("e");
        scope.add("i");
        scope.add("ans");
        scope.add("ANS");
        validatePlotAst(function.expression, scope);
    }

    private void validatePlotAst(AstNode node, Set<String> scope) {
        if (node instanceof AstNum) return;
        if (node instanceof AstVar) {
            String name = ((AstVar) node).name;
            if (!scope.contains(name)) {
                throw new RuntimeException("Unbekannte Variable: " + name);
            }
            return;
        }
        if (node instanceof AstNeg) {
            validatePlotAst(((AstNeg) node).inner, scope);
            return;
        }
        if (node instanceof AstAdd) {
            validatePlotAst(((AstAdd) node).left, scope);
            validatePlotAst(((AstAdd) node).right, scope);
            return;
        }
        if (node instanceof AstSub) {
            validatePlotAst(((AstSub) node).left, scope);
            validatePlotAst(((AstSub) node).right, scope);
            return;
        }
        if (node instanceof AstMul) {
            validatePlotAst(((AstMul) node).left, scope);
            validatePlotAst(((AstMul) node).right, scope);
            return;
        }
        if (node instanceof AstDiv) {
            validatePlotAst(((AstDiv) node).left, scope);
            validatePlotAst(((AstDiv) node).right, scope);
            return;
        }
        if (node instanceof AstPow) {
            validatePlotAst(((AstPow) node).base, scope);
            validatePlotAst(((AstPow) node).exp, scope);
            return;
        }
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            String fn = f.name;
            int argc = f.args.size();
            validateFunctionArity(fn, argc);
            if (fn.equals("det")) {
                validatePlotMatrix(f.args.get(0), scope);
                return;
            }
            if (fn.equals("sum") || fn.equals("prod") || fn.equals("product")
                    || fn.equals("int") || fn.equals("defint")) {
                if (!(f.args.get(1) instanceof AstVar)) {
                    throw new RuntimeException("Ungültige Argumente für " + fn);
                }
                validatePlotAst(f.args.get(2), scope);
                validatePlotAst(f.args.get(3), scope);
                Set<String> innerScope = new HashSet<>(scope);
                innerScope.add(((AstVar) f.args.get(1)).name);
                validatePlotAst(f.args.get(0), innerScope);
                return;
            }
            if (fn.equals("deriv") || fn.equals("diff") || fn.equals("d")) {
                if (!(f.args.get(1) instanceof AstVar)) {
                    throw new RuntimeException("Ungültige Argumente für " + fn);
                }
                if (argc == 3) validatePlotAst(f.args.get(2), scope);
                Set<String> innerScope = new HashSet<>(scope);
                innerScope.add(((AstVar) f.args.get(1)).name);
                validatePlotAst(f.args.get(0), innerScope);
                return;
            }
            if (fn.equals("integral") && argc == 2) {
                if (!(f.args.get(1) instanceof AstVar)) {
                    throw new RuntimeException("Ungültige Argumente für " + fn);
                }
                Set<String> innerScope = new HashSet<>(scope);
                innerScope.add(((AstVar) f.args.get(1)).name);
                validatePlotAst(f.args.get(0), innerScope);
                return;
            }
            for (AstNode a : f.args) {
                validatePlotAst(a, scope);
            }
            return;
        }
        throw new RuntimeException("Ungültiger AST-Knoten");
    }

    private static void validateFunctionArity(String name, int count) {
        int minimum;
        int maximum;
        switch (name) {
            case "sum": case "prod": case "product": case "int": case "defint":
                minimum = maximum = 4;
                break;
            case "integral":
                minimum = maximum = 2;
                break;
            case "deriv": case "diff": case "d":
                minimum = 2;
                maximum = 3;
                break;
            case "log": case "nthroot":
                minimum = 1;
                maximum = 2;
                break;
            case "gcd": case "lcm": case "mod": case "binomial": case "nCr":
                minimum = maximum = 2;
                break;
            case "factorial": case "!": case "percent": case "%": case "det":
            case "sin": case "cos": case "tan": case "asin": case "acos": case "atan":
            case "sinh": case "cosh": case "tanh": case "asinh": case "acosh": case "atanh":
            case "exp": case "ln": case "lg": case "log10": case "sqrt": case "abs":
            case "re": case "im": case "arg": case "floor": case "ceil": case "round":
            case "sign": case "sgn":
                minimum = maximum = 1;
                break;
            default:
                throw new RuntimeException("Unbekannte Funktion: " + name);
        }
        if (count < minimum || count > maximum) {
            String expected = minimum == maximum ? String.valueOf(minimum) : minimum + " oder " + maximum;
            throw new RuntimeException(name + " erfordert " + expected + (maximum == 1 ? " Argument" : " Argumente"));
        }
    }

    public PlotFunction compilePlotFunction(String expression) {
        if (expression == null || expression.trim().isEmpty()) return null;
        AstNode ast = AstParser.tryParse(expression.trim());
        if (ast == null) throw new RuntimeException("Ungültige Plot-Syntax");
        PlotFunction function = new PlotFunction(ast, this);
        validatePlotFunction(function);
        return function;
    }

    private double evaluatePlotAst(AstNode ast, double x) {
        IntegrationReport saved = lastIntegrationReport;
        try {
            Map<String, Value> overrides = new HashMap<>();
            overrides.put("x", Value.fromFloat(x));
            Value value = evalAst(ast, overrides);
            return value.c.isReal() && Double.isFinite(value.c.re) ? value.c.re : Double.NaN;
        } catch (ArithmeticException e) {
            return Double.NaN;
        } finally {
            lastIntegrationReport = saved;
        }
    }

    private static long requireIntegerBound(Value v, String fnName) {
        if (!v.c.isReal() || !Double.isFinite(v.c.re)) {
            throw new RuntimeException(fnName + "-Grenze muss eine endliche ganze Zahl sein");
        }
        if (v.rat != null) {
            if (!v.rat.den.equals(BigInteger.ONE)) {
                throw new RuntimeException(fnName + "-Grenze muss ganzzahlig sein");
            }
            return v.rat.num.longValueExact();
        }
        double rounded = Math.rint(v.c.re);
        if (Math.abs(v.c.re - rounded) > 1e-9 || Math.abs(rounded) > 9e15) {
            throw new RuntimeException(fnName + "-Grenze muss ganzzahlig sein");
        }
        return Math.round(rounded);
    }

    private double evalRealDerivativeSample(AstNode expr, Map<String, Value> local, String var, double x) {
        local.put(var, Value.fromFloat(x));
        Value val = evalAst(expr, local);
        if (!val.c.isReal() || !Double.isFinite(val.c.re)) {
            throw new RuntimeException("Funktion an der Stelle nicht reell differenzierbar");
        }
        return val.c.re;
    }

    private static boolean hasTrigFunction(AstNode node) {
        if (node instanceof AstNeg) return hasTrigFunction(((AstNeg) node).inner);
        if (node instanceof AstAdd) return hasTrigFunction(((AstAdd) node).left) || hasTrigFunction(((AstAdd) node).right);
        if (node instanceof AstSub) return hasTrigFunction(((AstSub) node).left) || hasTrigFunction(((AstSub) node).right);
        if (node instanceof AstMul) return hasTrigFunction(((AstMul) node).left) || hasTrigFunction(((AstMul) node).right);
        if (node instanceof AstDiv) return hasTrigFunction(((AstDiv) node).left) || hasTrigFunction(((AstDiv) node).right);
        if (node instanceof AstPow) return hasTrigFunction(((AstPow) node).base) || hasTrigFunction(((AstPow) node).exp);
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            if (f.name.equals("sin") || f.name.equals("cos") || f.name.equals("tan")) return true;
            for (AstNode a : f.args) {
                if (hasTrigFunction(a)) return true;
            }
        }
        return false;
    }

    private static boolean containsIntegralNode(AstNode node) {
        if (node instanceof AstNeg) return containsIntegralNode(((AstNeg) node).inner);
        if (node instanceof AstAdd) return containsIntegralNode(((AstAdd) node).left) || containsIntegralNode(((AstAdd) node).right);
        if (node instanceof AstSub) return containsIntegralNode(((AstSub) node).left) || containsIntegralNode(((AstSub) node).right);
        if (node instanceof AstMul) return containsIntegralNode(((AstMul) node).left) || containsIntegralNode(((AstMul) node).right);
        if (node instanceof AstDiv) return containsIntegralNode(((AstDiv) node).left) || containsIntegralNode(((AstDiv) node).right);
        if (node instanceof AstPow) return containsIntegralNode(((AstPow) node).base) || containsIntegralNode(((AstPow) node).exp);
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            if (f.name.equals("int") || f.name.equals("defint")) return true;
            for (AstNode a : f.args) {
                if (containsIntegralNode(a)) return true;
            }
        }
        return false;
    }

    private Value evalAst(AstNode node, Map<String, Value> ov) {
        checkInterrupted();
        if (node instanceof AstNum) return ((AstNum) node).val;
        if (node instanceof AstVar) {
            return resolveVar(((AstVar) node).name, ov);
        }
        if (node instanceof AstAdd) {
            return evalAst(((AstAdd) node).left, ov).add(evalAst(((AstAdd) node).right, ov));
        }
        if (node instanceof AstSub) {
            return evalAst(((AstSub) node).left, ov).sub(evalAst(((AstSub) node).right, ov));
        }
        if (node instanceof AstMul) {
            return evalAst(((AstMul) node).left, ov).mul(evalAst(((AstMul) node).right, ov));
        }
        if (node instanceof AstDiv) {
            return evalAst(((AstDiv) node).left, ov).div(evalAst(((AstDiv) node).right, ov));
        }
        if (node instanceof AstPow) {
            return evalAst(((AstPow) node).base, ov).pow(evalAst(((AstPow) node).exp, ov));
        }
        if (node instanceof AstNeg) {
            return evalAst(((AstNeg) node).inner, ov).negate();
        }
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            String fn = f.name;
            validateFunctionArity(fn, f.args.size());
            boolean sum = fn.equals("sum");
            if ((sum || fn.equals("prod") || fn.equals("product")) && f.args.size() == 4) {
                String v = calculusVariable(f.args.get(1).toDisplayString());
                long a = requireIntegerBound(evalAst(f.args.get(2), ov), fn);
                long b = requireIntegerBound(evalAst(f.args.get(3), ov), fn);
                if (a <= Long.MAX_VALUE - 100000 && b > a + 100000) {
                    throw new RuntimeException(sum ? "Summationsbereich zu groß" : "Produktbereich zu groß");
                }
                Value result = Value.fromInt(sum ? 0 : 1);
                if (a > b) return result;
                Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
                int iter = 0;
                for (long k = a; ; k++) {
                    if ((++iter & 255) == 0) checkInterrupted();
                    local.put(v, Value.fromInt(k));
                    Value term = evalAst(f.args.get(0), local);
                    result = sum ? result.add(term) : result.mul(term);
                    if (k == b) return result;
                }
            }
            if ((fn.equals("int") || fn.equals("defint")) && f.args.size() == 4) {
                String v = calculusVariable(f.args.get(1).toDisplayString());
                Value aVal = evalAst(f.args.get(2), ov);
                Value bVal = evalAst(f.args.get(3), ov);
                if (!aVal.c.isReal() || !bVal.c.isReal() || !Double.isFinite(aVal.c.re) || !Double.isFinite(bVal.c.re)) {
                    throw new RuntimeException("Integrationsgrenzen müssen endliche reelle Zahlen sein");
                }
                return Value.fromFloat(evaluateDefiniteIntegral(
                        f.args.get(0), f.args.get(0).toDisplayString(), v, aVal.c.re, bVal.c.re, ov));
            }
            if (fn.equals("integral") && f.args.size() == 2) {
                String v = calculusVariable(f.args.get(1).toDisplayString());
                AstNode iNode = f.args.get(0).integrate(v, radiansMode);
                if (iNode == null) throw new RuntimeException("Symbolische Integration nicht möglich");
                return evalAst(iNode, ov);
            }
            if ((fn.equals("deriv") || fn.equals("diff") || fn.equals("d")) && f.args.size() >= 2) {
                String v = calculusVariable(f.args.get(1).toDisplayString());
                if (f.args.size() == 2) {
                    if (containsStepFunction(f.args.get(0))) {
                        throw new RuntimeException("Symbolische Ableitung nicht möglich");
                    }
                    AstNode dNode = f.args.get(0).diff(v, radiansMode);
                    if (dNode == null) throw new RuntimeException("Symbolische Ableitung nicht möglich");
                    return evalAst(dNode, ov);
                }
                Value x0Val = evalAst(f.args.get(2), ov);
                if (!x0Val.c.isReal() || !Double.isFinite(x0Val.c.re)) {
                    throw new RuntimeException("Ableitungsstelle muss eine endliche reelle Zahl sein");
                }
                double x0 = x0Val.c.re;
                Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
                if (containsDiscreteDomainOf(f.args.get(0), v)) {
                    throw new RuntimeException("Funktion an der Stelle nicht reell differenzierbar");
                }
                RealSampler sampler = x -> evalRealDerivativeSample(f.args.get(0), local, v, x);
                double f0 = sampler.sample(x0);
                boolean jumpChecked = containsStepOrAbs(f.args.get(0));
                boolean isFlat = jumpChecked && checkDerivativeJumpOrKink(sampler, x0, f0, true);
                AstNode dNode = f.args.get(0).diff(v, radiansMode);
                if (dNode != null) {
                    ValErr ve = null;
                    try {
                        Map<String, Value> dLocal = new HashMap<>(local);
                        dLocal.put(v, Value.fromFloat(x0));
                        ve = evalAstWithCancelErr(dNode.simplify(), dLocal);
                    } catch (RuntimeException ignored) {
                        checkInterrupted();
                    }
                    if (ve != null && ve.val.c.isReal() && Double.isFinite(ve.val.c.re)) {
                        if (ve.cancelErr <= 5e-7 * Math.abs(ve.val.c.re)) {
                            return Value.fromFloat(ve.val.c.re);
                        }
                        throw new RuntimeException("Auslöschung zu stark für verlässliche Ableitung");
                    }
                }
                if (isFlat) {
                    return Value.fromFloat(0.0);
                }
                return Value.fromFloat(computeNumericalDerivative(sampler, x0, f0, jumpChecked));
            }
            if (fn.equals("det") && f.args.size() == 1) {
                Value exactDet = tryEvalExactDetAst(f.args.get(0), ov);
                if (exactDet != null) return exactDet;
                return Value.fromFloat(matrixDet(evalMatrixAst(f.args.get(0), ov)));
            }
            List<Value> argVals = new ArrayList<>(f.args.size());
            for (AstNode a : f.args) {
                argVals.add(evalAst(a, ov));
            }
            return applyFunctionValues(fn, argVals);
        }
        throw new IllegalArgumentException("Ungültiger AST-Knoten");
    }

    public boolean isRadiansMode() { return radiansMode; }
    public void setRadiansMode(boolean radians) { radiansMode = radians; }
    public boolean isModuloAlwaysPositive() { return moduloAlwaysPositive; }
    public void setModuloAlwaysPositive(boolean pos) { moduloAlwaysPositive = pos; }
    public double getLastAnswer() { return lastAns.c.re; }
    public Value getLastAns() { return lastAns; }
    public void setLastAns(Value val) { if (val != null) this.lastAns = val; }
    public double[][] getLastMatrixAns() { return cloneMatrix(lastMatrixAns); }
    public void setLastMatrixAns(double[][] mat) { this.lastMatrixAns = cloneMatrix(mat); }
    public double[][] getMatrixVariable(String name) {
        return name == null ? null : cloneMatrix(matrixVariables.get(name));
    }
    public void setMatrixVariable(String name, double[][] mat) {
        if (name == null) return;
        if (mat == null) {
            matrixVariables.remove(name);
        } else {
            matrixVariables.put(name, cloneMatrix(mat));
            variables.remove(name);
        }
    }
    public Map<String, double[][]> getMatrixVariables() {
        Map<String, double[][]> copy = new HashMap<>();
        for (String v : USER_VARIABLES) {
            double[][] mat = matrixVariables.get(v);
            if (mat != null) {
                copy.put(v, cloneMatrix(mat));
            }
        }
        return copy;
    }
    public List<String> getMatrixVariableNames() {
        List<String> names = new ArrayList<>();
        for (String v : USER_VARIABLES) {
            if (matrixVariables.containsKey(v)) {
                names.add(v);
            }
        }
        return names;
    }
    public void copyEvaluatedStateFrom(CalculatorEngine source) {
        if (source == null) return;
        variables.clear();
        variables.putAll(source.variables);
        matrixVariables.clear();
        for (Map.Entry<String, double[][]> entry : source.matrixVariables.entrySet()) {
            matrixVariables.put(entry.getKey(), cloneMatrix(entry.getValue()));
        }
        lastAns = source.lastAns;
        lastMatrixAns = cloneMatrix(source.lastMatrixAns);
        radiansMode = source.radiansMode;
        moduloAlwaysPositive = source.moduloAlwaysPositive;
        lastIntegrationReport = source.lastIntegrationReport;
    }
    public IntegrationReport getLastIntegrationReport() { return lastIntegrationReport; }

    public void setVariable(String name, double val) {
        variables.put(name, Value.fromFloat(val));
        matrixVariables.remove(name);
    }

    public void unsetVariable(String name) {
        if (name != null) {
            variables.remove(name);
            matrixVariables.remove(name);
        }
    }

    public void setUserVariable(String name, Value val) {
        if (name != null && val != null) {
            variables.put(name, val);
            matrixVariables.remove(name);
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

    public String serializeMatrix(double[][] mat) {
        if (mat == null || mat.length == 0 || mat[0] == null || mat[0].length == 0) {
            return null;
        }
        int cols = mat[0].length;
        StringBuilder sb = new StringBuilder("M:");
        for (int r = 0; r < mat.length; r++) {
            if (mat[r] == null || mat[r].length != cols) {
                return null;
            }
            if (r > 0) sb.append(';');
            for (int c = 0; c < cols; c++) {
                double v = mat[r][c];
                if (!Double.isFinite(v)) {
                    return null;
                }
                if (c > 0) sb.append(',');
                sb.append(Double.toString(v));
            }
        }
        return sb.toString();
    }

    public double[][] deserializeMatrix(String s) {
        if (s == null || !s.startsWith("M:")) {
            return null;
        }
        try {
            String body = s.substring(2);
            if (body.isEmpty()) return null;
            String[] rowParts = body.split(";", -1);
            if (rowParts.length == 0) return null;
            double[][] mat = new double[rowParts.length][];
            int expectedCols = -1;
            for (int r = 0; r < rowParts.length; r++) {
                String[] cellParts = rowParts[r].split(",", -1);
                if (cellParts.length == 0) return null;
                if (expectedCols < 0) expectedCols = cellParts.length;
                else if (cellParts.length != expectedCols) return null;
                mat[r] = new double[expectedCols];
                for (int c = 0; c < expectedCols; c++) {
                    double v = Double.parseDouble(cellParts[c]);
                    if (!Double.isFinite(v)) return null;
                    mat[r][c] = v;
                }
            }
            return mat;
        } catch (Exception ignored) {
            return null;
        }
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

    public String formatVariable(String name, boolean exact) {
        Value v = variables.get(name);
        if (v == null) return null;
        if (exact && v.rat != null) {
            if (v.rat.den.equals(BigInteger.ONE)) {
                return v.rat.num.toString();
            }
            return v.rat.num.toString() + "/" + v.rat.den.toString();
        }
        return formatDecimalVal(v);
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
        variables.put("μ₀", Value.fromFloat(PERMEABILITY_VACUUM));
        variables.put("eps0", Value.fromFloat(PERMITTIVITY_VACUUM));
        variables.put("ε₀", Value.fromFloat(PERMITTIVITY_VACUUM));
        variables.put("G", Value.fromFloat(GRAVITATION_CONST));
        variables.put("G_const", Value.fromFloat(GRAVITATION_CONST));
        variables.put("h", Value.fromFloat(PLANCK_CONSTANT));
        variables.put("h_planck", Value.fromFloat(PLANCK_CONSTANT));
        variables.put("alpha_fs", Value.fromFloat(FINE_STRUCTURE_CONST));
        variables.put("α", Value.fromFloat(FINE_STRUCTURE_CONST));
        variables.put("k_B", Value.fromFloat(BOLTZMANN_CONST));
        variables.put("g", Value.fromFloat(GRAVITATIONAL_ACCELERATION));
        variables.put("g_earth", Value.fromFloat(GRAVITATIONAL_ACCELERATION));
        variables.put("m_P", Value.fromFloat(PLANCK_MASS));
        variables.put("t_P", Value.fromFloat(PLANCK_TIME));
        variables.put("l_P", Value.fromFloat(PLANCK_LENGTH));

        variables.put("e_charge", Value.fromFloat(ELECTRON_CHARGE));
        variables.put("Z0", Value.fromFloat(IMPEDANCE_VACUUM));
        variables.put("Z₀", Value.fromFloat(IMPEDANCE_VACUUM));
        variables.put("Phi0", Value.fromFloat(MAGNETIC_FLUX_QUANTUM));
        variables.put("Φ₀", Value.fromFloat(MAGNETIC_FLUX_QUANTUM));
        variables.put("K_J", Value.fromFloat(JOSEPHSON_CONST));
        variables.put("R_K", Value.fromFloat(VON_KLITZING_CONST));
        variables.put("G0", Value.fromFloat(CONDUCTANCE_QUANTUM));
        variables.put("G0_cond", Value.fromFloat(CONDUCTANCE_QUANTUM));
        variables.put("G₀", Value.fromFloat(CONDUCTANCE_QUANTUM));
        variables.put("sigma", Value.fromFloat(STEFAN_BOLTZMANN_CONST));
        variables.put("sigma_sb", Value.fromFloat(STEFAN_BOLTZMANN_CONST));
        variables.put("σ", Value.fromFloat(STEFAN_BOLTZMANN_CONST));
        variables.put("R_inf", Value.fromFloat(RYDBERG_CONST));
        variables.put("R_∞", Value.fromFloat(RYDBERG_CONST));
        variables.put("E_h", Value.fromFloat(HARTREE_ENERGY));
        variables.put("E_H", Value.fromFloat(HARTREE_ENERGY));
        variables.put("mu_B", Value.fromFloat(BOHR_MAGNETON));
        variables.put("μ_B", Value.fromFloat(BOHR_MAGNETON));
        variables.put("mu_e", Value.fromFloat(ELECTRON_MAGNETIC_MOMENT));
        variables.put("μ_e", Value.fromFloat(ELECTRON_MAGNETIC_MOMENT));

        variables.put("N_A", Value.fromFloat(AVOGADRO_CONST));
        variables.put("m_u", Value.fromFloat(ATOMIC_MASS_CONST));
        variables.put("u", Value.fromFloat(ATOMIC_MASS_CONST));
        variables.put("u_amu", Value.fromFloat(ATOMIC_MASS_CONST));
        variables.put("F", Value.fromFloat(FARADAY_CONST));
        variables.put("F_faraday", Value.fromFloat(FARADAY_CONST));
        variables.put("R", Value.fromFloat(MOLAR_GAS_CONSTANT));
        variables.put("R_gas", Value.fromFloat(MOLAR_GAS_CONSTANT));
        variables.put("m_e", Value.fromFloat(MASS_ELECTRON));
        variables.put("m_p", Value.fromFloat(MASS_PROTON));
        variables.put("m_n", Value.fromFloat(MASS_NEUTRON));
        variables.put("m_mu", Value.fromFloat(MASS_MUON));
        variables.put("r_e", Value.fromFloat(CLASSICAL_ELECTRON_RADIUS));
        variables.put("lambda_C", Value.fromFloat(COMPTON_WAVELENGTH));
        variables.put("a_0", Value.fromFloat(BOHR_RADIUS));
        variables.put("mu_N", Value.fromFloat(NUCLEAR_MAGNETON));
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
            double n = num.doubleValue();
            double d = den.doubleValue();
            if (Double.isFinite(n) && Double.isFinite(d) && d != 0.0
                    && (n != 0.0 || num.signum() == 0)) {
                return n / d;
            }
            try {
                return new java.math.BigDecimal(num)
                        .divide(new java.math.BigDecimal(den), java.math.MathContext.DECIMAL128)
                        .doubleValue();
            } catch (ArithmeticException e) {
                return n / d;
            }
        }
    }

    // --- Complex Numbers ---
    static final class Complex {
        final double re;
        final double im;

        Complex(double re, double im) {
            this.re = (re == 0.0) ? 0.0 : re;
            this.im = (im == 0.0) ? 0.0 : im;
        }

        static Complex real(double r) { return new Complex(r, 0.0); }
        static Complex i() { return new Complex(0.0, 1.0); }
        boolean isReal() { return Math.abs(im) < 1e-12; }
        double abs() { return Math.hypot(re, im); }
        double arg() {
            double cleanIm = Math.abs(im) == 0.0 ? 0.0 : im;
            double cleanRe = re == 0.0 ? 0.0 : re;
            return Math.atan2(cleanIm, cleanRe);
        }

        Complex negate() { return new Complex(-re, -im); }
        Complex add(Complex o) { return new Complex(re + o.re, im + o.im); }
        Complex sub(Complex o) { return new Complex(re - o.re, im - o.im); }
        Complex mul(Complex o) { return new Complex(re * o.re - im * o.im, re * o.im + im * o.re); }
        Complex div(Complex o) {
            if (o.re == 0.0 && o.im == 0.0) throw new ArithmeticException("Division by zero");
            if (Double.isFinite(re) && Double.isFinite(im)
                    && (Double.isInfinite(o.re) || Double.isInfinite(o.im))
                    && !Double.isNaN(o.re) && !Double.isNaN(o.im)) {
                return real(0.0);
            }
            if (Math.abs(o.re) >= Math.abs(o.im)) {
                double r = o.im / o.re;
                double den = o.re + o.im * r;
                return new Complex((re + im * r) / den, (im - re * r) / den);
            } else {
                double r = o.re / o.im;
                double den = o.re * r + o.im;
                return new Complex((re * r + im) / den, (im * r - re) / den);
            }
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
            if (im == 0.0) return real(Math.exp(re));
            double r = Math.exp(re);
            return new Complex(r * Math.cos(im), r * Math.sin(im));
        }
        Complex ln() {
            return new Complex(Math.log(abs()), arg());
        }
        Complex pow(Complex exp) {
            if (isReal() && exp.isReal() && (re >= 0.0 || (Double.isFinite(exp.re) && Math.rint(exp.re) == exp.re))) {
                return real(Math.pow(re, exp.re));
            }
            if (abs() == 0.0) return real(0.0);
            return ln().mul(exp).exp();
        }
        Complex sin() {
            if (im == 0.0) return real(Math.sin(re));
            return new Complex(Math.sin(re) * Math.cosh(im), Math.cos(re) * Math.sinh(im));
        }
        Complex cos() {
            if (im == 0.0) return real(Math.cos(re));
            return new Complex(Math.cos(re) * Math.cosh(im), -Math.sin(re) * Math.sinh(im));
        }
        Complex tan() {
            return sin().div(cos());
        }
        Complex sinh() {
            if (im == 0.0) return real(Math.sinh(re));
            return new Complex(Math.sinh(re) * Math.cos(im), Math.cosh(re) * Math.sin(im));
        }
        Complex cosh() {
            if (im == 0.0) return real(Math.cosh(re));
            return new Complex(Math.cosh(re) * Math.cos(im), Math.sinh(re) * Math.sin(im));
        }
        Complex tanh() {
            if (Math.abs(re) > 20.0) {
                return Complex.real(Math.copySign(1.0, re));
            }
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

        private static Value fromExactRat(ExactRat r) {
            double d = r.toDouble();
            if (!r.den.equals(BigInteger.ONE) && (r.den.bitLength() > 256 || r.num.bitLength() > 1024)) {
                return new Value(Complex.real(d), null);
            }
            return new Value(Complex.real(d), r);
        }

        Value add(Value o) {
            ExactRat r = (rat != null && o.rat != null) ? rat.add(o.rat) : null;
            if (r != null && c.isReal() && o.c.isReal()) {
                return fromExactRat(r);
            }
            return new Value(c.add(o.c), null);
        }

        Value sub(Value o) {
            ExactRat r = (rat != null && o.rat != null) ? rat.sub(o.rat) : null;
            if (r != null && c.isReal() && o.c.isReal()) {
                return fromExactRat(r);
            }
            return new Value(c.sub(o.c), null);
        }

        Value mul(Value o) {
            ExactRat r = (rat != null && o.rat != null) ? rat.mul(o.rat) : null;
            if (r != null && c.isReal() && o.c.isReal()) {
                return fromExactRat(r);
            }
            return new Value(c.mul(o.c), null);
        }

        Value div(Value o) {
            if (o.c.abs() == 0.0 || (o.c.re == 0.0 && o.c.im == 0.0)) {
                throw new ArithmeticException("Division by zero");
            }
            ExactRat r = (rat != null && o.rat != null) ? rat.div(o.rat) : null;
            if (r != null && c.isReal() && o.c.isReal()) {
                return fromExactRat(r);
            }
            Complex res = c.div(o.c);
            if (!Double.isFinite(res.re) || !Double.isFinite(res.im)) {
                throw new ArithmeticException("Division by zero");
            }
            return new Value(res, null);
        }

        Value pow(Value exp) {
            if (rat != null && exp.rat != null && exp.rat.den.equals(BigInteger.ONE)) {
                Integer pObj = null;
                try {
                    pObj = exp.rat.num.intValueExact();
                } catch (ArithmeticException ignored) {}
                if (pObj != null) {
                    int p = pObj;
                    int ap = Math.abs(p);
                    if (p < 0 && rat.num.signum() == 0) {
                        throw new ArithmeticException("Division by zero");
                    }
                    int maxBits = rat.den.equals(BigInteger.ONE) ? 16384 : 4096;
                    long estBits = (long) Math.max(rat.num.bitLength(), rat.den.bitLength()) * (long) ap;
                    if (ap <= 1000 && estBits <= maxBits) {
                        if (p >= 0) {
                            ExactRat r = new ExactRat(rat.num.pow(p), rat.den.pow(p));
                            return fromExactRat(r);
                        } else {
                            ExactRat r = new ExactRat(rat.den.pow(ap), rat.num.pow(ap));
                            return fromExactRat(r);
                        }
                    }
                }
            }
            if (c.abs() == 0.0 && exp.c.re < 0.0) {
                throw new ArithmeticException("Division by zero");
            }
            return new Value(c.pow(exp.c), null);
        }

        Value negate() {
            return new Value(c.negate(), rat != null ? new ExactRat(rat.num.negate(), rat.den) : null);
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
    private static final int MAX_NESTING_DEPTH = 64;
    private static final ThreadLocal<Integer> EVAL_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static void checkBracketDepth(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char ch = expr.charAt(i);
            if (ch == '(' || ch == '[') {
                depth++;
                if (depth > MAX_NESTING_DEPTH) {
                    throw new RuntimeException("Maximale Verschachtelungstiefe überschritten");
                }
            } else if (ch == ')' || ch == ']') {
                depth = Math.max(0, depth - 1);
            }
        }
    }

    private Value evalExpression(String expr, Map<String, Value> overrides) {
        int d = EVAL_DEPTH.get();
        if (d >= MAX_NESTING_DEPTH) {
            throw new RuntimeException("Maximale Verschachtelungstiefe überschritten");
        }
        EVAL_DEPTH.set(d + 1);
        try {
            return new ExprEvaluator(normalizeEvaluation(expr), overrides).parse();
        } finally {
            EVAL_DEPTH.set(d);
        }
    }

    private static String normalizeSymbols(String expression) {
        return expression.replace("×", "*").replace("·", "*").replace("÷", "/")
                .replace("π", "pi").replace("−", "-")
                .replace("²", "^2").replace("³", "^3").replace("⁻¹", "^(-1)");
    }

    private static String normalizeEvaluation(String expression) {
        String s = normalizeSymbols(expression);
        // Normalize scientific notation with optional spaces, e.g. "1e - 3" -> "1e-3", "2.5e + 4" -> "2.5e+4", "1e 3" -> "1e3"
        s = s.replaceAll("(\\d+(?:\\.\\d+)?)[eE]\\s*([+\\-])\\s*(\\d+)", "$1e$2$3");
        return s.replaceAll("(\\d+(?:\\.\\d+)?)[eE]\\s+(\\d+)", "$1e$2");
    }

    Value evalModulo(Value v0, Value v1) {
        if ((v0.rat == null || !v0.rat.den.equals(BigInteger.ONE))
                || (v1.rat == null || !v1.rat.den.equals(BigInteger.ONE))) {
            if (!v0.c.isReal() || !v1.c.isReal()
                    || !Double.isFinite(v0.c.re) || !Double.isFinite(v1.c.re)) {
                throw new RuntimeException("Modulo erfordert endliche reelle Zahlen");
            }
            if (v1.c.re == 0.0) throw new RuntimeException("Modulo durch Null");
            double remainder = v0.c.re % v1.c.re;
            return Value.fromFloat(moduloAlwaysPositive && remainder < 0
                    ? remainder + Math.abs(v1.c.re) : remainder);
        }
        BigInteger a = v0.rat.num;
        BigInteger b = v1.rat.num;
        if (b.equals(BigInteger.ZERO)) throw new RuntimeException("Modulo durch Null");
        BigInteger rem = a.remainder(b);
        if (moduloAlwaysPositive && rem.signum() < 0) {
            rem = rem.add(b.abs());
        }
        return Value.fromInt(rem);
    }

    private static boolean isInfixModulo(String source, int position) {
        if (!source.startsWith("mod", position) || position + 3 >= source.length()) return false;
        char next = source.charAt(position + 3);
        return next == ' ' || next == '(' || Character.isDigit(next) || next == '-' || next == '+';
    }

    private class ExprEvaluator {
        final String s;
        final Map<String, Value> overrides;
        int pos = -1, ch;
        int depth = 0;

        ExprEvaluator(String s, Map<String, Value> overrides) {
            this.s = s;
            this.overrides = overrides;
        }

        void enterDepth() {
            if (++depth > MAX_NESTING_DEPTH) {
                throw new RuntimeException("Maximale Verschachtelungstiefe überschritten");
            }
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
                if (isInfixModulo(s, pos)) {
                    pos += 2;
                    nextChar();
                    Value right = parseUnary();
                    x = evalModulo(x, right);
                    continue;
                }

                if (eat('*')) {
                    x = x.mul(parseUnary());
                } else if (eat('/')) {
                    Value right = parseUnary();
                    x = x.div(right);
                } else if (ch == '(' || ch == '√' || Character.isLetter(ch)) {
                    // Implicit multiplication
                    Value right = parseUnary();
                    x = x.mul(right);
                } else {
                    return x;
                }
            }
        }

        Value parseUnary() {
            if (eat('+')) {
                enterDepth();
                try { return parseUnary(); } finally { depth--; }
            }
            if (eat('-')) {
                enterDepth();
                try {
                    Value v = parseUnary();
                    return new Value(new Complex(-v.c.re, -v.c.im),
                            v.rat != null ? new ExactRat(v.rat.num.negate(), v.rat.den) : null);
                } finally {
                    depth--;
                }
            }
            if (eat('√')) {
                enterDepth();
                try {
                    Value v = parseUnary();
                    return v.sqrt();
                } finally {
                    depth--;
                }
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
                    val = evalFactorial(val);
                } else if (eat('%')) {
                    val = evalPercent(val);
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
                enterDepth();
                try {
                    Value x = parseExpression();
                    eat(')');
                    return x;
                } finally {
                    depth--;
                }
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
                if ((ch == 'e' || ch == 'E') && pos + 1 < s.length()) {
                    int lookahead = pos + 1;
                    while (lookahead < s.length() && s.charAt(lookahead) == ' ') lookahead++;
                    if (lookahead < s.length()) {
                        char next = s.charAt(lookahead);
                        if (Character.isDigit(next) || next == '+' || next == '-') {
                            hasExp = true;
                            pos = lookahead;
                            ch = s.charAt(pos);
                            if (ch == '+' || ch == '-') nextChar();
                            while (ch >= '0' && ch <= '9') nextChar();
                        }
                    }
                }
                String numStr = s.substring(startPos, pos).replaceAll("\\s+", "");
                if (!hasDot && !hasExp) {
                    try {
                        BigInteger bi = new BigInteger(numStr);
                        return Value.fromInt(bi);
                    } catch (Exception ignored) {}
                }
                double d = Double.parseDouble(numStr);
                ExactRat sciRat = parseScientificExactRat(numStr);
                if (sciRat != null) {
                    Value sciVal = valueFromExactRat(sciRat);
                    if (sciVal != null) return sciVal;
                }
                return Value.fromFloat(d);
            }

            // Identifier or Function call
            if (Character.isLetter(ch)) {
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
                        } else if (ch == '[') {
                            depth++;
                            sb.append((char) ch);
                        } else if (ch == ']') {
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
        if (name.equals("pi") || name.equals("π")) return Value.fromFloat(Math.PI);
        if (name.equals("e")) return Value.fromFloat(Math.E);
        if (name.equals("ans") || name.equals("ANS")) return lastAns;
        if (variables.containsKey(name)) return variables.get(name);
        throw new RuntimeException("Unknown var: " + name);
    }

    private Value evalFactorial(Value val) {
        if (!val.c.isReal() || val.c.re < 0.0 || Math.abs(val.c.re - Math.round(val.c.re)) > 1e-11) {
            throw new RuntimeException("Fakultät ist nur für nicht-negative ganze Zahlen definiert");
        }
        if (val.c.re > 170.0) {
            throw new RuntimeException("Fakultät übersteigt Rechenbereich");
        }
        long k = Math.round(val.c.re);
        BigInteger prod = BigInteger.ONE;
        for (long i = 2; i <= k; i++) prod = prod.multiply(BigInteger.valueOf(i));
        return Value.fromInt(prod);
    }

    private Value evalPercent(Value val) {
        return val.mul(Value.fromRat(1, 100));
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
        boolean refinable;
        Subinterval(double a, double b, double val5, double err) {
            this.a = a;
            this.b = b;
            this.val5 = val5;
            this.err = err;
            double mid = a + (b - a) * 0.5;
            this.refinable = (mid != a && mid != b);
        }
    }

    private static Subinterval evalSubinterval(IntegrandFunc f, double ia, double ib) {
        checkInterrupted();
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
        return adaptiveGaussIntegrate(f, a, b, initialSubintervals, epsAbs, epsRel, null);
    }

    private static IntegrationReport adaptiveGaussIntegrate(IntegrandFunc f, double a, double b, int initialSubintervals, double epsAbs, double epsRel, List<Double> extraAnchors) {
        return adaptiveGaussIntegrate(f, a, b, initialSubintervals, epsAbs, epsRel, extraAnchors, false);
    }

    private static final class AnchorPoint {
        final double x;
        final boolean critical;
        AnchorPoint(double x, boolean critical) {
            this.x = x;
            this.critical = critical;
        }
    }

    private static IntegrationReport adaptiveGaussIntegrate(IntegrandFunc f, double a, double b, int initialSubintervals, double epsAbs, double epsRel, List<Double> extraAnchors, boolean anchorsTruncated) {
        if (a == b) {
            return new IntegrationReport(0.0, 0.0, true, 0, 0, "Konvergiert: Fehlerabschätzung 0.00e+00 <= Toleranz 1.00e-10 (0 Auswertungen)");
        }
        List<Subinterval> intervals = new ArrayList<>(initialSubintervals * 2 + 128);
        double totalVal = 0.0;
        double totalErr = 0.0;
        double span = Math.abs(b - a);
        int evals = 0;

        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        double step = (hi - lo) / (double) initialSubintervals;
        java.util.ArrayList<Double> uniqueExtra = new java.util.ArrayList<>();
        if (extraAnchors != null) {
            for (double ea : extraAnchors) {
                if (Double.isFinite(ea) && ea > lo && ea < hi) {
                    uniqueExtra.add(ea);
                }
            }
            java.util.Collections.sort(uniqueExtra);
            int write = 0;
            for (int i = 0; i < uniqueExtra.size(); i++) {
                double val = uniqueExtra.get(i);
                if (write == 0 || Math.abs(val - uniqueExtra.get(write - 1)) > 1e-12 * Math.max(1.0, Math.abs(val))) {
                    uniqueExtra.set(write++, val);
                }
            }
            if (write < uniqueExtra.size()) {
                uniqueExtra.subList(write, uniqueExtra.size()).clear();
            }
        }
        boolean manyExtra = uniqueExtra.size() > 64;
        java.util.ArrayList<AnchorPoint> pts = new java.util.ArrayList<>(initialSubintervals + uniqueExtra.size() + 128);
        for (int i = 0; i <= initialSubintervals; i++) {
            double p = i == initialSubintervals ? hi : (lo + i * step);
            pts.add(new AnchorPoint(p, i == 0 || i == initialSubintervals));
        }

        if (initialSubintervals >= 500) {
            double mid = 0.5 * (lo + hi);
            java.util.ArrayList<Double> offsetAnchors = new java.util.ArrayList<>();
            offsetAnchors.add(lo);
            offsetAnchors.add(mid);
            offsetAnchors.add(hi);
            pts.add(new AnchorPoint(mid, true));
            if (lo < 0.0 && hi > 0.0) {
                offsetAnchors.add(0.0);
                pts.add(new AnchorPoint(0.0, true));
            }
            for (double ea : uniqueExtra) {
                pts.add(new AnchorPoint(ea, true));
                if (!manyExtra) offsetAnchors.add(ea);
            }
            double[] offsets = new double[]{
                    1e-7, 5e-7, 1e-6, 3e-6, 1e-5, 3e-5, 1e-4, 3e-4, 1e-3, 3e-3,
                    1e-2, 3e-2, 1e-1, 1.0, 4.0, 16.0, 64.0, 256.0, 1024.0
            };
            for (double anchor : offsetAnchors) {
                for (double off : offsets) {
                    if (off >= span * 0.25) break;
                    if (anchor - off > lo) pts.add(new AnchorPoint(anchor - off, false));
                    if (anchor + off < hi) pts.add(new AnchorPoint(anchor + off, false));
                }
            }
        } else {
            java.util.ArrayList<Double> offsetAnchors = new java.util.ArrayList<>();
            if (lo < 0.0 && hi > 0.0) {
                offsetAnchors.add(0.0);
                pts.add(new AnchorPoint(0.0, true));
            }
            for (double ea : uniqueExtra) {
                pts.add(new AnchorPoint(ea, true));
                if (!manyExtra) offsetAnchors.add(ea);
            }
            double[] nestedOffsets = new double[]{1e-4, 5e-4, 2e-3, 5e-3, 1e-2, 2e-2, 5e-2, 0.25, 1.0, 4.0};
            for (double anchor : offsetAnchors) {
                for (double off : nestedOffsets) {
                    if (off >= span * 0.25) break;
                    if (anchor - off > lo) pts.add(new AnchorPoint(anchor - off, false));
                    if (anchor + off < hi) pts.add(new AnchorPoint(anchor + off, false));
                }
            }
        }

        pts.sort((x, y) -> {
            int cmp = Double.compare(x.x, y.x);
            if (cmp != 0) return cmp;
            return Boolean.compare(y.critical, x.critical);
        });
        double[] dedup = new double[pts.size()];
        boolean[] dedupCrit = new boolean[pts.size()];
        int count = 0;
        for (AnchorPoint ap : pts) {
            double p = ap.x;
            boolean crit = ap.critical;
            if (count > 0) {
                double lastP = dedup[count - 1];
                boolean lastCrit = dedupCrit[count - 1];
                if (p == lastP) {
                    if (crit) dedupCrit[count - 1] = true;
                    continue;
                }
                if ((p - lastP) <= 1e-12 * Math.max(1.0, Math.abs(p))) {
                    if (crit && lastCrit) {
                        dedup[count] = p;
                        dedupCrit[count] = true;
                        count++;
                        continue;
                    }
                    if (p == hi || (crit && !lastCrit)) {
                        dedup[count - 1] = p;
                        dedupCrit[count - 1] = crit;
                    }
                    continue;
                }
            }
            dedup[count] = p;
            dedupCrit[count] = crit;
            count++;
        }
        if (a < b) {
            for (int i = 0; i + 1 < count; i++) {
                Subinterval sub = evalSubinterval(f, dedup[i], dedup[i + 1]);
                totalVal += sub.val5;
                totalErr += sub.err;
                intervals.add(sub);
            }
        } else {
            for (int i = count - 1; i > 0; i--) {
                Subinterval sub = evalSubinterval(f, dedup[i], dedup[i - 1]);
                totalVal += sub.val5;
                totalErr += sub.err;
                intervals.add(sub);
            }
        }
        evals = Math.max(0, (count - 1) * 7);

        int maxEvals = 100000;
        double targetTol = Math.max(epsAbs, epsRel * Math.abs(totalVal));

        while (totalErr > targetTol && evals < maxEvals) {
            checkInterrupted();
            int worstIdx = -1;
            double worstErr = -1.0;
            for (int i = 0; i < intervals.size(); i++) {
                Subinterval iv = intervals.get(i);
                if (iv.refinable && iv.err > worstErr) {
                    worstErr = iv.err;
                    worstIdx = i;
                }
            }

            if (worstIdx < 0 || worstErr <= 1e-15) {
                break;
            }

            Subinterval curWorst = intervals.get(worstIdx);
            double mid = (curWorst.a + curWorst.b) * 0.5;
            if (mid == curWorst.a || mid == curWorst.b) {
                curWorst.refinable = false;
                continue;
            }

            Subinterval worst = intervals.remove(worstIdx);
            totalVal -= worst.val5;
            totalErr -= worst.err;

            Subinterval left = evalSubinterval(f, worst.a, mid);
            Subinterval right = evalSubinterval(f, mid, worst.b);

            totalVal += left.val5 + right.val5;
            totalErr += left.err + right.err;
            targetTol = Math.max(epsAbs, epsRel * Math.abs(totalVal));

            intervals.add(left);
            intervals.add(right);
            evals += 14;

            if (totalErr <= targetTol) {
                break;
            }
        }

        boolean converged = totalErr <= targetTol && !anchorsTruncated;
        String message = anchorsTruncated
                ? String.format(Locale.US, "Konvergenz nicht erreicht: Zu viele Unstetigkeits-/Knickstellen im Intervall (Fehlerabschätzung %.2e)", totalErr)
                : (converged
                ? String.format(Locale.US, "Konvergiert: Fehlerabschätzung %.2e <= Toleranz %.2e (%d Auswertungen)", totalErr, targetTol, evals)
                : String.format(Locale.US, "Konvergenz nicht erreicht: Fehlerabschätzung %.2e > Toleranz %.2e (Budget erschöpft: %d Auswertungen)", totalErr, targetTol, evals));

        return new IntegrationReport(totalVal, totalErr, converged, intervals.size(), evals, message);
    }

    private static String calculusVariable(String text) {
        String name = text.trim();
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) {
            throw new RuntimeException("Bitte eine Variable angeben");
        }
        return name;
    }

    private static boolean isEntireAntiderivative(AstNode node, String var) {
        if (node == null) return false;
        if (node instanceof AstNum || node instanceof AstVar) return true;
        if (node instanceof AstNeg) return isEntireAntiderivative(((AstNeg) node).inner, var);
        if (node instanceof AstAdd) {
            AstAdd add = (AstAdd) node;
            return isEntireAntiderivative(add.left, var) && isEntireAntiderivative(add.right, var);
        }
        if (node instanceof AstSub) {
            AstSub sub = (AstSub) node;
            return isEntireAntiderivative(sub.left, var) && isEntireAntiderivative(sub.right, var);
        }
        if (node instanceof AstMul) {
            AstMul mul = (AstMul) node;
            return isEntireAntiderivative(mul.left, var) && isEntireAntiderivative(mul.right, var);
        }
        if (node instanceof AstDiv) {
            AstDiv div = (AstDiv) node;
            return !div.right.containsVar(var)
                    && isEntireAntiderivative(div.left, var)
                    && isEntireAntiderivative(div.right, var);
        }
        if (node instanceof AstPow) {
            AstPow pow = (AstPow) node;
            if (pow.exp.containsVar(var) || !isEntireAntiderivative(pow.exp, var)) {
                return false;
            }
            if (!pow.base.containsVar(var)) {
                return isEntireAntiderivative(pow.base, var);
            }
            Value ev = pow.exp.constValue();
            if (ev == null || ev.rat == null || !ev.rat.den.equals(BigInteger.ONE) || ev.rat.num.signum() <= 0) {
                return false;
            }
            return isEntireAntiderivative(pow.base, var);
        }
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            if (f.args.size() != 1) return false;
            String fn = f.name;
            if (!fn.equals("sin") && !fn.equals("cos") && !fn.equals("sinh") && !fn.equals("cosh")
                    && !fn.equals("exp") && !fn.equals("atan") && !fn.equals("arctan")) {
                return false;
            }
            return isEntireAntiderivative(f.args.get(0), var);
        }
        return false;
    }

    private Double evalAstAtPoint(AstNode target, String var, double x, Map<String, Value> ov) {
        try {
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            local.put(var, Value.fromFloat(x));
            Value val = evalAst(target, local);
            return (val.c.isReal() && Double.isFinite(val.c.re)) ? val.c.re : null;
        } catch (RuntimeException ignored) {
            checkInterrupted();
            return null;
        }
    }

    private boolean isLinOrULnPair(AstNode a, AstNode b, String var, double lo, double hi, Map<String, Value> ov) {
        if (a instanceof AstFunc) {
            AstFunc f = (AstFunc) a;
            if ("ln".equals(f.name) && f.args.size() == 1 && AstNode.matchLinear(b, var) != null) {
                AstNode lnArg = f.args.get(0);
                if (lnArg instanceof AstFunc) {
                    AstFunc absF = (AstFunc) lnArg;
                    if ("abs".equals(absF.name) && absF.args.size() == 1 && absF.args.get(0).equals(b)) {
                        return isAntiderivativeValidOn(b, var, lo, hi, ov);
                    }
                }
                if (lnArg.equals(b)) {
                    Double vLo = evalAstAtPoint(b, var, lo, ov);
                    Double vHi = evalAstAtPoint(b, var, hi, ov);
                    return vLo != null && vHi != null && vLo >= 0.0 && vHi >= 0.0;
                }
            }
        }
        return false;
    }

    private Value evalAntiderivativeEndpoint(AstNode node, Map<String, Value> ov) {
        if (node instanceof AstNeg) {
            return evalAntiderivativeEndpoint(((AstNeg) node).inner, ov).negate();
        }
        if (node instanceof AstAdd) {
            AstAdd add = (AstAdd) node;
            return evalAntiderivativeEndpoint(add.left, ov).add(evalAntiderivativeEndpoint(add.right, ov));
        }
        if (node instanceof AstSub) {
            AstSub sub = (AstSub) node;
            return evalAntiderivativeEndpoint(sub.left, ov).sub(evalAntiderivativeEndpoint(sub.right, ov));
        }
        if (node instanceof AstMul) {
            AstMul mul = (AstMul) node;
            Value va = evalAntiderivativeEndpoint(mul.left, ov);
            Value vb = evalAntiderivativeEndpoint(mul.right, ov);
            boolean aIsLn = (mul.left instanceof AstFunc) && "ln".equals(((AstFunc) mul.left).name);
            boolean bIsLn = (mul.right instanceof AstFunc) && "ln".equals(((AstFunc) mul.right).name);
            if ((va.c.isReal() && va.c.re == 0.0 && bIsLn && vb.c.isReal() && Double.isInfinite(vb.c.re))
                    || (vb.c.isReal() && vb.c.re == 0.0 && aIsLn && va.c.isReal() && Double.isInfinite(va.c.re))) {
                return Value.fromFloat(0.0);
            }
            return va.mul(vb);
        }
        if (node instanceof AstDiv) {
            AstDiv div = (AstDiv) node;
            return evalAntiderivativeEndpoint(div.left, ov).div(evalAntiderivativeEndpoint(div.right, ov));
        }
        return evalAst(node, ov);
    }

    private boolean isAntiderivativeValidOn(AstNode node, String var, double lo, double hi, Map<String, Value> ov) {
        if (node == null) return false;
        if (node instanceof AstNum || node instanceof AstVar) return true;
        if (node instanceof AstNeg) return isAntiderivativeValidOn(((AstNeg) node).inner, var, lo, hi, ov);
        if (node instanceof AstAdd) {
            AstAdd add = (AstAdd) node;
            return isAntiderivativeValidOn(add.left, var, lo, hi, ov)
                    && isAntiderivativeValidOn(add.right, var, lo, hi, ov);
        }
        if (node instanceof AstSub) {
            AstSub sub = (AstSub) node;
            return isAntiderivativeValidOn(sub.left, var, lo, hi, ov)
                    && isAntiderivativeValidOn(sub.right, var, lo, hi, ov);
        }
        if (node instanceof AstMul) {
            AstMul mul = (AstMul) node;
            if (isLinOrULnPair(mul.left, mul.right, var, lo, hi, ov)
                    || isLinOrULnPair(mul.right, mul.left, var, lo, hi, ov)) {
                return true;
            }
            return isAntiderivativeValidOn(mul.left, var, lo, hi, ov)
                    && isAntiderivativeValidOn(mul.right, var, lo, hi, ov);
        }
        if (node instanceof AstDiv) {
            AstDiv div = (AstDiv) node;
            return !div.right.containsVar(var)
                    && isAntiderivativeValidOn(div.left, var, lo, hi, ov)
                    && isAntiderivativeValidOn(div.right, var, lo, hi, ov);
        }
        if (node instanceof AstPow) {
            if (isEntireAntiderivative(node, var)) return true;
            AstPow pow = (AstPow) node;
            if (pow.exp.containsVar(var) || !isAntiderivativeValidOn(pow.exp, var, lo, hi, ov)) {
                return false;
            }
            if (!pow.base.containsVar(var)) {
                return isAntiderivativeValidOn(pow.base, var, lo, hi, ov);
            }
            try {
                Value ev = evalAst(pow.exp, ov);
                if (ev.c.isReal() && Double.isFinite(ev.c.re)) {
                    if (pow.base instanceof AstFunc) {
                        AstFunc bf = (AstFunc) pow.base;
                        if ("abs".equals(bf.name) && bf.args.size() == 1 && AstNode.matchLinear(bf.args.get(0), var) != null) {
                            if (ev.c.re > 0.0) {
                                return isAntiderivativeValidOn(bf.args.get(0), var, lo, hi, ov);
                            }
                            Double vLo = evalAstAtPoint(bf.args.get(0), var, lo, ov);
                            Double vHi = evalAstAtPoint(bf.args.get(0), var, hi, ov);
                            return vLo != null && vHi != null && vLo != 0.0 && vHi != 0.0
                                    && Math.signum(vLo) == Math.signum(vHi);
                        }
                    }
                    if (ev.c.re > 0.0 && AstNode.matchLinear(pow.base, var) != null) {
                        Double vLo = evalAstAtPoint(pow.base, var, lo, ov);
                        Double vHi = evalAstAtPoint(pow.base, var, hi, ov);
                        return vLo != null && vHi != null && vLo >= 0.0 && vHi >= 0.0;
                    }
                }
            } catch (RuntimeException ignored) {
                checkInterrupted();
            }
            return false;
        }
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            if (f.args.size() != 1) return false;
            String fn = f.name;
            if (fn.equals("sin") || fn.equals("cos") || fn.equals("sinh") || fn.equals("cosh")
                    || fn.equals("exp") || fn.equals("atan") || fn.equals("arctan")
                    || fn.equals("abs") || fn.equals("sign") || fn.equals("sgn")) {
                return isAntiderivativeValidOn(f.args.get(0), var, lo, hi, ov);
            }
            AstNode arg = f.args.get(0);
            if (fn.equals("ln")) {
                if (arg instanceof AstFunc) {
                    AstFunc af = (AstFunc) arg;
                    if ("abs".equals(af.name) && af.args.size() == 1 && AstNode.matchLinear(af.args.get(0), var) != null) {
                        Double vLo = evalAstAtPoint(af.args.get(0), var, lo, ov);
                        Double vHi = evalAstAtPoint(af.args.get(0), var, hi, ov);
                        return vLo != null && vHi != null && vLo != 0.0 && vHi != 0.0
                                && Math.signum(vLo) == Math.signum(vHi);
                    }
                } else if (AstNode.matchLinear(arg, var) != null) {
                    Double vLo = evalAstAtPoint(arg, var, lo, ov);
                    Double vHi = evalAstAtPoint(arg, var, hi, ov);
                    return vLo != null && vHi != null && vLo > 0.0 && vHi > 0.0;
                }
                return false;
            }
            if (fn.equals("asin") || fn.equals("arcsin") || fn.equals("acos") || fn.equals("arccos")) {
                if (AstNode.matchLinear(arg, var) != null) {
                    Double vLo = evalAstAtPoint(arg, var, lo, ov);
                    Double vHi = evalAstAtPoint(arg, var, hi, ov);
                    return vLo != null && vHi != null && Math.abs(vLo) <= 1.0 && Math.abs(vHi) <= 1.0;
                }
                return false;
            }
            if (fn.equals("sqrt")) {
                if (arg instanceof AstFunc) {
                    AstFunc af = (AstFunc) arg;
                    if ("abs".equals(af.name) && af.args.size() == 1 && AstNode.matchLinear(af.args.get(0), var) != null) {
                        return isAntiderivativeValidOn(af.args.get(0), var, lo, hi, ov);
                    }
                }
                if (AstNode.matchLinear(arg, var) != null) {
                    Double vLo = evalAstAtPoint(arg, var, lo, ov);
                    Double vHi = evalAstAtPoint(arg, var, hi, ov);
                    return vLo != null && vHi != null && vLo >= 0.0 && vHi >= 0.0;
                }
                AstNode.QuadraticMatch qm = AstNode.matchQuadratic(arg, var);
                if (qm != null) {
                    try {
                        Value qav = evalAst(qm.a, ov);
                        if (qav.c.isReal() && qav.c.re <= 0.0) {
                            Double vLo = evalAstAtPoint(arg, var, lo, ov);
                            Double vHi = evalAstAtPoint(arg, var, hi, ov);
                            return vLo != null && vHi != null && vLo >= 0.0 && vHi >= 0.0;
                        }
                    } catch (RuntimeException ignored) {
                        checkInterrupted();
                    }
                }
                return false;
            }
        }
        return false;
    }

    private Double evalAstWithDefaultZero(AstNode node, String var, Double xVal, Map<String, Value> ov) {
        try {
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            for (String uv : USER_VARIABLES) {
                if (!uv.equals(var) && !variables.containsKey(uv) && !local.containsKey(uv)) {
                    local.put(uv, Value.fromFloat(0.0));
                }
            }
            if (xVal != null) {
                local.put(var, Value.fromFloat(xVal));
            }
            Value val = evalAst(node, local);
            return (val.c.isReal() && Double.isFinite(val.c.re)) ? val.c.re : null;
        } catch (RuntimeException ignored) {
            checkInterrupted();
            return null;
        }
    }

    private static final int MAX_CRITICAL_POINTS = 2048;

    private static void pushCriticalPoint(List<Double> out, double root, double lo, double hi, boolean[] truncated) {
        if (Double.isFinite(root) && root > lo && root < hi) {
            if (out.size() >= MAX_CRITICAL_POINTS) {
                if (truncated != null) truncated[0] = true;
            } else {
                out.add(root);
            }
        }
    }

    private void pushPeriodicRoots(AstNode arg, String var, double baseSol, double period,
                                   double lo, double hi, Map<String, Value> ov, List<Double> out, boolean[] truncated, int depth) {
        if ((truncated != null && truncated[0]) || period <= 0.0 || !Double.isFinite(period)) return;
        AstNode.LinearMatch lin = AstNode.matchLinear(arg, var);
        if (lin != null) {
            Double a = evalAstWithDefaultZero(lin.a, var, null, ov);
            Double b = evalAstWithDefaultZero(lin.b, var, null, ov);
            if (a != null && b != null && a != 0.0) {
                double uLo = a * lo + b;
                double uHi = a * hi + b;
                long mMin = (long) Math.floor((Math.min(uLo, uHi) - baseSol) / period) - 1;
                long mMax = (long) Math.ceil((Math.max(uLo, uHi) - baseSol) / period) + 1;
                long maxEnd = Math.min(mMax, mMin + MAX_CRITICAL_POINTS + 8L);
                for (long m = mMin; m <= maxEnd; m++) {
                    double root = (baseSol + m * period - b) / a;
                    pushCriticalPoint(out, root, lo, hi, truncated);
                    if (truncated != null && truncated[0]) return;
                }
                if (maxEnd < mMax && truncated != null) {
                    truncated[0] = true;
                }
            }
            return;
        }
        if (depth >= 2) {
            if (truncated != null) truncated[0] = true;
            return;
        }
        ArrayList<Double> xs = new ArrayList<>(280);
        xs.add(lo);
        xs.add(hi);
        if (lo < 0.0 && hi > 0.0) xs.add(0.0);
        int gridN = 128;
        double span = hi - lo;
        for (int i = 1; i < gridN; i++) {
            xs.add(lo + span * ((double) i) / ((double) gridN));
        }
        ArrayList<Double> innerPts = new ArrayList<>();
        boolean[] innerTrunc = new boolean[]{false};
        collectCriticalPointsImpl(arg, var, lo, hi, ov, innerPts, innerTrunc, depth + 1);
        AstNode dArg = arg.diff(var, radiansMode);
        if (dArg != null) {
            collectCriticalPointsImpl(dArg, var, lo, hi, ov, innerPts, innerTrunc, depth + 1);
        }
        for (double p : innerPts) {
            if (Double.isFinite(p) && p > lo && p < hi) xs.add(p);
        }
        java.util.Collections.sort(xs);
        ArrayList<Double> uniqX = new ArrayList<>(xs.size());
        for (double p : xs) {
            if (uniqX.isEmpty() || Math.abs(p - uniqX.get(uniqX.size() - 1)) > 1e-13 * Math.max(1.0, Math.abs(p))) {
                uniqX.add(p);
            }
        }
        if (uniqX.size() < 2) return;
        double[] sampleX = new double[uniqX.size()];
        double[] sampleU = new double[uniqX.size()];
        for (int i = 0; i < uniqX.size(); i++) {
            checkInterrupted();
            double x = uniqX.get(i);
            Double u = evalAstWithDefaultZero(arg, var, x, ov);
            if (u == null && i == 0) {
                double eps = 1e-12 * Math.max(1e-12, uniqX.get(1) - x);
                u = evalAstWithDefaultZero(arg, var, x + eps, ov);
            } else if (u == null && i + 1 == uniqX.size()) {
                double eps = 1e-12 * Math.max(1e-12, x - uniqX.get(i - 1));
                u = evalAstWithDefaultZero(arg, var, x - eps, ov);
            }
            if (u == null) {
                if (truncated != null) truncated[0] = true;
                return;
            }
            sampleX[i] = x;
            sampleU[i] = u;
        }
        for (int i = 0; i + 1 < sampleX.length; i++) {
            double x0 = sampleX[i];
            double u0 = sampleU[i];
            double x1 = sampleX[i + 1];
            double u1 = sampleU[i + 1];
            double uMin = Math.min(u0, u1);
            double uMax = Math.max(u0, u1);
            double spanPeriods = (uMax - uMin) / period;
            if (!Double.isFinite(spanPeriods) || spanPeriods > 4096.0) {
                if (truncated != null) truncated[0] = true;
                return;
            }
            long mStart = (long) Math.floor((uMin - baseSol) / period) - 1;
            long mEnd = (long) Math.ceil((uMax - baseSol) / period) + 1;
            for (long m = mStart; m <= mEnd; m++) {
                double target = baseSol + m * period;
                double d0Orig = u0 - target;
                double d1Orig = u1 - target;
                if (d0Orig == 0.0) {
                    pushCriticalPoint(out, x0, lo, hi, truncated);
                } else if (d1Orig == 0.0) {
                    pushCriticalPoint(out, x1, lo, hi, truncated);
                } else if ((d0Orig < 0.0 && d1Orig > 0.0) || (d0Orig > 0.0 && d1Orig < 0.0)) {
                    double aB = x0;
                    double bB = x1;
                    double dA = d0Orig;
                    for (int it = 0; it < 60; it++) {
                        checkInterrupted();
                        double mid = 0.5 * (aB + bB);
                        if (mid == aB || mid == bB) break;
                        Double um = evalAstWithDefaultZero(arg, var, mid, ov);
                        if (um == null) {
                            if (truncated != null) truncated[0] = true;
                            return;
                        }
                        double dm = um - target;
                        if (dm == 0.0) {
                            aB = mid;
                            bB = mid;
                            break;
                        }
                        if ((dA < 0.0 && dm < 0.0) || (dA > 0.0 && dm > 0.0)) {
                            aB = mid;
                            dA = dm;
                        } else {
                            bB = mid;
                        }
                        if ((bB - aB) <= 1e-15 * Math.max(1.0, Math.abs(mid))) break;
                    }
                    pushCriticalPoint(out, 0.5 * (aB + bB), lo, hi, truncated);
                }
                if (truncated != null && truncated[0]) return;
            }
        }
    }

    private void collectCriticalPoints(AstNode node, String var, double lo, double hi, Map<String, Value> ov, List<Double> out, boolean[] truncated) {
        collectCriticalPointsImpl(node, var, lo, hi, ov, out, truncated, 0);
    }

    private void collectCriticalPointsImpl(AstNode node, String var, double lo, double hi, Map<String, Value> ov, List<Double> out, boolean[] truncated, int depth) {
        if (node == null || (truncated != null && truncated[0])) return;
        if (out.size() >= MAX_CRITICAL_POINTS) {
            if (node.containsVar(var) && truncated != null) truncated[0] = true;
            return;
        }
        if (!node.containsVar(var)) return;
        if (node instanceof AstFunc) {
            AstFunc fn = (AstFunc) node;
            String trimmedFn = fn.name.trim();
            if (trimmedFn.equals("mod") && fn.args.size() == 2) {
                if (!fn.args.get(1).containsVar(var)) {
                    Double mVal = evalAstWithDefaultZero(fn.args.get(1), var, null, ov);
                    if (mVal != null && Double.isFinite(mVal) && mVal != 0.0) {
                        pushPeriodicRoots(fn.args.get(0), var, 0.0, Math.abs(mVal), lo, hi, ov, out, truncated, depth);
                        if (depth == 0 && (truncated == null || !truncated[0])) {
                            collectCriticalPointsImpl(fn.args.get(0), var, lo, hi, ov, out, truncated, depth + 1);
                        }
                        return;
                    }
                }
                if (truncated != null) truncated[0] = true;
                return;
            }
            if (fn.args.size() == 1) {
                AstNode arg = fn.args.get(0);
                double half = radiansMode ? Math.PI : 180.0;
                switch (trimmedFn) {
                    case "sin":
                        pushPeriodicRoots(arg, var, 0.0, half, lo, hi, ov, out, truncated, depth);
                        if (depth == 0 && (truncated == null || !truncated[0]) && AstNode.matchLinear(arg, var) == null) {
                            collectCriticalPointsImpl(arg, var, lo, hi, ov, out, truncated, depth + 1);
                        }
                        return;
                    case "cos":
                        pushPeriodicRoots(arg, var, 0.5 * half, half, lo, hi, ov, out, truncated, depth);
                        if (depth == 0 && (truncated == null || !truncated[0]) && AstNode.matchLinear(arg, var) == null) {
                            collectCriticalPointsImpl(arg, var, lo, hi, ov, out, truncated, depth + 1);
                        }
                        return;
                    case "tan":
                        pushPeriodicRoots(arg, var, 0.0, half, lo, hi, ov, out, truncated, depth);
                        pushPeriodicRoots(arg, var, 0.5 * half, half, lo, hi, ov, out, truncated, depth);
                        if (depth == 0 && (truncated == null || !truncated[0]) && AstNode.matchLinear(arg, var) == null) {
                            collectCriticalPointsImpl(arg, var, lo, hi, ov, out, truncated, depth + 1);
                        }
                        return;
                    case "floor":
                    case "ceil":
                        pushPeriodicRoots(arg, var, 0.0, 1.0, lo, hi, ov, out, truncated, depth);
                        if (depth == 0 && (truncated == null || !truncated[0]) && AstNode.matchLinear(arg, var) == null) {
                            collectCriticalPointsImpl(arg, var, lo, hi, ov, out, truncated, depth + 1);
                        }
                        return;
                    case "round":
                        pushPeriodicRoots(arg, var, 0.5, 1.0, lo, hi, ov, out, truncated, depth);
                        if (depth == 0 && (truncated == null || !truncated[0]) && AstNode.matchLinear(arg, var) == null) {
                            collectCriticalPointsImpl(arg, var, lo, hi, ov, out, truncated, depth + 1);
                        }
                        return;
                    default:
                        break;
                }
            }
        }
        AstNode.LinearMatch lin = AstNode.matchLinear(node, var);
        if (lin != null) {
            Double av = evalAstWithDefaultZero(lin.a, var, null, ov);
            Double bv = evalAstWithDefaultZero(lin.b, var, null, ov);
            if (av != null && bv != null && av != 0.0) {
                pushCriticalPoint(out, -bv / av, lo, hi, truncated);
            }
        } else {
            Double f0 = evalAstWithDefaultZero(node, var, 0.0, ov);
            Double f1 = evalAstWithDefaultZero(node, var, 1.0, ov);
            Double fm1 = evalAstWithDefaultZero(node, var, -1.0, ov);
            Double f2 = evalAstWithDefaultZero(node, var, 2.0, ov);
            if (f0 != null && f1 != null && fm1 != null && f2 != null) {
                double qa = 0.5 * (f1 + fm1) - f0;
                double qb = 0.5 * (f1 - fm1);
                double qc = f0;
                double f2Pred = 4.0 * qa + 2.0 * qb + qc;
                if (Math.abs(qa) > 1e-12 && Math.abs(f2Pred - f2) <= 1e-6 * (1.0 + Math.abs(f2) + Math.abs(f0))) {
                    double xv = -qb / (2.0 * qa);
                    pushCriticalPoint(out, xv, lo, hi, truncated);
                    double disc = qb * qb - 4.0 * qa * qc;
                    if (disc >= 0.0) {
                        double s = Math.sqrt(disc);
                        pushCriticalPoint(out, (-qb - s) / (2.0 * qa), lo, hi, truncated);
                        pushCriticalPoint(out, (-qb + s) / (2.0 * qa), lo, hi, truncated);
                    }
                } else {
                    Double fm2 = evalAstWithDefaultZero(node, var, -2.0, ov);
                    Double f05 = evalAstWithDefaultZero(node, var, 0.5, ov);
                    if (fm2 != null && f05 != null
                            && Math.abs(f1 - fm1) <= 1e-9 * (1.0 + Math.abs(f1))
                            && Math.abs(f2 - fm2) <= 1e-9 * (1.0 + Math.abs(f2))) {
                        double u1 = f1 - f0;
                        double u2 = f2 - f0;
                        double ba = (u2 - 4.0 * u1) / 12.0;
                        double bb = (16.0 * u1 - u2) / 12.0;
                        double bc = f0;
                        double f05Pred = ba * 0.0625 + bb * 0.25 + bc;
                        if (Math.abs(ba) > 1e-12 && Math.abs(f05Pred - f05) <= 1e-6 * (1.0 + Math.abs(f05) + Math.abs(f0))) {
                            double uVert = -bb / (2.0 * ba);
                            if (uVert > 0.0) {
                                double s = Math.sqrt(uVert);
                                pushCriticalPoint(out, s, lo, hi, truncated);
                                pushCriticalPoint(out, -s, lo, hi, truncated);
                            }
                            double disc = bb * bb - 4.0 * ba * bc;
                            if (disc >= 0.0) {
                                double s = Math.sqrt(disc);
                                double[] uRoots = new double[]{(-bb - s) / (2.0 * ba), (-bb + s) / (2.0 * ba)};
                                for (double uRoot : uRoots) {
                                    if (uRoot >= 0.0) {
                                        double xr = Math.sqrt(uRoot);
                                        pushCriticalPoint(out, xr, lo, hi, truncated);
                                        pushCriticalPoint(out, -xr, lo, hi, truncated);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (node instanceof AstAdd || node instanceof AstSub) {
            boolean isSub = node instanceof AstSub;
            AstNode nl = isSub ? ((AstSub) node).left : ((AstAdd) node).left;
            AstNode nr = isSub ? ((AstSub) node).right : ((AstAdd) node).right;
            AstNode fnCandidate = null;
            Double targetVal = null;
            if (!nr.containsVar(var)) {
                Double k = evalAstWithDefaultZero(nr, var, null, ov);
                if (k != null) {
                    fnCandidate = nl;
                    targetVal = isSub ? k : -k;
                }
            } else if (!nl.containsVar(var)) {
                Double k = evalAstWithDefaultZero(nl, var, null, ov);
                if (k != null) {
                    fnCandidate = nr;
                    targetVal = isSub ? k : -k;
                }
            }
            if (fnCandidate instanceof AstFunc && targetVal != null) {
                AstFunc fn = (AstFunc) fnCandidate;
                if (fn.args.size() == 1) {
                    AstNode arg = fn.args.get(0);
                    double target = targetVal;
                    double pi = Math.PI;
                    switch (fn.name) {
                        case "sin":
                            if (Math.abs(target) <= 1.0) {
                                double u0 = radiansMode ? Math.asin(target) : Math.toDegrees(Math.asin(target));
                                double half = radiansMode ? pi : 180.0;
                                double full = 2.0 * half;
                                pushPeriodicRoots(arg, var, u0, full, lo, hi, ov, out, truncated, depth);
                                pushPeriodicRoots(arg, var, half - u0, full, lo, hi, ov, out, truncated, depth);
                            }
                            break;
                        case "cos":
                            if (Math.abs(target) <= 1.0) {
                                double u0 = radiansMode ? Math.acos(target) : Math.toDegrees(Math.acos(target));
                                double full = radiansMode ? 2.0 * pi : 360.0;
                                pushPeriodicRoots(arg, var, u0, full, lo, hi, ov, out, truncated, depth);
                                pushPeriodicRoots(arg, var, -u0, full, lo, hi, ov, out, truncated, depth);
                            }
                            break;
                        case "tan": {
                            double u0 = radiansMode ? Math.atan(target) : Math.toDegrees(Math.atan(target));
                            double half = radiansMode ? pi : 180.0;
                            pushPeriodicRoots(arg, var, u0, half, lo, hi, ov, out, truncated, depth);
                            break;
                        }
                        case "exp":
                            if (target > 0.0) {
                                collectCriticalPoints(new AstSub(arg, new AstNum(Value.fromFloat(Math.log(target)))), var, lo, hi, ov, out, truncated);
                            }
                            break;
                        case "ln":
                            collectCriticalPoints(new AstSub(arg, new AstNum(Value.fromFloat(Math.exp(target)))), var, lo, hi, ov, out, truncated);
                            break;
                        case "sqrt":
                            if (target >= 0.0) {
                                collectCriticalPoints(new AstSub(arg, new AstNum(Value.fromFloat(target * target))), var, lo, hi, ov, out, truncated);
                            }
                            break;
                        case "abs":
                            if (target >= 0.0) {
                                collectCriticalPoints(new AstSub(arg, new AstNum(Value.fromFloat(target))), var, lo, hi, ov, out, truncated);
                                collectCriticalPoints(new AstAdd(arg, new AstNum(Value.fromFloat(target))), var, lo, hi, ov, out, truncated);
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        if (node instanceof AstNeg) {
            collectCriticalPoints(((AstNeg) node).inner, var, lo, hi, ov, out, truncated);
        } else if (node instanceof AstAdd) {
            AstAdd add = (AstAdd) node;
            collectCriticalPoints(add.left, var, lo, hi, ov, out, truncated);
            collectCriticalPoints(add.right, var, lo, hi, ov, out, truncated);
        } else if (node instanceof AstSub) {
            AstSub sub = (AstSub) node;
            collectCriticalPoints(sub.left, var, lo, hi, ov, out, truncated);
            collectCriticalPoints(sub.right, var, lo, hi, ov, out, truncated);
        } else if (node instanceof AstMul) {
            AstMul mul = (AstMul) node;
            collectCriticalPoints(mul.left, var, lo, hi, ov, out, truncated);
            collectCriticalPoints(mul.right, var, lo, hi, ov, out, truncated);
        } else if (node instanceof AstDiv) {
            AstDiv div = (AstDiv) node;
            collectCriticalPoints(div.left, var, lo, hi, ov, out, truncated);
            collectCriticalPoints(div.right, var, lo, hi, ov, out, truncated);
        } else if (node instanceof AstPow) {
            collectCriticalPoints(((AstPow) node).base, var, lo, hi, ov, out, truncated);
        } else if (node instanceof AstFunc) {
            for (AstNode arg : ((AstFunc) node).args) {
                collectCriticalPoints(arg, var, lo, hi, ov, out, truncated);
            }
        }
    }

    private double evaluateDefiniteIntegral(AstNode exprNode, String exprStr, String v, double a, double b, Map<String, Value> ov) {
        Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
        int prevDepth = INTEGRATION_DEPTH.get();
        boolean hasInnerInt = (exprNode != null)
                ? containsIntegralNode(exprNode)
                : (exprStr.contains("int(") || exprStr.contains("defint("));
        AstNode rawParsed = exprNode != null ? exprNode : AstParser.tryParse(exprStr);
        AstNode parsed = rawParsed != null ? AstNode.expandDerivatives(rawParsed, radiansMode) : null;
        if (prevDepth == 0 && !hasInnerInt && parsed != null) {
            AstNode anti = parsed.integrate(v, radiansMode);
            if (isAntiderivativeValidOn(anti, v, Math.min(a, b), Math.max(a, b), ov)) {
                try {
                    local.put(v, Value.fromFloat(b));
                    Value fbVal = evalAntiderivativeEndpoint(anti, local);
                    local.put(v, Value.fromFloat(a));
                    Value faVal = evalAntiderivativeEndpoint(anti, local);
                    if (fbVal.c.isReal() && faVal.c.isReal()
                            && Double.isFinite(fbVal.c.re) && Double.isFinite(faVal.c.re)) {
                        double fa = faVal.c.re;
                        double fb = fbVal.c.re;
                        double diff = fb - fa;
                        double scale = Math.max(Math.abs(fa), Math.abs(fb));
                        double finalDiff = (scale > 0.0 && Math.abs(diff) <= 1e-15 * scale && Math.abs(b - a) > 1e-9)
                                ? 0.0
                                : diff;
                        boolean severeCancellation = finalDiff != 0.0
                                && scale > 0.0
                                && Math.abs(finalDiff) <= 1e-5 * scale
                                && Math.abs(b - a) <= 10.0;
                        if (Double.isFinite(finalDiff) && !severeCancellation) {
                            lastIntegrationReport = new IntegrationReport(
                                    finalDiff, 0.0, true, 1, 2,
                                    "Konvergiert: Exakte Stammfunktion ausgewertet (2 Auswertungen)");
                            return finalDiff;
                        }
                    }
                } catch (RuntimeException ignored) {
                    checkInterrupted();
                }
            }
        }
        List<Double> criticalAnchors = new ArrayList<>();
        boolean[] anchorsTruncated = new boolean[]{false};
        if (parsed != null) {
            double lo = Math.min(a, b);
            double hi = Math.max(a, b);
            collectCriticalPoints(parsed, v, lo, hi, ov, criticalAnchors, anchorsTruncated);
            if (prevDepth == 0) {
                AstNode dNode = parsed.diff(v, radiansMode);
                if (dNode != null) {
                    collectCriticalPoints(dNode, v, lo, hi, ov, criticalAnchors, anchorsTruncated);
                }
            }
        }
        int initialSubintervals = (prevDepth > 0 || hasInnerInt) ? 16 : 2500;
        double epsAbs = (prevDepth > 0 || hasInnerInt) ? 1e-12 : 1e-13;
        double epsRel = 1e-9;
        INTEGRATION_DEPTH.set(prevDepth + 1);
        try {
            final AstNode evalTarget = (!hasInnerInt && parsed != null) ? parsed : exprNode;
            IntegrationReport report = adaptiveGaussIntegrate(x -> {
                local.put(v, Value.fromFloat(x));
                Value val = (evalTarget != null) ? evalAst(evalTarget, local) : evalExpression(exprStr, local);
                if (!val.c.isReal() || !Double.isFinite(val.c.re)) {
                    throw new RuntimeException("Integrand liefert nicht-reellen oder unendlichen Wert");
                }
                return val.c.re;
            }, a, b, initialSubintervals, epsAbs, epsRel, criticalAnchors, anchorsTruncated[0]);
            lastIntegrationReport = report;
            return report.value;
        } finally {
            INTEGRATION_DEPTH.set(prevDepth);
        }
    }

    private interface RealSampler {
        double sample(double x);
    }

    private static final class ValErr {
        final Value val;
        final double cancelErr;
        ValErr(Value val, double cancelErr) { this.val = val; this.cancelErr = cancelErr; }
    }

    private ValErr evalAstWithCancelErr(AstNode node, Map<String, Value> ov) {
        if (node instanceof AstNum || node instanceof AstVar) {
            return new ValErr(evalAst(node, ov), 0.0);
        }
        if (node instanceof AstNeg) {
            ValErr inner = evalAstWithCancelErr(((AstNeg) node).inner, ov);
            return new ValErr(inner.val.negate(), inner.cancelErr);
        }
        if (node instanceof AstAdd) {
            AstAdd add = (AstAdd) node;
            ValErr la = evalAstWithCancelErr(add.left, ov);
            ValErr rb = evalAstWithCancelErr(add.right, ov);
            double step = 1.5 * Math.ulp(1.0) * (la.val.c.abs() + rb.val.c.abs());
            return new ValErr(la.val.add(rb.val), la.cancelErr + rb.cancelErr + step);
        }
        if (node instanceof AstSub) {
            AstSub sub = (AstSub) node;
            ValErr la = evalAstWithCancelErr(sub.left, ov);
            ValErr rb = evalAstWithCancelErr(sub.right, ov);
            double step = 1.5 * Math.ulp(1.0) * (la.val.c.abs() + rb.val.c.abs());
            return new ValErr(la.val.sub(rb.val), la.cancelErr + rb.cancelErr + step);
        }
        if (node instanceof AstMul) {
            AstMul mul = (AstMul) node;
            ValErr la = evalAstWithCancelErr(mul.left, ov);
            ValErr rb = evalAstWithCancelErr(mul.right, ov);
            return new ValErr(la.val.mul(rb.val), la.val.c.abs() * rb.cancelErr + rb.val.c.abs() * la.cancelErr);
        }
        if (node instanceof AstDiv) {
            AstDiv div = (AstDiv) node;
            ValErr la = evalAstWithCancelErr(div.left, ov);
            ValErr rb = evalAstWithCancelErr(div.right, ov);
            Value q = la.val.div(rb.val);
            double bd = Math.max(rb.val.c.abs(), Double.MIN_NORMAL);
            return new ValErr(q, (la.cancelErr + q.c.abs() * rb.cancelErr) / bd);
        }
        if (node instanceof AstPow) {
            AstPow pow = (AstPow) node;
            ValErr b = evalAstWithCancelErr(pow.base, ov);
            ValErr e = evalAstWithCancelErr(pow.exp, ov);
            Value p = b.val.pow(e.val);
            double babs = b.val.c.abs();
            double pabs = p.c.abs();
            double err = (b.cancelErr == 0.0 && e.cancelErr == 0.0) ? 0.0
                    : (babs > 0.0 ? pabs * ((e.val.c.abs() * b.cancelErr / babs) + Math.abs(Math.log(babs)) * e.cancelErr) : 0.0);
            return new ValErr(p, err);
        }
        if (node instanceof AstFunc && ((AstFunc) node).args.size() == 1) {
            AstFunc fn = (AstFunc) node;
            ValErr u = evalAstWithCancelErr(fn.args.get(0), ov);
            Value v = evalAst(node, ov);
            double scale;
            switch (fn.name) {
                case "sin":
                case "cos": scale = 1.0; break;
                case "tan": scale = 1.0 + v.c.abs() * v.c.abs(); break;
                case "exp": scale = v.c.abs(); break;
                case "ln": scale = 1.0 / Math.max(u.val.c.abs(), Double.MIN_NORMAL); break;
                case "sqrt": scale = 0.5 / Math.max(v.c.abs(), Double.MIN_NORMAL); break;
                default: scale = 1.0 + v.c.abs() / Math.max(u.val.c.abs(), 1.0); break;
            }
            return new ValErr(v, scale * u.cancelErr);
        }
        return new ValErr(evalAst(node, ov), 0.0);
    }

    private static boolean containsStepFunction(AstNode node) {
        return containsNonsmoothFunction(node, false);
    }

    private static boolean containsStepOrAbs(AstNode node) {
        return containsNonsmoothFunction(node, true);
    }

    private static boolean containsNonsmoothFunction(AstNode node, boolean includeAbs) {
        if (node == null) return false;
        if (node instanceof AstNeg) return containsNonsmoothFunction(((AstNeg) node).inner, includeAbs);
        if (node instanceof AstAdd) {
            AstAdd a = (AstAdd) node;
            return containsNonsmoothFunction(a.left, includeAbs) || containsNonsmoothFunction(a.right, includeAbs);
        }
        if (node instanceof AstSub) {
            AstSub s = (AstSub) node;
            return containsNonsmoothFunction(s.left, includeAbs) || containsNonsmoothFunction(s.right, includeAbs);
        }
        if (node instanceof AstMul) {
            AstMul m = (AstMul) node;
            return containsNonsmoothFunction(m.left, includeAbs) || containsNonsmoothFunction(m.right, includeAbs);
        }
        if (node instanceof AstDiv) {
            AstDiv d = (AstDiv) node;
            return containsNonsmoothFunction(d.left, includeAbs) || containsNonsmoothFunction(d.right, includeAbs);
        }
        if (node instanceof AstPow) {
            AstPow p = (AstPow) node;
            return containsNonsmoothFunction(p.base, includeAbs) || containsNonsmoothFunction(p.exp, includeAbs);
        }
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            if (f.name.equals("floor") || f.name.equals("ceil") || f.name.equals("round")
                    || f.name.equals("sign") || f.name.equals("sgn") || f.name.equals("mod")
                    || (includeAbs && f.name.equals("abs"))) {
                return true;
            }
            for (AstNode arg : f.args) {
                if (containsNonsmoothFunction(arg, includeAbs)) return true;
            }
        }
        return false;
    }

    private static boolean containsDiscreteDomainOf(AstNode node, String var) {
        if (node == null) return false;
        if (node instanceof AstNeg) return containsDiscreteDomainOf(((AstNeg) node).inner, var);
        if (node instanceof AstAdd) {
            AstAdd a = (AstAdd) node;
            return containsDiscreteDomainOf(a.left, var) || containsDiscreteDomainOf(a.right, var);
        }
        if (node instanceof AstSub) {
            AstSub s = (AstSub) node;
            return containsDiscreteDomainOf(s.left, var) || containsDiscreteDomainOf(s.right, var);
        }
        if (node instanceof AstMul) {
            AstMul m = (AstMul) node;
            return containsDiscreteDomainOf(m.left, var) || containsDiscreteDomainOf(m.right, var);
        }
        if (node instanceof AstDiv) {
            AstDiv d = (AstDiv) node;
            return containsDiscreteDomainOf(d.left, var) || containsDiscreteDomainOf(d.right, var);
        }
        if (node instanceof AstPow) {
            AstPow p = (AstPow) node;
            return containsDiscreteDomainOf(p.base, var) || containsDiscreteDomainOf(p.exp, var);
        }
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            String n = f.name.trim();
            boolean isFact = n.equals("factorial") || n.equals("fact") || n.equals("!");
            for (AstNode arg : f.args) {
                if ((isFact && arg.containsVar(var)) || containsDiscreteDomainOf(arg, var)) return true;
            }
        }
        return false;
    }

    private double computeNumericalDerivative(RealSampler sampler, double x0) {
        return computeNumericalDerivative(sampler, x0, sampler.sample(x0), false);
    }

    /** Returns true when symmetric h1/h2 samples equal f0 identically (or vanish smoothly by Aitken). */
    private boolean checkDerivativeJumpOrKink(RealSampler sampler, double x0, double f0, boolean checkSublinearZero) {
        double h1 = 1e-7 * Math.max(1.0, Math.abs(x0));
        double h2 = 1e-5 * Math.max(1.0, Math.abs(x0));
        try {
            double fp1k = sampler.sample(x0 + h1);
            double fm1k = sampler.sample(x0 - h1);
            double fp2k = sampler.sample(x0 + h2);
            double fm2k = sampler.sample(x0 - h2);
            double jump1 = Math.abs(fp1k - fm1k);
            double jump2 = Math.abs(fp2k - fm2k);
            double d0p1 = Math.abs(fp1k - f0);
            double d0p2 = Math.abs(fp2k - f0);
            double d0m1 = Math.abs(fm1k - f0);
            double d0m2 = Math.abs(fm2k - f0);
            double valScale = Math.max(1.0, Math.max(Math.abs(f0), Math.max(Math.abs(fp1k), Math.abs(fm1k))));
            if ((jump1 > 1e-4 * valScale && jump2 < 2.5 * jump1)
                    || (d0p1 > 1e-4 * valScale && d0p2 < 2.5 * d0p1)
                    || (d0m1 > 1e-4 * valScale && d0m2 < 2.5 * d0m1)) {
                throw new RuntimeException("Funktion an der Stelle nicht stetig / nicht differenzierbar");
            }
            double dr1 = (fp1k - f0) / h1;
            double dl1 = (f0 - fm1k) / h1;
            double dr2 = (fp2k - f0) / h2;
            double dl2 = (f0 - fm2k) / h2;
            double slopeScale = Math.max(1.0, Math.max(Math.abs(dr1), Math.abs(dl1)));
            if ((d0p1 > 1e-9 * valScale && Math.abs(dr1) > 3.0 * Math.abs(dr2) && Math.abs(dr1) > 10.0 * valScale)
                    || (d0m1 > 1e-9 * valScale && Math.abs(dl1) > 3.0 * Math.abs(dl2) && Math.abs(dl1) > 10.0 * valScale)) {
                throw new RuntimeException("Funktion an der Stelle nicht differenzierbar");
            }
            if (Math.abs(dr1 - dr2) <= 1e-2 * slopeScale
                    && Math.abs(dl1 - dl2) <= 1e-2 * slopeScale
                    && Math.abs(dr1 - dl1) > 1e-3 * slopeScale) {
                throw new RuntimeException("Funktion an der Stelle nicht differenzierbar");
            }
            if (checkSublinearZero
                    && Math.abs(dr1) <= 0.35 * Math.abs(dr2)
                    && Math.abs(dl1) <= 0.35 * Math.abs(dl2)
                    && Math.max(Math.abs(dr1), Math.abs(dl1)) <= 1e-2 * slopeScale) {
                double h0 = 1e-9 * Math.max(1.0, Math.abs(x0));
                double fp0k = sampler.sample(x0 + h0);
                double fm0k = sampler.sample(x0 - h0);
                double dr0 = (fp0k - f0) / h0;
                double dl0 = (f0 - fm0k) / h0;
                if (Math.abs(dr0) <= 0.35 * Math.abs(dr1)
                        && Math.abs(dl0) <= 0.35 * Math.abs(dl1)
                        && Math.max(Math.abs(dr0), Math.abs(dl0)) <= 1e-3 * slopeScale) {
                    double denR = dr2 - 2.0 * dr1 + dr0;
                    double limR = denR == 0.0 ? dr0 : dr0 - (dr1 - dr0) * (dr1 - dr0) / denR;
                    double denL = dl2 - 2.0 * dl1 + dl0;
                    double limL = denL == 0.0 ? dl0 : dl0 - (dl1 - dl0) * (dl1 - dl0) / denL;
                    if (Math.abs(limR) <= 1e-9 && Math.abs(limL) <= 1e-9) {
                        return true;
                    }
                }
            }
            return fp1k == f0 && fm1k == f0 && fp2k == f0 && fm2k == f0;
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("differenzierbar")) {
                throw ex;
            }
            checkInterrupted();
            return false;
        }
    }

    private double computeNumericalDerivative(RealSampler sampler, double x0, double f0, boolean jumpChecked) {
        if (!jumpChecked && checkDerivativeJumpOrKink(sampler, x0, f0, false)) {
            return 0.0;
        }
        double h = 1e-4 * Math.max(1.0, Math.abs(x0));
        boolean adjusted = false;
        double fp2 = 0.0;
        double fp1 = 0.0;
        double fm1 = 0.0;
        double fm2 = 0.0;
        boolean ok = false;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                fp2 = sampler.sample(x0 + 2.0 * h);
                fp1 = sampler.sample(x0 + h);
                fm1 = sampler.sample(x0 - h);
                fm2 = sampler.sample(x0 - 2.0 * h);
                ok = true;
                break;
            } catch (RuntimeException ex) {
                checkInterrupted();
                h *= 0.5;
                adjusted = true;
                if (!Double.isFinite(h) || h == 0.0 || x0 + h == x0 || x0 - h == x0) {
                    throw new RuntimeException("Funktion an der Stelle nicht reell differenzierbar");
                }
            }
        }
        if (!ok) {
            throw new RuntimeException("Funktion an der Stelle nicht reell differenzierbar");
        }
        if (adjusted) {
            double safeH = h * 0.25;
            if (safeH > 0.0 && x0 + safeH != x0 && x0 - safeH != x0) {
                try {
                    double sp2 = sampler.sample(x0 + 2.0 * safeH);
                    double sp1 = sampler.sample(x0 + safeH);
                    double sm1 = sampler.sample(x0 - safeH);
                    double sm2 = sampler.sample(x0 - 2.0 * safeH);
                    h = safeH;
                    fp2 = sp2;
                    fp1 = sp1;
                    fm1 = sm1;
                    fm2 = sm2;
                } catch (RuntimeException ignored) {
                    checkInterrupted();
                }
            }
        } else if (Math.abs(f0) > 0.0 && Math.abs(fp1 - fm1) < 1e-7 * Math.abs(f0)) {
            double hWide = 1e-2 * Math.max(1.0, Math.abs(x0));
            if (hWide > h && x0 + hWide != x0 && x0 - hWide != x0) {
                try {
                    double wp2 = sampler.sample(x0 + 2.0 * hWide);
                    double wp1 = sampler.sample(x0 + hWide);
                    double wm1 = sampler.sample(x0 - hWide);
                    double wm2 = sampler.sample(x0 - 2.0 * hWide);
                    h = hWide;
                    fp2 = wp2;
                    fp1 = wp1;
                    fm1 = wm1;
                    fm2 = wm2;
                } catch (RuntimeException ignored) {
                    checkInterrupted();
                }
            }
        }
        double hEff = 0.5 * ((x0 + h) - (x0 - h));
        double denom = 12.0 * (hEff > 0.0 ? hEff : h);
        double derivCur = (8.0 * (fp1 - fm1) - (fp2 - fm2)) / denom;
        double best = derivCur;
        double scale = Math.max(Math.abs(f0),
                Math.max(Math.max(Math.abs(fp1), Math.abs(fm1)),
                        Math.max(Math.abs(fp2), Math.abs(fm2))));

        double curH = h;
        double xUlp = Math.ulp(Math.abs(x0));
        for (int ref = 0; ref < 20; ref++) {
            double hHalf = curH * 0.5;
            if (hHalf <= 0.0 || x0 + hHalf == x0 || x0 - hHalf == x0) break;
            try {
                double hp2 = sampler.sample(x0 + 2.0 * hHalf);
                double hp1 = sampler.sample(x0 + hHalf);
                double hm1 = sampler.sample(x0 - hHalf);
                double hm2 = sampler.sample(x0 - 2.0 * hHalf);
                double hHalfEff = 0.5 * ((x0 + hHalf) - (x0 - hHalf));
                double denomHalf = 12.0 * (hHalfEff > 0.0 ? hHalfEff : hHalf);
                double derivHalf = (8.0 * (hp1 - hm1) - (hp2 - hm2)) / denomHalf;
                scale = Math.max(scale,
                        Math.max(Math.max(Math.abs(hp1), Math.abs(hm1)),
                                Math.max(Math.abs(hp2), Math.abs(hm2))));
                double noiseBound = (64.0 * Math.ulp(1.0) * scale + 8.0 * Math.abs(derivCur) * xUlp) / hHalf;
                double diff = Math.abs(derivHalf - derivCur);
                if (!Double.isFinite(derivHalf) || diff <= 4.0 * noiseBound) {
                    break;
                }
                double richardson = (16.0 * derivHalf - derivCur) / 15.0;
                if (!Double.isFinite(richardson)) {
                    break;
                }
                best = richardson;
                if (diff <= 1e-9 * Math.max(1.0, Math.abs(richardson))) {
                    break;
                }
                derivCur = derivHalf;
                curH = hHalf;
            } catch (RuntimeException ignored) {
                checkInterrupted();
                break;
            }
        }
        return best;
    }

    private double evalRealDerivativeSampleExpr(String expr, Map<String, Value> local, String var, double x) {
        local.put(var, Value.fromFloat(x));
        Value val = evalExpression(expr, local);
        if (!val.c.isReal() || !Double.isFinite(val.c.re)) {
            throw new RuntimeException("Funktion an der Stelle nicht reell differenzierbar");
        }
        return val.c.re;
    }

    private Value applyFunction(String fn, List<String> args, Map<String, Value> ov) {
        checkInterrupted();
        validateFunctionArity(fn, args.size());
        if (fn.equals("det") && args.size() == 1) {
            return Value.fromFloat(matrixDet(evalMatrixExpr(args.get(0), ov)));
        }
        if (fn.equals("integral") && args.size() == 2) {
            throw new RuntimeException("Symbolische Integration nicht möglich");
        }

        boolean sum = fn.equals("sum");
        if ((sum || fn.equals("prod") || fn.equals("product")) && args.size() == 4) {
            String v = calculusVariable(args.get(1));
            long a = requireIntegerBound(evalExpression(args.get(2), ov), fn);
            long b = requireIntegerBound(evalExpression(args.get(3), ov), fn);
            if (a <= Long.MAX_VALUE - 100000 && b > a + 100000) {
                throw new RuntimeException(sum ? "Summationsbereich zu groß" : "Produktbereich zu groß");
            }
            Value result = Value.fromInt(sum ? 0 : 1);
            if (a > b) return result;
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            int iter = 0;
            for (long k = a; ; k++) {
                if ((++iter & 255) == 0) checkInterrupted();
                local.put(v, Value.fromInt(k));
                Value term = evalExpression(args.get(0), local);
                result = sum ? result.add(term) : result.mul(term);
                if (k == b) return result;
            }
        }

        if ((fn.equals("int") || fn.equals("defint")) && args.size() == 4) {
            String v = calculusVariable(args.get(1));
            Value aVal = evalExpression(args.get(2), ov);
            Value bVal = evalExpression(args.get(3), ov);
            if (!aVal.c.isReal() || !bVal.c.isReal() || !Double.isFinite(aVal.c.re) || !Double.isFinite(bVal.c.re)) {
                throw new RuntimeException("Integrationsgrenzen müssen endliche reelle Zahlen sein");
            }
            return Value.fromFloat(evaluateDefiniteIntegral(
                    null, args.get(0), v, aVal.c.re, bVal.c.re, ov));
        }

        if ((fn.equals("deriv") || fn.equals("diff") || fn.equals("d")) && args.size() >= 2) {
            if (args.size() == 2) {
                String v = calculusVariable(args.get(1));
                AstNode innerAst = AstParser.tryParse(args.get(0));
                if (innerAst != null && !containsStepFunction(innerAst)) {
                    AstNode dNode = innerAst.diff(v, radiansMode);
                    if (dNode != null) {
                        return evalAst(dNode.simplify(), ov);
                    }
                }
                throw new RuntimeException("Symbolische Ableitung erforderlich");
            }
            String v = calculusVariable(args.get(1));
            Value x0Val = evalExpression(args.get(2), ov);
            if (!x0Val.c.isReal() || !Double.isFinite(x0Val.c.re)) {
                throw new RuntimeException("Ableitungsstelle muss eine endliche reelle Zahl sein");
            }
            double x0 = x0Val.c.re;
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            RealSampler sampler = x -> evalRealDerivativeSampleExpr(args.get(0), local, v, x);
            AstNode parsedAst = AstParser.tryParse(args.get(0));
            if (parsedAst != null && containsDiscreteDomainOf(parsedAst, v)) {
                throw new RuntimeException("Funktion an der Stelle nicht reell differenzierbar");
            }
            double f0 = sampler.sample(x0);
            boolean jumpChecked = parsedAst != null && containsStepOrAbs(parsedAst);
            boolean isFlat = jumpChecked && checkDerivativeJumpOrKink(sampler, x0, f0, true);
            if (parsedAst != null) {
                AstNode dNode = parsedAst.diff(v, radiansMode);
                if (dNode != null) {
                    ValErr ve = null;
                    try {
                        Map<String, Value> dLocal = new HashMap<>(local);
                        dLocal.put(v, Value.fromFloat(x0));
                        ve = evalAstWithCancelErr(dNode.simplify(), dLocal);
                    } catch (RuntimeException ignored) {
                        checkInterrupted();
                    }
                    if (ve != null && ve.val.c.isReal() && Double.isFinite(ve.val.c.re)) {
                        if (ve.cancelErr <= 5e-7 * Math.abs(ve.val.c.re)) {
                            return Value.fromFloat(ve.val.c.re);
                        }
                        throw new RuntimeException("Auslöschung zu stark für verlässliche Ableitung");
                    }
                }
            }
            if (isFlat) {
                return Value.fromFloat(0.0);
            }
            return Value.fromFloat(computeNumericalDerivative(sampler, x0, f0, jumpChecked));
        }

        List<Value> argVals = new ArrayList<>(args.size());
        for (String a : args) {
            argVals.add(evalExpression(a, ov));
        }
        return applyFunctionValues(fn, argVals);
    }

    private Value applyFunctionValues(String fn, List<Value> argVals) {
        Value v0 = argVals.get(0);
        if (fn.equals("sqrt")) {
            return v0.sqrt();
        }

        Complex c = v0.c;
        if (!radiansMode && c.isReal()) {
            c = Complex.real(Math.toRadians(c.re));
        }

        switch (fn) {
            case "factorial":
            case "!": return evalFactorial(v0);
            case "percent":
            case "%": return evalPercent(v0);
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
            case "lg":
            case "log10": return Value.fromFloat(Math.log10(v0.c.re));
            case "exp": return Value.fromComplex(v0.c.exp());
            case "re": return Value.fromFloat(v0.c.re);
            case "im": return Value.fromFloat(v0.c.im);
            case "arg": {
                double a = Math.atan2(v0.c.im, v0.c.re);
                return Value.fromFloat(radiansMode ? a : Math.toDegrees(a));
            }
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
                if (Math.abs(v0.c.re) >= 9.0e15 || !Double.isFinite(v0.c.re)) {
                    return Value.fromFloat(Math.floor(v0.c.re));
                }
                return Value.fromInt((long) Math.floor(v0.c.re));
            }
            case "ceil": {
                if (!v0.c.isReal()) throw new RuntimeException("ceil erfordert reelle Zahl");
                if (v0.rat != null) {
                    BigInteger[] qr = v0.rat.num.divideAndRemainder(v0.rat.den);
                    BigInteger q = qr[0];
                    if (qr[1].signum() > 0) q = q.add(BigInteger.ONE);
                    return Value.fromInt(q);
                }
                if (Math.abs(v0.c.re) >= 9.0e15 || !Double.isFinite(v0.c.re)) {
                    return Value.fromFloat(Math.ceil(v0.c.re));
                }
                return Value.fromInt((long) Math.ceil(v0.c.re));
            }
            case "round": {
                if (!v0.c.isReal()) throw new RuntimeException("round erfordert reelle Zahl");
                if (v0.rat != null) {
                    BigInteger[] qr = v0.rat.num.divideAndRemainder(v0.rat.den);
                    BigInteger q = qr[0];
                    if (qr[1].abs().shiftLeft(1).compareTo(v0.rat.den) >= 0) {
                        q = (v0.rat.num.signum() >= 0) ? q.add(BigInteger.ONE) : q.subtract(BigInteger.ONE);
                    }
                    return Value.fromInt(q);
                }
                if (Math.abs(v0.c.re) >= 9.0e15 || !Double.isFinite(v0.c.re)) {
                    return Value.fromFloat(Math.rint(v0.c.re));
                }
                double ax = Math.abs(v0.c.re);
                double fl = Math.floor(ax);
                long rounded = (long) ((ax - fl >= 0.5) ? (fl + 1.0) : fl);
                if (v0.c.re < 0.0) rounded = -rounded;
                return Value.fromInt(rounded);
            }
            case "sign":
            case "sgn": {
                if (!v0.c.isReal()) throw new RuntimeException("sign erfordert reelle Zahl");
                long sgn = v0.c.re > 0 ? 1 : (v0.c.re < 0 ? -1 : 0);
                return Value.fromInt(sgn);
            }
            case "gcd": {
                Value v1 = argVals.get(1);
                BigInteger a = (v0.rat != null && v0.rat.den.equals(BigInteger.ONE))
                        ? v0.rat.num.abs()
                        : BigInteger.valueOf(Math.round(Math.abs(v0.c.re)));
                BigInteger b = (v1.rat != null && v1.rat.den.equals(BigInteger.ONE))
                        ? v1.rat.num.abs()
                        : BigInteger.valueOf(Math.round(Math.abs(v1.c.re)));
                return Value.fromInt(a.gcd(b));
            }
            case "lcm": {
                Value v1 = argVals.get(1);
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
                return evalModulo(v0, argVals.get(1));
            }
            case "nthroot": {
                Value index = argVals.size() == 1 ? Value.fromInt(3) : v0;
                Value radicand = argVals.size() == 1 ? v0 : argVals.get(1);
                if (!index.c.isReal() || !Double.isFinite(index.c.re) || index.c.re == 0.0) {
                    throw new RuntimeException("Wurzelindex muss eine reelle Zahl ungleich null sein");
                }
                double n = index.c.re;
                if (radicand.c.isReal() && radicand.c.re < 0.0 && Math.abs(n % 2.0) == 1.0) {
                    return Value.fromFloat(-Math.pow(-radicand.c.re, 1.0 / n));
                }
                return radicand.pow(Value.fromFloat(1.0 / n));
            }
            case "log": {
                if (argVals.size() == 2) {
                    Value v1 = argVals.get(1);
                    return Value.fromFloat(Math.log(v1.c.re) / Math.log(v0.c.re));
                }
                return Value.fromFloat(Math.log10(v0.c.re));
            }
            case "binomial":
            case "nCr": {
                Value v1 = argVals.get(1);
                if (!v0.c.isReal() || !v1.c.isReal()
                        || !Double.isFinite(v0.c.re) || !Double.isFinite(v1.c.re)) {
                    throw new RuntimeException("binomial erfordert endliche reelle Zahlen");
                }
                return Value.fromFloat(binomialCoeff(v0.c.re, v1.c.re));
            }
            default:
                throw new RuntimeException("Unknown func: " + fn);
        }
    }

    private double binomialCoeff(double n, double k) {
        double kRounded = Math.rint(k);
        if (!Double.isFinite(n) || !Double.isFinite(k)
                || Math.abs(k - kRounded) > 1e-9 || kRounded < 0.0) {
            throw new RuntimeException("binomial erfordert nicht-negatives ganzzahliges k");
        }
        long ku = Math.round(kRounded);
        if (ku == 0) return 1.0;
        if (ku > 100000) throw new RuntimeException("binomial: k zu groß");
        double nRounded = Math.rint(n);
        if (Math.abs(n - nRounded) <= 1e-9 && nRounded >= 0.0) {
            long nu = Math.round(nRounded);
            if (ku > nu) return 0.0;
            ku = Math.min(ku, nu - ku);
            double res = 1.0;
            for (long i = 1; i <= ku; i++) {
                res = res * (double) (nu - ku + i) / (double) i;
                if (!Double.isFinite(res)) break;
            }
            return res;
        }
        double res = 1.0;
        for (long i = 1; i <= ku; i++) {
            res = res * (n - (double) ku + (double) i) / (double) i;
            if (!Double.isFinite(res)) break;
        }
        return res;
    }

    // --- Matrix Support ---
    private static void checkMatrixFinite(double[][] mat) {
        for (double[] row : mat) {
            for (double v : row) {
                if (!Double.isFinite(v)) {
                    throw new RuntimeException("Matrix-Überlauf");
                }
            }
        }
    }

    private static ExactRat doubleToExactRat(double v) {
        if (v == 0.0) return ExactRat.of(0);
        if (!Double.isFinite(v) || Math.abs(v) > 1e30 || Math.abs(v) < 1e-30) {
            return null;
        }
        double x = Math.abs(v);
        if (x < 1e9) {
            long p0 = 0, q0 = 1, p1 = 1, q1 = 0;
            double rem = x;
            for (int iter = 0; iter < 32; iter++) {
                double a = Math.floor(rem);
                if (!Double.isFinite(a) || a > 1e9) break;
                long ai = (long) a;
                if (q1 > 0 && ai > (100_000L - q0) / q1) break;
                long p2 = ai * p1 + p0;
                long q2 = ai * q1 + q0;
                if (q2 > 100_000L) break;
                if (p2 != 0) {
                    double approx = (double) p2 / (double) q2;
                    if (Math.abs(approx - x) <= 2e-15 * x) {
                        long num = v < 0 ? -p2 : p2;
                        return new ExactRat(BigInteger.valueOf(num), BigInteger.valueOf(q2));
                    }
                }
                double frac = rem - a;
                if (frac <= 1e-15 * Math.max(1.0, rem)) break;
                p0 = p1; q0 = q1;
                p1 = p2; q1 = q2;
                rem = 1.0 / frac;
            }
        }
        try {
            java.math.BigDecimal bd = java.math.BigDecimal.valueOf(v).stripTrailingZeros();
            int scale = bd.scale();
            if (scale < -30 || scale > 30) return null;
            BigInteger num;
            BigInteger den;
            if (scale <= 0) {
                num = bd.unscaledValue().multiply(BigInteger.TEN.pow(-scale));
                den = BigInteger.ONE;
            } else {
                num = bd.unscaledValue();
                den = BigInteger.TEN.pow(scale);
            }
            if (num.bitLength() > 128 || den.bitLength() > 128) return null;
            return new ExactRat(num, den);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private double[][] tryExactMatrixInv(double[][] m) {
        int n = m.length;
        if (n > 12) return null;
        ExactRat[][] aug = new ExactRat[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                ExactRat cell = doubleToExactRat(m[i][j]);
                if (cell == null) return null;
                aug[i][j] = cell;
                aug[i][n + j] = ExactRat.of(i == j ? 1L : 0L);
            }
        }
        for (int i = 0; i < n; i++) {
            int pivotRow = -1;
            for (int k = i; k < n; k++) {
                if (aug[k][i].num.signum() != 0) {
                    pivotRow = k;
                    break;
                }
            }
            if (pivotRow < 0) {
                throw new RuntimeException("Matrix ist singulär (nicht invertierbar)");
            }
            if (pivotRow != i) {
                ExactRat[] tmp = aug[i];
                aug[i] = aug[pivotRow];
                aug[pivotRow] = tmp;
            }
            ExactRat pivot = aug[i][i];
            for (int j = 0; j < 2 * n; j++) {
                aug[i][j] = aug[i][j].div(pivot);
                if (aug[i][j].num.bitLength() > 2048 || aug[i][j].den.bitLength() > 2048) {
                    return null;
                }
            }
            for (int k = 0; k < n; k++) {
                if (k != i) {
                    ExactRat factor = aug[k][i];
                    if (factor.num.signum() != 0) {
                        for (int j = 0; j < 2 * n; j++) {
                            aug[k][j] = aug[k][j].sub(factor.mul(aug[i][j]));
                            if (aug[k][j].num.bitLength() > 2048 || aug[k][j].den.bitLength() > 2048) {
                                return null;
                            }
                        }
                    }
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double val = aug[i][n + j].toDouble();
                if (!Double.isFinite(val)) {
                    throw new RuntimeException("Matrix-Überlauf");
                }
                inv[i][j] = (val == 0.0) ? 0.0 : val;
            }
        }
        return inv;
    }

    private double[][] matrixInv(double[][] m) {
        int n = m.length;
        if (n == 0 || m[0].length != n) throw new RuntimeException("Inverse erfordert quadratische Matrix");
        checkMatrixFinite(m);
        double[][] exactInv = tryExactMatrixInv(m);
        if (exactInv != null) {
            return exactInv;
        }
        double[] rowScale = new double[n];
        double[] colScale = new double[n];
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            double rMax = 0.0;
            for (int j = 0; j < n; j++) {
                double a = Math.abs(m[i][j]);
                rMax = Math.max(rMax, a);
                colScale[j] = Math.max(colScale[j], a);
            }
            if (rMax == 0.0) {
                throw new RuntimeException("Matrix ist singulär (nicht invertierbar)");
            }
            rowScale[i] = rMax;
            System.arraycopy(m[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1.0;
        }
        for (int i = 0; i < n; i++) {
            int pivotRow = i;
            double sI = 0.0;
            for (int col = i; col < n; col++) sI = Math.max(sI, Math.abs(aug[i][col]));
            double bestRatio = sI > 0.0 ? Math.abs(aug[i][i]) / sI : 0.0;
            for (int k = i + 1; k < n; k++) {
                double sK = 0.0;
                for (int col = i; col < n; col++) sK = Math.max(sK, Math.abs(aug[k][col]));
                double ratio = sK > 0.0 ? Math.abs(aug[k][i]) / sK : 0.0;
                if (ratio > bestRatio) {
                    bestRatio = ratio;
                    pivotRow = k;
                }
            }
            double condDenom = Math.min(rowScale[pivotRow], colScale[i]);
            double condRatio = condDenom > 0.0 ? Math.abs(aug[pivotRow][i]) / condDenom : 0.0;
            if (!Double.isFinite(bestRatio) || condRatio <= 1e-14) {
                throw new RuntimeException("Matrix ist singulär (nicht invertierbar)");
            }
            if (pivotRow != i) {
                double[] tmp = aug[i];
                aug[i] = aug[pivotRow];
                aug[pivotRow] = tmp;
                double sTmp = rowScale[i];
                rowScale[i] = rowScale[pivotRow];
                rowScale[pivotRow] = sTmp;
            }
            double pivot = aug[i][i];
            for (int j = 0; j < 2 * n; j++) {
                double q = aug[i][j] / pivot;
                if (!Double.isFinite(q)) throw new RuntimeException("Matrix-Überlauf");
                aug[i][j] = (q == 0.0) ? 0.0 : q;
            }
            for (int k = 0; k < n; k++) {
                if (k != i) {
                    double factor = aug[k][i];
                    if (factor != 0.0) {
                        for (int j = 0; j < 2 * n; j++) {
                            double old = aug[k][j];
                            double subTerm = factor * aug[i][j];
                            if (!Double.isFinite(subTerm)) throw new RuntimeException("Matrix-Überlauf");
                            double updated = old - subTerm;
                            if (!Double.isFinite(updated)) throw new RuntimeException("Matrix-Überlauf");
                            aug[k][j] = (updated == 0.0) ? 0.0 : updated;
                        }
                    }
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double val = aug[i][n + j];
                if (!Double.isFinite(val)) throw new RuntimeException("Matrix-Überlauf");
                inv[i][j] = (val == 0.0) ? 0.0 : val;
            }
        }
        return inv;
    }

    private double[][] matrixPow(double[][] m, double expScalar) {
        int n = m.length;
        if (n == 0 || m[0].length != n) throw new RuntimeException("Matrixpotenz erfordert quadratische Matrix");
        if (!Double.isFinite(expScalar) || Math.abs(expScalar - Math.rint(expScalar)) > 1e-12 || Math.abs(expScalar) > 1000.0) {
            throw new RuntimeException("Matrixexponent muss eine ganze Zahl zwischen -1000 und 1000 sein");
        }
        long exp = Math.round(expScalar);
        double[][] res = new double[n][n];
        for (int i = 0; i < n; i++) res[i][i] = 1.0;
        if (exp == 0) return res;
        double[][] base = (exp < 0) ? matrixInv(m) : m;
        long p = Math.abs(exp);
        while (p > 0) {
            checkInterrupted();
            if ((p & 1L) != 0L) res = multiplyMatrices(res, base);
            p >>= 1;
            if (p > 0) base = multiplyMatrices(base, base);
        }
        return res;
    }

    private boolean isMatrixVariableRef(String name) {
        return (name.equalsIgnoreCase("ans") && lastMatrixAns != null)
                || matrixVariables.containsKey(name);
    }

    private boolean containsMatrixExpression(AstNode node) {
        if (node instanceof AstVar) return isMatrixVariableRef(((AstVar) node).name);
        if (node instanceof AstNeg) return containsMatrixExpression(((AstNeg) node).inner);
        if (node instanceof AstAdd) return containsMatrixExpression(((AstAdd) node).left) || containsMatrixExpression(((AstAdd) node).right);
        if (node instanceof AstSub) return containsMatrixExpression(((AstSub) node).left) || containsMatrixExpression(((AstSub) node).right);
        if (node instanceof AstMul) return containsMatrixExpression(((AstMul) node).left) || containsMatrixExpression(((AstMul) node).right);
        if (node instanceof AstDiv) return containsMatrixExpression(((AstDiv) node).left) || containsMatrixExpression(((AstDiv) node).right);
        if (node instanceof AstPow) return containsMatrixExpression(((AstPow) node).base) || containsMatrixExpression(((AstPow) node).exp);
        if (node instanceof AstFunc) {
            AstFunc f = (AstFunc) node;
            if (f.name.equals("det")) return false;
            if (f.name.equals("[]") || isMatrixTransform(f)) return true;
            for (AstNode a : f.args) {
                if (containsMatrixExpression(a)) return true;
            }
        }
        return false;
    }

    private static double[][] multiplyMatrices(double[][] left, double[][] right) {
        int rows = left.length;
        int inner = left[0].length;
        if (right.length != inner) {
            throw new RuntimeException("Matrixdimensionen inkompatibel");
        }
        int cols = right[0].length;
        double[][] result = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double sum = 0.0;
                double absSum = 0.0;
                for (int k = 0; k < inner; k++) {
                    double prod = left[r][k] * right[k][c];
                    if (!Double.isFinite(prod)) {
                        throw new RuntimeException("Matrix-Überlauf");
                    }
                    sum += prod;
                    if (!Double.isFinite(sum)) {
                        throw new RuntimeException("Matrix-Überlauf");
                    }
                    absSum += Math.abs(prod);
                }
                if (!Double.isFinite(sum)) {
                    throw new RuntimeException("Matrix-Überlauf");
                }
                result[r][c] = (sum == 0.0) ? 0.0 : sum;
            }
        }
        return result;
    }

    private static double[][] scaleMatrix(double[][] m, double s) {
        double[][] res = new double[m.length][m[0].length];
        for (int r = 0; r < m.length; r++) {
            for (int c = 0; c < m[r].length; c++) {
                double val = m[r][c] * s;
                if (!Double.isFinite(val)) {
                    throw new RuntimeException("Matrix-Überlauf");
                }
                res[r][c] = (val == 0.0) ? 0.0 : val;
            }
        }
        return res;
    }

    private static final class MatrixOrScalar {
        final double[][] matrix;
        final double scalar;
        final boolean isMatrix;
        MatrixOrScalar(double[][] matrix) { this.matrix = matrix; this.scalar = 0.0; this.isMatrix = true; }
        MatrixOrScalar(double scalar) { this.matrix = null; this.scalar = scalar; this.isMatrix = false; }
    }

    private double[][] evalMatrixExpr(String expression, Map<String, Value> overrides) {
        String s = expression.trim();
        AstNode ast = AstParser.tryParse(s);
        if (ast != null) {
            return evalMatrixAst(ast, overrides);
        }
        int depth = 0;
        int split = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '[' || ch == '(') depth++;
            else if (ch == ']' || ch == ')') depth--;
            else if (depth == 0 && (ch == '+' || ch == '-' || ch == '−')) split = i;
        }
        if (split >= 0) {
            double[][] left = evalMatrixExpr(s.substring(0, split), overrides);
            double[][] right = evalMatrixExpr(s.substring(split + 1), overrides);
            return combineMatrices(left, right, s.charAt(split) == '+' ? 1 : -1);
        }

        if (s.startsWith("inv(") && s.endsWith(")")) {
            return matrixInv(evalMatrixExpr(s.substring(4, s.length() - 1), overrides));
        }
        if ((s.startsWith("trans(") || s.startsWith("transpose(")) && s.endsWith(")")) {
            return transposeMatrix(evalMatrixExpr(s.substring(s.indexOf('(') + 1, s.length() - 1), overrides));
        }
        return parseMatrix(s, overrides);
    }

    private static double[][] combineMatrices(double[][] left, double[][] right, int sign) {
        if (left.length != right.length || left[0].length != right[0].length) {
            throw new RuntimeException("Matrixdimensionen stimmen nicht überein");
        }
        double[][] res = new double[left.length][left[0].length];
        for (int r = 0; r < left.length; r++) {
            for (int c = 0; c < left[r].length; c++) {
                double val = left[r][c] + sign * right[r][c];
                if (!Double.isFinite(val)) {
                    throw new RuntimeException("Matrix-Überlauf");
                }
                res[r][c] = (val == 0.0) ? 0.0 : val;
            }
        }
        return res;
    }

    private static double[][] transposeMatrix(double[][] matrix) {
        double[][] transpose = new double[matrix[0].length][matrix.length];
        for (int r = 0; r < matrix.length; r++) {
            for (int c = 0; c < matrix[r].length; c++) transpose[c][r] = matrix[r][c];
        }
        return transpose;
    }

    private static List<AstNode> matrixItems(AstNode node) {
        if (node instanceof AstFunc && ((AstFunc) node).name.equals("[]")) {
            return ((AstFunc) node).args;
        }
        throw new RuntimeException("Matrix muss mit [[...]] umschlossen sein");
    }

    private void validatePlotMatrix(AstNode node, Set<String> scope) {
        if (node instanceof AstAdd) {
            validatePlotMatrix(((AstAdd) node).left, scope);
            validatePlotMatrix(((AstAdd) node).right, scope);
        } else if (node instanceof AstSub) {
            validatePlotMatrix(((AstSub) node).left, scope);
            validatePlotMatrix(((AstSub) node).right, scope);
        } else if (node instanceof AstMul) {
            AstMul m = (AstMul) node;
            boolean leftMat = containsMatrixExpression(m.left);
            boolean rightMat = containsMatrixExpression(m.right);
            if (leftMat && rightMat) {
                validatePlotMatrix(m.left, scope);
                validatePlotMatrix(m.right, scope);
            } else if (leftMat) {
                validatePlotMatrix(m.left, scope);
                validatePlotAst(m.right, scope);
            } else if (rightMat) {
                validatePlotAst(m.left, scope);
                validatePlotMatrix(m.right, scope);
            } else {
                throw new RuntimeException("Matrix erwartet");
            }
        } else if (node instanceof AstPow) {
            AstPow p = (AstPow) node;
            validatePlotMatrix(p.base, scope);
            validatePlotAst(p.exp, scope);
        } else if (node instanceof AstNeg) {
            validatePlotMatrix(((AstNeg) node).inner, scope);
        } else if (isMatrixTransform(node)) {
            validatePlotMatrix(((AstFunc) node).args.get(0), scope);
        } else {
            List<AstNode> rows = matrixItems(node);
            int columns = matrixItems(rows.get(0)).size();
            for (AstNode row : rows) {
                List<AstNode> cells = matrixItems(row);
                if (cells.size() != columns) throw new RuntimeException("Matrixzeilen ungleich lang");
                for (AstNode cell : cells) validatePlotAst(cell, scope);
            }
        }
    }

    private static boolean isMatrixTransform(AstNode node) {
        if (!(node instanceof AstFunc)) return false;
        AstFunc call = (AstFunc) node;
        return call.args.size() == 1
                && (call.name.equals("inv") || call.name.equals("trans") || call.name.equals("transpose"));
    }

    private double[][] evalMatrixAst(AstNode node, Map<String, Value> overrides) {
        MatrixOrScalar res = evalMatrixOrScalarAst(node, overrides);
        if (!res.isMatrix) {
            throw new RuntimeException("Matrix erwartet");
        }
        return res.matrix;
    }

    private MatrixOrScalar evalMatrixOrScalarAst(AstNode node, Map<String, Value> overrides) {
        if (node instanceof AstNeg) {
            MatrixOrScalar inner = evalMatrixOrScalarAst(((AstNeg) node).inner, overrides);
            return inner.isMatrix
                    ? new MatrixOrScalar(scaleMatrix(inner.matrix, -1.0))
                    : new MatrixOrScalar(-inner.scalar);
        }
        if (node instanceof AstAdd) {
            MatrixOrScalar l = evalMatrixOrScalarAst(((AstAdd) node).left, overrides);
            MatrixOrScalar r = evalMatrixOrScalarAst(((AstAdd) node).right, overrides);
            if (!l.isMatrix || !r.isMatrix) throw new RuntimeException("Matrixdimensionen stimmen nicht überein");
            return new MatrixOrScalar(combineMatrices(l.matrix, r.matrix, 1));
        }
        if (node instanceof AstSub) {
            MatrixOrScalar l = evalMatrixOrScalarAst(((AstSub) node).left, overrides);
            MatrixOrScalar r = evalMatrixOrScalarAst(((AstSub) node).right, overrides);
            if (!l.isMatrix || !r.isMatrix) throw new RuntimeException("Matrixdimensionen stimmen nicht überein");
            return new MatrixOrScalar(combineMatrices(l.matrix, r.matrix, -1));
        }
        if (node instanceof AstMul) {
            MatrixOrScalar l = evalMatrixOrScalarAst(((AstMul) node).left, overrides);
            MatrixOrScalar r = evalMatrixOrScalarAst(((AstMul) node).right, overrides);
            if (l.isMatrix && r.isMatrix) {
                return new MatrixOrScalar(multiplyMatrices(l.matrix, r.matrix));
            } else if (l.isMatrix) {
                return new MatrixOrScalar(scaleMatrix(l.matrix, r.scalar));
            } else if (r.isMatrix) {
                return new MatrixOrScalar(scaleMatrix(r.matrix, l.scalar));
            } else {
                return new MatrixOrScalar(l.scalar * r.scalar);
            }
        }
        if (node instanceof AstDiv) {
            MatrixOrScalar l = evalMatrixOrScalarAst(((AstDiv) node).left, overrides);
            MatrixOrScalar r = evalMatrixOrScalarAst(((AstDiv) node).right, overrides);
            if (!r.isMatrix && l.isMatrix) {
                if (r.scalar == 0.0 || !Double.isFinite(r.scalar)) throw new ArithmeticException("Division by zero");
                return new MatrixOrScalar(scaleMatrix(l.matrix, 1.0 / r.scalar));
            }
            if (l.isMatrix || r.isMatrix) throw new RuntimeException("Ungültige Matrixdivision");
        }
        if (node instanceof AstPow) {
            AstPow p = (AstPow) node;
            MatrixOrScalar l = evalMatrixOrScalarAst(p.base, overrides);
            MatrixOrScalar r = evalMatrixOrScalarAst(p.exp, overrides);
            if (l.isMatrix && !r.isMatrix) {
                return new MatrixOrScalar(matrixPow(l.matrix, r.scalar));
            }
            if (l.isMatrix || r.isMatrix) throw new RuntimeException("Ungültige Matrixpotenz");
            Value baseV = Value.fromFloat(l.scalar);
            Value expV = Value.fromFloat(r.scalar);
            Value powV = baseV.pow(expV);
            if (!powV.c.isReal() || !Double.isFinite(powV.c.re)) {
                throw new RuntimeException("Skalar muss eine endliche reelle Zahl sein");
            }
            return new MatrixOrScalar(powV.c.re);
        }
        if (isMatrixTransform(node)) {
            AstFunc call = (AstFunc) node;
            double[][] matrix = evalMatrixAst(call.args.get(0), overrides);
            return new MatrixOrScalar(call.name.equals("inv") ? matrixInv(matrix) : transposeMatrix(matrix));
        }
        if (node instanceof AstFunc && ((AstFunc) node).name.equals("[]")) {
            List<AstNode> rows = matrixItems(node);
            if (rows.isEmpty()) throw new RuntimeException("Matrix darf nicht leer sein");
            int expectedCols = -1;
            double[][] matrix = new double[rows.size()][];
            for (int r = 0; r < rows.size(); r++) {
                List<AstNode> cells = matrixItems(rows.get(r));
                if (expectedCols < 0) expectedCols = cells.size();
                else if (cells.size() != expectedCols) throw new RuntimeException("Matrixzeilen ungleich lang");
                matrix[r] = new double[cells.size()];
                for (int c = 0; c < cells.size(); c++) {
                    Value value = evalAst(cells.get(c), overrides);
                    if (!value.c.isReal() || !Double.isFinite(value.c.re)) {
                        throw new RuntimeException("Matrixzellen müssen endliche reelle Zahlen sein");
                    }
                    matrix[r][c] = value.c.re;
                }
            }
            return new MatrixOrScalar(matrix);
        }
        if (node instanceof AstVar) {
            String name = ((AstVar) node).name;
            if (overrides == null || !overrides.containsKey(name)) {
                if (name.equalsIgnoreCase("ans") && lastMatrixAns != null) {
                    return new MatrixOrScalar(cloneMatrix(lastMatrixAns));
                }
                double[][] matVar = matrixVariables.get(name);
                if (matVar != null) {
                    return new MatrixOrScalar(cloneMatrix(matVar));
                }
            }
        }
        Value v = evalAst(node, overrides);
        if (!v.c.isReal() || !Double.isFinite(v.c.re)) {
            throw new RuntimeException("Skalar muss eine endliche reelle Zahl sein");
        }
        return new MatrixOrScalar(v.c.re);
    }

    private double[][] parseMatrix(String expression, Map<String, Value> overrides) {
        List<String> rows = splitMatrixItems(expression);
        double[][] matrix = new double[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = splitMatrixItems(rows.get(r).trim());
            if (r > 0 && cells.size() != matrix[0].length) {
                throw new RuntimeException("Matrixzeilen ungleich lang");
            }
            matrix[r] = new double[cells.size()];
            for (int c = 0; c < cells.size(); c++) {
                Value value = evalExpression(cells.get(c).trim(), overrides);
                if (!value.c.isReal() || !Double.isFinite(value.c.re)) {
                    throw new RuntimeException("Matrixzellen müssen endliche reelle Zahlen sein");
                }
                matrix[r][c] = value.c.re;
            }
        }
        return matrix;
    }

    private static List<String> splitMatrixItems(String brackets) {
        if (!brackets.startsWith("[") || !brackets.endsWith("]")) {
            throw new RuntimeException("Matrix muss mit [[...]] umschlossen sein");
        }
        List<String> parts = new ArrayList<>();
        int start = 1;
        int depth = 0;
        for (int i = 1; i < brackets.length() - 1; i++) {
            char ch = brackets.charAt(i);
            if (ch == '[' || ch == '(') depth++;
            else if (ch == ']' || ch == ')') depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(brackets.substring(start, i));
                start = i + 1;
            }
            if (depth < 0) throw new RuntimeException("Ungültige Matrixklammern");
        }
        if (depth != 0) throw new RuntimeException("Ungültige Matrixklammern");
        parts.add(brackets.substring(start, brackets.length() - 1));
        return parts;
    }

    private static ExactRat parseScientificExactRat(String s) {
        if (s == null) return null;
        int eIdx = s.indexOf('e');
        if (eIdx < 0) eIdx = s.indexOf('E');
        if (eIdx < 0) return null;
        String mant = s.substring(0, eIdx);
        String expStr = s.substring(eIdx + 1);
        if (mant.isEmpty() || expStr.isEmpty()) return null;
        int exp;
        try {
            exp = Integer.parseInt(expStr);
        } catch (NumberFormatException ex) {
            return null;
        }
        boolean neg = false;
        int start = 0;
        if (mant.charAt(0) == '-') {
            neg = true;
            start = 1;
        } else if (mant.charAt(0) == '+') {
            start = 1;
        }
        StringBuilder digits = new StringBuilder(mant.length());
        int fracDigits = 0;
        boolean seenDot = false;
        for (int i = start; i < mant.length(); i++) {
            char ch = mant.charAt(i);
            if (ch == '.') {
                if (seenDot) return null;
                seenDot = true;
            } else if (ch >= '0' && ch <= '9') {
                digits.append(ch);
                if (seenDot) fracDigits++;
            } else {
                return null;
            }
        }
        if (digits.length() == 0 || digits.length() > 40) return null;
        int firstNonZero = 0;
        while (firstNonZero < digits.length() && digits.charAt(firstNonZero) == '0') firstNonZero++;
        if (firstNonZero == digits.length()) return ExactRat.of(0);
        int lastNonZero = digits.length();
        while (lastNonZero > firstNonZero && digits.charAt(lastNonZero - 1) == '0') lastNonZero--;
        int dropped = digits.length() - lastNonZero;
        long scaleLong = (long) exp - fracDigits + dropped;
        if (Math.abs(scaleLong) > 100) return null;
        int scale = (int) scaleLong;
        BigInteger base = new BigInteger(digits.substring(firstNonZero, lastNonZero));
        if (neg) base = base.negate();
        if (scale >= 0) {
            return new ExactRat(base.multiply(BigInteger.TEN.pow(scale)), BigInteger.ONE);
        }
        return new ExactRat(base, BigInteger.TEN.pow(-scale));
    }

    private static Value valueFromExactRat(ExactRat r) {
        if (r == null) return null;
        if (r.den.equals(BigInteger.ONE)) {
            return Value.fromInt(r.num);
        }
        if (r.num.bitLength() < 1000 && r.den.bitLength() < 1000) {
            return Value.fromInt(r.num).div(Value.fromInt(r.den));
        }
        return null;
    }

    private ExactRat evalExactScalarRatAst(AstNode node, Map<String, Value> ov) {
        if (node instanceof AstNum) {
            Value v = ((AstNum) node).val;
            if (v.rat != null) return v.rat;
            return v.c.isReal() ? doubleToExactRat(v.c.re) : null;
        }
        if (node instanceof AstVar) {
            String name = ((AstVar) node).name;
            Value v = null;
            if (ov != null && ov.containsKey(name)) v = ov.get(name);
            else if (name.equalsIgnoreCase("ans")) v = lastAns;
            else v = variables.get(name);
            if (v == null) return null;
            if (v.rat != null) return v.rat;
            return v.c.isReal() ? doubleToExactRat(v.c.re) : null;
        }
        if (node instanceof AstNeg) {
            ExactRat inner = evalExactScalarRatAst(((AstNeg) node).inner, ov);
            return inner != null ? new ExactRat(inner.num.negate(), inner.den) : null;
        }
        if (node instanceof AstAdd) {
            ExactRat l = evalExactScalarRatAst(((AstAdd) node).left, ov);
            ExactRat r = evalExactScalarRatAst(((AstAdd) node).right, ov);
            return (l != null && r != null) ? l.add(r) : null;
        }
        if (node instanceof AstSub) {
            ExactRat l = evalExactScalarRatAst(((AstSub) node).left, ov);
            ExactRat r = evalExactScalarRatAst(((AstSub) node).right, ov);
            return (l != null && r != null) ? l.sub(r) : null;
        }
        if (node instanceof AstMul) {
            ExactRat l = evalExactScalarRatAst(((AstMul) node).left, ov);
            ExactRat r = evalExactScalarRatAst(((AstMul) node).right, ov);
            return (l != null && r != null) ? l.mul(r) : null;
        }
        if (node instanceof AstDiv) {
            ExactRat l = evalExactScalarRatAst(((AstDiv) node).left, ov);
            ExactRat r = evalExactScalarRatAst(((AstDiv) node).right, ov);
            if (l == null || r == null) return null;
            if (r.num.signum() == 0) throw new ArithmeticException("Division by zero");
            return l.div(r);
        }
        if (node instanceof AstPow) {
            ExactRat b = evalExactScalarRatAst(((AstPow) node).base, ov);
            ExactRat e = evalExactScalarRatAst(((AstPow) node).exp, ov);
            if (b == null || e == null || !e.den.equals(BigInteger.ONE)) return null;
            if (e.num.bitLength() > 14) return null;
            int p = e.num.intValue();
            if (Math.abs(p) > 1000) return null;
            if (Math.max(b.num.bitLength(), b.den.bitLength()) * (long) Math.abs(p) > 32768L) return null;
            return exactRatPow(b, p);
        }
        return null;
    }

    private static ExactRat exactRatPow(ExactRat base, int exp) {
        if (exp == 0) return ExactRat.of(1L);
        if (exp < 0) {
            if (base.num.signum() == 0) throw new ArithmeticException("Division by zero");
            int ap = -exp;
            BigInteger num = (base.num.signum() < 0 && (ap & 1) != 0) ? base.den.pow(ap).negate() : base.den.pow(ap);
            return new ExactRat(num, base.num.abs().pow(ap));
        }
        BigInteger num = base.num.pow(exp);
        BigInteger den = base.den.pow(exp);
        return new ExactRat(num, den);
    }

    private static ExactRat[][] doubleMatrixToExact(double[][] m) {
        int rows = m.length;
        int cols = m[0].length;
        ExactRat[][] out = new ExactRat[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ExactRat er = doubleToExactRat(m[r][c]);
                if (er == null) return null;
                out[r][c] = er;
            }
        }
        return out;
    }

    private static ExactRat[][] exactMatrixAddSub(ExactRat[][] a, ExactRat[][] b, int sign) {
        if (a.length != b.length || a[0].length != b[0].length) {
            throw new RuntimeException("Matrixdimensionen stimmen nicht überein");
        }
        int rows = a.length, cols = a[0].length;
        ExactRat[][] res = new ExactRat[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ExactRat v = sign > 0 ? a[r][c].add(b[r][c]) : a[r][c].sub(b[r][c]);
                if (v.num.bitLength() > 16384 || v.den.bitLength() > 16384) return null;
                res[r][c] = v;
            }
        }
        return res;
    }

    private static ExactRat[][] exactMatrixScale(ExactRat[][] m, ExactRat s) {
        int rows = m.length, cols = m[0].length;
        ExactRat[][] res = new ExactRat[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ExactRat v = m[r][c].mul(s);
                if (v.num.bitLength() > 16384 || v.den.bitLength() > 16384) return null;
                res[r][c] = v;
            }
        }
        return res;
    }

    private static ExactRat[][] exactMatrixMul(ExactRat[][] left, ExactRat[][] right) {
        int rows = left.length, inner = left[0].length;
        if (right.length != inner) throw new RuntimeException("Matrixdimensionen inkompatibel");
        int cols = right[0].length;
        ExactRat[][] res = new ExactRat[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                checkInterrupted();
                ExactRat sum = ExactRat.of(0L);
                for (int k = 0; k < inner; k++) {
                    sum = sum.add(left[r][k].mul(right[k][c]));
                    if (sum.num.bitLength() > 16384 || sum.den.bitLength() > 16384) return null;
                }
                res[r][c] = sum;
            }
        }
        return res;
    }

    private static ExactRat[][] exactMatrixInv(ExactRat[][] m) {
        int n = m.length;
        if (n == 0 || m[0].length != n) throw new RuntimeException("Inverse erfordert quadratische Matrix");
        ExactRat[][] aug = new ExactRat[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                aug[i][j] = m[i][j];
                aug[i][n + j] = ExactRat.of(i == j ? 1L : 0L);
            }
        }
        for (int i = 0; i < n; i++) {
            checkInterrupted();
            int pivotRow = -1;
            for (int k = i; k < n; k++) {
                if (aug[k][i].num.signum() != 0) {
                    pivotRow = k;
                    break;
                }
            }
            if (pivotRow < 0) throw new RuntimeException("Matrix ist singulär (nicht invertierbar)");
            if (pivotRow != i) {
                ExactRat[] tmp = aug[i];
                aug[i] = aug[pivotRow];
                aug[pivotRow] = tmp;
            }
            ExactRat pivot = aug[i][i];
            for (int j = 0; j < 2 * n; j++) {
                aug[i][j] = aug[i][j].div(pivot);
                if (aug[i][j].num.bitLength() > 16384 || aug[i][j].den.bitLength() > 16384) return null;
            }
            for (int k = 0; k < n; k++) {
                if (k != i && aug[k][i].num.signum() != 0) {
                    ExactRat factor = aug[k][i];
                    for (int j = 0; j < 2 * n; j++) {
                        aug[k][j] = aug[k][j].sub(factor.mul(aug[i][j]));
                        if (aug[k][j].num.bitLength() > 16384 || aug[k][j].den.bitLength() > 16384) return null;
                    }
                }
            }
        }
        ExactRat[][] inv = new ExactRat[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aug[i], n, inv[i], 0, n);
        return inv;
    }

    private ExactRat[][] tryEvalExactMatrixAst(AstNode node, Map<String, Value> overrides) {
        checkInterrupted();
        if (node instanceof AstVar) {
            String name = ((AstVar) node).name;
            if (overrides == null || !overrides.containsKey(name)) {
                if (name.equalsIgnoreCase("ans") && lastMatrixAns != null) {
                    return doubleMatrixToExact(lastMatrixAns);
                }
                double[][] matVar = matrixVariables.get(name);
                if (matVar != null) {
                    return doubleMatrixToExact(matVar);
                }
            }
            return null;
        }
        if (node instanceof AstNeg) {
            ExactRat[][] inner = tryEvalExactMatrixAst(((AstNeg) node).inner, overrides);
            return inner != null ? exactMatrixScale(inner, ExactRat.of(-1L)) : null;
        }
        if (node instanceof AstAdd) {
            ExactRat[][] l = tryEvalExactMatrixAst(((AstAdd) node).left, overrides);
            ExactRat[][] r = tryEvalExactMatrixAst(((AstAdd) node).right, overrides);
            return (l != null && r != null) ? exactMatrixAddSub(l, r, 1) : null;
        }
        if (node instanceof AstSub) {
            ExactRat[][] l = tryEvalExactMatrixAst(((AstSub) node).left, overrides);
            ExactRat[][] r = tryEvalExactMatrixAst(((AstSub) node).right, overrides);
            return (l != null && r != null) ? exactMatrixAddSub(l, r, -1) : null;
        }
        if (node instanceof AstMul) {
            AstMul mul = (AstMul) node;
            ExactRat[][] lm = tryEvalExactMatrixAst(mul.left, overrides);
            ExactRat[][] rm = tryEvalExactMatrixAst(mul.right, overrides);
            if (lm != null && rm != null) return exactMatrixMul(lm, rm);
            if (lm != null) {
                ExactRat s = evalExactScalarRatAst(mul.right, overrides);
                return s != null ? exactMatrixScale(lm, s) : null;
            }
            if (rm != null) {
                ExactRat s = evalExactScalarRatAst(mul.left, overrides);
                return s != null ? exactMatrixScale(rm, s) : null;
            }
            return null;
        }
        if (node instanceof AstDiv) {
            AstDiv div = (AstDiv) node;
            ExactRat[][] lm = tryEvalExactMatrixAst(div.left, overrides);
            if (lm == null) return null;
            ExactRat s = evalExactScalarRatAst(div.right, overrides);
            if (s == null) return null;
            if (s.num.signum() == 0) throw new ArithmeticException("Division by zero");
            return exactMatrixScale(lm, ExactRat.of(1L).div(s));
        }
        if (node instanceof AstPow) {
            AstPow pow = (AstPow) node;
            ExactRat[][] m = tryEvalExactMatrixAst(pow.base, overrides);
            if (m == null) return null;
            int n = m.length;
            if (n == 0 || m[0].length != n) throw new RuntimeException("Matrixpotenz erfordert quadratische Matrix");
            ExactRat e = evalExactScalarRatAst(pow.exp, overrides);
            if (e == null || !e.den.equals(BigInteger.ONE) || e.num.bitLength() > 14) return null;
            int exp = e.num.intValue();
            if (Math.abs(exp) > 1000) {
                throw new RuntimeException("Matrixexponent muss eine ganze Zahl zwischen -1000 und 1000 sein");
            }
            ExactRat[][] res = new ExactRat[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) res[i][j] = ExactRat.of(i == j ? 1L : 0L);
            }
            if (exp == 0) return res;
            ExactRat[][] base = (exp < 0) ? exactMatrixInv(m) : m;
            if (base == null) return null;
            int p = Math.abs(exp);
            while (p > 0) {
                checkInterrupted();
                if ((p & 1) != 0) {
                    res = exactMatrixMul(res, base);
                    if (res == null) return null;
                }
                p >>= 1;
                if (p > 0) {
                    base = exactMatrixMul(base, base);
                    if (base == null) return null;
                }
            }
            return res;
        }
        if (isMatrixTransform(node)) {
            AstFunc call = (AstFunc) node;
            ExactRat[][] m = tryEvalExactMatrixAst(call.args.get(0), overrides);
            if (m == null) return null;
            if (call.name.equals("inv")) return exactMatrixInv(m);
            int rows = m.length, cols = m[0].length;
            ExactRat[][] t = new ExactRat[cols][rows];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) t[c][r] = m[r][c];
            }
            return t;
        }
        if (node instanceof AstFunc && ((AstFunc) node).name.equals("[]")) {
            List<AstNode> rows = matrixItems(node);
            if (rows.isEmpty()) throw new RuntimeException("Matrix darf nicht leer sein");
            int expectedCols = -1;
            ExactRat[][] mat = new ExactRat[rows.size()][];
            for (int r = 0; r < rows.size(); r++) {
                List<AstNode> cells = matrixItems(rows.get(r));
                if (expectedCols < 0) expectedCols = cells.size();
                else if (cells.size() != expectedCols) throw new RuntimeException("Matrixzeilen ungleich lang");
                mat[r] = new ExactRat[cells.size()];
                for (int c = 0; c < cells.size(); c++) {
                    ExactRat cell = evalExactScalarRatAst(cells.get(c), overrides);
                    if (cell == null) return null;
                    mat[r][c] = cell;
                }
            }
            return mat;
        }
        return null;
    }

    private static ExactRat exactMatrixDet(ExactRat[][] m) {
        int n = m.length;
        if (n == 0 || m[0].length != n) throw new RuntimeException("Determinante erfordert quadratische Matrix");
        ExactRat[][] a = new ExactRat[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(m[i], 0, a[i], 0, n);
        ExactRat det = ExactRat.of(1L);
        boolean neg = false;
        for (int i = 0; i < n; i++) {
            checkInterrupted();
            int pivotRow = -1;
            for (int k = i; k < n; k++) {
                if (a[k][i].num.signum() != 0) {
                    pivotRow = k;
                    break;
                }
            }
            if (pivotRow < 0) return ExactRat.of(0L);
            if (pivotRow != i) {
                ExactRat[] tmp = a[i];
                a[i] = a[pivotRow];
                a[pivotRow] = tmp;
                neg = !neg;
            }
            ExactRat pivot = a[i][i];
            det = det.mul(pivot);
            if (det.num.bitLength() > 32768 || det.den.bitLength() > 32768) return null;
            for (int k = i + 1; k < n; k++) {
                if (a[k][i].num.signum() == 0) continue;
                ExactRat factor = a[k][i].div(pivot);
                for (int j = i + 1; j < n; j++) {
                    if (a[i][j].num.signum() != 0) {
                        a[k][j] = a[k][j].sub(factor.mul(a[i][j]));
                        if (a[k][j].num.bitLength() > 32768 || a[k][j].den.bitLength() > 32768) return null;
                    }
                }
            }
        }
        return neg ? new ExactRat(det.num.negate(), det.den) : det;
    }

    private Value tryEvalExactDetAst(AstNode arg, Map<String, Value> overrides) {
        if (arg instanceof AstPow) {
            AstPow pow = (AstPow) arg;
            ExactRat[][] baseMat = tryEvalExactMatrixAst(pow.base, overrides);
            ExactRat e = evalExactScalarRatAst(pow.exp, overrides);
            if (baseMat != null && e != null && e.den.equals(BigInteger.ONE) && e.num.bitLength() <= 14) {
                int n = baseMat.length;
                if (n == 0 || baseMat[0].length != n) throw new RuntimeException("Matrixpotenz erfordert quadratische Matrix");
                int exp = e.num.intValue();
                if (Math.abs(exp) > 1000) {
                    throw new RuntimeException("Matrixexponent muss eine ganze Zahl zwischen -1000 und 1000 sein");
                }
                if (exp == 0) return Value.fromInt(1);
                ExactRat baseDet = exactMatrixDet(baseMat);
                if (baseDet != null) {
                    if (exp < 0 && baseDet.num.signum() == 0) {
                        throw new RuntimeException("Matrix ist singulär (nicht invertierbar)");
                    }
                    ExactRat res = exactRatPow(baseDet, exp);
                    Value v = valueFromExactRat(res);
                    if (v != null) return v;
                }
            }
        }
        ExactRat[][] mat = tryEvalExactMatrixAst(arg, overrides);
        if (mat == null) return null;
        ExactRat res = exactMatrixDet(mat);
        return valueFromExactRat(res);
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
            if (maxVal == 0.0) return 0.0;
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

    public String formatMatrixReusable(double[][] mat) {
        if (mat == null || mat.length == 0) {
            return "";
        }
        return formatMatrix(mat, true);
    }

    private String formatMatrix(double[][] mat, boolean reusable) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int r = 0; r < mat.length; r++) {
            if (r > 0) sb.append(", ");
            sb.append("[");
            for (int c = 0; c < mat[r].length; c++) {
                if (c > 0) sb.append(", ");
                sb.append(reusable ? Double.toString(mat[r][c]) : formatDecimal(mat[r][c]));
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    // --- Abstract Syntax Tree (AST) for Symbolic & Exact Calculations ---
    abstract static class AstNode {
        AstNode diff(String var) { return diff(var, true); }
        abstract AstNode diff(String var, boolean radiansMode);
        AstNode integrate(String var) { return integrate(var, true); }
        abstract AstNode integrate(String var, boolean radiansMode);
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

        static boolean isNegRealNum(AstNode node) {
            if (!(node instanceof AstNum)) return false;
            Value v = ((AstNum) node).val;
            if (v.rat != null) return v.rat.num.signum() < 0;
            return v.c.im == 0.0 && v.c.re < 0.0;
        }

        static boolean isNegTerm(AstNode node) {
            return node instanceof AstNeg || isNegRealNum(node);
        }

        static Value exactRatSqrt(ExactRat r) {
            if (r == null || r.num.signum() <= 0 || r.den.signum() <= 0) return null;
            if (r.num.bitLength() > 52 || r.den.bitLength() > 52) return null;
            long sn = Math.round(Math.sqrt((double) r.num.longValue()));
            long sd = Math.round(Math.sqrt((double) r.den.longValue()));
            if (sn > 0 && sd > 0 && sn * sn == r.num.longValue() && sd * sd == r.den.longValue()) {
                return Value.fromExactRat(new ExactRat(BigInteger.valueOf(sn), BigInteger.valueOf(sd)));
            }
            return null;
        }

        static String wrapIf(AstNode node, boolean cond) {
            String s = node.toDisplayString();
            return cond ? "(" + s + ")" : s;
        }

        static ExactRat decimalToExactRat(double v) {
            if (!Double.isFinite(v)) return null;
            java.math.BigDecimal bd = java.math.BigDecimal.valueOf(v).stripTrailingZeros();
            if (bd.scale() <= 0) {
                if (Math.abs(v) > 1e15) return null;
                return new ExactRat(bd.toBigInteger(), BigInteger.ONE);
            }
            if (bd.scale() > 8) return null;
            return new ExactRat(bd.unscaledValue(), BigInteger.TEN.pow(bd.scale()));
        }

        static ExactRat evalConstantRat(AstNode node) {
            if (node instanceof AstNum) {
                Value v = ((AstNum) node).val;
                return v.rat != null ? v.rat : decimalToExactRat(v.c.re);
            }
            if (node instanceof AstNeg) {
                ExactRat r = evalConstantRat(((AstNeg) node).inner);
                return r != null ? new ExactRat(r.num.negate(), r.den) : null;
            }
            if (node instanceof AstAdd) {
                ExactRat l = evalConstantRat(((AstAdd) node).left);
                ExactRat r = evalConstantRat(((AstAdd) node).right);
                return (l != null && r != null) ? l.add(r) : null;
            }
            if (node instanceof AstSub) {
                ExactRat l = evalConstantRat(((AstSub) node).left);
                ExactRat r = evalConstantRat(((AstSub) node).right);
                return (l != null && r != null) ? l.sub(r) : null;
            }
            if (node instanceof AstMul) {
                ExactRat l = evalConstantRat(((AstMul) node).left);
                ExactRat r = evalConstantRat(((AstMul) node).right);
                return (l != null && r != null) ? l.mul(r) : null;
            }
            if (node instanceof AstDiv) {
                ExactRat l = evalConstantRat(((AstDiv) node).left);
                ExactRat r = evalConstantRat(((AstDiv) node).right);
                return (l != null && r != null && r.num.signum() != 0) ? l.div(r) : null;
            }
            return null;
        }

        static final class LinearMatch {
            final AstNode a;
            final AstNode b;
            LinearMatch(AstNode a, AstNode b) { this.a = a; this.b = b; }
        }

        static LinearMatch matchLinear(AstNode expr, String var) {
            if (!expr.containsVar(var)) return null;
            if (expr instanceof AstVar && ((AstVar) expr).name.equals(var)) {
                return new LinearMatch(new AstNum(1), new AstNum(0));
            }
            if (expr instanceof AstNeg) {
                LinearMatch inner = matchLinear(((AstNeg) expr).inner, var);
                if (inner == null) return null;
                return new LinearMatch(new AstNeg(inner.a).simplify(), new AstNeg(inner.b).simplify());
            }
            if (expr instanceof AstAdd) {
                AstAdd add = (AstAdd) expr;
                if (!add.right.containsVar(var)) {
                    LinearMatch l = matchLinear(add.left, var);
                    if (l != null) return new LinearMatch(l.a, new AstAdd(l.b, add.right).simplify());
                } else if (!add.left.containsVar(var)) {
                    LinearMatch r = matchLinear(add.right, var);
                    if (r != null) return new LinearMatch(r.a, new AstAdd(add.left, r.b).simplify());
                }
            }
            if (expr instanceof AstSub) {
                AstSub sub = (AstSub) expr;
                if (!sub.right.containsVar(var)) {
                    LinearMatch l = matchLinear(sub.left, var);
                    if (l != null) return new LinearMatch(l.a, new AstSub(l.b, sub.right).simplify());
                } else if (!sub.left.containsVar(var)) {
                    LinearMatch r = matchLinear(sub.right, var);
                    if (r != null) return new LinearMatch(new AstNeg(r.a).simplify(), new AstSub(sub.left, r.b).simplify());
                }
            }
            if (expr instanceof AstMul) {
                AstMul mul = (AstMul) expr;
                if (!mul.left.containsVar(var)) {
                    LinearMatch r = matchLinear(mul.right, var);
                    if (r != null) return new LinearMatch(new AstMul(mul.left, r.a).simplify(), new AstMul(mul.left, r.b).simplify());
                } else if (!mul.right.containsVar(var)) {
                    LinearMatch l = matchLinear(mul.left, var);
                    if (l != null) return new LinearMatch(new AstMul(mul.right, l.a).simplify(), new AstMul(mul.right, l.b).simplify());
                }
            }
            if (expr instanceof AstDiv) {
                AstDiv div = (AstDiv) expr;
                if (!div.right.containsVar(var)) {
                    LinearMatch l = matchLinear(div.left, var);
                    if (l != null) return new LinearMatch(new AstDiv(l.a, div.right).simplify(), new AstDiv(l.b, div.right).simplify());
                }
            }
            return null;
        }

        static final class QuadraticMatch {
            final AstNode a;
            final AstNode b;
            final AstNode c;
            QuadraticMatch(AstNode a, AstNode b, AstNode c) { this.a = a; this.b = b; this.c = c; }
        }

        static QuadraticMatch matchQuadratic(AstNode expr, String var) {
            if (!expr.containsVar(var)) {
                return new QuadraticMatch(new AstNum(0), new AstNum(0), expr);
            }
            LinearMatch lin = matchLinear(expr, var);
            if (lin != null) {
                return new QuadraticMatch(new AstNum(0), lin.a, lin.b);
            }
            if (expr instanceof AstNeg) {
                QuadraticMatch inner = matchQuadratic(((AstNeg) expr).inner, var);
                if (inner == null) return null;
                return new QuadraticMatch(
                        new AstNeg(inner.a).simplify(),
                        new AstNeg(inner.b).simplify(),
                        new AstNeg(inner.c).simplify()
                );
            }
            if (expr instanceof AstAdd) {
                AstAdd add = (AstAdd) expr;
                QuadraticMatch l = matchQuadratic(add.left, var);
                QuadraticMatch r = matchQuadratic(add.right, var);
                if (l == null || r == null) return null;
                return new QuadraticMatch(
                        new AstAdd(l.a, r.a).simplify(),
                        new AstAdd(l.b, r.b).simplify(),
                        new AstAdd(l.c, r.c).simplify()
                );
            }
            if (expr instanceof AstSub) {
                AstSub sub = (AstSub) expr;
                QuadraticMatch l = matchQuadratic(sub.left, var);
                QuadraticMatch r = matchQuadratic(sub.right, var);
                if (l == null || r == null) return null;
                return new QuadraticMatch(
                        new AstSub(l.a, r.a).simplify(),
                        new AstSub(l.b, r.b).simplify(),
                        new AstSub(l.c, r.c).simplify()
                );
            }
            if (expr instanceof AstMul) {
                AstMul mul = (AstMul) expr;
                if (!mul.left.containsVar(var)) {
                    QuadraticMatch r = matchQuadratic(mul.right, var);
                    if (r == null) return null;
                    return new QuadraticMatch(
                            new AstMul(mul.left, r.a).simplify(),
                            new AstMul(mul.left, r.b).simplify(),
                            new AstMul(mul.left, r.c).simplify()
                    );
                }
                if (!mul.right.containsVar(var)) {
                    QuadraticMatch l = matchQuadratic(mul.left, var);
                    if (l == null) return null;
                    return new QuadraticMatch(
                            new AstMul(l.a, mul.right).simplify(),
                            new AstMul(l.b, mul.right).simplify(),
                            new AstMul(l.c, mul.right).simplify()
                    );
                }
                LinearMatch l = matchLinear(mul.left, var);
                LinearMatch r = matchLinear(mul.right, var);
                if (l == null || r == null) return null;
                AstNode qa = new AstMul(l.a, r.a).simplify();
                AstNode qb = new AstAdd(
                        new AstMul(l.a, r.b).simplify(),
                        new AstMul(l.b, r.a).simplify()
                ).simplify();
                AstNode qc = new AstMul(l.b, r.b).simplify();
                return new QuadraticMatch(qa, qb, qc);
            }
            if (expr instanceof AstDiv) {
                AstDiv div = (AstDiv) expr;
                if (!div.right.containsVar(var)) {
                    QuadraticMatch l = matchQuadratic(div.left, var);
                    if (l == null) return null;
                    return new QuadraticMatch(
                            new AstDiv(l.a, div.right).simplify(),
                            new AstDiv(l.b, div.right).simplify(),
                            new AstDiv(l.c, div.right).simplify()
                    );
                }
            }
            if (expr instanceof AstPow) {
                AstPow pow = (AstPow) expr;
                if (!pow.exp.containsVar(var)) {
                    ExactRat er = evalConstantRat(pow.exp);
                    boolean isTwo = er != null
                            ? (er.den.equals(BigInteger.ONE) && er.num.equals(BigInteger.valueOf(2)))
                            : (pow.exp.constValue() != null && pow.exp.constValue().c.isReal()
                               && Math.abs(pow.exp.constValue().c.re - 2.0) < 1e-12);
                    if (isTwo) {
                        LinearMatch pq = matchLinear(pow.base, var);
                        if (pq == null) return null;
                        AstNode qa = new AstMul(pq.a, pq.a).simplify();
                        AstNode qb = new AstMul(new AstNum(2), new AstMul(pq.a, pq.b).simplify()).simplify();
                        AstNode qc = new AstMul(pq.b, pq.b).simplify();
                        return new QuadraticMatch(qa, qb, qc);
                    }
                }
            }
            return null;
        }

        static AstNode expandDerivatives(AstNode node, boolean radiansMode) {
            if (node == null) return null;
            if (node instanceof AstNum || node instanceof AstVar) return node;
            if (node instanceof AstNeg) {
                return new AstNeg(expandDerivatives(((AstNeg) node).inner, radiansMode)).simplify();
            }
            if (node instanceof AstAdd) {
                AstAdd a = (AstAdd) node;
                return new AstAdd(expandDerivatives(a.left, radiansMode), expandDerivatives(a.right, radiansMode)).simplify();
            }
            if (node instanceof AstSub) {
                AstSub s = (AstSub) node;
                return new AstSub(expandDerivatives(s.left, radiansMode), expandDerivatives(s.right, radiansMode)).simplify();
            }
            if (node instanceof AstMul) {
                AstMul m = (AstMul) node;
                return new AstMul(expandDerivatives(m.left, radiansMode), expandDerivatives(m.right, radiansMode)).simplify();
            }
            if (node instanceof AstDiv) {
                AstDiv d = (AstDiv) node;
                return new AstDiv(expandDerivatives(d.left, radiansMode), expandDerivatives(d.right, radiansMode)).simplify();
            }
            if (node instanceof AstPow) {
                AstPow p = (AstPow) node;
                return new AstPow(expandDerivatives(p.base, radiansMode), expandDerivatives(p.exp, radiansMode)).simplify();
            }
            if (node instanceof AstFunc) {
                AstFunc f = (AstFunc) node;
                if ((f.name.equals("d") || f.name.equals("deriv") || f.name.equals("diff")) && f.args.size() == 2) {
                    AstNode inner = expandDerivatives(f.args.get(0), radiansMode);
                    String v = calculusVariable(f.args.get(1).toDisplayString());
                    AstNode dAst = inner.diff(v, radiansMode);
                    if (dAst != null) return dAst.simplify();
                }
                List<AstNode> eargs = new ArrayList<>(f.args.size());
                for (AstNode arg : f.args) eargs.add(expandDerivatives(arg, radiansMode));
                return new AstFunc(f.name, eargs);
            }
            return node;
        }
    }

    static final class AstNum extends AstNode {
        final Value val;
        final String literal;
        AstNum(Value val, String literal) { this.val = val; this.literal = literal; }
        AstNum(Value val) { this(val, null); }
        AstNum(long n) { this(Value.fromInt(n)); }
        AstNum(BigInteger n) { this(Value.fromInt(n)); }
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
        @Override AstNode diff(String var, boolean radiansMode) { return new AstNum(0); }
        @Override AstNode integrate(String var, boolean radiansMode) {
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
        @Override AstNode diff(String var, boolean radiansMode) { return name.equals(var) ? new AstNum(1) : new AstNum(0); }
        @Override AstNode integrate(String var, boolean radiansMode) {
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
        @Override AstNode diff(String var, boolean radiansMode) {
            AstNode dl = left.diff(var, radiansMode);
            AstNode dr = right.diff(var, radiansMode);
            return (dl != null && dr != null) ? new AstAdd(dl, dr) : null;
        }
        @Override AstNode integrate(String var, boolean radiansMode) {
            AstNode il = left.integrate(var, radiansMode);
            AstNode ir = right.integrate(var, radiansMode);
            return (il != null && ir != null) ? new AstAdd(il, ir) : null;
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sl.isZero()) return sr;
            if (sr.isZero()) return sl;
            if (isNegTerm(sr)) return new AstSub(sl, new AstNeg(sr).simplify()).simplify();
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
        @Override AstNode diff(String var, boolean radiansMode) {
            AstNode dl = left.diff(var, radiansMode);
            AstNode dr = right.diff(var, radiansMode);
            return (dl != null && dr != null) ? new AstSub(dl, dr) : null;
        }
        @Override AstNode integrate(String var, boolean radiansMode) {
            AstNode il = left.integrate(var, radiansMode);
            AstNode ir = right.integrate(var, radiansMode);
            return (il != null && ir != null) ? new AstSub(il, ir) : null;
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sr.isZero()) return sl;
            if (sl.isZero()) return new AstNeg(sr).simplify();
            if (isNegTerm(sr)) return new AstAdd(sl, new AstNeg(sr).simplify()).simplify();
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
        @Override AstNode diff(String var, boolean radiansMode) {
            // (a * b)' = a' * b + a * b'
            AstNode dl = left.diff(var, radiansMode);
            AstNode dr = right.diff(var, radiansMode);
            if (dl == null || dr == null) return null;
            return new AstAdd(new AstMul(dl, right), new AstMul(left, dr));
        }
        @Override AstNode integrate(String var, boolean radiansMode) {
            if (!left.containsVar(var)) {
                AstNode ir = right.integrate(var, radiansMode);
                return ir != null ? new AstMul(left, ir).simplify() : null;
            }
            if (!right.containsVar(var)) {
                AstNode il = left.integrate(var, radiansMode);
                return il != null ? new AstMul(il, right).simplify() : null;
            }
            AstNode byParts = tryIntegrateLinearFn(left, right, var, radiansMode);
            if (byParts != null) return byParts;
            return tryIntegrateLinearFn(right, left, var, radiansMode);
        }

        private static AstNode tryIntegrateLinearFn(AstNode linCandidate, AstNode fnCandidate, String var, boolean radiansMode) {
            if (!(fnCandidate instanceof AstFunc)) return null;
            AstFunc fn = (AstFunc) fnCandidate;
            if ((!"exp".equals(fn.name) && !"sin".equals(fn.name) && !"cos".equals(fn.name)) || fn.args.size() != 1) {
                return null;
            }
            LinearMatch linP = matchLinear(linCandidate, var);
            LinearMatch linA = matchLinear(fn.args.get(0), var);
            if (linP == null || linP.a.isZero() || linA == null || linA.a.isZero()) return null;
            AstNode c = linA.a;
            boolean isDegTrig = !radiansMode && !"exp".equals(fn.name);
            AstNode k = isDegTrig
                    ? new AstDiv(new AstMul(c, new AstVar("pi")).simplify(), new AstNum(180)).simplify()
                    : c;
            AstNode term1 = k.isOne() ? linCandidate : new AstDiv(linCandidate, k).simplify();
            AstNode term2 = k.isOne() ? linP.a : new AstDiv(new AstDiv(linP.a, k).simplify(), k).simplify();
            if ("exp".equals(fn.name)) {
                AstNode polyFactor = new AstSub(term1, term2).simplify();
                return new AstMul(polyFactor, fnCandidate).simplify();
            }
            if ("sin".equals(fn.name)) {
                AstNode p1 = new AstMul(term2, fnCandidate).simplify();
                AstNode p2 = new AstMul(term1, new AstFunc("cos", fn.args)).simplify();
                return new AstSub(p1, p2).simplify();
            }
            AstNode p1 = new AstMul(term1, new AstFunc("sin", fn.args)).simplify();
            AstNode p2 = new AstMul(term2, fnCandidate).simplify();
            return new AstAdd(p1, p2).simplify();
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sl.isZero() || sr.isZero()) return new AstNum(0);
            if (sl.isOne()) return sr;
            if (sr.isOne()) return sl;
            if (sl.isMinusOne()) return new AstNeg(sr).simplify();
            if (sr.isMinusOne()) return new AstNeg(sl).simplify();
            if (sr instanceof AstNeg) return new AstNeg(new AstMul(sl, ((AstNeg) sr).inner).simplify()).simplify();
            if (sl instanceof AstNeg) return new AstNeg(new AstMul(((AstNeg) sl).inner, sr).simplify()).simplify();
            if (sl instanceof AstNum && sr instanceof AstNum) {
                Value lv = ((AstNum) sl).val;
                Value rv = ((AstNum) sr).val;
                if (lv.rat != null && rv.rat != null) {
                    return new AstNum(Value.fromExactRat(lv.rat.mul(rv.rat)));
                }
            }
            if (sl instanceof AstNum && sr instanceof AstMul && ((AstMul) sr).left instanceof AstNum) {
                Value lv = ((AstNum) sl).val;
                Value rlv = ((AstNum) ((AstMul) sr).left).val;
                if (lv.rat != null && rlv.rat != null) {
                    AstNode combined = new AstNum(Value.fromExactRat(lv.rat.mul(rlv.rat)));
                    return new AstMul(combined, ((AstMul) sr).right).simplify();
                }
            }
            if (sl instanceof AstNum && sr instanceof AstDiv) {
                AstDiv sd = (AstDiv) sr;
                if (sd.left.isOne()) {
                    return new AstDiv(sl, sd.right).simplify();
                }
                if (sd.right instanceof AstNum) {
                    Value lv = ((AstNum) sl).val;
                    Value drv = ((AstNum) sd.right).val;
                    if (lv.rat != null && drv.rat != null && drv.rat.num.signum() != 0) {
                        ExactRat factor = lv.rat.div(drv.rat);
                        if (factor.den.equals(BigInteger.ONE)) {
                            return new AstMul(new AstNum(Value.fromExactRat(factor)), sd.left).simplify();
                        }
                        AstNode numNode = factor.num.equals(BigInteger.ONE)
                                ? sd.left
                                : new AstMul(new AstNum(Value.fromInt(factor.num)), sd.left).simplify();
                        return new AstDiv(numNode, new AstNum(Value.fromInt(factor.den))).simplify();
                    }
                }
            }
            if (sr instanceof AstNum && sl instanceof AstDiv) {
                AstDiv sd = (AstDiv) sl;
                if (sd.left.isOne()) {
                    return new AstDiv(sr, sd.right).simplify();
                }
                if (sd.right instanceof AstNum) {
                    Value rv = ((AstNum) sr).val;
                    Value drv = ((AstNum) sd.right).val;
                    if (rv.rat != null && drv.rat != null && drv.rat.num.signum() != 0) {
                        ExactRat factor = rv.rat.div(drv.rat);
                        if (factor.den.equals(BigInteger.ONE)) {
                            return new AstMul(new AstNum(Value.fromExactRat(factor)), sd.left).simplify();
                        }
                        AstNode numNode = factor.num.equals(BigInteger.ONE)
                                ? sd.left
                                : new AstMul(new AstNum(Value.fromInt(factor.num)), sd.left).simplify();
                        return new AstDiv(numNode, new AstNum(Value.fromInt(factor.den))).simplify();
                    }
                }
            }
            if (isNegRealNum(sl)) {
                return new AstNeg(new AstMul(new AstNeg(sl).simplify(), sr).simplify()).simplify();
            }
            if (isNegRealNum(sr)) {
                return new AstNeg(new AstMul(sl, new AstNeg(sr).simplify()).simplify()).simplify();
            }
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
        @Override AstNode diff(String var, boolean radiansMode) {
            if (right.isZero()) return null;
            // (a / b)' = (a' * b - a * b') / b^2
            AstNode dl = left.diff(var, radiansMode);
            AstNode dr = right.diff(var, radiansMode);
            if (dl == null || dr == null) return null;
            return new AstDiv(
                new AstSub(new AstMul(dl, right), new AstMul(left, dr)),
                new AstPow(right, new AstNum(2))
            );
        }
        @Override AstNode integrate(String var, boolean radiansMode) {
            if (!right.containsVar(var)) {
                AstNode il = left.integrate(var, radiansMode);
                return il != null ? new AstDiv(il, right).simplify() : null;
            }
            if (!left.containsVar(var)) {
                AstNode sr = right.simplify();
                if (sr instanceof AstNeg) {
                    AstNode res = new AstDiv(new AstNeg(left).simplify(), ((AstNeg) sr).inner)
                            .integrate(var, radiansMode);
                    if (res != null) return res;
                }
                if (sr instanceof AstMul) {
                    AstMul sm = (AstMul) sr;
                    if (!sm.left.containsVar(var)) {
                        AstNode res = new AstDiv(new AstDiv(left, sm.left).simplify(), sm.right)
                                .integrate(var, radiansMode);
                        if (res != null) return res;
                    } else if (!sm.right.containsVar(var)) {
                        AstNode res = new AstDiv(new AstDiv(left, sm.right).simplify(), sm.left)
                                .integrate(var, radiansMode);
                        if (res != null) return res;
                    }
                }
                AstNode.LinearMatch lin = matchLinear(sr, var);
                if (lin != null && !lin.a.isZero()) {
                    AstNode lnInner = lin.b.isZero() ? new AstVar(var) : sr;
                    AstNode ln = new AstFunc("ln", java.util.Collections.singletonList(
                            new AstFunc("abs", java.util.Collections.singletonList(lnInner))));
                    AstNode num = left.isOne() ? ln : new AstMul(left, ln).simplify();
                    return lin.a.isOne() ? num : new AstDiv(num, lin.a).simplify();
                }
                if (sr instanceof AstPow) {
                    AstPow p = (AstPow) sr;
                    if (!p.exp.containsVar(var)) {
                        AstNode negPow = new AstPow(p.base, new AstNeg(p.exp).simplify());
                        AstNode intPow = negPow.integrate(var, radiansMode);
                        if (intPow != null) {
                            return left.isOne() ? intPow : new AstMul(left, intPow).simplify();
                        }
                    }
                }
                if (sr instanceof AstFunc) {
                    AstFunc fn = (AstFunc) sr;
                    if ("sqrt".equals(fn.name) && fn.args.size() == 1) {
                        AstNode sqArg = fn.args.get(0);
                        boolean isAbsSq = false;
                        AstNode.LinearMatch sqLin;
                        if (sqArg instanceof AstFunc && "abs".equals(((AstFunc) sqArg).name)
                                && ((AstFunc) sqArg).args.size() == 1) {
                            sqLin = matchLinear(((AstFunc) sqArg).args.get(0), var);
                            isAbsSq = true;
                        } else {
                            sqLin = matchLinear(sqArg, var);
                        }
                        if (sqLin != null && !sqLin.a.isZero()) {
                            AstNode two = new AstNum(2);
                            AstNode scaled = left.isOne() ? two : new AstMul(two, left).simplify();
                            AstNode num = new AstMul(scaled, sr).simplify();
                            if (isAbsSq) {
                                AstNode absInner = ((AstFunc) sqArg).args.get(0);
                                num = new AstMul(new AstFunc("sign", java.util.Collections.singletonList(absInner)), num).simplify();
                            }
                            return sqLin.a.isOne() ? num : new AstDiv(num, sqLin.a).simplify();
                        }
                        AstNode.QuadraticMatch sqQuad = matchQuadratic(fn.args.get(0), var);
                        if (sqQuad != null && sqQuad.b.isZero()) {
                            Value av = sqQuad.a.constValue();
                            Value cv = sqQuad.c.constValue();
                            if (av != null && cv != null && av.c.isReal() && cv.c.isReal()
                                    && av.c.re < 0.0 && cv.c.re > 0.0) {
                                double negA = -av.c.re;
                                double cVal = cv.c.re;
                                AstNode arg = (Math.abs(negA - cVal) < 1e-12)
                                        ? new AstVar(var)
                                        : new AstMul(new AstNum(Value.fromFloat(Math.sqrt(negA / cVal))), new AstVar(var)).simplify();
                                AstNode asinNode = new AstFunc("asin", java.util.Collections.singletonList(arg));
                                if (!radiansMode) {
                                    AstNode piOver180 = new AstDiv(new AstVar("pi"), new AstNum(180));
                                    asinNode = new AstMul(piOver180, asinNode).simplify();
                                }
                                AstNode num = left.isOne() ? asinNode : new AstMul(left, asinNode).simplify();
                                return (Math.abs(negA - 1.0) < 1e-12)
                                        ? num
                                        : new AstDiv(num, new AstNum(Value.fromFloat(Math.sqrt(negA)))).simplify();
                            }
                        }
                    }
                }
                AstNode.QuadraticMatch quad = matchQuadratic(sr, var);
                if (quad != null) {
                    Value av = quad.a.constValue();
                    Value cv = quad.c.constValue();
                    Value bvOpt = quad.b.constValue();
                    double bv = bvOpt != null ? bvOpt.c.re : 0.0;
                    boolean bIsReal = bvOpt != null ? bvOpt.c.isReal() : quad.b.isZero();
                    if (av != null && cv != null && av.c.isReal() && cv.c.isReal() && bIsReal && av.c.re > 0.0) {
                        if (quad.b.isZero() && cv.c.re > 0.0) {
                            AstNode arg;
                            if (Math.abs(av.c.re - cv.c.re) < 1e-12) {
                                arg = new AstVar(var);
                            } else {
                                Value ratioVal = (av.rat != null && cv.rat != null) ? exactRatSqrt(av.rat.div(cv.rat)) : null;
                                if (ratioVal == null) ratioVal = Value.fromFloat(Math.sqrt(av.c.re / cv.c.re));
                                arg = new AstMul(new AstNum(ratioVal), new AstVar(var)).simplify();
                            }
                            AstNode atanNode = new AstFunc("atan", java.util.Collections.singletonList(arg));
                            if (!radiansMode) {
                                AstNode piOver180 = new AstDiv(new AstVar("pi"), new AstNum(180));
                                atanNode = new AstMul(piOver180, atanNode).simplify();
                            }
                            AstNode num = left.isOne() ? atanNode : new AstMul(left, atanNode).simplify();
                            double prod = av.c.re * cv.c.re;
                            if (Math.abs(prod - 1.0) < 1e-12) {
                                return num;
                            }
                            Value scaleVal = (av.rat != null && cv.rat != null) ? exactRatSqrt(av.rat.mul(cv.rat)) : null;
                            if (scaleVal == null) scaleVal = Value.fromFloat(Math.sqrt(prod));
                            return new AstDiv(num, new AstNum(scaleVal)).simplify();
                        } else {
                            double disc = 4.0 * av.c.re * cv.c.re - bv * bv;
                            double tol = 1e-12 * (av.c.re * Math.abs(cv.c.re) + bv * bv + 1.0);
                            if (disc > tol) {
                                double rtDisc = Math.sqrt(disc);
                                AstNode linTop = new AstAdd(
                                        new AstMul(new AstNum(Value.fromFloat(2.0 * av.c.re)), new AstVar(var)).simplify(),
                                        quad.b
                                ).simplify();
                                AstNode arg = new AstDiv(linTop, new AstNum(Value.fromFloat(rtDisc))).simplify();
                                AstNode atanNode = new AstFunc("atan", java.util.Collections.singletonList(arg));
                                if (!radiansMode) {
                                    AstNode piOver180 = new AstDiv(new AstVar("pi"), new AstNum(180));
                                    atanNode = new AstMul(piOver180, atanNode).simplify();
                                }
                                AstNode twoA = new AstMul(new AstNum(2), left).simplify();
                                AstNode num = new AstMul(twoA, atanNode).simplify();
                                return new AstDiv(num, new AstNum(Value.fromFloat(rtDisc))).simplify();
                            } else if (Math.abs(disc) <= tol) {
                                AstNode linBot = new AstAdd(
                                        new AstMul(new AstNum(Value.fromFloat(2.0 * av.c.re)), new AstVar(var)).simplify(),
                                        quad.b
                                ).simplify();
                                AstNode twoA = new AstMul(new AstNum(2), left).simplify();
                                return new AstNeg(new AstDiv(twoA, linBot).simplify()).simplify();
                            }
                        }
                    }
                }
            }
            return null;
        }
        @Override AstNode simplify() {
            AstNode sl = left.simplify();
            AstNode sr = right.simplify();
            if (sl.isZero()) return new AstNum(0);
            if (sr.isOne()) return sl;
            if (sr.isMinusOne()) return new AstNeg(sl).simplify();
            if (sr instanceof AstNeg) {
                return new AstNeg(new AstDiv(sl, ((AstNeg) sr).inner).simplify()).simplify();
            }
            if (sr instanceof AstNum && ((AstNum) sr).isNegativeDisplay()) {
                return new AstNeg(new AstDiv(sl, new AstNeg(sr).simplify()).simplify()).simplify();
            }
            if (sl instanceof AstNeg) {
                return new AstNeg(new AstDiv(((AstNeg) sl).inner, sr).simplify()).simplify();
            }
            if (sl instanceof AstNum && ((AstNum) sl).isNegativeDisplay()) {
                return new AstNeg(new AstDiv(new AstNeg(sl).simplify(), sr).simplify()).simplify();
            }
            if (sl.equals(sr)) return new AstNum(1);
            if (sl instanceof AstNum && sr instanceof AstNum) {
                Value lv = ((AstNum) sl).val;
                Value rv = ((AstNum) sr).val;
                if (lv.rat != null && rv.rat != null && rv.rat.num.signum() != 0) {
                    ExactRat q = lv.rat.div(rv.rat);
                    return new AstNum(new Value(Complex.real(q.toDouble()), q));
                }
            }
            if (sl instanceof AstDiv) {
                AstDiv inner = (AstDiv) sl;
                return new AstDiv(inner.left, new AstMul(inner.right, sr).simplify()).simplify();
            }
            if (sr instanceof AstDiv) {
                AstDiv inner = (AstDiv) sr;
                return new AstDiv(new AstMul(sl, inner.right).simplify(), inner.left).simplify();
            }
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
        @Override AstNode diff(String var, boolean radiansMode) {
            boolean baseHasVar = base.containsVar(var);
            boolean expHasVar = exp.containsVar(var);

            if (!baseHasVar && !expHasVar) {
                return new AstNum(0);
            }

            if (baseHasVar && !expHasVar) {
                AstNode uPrime = base.diff(var, radiansMode);
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
                AstNode vPrime = exp.diff(var, radiansMode);
                if (vPrime == null) return null;
                return new AstMul(
                        new AstMul(this, new AstFunc("ln", java.util.Collections.singletonList(base))),
                        vPrime
                );
            }

            // General power rule (u > 0): (u^v)' = u^v * (v' * ln(u) + (v * u') / u)
            AstNode uPrime = base.diff(var, radiansMode);
            AstNode vPrime = exp.diff(var, radiansMode);
            if (uPrime == null || vPrime == null) return null;
            AstNode term1 = new AstMul(vPrime, new AstFunc("ln", java.util.Collections.singletonList(base)));
            AstNode term2 = new AstDiv(new AstMul(exp, uPrime), base);
            return new AstMul(this, new AstAdd(term1, term2));
        }
        @Override AstNode integrate(String var, boolean radiansMode) {
            if (base instanceof AstFunc && !exp.containsVar(var)) {
                AstFunc fn = (AstFunc) base;
                if (("sin".equals(fn.name) || "cos".equals(fn.name)) && fn.args.size() == 1) {
                    ExactRat expRat = evalConstantRat(exp);
                    boolean isSq = expRat != null && expRat.den.equals(BigInteger.ONE) && expRat.num.equals(BigInteger.valueOf(2));
                    if (!isSq) {
                        Value cVal = exp.constValue();
                        isSq = cVal != null && cVal.c.isReal() && Math.abs(cVal.c.re - 2.0) < 1e-11;
                    }
                    if (isSq) {
                        AstNode u = fn.args.get(0);
                        LinearMatch uLin = matchLinear(u, var);
                        if (uLin != null && !uLin.a.isZero()) {
                            AstNode halfX = new AstDiv(new AstVar(var), new AstNum(2));
                            AstNode twoU = new AstMul(new AstNum(2), u).simplify();
                            AstNode sinTwoU = new AstFunc("sin", java.util.Collections.singletonList(twoU));
                            AstNode fourA = uLin.a.isOne()
                                    ? new AstNum(4)
                                    : new AstMul(new AstNum(4), uLin.a).simplify();
                            AstNode osc = new AstDiv(sinTwoU, fourA).simplify();
                            AstNode scaledOsc = radiansMode
                                    ? osc
                                    : new AstMul(new AstDiv(new AstNum(180), new AstVar("pi")), osc).simplify();
                            return "sin".equals(fn.name)
                                    ? new AstSub(halfX, scaledOsc).simplify()
                                    : new AstAdd(halfX, scaledOsc).simplify();
                        }
                    }
                }
            }
            boolean isAbsBase = base instanceof AstFunc && "abs".equals(((AstFunc) base).name)
                    && ((AstFunc) base).args.size() == 1;
            LinearMatch lin = isAbsBase
                    ? matchLinear(((AstFunc) base).args.get(0), var)
                    : matchLinear(base, var);
            if (lin != null && !lin.a.isZero() && !exp.containsVar(var)) {
                AstNode a = lin.a;
                boolean unitA = a.isOne();
                AstNode signWrapper = isAbsBase
                        ? new AstFunc("sign", java.util.Collections.singletonList(((AstFunc) base).args.get(0)))
                        : null;
                ExactRat expRat = evalConstantRat(exp);
                if (expRat != null) {
                    if (expRat.den.equals(BigInteger.ONE) && expRat.num.equals(BigInteger.valueOf(-1))) {
                        AstNode lnTarget = isAbsBase ? base : new AstFunc("abs", java.util.Collections.singletonList(base));
                        AstNode lnNode = new AstFunc("ln", java.util.Collections.singletonList(lnTarget));
                        AstNode res = unitA ? lnNode : new AstDiv(lnNode, a).simplify();
                        return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
                    }
                    ExactRat newExp = expRat.add(ExactRat.of(1));
                    if (newExp.den.equals(BigInteger.ONE) && newExp.num.signum() < 0) {
                        BigInteger posK = newExp.num.negate();
                        AstNode powNode = posK.equals(BigInteger.ONE)
                                ? base
                                : new AstPow(base, new AstNum(posK));
                        AstNode factor = new AstNum(posK);
                        AstNode denom = posK.equals(BigInteger.ONE)
                                ? (unitA ? powNode : new AstMul(a, powNode).simplify())
                                : new AstMul(unitA ? factor : new AstMul(factor, a).simplify(), powNode).simplify();
                        AstNode res = new AstNeg(new AstDiv(new AstNum(1), denom).simplify()).simplify();
                        return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
                    }
                    AstNode powNode = (newExp.den.equals(BigInteger.ONE) && newExp.num.equals(BigInteger.ONE))
                            ? base
                            : new AstPow(base, new AstNum(new Value(Complex.real(newExp.toDouble()), newExp)));
                    if (newExp.den.equals(BigInteger.ONE)) {
                        AstNode denom = unitA
                                ? new AstNum(new Value(Complex.real(newExp.toDouble()), newExp))
                                : new AstMul(new AstNum(new Value(Complex.real(newExp.toDouble()), newExp)), a).simplify();
                        AstNode res = new AstDiv(powNode, denom).simplify();
                        return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
                    }
                    ExactRat coeff = ExactRat.of(1).div(newExp);
                    AstNode baseRes = new AstMul(
                            new AstNum(new Value(Complex.real(coeff.toDouble()), coeff)),
                            powNode
                    ).simplify();
                    AstNode res = unitA ? baseRes : new AstDiv(baseRes, a).simplify();
                    return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
                }
                Value cVal = exp.constValue();
                if (cVal != null && cVal.c.isReal()) {
                    if (Math.abs(cVal.c.re + 1.0) < 1e-11) {
                        AstNode lnTarget = isAbsBase ? base : new AstFunc("abs", java.util.Collections.singletonList(base));
                        AstNode lnNode = new AstFunc("ln", java.util.Collections.singletonList(lnTarget));
                        AstNode res = unitA ? lnNode : new AstDiv(lnNode, a).simplify();
                        return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
                    }
                    if (Math.abs(cVal.c.re - Math.round(cVal.c.re)) < 1e-11) {
                        long newP = Math.round(cVal.c.re) + 1;
                        if (newP < 0) {
                            long posK = -newP;
                            AstNode powNode = posK == 1 ? base : new AstPow(base, new AstNum(posK));
                            AstNode factor = new AstNum(posK);
                            AstNode denom = posK == 1
                                    ? (unitA ? powNode : new AstMul(a, powNode).simplify())
                                    : new AstMul(unitA ? factor : new AstMul(factor, a).simplify(), powNode).simplify();
                            AstNode res = new AstNeg(new AstDiv(new AstNum(1), denom).simplify()).simplify();
                            return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
                        }
                        AstNode powNode = new AstPow(base, new AstNum(newP));
                        AstNode denom = unitA ? new AstNum(newP) : new AstMul(new AstNum(newP), a).simplify();
                        AstNode res = new AstDiv(powNode, denom).simplify();
                        return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
                    }
                    double newP = cVal.c.re + 1.0;
                    AstNode denom = unitA ? new AstNum(Value.fromFloat(newP)) : new AstMul(new AstNum(Value.fromFloat(newP)), a).simplify();
                    AstNode res = new AstDiv(new AstPow(base, new AstNum(Value.fromFloat(newP))), denom).simplify();
                    return signWrapper != null ? new AstMul(signWrapper, res).simplify() : res;
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
        @Override AstNode diff(String var, boolean radiansMode) {
            AstNode d = inner.diff(var, radiansMode);
            return d != null ? new AstNeg(d) : null;
        }
        @Override AstNode integrate(String var, boolean radiansMode) {
            AstNode i = inner.integrate(var, radiansMode);
            return i != null ? new AstNeg(i).simplify() : null;
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
        @Override AstNode diff(String var, boolean radiansMode) {
            if (!containsVar(var)) return new AstNum(0);
            if (args.size() == 1) {
                AstNode u = args.get(0);
                AstNode uPrime = u.diff(var, radiansMode);
                if (uPrime == null) return null;
                AstNode degScale = radiansMode ? null : new AstDiv(new AstVar("pi"), new AstNum(180));
                AstNode invDegScale = radiansMode ? null : new AstDiv(new AstNum(180), new AstVar("pi"));
                switch (name) {
                    case "sin": {
                        AstNode base = new AstMul(uPrime, new AstFunc("cos", java.util.Collections.singletonList(u)));
                        return degScale != null ? new AstMul(degScale, base.simplify()) : base;
                    }
                    case "cos": {
                        AstNode base = new AstMul(uPrime, new AstFunc("sin", java.util.Collections.singletonList(u)));
                        return new AstNeg(degScale != null ? new AstMul(degScale, base.simplify()) : base);
                    }
                    case "tan": {
                        AstNode denom = new AstPow(new AstFunc("cos", java.util.Collections.singletonList(u)), new AstNum(2));
                        return degScale != null ? new AstDiv(new AstMul(degScale, uPrime), denom) : new AstDiv(uPrime, denom);
                    }
                    case "sinh": return new AstMul(uPrime, new AstFunc("cosh", java.util.Collections.singletonList(u)));
                    case "cosh": return new AstMul(uPrime, new AstFunc("sinh", java.util.Collections.singletonList(u)));
                    case "tanh": return new AstDiv(uPrime, new AstPow(new AstFunc("cosh", java.util.Collections.singletonList(u)), new AstNum(2)));
                    case "asin":
                    case "arcsin": {
                        AstNode base = new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstSub(new AstNum(1), new AstPow(u, new AstNum(2))))));
                        return invDegScale != null ? new AstMul(invDegScale, base) : base;
                    }
                    case "acos":
                    case "arccos": {
                        AstNode base = new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstSub(new AstNum(1), new AstPow(u, new AstNum(2))))));
                        return new AstNeg(invDegScale != null ? new AstMul(invDegScale, base) : base);
                    }
                    case "atan":
                    case "arctan": {
                        AstNode base = new AstDiv(uPrime, new AstAdd(new AstNum(1), new AstPow(u, new AstNum(2))));
                        return invDegScale != null ? new AstMul(invDegScale, base) : base;
                    }
                    case "asinh":
                    case "arcsinh": return new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstAdd(new AstPow(u, new AstNum(2)), new AstNum(1)))));
                    case "acosh":
                    case "arccosh": return new AstDiv(uPrime, new AstFunc("sqrt", java.util.Collections.singletonList(new AstSub(new AstPow(u, new AstNum(2)), new AstNum(1)))));
                    case "atanh":
                    case "arctanh": return new AstDiv(uPrime, new AstSub(new AstNum(1), new AstPow(u, new AstNum(2))));
                    case "sqrt": return new AstDiv(uPrime, new AstMul(new AstNum(2), new AstFunc("sqrt", java.util.Collections.singletonList(u))));
                    case "exp": return new AstMul(uPrime, new AstFunc("exp", java.util.Collections.singletonList(u)));
                    case "ln": return new AstDiv(uPrime, u);
                    case "abs": return new AstMul(
                            uPrime,
                            new AstFunc("sign", java.util.Collections.singletonList(u))
                    );
                    case "floor":
                    case "ceil":
                    case "round":
                    case "sign":
                    case "sgn": return new AstNum(0);
                    default: return null;
                }
            }
            if (args.size() == 2) {
                switch (name) {
                    case "power":
                    case "pow": return new AstPow(args.get(0), args.get(1)).diff(var, radiansMode);
                    case "addition": return new AstAdd(args.get(0), args.get(1)).diff(var, radiansMode);
                    case "subtraction": return new AstSub(args.get(0), args.get(1)).diff(var, radiansMode);
                    case "multiplication": return new AstMul(args.get(0), args.get(1)).diff(var, radiansMode);
                    case "division":
                    case "fraction": return new AstDiv(args.get(0), args.get(1)).diff(var, radiansMode);
                    default: return null;
                }
            }
            return null;
        }
        @Override AstNode integrate(String var, boolean radiansMode) {
            if (args.size() != 1) return null;
            AstNode u = args.get(0);
            if (!u.containsVar(var)) {
                return new AstMul(this, new AstVar(var));
            }
            boolean isAbsArg = u instanceof AstFunc && "abs".equals(((AstFunc) u).name)
                    && ((AstFunc) u).args.size() == 1;
            if (isAbsArg && !"sqrt".equals(name) && !"ln".equals(name)) {
                return null;
            }
            AstNode uLin = isAbsArg ? ((AstFunc) u).args.get(0) : u;
            LinearMatch lin = matchLinear(uLin, var);
            if (lin == null) return null;
            AstNode a = lin.a;
            if (a.isZero()) return null;
            boolean unitA = a.isOne();
            AstNode uNode = u;
            AstNode invDegScale = radiansMode ? null : new AstDiv(new AstNum(180), new AstVar("pi"));
            switch (name) {
                case "sqrt": {
                    ExactRat threeHalves = new ExactRat(BigInteger.valueOf(3), BigInteger.valueOf(2));
                    AstNode expNode = new AstNum(new Value(Complex.real(1.5), threeHalves));
                    AstNode powNode = new AstPow(uNode, expNode);
                    AstNode scaledPow = isAbsArg
                            ? new AstMul(new AstFunc("sign", java.util.Collections.singletonList(uLin)), powNode).simplify()
                            : powNode;
                    if (unitA) {
                        ExactRat twoThirds = new ExactRat(BigInteger.valueOf(2), BigInteger.valueOf(3));
                        return new AstMul(
                                new AstNum(new Value(Complex.real(2.0 / 3.0), twoThirds)),
                                scaledPow
                        );
                    }
                    AstNode denom = new AstMul(new AstNum(3), a).simplify();
                    return new AstDiv(new AstMul(new AstNum(2), scaledPow), denom);
                }
                case "abs": {
                    AstNode sq = new AstMul(
                            new AstFunc("sign", java.util.Collections.singletonList(uLin)),
                            new AstPow(uLin, new AstNum(2))
                    );
                    AstNode denom = unitA ? new AstNum(2) : new AstMul(new AstNum(2), a).simplify();
                    return new AstDiv(sq, denom).simplify();
                }
                case "exp": {
                    AstNode base = new AstFunc("exp", java.util.Collections.singletonList(uNode));
                    return unitA ? base : new AstDiv(base, a).simplify();
                }
                case "ln": {
                    AstNode base = new AstSub(
                            new AstMul(uLin, new AstFunc("ln", java.util.Collections.singletonList(uNode))),
                            uLin
                    );
                    return unitA ? base : new AstDiv(base, a).simplify();
                }
                case "sin": {
                    AstNode cosU = new AstFunc("cos", java.util.Collections.singletonList(uNode));
                    AstNode base;
                    if (unitA) base = new AstNeg(cosU);
                    else if (a instanceof AstNeg) base = new AstDiv(cosU, ((AstNeg) a).inner).simplify();
                    else if (a instanceof AstNum && ((AstNum) a).isNegativeDisplay()) {
                        base = new AstDiv(cosU, new AstNeg(a).simplify()).simplify();
                    } else {
                        base = new AstNeg(new AstDiv(cosU, a).simplify());
                    }
                    return invDegScale != null ? new AstMul(invDegScale, base).simplify() : base;
                }
                case "cos": {
                    AstNode sinU = new AstFunc("sin", java.util.Collections.singletonList(uNode));
                    AstNode base = unitA ? sinU : new AstDiv(sinU, a).simplify();
                    return invDegScale != null ? new AstMul(invDegScale, base).simplify() : base;
                }
                case "tan": {
                    AstNode lnCosU = new AstFunc("ln", java.util.Collections.singletonList(
                            new AstFunc("cos", java.util.Collections.singletonList(uNode))));
                    AstNode base;
                    if (unitA) base = new AstNeg(lnCosU);
                    else if (a instanceof AstNeg) base = new AstDiv(lnCosU, ((AstNeg) a).inner).simplify();
                    else if (a instanceof AstNum && ((AstNum) a).isNegativeDisplay()) {
                        base = new AstDiv(lnCosU, new AstNeg(a).simplify()).simplify();
                    } else {
                        base = new AstNeg(new AstDiv(lnCosU, a).simplify());
                    }
                    return invDegScale != null ? new AstMul(invDegScale, base).simplify() : base;
                }
                case "sinh": {
                    AstNode coshU = new AstFunc("cosh", java.util.Collections.singletonList(uNode));
                    return unitA ? coshU : new AstDiv(coshU, a).simplify();
                }
                case "cosh": {
                    AstNode sinhU = new AstFunc("sinh", java.util.Collections.singletonList(uNode));
                    return unitA ? sinhU : new AstDiv(sinhU, a).simplify();
                }
                default:
                    return null;
            }
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

    private static final class Monomial implements Comparable<Monomial> {
        final java.util.TreeMap<String, Integer> vars;

        Monomial() { this.vars = new java.util.TreeMap<>(); }
        Monomial(java.util.TreeMap<String, Integer> vars) { this.vars = vars; }

        int totalDegree() {
            int d = 0;
            for (int e : vars.values()) d += e;
            return d;
        }

        Monomial multiply(Monomial o) {
            java.util.TreeMap<String, Integer> m = new java.util.TreeMap<>(this.vars);
            for (Map.Entry<String, Integer> entry : o.vars.entrySet()) {
                m.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            return new Monomial(m);
        }

        Monomial divideBy(Monomial o) {
            java.util.TreeMap<String, Integer> m = new java.util.TreeMap<>(this.vars);
            for (Map.Entry<String, Integer> entry : o.vars.entrySet()) {
                int numExp = m.getOrDefault(entry.getKey(), 0);
                int denExp = entry.getValue();
                if (numExp < denExp) return null;
                if (numExp == denExp) m.remove(entry.getKey());
                else m.put(entry.getKey(), numExp - denExp);
            }
            return new Monomial(m);
        }

        @Override
        public int compareTo(Monomial o) {
            int cmp = Integer.compare(o.totalDegree(), this.totalDegree());
            if (cmp != 0) return cmp;
            java.util.Iterator<Map.Entry<String, Integer>> it1 = this.vars.entrySet().iterator();
            java.util.Iterator<Map.Entry<String, Integer>> it2 = o.vars.entrySet().iterator();
            while (it1.hasNext() && it2.hasNext()) {
                Map.Entry<String, Integer> e1 = it1.next();
                Map.Entry<String, Integer> e2 = it2.next();
                cmp = e1.getKey().compareTo(e2.getKey());
                if (cmp != 0) return cmp;
                cmp = Integer.compare(e2.getValue(), e1.getValue());
                if (cmp != 0) return cmp;
            }
            return Integer.compare(o.vars.size(), this.vars.size());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Monomial && vars.equals(((Monomial) o).vars);
        }

        @Override
        public int hashCode() { return vars.hashCode(); }
    }

    private static java.util.TreeMap<Monomial, ExactRat> astToPoly(AstNode node) {
        if (node instanceof AstNum) {
            Value v = ((AstNum) node).val;
            ExactRat r = v.rat != null ? v.rat : AstNode.decimalToExactRat(v.c.re);
            if (r == null) return null;
            java.util.TreeMap<Monomial, ExactRat> map = new java.util.TreeMap<>();
            if (r.num.signum() != 0) map.put(new Monomial(), r);
            return map;
        }
        if (node instanceof AstVar) {
            String name = ((AstVar) node).name;
            if (name.equals("i")) return null;
            java.util.TreeMap<String, Integer> vars = new java.util.TreeMap<>();
            vars.put(name, 1);
            java.util.TreeMap<Monomial, ExactRat> map = new java.util.TreeMap<>();
            map.put(new Monomial(vars), ExactRat.of(1));
            return map;
        }
        if (node instanceof AstNeg) {
            java.util.TreeMap<Monomial, ExactRat> inner = astToPoly(((AstNeg) node).inner);
            if (inner == null) return null;
            java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
            for (Map.Entry<Monomial, ExactRat> e : inner.entrySet()) {
                res.put(e.getKey(), new ExactRat(e.getValue().num.negate(), e.getValue().den));
            }
            return res;
        }
        if (node instanceof AstAdd) {
            java.util.TreeMap<Monomial, ExactRat> l = astToPoly(((AstAdd) node).left);
            java.util.TreeMap<Monomial, ExactRat> r = astToPoly(((AstAdd) node).right);
            if (l == null || r == null) return null;
            return addPoly(l, r, 1);
        }
        if (node instanceof AstSub) {
            java.util.TreeMap<Monomial, ExactRat> l = astToPoly(((AstSub) node).left);
            java.util.TreeMap<Monomial, ExactRat> r = astToPoly(((AstSub) node).right);
            if (l == null || r == null) return null;
            return addPoly(l, r, -1);
        }
        if (node instanceof AstMul) {
            java.util.TreeMap<Monomial, ExactRat> l = astToPoly(((AstMul) node).left);
            java.util.TreeMap<Monomial, ExactRat> r = astToPoly(((AstMul) node).right);
            if (l == null || r == null) return null;
            return mulPoly(l, r);
        }
        if (node instanceof AstDiv) {
            java.util.TreeMap<Monomial, ExactRat> l = astToPoly(((AstDiv) node).left);
            if (l == null) return null;
            ExactRat scalar = AstNode.evalConstantRat(((AstDiv) node).right);
            if (scalar != null) {
                if (scalar.num.signum() == 0) return null;
                java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
                for (Map.Entry<Monomial, ExactRat> e : l.entrySet()) {
                    ExactRat c = e.getValue().div(scalar);
                    if (c.num.signum() != 0) res.put(e.getKey(), c);
                }
                return res;
            }
            java.util.TreeMap<Monomial, ExactRat> r = astToPoly(((AstDiv) node).right);
            if (r == null || r.size() != 1) return null;
            Map.Entry<Monomial, ExactRat> denEntry = r.entrySet().iterator().next();
            Monomial denMon = denEntry.getKey();
            ExactRat denCoeff = denEntry.getValue();
            if (denCoeff.num.signum() == 0) return null;
            java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
            for (Map.Entry<Monomial, ExactRat> e : l.entrySet()) {
                Monomial qMon = e.getKey().divideBy(denMon);
                if (qMon == null) return null;
                ExactRat c = e.getValue().div(denCoeff);
                if (c.num.signum() != 0) res.put(qMon, c);
            }
            return res;
        }
        if (node instanceof AstPow) {
            AstPow p = (AstPow) node;
            ExactRat expRat = AstNode.evalConstantRat(p.exp);
            if (expRat == null || !expRat.den.equals(BigInteger.ONE) || expRat.num.signum() < 0) return null;
            int expVal;
            try {
                expVal = expRat.num.intValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
            if (expVal > 64) return null;
            java.util.TreeMap<Monomial, ExactRat> base = astToPoly(p.base);
            if (base == null) return null;
            java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
            res.put(new Monomial(), ExactRat.of(1));
            for (int i = 0; i < expVal; i++) {
                checkInterrupted();
                res = mulPoly(res, base);
                if (res == null) return null;
            }
            return res;
        }
        return null;
    }

    private static java.util.TreeMap<Monomial, ExactRat> addPoly(
            java.util.TreeMap<Monomial, ExactRat> l,
            java.util.TreeMap<Monomial, ExactRat> r,
            int sign) {
        java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>(l);
        for (Map.Entry<Monomial, ExactRat> e : r.entrySet()) {
            ExactRat addend = sign >= 0 ? e.getValue() : new ExactRat(e.getValue().num.negate(), e.getValue().den);
            ExactRat cur = res.get(e.getKey());
            ExactRat next = cur == null ? addend : cur.add(addend);
            if (next.num.signum() == 0) res.remove(e.getKey());
            else res.put(e.getKey(), next);
        }
        return res;
    }

    private static java.util.TreeMap<Monomial, ExactRat> mulPoly(
            java.util.TreeMap<Monomial, ExactRat> l,
            java.util.TreeMap<Monomial, ExactRat> r) {
        if ((long) l.size() * (long) r.size() > 4096) return null;
        java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
        for (Map.Entry<Monomial, ExactRat> el : l.entrySet()) {
            checkInterrupted();
            for (Map.Entry<Monomial, ExactRat> er : r.entrySet()) {
                Monomial m = el.getKey().multiply(er.getKey());
                ExactRat c = el.getValue().mul(er.getValue());
                ExactRat cur = res.get(m);
                ExactRat next = cur == null ? c : cur.add(c);
                if (next.num.signum() == 0) res.remove(m);
                else res.put(m, next);
            }
        }
        return res;
    }

    private static AstNode monomialToAst(Monomial m) {
        AstNode node = null;
        for (Map.Entry<String, Integer> e : m.vars.entrySet()) {
            AstNode factor = e.getValue() == 1
                    ? new AstVar(e.getKey())
                    : new AstPow(new AstVar(e.getKey()), new AstNum(e.getValue()));
            node = (node == null) ? factor : new AstMul(node, factor);
        }
        return node;
    }

    private static AstNode polyToAst(java.util.TreeMap<Monomial, ExactRat> poly) {
        if (poly.isEmpty()) return new AstNum(0);
        AstNode result = null;
        for (Map.Entry<Monomial, ExactRat> entry : poly.entrySet()) {
            ExactRat c = entry.getValue();
            boolean neg = c.num.signum() < 0;
            ExactRat absC = new ExactRat(c.num.abs(), c.den);
            AstNode mon = monomialToAst(entry.getKey());
            AstNode termNode;
            if (mon == null) {
                termNode = new AstNum(new Value(Complex.real(absC.toDouble()), absC));
            } else if (absC.num.equals(BigInteger.ONE) && absC.den.equals(BigInteger.ONE)) {
                termNode = mon;
            } else {
                termNode = new AstMul(new AstNum(new Value(Complex.real(absC.toDouble()), absC)), mon);
            }
            if (result == null) {
                result = neg ? new AstNeg(termNode) : termNode;
            } else {
                result = neg ? new AstSub(result, termNode) : new AstAdd(result, termNode);
            }
        }
        return result;
    }

    private static boolean containsAddOrSub(AstNode node) {
        if (node instanceof AstAdd || node instanceof AstSub) return true;
        if (node instanceof AstMul) {
            AstMul m = (AstMul) node;
            return containsAddOrSub(m.left) || containsAddOrSub(m.right);
        }
        if (node instanceof AstDiv) {
            AstDiv d = (AstDiv) node;
            return containsAddOrSub(d.left) || containsAddOrSub(d.right);
        }
        if (node instanceof AstPow) {
            AstPow p = (AstPow) node;
            return containsAddOrSub(p.base) || containsAddOrSub(p.exp);
        }
        if (node instanceof AstNeg) return containsAddOrSub(((AstNeg) node).inner);
        if (node instanceof AstFunc) {
            for (AstNode arg : ((AstFunc) node).args) {
                if (containsAddOrSub(arg)) return true;
            }
        }
        return false;
    }

    private static int countVarOccurrences(AstNode node) {
        if (node instanceof AstVar) return ((AstVar) node).name.equals("i") ? 0 : 1;
        if (node instanceof AstNeg) return countVarOccurrences(((AstNeg) node).inner);
        if (node instanceof AstAdd) return countVarOccurrences(((AstAdd) node).left) + countVarOccurrences(((AstAdd) node).right);
        if (node instanceof AstSub) return countVarOccurrences(((AstSub) node).left) + countVarOccurrences(((AstSub) node).right);
        if (node instanceof AstMul) return countVarOccurrences(((AstMul) node).left) + countVarOccurrences(((AstMul) node).right);
        if (node instanceof AstDiv) return countVarOccurrences(((AstDiv) node).left) + countVarOccurrences(((AstDiv) node).right);
        if (node instanceof AstPow) return countVarOccurrences(((AstPow) node).base) + countVarOccurrences(((AstPow) node).exp);
        if (node instanceof AstFunc) {
            int sum = 0;
            for (AstNode arg : ((AstFunc) node).args) sum += countVarOccurrences(arg);
            return sum;
        }
        return 0;
    }

    private static boolean hasCompoundPowerBase(AstNode node) {
        if (node instanceof AstPow) {
            AstPow p = (AstPow) node;
            boolean baseCompound = p.base instanceof AstMul || p.base instanceof AstDiv || p.base instanceof AstPow;
            if (baseCompound && countVarOccurrences(p.base) > 0) return true;
            return hasCompoundPowerBase(p.base) || hasCompoundPowerBase(p.exp);
        }
        if (node instanceof AstMul) return hasCompoundPowerBase(((AstMul) node).left) || hasCompoundPowerBase(((AstMul) node).right);
        if (node instanceof AstDiv) return hasCompoundPowerBase(((AstDiv) node).left) || hasCompoundPowerBase(((AstDiv) node).right);
        if (node instanceof AstNeg) return hasCompoundPowerBase(((AstNeg) node).inner);
        return false;
    }

    private static int countNumericLiterals(AstNode node) {
        if (node instanceof AstNum) return 1;
        if (node instanceof AstNeg) return countNumericLiterals(((AstNeg) node).inner);
        if (node instanceof AstAdd) return countNumericLiterals(((AstAdd) node).left) + countNumericLiterals(((AstAdd) node).right);
        if (node instanceof AstSub) return countNumericLiterals(((AstSub) node).left) + countNumericLiterals(((AstSub) node).right);
        if (node instanceof AstMul) return countNumericLiterals(((AstMul) node).left) + countNumericLiterals(((AstMul) node).right);
        if (node instanceof AstDiv) return countNumericLiterals(((AstDiv) node).left) + countNumericLiterals(((AstDiv) node).right);
        if (node instanceof AstPow) return countNumericLiterals(((AstPow) node).base) + countNumericLiterals(((AstPow) node).exp);
        if (node instanceof AstFunc) {
            int sum = 0;
            for (AstNode arg : ((AstFunc) node).args) sum += countNumericLiterals(arg);
            return sum;
        }
        return 0;
    }

    private static boolean canSimplifyAsPolynomial(AstNode node) {
        return containsAddOrSub(node)
                || countVarOccurrences(node) >= 2
                || hasCompoundPowerBase(node)
                || countNumericLiterals(node) >= 2
                || (countVarOccurrences(node) >= 1
                    && !(node instanceof AstVar && !java.util.Arrays.asList(USER_VARIABLES).contains(((AstVar) node).name)));
    }

    public static AstNode simplifyPolynomialAst(AstNode node) {
        if (canSimplifyAsPolynomial(node)) {
            java.util.TreeMap<Monomial, ExactRat> poly = astToPoly(node);
            if (poly != null) {
                AstNode expanded = polyToAst(poly);
                if (poly.size() > 1 || !expanded.equals(node) || countVarOccurrences(node) >= 1) {
                    return expanded;
                }
                return null;
            }
        }

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
        private final boolean notation;
        private int pos = -1, ch;
        private int depth = 0;

        private void enterDepth() {
            if (++depth > MAX_NESTING_DEPTH) {
                throw new RuntimeException("Maximale Verschachtelungstiefe überschritten");
            }
        }

        private AstParser(String expression, boolean notation) {
            this.notation = notation;
            this.s = normalizeEvaluation(expression);
        }

        static AstNode tryParse(String expr) {
            return tryParse(expr, false);
        }

        static AstNode tryParseNotation(String expr) {
            return tryParse(expr, true);
        }

        private static AstNode tryParse(String expr, boolean notation) {
            try {
                AstParser p = new AstParser(expr, notation);
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
                if (isInfixModulo(s, pos)) {
                    pos += 2;
                    nextChar();
                    x = new AstFunc(notation ? " mod " : "mod", java.util.Arrays.asList(x, parseUnary()));
                } else if (eat('*')) {
                    x = new AstMul(x, parseUnary());
                } else if (eat('/')) {
                    x = new AstDiv(x, parseUnary());
                } else if (ch == '(' || ch == '√' || Character.isLetter(ch)) {
                    AstNode right = parseUnary();
                    x = new AstMul(x, right);
                } else {
                    return x;
                }
            }
        }

        private AstNode parseUnary() {
            if (eat('+')) {
                enterDepth();
                try { return parseUnary(); } finally { depth--; }
            }
            if (eat('-')) {
                enterDepth();
                try { return new AstNeg(parseUnary()); } finally { depth--; }
            }
            if (eat('√')) {
                enterDepth();
                try { return new AstFunc("sqrt", java.util.Collections.singletonList(parseUnary())); } finally { depth--; }
            }
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
                    val = new AstFunc(notation ? "!" : "factorial", java.util.Collections.singletonList(val));
                } else if (eat('%')) {
                    val = notation ? new AstFunc("%", java.util.Collections.singletonList(val))
                            : new AstMul(val, new AstNum(Value.fromRat(1, 100)));
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
                return new AstNum(Value.fromRat(1, 100), notation ? "%" : null);
            }

            if (eat('(')) {
                enterDepth();
                try {
                    AstNode x = parseExpression();
                    if (!eat(')')) throw new RuntimeException("Expected ')'");
                    return x;
                } finally {
                    depth--;
                }
            }

            if (eat('[')) {
                enterDepth();
                try {
                    List<AstNode> entries = new ArrayList<>();
                    do {
                        entries.add(parseExpression());
                    } while (eat(','));
                    if (!eat(']')) throw new RuntimeException("Expected ']'");
                    return new AstFunc("[]", entries);
                } finally {
                    depth--;
                }
            }

            // Hex number 0x...
            if (ch == '0' && pos + 1 < s.length() && (s.charAt(pos + 1) == 'x' || s.charAt(pos + 1) == 'X')) {
                nextChar();
                nextChar();
                int hexStart = pos;
                while (Character.digit(ch, 16) != -1) nextChar();
                String hexStr = s.substring(hexStart, pos);
                BigInteger bi = new BigInteger(hexStr, 16);
                return new AstNum(Value.fromInt(bi), notation ? s.substring(startPos, pos) : null);
            }

            // Number
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                boolean hasDot = false;
                boolean hasExp = false;
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    if (ch == '.') hasDot = true;
                    nextChar();
                }
                if ((ch == 'e' || ch == 'E') && pos + 1 < s.length()) {
                    int lookahead = pos + 1;
                    while (lookahead < s.length() && s.charAt(lookahead) == ' ') lookahead++;
                    if (lookahead < s.length()) {
                        char next = s.charAt(lookahead);
                        if (Character.isDigit(next) || next == '+' || next == '-') {
                            hasExp = true;
                            pos = lookahead;
                            ch = s.charAt(pos);
                            if (ch == '+' || ch == '-') nextChar();
                            while (ch >= '0' && ch <= '9') nextChar();
                        }
                    }
                }
                String numStr = s.substring(startPos, pos).replaceAll("\\s+", "");
                if (!hasDot && !hasExp) {
                    try {
                        BigInteger bi = new BigInteger(numStr);
                        return new AstNum(Value.fromInt(bi), notation ? numStr : null);
                    } catch (Exception ignored) {}
                }
                double d = Double.parseDouble(numStr);
                return new AstNum(Value.fromFloat(d), notation ? numStr : null);
            }

            // Identifier or Function call
            if (Character.isLetter(ch)) {
                while (Character.isLetterOrDigit(ch) || ch == '_') nextChar();
                String ident = s.substring(startPos, pos);
                if (eat('(')) {
                    enterDepth();
                    try {
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
                    } finally {
                        depth--;
                    }
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
                    AstNode resAst = f.args.get(0).integrate(var, radiansMode);
                    if (resAst != null) {
                        String res = resAst.simplify().toDisplayString();
                        double numVal = 0.0;
                        try { numVal = Double.parseDouble(res); } catch (Exception ignored) {}
                        return new Result(numVal, res, res);
                    }
                }
            }
            if ((f.name.equals("d") || f.name.equals("deriv") || f.name.equals("diff")) && f.args.size() == 2) {
                if (!containsStepFunction(f.args.get(0)) && f.args.get(1) instanceof AstVar) {
                    String var = ((AstVar) f.args.get(1)).name;
                    AstNode diffAst = f.args.get(0).diff(var, radiansMode);
                    if (diffAst != null) {
                        String res = diffAst.simplify().toDisplayString();
                        double numVal = 0.0;
                        try { numVal = Double.parseDouble(res); } catch (Exception ignored) {}
                        return new Result(numVal, res, res);
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
            double numVal = 0.0;
            try { numVal = Double.parseDouble(res); } catch (Exception ignored) {}
            return new Result(numVal, res, res);
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
            String s = String.format(Locale.US, "%.12g", v);
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

        int intDigits = (absV < 1.0) ? 1 : ((int) Math.floor(Math.log10(absV)) + 1);
        int maxDecimals = Math.max(0, Math.min(10, 15 - intDigits));
        return trimTrailingZeros(String.format(Locale.US, "%." + maxDecimals + "f", v));
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

    private String symbolicResult(AstNode ast, Value val) {
        return val.rat == null && val.c.im == 0.0 ? tryFormatExactAst(ast) : null;
    }

    private String formatExactVal(Value val, String decFallback, String symbolic) {
        if (val.rat != null) {
            if (val.rat.den.equals(BigInteger.ONE)) {
                return val.rat.num.toString();
            }
            return val.rat.num.toString() + " / " + val.rat.den.toString();
        }
        return symbolic != null ? symbolic : decFallback;
    }

    private String formatReusableVal(Value val, String symbolic) {
        if (val.rat != null) {
            return val.rat.den.equals(BigInteger.ONE)
                    ? val.rat.num.toString()
                    : (val.rat.num.toString() + "/" + val.rat.den.toString());
        }
        if (symbolic != null) {
            String expression = symbolic.replaceAll("(?<=\\d)√", "*√").replaceAll("√(\\d+)", "sqrt($1)");
            return expression.contains("*") ? "(" + expression + ")" : expression;
        }
        if (val.c.im != 0.0) {
            if (val.c.re == 0.0) {
                if (val.c.im == 1.0) return "i";
                if (val.c.im == -1.0) return "-i";
                return Double.toString(val.c.im) + "*i";
            }
            return Double.toString(val.c.re) + (val.c.im >= 0 ? " + " : " - ") + Double.toString(Math.abs(val.c.im)) + "*i";
        }
        return Double.toString(val.c.re);
    }

    private String tryFormatExactAst(AstNode ast) {
        if (ast == null) return null;
        if (ast instanceof AstNeg) {
            String inner = tryFormatExactAst(((AstNeg) ast).inner);
            if (inner == null) return null;
            return inner.startsWith("-") ? inner.substring(1) : "-" + inner;
        }
        if (ast instanceof AstFunc) {
            AstFunc f = (AstFunc) ast;
            if (f.name.equals("sqrt") && f.args.size() == 1) {
                ExactRat argRat = tryExactRatAst(f.args.get(0));
                if (argRat != null && argRat.num.signum() > 0 && argRat.den.equals(BigInteger.ONE)
                        && argRat.num.bitLength() <= 62) {
                    return formatSurd(1, argRat.num.longValue(), 1);
                }
            }
            if ((f.name.equals("sin") || f.name.equals("cos") || f.name.equals("tan")) && f.args.size() == 1) {
                return tryExactTrigAst(f.name, f.args.get(0));
            }
        }
        if (ast instanceof AstDiv) {
            AstDiv d = (AstDiv) ast;
            ExactRat numRat = tryExactRatAst(d.left);
            ExactRat denRat = tryExactRatAst(d.right);
            if (numRat != null && d.right instanceof AstFunc) {
                AstFunc f = (AstFunc) d.right;
                if (f.name.equals("sqrt") && f.args.size() == 1 && numRat.den.equals(BigInteger.ONE)) {
                    ExactRat r = tryExactRatAst(f.args.get(0));
                    if (r != null && r.num.signum() > 0 && r.den.equals(BigInteger.ONE) && r.num.bitLength() <= 30) {
                        return formatSurd(numRat.num, r.num.longValue(), r.num);
                    }
                }
            }
            if (denRat != null && denRat.num.signum() != 0 && denRat.den.equals(BigInteger.ONE)
                    && denRat.num.bitLength() <= 62 && d.left instanceof AstFunc) {
                AstFunc f = (AstFunc) d.left;
                if (f.name.equals("sqrt") && f.args.size() == 1) {
                    ExactRat r = tryExactRatAst(f.args.get(0));
                    if (r != null && r.num.signum() > 0 && r.den.equals(BigInteger.ONE) && r.num.bitLength() <= 62) {
                        return formatSurd(1, r.num.longValue(), denRat.num.longValue());
                    }
                }
            }
        }
        if (ast instanceof AstMul) {
            AstMul m = (AstMul) ast;
            ExactRat lRat = tryExactRatAst(m.left);
            ExactRat rRat = tryExactRatAst(m.right);
            if (lRat != null && lRat.den.equals(BigInteger.ONE) && lRat.num.bitLength() <= 62 && m.right instanceof AstFunc) {
                AstFunc f = (AstFunc) m.right;
                if (f.name.equals("sqrt") && f.args.size() == 1) {
                    ExactRat r = tryExactRatAst(f.args.get(0));
                    if (r != null && r.num.signum() > 0 && r.den.equals(BigInteger.ONE) && r.num.bitLength() <= 62) {
                        return formatSurd(lRat.num.longValue(), r.num.longValue(), 1);
                    }
                }
            }
            if (rRat != null && rRat.den.equals(BigInteger.ONE) && rRat.num.bitLength() <= 62 && m.left instanceof AstFunc) {
                AstFunc f = (AstFunc) m.left;
                if (f.name.equals("sqrt") && f.args.size() == 1) {
                    ExactRat r = tryExactRatAst(f.args.get(0));
                    if (r != null && r.num.signum() > 0 && r.den.equals(BigInteger.ONE) && r.num.bitLength() <= 62) {
                        return formatSurd(rRat.num.longValue(), r.num.longValue(), 1);
                    }
                }
            }
        }
        return null;
    }

    private ExactRat tryExactRatAst(AstNode node) {
        if (node instanceof AstNum) return ((AstNum) node).val.rat;
        if (node instanceof AstNeg) {
            ExactRat r = tryExactRatAst(((AstNeg) node).inner);
            return r != null ? new ExactRat(r.num.negate(), r.den) : null;
        }
        if (node instanceof AstAdd) {
            ExactRat a = tryExactRatAst(((AstAdd) node).left);
            ExactRat b = tryExactRatAst(((AstAdd) node).right);
            return (a != null && b != null) ? a.add(b) : null;
        }
        if (node instanceof AstSub) {
            ExactRat a = tryExactRatAst(((AstSub) node).left);
            ExactRat b = tryExactRatAst(((AstSub) node).right);
            return (a != null && b != null) ? a.sub(b) : null;
        }
        if (node instanceof AstMul) {
            ExactRat a = tryExactRatAst(((AstMul) node).left);
            ExactRat b = tryExactRatAst(((AstMul) node).right);
            return (a != null && b != null) ? a.mul(b) : null;
        }
        if (node instanceof AstDiv) {
            ExactRat a = tryExactRatAst(((AstDiv) node).left);
            ExactRat b = tryExactRatAst(((AstDiv) node).right);
            if (a != null && b != null && b.num.signum() != 0) return a.div(b);
        }
        return null;
    }

    private ExactRat tryPiCoeffAst(AstNode node) {
        if (node instanceof AstVar) {
            String n = ((AstVar) node).name;
            if (n.equals("pi") || n.equals("π")) return new ExactRat(BigInteger.ONE, BigInteger.ONE);
            return null;
        }
        if (node instanceof AstNeg) {
            ExactRat r = tryPiCoeffAst(((AstNeg) node).inner);
            return r != null ? new ExactRat(r.num.negate(), r.den) : null;
        }
        if (node instanceof AstMul) {
            AstMul m = (AstMul) node;
            ExactRat l = tryExactRatAst(m.left);
            ExactRat rp = tryPiCoeffAst(m.right);
            if (l != null && rp != null) return l.mul(rp);
            ExactRat lp = tryPiCoeffAst(m.left);
            ExactRat r = tryExactRatAst(m.right);
            if (lp != null && r != null) return lp.mul(r);
        }
        if (node instanceof AstDiv) {
            AstDiv d = (AstDiv) node;
            ExactRat lp = tryPiCoeffAst(d.left);
            ExactRat r = tryExactRatAst(d.right);
            if (lp != null && r != null && r.num.signum() != 0) return lp.div(r);
        }
        return null;
    }

    private String tryExactTrigAst(String fn, AstNode arg) {
        ExactRat degRat;
        if (radiansMode) {
            ExactRat piCoeff = tryPiCoeffAst(arg);
            if (piCoeff == null) {
                ExactRat z = tryExactRatAst(arg);
                if (z != null && z.num.signum() == 0) piCoeff = z;
                else return null;
            }
            degRat = piCoeff.mul(new ExactRat(BigInteger.valueOf(180), BigInteger.ONE));
        } else {
            degRat = tryExactRatAst(arg);
        }
        if (degRat == null || !degRat.den.equals(BigInteger.ONE)) return null;
        long deg = ((degRat.num.mod(BigInteger.valueOf(360))).longValue() + 360) % 360;
        if (fn.equals("sin")) {
            if (deg == 0 || deg == 180) return "0";
            if (deg == 90) return "1";
            if (deg == 270) return "-1";
            if (deg == 30 || deg == 150) return "1 / 2";
            if (deg == 210 || deg == 330) return "-1 / 2";
            if (deg == 45 || deg == 135) return "√2 / 2";
            if (deg == 225 || deg == 315) return "-√2 / 2";
            if (deg == 60 || deg == 120) return "√3 / 2";
            if (deg == 240 || deg == 300) return "-√3 / 2";
        } else if (fn.equals("cos")) {
            if (deg == 90 || deg == 270) return "0";
            if (deg == 0) return "1";
            if (deg == 180) return "-1";
            if (deg == 60 || deg == 300) return "1 / 2";
            if (deg == 120 || deg == 240) return "-1 / 2";
            if (deg == 45 || deg == 315) return "√2 / 2";
            if (deg == 135 || deg == 225) return "-√2 / 2";
            if (deg == 30 || deg == 330) return "√3 / 2";
            if (deg == 150 || deg == 210) return "-√3 / 2";
        } else if (fn.equals("tan")) {
            if (deg == 0 || deg == 180) return "0";
            if (deg == 45 || deg == 225) return "1";
            if (deg == 135 || deg == 315) return "-1";
            if (deg == 30 || deg == 210) return "√3 / 3";
            if (deg == 150 || deg == 330) return "-√3 / 3";
            if (deg == 60 || deg == 240) return "√3";
            if (deg == 120 || deg == 300) return "-√3";
        }
        return null;
    }

    private String formatSurd(long coeff, long radicand, long den) {
        return formatSurd(BigInteger.valueOf(coeff), radicand, BigInteger.valueOf(den));
    }

    private String formatSurd(BigInteger coeff, long radicand, BigInteger den) {
        if (coeff.signum() == 0) return "0";
        if (den.signum() < 0) {
            coeff = coeff.negate();
            den = den.negate();
        }
        long rem = radicand;
        long out = 1;
        // Factoring a large prime is unnecessary for an equivalent exact expression.
        for (long p = 2; p <= 10000 && p <= rem / p; p++) {
            while (rem % (p * p) == 0) {
                out *= p;
                rem /= (p * p);
            }
        }
        long root = (long) Math.sqrt(rem);
        if (root * root == rem) {
            out *= root;
            rem = 1;
        }
        BigInteger numCoeff = coeff.multiply(BigInteger.valueOf(out));
        BigInteger divisor = numCoeff.gcd(den);
        numCoeff = numCoeff.divide(divisor);
        den = den.divide(divisor);
        if (rem == 1) {
            return den.equals(BigInteger.ONE) ? numCoeff.toString() : (numCoeff + " / " + den);
        }
        String sign = numCoeff.signum() < 0 ? "-" : "";
        BigInteger absCoeff = numCoeff.abs();
        String top = (absCoeff.equals(BigInteger.ONE) ? "" : absCoeff.toString()) + "√" + rem;
        return den.equals(BigInteger.ONE) ? (sign + top) : (sign + top + " / " + den);
    }
}
