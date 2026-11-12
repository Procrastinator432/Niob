package com.niobcalculator.materialyou;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.niobcalculator.materialyou.CalculatorEngine.AstAdd;
import com.niobcalculator.materialyou.CalculatorEngine.AstDiv;
import com.niobcalculator.materialyou.CalculatorEngine.AstFunc;
import com.niobcalculator.materialyou.CalculatorEngine.AstMul;
import com.niobcalculator.materialyou.CalculatorEngine.AstNeg;
import com.niobcalculator.materialyou.CalculatorEngine.AstNode;
import com.niobcalculator.materialyou.CalculatorEngine.AstNum;
import com.niobcalculator.materialyou.CalculatorEngine.AstParser;
import com.niobcalculator.materialyou.CalculatorEngine.AstPow;
import com.niobcalculator.materialyou.CalculatorEngine.AstSub;
import com.niobcalculator.materialyou.CalculatorEngine.AstVar;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The draft keeps its editable layout independently of the expression sent to the calculator. */
final class FormulaEditor extends LinearLayout {
    private static final char TEMPLATE = '\uFFFC';
    private static final Pattern OPERAND = Pattern.compile(
            "(?:[\\p{L}_][\\p{L}\\p{N}_]*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+−-]?\\d+)?|%)$");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*$");
    private static final Pattern EXACT_FRACTION = Pattern.compile(
            "^([+−-]?\\d+)\\s*[/÷]\\s*([+−-]?\\d+)$");
    private static final Pattern LINEAR_NOTATION = Pattern.compile(
            "[*/^÷√²³⁻]|\\bpi\\b|[\\p{L}_][\\p{L}\\p{N}_]*\\s*\\(|\\[\\s*\\[");

    private interface Part {
        int width();
    }

    private static final class Text implements Part {
        final Row row;
        String value;
        EditText field;

        Text(Row row, String value) {
            this.row = row;
            this.value = value;
        }

        @Override public int width() {
            return value.length();
        }
    }

    private static final class Template implements Part {
        final SpecialInput kind;
        final List<Row> rows = new ArrayList<>();
        final int columns;
        final String symbol;
        final String token;
        Row parent;

        Template(SpecialInput kind) {
            this(kind, kind.fields.length, 0, "", "");
        }

        Template(SpecialInput kind, int fields, int columns, String symbol, String token) {
            this.kind = kind;
            this.columns = columns;
            this.symbol = symbol;
            this.token = token;
            for (int i = 0; i < fields; i++) rows.add(new Row(this));
        }

        @Override public int width() {
            return 1;
        }

        Row row(int index) {
            return rows.get(index);
        }

        String expression() {
            if (kind == SpecialInput.SYMBOL) return token;
            if (kind.wrapsOperand()) {
                String operator = kind == SpecialInput.FRACTION ? "/" : "^";
                return "((" + row(0).expression() + ")" + operator + "(" + row(1).expression() + "))";
            }
            if (kind == SpecialInput.MATRIX) {
                StringBuilder matrix = new StringBuilder("[");
                for (int i = 0; i < rows.size(); i += columns) {
                    if (i > 0) matrix.append(',');
                    matrix.append('[').append(arguments(i, i + columns)).append(']');
                }
                return matrix.append(']').toString();
            }
            return kind.prefix + arguments(0, rows.size()) + ")";
        }

        private String arguments(int start, int end) {
            StringBuilder result = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start) result.append(',');
                result.append(row(i).expression());
            }
            return result.toString();
        }

        JSONArray encode() {
            JSONArray data = new JSONArray().put(kind.name());
            if (kind == SpecialInput.SYMBOL) return data.put(symbol).put(token);
            if (kind == SpecialInput.MATRIX) data.put(columns);
            for (Row row : rows) data.put(row.encode());
            return data;
        }

        static Template decode(JSONArray data) throws JSONException {
            Object type = data.get(0);
            SpecialInput kind;
            try {
                kind = type instanceof Boolean
                        ? ((Boolean) type ? SpecialInput.FRACTION : SpecialInput.POWER)
                        : SpecialInput.valueOf(data.getString(0));
            } catch (IllegalArgumentException e) {
                throw new JSONException("Unknown formula template");
            }
            if (kind == SpecialInput.SYMBOL) {
                return new Template(kind, 0, 0, data.getString(1), data.getString(2));
            }
            boolean matrix = kind == SpecialInput.MATRIX;
            int columns = matrix ? data.getInt(1) : 0;
            int offset = matrix ? 2 : 1;
            int count = data.length() - offset;
            if (matrix ? columns <= 0 || count <= 0 || count % columns != 0
                    : count != kind.fields.length) {
                throw new JSONException("Invalid formula fields");
            }
            Template template = new Template(kind, count, columns, "", "");
            for (int i = 0; i < count; i++) template.row(i).decode(data.getJSONArray(i + offset));
            return template;
        }
    }

    private static final class Row {
        final Template owner;
        final List<Part> parts = new ArrayList<>();

        Row(Template owner) {
            this.owner = owner;
            reset();
        }

        void reset() {
            parts.clear();
            parts.add(new Text(this, ""));
        }

        Text last() {
            return (Text) parts.get(parts.size() - 1);
        }

        void append(String value) {
            Text tail = last();
            tail.value += value;
        }

        void append(Template template) {
            template.parent = this;
            parts.add(template);
            parts.add(new Text(this, ""));
        }

        void appendRange(Row source, int start, int end) {
            int offset = 0;
            for (Part part : source.parts) {
                int next = offset + part.width();
                if (next > start && offset < end) {
                    if (part instanceof Template) {
                        append((Template) part);
                    } else {
                        String value = ((Text) part).value;
                        append(value.substring(Math.max(start - offset, 0), Math.min(end, next) - offset));
                    }
                }
                offset = next;
            }
        }

        String expression() {
            StringBuilder result = new StringBuilder();
            for (Part part : parts) {
                result.append(part instanceof Text ? ((Text) part).value : ((Template) part).expression());
            }
            return result.toString();
        }

        Text firstEmptyField() {
            if (owner != null && parts.size() == 1 && last().value.trim().isEmpty()) return last();
            for (Part part : parts) {
                if (part instanceof Template) {
                    for (Row field : ((Template) part).rows) {
                        Text missing = field.firstEmptyField();
                        if (missing != null) return missing;
                    }
                }
            }
            return null;
        }

        JSONArray encode() {
            JSONArray encoded = new JSONArray();
            for (Part part : parts) {
                if (part instanceof Text) {
                    encoded.put(((Text) part).value);
                } else {
                    encoded.put(((Template) part).encode());
                }
            }
            return encoded;
        }

        void decode(JSONArray encoded) throws JSONException {
            for (int i = 0; i < encoded.length(); i++) {
                Object part = encoded.get(i);
                if (part instanceof String) {
                    append((String) part);
                } else {
                    append(Template.decode(encoded.getJSONArray(i)));
                }
            }
        }
    }

    private final Row root = new Row(null);
    private final HorizontalScrollView scroll;
    private final Button next;
    private final Button exit;
    private final int foreground;
    private final int accent;
    private final Consumer<FormulaEditor> onChange;
    private final Runnable onEnter;
    private final String[] deleteTokens;
    private Text active = root.last();
    private float baseSize = 24f;
    private boolean rendering;
    private boolean restoreFocus;
    private boolean systemKeyboard;
    private String rootFieldDescription = "input_expression";
    private EditText errorField;

    FormulaEditor(Context context, int foreground, int accent, Consumer<FormulaEditor> onChange,
                  Runnable onEnter, String[] deleteTokens) {
        super(context);
        this.foreground = foreground;
        this.accent = accent;
        this.onChange = onChange;
        this.onEnter = onEnter;
        this.deleteTokens = deleteTokens;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);

        scroll = new HorizontalScrollView(context);
        scroll.setFillViewport(true);
        scroll.setHorizontalScrollBarEnabled(false);
        addView(new FormulaViewport(scroll), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        next = navigationButton("↹", "Nächstes Feld", () -> advanceField());
        exit = navigationButton("→", "Zur Grundlinie", this::leaveTemplate);
    }

    private Button navigationButton(String glyph, String label, Runnable action) {
        Button button = new Button(getContext());
        button.setText(glyph);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setTextColor(accent);
        button.setTextSize(22);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setOnClickListener(view -> action.run());
        addView(button, new LayoutParams(dp(48), dp(48)));
        return button;
    }

    private final class FormulaViewport extends ScrollView {
        FormulaViewport(View content) {
            super(FormulaEditor.this.getContext());
            setVerticalScrollBarEnabled(false);
            addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override protected void onMeasure(int width, int height) {
            int windowLimit = Math.round(getResources().getDisplayMetrics().heightPixels * 0.34f);
            int limit = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? windowLimit
                    : Math.min(MeasureSpec.getSize(height), windowLimit);
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    void restore(String layout, String fallback) {
        root.reset();
        if (!decode(root, layout)) root.append(fallback);
        structurePlotNotation();
        active = root.last();
    }

    void enableSystemKeyboard(String fieldDescription) {
        systemKeyboard = true;
        rootFieldDescription = fieldDescription;
    }

    void replaceDraft(String layout, String fallback) {
        restore(layout, fallback);
        render(active.value.length(), false);
        onChange.accept(this);
    }

    private static boolean decode(Row row, String layout) {
        if (layout == null || layout.isEmpty()) return false;
        try {
            row.decode(new JSONArray(layout));
            return true;
        } catch (JSONException e) {
            row.reset();
            return false;
        }
    }

    private boolean structurePlotNotation() {
        if (!systemKeyboard || hasMissingField() || !hasLinearNotation(root)) return false;
        AstNode expression = AstParser.tryParseNotation(root.expression());
        if (expression == null) return false;

        Row formatted = new Row(null);
        appendNotation(formatted, expression, 0);
        if (root.encode().toString().equals(formatted.encode().toString())) return false;
        root.reset();
        root.appendRange(formatted, 0, symbolLength(formatted));
        active = root.last();
        return true;
    }

    private static boolean hasLinearNotation(Row row) {
        for (Part part : row.parts) {
            if (part instanceof Text) {
                if (LINEAR_NOTATION.matcher(((Text) part).value).find()) return true;
            } else {
                for (Row field : ((Template) part).rows) {
                    if (hasLinearNotation(field)) return true;
                }
            }
        }
        return false;
    }

    private static int notationPrecedence(AstNode node) {
        if (node instanceof AstAdd || node instanceof AstSub) return 1;
        if (node instanceof AstMul || node instanceof AstFunc && ((AstFunc) node).name.equals(" mod ")) return 2;
        if (node instanceof AstNeg) return 3;
        if (node instanceof AstPow) return 4;
        return 5;
    }

    private static void appendNotation(Row row, AstNode node, int minimumPrecedence) {
        int precedence = notationPrecedence(node);
        boolean parentheses = precedence < minimumPrecedence;
        if (parentheses) row.append("(");
        if (node instanceof AstAdd) {
            AstAdd addition = (AstAdd) node;
            appendBinary(row, addition.left, " + ", addition.right, precedence);
        } else if (node instanceof AstSub) {
            AstSub subtraction = (AstSub) node;
            appendBinary(row, subtraction.left, " − ", subtraction.right, precedence);
        } else if (node instanceof AstMul) {
            AstMul multiplication = (AstMul) node;
            appendBinary(row, multiplication.left, " × ", multiplication.right, precedence);
        } else if (node instanceof AstDiv) {
            AstDiv division = (AstDiv) node;
            Template fraction = new Template(SpecialInput.FRACTION);
            appendNotation(fraction.row(0), division.left, 0);
            appendNotation(fraction.row(1), division.right, 0);
            row.append(fraction);
        } else if (node instanceof AstPow) {
            AstPow power = (AstPow) node;
            Template template = new Template(SpecialInput.POWER);
            appendNotation(template.row(0), power.base, 5);
            appendNotation(template.row(1), power.exp, 0);
            row.append(template);
        } else if (node instanceof AstNeg) {
            row.append("−");
            appendNotation(row, ((AstNeg) node).inner, precedence);
        } else if (node instanceof AstFunc) {
            appendFunction(row, (AstFunc) node, precedence);
        } else if (node instanceof AstNum) {
            row.append(((AstNum) node).literal);
        } else if (node instanceof AstVar && ((AstVar) node).name.equals("pi")) {
            row.append(new Template(SpecialInput.SYMBOL, 0, 0, "π", "pi"));
        } else {
            row.append(node.toDisplayString());
        }
        if (parentheses) row.append(")");
    }

    private static void appendBinary(Row row, AstNode left, String operator, AstNode right, int precedence) {
        appendNotation(row, left, precedence);
        row.append(operator);
        appendNotation(row, right, precedence + 1);
    }

    private static void appendFunction(Row row, AstFunc call, int precedence) {
        if (call.name.equals("!") || call.name.equals("%")) {
            appendNotation(row, call.args.get(0), precedence);
            row.append(call.name);
        } else if (call.name.equals(" mod ")) {
            appendBinary(row, call.args.get(0), call.name, call.args.get(1), precedence);
        } else if (call.name.equals("[]")) {
            appendMatrixNotation(row, call);
        } else {
            SpecialInput kind = notationFunction(call);
            if (kind != null && kind.fields.length == call.args.size()) {
                Template template = new Template(kind);
                for (int i = 0; i < call.args.size(); i++) {
                    appendNotation(template.row(i), call.args.get(i), 0);
                }
                row.append(template);
            } else {
                appendCall(row, call.name + "(", call.args, ")");
            }
        }
    }

    private static SpecialInput notationFunction(AstFunc call) {
        switch (call.name) {
            case "log": return call.args.size() == 1 ? SpecialInput.LOG10 : SpecialInput.LOG;
            case "nthroot": return call.args.size() == 1 ? SpecialInput.CUBE_ROOT : SpecialInput.INDEXED_ROOT;
            case "product": return SpecialInput.PRODUCT;
            case "defint": return SpecialInput.INTEGRAL;
            case "deriv":
            case "diff":
            case "d": return call.args.size() == 3 ? SpecialInput.DERIVATIVE_AT : SpecialInput.DERIVATIVE;
            case "nCr": return SpecialInput.BINOMIAL;
            case "sgn": return SpecialInput.SIGN;
            case "transpose": return SpecialInput.TRANSPOSE;
            default: return SpecialInput.forKey(call.name + "(");
        }
    }

    private static void appendMatrixNotation(Row row, AstFunc matrix) {
        List<AstNode> cells = new ArrayList<>();
        int columns = 0;
        for (AstNode entry : matrix.args) {
            if (!(entry instanceof AstFunc) || !((AstFunc) entry).name.equals("[]")) {
                appendCall(row, "[", matrix.args, "]");
                return;
            }
            List<AstNode> entries = ((AstFunc) entry).args;
            if (columns == 0) columns = entries.size();
            if (entries.size() != columns) {
                appendCall(row, "[", matrix.args, "]");
                return;
            }
            cells.addAll(entries);
        }
        Template template = new Template(SpecialInput.MATRIX, cells.size(), columns, "", "");
        for (int i = 0; i < cells.size(); i++) {
            appendNotation(template.row(i), cells.get(i), 0);
        }
        row.append(template);
    }

    private static void appendCall(Row row, String opening, List<AstNode> args, String closing) {
        row.append(opening);
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) row.append(", ");
            appendNotation(row, args.get(i), 0);
        }
        row.append(closing);
    }

    private void commitPlotNotation() {
        if (!structurePlotNotation()) return;
        render(active.value.length(), false);
        onChange.accept(this);
    }

    void finishSystemEditing() {
        if (!systemKeyboard || !hasFocus()) return;
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        clearFocus();
        commitPlotNotation();
    }

    String expression() {
        return root.expression();
    }

    String layoutState() {
        return root.parts.size() == 1 ? "" : root.encode().toString();
    }

    boolean hasMissingField() {
        return root.firstEmptyField() != null;
    }

    boolean focusMissingField() {
        Text missing = root.firstEmptyField();
        if (missing == null) return false;
        focus(missing, 0);
        return true;
    }

    EditText activeField() {
        return active.field;
    }

    void setInputError(String error) {
        EditText field = activeField();
        if (errorField != null && errorField != field) errorField.setError(null);
        if (field != null) field.setError(error);
        errorField = error == null ? null : field;
    }

    boolean hasTemplateBeforeCaret() {
        return active.row.parts.indexOf(active) > 0;
    }

    void show(float size) {
        boolean structured = structurePlotNotation();
        int caret = structured ? active.value.length() : selectionEnd();
        baseSize = size;
        render(caret, restoreFocus && !systemKeyboard);
        if (structured) onChange.accept(this);
    }

    View historyView(String layout, float size, Runnable onClick) {
        Row row = new Row(null);
        if (!decode(row, layout)) return null;
        HorizontalScrollView container = new HorizontalScrollView(getContext());
        container.setHorizontalScrollBarEnabled(false);
        View expression = buildRow(row, size, false);
        expression.setOnClickListener(view -> onClick.run());
        container.addView(expression);
        return container;
    }

    boolean restoreHistory(String layout) {
        if (!expression().trim().isEmpty() || !decode(root, layout)) return false;
        active = root.last();
        render(active.value.length(), true);
        onChange.accept(this);
        return true;
    }

    void clear() {
        root.reset();
        active = root.last();
        render(0, true);
        onChange.accept(this);
    }

    void insert(String value) {
        replace(selectionStart(), selectionEnd(), value);
    }

    void replace(int start, int end, String value) {
        EditText field = active.field;
        if (field != null) {
            field.getText().replace(start, end, value);
            field.setSelection(start + value.length());
            if (isAttachedToWindow()) focus(active, start + value.length());
        } else {
            active.value = active.value.substring(0, start) + value + active.value.substring(end);
            onChange.accept(this);
        }
    }

    void fraction() {
        place(new Template(SpecialInput.FRACTION), null, "");
    }

    boolean insertExactFraction(String expression) {
        Matcher match = EXACT_FRACTION.matcher(expression.trim());
        if (!match.matches()) return false;
        Template fraction = new Template(SpecialInput.FRACTION);
        fraction.row(0).append(match.group(1));
        fraction.row(1).append(match.group(2));
        place(fraction, null, "");
        focus(adjacentField(fraction, true), 0);
        return true;
    }

    void power(String base, String exponent) {
        place(new Template(SpecialInput.POWER), base, exponent);
    }

    void function(SpecialInput kind) {
        place(new Template(kind), null, "");
    }

    void symbol(String notation, String token) {
        place(new Template(SpecialInput.SYMBOL, 0, 0, notation, token), null, "");
    }

    void matrix(int rows, int columns, List<String> cells, SpecialInput operation) {
        Template matrix = new Template(SpecialInput.MATRIX, rows * columns, columns, "", "");
        for (int i = 0; i < cells.size(); i++) matrix.row(i).append(cells.get(i));
        if (operation == null) {
            place(matrix, null, "");
        } else {
            Template function = new Template(operation);
            function.row(0).append(matrix);
            place(function, null, "");
            focus(matrix.row(0).last(), matrix.row(0).last().value.length());
        }
    }

    private void place(Template template, String fixedBase, String exponent) {
        Row row = active.row;
        StringBuilder symbols = new StringBuilder();
        int activeStart = 0;
        for (Part part : row.parts) {
            if (part == active) activeStart = symbols.length();
            if (part instanceof Text) symbols.append(((Text) part).value);
            else symbols.append(TEMPLATE);
        }

        int start = activeStart + selectionStart();
        int end = activeStart + selectionEnd();
        int from = start;
        int to = end;
        boolean initializeOperand = template.kind.wrapsOperand() && template.row(0).firstEmptyField() != null;
        boolean captureOperand = initializeOperand || template.kind == SpecialInput.MODULO;
        boolean multiply = false;
        if (captureOperand && start == end) {
            int previousEnd = start;
            while (previousEnd > 0 && Character.isWhitespace(symbols.charAt(previousEnd - 1))) previousEnd--;
            int previousStart = operandStart(symbols.substring(0, previousEnd));
            if (previousStart < previousEnd) {
                if (fixedBase == null) {
                    from = template.kind == SpecialInput.MODULO
                            ? includeUnarySign(symbols, previousStart) : previousStart;
                    to = previousEnd;
                } else {
                    from = previousEnd;
                    multiply = true;
                }
            }
        }

        if (initializeOperand) {
            if (fixedBase == null) template.row(0).appendRange(row, from, to);
            else template.row(0).append(fixedBase);
            template.row(1).append(exponent);
        } else if (!template.kind.wrapsOperand() && template.kind != SpecialInput.SYMBOL
                && template.kind != SpecialInput.MATRIX) {
            int field = template.kind == SpecialInput.INDEXED_ROOT || template.kind == SpecialInput.LOG ? 1 : 0;
            template.row(field).appendRange(row, from, to);
        }

        Row replacement = new Row(null);
        replacement.appendRange(row, 0, from);
        if (multiply) replacement.append(" × ");
        replacement.append(template);
        replacement.appendRange(row, end, symbols.length());
        row.reset();
        row.appendRange(replacement, 0, symbolLength(replacement));

        if (template.rows.isEmpty()) {
            active = adjacentField(template, true);
            render(0, true);
        } else {
            int field = captureOperand && template.row(0).firstEmptyField() == null ? 1 : 0;
            active = template.row(field).last();
            render(active.value.length(), true);
        }
        onChange.accept(this);
    }

    private static int operandStart(String before) {
        int end = before.length();
        if (end == 0) return end;

        // Factorial and percent belong to the operand that they follow.
        int atomEnd = end;
        while (atomEnd > 1 && (before.charAt(atomEnd - 1) == '!' || before.charAt(atomEnd - 1) == '%')) {
            atomEnd--;
            while (atomEnd > 0 && Character.isWhitespace(before.charAt(atomEnd - 1))) atomEnd--;
        }
        if (atomEnd == 0) return end;

        char last = before.charAt(atomEnd - 1);
        if (last == TEMPLATE) return atomEnd - 1;
        if (last == ')' || last == ']') {
            char open = last == ')' ? '(' : '[';
            int depth = 1;
            for (int i = atomEnd - 2; i >= 0; i--) {
                char ch = before.charAt(i);
                if (ch == last) depth++;
                if (ch == open && --depth == 0) {
                    Matcher name = IDENTIFIER.matcher(before.substring(0, i));
                    return open == '(' && name.find() ? name.start() : i;
                }
            }
            return end;
        }
        Matcher operand = OPERAND.matcher(before.substring(0, atomEnd));
        return operand.find() ? operand.start() : end;
    }

    private static int includeUnarySign(CharSequence expression, int start) {
        int sign = start - 1;
        while (sign >= 0 && Character.isWhitespace(expression.charAt(sign))) sign--;
        if (sign < 0 || "+−-".indexOf(expression.charAt(sign)) < 0) return start;
        int previous = sign - 1;
        while (previous >= 0 && Character.isWhitespace(expression.charAt(previous))) previous--;
        return previous < 0 || "+−-*/×÷^([,=".indexOf(expression.charAt(previous)) >= 0 ? sign : start;
    }

    void backspace() {
        int start = selectionStart();
        int end = selectionEnd();
        if (start != end) {
            replace(start, end, "");
        } else if (start > 0) {
            String before = active.value.substring(0, start);
            for (String token : deleteTokens) {
                if (before.endsWith(token)) {
                    replace(start - token.length(), start, "");
                    return;
                }
            }
            replace(before.offsetByCodePoints(start, -1), start, "");
        } else {
            Template owner = active.row.owner;
            int index = active.row.parts.indexOf(active);
            if (index > 0) {
                Template previous = (Template) active.row.parts.get(index - 1);
                if (previous.kind == SpecialInput.SYMBOL) {
                    remove(previous, null);
                    return;
                }
            }
            if (owner != null && owner.kind.wrapsOperand() && active.row == owner.row(1)
                    && active.row.parts.size() == 1 && active.value.isEmpty()) {
                remove(owner, owner.row(0));
            } else if (owner != null && !owner.kind.wrapsOperand() && owner.row(0) == active.row
                    && owner.rows.stream().allMatch(row -> row.expression().trim().isEmpty())) {
                remove(owner, null);
            } else {
                move(false);
            }
        }
    }

    private void remove(Template template, Row kept) {
        Row row = template.parent;
        int index = row.parts.indexOf(template);
        Row remainder = new Row(null);
        int suffix = 0;
        for (int i = 0; i <= index; i++) suffix += row.parts.get(i).width();
        remainder.appendRange(row, suffix, symbolLength(row));

        row.parts.subList(index, row.parts.size()).clear();
        if (kept != null) row.appendRange(kept, 0, symbolLength(kept));
        active = row.last();
        int caret = active.value.length();
        row.appendRange(remainder, 0, symbolLength(remainder));
        render(caret, true);
        onChange.accept(this);
    }

    private static int symbolLength(Row row) {
        int length = 0;
        for (Part part : row.parts) length += part.width();
        return length;
    }

    private Text adjacentField(Template template, boolean right) {
        int index = template.parent.parts.indexOf(template);
        return (Text) template.parent.parts.get(index + (right ? 1 : -1));
    }

    private void leaveTemplate() {
        Template owner = active.row.owner;
        if (owner != null) focus(adjacentField(owner, true), 0);
    }

    boolean closeTemplate() {
        if (active.row.owner == null || hasOpenTextGroup()) return false;
        leaveTemplate();
        return true;
    }

    boolean advanceField() {
        Template owner = active.row.owner;
        if (owner == null || hasOpenTextGroup()) return false;
        int index = owner.rows.indexOf(active.row) + 1;
        if (index >= owner.rows.size()) return false;
        focus((Text) owner.row(index).parts.get(0), 0);
        return true;
    }

    private boolean hasOpenTextGroup() {
        int depth = 0;
        for (Part part : active.row.parts) {
            if (!(part instanceof Text)) continue;
            Text text = (Text) part;
            int length = text == active ? selectionStart() : text.value.length();
            for (int i = 0; i < length; i++) {
                char character = text.value.charAt(i);
                if (character == '(' || character == '[') depth++;
                else if (character == ')' || character == ']') depth--;
            }
            if (text == active) break;
        }
        return depth > 0;
    }

    private boolean move(boolean right) {
        Row row = active.row;
        int index = row.parts.indexOf(active);
        int step = right ? 1 : -1;
        int neighbor = index + step;
        if (neighbor >= 0 && neighbor < row.parts.size()) {
            Template template = (Template) row.parts.get(neighbor);
            if (template.rows.isEmpty()) {
                Text field = adjacentField(template, right);
                focus(field, right ? 0 : field.value.length());
            } else {
                Row field = template.row(right ? 0 : template.rows.size() - 1);
                focusBoundary(field, right);
            }
            return true;
        }
        Template owner = row.owner;
        if (owner == null) return false;
        int field = owner.rows.indexOf(row) + step;
        if (field >= 0 && field < owner.rows.size()) {
            focusBoundary(owner.row(field), right);
        } else {
            Text outside = adjacentField(owner, right);
            focus(outside, right ? 0 : outside.value.length());
        }
        return true;
    }

    private void focusBoundary(Row row, boolean start) {
        Text field = start ? (Text) row.parts.get(0) : row.last();
        focus(field, start ? 0 : field.value.length());
    }

    private int selectionStart() {
        return selection(false);
    }

    private int selectionEnd() {
        return selection(true);
    }

    private int selection(boolean end) {
        if (active.field == null) return active.value.length();
        int start = active.field.getSelectionStart();
        int finish = active.field.getSelectionEnd();
        if (start < 0 || finish < 0) return active.value.length();
        return end ? Math.max(start, finish) : Math.min(start, finish);
    }

    private void focus(Text text, int caret) {
        active = text;
        restoreFocus = true;
        updateExit();
        if (text.field != null) {
            text.field.requestFocus();
            text.field.setSelection(caret);
        }
    }

    private void updateExit() {
        Template owner = active.row.owner;
        exit.setVisibility(owner == null ? GONE : VISIBLE);
        next.setVisibility(owner != null
                && owner.rows.indexOf(active.row) < owner.rows.size() - 1 ? VISIBLE : GONE);
    }

    private void render(int caret, boolean requestFocus) {
        rendering = true;
        scroll.removeAllViews();
        LinearLayout line = buildRow(root, baseSize, true);
        line.setOnClickListener(view -> {
            focus(root.last(), root.last().value.length());
            if (systemKeyboard) {
                InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(active.field, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        scroll.addView(line, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rendering = false;
        updateExit();
        active.field.setSelection(caret);
        if (requestFocus) focus(active, caret);
    }

    private LinearLayout buildRow(Row row, float size, boolean editable) {
        LinearLayout line = new LinearLayout(getContext());
        line.setBaselineAligned(false);
        line.setGravity(Gravity.CENTER_VERTICAL);
        for (Part part : row.parts) {
            View child = part instanceof Text
                    ? buildText((Text) part, size, editable)
                    : buildTemplate((Template) part, size, editable);
            line.addView(child, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        return line;
    }

    private View buildText(Text text, float size, boolean editable) {
        if (!editable) {
            TextView label = new TextView(getContext());
            styleText(label, text.value, size);
            return label;
        }
        EditText field = new EditText(getContext());
        text.field = field;
        if (systemKeyboard) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            field.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                finishSystemEditing();
                return true;
            });
        }
        styleText(field, text.value, size);
        field.setSingleLine(true);
        field.setShowSoftInputOnFocus(systemKeyboard);
        field.setBackground(null);
        // Keep room for the caret between templates without spacing out the notation.
        int minimumWidth = dp(text.row.parts.size() > 1 ? 2 : text.row.owner == null ? 14 : 10);
        field.setMinWidth(minimumWidth);
        field.setMinimumWidth(minimumWidth);
        if (text.row.owner != null) field.setGravity(Gravity.CENTER);
        field.setContentDescription(description(text.row));
        field.setHintTextColor(accent);
        if (text.row.owner != null && text.row.parts.size() == 1) field.setHint("□");
        if (field.getTextCursorDrawable() != null) {
            android.graphics.drawable.Drawable cursor = field.getTextCursorDrawable().mutate();
            cursor.setTint(accent);
            field.setTextCursorDrawable(cursor);
        }
        field.setOnFocusChangeListener((view, focused) -> {
            if (focused && !rendering) {
                active = text;
                restoreFocus = true;
                updateExit();
            } else if (!focused && systemKeyboard && !rendering) {
                post(() -> {
                    if (isAttachedToWindow() && !hasFocus()) commitPlotNotation();
                });
            }
        });
        field.setOnKeyListener((view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_ENTER) {
                if (systemKeyboard) {
                    finishSystemEditing();
                    return true;
                }
                if (onEnter != null) {
                    onEnter.run();
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_TAB) {
                if (!advanceField()) leaveTemplate();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DEL) {
                backspace();
                return true;
            }
            if (selectionStart() != selectionEnd()) return false;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && selectionEnd() == active.value.length()) return move(true);
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && selectionStart() == 0) return move(false);
            return false;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                text.value = value.toString();
                onChange.accept(FormulaEditor.this);
            }
        });
        return field;
    }

    private String description(Row row) {
        Template owner = row.owner;
        if (owner == null) return rootFieldDescription;
        int index = owner.rows.indexOf(row);
        if (owner.kind == SpecialInput.MATRIX) {
            return "Zeile " + (index / owner.columns + 1) + ", Spalte " + (index % owner.columns + 1);
        }
        return owner.kind.fields[index];
    }

    private void styleText(TextView view, String value, float size) {
        view.setText(value);
        view.setTextColor(foreground);
        view.setTypeface(Typeface.DEFAULT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP,
                size * Math.min(getResources().getConfiguration().fontScale, 2f));
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, 0);
    }

    private View buildTemplate(Template template, float size, boolean editable) {
        float small = size * 0.76f;
        float script = size * 0.60f;
        switch (template.kind) {
            case FRACTION:
                return fractionView(buildRow(template.row(0), small, editable),
                        buildRow(template.row(1), small, editable));
            case POWER:
                return attachScript(buildRow(template.row(0), size, editable),
                        buildRow(template.row(1), size * 0.70f, editable), size, true);
            case MATRIX:
                return matrixView(template, small, editable);
            case SYMBOL: {
                TextView symbol = label(template.symbol, size);
                symbol.setText(MainActivity.formatSubscriptSymbol(template.symbol));
                return symbol;
            }
            case ROOT:
                return radical(buildRow(template.row(0), size, editable), size);
            case INDEXED_ROOT:
                return indexedRadical(buildRow(template.row(0), script, editable),
                        buildRow(template.row(1), size, editable), size);
            case CUBE_ROOT:
                return indexedRadical(label("3", script), buildRow(template.row(0), size, editable), size);
            case LOG:
                return horizontal(attachScript(label("log", size),
                                buildRow(template.row(0), script, editable), size, false),
                        frame(Frame.PARENS, buildRow(template.row(1), size, editable)));
            case SUM:
            case PRODUCT: {
                View lower = horizontal(buildRow(template.row(1), script, editable), label("=", script),
                        buildRow(template.row(2), script, editable));
                View operator = stack(buildRow(template.row(3), script, editable),
                        label(template.kind.notation, size), lower);
                return horizontal(operator, frame(Frame.PARENS, buildRow(template.row(0), size, editable)));
            }
            case INTEGRAL:
            case ANTIDERIVATIVE: {
                View operator = template.kind == SpecialInput.ANTIDERIVATIVE
                        ? label("∫", size * 1.25f)
                        : stack(buildRow(template.row(3), script, editable), label("∫", size * 1.25f),
                                buildRow(template.row(2), script, editable));
                return horizontal(operator, frame(Frame.PARENS, buildRow(template.row(0), size, editable)),
                        label(" d", size), buildRow(template.row(1), size, editable));
            }
            case DERIVATIVE:
            case DERIVATIVE_AT: {
                View operator = fractionView(label("d", small),
                        horizontal(label("d", small), buildRow(template.row(1), small, editable)));
                LinearLayout derivative = horizontal(operator,
                        frame(Frame.PARENS, buildRow(template.row(0), size, editable)));
                if (template.kind == SpecialInput.DERIVATIVE_AT) {
                    derivative.addView(label(" │ ", size));
                    derivative.addView(buildRow(template.row(2), small, editable));
                }
                return derivative;
            }
            case BINOMIAL:
                return frame(Frame.PARENS, stack(buildRow(template.row(0), small, editable),
                        buildRow(template.row(1), small, editable)));
            case MODULO:
                return frame(Frame.PARENS, horizontal(buildRow(template.row(0), size, editable),
                        label(" mod ", small), buildRow(template.row(1), size, editable)));
            case ABS:
                return frame(Frame.ABS, buildRow(template.row(0), size, editable));
            case FLOOR:
                return frame(Frame.FLOOR, buildRow(template.row(0), size, editable));
            case CEIL:
                return frame(Frame.CEIL, buildRow(template.row(0), size, editable));
            case EXP:
                return attachScript(label("e", size), buildRow(template.row(0), size * 0.70f, editable), size, true);
            case INVERSE:
            case TRANSPOSE:
                return attachScript(frame(Frame.PARENS, buildRow(template.row(0), size, editable)),
                        label(template.kind == SpecialInput.INVERSE ? "−1" : "T", script), size, true);
            default: {
                LinearLayout arguments = horizontal();
                for (int i = 0; i < template.rows.size(); i++) {
                    if (i > 0) arguments.addView(label(", ", size));
                    arguments.addView(buildRow(template.row(i), size, editable));
                }
                return horizontal(label(template.kind.notation, size), frame(Frame.PARENS, arguments));
            }
        }
    }

    private LinearLayout horizontal(View... views) {
        LinearLayout line = new LinearLayout(getContext());
        line.setBaselineAligned(false);
        line.setGravity(Gravity.CENTER_VERTICAL);
        for (View view : views) line.addView(view);
        return line;
    }

    private LinearLayout stack(View... views) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        for (View view : views) {
            column.addView(view, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        return column;
    }

    private View fractionView(View numerator, View denominator) {
        return new FractionLayout(numerator, denominator);
    }

    private int fractionCenter(View row) {
        int rowCenter = row.getMeasuredWidth() / 2;
        // Keep the base centered for short scripts. Center wider powers as a whole.
        if (row instanceof LinearLayout) {
            LinearLayout line = (LinearLayout) row;
            int offset = 0;
            int baseCenter = -1;
            for (int i = 0; i < line.getChildCount(); i++) {
                View child = line.getChildAt(i);
                if (child instanceof ScriptLayout && ((ScriptLayout) child).raised && baseCenter < 0) {
                    int baseWidth = ((ScriptLayout) child).base.getMeasuredWidth();
                    if (child.getMeasuredWidth() - baseWidth > baseWidth) return rowCenter;
                    baseCenter = offset + baseWidth / 2;
                } else if (!(child instanceof TextView) || ((TextView) child).length() > 0) {
                    return rowCenter;
                }
                offset += child.getMeasuredWidth();
            }
            if (baseCenter >= 0) return baseCenter;
        }
        return rowCenter;
    }

    private final class FractionLayout extends ViewGroup {
        private final View numerator;
        private final View denominator;
        private final Paint rule = new Paint();
        private int numeratorCenter;
        private int denominatorCenter;

        FractionLayout(View numerator, View denominator) {
            super(FormulaEditor.this.getContext());
            this.numerator = numerator;
            this.denominator = denominator;
            setPadding(dp(3), dp(2), dp(3), dp(2));
            setMinimumWidth(dp(18));
            setWillNotDraw(false);
            rule.setColor(foreground);
            addView(numerator, new ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            addView(denominator, new ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        @Override protected void onMeasure(int width, int height) {
            measureChild(numerator, width, height);
            measureChild(denominator, width, height);
            numeratorCenter = fractionCenter(numerator);
            denominatorCenter = fractionCenter(denominator);
            int leftExtent = Math.max(numeratorCenter, denominatorCenter);
            int rightExtent = Math.max(numerator.getMeasuredWidth() - numeratorCenter,
                    denominator.getMeasuredWidth() - denominatorCenter);
            int desiredWidth = getPaddingLeft() + 2 * Math.max(leftExtent, rightExtent) + getPaddingRight();
            int desiredHeight = getPaddingTop() + numerator.getMeasuredHeight() + dp(2) * 2 + dp(1)
                    + denominator.getMeasuredHeight() + getPaddingBottom();
            setMeasuredDimension(resolveSize(Math.max(desiredWidth, getSuggestedMinimumWidth()), width),
                    resolveSize(desiredHeight, height));
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight();
            int axis = getPaddingLeft() + available / 2;
            int denominatorTop = getPaddingTop() + numerator.getMeasuredHeight() + dp(2) * 2 + dp(1);
            layoutField(numerator, axis - numeratorCenter, getPaddingTop());
            layoutField(denominator, axis - denominatorCenter, denominatorTop);
        }

        private void layoutField(View field, int left, int top) {
            field.layout(left, top, left + field.getMeasuredWidth(), top + field.getMeasuredHeight());
        }

        @Override protected void onDraw(Canvas canvas) {
            int y = getPaddingTop() + numerator.getMeasuredHeight() + dp(2);
            canvas.drawRect(getPaddingLeft(), y, getWidth() - getPaddingRight(), y + dp(1), rule);
        }
    }

    private View attachScript(View base, View script, float size, boolean raised) {
        int offset = dp(Math.round(size * (raised ? 0.43f : 0.22f)
                * Math.min(getResources().getConfiguration().fontScale, 2f)));
        return new ScriptLayout(base, script, offset, raised);
    }

    private final class ScriptLayout extends LinearLayout {
        private final View base;
        private final View script;
        private final LayoutParams scriptParams;
        private final int offset;
        private final boolean raised;

        ScriptLayout(View base, View script, int offset, boolean raised) {
            super(FormulaEditor.this.getContext());
            this.base = base;
            this.script = script;
            this.offset = offset;
            this.raised = raised;
            setBaselineAligned(false);
            setGravity(raised ? Gravity.BOTTOM : Gravity.TOP);
            addView(base);
            scriptParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            scriptParams.leftMargin = dp(!raised || base instanceof MathFrame ? 1 : -3);
            addView(script, scriptParams);
        }

        @Override protected void onMeasure(int width, int height) {
            scriptParams.bottomMargin = raised ? offset : 0;
            scriptParams.topMargin = 0;
            super.onMeasure(width, height);
            int heightDifference = base.getMeasuredHeight() - script.getMeasuredHeight();
            if (raised) {
                // Keep the exponent at the top edge of a tall fraction or matrix.
                int rise = Math.max(offset, heightDifference + dp(1));
                if (rise == offset) return;
                scriptParams.bottomMargin = rise;
            } else {
                // Measure from the bottom of the word rather than its vertical midpoint.
                scriptParams.topMargin = Math.max(base.getMeasuredHeight() / 2, heightDifference + offset);
            }
            super.onMeasure(width, height);
        }
    }

    private View matrixView(Template matrix, float size, boolean editable) {
        TableLayout table = new TableLayout(getContext());
        for (int i = 0; i < matrix.rows.size(); i += matrix.columns) {
            TableRow row = new TableRow(getContext());
            for (int column = 0; column < matrix.columns; column++) {
                LinearLayout cell = buildRow(matrix.row(i + column), size, editable);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(5), dp(2), dp(5), dp(2));
                row.addView(cell);
            }
            table.addView(row);
        }
        return frame(Frame.BRACKETS, table);
    }

    private static Template parseMatrixResult(String expression) {
        if (expression == null || !expression.startsWith("[[") || !expression.endsWith("]]")) return null;
        String[] lines = expression.substring(2, expression.length() - 2).split("\\]\\s*,\\s*\\[");
        int columns = lines[0].split(",", -1).length;
        Template matrix = new Template(SpecialInput.MATRIX, lines.length * columns, columns, "", "");
        for (int row = 0; row < lines.length; row++) {
            String[] cells = lines[row].split(",", -1);
            if (cells.length != columns) return null;
            for (int column = 0; column < columns; column++) {
                String cell = cells[column].trim();
                if (!cell.matches("[+−-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) return null;
                matrix.row(row * columns + column).append(cell);
            }
        }
        return matrix;
    }

    View matrixResult(String expression, float size, Runnable onClick) {
        Template matrix = parseMatrixResult(expression);
        if (matrix == null) return null;
        for (Row cell : matrix.rows) {
            cell.last().value = MainActivity.formatDisplayNumber(cell.last().value);
        }
        HorizontalScrollView container = new HorizontalScrollView(getContext());
        container.setHorizontalScrollBarEnabled(false);
        View content = matrixView(matrix, size, false);
        if (onClick != null) content.setOnClickListener(view -> onClick.run());
        container.addView(content);
        return new FormulaViewport(container);
    }

    boolean insertMatrixResult(String expression) {
        Template matrix = parseMatrixResult(expression);
        if (matrix == null) return false;
        place(matrix, null, "");
        focus(adjacentField(matrix, true), 0);
        return true;
    }

    private TextView label(String text, float size) {
        TextView view = new TextView(getContext());
        styleText(view, text, size);
        return view;
    }

    private enum Frame { PARENS, BRACKETS, ABS, FLOOR, CEIL, RADICAL }

    private View frame(Frame style, View content) {
        return new MathFrame(style, content, 0);
    }

    private View radical(View content, float size) {
        float fontScale = Math.min(getResources().getConfiguration().fontScale, 2f);
        int stem = dp(Math.max(15, Math.round(size * fontScale * 0.64f)));
        return new MathFrame(Frame.RADICAL, content, stem);
    }

    private View indexedRadical(View index, View content, float size) {
        LinearLayout root = horizontal();
        LayoutParams indexParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        indexParams.gravity = Gravity.TOP;
        indexParams.rightMargin = -dp(4);
        root.addView(index, indexParams);
        root.addView(radical(content, size));
        return root;
    }

    private final class MathFrame extends LinearLayout {
        private final Frame style;
        private final int stem;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MathFrame(Frame style, View content, int stem) {
            super(FormulaEditor.this.getContext());
            this.style = style;
            this.stem = stem;
            setWillNotDraw(false);
            if (style == Frame.RADICAL) {
                setPadding(stem + dp(1), dp(5), dp(4), dp(3));
            } else if (style == Frame.PARENS) {
                setPadding(dp(4), dp(2), dp(4), dp(2));
            } else {
                setPadding(dp(7), dp(2), dp(7), dp(2));
            }
            paint.setColor(foreground);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(1, dp(1)));
            addView(content);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(2);
            float right = getWidth() - dp(2);
            float top = dp(2);
            float bottom = getHeight() - dp(2);
            float lip = dp(4);
            path.reset();
            if (style == Frame.RADICAL) {
                MathKeyDrawable.traceRadical(path, left, top, stem, right, bottom);
            } else if (style == Frame.PARENS) {
                left = dp(1);
                right = getWidth() - left;
                lip = dp(3);
                path.moveTo(left + lip, top);
                path.quadTo(left - lip, (top + bottom) / 2, left + lip, bottom);
                path.moveTo(right - lip, top);
                path.quadTo(right + lip, (top + bottom) / 2, right - lip, bottom);
            } else {
                boolean topLip = style == Frame.BRACKETS || style == Frame.CEIL;
                boolean bottomLip = style == Frame.BRACKETS || style == Frame.FLOOR;
                path.moveTo(topLip ? left + lip : left, top);
                path.lineTo(left, top);
                path.lineTo(left, bottom);
                if (bottomLip) path.lineTo(left + lip, bottom);
                path.moveTo(topLip ? right - lip : right, top);
                path.lineTo(right, top);
                path.lineTo(right, bottom);
                if (bottomLip) path.lineTo(right - lip, bottom);
            }
            canvas.drawPath(path, paint);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
