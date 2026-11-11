package com.jadecalculator.materialyou;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.SubscriptSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Android 12 (API 31+) Material You (Monet Dynamic Color) Scientific Calculator.
 * 100% Offline: Uses Android 12 system_accent1_* and system_neutral1_* wallpaper colors.
 */
public class MainActivity extends Activity {

    private final CalculatorEngine engine = new CalculatorEngine();
    private boolean moduloPositive = true;
    private boolean showExactFirst = true;

    private String currentInput = "";
    private int activeTab = 0;     // 0=Calc, 1=Plotter, 2=Constants, 3=Converter/Settings
    private int activeSection = 0; // 0=()√^, 1=sin, 2=∑∫i, 3=abc, 4=mehr
    private boolean dualKeyboard = true;
    private boolean showMoreVariables = false;

    // Android 12 Monet Dynamic Colors
    private int colBgSurface;
    private int colSurfaceContainer;
    private int colSurfaceHigh;
    private int colPrimary;
    private int colOnPrimary;
    private int colPrimaryContainer;
    private int colOnPrimaryContainer;
    private int colSecondaryContainer;
    private int colOnSecondaryContainer;
    private int colOnSurface;
    private int colOnSurfaceVariant;

    private static class HistoryEntry {
        String expr;
        String exactText;
        String decimalText;
        String reportText;
        boolean showingExact;

        HistoryEntry(String expr, String exactText, String decimalText, boolean showingExact) {
            this(expr, exactText, decimalText, null, showingExact);
        }

        HistoryEntry(String expr, String exactText, String decimalText, String reportText, boolean showingExact) {
            this.expr = expr;
            this.exactText = exactText;
            this.decimalText = decimalText;
            this.reportText = reportText;
            this.showingExact = showingExact;
        }
    }

    private final List<HistoryEntry> history = new ArrayList<>();
    private LinearLayout rootContainer;
    private TextView inputDisplayView;
    private TextView previewDisplayView;
    private View cursorView;
    private ScrollView historyScrollView;
    private LinearLayout historyListContainer;
    private FrameLayout keyboardBodyContainer;
    private LinearLayout sectionHeaderRow;
    private String plotExpr1 = "sin(x) * cos(x/2)";
    private String plotExpr2 = "x^2 / 4 - 2";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        resolveAndroid12DynamicColors();

        // Load saved state (Preferences & History & Calculations & UI Drafts)
        android.content.SharedPreferences prefs = getSharedPreferences("jade_calculator", MODE_PRIVATE);
        showExactFirst = prefs.getBoolean("show_exact_first", true);
        showMoreVariables = prefs.getBoolean("show_more_variables", false);
        engine.setRadiansMode(prefs.getBoolean("radians_mode", true));
        engine.setModuloAlwaysPositive(prefs.getBoolean("modulo_always_positive", true));

        String ansValStr = prefs.getString("ans_value", null);
        if (ansValStr != null) {
            engine.setLastAns(engine.deserializeValue(ansValStr));
        }

        for (String varName : CalculatorEngine.USER_VARIABLES) {
            String vStr = prefs.getString("var_" + varName, null);
            if (vStr != null) {
                engine.setUserVariable(varName, engine.deserializeValue(vStr));
            }
        }

        if (prefs.contains("current_input")) {
            currentInput = prefs.getString("current_input", "");
        }

        plotExpr1 = prefs.getString("plot_expr_1", "sin(x) * cos(x/2)");
        plotExpr2 = prefs.getString("plot_expr_2", "x^2 / 4 - 2");

        int histCount = prefs.getInt("history_count", -1);
        if (histCount >= 0) {
            for (int i = 0; i < histCount; i++) {
                String expr = prefs.getString("hist_expr_" + i, "");
                String ex = prefs.getString("hist_ex_" + i, "");
                String dec = prefs.getString("hist_dec_" + i, "");
                String rep = prefs.getString("hist_rep_" + i, "");
                boolean showEx = prefs.getBoolean("hist_show_ex_" + i, showExactFirst);
                if (!expr.isEmpty()) {
                    history.add(new HistoryEntry(expr, ex, dec, rep.isEmpty() ? null : rep, showEx));
                }
            }
        } else {
            // First run sample history matching reference
            addSampleHistory("6/4");
            addSampleHistory("500/260");
            addSampleHistory("150/80");
            saveState();
        }

        getWindow().setStatusBarColor(colSurfaceContainer);
        getWindow().setNavigationBarColor(colSurfaceContainer);

        rootContainer = new LinearLayout(this);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setBackgroundColor(colBgSurface);
        setContentView(rootContainer);

        rebuildScreen();
    }

    private void saveState() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences("jade_calculator", MODE_PRIVATE).edit();
        ed.putBoolean("show_exact_first", showExactFirst);
        ed.putBoolean("show_more_variables", showMoreVariables);
        ed.putBoolean("radians_mode", engine.isRadiansMode());
        ed.putBoolean("modulo_always_positive", engine.isModuloAlwaysPositive());
        ed.putString("ans_value", engine.serializeValue(engine.getLastAns()));
        ed.putString("current_input", currentInput != null ? currentInput : "");
        ed.putString("plot_expr_1", plotExpr1 != null ? plotExpr1 : "");
        ed.putString("plot_expr_2", plotExpr2 != null ? plotExpr2 : "");

        Map<String, CalculatorEngine.Value> userVars = engine.getUserVariables();
        for (Map.Entry<String, CalculatorEngine.Value> entry : userVars.entrySet()) {
            ed.putString("var_" + entry.getKey(), engine.serializeValue(entry.getValue()));
        }

        ed.putInt("history_count", history.size());
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry h = history.get(i);
            ed.putString("hist_expr_" + i, h.expr);
            ed.putString("hist_ex_" + i, h.exactText);
            ed.putString("hist_dec_" + i, h.decimalText);
            ed.putString("hist_rep_" + i, h.reportText != null ? h.reportText : "");
            ed.putBoolean("hist_show_ex_" + i, h.showingExact);
        }
        ed.apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuildScreen();
    }

    private void resolveAndroid12DynamicColors() {
        // Android 12 (API 31) Monet Dynamic Color resources
        colBgSurface = getColor(android.R.color.system_neutral1_900);
        colSurfaceContainer = getColor(android.R.color.system_neutral1_800);
        colSurfaceHigh = getColor(android.R.color.system_neutral2_700);
        colPrimary = getColor(android.R.color.system_accent1_200);
        colOnPrimary = getColor(android.R.color.system_accent1_900);
        colPrimaryContainer = getColor(android.R.color.system_accent1_700);
        colOnPrimaryContainer = getColor(android.R.color.system_accent1_100);
        colSecondaryContainer = getColor(android.R.color.system_accent2_700);
        colOnSecondaryContainer = getColor(android.R.color.system_accent2_100);
        colOnSurface = getColor(android.R.color.system_neutral1_50);
        colOnSurfaceVariant = getColor(android.R.color.system_neutral2_200);
    }

    private String getIntegrationReportText(String expr) {
        if (expr != null && (expr.contains("int(") || expr.contains("defint("))) {
            CalculatorEngine.IntegrationReport r = engine.getLastIntegrationReport();
            if (r != null) {
                String badge = r.converged ? "✓ konvergiert" : "⚠ langsame Konv.";
                return String.format(java.util.Locale.US, "%s  •  Δ ≈ %.1e  •  n = %d", badge, r.absErr, r.subintervals);
            }
        }
        return null;
    }

    private void addSampleHistory(String expr) {
        try {
            CalculatorEngine.Result result = engine.evaluateAndCommit(expr);
            String dec = result.decimalText;
            String ex = result.exactText;
            String rep = getIntegrationReportText(expr);
            history.add(new HistoryEntry(expr, ex, dec, rep, false));
        } catch (Exception ignored) {
        }
    }

    private void rebuildScreen() {
        rootContainer.removeAllViews();
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        // 1. Top App Bar (only shown on non-calculator tabs: 2D-Plot, Konstanten, Setup)
        if (activeTab != 0) {
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setBackgroundColor(colSurfaceContainer);
            int padH = dp(14);
            int padV = dp(isLandscape ? 6 : 10);
            topBar.setPadding(padH, padV, padH, padV);

            TextView titleView = new TextView(this);
            String titleText = (activeTab == 1) ? "2D-Plotter" : (activeTab == 2 ? "Physikalische Konstanten" : "Einstellungen");
            titleView.setText(titleText);
            titleView.setTextColor(colOnSurface);
            setScaledTextSize(titleView, isLandscape ? 15f : 17f, 2.0f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            topBar.addView(titleView, titleLp);

            final TextView angleChip = createChip(engine.isRadiansMode() ? "RAD" : "DEG", colSecondaryContainer, colOnSecondaryContainer);
            angleChip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                engine.setRadiansMode(!engine.isRadiansMode());
                saveState();
                rebuildScreen();
            });
            topBar.addView(angleChip);

            rootContainer.addView(topBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 2. Main Content Area (weight = 1)
        FrameLayout contentArea = new FrameLayout(this);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rootContainer.addView(contentArea, contentLp);

        if (activeTab == 0) {
            buildCalculatorTab(contentArea);
        } else if (activeTab == 1) {
            buildPlotterTab(contentArea);
        } else if (activeTab == 2) {
            buildConstantsTab(contentArea);
        } else {
            buildConverterTab(contentArea);
        }

        // 3. Bottom Material You Navigation Bar (Pill Indicators)
        LinearLayout bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setBackgroundColor(colSurfaceContainer);
        bottomNav.setPadding(dp(6), dp(isLandscape ? 4 : 6), dp(6), dp(isLandscape ? 4 : 6));

        String[] navTitles = {"∑ Rechner", "📈 2D-Plot", "🔬 Konst.", "⚙ Setup"};
        for (int i = 0; i < navTitles.length; i++) {
            final int tabIdx = i;
            boolean sel = (activeTab == i);
            Button navBtn = makeMaterialButton(
                    navTitles[i],
                    sel ? colPrimaryContainer : Color.TRANSPARENT,
                    sel ? colOnPrimaryContainer : colOnSurfaceVariant,
                    18,
                    isLandscape ? 12f : 12.5f,
                    2.0f
            );
            navBtn.setSingleLine(false);
            navBtn.setMaxLines(2);
            navBtn.setGravity(Gravity.CENTER);
            navBtn.setPadding(dp(2), dp(2), dp(2), dp(2));
            navBtn.setMinimumHeight(dp(isLandscape ? 36 : 46));
            navBtn.setAutoSizeTextTypeUniformWithConfiguration(
                    8, Math.round((isLandscape ? 12f : 12.5f) * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
            navBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                activeTab = tabIdx;
                rebuildScreen();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            bottomNav.addView(navBtn, lp);
        }
        rootContainer.addView(bottomNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout buildNumpadCard() {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(roundedTopRect(colSurfaceContainer, 24));
        card.setPadding(dp(8), dp(isLandscape ? 4 : 8), dp(8), dp(isLandscape ? 4 : 6));

        // Digits part (3 columns x 4 rows)
        LinearLayout digitsCol = new LinearLayout(this);
        digitsCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams digLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f);
        card.addView(digitsCol, digLp);

        String[][] digRows = {
                {"7", "8", "9"},
                {"4", "5", "6"},
                {"1", "2", "3"},
                {"0", ",", "ENTER"}
        };

        for (int r = 0; r < digRows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < digRows[r].length; c++) {
                final String text = digRows[r][c];
                final String tok = text.equals("ENTER") ? "=" : (text.equals(",") ? "." : text);
                boolean isEnter = text.equals("ENTER");
                int fg = isEnter ? colPrimary : colOnSurface;
                float spSize = isEnter ? 14f : 23f;

                Button b = makeMaterialButton(text, Color.TRANSPARENT, fg, 16, spSize, 2.0f);
                b.setSingleLine(true);
                b.setIncludeFontPadding(false);
                b.setPadding(0, 0, 0, 0);
                b.setAutoSizeTextTypeUniformWithConfiguration(
                        8, Math.round(spSize * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    handleKeyPress(tok);
                });

                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                blp.setMargins(dp(2), dp(1), dp(2), dp(1));
                row.addView(b, blp);
            }
            digitsCol.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        // Operators column on right (5 rows: ⌫, ÷, ×, +, −)
        LinearLayout opsCol = new LinearLayout(this);
        opsCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams opsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        card.addView(opsCol, opsLp);

        String[][] opDefs = {
                {"\u232b", "DEL"},
                {"\u00f7", "/"},
                {"\u00d7", "*"},
                {"+", "+"},
                {"\u2212", "-"}
        };

        for (final String[] op : opDefs) {
            Button b = makeMaterialButton(op[0], Color.TRANSPARENT, colOnSurface, 16, 21f, 2.0f);
            b.setSingleLine(true);
            b.setPadding(dp(2), dp(1), dp(2), dp(1));
            b.setAutoSizeTextTypeUniformWithConfiguration(
                    8, Math.round(21f * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                handleKeyPress(op[1]);
            });

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            blp.setMargins(dp(2), dp(1), dp(2), dp(1));
            opsCol.addView(b, blp);
        }

        return card;
    }

    private void buildCalculatorTab(FrameLayout parent) {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        // 1. Scrollable History Area
        ScrollView historyScroll = new ScrollView(this);
        historyScrollView = historyScroll;
        historyScroll.setBackgroundColor(Color.TRANSPARENT);
        historyListContainer = new LinearLayout(this);
        historyListContainer.setOrientation(LinearLayout.VERTICAL);
        historyListContainer.setPadding(dp(12), dp(8), dp(12), dp(8));
        historyScroll.addView(historyListContainer);
        refreshHistoryViews();

        // 2. Active Expression & Input Row (Pitch black bar with blue cursor '|')
        LinearLayout inputCard = new LinearLayout(this);
        inputCard.setOrientation(LinearLayout.VERTICAL);
        inputCard.setBackgroundColor(0xFF000000);
        inputCard.setPadding(dp(14), dp(6), dp(14), dp(6));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout inputWrap = new LinearLayout(this);
        inputWrap.setOrientation(LinearLayout.HORIZONTAL);
        inputWrap.setGravity(Gravity.CENTER_VERTICAL);

        inputDisplayView = new TextView(this);
        setScaledTextSize(inputDisplayView, isLandscape ? 19f : 24f, 2.0f);
        inputDisplayView.setTextColor(colOnSurface);
        inputDisplayView.setTypeface(Typeface.DEFAULT_BOLD);
        inputDisplayView.setTextIsSelectable(true);
        inputWrap.addView(inputDisplayView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cursorView = new View(this);
        cursorView.setBackground(roundedRect(colPrimary, 2));
        LinearLayout.LayoutParams cursorLp = new LinearLayout.LayoutParams(dp(2.5f), dp(24));
        cursorLp.leftMargin = dp(2);
        inputWrap.addView(cursorView, cursorLp);

        inputRow.addView(inputWrap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button acBtn = makeMaterialButton("AC", Color.TRANSPARENT, 0x88FFFFFF, 12, 12f, 2.0f);
        acBtn.setSingleLine(true);
        acBtn.setMinimumWidth(dp(isLandscape ? 40 : 44));
        acBtn.setMinimumHeight(dp(isLandscape ? 28 : 32));
        acBtn.setPadding(dp(4), dp(1), dp(4), dp(1));
        acBtn.setAutoSizeTextTypeUniformWithConfiguration(8, Math.round(12f * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
        acBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            currentInput = "";
            saveState();
            updateInputAndPreview();
        });
        inputRow.addView(acBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputCard.addView(inputRow);

        previewDisplayView = new TextView(this);
        setScaledTextSize(previewDisplayView, isLandscape ? 12f : 13f, 2.0f);
        previewDisplayView.setTextColor(colPrimary);
        inputCard.addView(previewDisplayView);

        // 3. Dual Keyboard Container
        LinearLayout kbContainer = new LinearLayout(this);
        kbContainer.setOrientation(LinearLayout.VERTICAL);

        // 3a. Upper Keyboard (Secondary Pad): Tab Bar + 3 rows of secondary keys
        LinearLayout upperKb = new LinearLayout(this);
        upperKb.setOrientation(LinearLayout.VERTICAL);
        upperKb.setBackgroundColor(Color.TRANSPARENT);
        upperKb.setPadding(dp(8), dp(2), dp(8), dp(2));

        // Tab Row (5 tabs)
        sectionHeaderRow = new LinearLayout(this);
        sectionHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        upperKb.addView(sectionHeaderRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Secondary Keys Grid
        keyboardBodyContainer = new FrameLayout(this);
        LinearLayout.LayoutParams secGridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        upperKb.addView(keyboardBodyContainer, secGridLp);

        // 3b. Lower Keyboard: Numpad Card with 24dp rounded top corners
        LinearLayout numpadCard = buildNumpadCard();

        if (isLandscape) {
            // Landscape layout
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(4), dp(8), dp(4));

            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            histLp.bottomMargin = dp(4);
            leftCol.addView(historyScroll, histLp);
            leftCol.addView(inputCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.40f);
            leftLp.rightMargin = dp(6);
            row.addView(leftCol, leftLp);

            LinearLayout.LayoutParams upperLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f);
            kbContainer.addView(upperKb, upperLp);
            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.55f);
            kbContainer.addView(numpadCard, numLp);

            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.60f);
            row.addView(kbContainer, rightLp);

            parent.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // Portrait layout
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(0, 0, 0, 0);

            LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.40f);
            col.addView(historyScroll, histLp);

            LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            col.addView(inputCard, inLp);

            LinearLayout.LayoutParams upperLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.38f);
            kbContainer.addView(upperKb, upperLp);

            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.62f);
            kbContainer.addView(numpadCard, numLp);

            LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.60f);
            col.addView(kbContainer, kbLp);

            parent.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        updateInputAndPreview();
        refreshKeyboardSection();
    }

    private static String formatUnchainedSuperscripts(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) == '^') {
                String sup = null;
                int expLen = 0;
                if (s.startsWith("^(-1)", i)) {
                    sup = "⁻¹";
                    expLen = 5;
                } else if (s.startsWith("^(-2)", i)) {
                    sup = "⁻²";
                    expLen = 5;
                } else if (s.startsWith("^(-3)", i)) {
                    sup = "⁻³";
                    expLen = 5;
                } else if (i + 1 < n && (s.charAt(i + 1) == '2' || s.charAt(i + 1) == '3' || s.charAt(i + 1) == '4')) {
                    char d = s.charAt(i + 1);
                    boolean nextIsPartOfExponent = (i + 2 < n) && (Character.isLetterOrDigit(s.charAt(i + 2)) || s.charAt(i + 2) == '.' || s.charAt(i + 2) == '_');
                    if (!nextIsPartOfExponent) {
                        sup = (d == '2') ? "²" : ((d == '3') ? "³" : "⁴");
                        expLen = 2;
                    }
                }

                if (sup != null) {
                    int afterIdx = i + expLen;
                    while (afterIdx < n && s.charAt(afterIdx) == ' ') afterIdx++;
                    boolean followedByCaret = (afterIdx < n && s.charAt(afterIdx) == '^');

                    // Check if base immediately before `i` is itself the right-hand side of a `^`
                    int prevIdx = i - 1;
                    while (prevIdx >= 0 && s.charAt(prevIdx) == ' ') prevIdx--;
                    if (prevIdx >= 0 && s.charAt(prevIdx) == ')') {
                        int depth = 1;
                        prevIdx--;
                        while (prevIdx >= 0 && depth > 0) {
                            if (s.charAt(prevIdx) == ')') depth++;
                            else if (s.charAt(prevIdx) == '(') depth--;
                            prevIdx--;
                        }
                    } else {
                        while (prevIdx >= 0 && (Character.isLetterOrDigit(s.charAt(prevIdx)) || s.charAt(prevIdx) == '_' || s.charAt(prevIdx) == '.')) {
                            prevIdx--;
                        }
                    }
                    while (prevIdx >= 0 && s.charAt(prevIdx) == ' ') prevIdx--;
                    boolean precededByCaret = (prevIdx >= 0 && s.charAt(prevIdx) == '^');

                    if (!followedByCaret && !precededByCaret) {
                        out.append(sup);
                        i += expLen;
                        continue;
                    }
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static String prettifyMath(String expr) {
        if (expr == null || expr.isEmpty()) return "";
        String s = expr;
        // Replace function prefixes with proper mathematical notation
        s = s.replace("nthroot(", "ⁿ√(")
                .replace("sqrt(", "√(")
                .replace("sum(", "∑(")
                .replace("prod(", "∏(")
                .replace("defint(", "∫(")
                .replace("int(", "∫(")
                .replace("deriv(", "d/dx(")
                .replace("diff(", "d/dx(")
                .replace("binomial(", "nCr(")
                .replace("asinh(", "sinh⁻¹(")
                .replace("acosh(", "cosh⁻¹(")
                .replace("atanh(", "tanh⁻¹(")
                .replace("asin(", "sin⁻¹(")
                .replace("acos(", "cos⁻¹(")
                .replace("atan(", "tan⁻¹(");

        // Standalone identifiers pi -> π, ans -> ANS
        s = s.replaceAll("\\bpi\\b", "π")
                .replaceAll("\\bans\\b", "ANS");

        // Convert only single (unchained) exponents to superscripts so 2^2^2 stays 2^2^2 instead of 2²²
        s = formatUnchainedSuperscripts(s);

        // Operators: * -> ×, / -> ÷ (except in simple fractions a/b and d/dx)
        s = s.replace("*", " × ");
        if (!s.matches("^-?\\d+/\\d+$")) {
            s = s.replace("d/dx(", "\u0001DDX\u0001")
                    .replace("/", " ÷ ")
                    .replace("\u0001DDX\u0001", "d/dx(");
        }

        // Replace ASCII hyphen with mathematical minus (−), preserving scientific notation like 1e-34
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-') {
                boolean isSciExp = (i > 0 && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E'));
                sb.append(isSciExp ? '-' : '−');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    private void refreshHistoryViews() {
        if (historyListContainer == null) return;
        historyListContainer.removeAllViews();
        for (final HistoryEntry item : history) {
            LinearLayout itemBox = new LinearLayout(this);
            itemBox.setOrientation(LinearLayout.VERTICAL);
            itemBox.setBackgroundColor(Color.TRANSPARENT);
            itemBox.setPadding(dp(6), dp(4), dp(6), dp(4));

            // Row 1: Expression (vertical fraction if simple fraction a/b)
            boolean isSimpleFraction = item.expr.matches("^-?\\d+/\\d+$");
            if (isSimpleFraction) {
                String[] parts = item.expr.split("/");
                View fracView = createFractionView(parts[0], parts[1]);
                fracView.setOnClickListener(v -> {
                    currentInput += item.expr;
                    saveState();
                    updateInputAndPreview();
                });
                LinearLayout.LayoutParams fracLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fracLp.gravity = Gravity.START;
                fracLp.bottomMargin = dp(4);
                itemBox.addView(fracView, fracLp);
            } else {
                TextView exprTv = new TextView(this);
                exprTv.setText(prettifyMath(item.expr));
                exprTv.setTextColor(colOnSurface);
                setScaledTextSize(exprTv, 18f, 2.0f);
                exprTv.setTypeface(Typeface.DEFAULT_BOLD);
                exprTv.setOnClickListener(v -> {
                    currentInput += item.expr;
                    saveState();
                    updateInputAndPreview();
                });
                LinearLayout.LayoutParams expLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                expLp.bottomMargin = dp(4);
                itemBox.addView(exprTv, expLp);
            }

            // Row 2: Left-aligned pill badge "> exakt" + "=" + right-aligned result
            LinearLayout resRow = new LinearLayout(this);
            resRow.setOrientation(LinearLayout.HORIZONTAL);
            resRow.setGravity(Gravity.CENTER_VERTICAL);

            Button exactBadge = makeMaterialButton(
                    item.showingExact ? "> dezimal" : "> exakt",
                    0xFF1A73E8,
                    0xFF001D35,
                    14,
                    12f,
                    2.0f
            );
            exactBadge.setSingleLine(true);
            exactBadge.setIncludeFontPadding(false);
            exactBadge.setMinimumWidth(dp(86));
            exactBadge.setMinimumHeight(dp(30));
            exactBadge.setPadding(dp(6), dp(1), dp(6), dp(1));
            setScaledTextSize(exactBadge, 11.5f, 2.0f);
            exactBadge.setAutoSizeTextTypeUniformWithConfiguration(8, Math.round(11.5f * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
            exactBadge.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                item.showingExact = !item.showingExact;
                saveState();
                refreshHistoryViews();
            });
            resRow.addView(exactBadge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView eqTv = new TextView(this);
            eqTv.setText("  =  ");
            eqTv.setTextColor(0xFF4285F4);
            setScaledTextSize(eqTv, 18f, 2.0f);
            eqTv.setTypeface(Typeface.DEFAULT_BOLD);
            resRow.addView(eqTv);

            TextView resTv = new TextView(this);
            String rawShown = item.showingExact ? item.exactText : item.decimalText;
            String shown = formatDisplayNumber(rawShown);
            resTv.setText(shown);
            resTv.setGravity(Gravity.END);
            resTv.setTextColor(colOnSurface);
            setScaledTextSize(resTv, 23f, 2.0f);
            resTv.setTypeface(Typeface.DEFAULT_BOLD);
            resTv.setOnClickListener(v -> {
                currentInput += rawShown;
                saveState();
                updateInputAndPreview();
            });
            resRow.addView(resTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            itemBox.addView(resRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (item.reportText != null && !item.reportText.isEmpty()) {
                TextView repTv = new TextView(this);
                repTv.setText(item.reportText);
                repTv.setTextColor(colOnSurfaceVariant);
                setScaledTextSize(repTv, 11.5f, 2.0f);
                repTv.setPadding(0, dp(2), 0, 0);
                itemBox.addView(repTv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            View divider = new View(this);
            divider.setBackgroundColor(0x22FFFFFF);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divLp.topMargin = dp(8);
            itemBox.addView(divider, divLp);

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(10);
            historyListContainer.addView(itemBox, rLp);
        }
        if (historyScrollView != null) {
            historyScrollView.post(() -> historyScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateInputAndPreview() {
        if (inputDisplayView == null || previewDisplayView == null || cursorView == null) return;
        if (currentInput.isEmpty()) {
            inputDisplayView.setText("");
            cursorView.setVisibility(View.VISIBLE);
            String ansStr = engine.formatAns(false);
            if (ansStr != null && !ansStr.isEmpty() && !ansStr.equals("0")) {
                previewDisplayView.setText("ANS = " + ansStr);
            } else {
                previewDisplayView.setText("ANS = 0");
            }
        } else {
            String math = prettifyMath(currentInput);
            inputDisplayView.setText(math);
            cursorView.setVisibility(View.VISIBLE);
            try {
                CalculatorEngine.Result result = engine.evaluate(currentInput);
                previewDisplayView.setText("= " + result.decimalText);
            } catch (Exception e) {
                previewDisplayView.setText("");
            }
        }
    }

    private static final class KeyDef {
        final String label;
        final String token;

        KeyDef(String label, String token) {
            this.label = label;
            this.token = token;
        }
    }

    private static KeyDef k(String label, String token) {
        return new KeyDef(label, token);
    }

    private static KeyDef k(String same) {
        return new KeyDef(same, same);
    }

    private void refreshKeyboardSection() {
        if (sectionHeaderRow == null || keyboardBodyContainer == null) return;
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        sectionHeaderRow.removeAllViews();

        String[] secNames = {"( ) √ ^", "sin", "∑ ∫ i", "a b c", "mehr"};
        for (int i = 0; i < secNames.length; i++) {
            final int sIdx = i;
            boolean sel = (activeSection == i);
            Button b = makeMaterialButton(
                    secNames[i],
                    Color.TRANSPARENT,
                    sel ? colPrimary : colOnSurfaceVariant,
                    12,
                    isLandscape ? 12f : 13.5f,
                    2.0f
            );
            if (sel) {
                b.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                b.setTypeface(Typeface.DEFAULT);
            }
            b.setSingleLine(true);
            b.setPadding(dp(4), dp(2), dp(4), dp(2));
            b.setMinimumHeight(dp(isLandscape ? 28 : 34));
            b.setAutoSizeTextTypeUniformWithConfiguration(
                    8, Math.round(13.5f * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                activeSection = sIdx;
                refreshKeyboardSection();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(1), 0, dp(1), 0);
            sectionHeaderRow.addView(b, lp);
        }

        keyboardBodyContainer.removeAllViews();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        KeyDef[][][] sections = {
                // 0: ( ) √ ^
                {
                        {k("("), k(")"), k("π", "pi"), k("e")},
                        {k("x²", "^2"), k("xⁿ", "^("), k("√x", "sqrt("), k("ⁿ√x", "nthroot(3,")},
                        {k("ln", "ln("), k("logₐ(x)", "log(10,"), k("10ⁿ", "10^("), k("ans", "ans")}
                },
                // 1: sin
                {
                        {k("sin", "sin("), k("sin⁻¹", "asin("), k("sinh", "sinh("), k("sinh⁻¹", "asinh(")},
                        {k("cos", "cos("), k("cos⁻¹", "acos("), k("cosh", "cosh("), k("cosh⁻¹", "acosh(")},
                        {k("tan", "tan("), k("tan⁻¹", "atan("), k("tanh", "tanh("), k("tanh⁻¹", "atanh(")}
                },
                // 2: ∑ ∫ i
                {
                        {k("∑", "sum("), k("∏", "prod("), k("(ⁿₖ)", "binomial("), k("n!", "!")},
                        {k("∫", "int("), k("d/dx", "deriv("), k("mod", "mod("), k("i")},
                        {k("[ ⋮ ]", "[["), k("[ ⋮ ]⁻¹", "inv("), k("[ ⋮ ]ᵀ", "trans("), k("det", "det(")}
                },
                // 3: a b c
                showMoreVariables ?
                new KeyDef[][]{
                        {k("a"), k("b"), k("c"), k("m")},
                        {k("k"), k("l"), k("n"), k(":=")},
                        {k("x"), k("y"), k("z"), k("mehr", "TOGGLE_VAR_MORE")}
                } :
                new KeyDef[][]{
                        {k("%"), k("x"), k("y"), k("n")},
                        {k("c₀", "c0"), k("G", "G0"), k("g"), k("h")},
                        {k("e⁻", "e_charge"), k("μ₀", "mu0"), k("N_A"), k("mehr", "TOGGLE_VAR_MORE")}
                },
                // 4: mehr
                {
                        {k("|x|", "abs("), k("⌊x⌋", "floor("), k("⌈x⌉", "ceil("), k("round", "round(")},
                        {k("gcd", "gcd("), k("lcm", "lcm("), k("sgn", "sign("), k("exp", "exp(")},
                        {k(engine.isRadiansMode() ? "RAD" : "DEG", "TOGGLE_ANGLE"), k("🔬 Konst.", "TAB_2"), k("📈 2D-Plot", "TAB_1"), k("⚙ Setup", "TAB_3")}
                }
        };

        KeyDef[][] rows = sections[Math.min(activeSection, sections.length - 1)];
        for (KeyDef[] row : rows) {
            LinearLayout rLayout = new LinearLayout(this);
            rLayout.setOrientation(LinearLayout.HORIZONTAL);
            for (final KeyDef keyDef : row) {
                final String tok = keyDef.token;
                final String lbl = keyDef.label;
                boolean isAccent = tok.equals("TOGGLE_VAR_MORE") || tok.equals("TAB_1") || tok.equals("TAB_3");
                int fg = isAccent ? colPrimary : colOnSurface;

                float btnFontSize = 15.5f;
                if (lbl.length() > 6) btnFontSize = 11.5f;
                else if (lbl.length() > 4) btnFontSize = 13f;

                Button kb = makeMaterialButton(lbl, Color.TRANSPARENT, fg, 14, btnFontSize, 2.0f);
                kb.setSingleLine(true);
                kb.setPadding(dp(2), dp(1), dp(2), dp(1));
                kb.setAutoSizeTextTypeUniformWithConfiguration(
                        8, Math.round(btnFontSize * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
                kb.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    handleKeyPress(tok);
                });
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                klp.setMargins(dp(2), dp(1), dp(2), dp(1));
                rLayout.addView(kb, klp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            grid.addView(rLayout, rlp);
        }
        keyboardBodyContainer.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static final String[] MULTI_CHAR_TOKENS = {
            " := ", ":= ",
            "nthroot(3,", "nthroot(", "log(10,", "10^(",
            "transpose(", "binomial(",
            "asinh(", "acosh(", "atanh(",
            "asin(", "acos(", "atan(",
            "sinh(", "cosh(", "tanh(",
            "sin(", "cos(", "tan(",
            "sqrt(", "deriv(", "defint(", "floor(", "round(", "trans(",
            "prod(", "sign(", "ceil(",
            "sum(", "int(", "det(", "inv(", "abs(", "exp(", "gcd(", "lcm(", "mod(", "log(",
            "ln(", "d(",
            "^(", "^2", "[[", "]]", "ans", "pi",
            "alpha_fs", "e_charge", "sigma", "R_inf", "mu_B", "mu_e",
            "Phi0", "eps0", "mu0", "c0", "k_B", "m_P", "t_P", "l_P",
            "Z0", "K_J", "R_K", "G0", "E_h", "N_A", "m_u", "m_e", "m_p", "m_n"
    };

    private static String deleteLastToken(String input) {
        if (input == null || input.isEmpty()) return "";
        for (String tok : MULTI_CHAR_TOKENS) {
            if (input.endsWith(tok)) {
                return input.substring(0, input.length() - tok.length());
            }
        }
        return input.substring(0, input.length() - 1);
    }

    private void handleKeyPress(String key) {
        if (key.equals("TOGGLE_VAR_MORE")) {
            showMoreVariables = !showMoreVariables;
            saveState();
            refreshKeyboardSection();
            return;
        }
        if (key.equals("TOGGLE_ANGLE")) {
            engine.setRadiansMode(!engine.isRadiansMode());
            saveState();
            rebuildScreen();
            return;
        }
        if (key.equals("TAB_1")) {
            activeTab = 1;
            rebuildScreen();
            return;
        }
        if (key.equals("TAB_2")) {
            activeTab = 2;
            rebuildScreen();
            return;
        }
        if (key.equals("TAB_3")) {
            activeTab = 3;
            rebuildScreen();
            return;
        }
        if (key.equals("AC_ALL")) {
            currentInput = "";
            saveState();
            updateInputAndPreview();
            return;
        }

        if (key.equals("=")) {
            if (!currentInput.trim().isEmpty()) {
                try {
                    CalculatorEngine.Result result = engine.evaluateAndCommit(currentInput);
                    String dec = result.decimalText;
                    String ex = result.exactText;
                    String rep = getIntegrationReportText(currentInput);
                    history.add(new HistoryEntry(currentInput, ex, dec, rep, showExactFirst));
                    currentInput = "";
                    saveState();
                    refreshHistoryViews();
                } catch (Exception e) {
                    history.add(new HistoryEntry(currentInput, "Fehler", "Fehler", true));
                    saveState();
                    refreshHistoryViews();
                }
            }
        } else if (key.equals("DEL")) {
            currentInput = deleteLastToken(currentInput);
            saveState();
        } else if (key.equals(":=")) {
            if (!currentInput.isEmpty() && !currentInput.endsWith(" ")) {
                currentInput += " := ";
            } else {
                currentInput += ":= ";
            }
            saveState();
        } else if (key.startsWith("AC")) {
            history.clear();
            saveState();
            refreshHistoryViews();
        } else {
            currentInput += key;
            saveState();
        }
        updateInputAndPreview();
    }

    private void buildPlotterTab(FrameLayout parent) {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(isLandscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        header.setBackground(roundedRect(colSurfaceContainer, 16));
        header.setPadding(dp(10), isLandscape ? dp(4) : dp(8), dp(10), isLandscape ? dp(4) : dp(8));

        EditText f1Input = new EditText(this);
        f1Input.setText(plotExpr1);
        f1Input.setTextColor(colPrimary);
        f1Input.setHint("f₁(x)");
        if (isLandscape) {
            header.addView(f1Input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            View gap = new View(this);
            header.addView(gap, new LinearLayout.LayoutParams(dp(8), dp(1)));
        } else {
            header.addView(f1Input);
        }

        EditText f2Input = new EditText(this);
        f2Input.setText(plotExpr2);
        f2Input.setTextColor(colOnSecondaryContainer);
        f2Input.setHint("f₂(x)");
        if (isLandscape) {
            header.addView(f2Input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            header.addView(f2Input);
        }

        final Plotter2DView plotView = new Plotter2DView(this);
        f1Input.addTextChangedListener(new SimpleWatcher(s -> {
            plotExpr1 = s;
            plotView.invalidate();
            saveState();
        }));
        f2Input.addTextChangedListener(new SimpleWatcher(s -> {
            plotExpr2 = s;
            plotView.invalidate();
            saveState();
        }));

        col.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        pLp.topMargin = dp(6);
        col.addView(plotView, pLp);
        parent.addView(col);
    }

    private class Plotter2DView extends View {
        private final Paint minorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint majorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curve1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curve2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaleDetector;
        private float centerX = 0f;
        private float centerY = 0f;
        private float scale = 64f;
        private float lastTouchX;
        private float lastTouchY;

        Plotter2DView(Context ctx) {
            super(ctx);
            // Exact FunctionPlotting2DView.onDraw strokes: 1.0f minor, 1.25f major, 19.0f axis label
            minorPaint.setStrokeWidth(1.0f);
            minorPaint.setColor(0xFF2B2F38);
            majorPaint.setStrokeWidth(1.25f);
            majorPaint.setColor(0xFF454B58);
            axisPaint.setStrokeWidth(2.2f);
            axisPaint.setColor(colOnSurfaceVariant);
            axisPaint.setTextSize(19.0f);

            curve1Paint.setStrokeWidth(4.0f);
            curve1Paint.setStyle(Paint.Style.STROKE);
            curve1Paint.setColor(colPrimary);

            curve2Paint.setStrokeWidth(4.0f);
            curve2Paint.setStyle(Paint.Style.STROKE);
            curve2Paint.setColor(0xFF64B5F6);

            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    scale = Math.max(8f, Math.min(2048f, scale * factor));
                    invalidate();
                    return true;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            if (scaleDetector.isInProgress()) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (event.getPointerCount() == 1) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    centerX -= dx / scale;
                    centerY += dy / scale;
                    invalidate();
                }
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(colSurfaceContainer);
            int w = getWidth();
            int h = getHeight();
            float cx = w * 0.5f;
            float cy = h * 0.5f;

            float xMin = centerX - (w * 0.5f) / scale;
            float xMax = centerX + (w * 0.5f) / scale;
            float yMin = centerY - (h * 0.5f) / scale;
            float yMax = centerY + (h * 0.5f) / scale;

            for (float gx = (float) Math.floor(xMin); gx <= xMax; gx += 0.5f) {
                float sx = cx + (gx - centerX) * scale;
                boolean major = Math.abs(gx - Math.round(gx)) < 0.01f;
                canvas.drawLine(sx, 0, sx, h, major ? majorPaint : minorPaint);
            }
            for (float gy = (float) Math.floor(yMin); gy <= yMax; gy += 0.5f) {
                float sy = cy - (gy - centerY) * scale;
                boolean major = Math.abs(gy - Math.round(gy)) < 0.01f;
                canvas.drawLine(0, sy, w, sy, major ? majorPaint : minorPaint);
            }

            float x0 = cx - centerX * scale;
            float y0 = cy + centerY * scale;
            canvas.drawLine(x0, 0, x0, h, axisPaint);
            canvas.drawLine(0, y0, w, y0, axisPaint);

            drawCurve(canvas, plotExpr1, curve1Paint, w, cx, cy);
            drawCurve(canvas, plotExpr2, curve2Paint, w, cx, cy);
        }

        private void drawCurve(Canvas canvas, String expr, Paint paint, int w, float cx, float cy) {
            if (expr == null || expr.trim().isEmpty()) return;
            float prevX = 0f, prevY = 0f;
            boolean hasPrev = false;
            for (int px = 0; px <= w; px += 3) {
                double xv = centerX + (px - cx) / scale;
                try {
                    double yv = engine.evaluateForX(expr, xv);
                    if (Double.isFinite(yv)) {
                        float sy = (float) (cy - (yv - centerY) * scale);
                        if (hasPrev && Math.abs(sy - prevY) < getHeight()) {
                            canvas.drawLine(prevX, prevY, px, sy, paint);
                        }
                        prevX = px;
                        prevY = sy;
                        hasPrev = true;
                    } else {
                        hasPrev = false;
                    }
                } catch (Exception e) {
                    hasPrev = false;
                }
            }
        }
    }

    private static CharSequence formatSubscriptSymbol(String sym) {
        int idx = sym.indexOf('_');
        if (idx < 0 || idx == sym.length() - 1) return sym;
        String base = sym.substring(0, idx);
        String sub = sym.substring(idx + 1);
        SpannableString ss = new SpannableString(base + sub);
        ss.setSpan(new SubscriptSpan(), base.length(), base.length() + sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new RelativeSizeSpan(0.75f), base.length(), base.length() + sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private void buildConstantsTab(FrameLayout parent) {
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView hdr = new TextView(this);
        hdr.setText("Physikalische & Mathematische Konstanten");
        hdr.setTextColor(colPrimary);
        setScaledTextSize(hdr, 16f, 2.0f);
        hdr.setTypeface(Typeface.DEFAULT_BOLD);
        hdr.setPadding(0, 0, 0, dp(10));
        list.addView(hdr);

        String[][] consts = {
                {"c₀", "c0", "Lichtgeschwindigkeit im Vakuum", "2.9979 × 10⁸ m/s"},
                {"μ₀", "mu0", "Magnetische Feldkonstante", "12.566 × 10⁻⁷ N/A²"},
                {"ε₀", "eps0", "Elektrische Feldkonstante", "8.8542 × 10⁻¹² F/m"},
                {"G", "G", "Gravitationskonstante", "6.6738 × 10⁻¹¹ m³/(kg·s²)"},
                {"h", "h", "Plancksches Wirkungsquantum", "6.6261 × 10⁻³⁴ J·s"},
                {"α", "alpha_fs", "Feinstrukturkonstante", "7.2974 × 10⁻³"},
                {"k_B", "k_B", "Boltzmann-Konstante", "1.3806 × 10⁻²³ J/K"},
                {"g", "g", "Normfallbeschleunigung", "9.8067 m/s²"},
                {"m_P", "m_P", "Planck-Masse", "2.1765 × 10⁻⁸ kg"},
                {"t_P", "t_P", "Planck-Zeit", "5.3911 × 10⁻⁴⁴ s"},
                {"l_P", "l_P", "Planck-Länge", "1.6162 × 10⁻³⁵ m"},
                {"e⁻", "e_charge", "Elementarladung", "1.6022 × 10⁻¹⁹ C"},
                {"Z₀", "Z0", "Wellenwiderstand des Vakuums", "376.73 Ω"},
                {"Φ₀", "Phi0", "Magnetisches Flussquant", "2.0678 × 10⁻¹⁵ Wb"},
                {"K_J", "K_J", "Josephson-Konstante", "4.835 × 10¹⁴ Hz/V"},
                {"R_K", "R_K", "Von-Klitzing-Konstante", "25813.0 Ω"},
                {"G₀", "G0", "Leitwertquant", "7.7481 × 10⁻⁵ S"},
                {"σ", "sigma", "Stefan-Boltzmann-Konstante", "5.6704 × 10⁻⁸ W/(m²·K⁴)"},
                {"R_∞", "R_inf", "Rydberg-Konstante", "1.0974 × 10⁷ 1/m"},
                {"E_h", "E_h", "Hartree-Energie", "4.3597 × 10⁻¹⁸ J"},
                {"μ_B", "mu_B", "Bohrsches Magneton", "1.3996 × 10¹⁰ J/T"},
                {"μ_e", "mu_e", "Magnetisches Moment des Elektrons", "−9.2848 × 10⁻²⁴ J/T"},
                {"N_A", "N_A", "Avogadro-Konstante", "6.0221 × 10²³ 1/mol"},
                {"m_u", "m_u", "Atomare Masseneinheit", "1.6605 × 10⁻²⁷ kg"},
                {"F", "F", "Faraday-Konstante", "96485.0 C/mol"},
                {"R", "R", "Universelle Gaskonstante", "8.3145 J/(mol·K)"},
                {"m_e", "m_e", "Elektronenmasse", "9.1094 × 10⁻³¹ kg"},
                {"m_p", "m_p", "Protonenmasse", "1.6749 × 10⁻²⁷ kg"},
                {"m_n", "m_n", "Neutronenmasse", "1.6726 × 10⁻²⁷ kg"},
                {"π", "pi", "Kreiszahl Pi", "3.14159265…"},
                {"e", "e", "Eulersche Zahl", "2.71828182…"},
                {"i", "i", "Imaginäre Einheit", "√(−1)"}
        };

        for (final String[] c : consts) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(roundedRect(colSurfaceContainer, 14));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView sym = createChip(formatSubscriptSymbol(c[0]), colPrimaryContainer, colOnPrimaryContainer);
            card.addView(sym);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(10), 0, dp(10), 0);
            TextView nameTv = new TextView(this);
            nameTv.setText(c[2]);
            nameTv.setTextColor(colOnSurface);
            setScaledTextSize(nameTv, 13.5f, 2.0f);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            TextView valTv = new TextView(this);
            valTv.setText(c[3]);
            valTv.setTextColor(colOnSurfaceVariant);
            setScaledTextSize(valTv, 12.5f, 2.0f);
            info.addView(nameTv);
            info.addView(valTv);
            card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button ins = makeMaterialButton("+ Einsetzen", colPrimary, colOnPrimary, 12, 12f, 2.0f);
            ins.setSingleLine(true);
            ins.setPadding(dp(10), dp(6), dp(10), dp(6));
            ins.setAutoSizeTextTypeUniformWithConfiguration(8, Math.round(12f * 2.0f), 1, TypedValue.COMPLEX_UNIT_DIP);
            ins.setOnClickListener(v -> {
                currentInput += c[1];
                activeTab = 0;
                saveState();
                rebuildScreen();
            });
            card.addView(ins);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            list.addView(card, lp);
        }
        sv.addView(list);
        parent.addView(sv);
    }

    private void buildConverterTab(FrameLayout parent) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView header = new TextView(this);
        header.setText("Einstellungen");
        header.setTextColor(colPrimary);
        setScaledTextSize(header, 17f, 2.0f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(header);

        TextView desc = new TextView(this);
        desc.setText("Rechenmodus, Winkelmaß und Ergebnisanzeige");
        desc.setTextColor(colOnSurfaceVariant);
        setScaledTextSize(desc, 13.5f, 2.0f);
        desc.setPadding(0, dp(4), 0, dp(14));
        col.addView(desc);

        // Setting 1: Exact first
        Button toggleExact = makeMaterialButton(
                "Exakte Brüche zuerst anzeigen: " + (showExactFirst ? "AN" : "AUS"),
                colSurfaceContainer, colOnSurface, 14, 14f, 2.0f);
        toggleExact.setOnClickListener(v -> {
            showExactFirst = !showExactFirst;
            saveState();
            rebuildScreen();
        });
        col.addView(toggleExact);

        View sp1 = new View(this);
        col.addView(sp1, new LinearLayout.LayoutParams(dp(1), dp(8)));

        // Setting 2: Modulo always positive
        Button toggleModulo = makeMaterialButton(
                "Modulo immer positiv: " + (engine.isModuloAlwaysPositive() ? "AN (−7 mod 3 = 2)" : "AUS (−7 mod 3 = −1)"),
                colSurfaceContainer, colOnSurface, 14, 14f, 2.0f);
        toggleModulo.setOnClickListener(v -> {
            engine.setModuloAlwaysPositive(!engine.isModuloAlwaysPositive());
            saveState();
            rebuildScreen();
        });
        col.addView(toggleModulo);

        View sp2 = new View(this);
        col.addView(sp2, new LinearLayout.LayoutParams(dp(1), dp(8)));

        // Setting 3: Angle mode
        Button toggleAngle = makeMaterialButton(
                "Standard-Winkelmodus: " + (engine.isRadiansMode() ? "RAD (Bogenmaß)" : "DEG (Grad)"),
                colSurfaceContainer, colOnSurface, 14, 14f, 2.0f);
        toggleAngle.setOnClickListener(v -> {
            engine.setRadiansMode(!engine.isRadiansMode());
            saveState();
            rebuildScreen();
        });
        col.addView(toggleAngle);

        View sp3 = new View(this);
        col.addView(sp3, new LinearLayout.LayoutParams(dp(1), dp(12)));

        // Setting 4: Clear history
        Button clearHist = makeMaterialButton(
                "🗑 Verlauf vollständig leeren",
                colSecondaryContainer, colOnSecondaryContainer, 14, 14f, 2.0f);
        clearHist.setOnClickListener(v -> {
            history.clear();
            saveState();
            rebuildScreen();
        });
        col.addView(clearHist);

        sv.addView(col);
        parent.addView(sv);
    }

    // --- UI Helpers ---
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setScaledTextSize(TextView tv, float baseSp, float maxScale) {
        float scale = getResources().getConfiguration().fontScale;
        if (scale <= 0f) scale = 1.0f;
        float effective = Math.min(scale, maxScale);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, baseSp * effective);
    }

    private GradientDrawable roundedTopRect(int fillColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        float r = dp(radiusDp);
        gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return gd;
    }

    public static String formatDisplayNumber(String numStr) {
        if (numStr == null || numStr.isEmpty()) return "";
        if (numStr.contains("e") || numStr.contains("E") || numStr.contains("i") || numStr.contains("[") || numStr.contains("/")) {
            return numStr;
        }
        String clean = numStr.replace(',', '.');
        int dot = clean.indexOf('.');
        if (dot >= 0) {
            String fracPart = clean.substring(dot + 1);
            if (fracPart.length() > 6) {
                try {
                    double d = Double.parseDouble(clean);
                    clean = String.format(java.util.Locale.US, "%.6f", d);
                    dot = clean.indexOf('.');
                } catch (Exception ignored) {
                }
            }
            String intPart = clean.substring(0, dot);
            fracPart = clean.substring(dot + 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fracPart.length(); i++) {
                if (i > 0 && i % 3 == 0) sb.append(' ');
                sb.append(fracPart.charAt(i));
            }
            return intPart + "," + sb.toString();
        }
        return numStr;
    }

    private View createFractionView(String num, String den) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView numTv = new TextView(this);
        numTv.setText(num);
        numTv.setTextColor(colOnSurface);
        numTv.setGravity(Gravity.CENTER);
        setScaledTextSize(numTv, 18f, 2.0f);
        numTv.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(numTv);

        int maxLen = Math.max(num.length(), den.length());
        int lineW = Math.max(dp(maxLen * 12 + 10), dp(36));

        View line = new View(this);
        line.setBackgroundColor(colOnSurface);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(lineW, dp(1.5f));
        lineLp.gravity = Gravity.CENTER_HORIZONTAL;
        lineLp.topMargin = dp(2);
        lineLp.bottomMargin = dp(2);
        box.addView(line, lineLp);

        TextView denTv = new TextView(this);
        denTv.setText(den);
        denTv.setTextColor(colOnSurface);
        denTv.setGravity(Gravity.CENTER);
        setScaledTextSize(denTv, 18f, 2.0f);
        denTv.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(denTv);

        return box;
    }

    private GradientDrawable roundedRect(int fillColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private GradientDrawable roundedRectStroke(int fillColor, int strokeColor, int radiusDp, int strokePx) {
        GradientDrawable gd = roundedRect(fillColor, radiusDp);
        gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    private TextView createChip(CharSequence text, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        setScaledTextSize(tv, 11.5f, 2.0f);
        tv.setSingleLine(true);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setBackground(roundedRect(bg, 12));
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        return tv;
    }

    private Button makeMaterialButton(String text, int bg, int fg, int cornerDp, float spSize) {
        return makeMaterialButton(text, bg, fg, cornerDp, spSize, 2.0f);
    }

    private Button makeMaterialButton(String text, int bg, int fg, int cornerDp, float spSize, float maxScale) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(fg);
        setScaledTextSize(b, spSize, maxScale);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(2), dp(1), dp(2), dp(1));
        GradientDrawable mask = roundedRect(Color.WHITE, cornerDp);
        GradientDrawable content = roundedRect(bg, cornerDp);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), content, mask);
        b.setBackground(ripple);
        return b;
    }

    private interface TextCallback {
        void onText(String s);
    }

    private static class SimpleWatcher implements TextWatcher {
        private final TextCallback cb;
        SimpleWatcher(TextCallback cb) { this.cb = cb; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            cb.onText(s.toString());
        }
        @Override public void afterTextChanged(Editable s) {}
    }
}

