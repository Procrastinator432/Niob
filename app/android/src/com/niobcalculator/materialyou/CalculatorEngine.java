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
    public static final double MASS_PROTON = 1.6726e-27;
    public static final double MASS_NEUTRON = 1.6749e-27;
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
            throw new RuntimeException("Zeitüberschreitung bei der Berechnung");
        }
    }

    private final Map<String, Value> variables = new HashMap<>();
    private Value lastAns = Value.fromInt(0);
    private boolean radiansMode = true;
    private boolean moduloAlwaysPositive = true;
    private IntegrationReport lastIntegrationReport = null;

    public CalculatorEngine() {
        initConstantsAndVariables();
    }

    CalculatorEngine snapshotForPlot() {
        return new CalculatorEngine(this);
    }

    private CalculatorEngine(CalculatorEngine source) {
        variables.putAll(source.variables);
        lastAns = source.lastAns;
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
        AstNode parsedAst = AstParser.tryParse(trimmed);

        // 1. Variable Assignment `var := expr`
        int assignIdx = trimmed.indexOf(":=");
        if (assignIdx >= 0) {
            String vname = trimmed.substring(0, assignIdx).trim();
            if (!isUserVariable(vname)) {
                throw new RuntimeException("Unbekannte Variable: " + vname);
            }
            String rhs = trimmed.substring(assignIdx + 2).trim();
            AstNode rhsAst = AstParser.tryParse(rhs);
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
                lastAns = rhsVal;
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
                Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
                int prevDepth = INTEGRATION_DEPTH.get();
                boolean hasInnerInt = containsIntegralNode(f.args.get(0));
                boolean hasTrig = hasTrigFunction(f.args.get(0));
                int initialSubintervals = (prevDepth > 0 || hasInnerInt || !hasTrig) ? 16 : 500;
                INTEGRATION_DEPTH.set(prevDepth + 1);
                try {
                    IntegrationReport report = adaptiveGaussIntegrate(x -> {
                        local.put(v, Value.fromFloat(x));
                        Value val = evalAst(f.args.get(0), local);
                        if (!val.c.isReal() || !Double.isFinite(val.c.re)) {
                            throw new RuntimeException("Integrand liefert nicht-reellen oder unendlichen Wert");
                        }
                        return val.c.re;
                    }, aVal.c.re, bVal.c.re, initialSubintervals, 1e-10, 1e-8);
                    lastIntegrationReport = report;
                    return Value.fromFloat(report.value);
                } finally {
                    INTEGRATION_DEPTH.set(prevDepth);
                }
            }
            if (fn.equals("integral") && f.args.size() == 2) {
                String v = calculusVariable(f.args.get(1).toDisplayString());
                AstNode iNode = f.args.get(0).integrate(v);
                if (iNode == null) throw new RuntimeException("Symbolische Integration nicht möglich");
                return evalAst(iNode, ov);
            }
            if ((fn.equals("deriv") || fn.equals("diff") || fn.equals("d")) && f.args.size() >= 2) {
                String v = calculusVariable(f.args.get(1).toDisplayString());
                if (f.args.size() == 2) {
                    AstNode dNode = f.args.get(0).diff(v, radiansMode);
                    if (dNode == null) throw new RuntimeException("Symbolische Ableitung nicht möglich");
                    return evalAst(dNode, ov);
                }
                Value x0Val = evalAst(f.args.get(2), ov);
                if (!x0Val.c.isReal() || !Double.isFinite(x0Val.c.re)) {
                    throw new RuntimeException("Ableitungsstelle muss eine endliche reelle Zahl sein");
                }
                double x0 = x0Val.c.re;
                double h = (Math.abs(x0) > 1e10) ? (1e-3 * Math.abs(x0)) : ((Math.abs(x0) > 1e4) ? 1e-3 : 1e-4);
                Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
                evalRealDerivativeSample(f.args.get(0), local, v, x0);
                double fp2 = evalRealDerivativeSample(f.args.get(0), local, v, x0 + 2.0 * h);
                double fp1 = evalRealDerivativeSample(f.args.get(0), local, v, x0 + h);
                double fm1 = evalRealDerivativeSample(f.args.get(0), local, v, x0 - h);
                double fm2 = evalRealDerivativeSample(f.args.get(0), local, v, x0 - 2.0 * h);
                return Value.fromFloat((-fp2 + 8.0 * fp1 - 8.0 * fm1 + fm2) / (12.0 * h));
            }
            if (fn.equals("det") && f.args.size() == 1) {
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
    private Value evalExpression(String expr, Map<String, Value> overrides) {
        return new ExprEvaluator(normalizeEvaluation(expr), overrides).parse();
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
                } else if (ch == '(' || ch == '√' || Character.isLetter(ch) || ch == '_') {
                    // Implicit multiplication
                    Value right = parseUnary();
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
        Subinterval(double a, double b, double val5, double err) {
            this.a = a; this.b = b; this.val5 = val5; this.err = err;
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
            checkInterrupted();
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

    private static String calculusVariable(String text) {
        String name = text.trim();
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) {
            throw new RuntimeException("Bitte eine Variable angeben");
        }
        return name;
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
            double a = aVal.c.re;
            double b = bVal.c.re;
            String exprStr = args.get(0);
            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            int prevDepth = INTEGRATION_DEPTH.get();
            boolean hasInnerInt = exprStr.contains("int(") || exprStr.contains("defint(");
            boolean hasTrig = exprStr.contains("sin") || exprStr.contains("cos") || exprStr.contains("tan");
            int initialSubintervals = (prevDepth > 0 || hasInnerInt || !hasTrig) ? 16 : 500;
            INTEGRATION_DEPTH.set(prevDepth + 1);
            try {
                IntegrationReport report = adaptiveGaussIntegrate(x -> {
                    local.put(v, Value.fromFloat(x));
                    Value val = evalExpression(exprStr, local);
                    if (!val.c.isReal() || !Double.isFinite(val.c.re)) {
                        throw new RuntimeException("Integrand liefert nicht-reellen oder unendlichen Wert");
                    }
                    return val.c.re;
                }, a, b, initialSubintervals, 1e-10, 1e-8);
                lastIntegrationReport = report;
                return Value.fromFloat(report.value);
            } finally {
                INTEGRATION_DEPTH.set(prevDepth);
            }
        }

        if ((fn.equals("deriv") || fn.equals("diff") || fn.equals("d")) && args.size() >= 2) {
            if (args.size() == 2) {
                throw new RuntimeException("Symbolische Ableitung erforderlich");
            }
            String v = calculusVariable(args.get(1));
            Value x0Val = evalExpression(args.get(2), ov);
            if (!x0Val.c.isReal() || !Double.isFinite(x0Val.c.re)) {
                throw new RuntimeException("Ableitungsstelle muss eine endliche reelle Zahl sein");
            }
            double x0 = x0Val.c.re;
            double h = (Math.abs(x0) > 1e10) ? (1e-3 * Math.abs(x0)) : ((Math.abs(x0) > 1e4) ? 1e-3 : 1e-4);

            Map<String, Value> local = ov != null ? new HashMap<>(ov) : new HashMap<>();
            evalRealDerivativeSampleExpr(args.get(0), local, v, x0);
            double fp2 = evalRealDerivativeSampleExpr(args.get(0), local, v, x0 + 2.0 * h);
            double fp1 = evalRealDerivativeSampleExpr(args.get(0), local, v, x0 + h);
            double fm1 = evalRealDerivativeSampleExpr(args.get(0), local, v, x0 - h);
            double fm2 = evalRealDerivativeSampleExpr(args.get(0), local, v, x0 - 2.0 * h);

            double deriv = (-fp2 + 8.0 * fp1 - 8.0 * fm1 + fm2) / (12.0 * h);
            return Value.fromFloat(deriv);
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

    private static boolean containsMatrixExpression(AstNode node) {
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
                for (int k = 0; k < inner; k++) {
                    sum += left[r][k] * right[k][c];
                }
                result[r][c] = sum;
            }
        }
        return result;
    }

    private static double[][] scaleMatrix(double[][] m, double s) {
        double[][] res = new double[m.length][m[0].length];
        for (int r = 0; r < m.length; r++) {
            for (int c = 0; c < m[r].length; c++) {
                res[r][c] = m[r][c] * s;
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
        for (int r = 0; r < left.length; r++) {
            for (int c = 0; c < left[r].length; c++) left[r][c] += sign * right[r][c];
        }
        return left;
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

        static ExactRat evalConstantRat(AstNode node) {
            if (node instanceof AstNum) return ((AstNum) node).val.rat;
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
        @Override AstNode diff(String var, boolean radiansMode) { return name.equals(var) ? new AstNum(1) : new AstNum(0); }
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
        @Override AstNode diff(String var, boolean radiansMode) {
            AstNode dl = left.diff(var, radiansMode);
            AstNode dr = right.diff(var, radiansMode);
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
        @Override AstNode diff(String var, boolean radiansMode) {
            AstNode dl = left.diff(var, radiansMode);
            AstNode dr = right.diff(var, radiansMode);
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
        @Override AstNode diff(String var, boolean radiansMode) {
            // (a * b)' = a' * b + a * b'
            AstNode dl = left.diff(var, radiansMode);
            AstNode dr = right.diff(var, radiansMode);
            if (dl == null || dr == null) return null;
            return new AstAdd(new AstMul(dl, right), new AstMul(left, dr));
        }
        @Override AstNode integrate(String var) {
            if (!left.containsVar(var)) {
                AstNode ir = right.integrate(var);
                return ir != null ? new AstMul(left, ir).simplify() : null;
            }
            if (!right.containsVar(var)) {
                AstNode il = left.integrate(var);
                return il != null ? new AstMul(il, right).simplify() : null;
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
        @Override AstNode integrate(String var) {
            if (!right.containsVar(var)) {
                AstNode il = left.integrate(var);
                return il != null ? new AstDiv(il, right).simplify() : null;
            }
            if (!left.containsVar(var) && right instanceof AstVar && ((AstVar) right).name.equals(var)) {
                AstNode ln = new AstFunc("ln", java.util.Collections.singletonList(new AstVar(var)));
                return left.isOne() ? ln : new AstMul(left, ln);
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
        @Override AstNode integrate(String var) {
            if (base instanceof AstVar && ((AstVar) base).name.equals(var) && !exp.containsVar(var)) {
                ExactRat expRat = evalConstantRat(exp);
                if (expRat != null) {
                    if (expRat.den.equals(BigInteger.ONE) && expRat.num.equals(BigInteger.valueOf(-1))) {
                        return new AstFunc("ln", java.util.Collections.singletonList(new AstVar(var)));
                    }
                    ExactRat newExp = expRat.add(ExactRat.of(1));
                    if (newExp.den.equals(BigInteger.ONE)) {
                        return new AstDiv(
                                new AstPow(new AstVar(var), new AstNum(new Value(Complex.real(newExp.toDouble()), newExp))),
                                new AstNum(new Value(Complex.real(newExp.toDouble()), newExp))
                        );
                    }
                    ExactRat coeff = ExactRat.of(1).div(newExp);
                    return new AstMul(
                            new AstNum(new Value(Complex.real(coeff.toDouble()), coeff)),
                            new AstPow(new AstVar(var), new AstNum(new Value(Complex.real(newExp.toDouble()), newExp)))
                    );
                }
                Value cVal = exp.constValue();
                if (cVal != null && cVal.c.isReal()) {
                    if (Math.abs(cVal.c.re + 1.0) < 1e-11) {
                        return new AstFunc("ln", java.util.Collections.singletonList(new AstVar(var)));
                    }
                    if (Math.abs(cVal.c.re - Math.round(cVal.c.re)) < 1e-11) {
                        long newP = Math.round(cVal.c.re) + 1;
                        return new AstDiv(new AstPow(new AstVar(var), new AstNum(newP)), new AstNum(newP));
                    }
                    double newP = cVal.c.re + 1.0;
                    return new AstDiv(new AstPow(new AstVar(var), new AstNum(Value.fromFloat(newP))), new AstNum(Value.fromFloat(newP)));
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
        @Override AstNode integrate(String var) {
            if (args.size() != 1) return null;
            AstNode u = args.get(0);
            if (!u.containsVar(var)) {
                return new AstMul(this, new AstVar(var));
            }
            LinearMatch lin = matchLinear(u, var);
            if (lin == null) return null;
            AstNode a = lin.a;
            if (a.isZero()) return null;
            boolean unitA = a.isOne();
            AstNode uNode = u;
            switch (name) {
                case "sqrt": {
                    ExactRat threeHalves = new ExactRat(BigInteger.valueOf(3), BigInteger.valueOf(2));
                    AstNode expNode = new AstNum(new Value(Complex.real(1.5), threeHalves));
                    if (unitA) {
                        ExactRat twoThirds = new ExactRat(BigInteger.valueOf(2), BigInteger.valueOf(3));
                        return new AstMul(
                                new AstNum(new Value(Complex.real(2.0 / 3.0), twoThirds)),
                                new AstPow(uNode, expNode)
                        );
                    }
                    AstNode denom = new AstMul(new AstNum(3), a).simplify();
                    return new AstDiv(new AstMul(new AstNum(2), new AstPow(uNode, expNode)), denom);
                }
                case "exp": {
                    AstNode base = new AstFunc("exp", java.util.Collections.singletonList(uNode));
                    return unitA ? base : new AstDiv(base, a);
                }
                case "sin": {
                    AstNode cosU = new AstFunc("cos", java.util.Collections.singletonList(uNode));
                    if (unitA) return new AstNeg(cosU);
                    if (a instanceof AstNeg) return new AstDiv(cosU, ((AstNeg) a).inner);
                    if (a instanceof AstNum && ((AstNum) a).isNegativeDisplay()) {
                        return new AstDiv(cosU, new AstNeg(a).simplify());
                    }
                    return new AstNeg(new AstDiv(cosU, a));
                }
                case "cos": {
                    AstNode sinU = new AstFunc("sin", java.util.Collections.singletonList(uNode));
                    return unitA ? sinU : new AstDiv(sinU, a);
                }
                case "sinh": {
                    AstNode coshU = new AstFunc("cosh", java.util.Collections.singletonList(uNode));
                    return unitA ? coshU : new AstDiv(coshU, a);
                }
                case "cosh": {
                    AstNode sinhU = new AstFunc("sinh", java.util.Collections.singletonList(uNode));
                    return unitA ? sinhU : new AstDiv(sinhU, a);
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
            ExactRat r = v.rat;
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
            ExactRat scalar = AstNode.evalConstantRat(((AstDiv) node).right);
            if (l == null || scalar == null || scalar.num.signum() == 0) return null;
            java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
            for (Map.Entry<Monomial, ExactRat> e : l.entrySet()) {
                ExactRat c = e.getValue().div(scalar);
                if (c.num.signum() != 0) res.put(e.getKey(), c);
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
            if (expVal > 8) return null;
            java.util.TreeMap<Monomial, ExactRat> base = astToPoly(p.base);
            if (base == null) return null;
            java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
            res.put(new Monomial(), ExactRat.of(1));
            for (int i = 0; i < expVal; i++) {
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
        if ((long) l.size() * (long) r.size() > 128) return null;
        java.util.TreeMap<Monomial, ExactRat> res = new java.util.TreeMap<>();
        for (Map.Entry<Monomial, ExactRat> el : l.entrySet()) {
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

    public static AstNode simplifyPolynomialAst(AstNode node) {
        if (containsAddOrSub(node)) {
            java.util.TreeMap<Monomial, ExactRat> poly = astToPoly(node);
            if (poly != null) {
                AstNode expanded = polyToAst(poly);
                if (poly.size() > 1 || !expanded.equals(node)) {
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
                } else if (ch == '(' || ch == '√' || Character.isLetter(ch) || ch == '_') {
                    AstNode right = parseUnary();
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
                AstNode x = parseExpression();
                if (!eat(')')) throw new RuntimeException("Expected ')'");
                return x;
            }

            if (eat('[')) {
                List<AstNode> entries = new ArrayList<>();
                do {
                    entries.add(parseExpression());
                } while (eat(','));
                if (!eat(']')) throw new RuntimeException("Expected ']'");
                return new AstFunc("[]", entries);
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
                        double numVal = 0.0;
                        try { numVal = Double.parseDouble(res); } catch (Exception ignored) {}
                        return new Result(numVal, res, res);
                    }
                }
            }
            if ((f.name.equals("d") || f.name.equals("deriv") || f.name.equals("diff")) && f.args.size() == 2) {
                if (f.args.get(1) instanceof AstVar) {
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
