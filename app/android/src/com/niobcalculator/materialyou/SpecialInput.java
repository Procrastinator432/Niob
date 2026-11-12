package com.niobcalculator.materialyou;

/** Function names for evaluation and their editable argument labels. */
enum SpecialInput {
    FRACTION("", "", "Zähler", "Nenner"),
    POWER("", "", "Basis", "Exponent"),
    MATRIX("", ""),
    SYMBOL("", ""),
    ROOT("sqrt(", "√", "Radikand"),
    INDEXED_ROOT("nthroot(", "√", "Wurzelindex", "Radikand"),
    CUBE_ROOT("nthroot(", "√", "Radikand"),
    LN("ln(", "ln", "Argument"),
    LOG("log(", "log", "Logarithmusbasis", "Argument"),
    LOG10("log(", "log", "Argument"),
    SIN("sin(", "sin", "Winkel"),
    ASIN("asin(", "sin⁻¹", "Argument"),
    COS("cos(", "cos", "Winkel"),
    ACOS("acos(", "cos⁻¹", "Argument"),
    TAN("tan(", "tan", "Winkel"),
    ATAN("atan(", "tan⁻¹", "Argument"),
    SINH("sinh(", "sinh", "Argument"),
    ASINH("asinh(", "sinh⁻¹", "Argument"),
    COSH("cosh(", "cosh", "Argument"),
    ACOSH("acosh(", "cosh⁻¹", "Argument"),
    TANH("tanh(", "tanh", "Argument"),
    ATANH("atanh(", "tanh⁻¹", "Argument"),
    SUM("sum(", "∑", "Summand", "Laufvariable", "Untergrenze", "Obergrenze"),
    PRODUCT("prod(", "∏", "Faktor", "Laufvariable", "Untergrenze", "Obergrenze"),
    INTEGRAL("int(", "∫", "Integrand", "Variable", "Untergrenze", "Obergrenze"),
    ANTIDERIVATIVE("integral(", "∫", "Integrand", "Variable"),
    DERIVATIVE("deriv(", "d", "Funktion", "Variable"),
    DERIVATIVE_AT("d(", "d", "Funktion", "Variable", "Auswertungsstelle"),
    BINOMIAL("binomial(", "", "n", "k"),
    MODULO("mod(", "mod", "Dividend", "Divisor"),
    ABS("abs(", "", "Betrag von"),
    FLOOR("floor(", "", "Abrunden"),
    CEIL("ceil(", "", "Aufrunden"),
    ROUND("round(", "round", "Argument"),
    GCD("gcd(", "ggT", "Erste Zahl", "Zweite Zahl"),
    LCM("lcm(", "kgV", "Erste Zahl", "Zweite Zahl"),
    SIGN("sign(", "sgn", "Argument"),
    EXP("exp(", "e", "Exponent"),
    DET("det(", "det", "Matrixausdruck"),
    INVERSE("inv(", "⁻¹", "Matrixausdruck"),
    TRANSPOSE("trans(", "ᵀ", "Matrixausdruck");

    final String prefix;
    final String notation;
    final String[] fields;

    SpecialInput(String prefix, String notation, String... fields) {
        this.prefix = prefix;
        this.notation = notation;
        this.fields = fields;
    }

    static SpecialInput forKey(String key) {
        if (!key.endsWith("(") || key.length() == 1) return null;
        for (SpecialInput input : values()) {
            if (input.prefix.equals(key)) return input;
        }
        return null;
    }

    boolean wrapsOperand() {
        return this == FRACTION || this == POWER;
    }
}
