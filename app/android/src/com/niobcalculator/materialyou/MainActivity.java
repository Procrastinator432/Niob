package com.niobcalculator.materialyou;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.RectF;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.TextPaint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.SubscriptSpan;
import android.util.TypedValue;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Android 12 (API 31+) Material You (Monet Dynamic Color) Scientific Calculator.
 * 100% Offline: Uses Android 12 system_accent1_* and system_neutral1_* wallpaper colors.
 */
public class MainActivity extends Activity {

    private final CalculatorEngine engine = new CalculatorEngine();
    private static final ExecutorService RESULT_WRITER = Executors.newSingleThreadExecutor();
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService evalExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong previewGeneration = new AtomicLong(0);
    private Future<?> pendingPreview;
    private boolean resultWritePending;
    private long draftRevision;
    private final Handler draftHandler = new Handler(Looper.getMainLooper());
    private final Runnable writeDraft = this::saveDraft;
    private final CategoryDef[] constantCategories = getConstantCategories();
    private boolean moduloPositive = true;
    private boolean showExactFirst = true;
    private static boolean useCommaDecimal = true;
    private static int thousandsGrouping = 0; // 0=Aus, 1=Punkt/Komma, 2=Leerzeichen

    private FormulaEditor formula;
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
    private static final int COL_PLOT_CURVE_2 = 0xFF64B5F6;

    private static class HistoryEntry {
        String expr;
        String exactText;
        String decimalText;
        String reusableText;
        String reportText;
        boolean showingExact;
        boolean hasExact;
        String formulaLayout = "";

        HistoryEntry(String expr, String exactText, String decimalText, boolean showingExact) {
            this(expr, exactText, decimalText, null, showingExact);
        }

        HistoryEntry(String expr, String exactText, String decimalText, String reportText, boolean showingExact) {
            this(expr, exactText, decimalText, exactText, reportText, showingExact, false);
        }

        HistoryEntry(String expr, String exactText, String decimalText, String reusableText, String reportText, boolean showingExact) {
            this(expr, exactText, decimalText, reusableText, reportText, showingExact, false);
        }

        HistoryEntry(String expr, String exactText, String decimalText, String reusableText, String reportText, boolean showingExact, boolean hasExact) {
            this.expr = expr;
            this.exactText = exactText;
            this.decimalText = decimalText;
            this.reusableText = (reusableText != null && !reusableText.isEmpty()) ? reusableText : exactText;
            this.reportText = reportText;
            this.showingExact = showingExact;
            this.hasExact = hasExact;
        }
    }

    private final List<HistoryEntry> history = new ArrayList<>();
    private LinearLayout rootContainer;

    private TextView previewDisplayView;
    private FrameLayout previewMatrix;

    private ScrollView historyScrollView;
    private LinearLayout historyListContainer;
    private FrameLayout keyboardBodyContainer;
    private LinearLayout sectionHeaderRow;
    private FormulaEditor plotFormula1;
    private FormulaEditor plotFormula2;
    private CalculatorEngine.PlotFunction compiledPlotFn1;
    private CalculatorEngine.PlotFunction compiledPlotFn2;
    private String compiledPlotExpression1;
    private String compiledPlotExpression2;
    private Plotter2DView plotView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        resolveAndroid12DynamicColors();

        // Load saved state (Preferences & History & Calculations & UI Drafts)
        SharedPreferences prefs = getSharedPreferences("niob_calculator", MODE_PRIVATE);
        SharedPreferences drafts = getSharedPreferences("niob_drafts", MODE_PRIVATE);
        showExactFirst = prefs.getBoolean("show_exact_first", true);
        showMoreVariables = prefs.getBoolean("show_more_variables", false);
        useCommaDecimal = prefs.getBoolean("use_comma_decimal", true);
        thousandsGrouping = prefs.getInt("thousands_grouping", 0);
        engine.setRadiansMode(prefs.getBoolean("radians_mode", true));
        engine.setModuloAlwaysPositive(prefs.getBoolean("modulo_always_positive", true));

        String ansValStr = prefs.getString("ans_value", null);
        if (ansValStr != null) {
            engine.setLastAns(engine.deserializeValue(ansValStr));
        }
        String ansMatStr = prefs.getString("ans_matrix", null);
        if (ansMatStr != null) {
            engine.setLastMatrixAns(engine.deserializeMatrix(ansMatStr));
        }

        for (String varName : CalculatorEngine.USER_VARIABLES) {
            String vStr = prefs.getString("var_" + varName, null);
            if ("__UNSET__".equals(vStr)) {
                engine.unsetVariable(varName);
            } else if (vStr != null) {
                engine.setUserVariable(varName, engine.deserializeValue(vStr));
            }
            String mvStr = prefs.getString("mat_var_" + varName, null);
            if (mvStr != null) {
                double[][] matVal = engine.deserializeMatrix(mvStr);
                if (matVal != null) {
                    engine.setMatrixVariable(varName, matVal);
                }
            }
        }

        formula = new FormulaEditor(this, colOnSurface, colPrimary, editor -> {
            ++draftRevision;
            scheduleDraftSave();
            updatePreviewOnly();
        }, () -> handleKeyPress("="), MULTI_CHAR_TOKENS);
        formula.setCommaDecimal(useCommaDecimal);
        long savedRevision = drafts.getLong("calculator_revision", 0);
        long committedRevision = prefs.getLong("committed_draft_revision", -1);
        draftRevision = Math.max(savedRevision, committedRevision);
        boolean restoreCalculatorDraft = savedRevision > committedRevision;
        formula.restore(restoreCalculatorDraft ? drafts.getString("formula_draft", prefs.getString("formula_draft", "")) : "",
                restoreCalculatorDraft ? drafts.getString("current_input", prefs.getString("current_input", "")) : "");

        plotFormula1 = makePlotFormula("f₁(x)", colPrimary,
                drafts.getString("plot_layout_1", prefs.getString("plot_layout_1", "")),
                drafts.getString("plot_expr_1", prefs.getString("plot_expr_1", "sin(x) * cos(x/2)")));
        plotFormula2 = makePlotFormula("f₂(x)", COL_PLOT_CURVE_2,
                drafts.getString("plot_layout_2", prefs.getString("plot_layout_2", "")),
                drafts.getString("plot_expr_2", prefs.getString("plot_expr_2", "x^2 / 4 - 2")));

        int histCount = prefs.getInt("history_count", -1);
        if (histCount >= 0) {
            for (int i = 0; i < histCount; i++) {
                String expr = prefs.getString("hist_expr_" + i, "");
                String ex = prefs.getString("hist_ex_" + i, "");
                String dec = prefs.getString("hist_dec_" + i, "");
                String reuse = prefs.getString("hist_reuse_" + i, "");
                String rep = prefs.getString("hist_rep_" + i, "");
                boolean showEx = prefs.getBoolean("hist_show_ex_" + i, showExactFirst);
                boolean hasEx = prefs.contains("hist_has_ex_" + i)
                        ? prefs.getBoolean("hist_has_ex_" + i, false)
                        : (!ex.equals(dec) || isPlainIntegerResult(ex));
                if (!expr.isEmpty()) {
                    HistoryEntry entry = new HistoryEntry(expr, ex, dec, reuse.isEmpty() ? legacyResult(ex, dec) : reuse,
                            rep.isEmpty() ? null : rep, showEx, hasEx);
                    entry.formulaLayout = prefs.getString("hist_layout_" + i, "");
                    history.add(entry);
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
        rootContainer.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            android.graphics.Insets insets = windowInsets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        setContentView(rootContainer);

        rebuildScreen();
    }

    private static String legacyResult(String exact, String decimal) {
        int exactAssignment = exact.indexOf(":=");
        int decimalAssignment = decimal.indexOf(":=");
        String exactValue = exactAssignment < 0 ? exact : exact.substring(exactAssignment + 2).trim();
        String decimalValue = decimalAssignment < 0 ? decimal : decimal.substring(decimalAssignment + 2).trim();
        // Earlier versions inferred radicals from rounded values.
        if (exactValue.contains("√") && !decimalValue.isEmpty()) return decimalValue;
        return exactValue;
    }

    private void scheduleDraftSave() {
        draftHandler.removeCallbacks(writeDraft);
        draftHandler.postDelayed(writeDraft, 150);
    }

    private void saveDraft() {
        draftHandler.removeCallbacks(writeDraft);
        SharedPreferences.Editor ed = getSharedPreferences("niob_drafts", MODE_PRIVATE).edit();
        if (formula != null) {
            ed.putString("current_input", formula.expression());
            ed.putString("formula_draft", formula.layoutState());
            ed.putLong("calculator_revision", draftRevision);
        }
        if (plotFormula1 != null) {
            ed.putString("plot_expr_1", plotFormula1.expression());
            ed.putString("plot_layout_1", plotFormula1.layoutState());
        }
        if (plotFormula2 != null) {
            ed.putString("plot_expr_2", plotFormula2.expression());
            ed.putString("plot_layout_2", plotFormula2.layoutState());
        }
        ed.apply();
    }

    private void writeHistoryToEditor(SharedPreferences.Editor ed) {
        ed.putInt("history_count", history.size());
        for (int i = 0; i < history.size(); i++) {
            writeHistoryEntry(ed, i, history.get(i));
        }
    }

    private void writeHistoryEntry(SharedPreferences.Editor ed, int index, HistoryEntry entry) {
        ed.putString("hist_expr_" + index, entry.expr);
        ed.putString("hist_layout_" + index, entry.formulaLayout);
        ed.putString("hist_ex_" + index, entry.exactText);
        ed.putString("hist_dec_" + index, entry.decimalText);
        ed.putString("hist_reuse_" + index, entry.reusableText);
        ed.putString("hist_rep_" + index, entry.reportText != null ? entry.reportText : "");
        ed.putBoolean("hist_show_ex_" + index, entry.showingExact);
        ed.putBoolean("hist_has_ex_" + index, entry.hasExact);
    }

    private static boolean isPlainIntegerResult(String s) {
        if (s == null) return false;
        int assignIdx = s.indexOf(":=");
        String rhs = (assignIdx >= 0 ? s.substring(assignIdx + 2) : s).trim();
        return rhs.matches("[+-]?\\d+");
    }

    private void writeVarsAndAnsToEditor(SharedPreferences.Editor ed) {
        ed.putString("ans_value", engine.serializeValue(engine.getLastAns()));
        String serializedMatAns = engine.serializeMatrix(engine.getLastMatrixAns());
        if (serializedMatAns != null) {
            ed.putString("ans_matrix", serializedMatAns);
        } else {
            ed.remove("ans_matrix");
        }
        Map<String, CalculatorEngine.Value> userVars = engine.getUserVariables();
        for (String varName : CalculatorEngine.USER_VARIABLES) {
            double[][] matVar = engine.getMatrixVariable(varName);
            String serializedMatVar = engine.serializeMatrix(matVar);
            if (serializedMatVar != null) {
                ed.putString("mat_var_" + varName, serializedMatVar);
                ed.putString("var_" + varName, "__UNSET__");
            } else {
                ed.remove("mat_var_" + varName);
                if (userVars.containsKey(varName)) {
                    ed.putString("var_" + varName, engine.serializeValue(userVars.get(varName)));
                } else {
                    ed.putString("var_" + varName, "__UNSET__");
                }
            }
        }
    }

    private void persistResult(HistoryEntry entry, CalculatorEngine.Value previousAns,
                               double[][] previousMatrixAns,
                               Map<String, CalculatorEngine.Value> previousVariables,
                               Map<String, double[][]> previousMatrixVariables,
                               long submittedDraft) {
        SharedPreferences prefs = getSharedPreferences("niob_calculator", MODE_PRIVATE);
        long previousCommittedDraft = prefs.getLong("committed_draft_revision", -1);
        SharedPreferences.Editor ed = prefs.edit();
        writeVarsAndAnsToEditor(ed);
        writeHistoryEntry(ed, history.size(), entry);
        ed.putInt("history_count", history.size() + 1);
        // A process stop before the editor clears the draft must not restore this input.
        ed.putLong("committed_draft_revision", submittedDraft);
        resultWritePending = true;
        RESULT_WRITER.execute(() -> {
            boolean saved = commitPreferences(ed);
            runOnUiThread(() -> {
                resultWritePending = false;
                if (saved) {
                    history.add(entry);
                    if (draftRevision == submittedDraft) {
                        clearInput();
                    } else {
                        saveDraft();
                        updatePreviewOnly();
                    }
                    refreshHistoryViews(true);
                } else {
                    engine.setLastAns(previousAns);
                    engine.setLastMatrixAns(previousMatrixAns);
                    for (String name : CalculatorEngine.USER_VARIABLES) {
                        double[][] prevMat = previousMatrixVariables.get(name);
                        CalculatorEngine.Value value = previousVariables.get(name);
                        if (prevMat != null) engine.setMatrixVariable(name, prevMat);
                        else if (value != null) engine.setUserVariable(name, value);
                        else engine.unsetVariable(name);
                    }
                    prefs.edit().putLong("committed_draft_revision", previousCommittedDraft).apply();
                    saveState();
                    saveDraft();
                    updatePreviewOnly();
                    Toast.makeText(this, "Rechnung konnte nicht gespeichert werden", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static boolean commitPreferences(SharedPreferences.Editor editor) {
        try {
            return editor.commit();
        } catch (RuntimeException e) {
            Log.e("Niob", "Unable to save calculator result", e);
            return false;
        }
    }

    private void saveState() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences("niob_calculator", MODE_PRIVATE).edit();
        ed.putBoolean("show_exact_first", showExactFirst);
        ed.putBoolean("show_more_variables", showMoreVariables);
        ed.putBoolean("use_comma_decimal", useCommaDecimal);
        ed.putInt("thousands_grouping", thousandsGrouping);
        ed.putBoolean("radians_mode", engine.isRadiansMode());
        ed.putBoolean("modulo_always_positive", engine.isModuloAlwaysPositive());
        writeVarsAndAnsToEditor(ed);
        writeHistoryToEditor(ed);
        ed.apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveDraft();
    }

    @Override
    protected void onDestroy() {
        previewGeneration.incrementAndGet();
        if (pendingPreview != null) {
            pendingPreview.cancel(true);
            pendingPreview = null;
        }
        previewExecutor.shutdownNow();
        evalExecutor.shutdownNow();
        super.onDestroy();
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
        return getIntegrationReportText(engine, expr);
    }

    private static String getIntegrationReportText(CalculatorEngine targetEngine, String expr) {
        if (expr != null && (expr.contains("int(") || expr.contains("defint("))) {
            CalculatorEngine.IntegrationReport r = targetEngine.getLastIntegrationReport();
            if (r != null) {
                if (!r.converged) {
                    return String.format(java.util.Locale.US, "nicht konvergiert  •  n = %d", r.subintervals);
                }
                return String.format(java.util.Locale.US, "konvergiert  •  Δ ≈ %.1e  •  n = %d", r.absErr, r.subintervals);
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
            history.add(new HistoryEntry(expr, ex, dec, result.reusableText, rep, false, result.isExact));
        } catch (Exception ignored) {
        }
    }

    private void rebuildScreen() {
        rootContainer.removeAllViews();
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        // 1. Top App Bar (only shown on Plotter and Einstellungen tabs; Konstanten uses clean card header like screenshot)
        if (activeTab == 1 || activeTab == 3) {
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setBackgroundColor(colSurfaceContainer);
            int padH = dp(16);
            int padV = dp(isLandscape ? 8 : 12);
            topBar.setPadding(padH, padV, padH, padV);

            TextView titleView = new TextView(this);
            String titleText = (activeTab == 1) ? "2D-Plotter" : "Einstellungen";
            titleView.setText(titleText);
            titleView.setTextColor(colOnSurface);
            setScaledTextSize(titleView, isLandscape ? 16f : 18f, 2.0f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            topBar.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (activeTab == 1) {
                titleView.setSingleLine(true);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                topBar.addView(buildAngleSwitches(dp(isLandscape ? 26 : 28), () -> {
                    finishPlotEditing();
                    invalidatePlot();
                }));
            }

            rootContainer.addView(topBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 2. Main Content Area (weight = 1)
        FrameLayout contentArea = new FrameLayout(this);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rootContainer.addView(contentArea, contentLp);

        plotView = null;
        if (activeTab == 0) {
            buildCalculatorTab(contentArea);
        } else if (activeTab == 1) {
            buildPlotterTab(contentArea);
        } else if (activeTab == 2) {
            buildConstantsTab(contentArea);
        } else {
            buildConverterTab(contentArea);
        }

        // 3. Bottom Material You Navigation Bar (Pure Icon Pill Indicators, No Emojis, No Words)
        LinearLayout bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setBackgroundColor(colSurfaceContainer);
        int navH = dp(isLandscape ? 44 : 52);
        bottomNav.setMinimumHeight(navH);
        bottomNav.setPadding(dp(8), dp(isLandscape ? 3 : 6), dp(8), dp(isLandscape ? 3 : 6));
        bottomNav.setGravity(Gravity.CENTER);

        float density = getResources().getDisplayMetrics().density;
        String[] navDescriptions = {"Rechner", "Plotter", "Konstanten", "Einstellungen"};
        for (int i = 0; i < 4; i++) {
            final int tabIdx = i;
            boolean sel = (activeTab == i);

            FrameLayout itemWrap = new FrameLayout(this);
            itemWrap.setContentDescription(navDescriptions[i]);
            itemWrap.setClickable(true);
            itemWrap.setFocusable(true);
            itemWrap.setMinimumHeight(dp(isLandscape ? 36 : 40));

            FrameLayout pill = new FrameLayout(this);
            pill.setBackground(roundedRect(sel ? colPrimaryContainer : Color.TRANSPARENT, 16));
            FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(dp(64), dp(32));
            pillLp.gravity = Gravity.CENTER;

            ImageView iconView = new ImageView(this);
            int iconColor = sel ? colOnPrimaryContainer : colOnSurfaceVariant;
            Drawable iconDrawable;
            if (i == 0) iconDrawable = new CalculatorNavDrawable(iconColor, density);
            else if (i == 1) iconDrawable = new PlotterNavDrawable(iconColor, density);
            else if (i == 2) iconDrawable = new ConstantsNavDrawable(iconColor, density);
            else iconDrawable = new SettingsNavDrawable(iconColor, sel ? colPrimaryContainer : colSurfaceContainer, density);

            iconView.setImageDrawable(iconDrawable);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            pill.addView(iconView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            itemWrap.addView(pill);

            GradientDrawable mask = roundedRect(Color.WHITE, 16);
            GradientDrawable content = roundedRect(Color.TRANSPARENT, 16);
            RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
            itemWrap.setBackground(ripple);

            itemWrap.setOnClickListener(v -> {
                if (resultWritePending) return;
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                activeTab = tabIdx;
                rebuildScreen();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            bottomNav.addView(itemWrap, lp);
        }
        rootContainer.addView(bottomNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, navH));
    }

    private Drawable outlinedPill(int strokeColor, float strokeWidthPx, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.TRANSPARENT);
        gd.setStroke(Math.round(strokeWidthPx), strokeColor);
        gd.setCornerRadius(dp(radiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), gd, mask);
    }

    private static class CalculatorNavDrawable extends Drawable {
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        CalculatorNavDrawable(int color, float density) {
            this.density = density;
            strokePaint.setColor(color);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.8f * density);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            fillPaint.setColor(color);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float w = 18f * density;
            float h = 22f * density;
            float left = cx - w / 2f;
            float top = cy - h / 2f;
            float right = left + w;
            float bottom = top + h;
            float r = 3.5f * density;

            RectF body = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(body, r, r, strokePaint);

            float sMargin = 3f * density;
            float sH = 4.5f * density;
            RectF screen = new RectF(left + sMargin, top + sMargin, right - sMargin, top + sMargin + sH);
            canvas.drawRoundRect(screen, 1.5f * density, 1.5f * density, strokePaint);

            float keyR = 1.1f * density;
            float ky1 = top + sMargin + sH + 3.5f * density;
            float ky2 = ky1 + 4f * density;
            float kx1 = left + sMargin + 1.5f * density;
            float kx2 = cx;
            float kx3 = right - sMargin - 1.5f * density;

            canvas.drawCircle(kx1, ky1, keyR, fillPaint);
            canvas.drawCircle(kx2, ky1, keyR, fillPaint);
            canvas.drawCircle(kx3, ky1, keyR, fillPaint);
            canvas.drawCircle(kx1, ky2, keyR, fillPaint);
            canvas.drawCircle(kx2, ky2, keyR, fillPaint);
            canvas.drawCircle(kx3, ky2, keyR, fillPaint);
        }

        @Override public void setAlpha(int alpha) { strokePaint.setAlpha(alpha); fillPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { strokePaint.setColorFilter(cf); fillPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static class PlotterNavDrawable extends Drawable {
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;

        PlotterNavDrawable(int color, float density) {
            this.density = density;
            axisPaint.setColor(color);
            axisPaint.setStyle(Paint.Style.STROKE);
            axisPaint.setStrokeWidth(1.6f * density);
            axisPaint.setStrokeCap(Paint.Cap.ROUND);

            curvePaint.setColor(color);
            curvePaint.setStyle(Paint.Style.STROKE);
            curvePaint.setStrokeWidth(2.0f * density);
            curvePaint.setStrokeCap(Paint.Cap.ROUND);
            curvePaint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float w = 22f * density;
            float h = 20f * density;
            float left = cx - w / 2f;
            float bottom = cy + h / 2f;
            float top = cy - h / 2f;
            float right = cx + w / 2f;

            canvas.drawLine(left + 2f * density, top, left + 2f * density, bottom, axisPaint);
            canvas.drawLine(left + 2f * density, bottom, right, bottom, axisPaint);

            path.reset();
            path.moveTo(left + 4f * density, bottom - 4f * density);
            path.cubicTo(
                    left + 8f * density, bottom - 18f * density,
                    left + 14f * density, bottom + 2f * density,
                    right - 1f * density, top + 2f * density
            );
            canvas.drawPath(path, curvePaint);
        }

        @Override public void setAlpha(int alpha) { axisPaint.setAlpha(alpha); curvePaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { axisPaint.setColorFilter(cf); curvePaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static class ConstantsNavDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        ConstantsNavDrawable(int color, float density) {
            this.density = density;
            paint.setColor(color);
            paint.setTypeface(Typeface.create("serif", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            paint.setTextSize(20f * density);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float y = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("π", cx, y, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static class SettingsNavDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        SettingsNavDrawable(int color, int bgColor, float density) {
            this.density = density;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            holePaint.setColor(bgColor);
            holePaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float rOuter = 8.5f * density;
            float rInner = 4.0f * density;
            float toothW = 3.2f * density;
            float toothH = 11f * density;

            for (int i = 0; i < 4; i++) {
                canvas.save();
                canvas.rotate(i * 45f, cx, cy);
                RectF tooth = new RectF(cx - toothW / 2f, cy - toothH, cx + toothW / 2f, cy + toothH);
                canvas.drawRoundRect(tooth, 1f * density, 1f * density, paint);
                canvas.restore();
            }
            canvas.drawCircle(cx, cy, rOuter, paint);
            canvas.drawCircle(cx, cy, rInner, holePaint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); holePaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); holePaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

private static class FilledBackspaceDrawable extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;

        FilledBackspaceDrawable(int fillColor, int xColor, float density) {
            this.density = density;
            fillPaint.setColor(fillColor);
            fillPaint.setStyle(Paint.Style.FILL);
            xPaint.setColor(xColor);
            xPaint.setStyle(Paint.Style.STROKE);
            xPaint.setStrokeCap(Paint.Cap.ROUND);
            xPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            // Solid filled backspace shape matching Vorlage
            float iconW = 27f * density;
            float iconH = 18f * density;
            float left = b.exactCenterX() - iconW / 2f;
            float top = b.exactCenterY() - iconH / 2f;
            float right = left + iconW;
            float bottom = top + iconH;

            float tipX = left;
            float tipY = top + iconH / 2f;
            float shoulderX = left + iconH * 0.46f;
            float r = 2.5f * density;

            path.reset();
            path.moveTo(right - r, top);
            path.quadTo(right, top, right, top + r);
            path.lineTo(right, bottom - r);
            path.quadTo(right, bottom, right - r, bottom);
            path.lineTo(shoulderX, bottom);
            path.lineTo(tipX, tipY);
            path.lineTo(shoulderX, top);
            path.close();

            canvas.drawPath(path, fillPaint);

            // Draw dark 'X' cutout centered in rectangular body
            xPaint.setStrokeWidth(1.9f * density);
            float cx = shoulderX + (right - shoulderX) / 2f;
            float cy = tipY;
            float d = 3.2f * density;
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, xPaint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, xPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            xPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fillPaint.setColorFilter(cf);
            xPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private LinearLayout buildNumpadCard() {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(roundedTopRect(colSurfaceContainer, 28));
        card.setPadding(dp(8), dp(isLandscape ? 4 : 10), dp(8), dp(isLandscape ? 4 : 8));

        // Digits part (3 columns x 4 rows)
        LinearLayout digitsCol = new LinearLayout(this);
        digitsCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams digLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f);
        card.addView(digitsCol, digLp);

        final String decLabel = useCommaDecimal ? "," : ".";
        String[][] digRows = {
                {"7", "8", "9"},
                {"4", "5", "6"},
                {"1", "2", "3"},
                {"0", decLabel, "ENTER"}
        };

        for (int r = 0; r < digRows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < digRows[r].length; c++) {
                final String text = digRows[r][c];
                final String tok = text.equals("ENTER") ? "=" : text;
                boolean isEnter = text.equals("ENTER");
                int fg = isEnter ? colPrimary : colOnSurface;
                float spSize = isEnter ? 14f : 24f;

                Button b = makeMaterialButton(text, Color.TRANSPARENT, fg, 16, spSize, 1.4f, isEnter);
                b.setSingleLine(true);
                b.setIncludeFontPadding(false);
                b.setPadding(0, 0, 0, 0);
                b.setAutoSizeTextTypeUniformWithConfiguration(
                        8, Math.round(spSize * 1.3f), 1, TypedValue.COMPLEX_UNIT_DIP);
                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    handleKeyPress(tok);
                });
                if (text.equals(decLabel)) {
                    b.setOnLongClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        handleKeyPress(", ");
                        return true;
                    });
                }

                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                blp.setMargins(dp(2), dp(2), dp(2), dp(2));
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
                {"\u00f7", "÷"},
                {"\u00d7", "×"},
                {"+", "+"},
                {"\u2212", "−"}
        };

        for (int i = 0; i < opDefs.length; i++) {
            final String[] op = opDefs[i];
            View btnView;
            if (i == 0) {
                // Filled Backspace Icon Button
                ImageButton ib = new ImageButton(this);
                ib.setImageDrawable(new FilledBackspaceDrawable(colOnSurface, colSurfaceContainer, getResources().getDisplayMetrics().density));
                GradientDrawable mask = roundedRect(Color.WHITE, 16);
                GradientDrawable content = roundedRect(Color.TRANSPARENT, 16);
                RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
                ib.setBackground(ripple);
                ib.setScaleType(ImageView.ScaleType.CENTER);
                ib.setContentDescription("\u232b");
                ib.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    handleKeyPress("DEL");
                });
                ib.setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    clearInput();
                    return true;
                });
                btnView = ib;
            } else {
                Button b = makeMaterialButton(op[0], Color.TRANSPARENT, colOnSurface, 16, 22f, 1.4f, false);
                b.setSingleLine(true);
                b.setPadding(dp(2), dp(1), dp(2), dp(1));
                b.setAutoSizeTextTypeUniformWithConfiguration(
                        8, Math.round(22f * 1.3f), 1, TypedValue.COMPLEX_UNIT_DIP);
                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    handleKeyPress(op[1]);
                });
                btnView = b;
            }

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            blp.setMargins(dp(2), dp(2), dp(2), dp(2));
            opsCol.addView(btnView, blp);
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
        historyListContainer.setPadding(dp(12), dp(14), dp(12), dp(8));
        historyScroll.addView(historyListContainer);
        historyScroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int oldH = oldBottom - oldTop;
            int newH = bottom - top;
            int heightDiff = oldH - newH;
            if (oldH > 0 && heightDiff > 0) {
                historyScroll.scrollBy(0, heightDiff);
            }
        });
        refreshHistoryViews();

        // 2. Active Expression & Input Row (Pitch black bar with blue cursor '|')
        LinearLayout inputCard = new LinearLayout(this);
        inputCard.setOrientation(LinearLayout.VERTICAL);
        inputCard.setBackgroundColor(0xFF000000);
        inputCard.setPadding(dp(14), dp(6), dp(14), dp(6));

        if (formula.getParent() != null) {
            ((ViewGroup) formula.getParent()).removeView(formula);
        }
        inputCard.addView(formula, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        formula.show(isLandscape ? 20f : 25f);

        previewDisplayView = new TextView(this);
        previewDisplayView.setContentDescription("preview_result");
        setScaledTextSize(previewDisplayView, isLandscape ? 12.5f : 14f, 2.0f);
        previewDisplayView.setTextColor(colPrimary);
        previewDisplayView.setTypeface(Typeface.DEFAULT_BOLD);
        previewDisplayView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        previewDisplayView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        previewDisplayView.setSingleLine(true);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prevLp.topMargin = dp(2);
        inputCard.addView(previewDisplayView, prevLp);
        previewMatrix = new FrameLayout(this);
        previewMatrix.setContentDescription("Matrixergebnis");
        previewMatrix.setVisibility(View.GONE);
        inputCard.addView(previewMatrix, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

            // Give the scientific rows room for the angle switches in a short window.
            LinearLayout.LayoutParams upperLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.54f);
            kbContainer.addView(upperKb, upperLp);
            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.46f);
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

            LinearLayout topPane = new LinearLayout(this);
            topPane.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            topPane.addView(historyScroll, histLp);

            LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            topPane.addView(inputCard, inLp);

            LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f);
            col.addView(topPane, topLp);

            LinearLayout.LayoutParams upperLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f);
            kbContainer.addView(upperKb, upperLp);

            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.55f);
            kbContainer.addView(numpadCard, numLp);

            LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f);
            col.addView(kbContainer, kbLp);

            parent.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        updatePreviewOnly();
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
        String pretty = sb.toString().replaceAll(" {2,}", " ").trim();
        if (useCommaDecimal) {
            pretty = pretty.replaceAll("(\\d)\\.(\\d)", "$1,$2");
        } else {
            pretty = FormulaEditor.localizeDecimalLiterals(pretty, false);
        }
        return pretty;
    }

    private void insertHistoryValue(String rawVal) {
        if (rawVal == null || rawVal.isEmpty()) return;
        if (formula.insertExactFraction(rawVal) || formula.insertMatrixResult(rawVal)) return;
        EditText activeEt = formula.activeField();
        int start = 0;
        String curText = "";
        if (activeEt != null) {
            start = Math.max(0, activeEt.getSelectionStart());
            curText = activeEt.getText() != null ? activeEt.getText().toString() : "";
        }
        String beforeCursor = (start >= 0 && start <= curText.length())
                ? curText.substring(0, start).trim()
                : curText.trim();
        boolean precededByOp = beforeCursor.endsWith("/") || beforeCursor.endsWith("*")
                || beforeCursor.endsWith("+") || beforeCursor.endsWith("-")
                || beforeCursor.endsWith("^") || beforeCursor.endsWith("÷")
                || beforeCursor.endsWith("×") || beforeCursor.endsWith("−");
        boolean isComposite = rawVal.contains("/") || rawVal.contains("÷")
                || rawVal.contains("+") || rawVal.contains("-") || rawVal.contains("−")
                || rawVal.contains(" ");
        String valToInsert = useCommaDecimal ? rawVal.replaceAll("(\\d)\\.(\\d)", "$1,$2") : rawVal;
        String toInsert;
        if (isComposite && (precededByOp || (!beforeCursor.isEmpty() && !beforeCursor.endsWith("(")))) {
            toInsert = "(" + valToInsert + ")";
        } else {
            toInsert = valToInsert;
        }
        insertAtCursor(toInsert);
    }

    private void refreshHistoryViews() {
        refreshHistoryViews(true);
    }

    private void refreshHistoryViews(boolean scrollToBottom) {
        if (historyListContainer == null) return;
        historyListContainer.removeAllViews();
        for (int i = 0; i < history.size(); i++) {
            final HistoryEntry item = history.get(i);
            LinearLayout itemBox = new LinearLayout(this);
            itemBox.setOrientation(LinearLayout.VERTICAL);
            itemBox.setBackgroundColor(Color.TRANSPARENT);
            itemBox.setPadding(dp(6), dp(8), dp(6), dp(8));

            // Row 1: Expression (vertical fraction if simple fraction a/b or composite fraction (a)/(b))
            String cleanExpr = item.expr.replace(" ", "").replace("÷", "/").replace("−", "-").replace("×", "*");
            boolean isFraction = false;
            String fNum = null;
            String fDen = null;
            if (cleanExpr.matches("^-?\\d+/\\d+$")) {
                String[] parts = cleanExpr.split("/");
                fNum = parts[0];
                fDen = parts[1];
                isFraction = true;
            } else if (cleanExpr.matches("^\\(?([a-zA-Z0-9_+\\-*·]+)\\)?/\\(?([a-zA-Z0-9_+\\-*·]+)\\)?$")) {
                int slashIdx = cleanExpr.lastIndexOf('/');
                String p1 = cleanExpr.substring(0, slashIdx);
                String p2 = cleanExpr.substring(slashIdx + 1);
                if (p1.startsWith("(") && p1.endsWith(")")) p1 = p1.substring(1, p1.length() - 1);
                if (p2.startsWith("(") && p2.endsWith(")")) p2 = p2.substring(1, p2.length() - 1);
                fNum = p1;
                fDen = p2;
                isFraction = true;
            }

            Runnable reuseExpression = () -> {
                if (!formula.restoreHistory(item.formulaLayout)) insertHistoryValue(item.expr);
            };
            View savedFormula = formula.historyView(item.formulaLayout, 19f, reuseExpression);
            if (savedFormula == null) savedFormula = formula.matrixResult(item.expr, 15f, reuseExpression);
            if (savedFormula != null) {
                itemBox.addView(savedFormula, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (isFraction && fNum != null && fDen != null) {
                View fracView = createFractionView(fNum, fDen);
                fracView.setOnClickListener(v -> {
                    insertHistoryValue(item.expr);
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
                    insertHistoryValue(item.expr);
                });
                LinearLayout.LayoutParams expLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                expLp.bottomMargin = dp(4);
                itemBox.addView(exprTv, expLp);
            }

            // Result display toggle and right-aligned value.
            LinearLayout resRow = new LinearLayout(this);
            resRow.setOrientation(LinearLayout.HORIZONTAL);
            resRow.setGravity(Gravity.CENTER_VERTICAL);

            String rawExact = item.exactText != null ? item.exactText : "";
            String rawDecimal = item.decimalText != null ? item.decimalText : "";
            String cleanExact = rawExact.replace(" ", "");
            boolean isExactFrac = item.hasExact && cleanExact.matches("^-?\\d+/\\d+$");
            boolean isMatrix = rawExact.contains("[[") || rawDecimal.contains("[[");
            String exactShown = item.hasExact ? formatExactDisplayNumber(rawExact) : "";
            String decimalShown = formatDisplayNumber(rawDecimal);
            boolean canToggle = item.hasExact && !isMatrix && (isExactFrac
                    || !exactShown.replace("\u200B", "").equals(decimalShown.replace("\u200B", "")));
            boolean showExactNow = canToggle && item.showingExact;

            if (canToggle) {
                Button exactBadge = makeMaterialButton(
                        showExactNow ? "exakt" : "dezimal",
                        colPrimary,
                        colOnPrimary,
                        14,
                        12.5f,
                        1.4f,
                        true
                );
                exactBadge.setSingleLine(true);
                exactBadge.setIncludeFontPadding(false);
                exactBadge.setMinimumWidth(dp(60));
                exactBadge.setMinimumHeight(dp(28));
                exactBadge.setPadding(dp(9), dp(3), dp(9), dp(3));
                exactBadge.setOnClickListener(v -> {
                    if (resultWritePending) return;
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    item.showingExact = !item.showingExact;
                    saveState();
                    refreshHistoryViews(false);
                });
                resRow.addView(exactBadge, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            TextView eqTv = new TextView(this);
            eqTv.setText(canToggle ? "  =  " : "=  ");
            eqTv.setTextColor(colPrimary);
            setScaledTextSize(eqTv, 18f, 2.0f);
            eqTv.setTypeface(Typeface.DEFAULT_BOLD);
            resRow.addView(eqTv);

            boolean useExact = canToggle ? item.showingExact : item.hasExact;
            String rawShown = useExact ? rawExact : rawDecimal;
            String reuseVal = (item.reusableText != null && !item.reusableText.isEmpty()) ? item.reusableText : rawExact;
            boolean isFractionResult = showExactNow && isExactFrac;

            View matrixResult = formula.matrixResult(rawShown, 15f, () -> insertHistoryValue(reuseVal));
            if (matrixResult != null) {
                View resSpacer = new View(this);
                resRow.addView(resSpacer, new LinearLayout.LayoutParams(0, 0, 1f));
                resRow.addView(matrixResult);
            } else if (isFractionResult) {
                View resSpacer = new View(this);
                resRow.addView(resSpacer, new LinearLayout.LayoutParams(0, 0, 1f));
                String[] fracParts = cleanExact.split("/");
                View fracResultView = createFractionView(fracParts[0].trim(), fracParts[1].trim(), 20f);
                fracResultView.setOnClickListener(v -> insertHistoryValue(reuseVal));
                LinearLayout.LayoutParams fracLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fracLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                resRow.addView(fracResultView, fracLp);
            } else {
                TextView resTv = new TextView(this);
                String shown = useExact ? exactShown : decimalShown;
                resTv.setText(shown);
                resTv.setGravity(Gravity.END);
                resTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
                resTv.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
                resTv.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
                resTv.setTextColor(colOnSurface);
                setScaledTextSize(resTv, 23f, 2.0f);
                resTv.setTypeface(Typeface.DEFAULT_BOLD);
                resTv.setOnClickListener(v -> insertHistoryValue(reuseVal));
                resRow.addView(resTv, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }

            itemBox.addView(resRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (item.reportText != null && !item.reportText.isEmpty()) {
                TextView repTv = new TextView(this);
                repTv.setText(item.reportText);
                repTv.setTextColor(colOnSurfaceVariant);
                setScaledTextSize(repTv, 11f, 1.8f);
                repTv.setPadding(0, dp(2), 0, 0);
                itemBox.addView(repTv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            // Subtle divider only between items (never after the last item)
            if (i < history.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0x18FFFFFF);
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(0.8f));
                divLp.topMargin = dp(6);
                itemBox.addView(divider, divLp);
            }

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(8);
            historyListContainer.addView(itemBox, rLp);
        }
        if (scrollToBottom && historyScrollView != null) {
            historyScrollView.post(() -> historyScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void insertAtCursor(String text) {
        formula.insert(text);
    }

    private void clearInput() {
        if (!resultWritePending) formula.clear();
    }

    private void updatePreviewOnly() {
        if (previewDisplayView == null || resultWritePending) return;
        if (formula.hasMissingField()) {
            previewGeneration.incrementAndGet();
            if (pendingPreview != null) {
                pendingPreview.cancel(true);
                pendingPreview = null;
            }
            previewDisplayView.setVisibility(View.VISIBLE);
            previewMatrix.removeAllViews();
            previewMatrix.setVisibility(View.GONE);
            previewDisplayView.setText("");
            return;
        }
        final String cur = formula.expression().trim();
        if (cur.isEmpty()) {
            previewGeneration.incrementAndGet();
            if (pendingPreview != null) {
                pendingPreview.cancel(true);
                pendingPreview = null;
            }
            previewDisplayView.setVisibility(View.VISIBLE);
            previewMatrix.removeAllViews();
            previewMatrix.setVisibility(View.GONE);
            String ansStr = engine.formatAns(false);
            if (ansStr != null && !ansStr.isEmpty() && !ansStr.equals("0")) {
                previewDisplayView.setText("ANS = " + formatDisplayNumber(ansStr));
            } else {
                previewDisplayView.setText("ANS = 0");
            }
            previewDisplayView.setTextColor(colPrimary);
            return;
        }

        final long gen = previewGeneration.incrementAndGet();
        if (pendingPreview != null) {
            pendingPreview.cancel(true);
        }
        final CalculatorEngine snapshot = new CalculatorEngine(engine);
        pendingPreview = previewExecutor.submit(() -> {
            try {
                final CalculatorEngine.Result result = snapshot.evaluateWithTimeout(cur, 350);
                runOnUiThread(() -> {
                    if (gen != previewGeneration.get() || previewDisplayView == null
                            || resultWritePending || isFinishing() || isDestroyed()) {
                        return;
                    }
                    previewDisplayView.setVisibility(View.VISIBLE);
                    previewMatrix.removeAllViews();
                    previewMatrix.setVisibility(View.GONE);
                    View matrix = formula.matrixResult(result.decimalText, 13f, null);
                    if (matrix != null) {
                        previewDisplayView.setVisibility(View.GONE);
                        previewMatrix.setVisibility(View.VISIBLE);
                        FrameLayout.LayoutParams matrixParams = new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END);
                        previewMatrix.addView(matrix, matrixParams);
                        return;
                    }
                    String dec = formatDisplayNumber(result.decimalText);
                    String ex = result.isExact ? formatExactDisplayNumber(result.exactText).replace("\u200B", "") : "";
                    if (result.isExact && !ex.isEmpty() && ex.length() <= 20 && !ex.equals(dec)) {
                        previewDisplayView.setText("= " + dec + "  (" + ex + ")");
                    } else {
                        previewDisplayView.setText("= " + dec);
                    }
                    previewDisplayView.setTextColor(colPrimary);
                });
            } catch (CancellationException ignored) {
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "";
                runOnUiThread(() -> {
                    if (gen != previewGeneration.get() || previewDisplayView == null
                            || resultWritePending || isFinishing() || isDestroyed()) {
                        return;
                    }
                    showPreviewError(msg, cur, false);
                });
            }
        });
    }

    private static String classifyEvalError(String msg, String cur, boolean submitted) {
        if (msg.contains("Division durch Null") || msg.contains("Division by zero") || msg.contains("Modulo durch Null")) {
            return "⚠ Division durch Null";
        }
        if (msg.contains("Wert zu groß") || msg.contains("Überlauf") || msg.contains("übersteigt Rechenbereich")
                || msg.contains("zu groß") || msg.contains("overflow") || msg.contains("Overflow")) {
            return "⚠ Wert zu groß";
        }
        if (msg.contains("Zeitlimit") || msg.contains("Zeitüberschreitung") || msg.contains("Time limit")) {
            return submitted ? "⚠ Zeitlimit überschritten" : "…";
        }
        if (msg.contains("Fakultät ist nur")) {
            return "⚠ Fakultät: nur ganze Zahlen ≥ 0";
        }
        if (msg.contains("nicht definiert") || msg.contains("singulär")
                || msg.contains("differenzierbar") || msg.contains("divergiert")) {
            return "⚠ Nicht definiert";
        }
        if (!submitted && cur != null) {
            boolean isIncomplete = cur.endsWith("+") || cur.endsWith("-") || cur.endsWith("*")
                    || cur.endsWith("/") || cur.endsWith("÷") || cur.endsWith("×") || cur.endsWith("−")
                    || cur.endsWith("^") || cur.endsWith("(") || cur.endsWith(",") || cur.endsWith(":=");
            if (isIncomplete) {
                return "";
            }
        }
        return "⚠ Ungültiger Ausdruck";
    }

    private void showPreviewError(String msg, String cur, boolean submitted) {
        if (previewDisplayView == null) return;
        previewDisplayView.setVisibility(View.VISIBLE);
        previewMatrix.removeAllViews();
        previewMatrix.setVisibility(View.GONE);
        String text = classifyEvalError(msg != null ? msg : "", cur, submitted);
        previewDisplayView.setText(text);
        boolean highlightError = text.startsWith("⚠ ") && !text.equals("⚠ Ungültiger Ausdruck");
        previewDisplayView.setTextColor(highlightError ? 0xFFFFB4AB : colOnSurfaceVariant);
    }

    private static final class KeyDef {
        final String label;
        final String token;
        final MathKeyDrawable.Symbol symbol;

        KeyDef(String label, String token, MathKeyDrawable.Symbol symbol) {
            this.label = label;
            this.token = token;
            this.symbol = symbol;
        }
    }

    private static KeyDef k(String label, String token) {
        return new KeyDef(label, token, null);
    }

    private static KeyDef k(String same) {
        return k(same, same);
    }

    private static KeyDef icon(String label, String token, MathKeyDrawable.Symbol symbol) {
        return new KeyDef(label, token, symbol);
    }

    private static final class ScientificKeyGrid extends LinearLayout {
        private final List<Button> textKeys = new ArrayList<>();
        private final TextPaint measurement = new TextPaint();
        private final float preferredSize;

        ScientificKeyGrid(Context context, float preferredSize) {
            super(context);
            setOrientation(VERTICAL);
            this.preferredSize = preferredSize;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            float commonSize = preferredSize;
            for (Button key : textKeys) {
                measurement.set(key.getPaint());
                measurement.setTextSize(preferredSize);
                float textWidth = Layout.getDesiredWidth(key.getText(), measurement);
                float textHeight = measurement.descent() - measurement.ascent();
                int availableWidth = key.getMeasuredWidth() - key.getPaddingLeft() - key.getPaddingRight();
                int availableHeight = key.getMeasuredHeight() - key.getPaddingTop() - key.getPaddingBottom();
                if (textWidth > 0) commonSize = Math.min(commonSize, preferredSize * availableWidth / textWidth);
                if (textHeight > 0) commonSize = Math.min(commonSize, preferredSize * availableHeight / textHeight);
            }
            int size = Math.max(1, (int) Math.floor(commonSize));
            boolean changed = false;
            for (Button key : textKeys) {
                if (key.getTextSize() != size) {
                    key.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                    changed = true;
                }
            }
            if (changed) super.onMeasure(widthSpec, heightSpec);
        }
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
                    1.4f,
                    sel
            );
            b.setSingleLine(true);
            b.setPadding(dp(4), dp(2), dp(4), dp(2));
            b.setMinimumHeight(dp(isLandscape ? 28 : 34));
            b.setAutoSizeTextTypeUniformWithConfiguration(
                    8, Math.round(13.5f * 1.4f), 1, TypedValue.COMPLEX_UNIT_DIP);
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
        float keyTextSize = isLandscape ? 14.5f : 16f;
        float fontScale = Math.min(getResources().getConfiguration().fontScale, 1.4f);
        ScientificKeyGrid grid = new ScientificKeyGrid(this, dp(keyTextSize * fontScale));

        KeyDef[][][] sections = {
                // 0: ( ) √ ^
                {
                        {k("("), k(")"), k("π", "pi"), k("e")},
                        {k("x²", "TEMPLATE_SQUARE"), k("xⁿ", "TEMPLATE_POWER"),
                                icon("Quadratwurzel", "sqrt(", MathKeyDrawable.Symbol.ROOT),
                                icon("Wurzel mit Index", "nthroot(", MathKeyDrawable.Symbol.INDEXED_ROOT)},
                        {k("ln", "ln("), k("logₐ(x)", "log("), k("10ⁿ", "TEMPLATE_POWER_10"), k("ans")}
                },
                // 1: sin
                {
                        {k("sin", "sin("), k("asin", "asin("), k("sinh", "sinh("), k("asinh", "asinh(")},
                        {k("cos", "cos("), k("acos", "acos("), k("cosh", "cosh("), k("acosh", "acosh(")},
                        {k("tan", "tan("), k("atan", "atan("), k("tanh", "tanh("), k("atanh", "atanh(")}
                },
                // 2: ∑ ∫ i
                {
                        {k("∑", "sum("), k("∏", "prod("),
                                icon("Binomialkoeffizient", "binomial(", MathKeyDrawable.Symbol.BINOMIAL), k("n!", "!")},
                        {k("∫", "int("), icon("Ableitung", "deriv(", MathKeyDrawable.Symbol.DERIVATIVE),
                                k("mod", "mod("), k("i")},
                        {icon("Matrix", "SHOW_MATRIX_DIALOG", MathKeyDrawable.Symbol.MATRIX),
                                icon("Inverse Matrix", "inv(", MathKeyDrawable.Symbol.INVERSE),
                                icon("Transponierte Matrix", "trans(", MathKeyDrawable.Symbol.TRANSPOSE), k("det", "det(")}
                },
                // 3: a b c
                showMoreVariables ?
                new KeyDef[][]{
                        {k("a"), k("b"), k("c"), k("m")},
                        {k("k"), k("l"), k("n"), k(":=")},
                        {k("x"), k("y"), k("z"), k("[a..z]", "TOGGLE_VAR_MORE")}
                } :
                new KeyDef[][]{
                        {k("%"), k("x"), k("y"), k("n")},
                        {k("c₀", "c0"), k("G", "G"), k("g"), k("h")},
                        {k("e⁻", "e_charge"), k("μ₀", "mu0"), k("N_A"), k("[a..z]", "TOGGLE_VAR_MORE")}
                },
                // 4: mehr
                {
                        {k("|x|", "abs("), k("⌊x⌋", "floor("), k("⌈x⌉", "ceil("), k("runden", "round(")},
                        {k("ggT", "gcd("), k("kgV", "lcm("), k("sgn", "sign(")}
                }
        };

        if (activeSection == 1) {
            grid.addView(buildAngleBar(isLandscape), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        KeyDef[][] rows = sections[activeSection];
        if (rows.length < 3) {
            grid.setWeightSum(3f);
            grid.setGravity(Gravity.CENTER_VERTICAL);
        }
        for (KeyDef[] row : rows) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setWeightSum(4f);
            rowLayout.setGravity(Gravity.CENTER_HORIZONTAL);
            int missingMargins = (4 - row.length) * dp(2);
            rowLayout.setPadding(missingMargins, 0, missingMargins, 0);
            for (KeyDef definition : row) {
                View key = makeScientificKey(definition, keyTextSize);
                if (key instanceof Button) grid.textKeys.add((Button) key);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                params.setMargins(dp(2), dp(2), dp(2), dp(2));
                rowLayout.addView(key, params);
            }
            grid.addView(rowLayout, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        keyboardBodyContainer.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout buildAngleBar(boolean landscape) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(0, 0, 0, dp(2));
        int height = dp(landscape ? 26 : 28);
        bar.addView(makeScientificKey(k("("), 15f), new LinearLayout.LayoutParams(dp(36), height));

        LinearLayout.LayoutParams switches = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switches.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(buildAngleSwitches(height, this::updatePreviewOnly), switches);
        bar.addView(makeScientificKey(k(")"), 15f), new LinearLayout.LayoutParams(dp(36), height));
        return bar;
    }

    private LinearLayout buildAngleSwitches(int height, Runnable modeChanged) {
        LinearLayout switches = new LinearLayout(this);
        switches.setGravity(Gravity.CENTER_VERTICAL);
        Button rad = makeAngleButton("RAD");
        Button deg = makeAngleButton("DEG");
        LinearLayout.LayoutParams radParams = new LinearLayout.LayoutParams(dp(58), height);
        radParams.rightMargin = dp(12);
        switches.addView(rad, radParams);
        switches.addView(deg, new LinearLayout.LayoutParams(dp(58), height));
        styleAngleSwitches(rad, deg);
        rad.setOnClickListener(v -> changeAngleMode(true, rad, deg, modeChanged));
        deg.setOnClickListener(v -> changeAngleMode(false, rad, deg, modeChanged));
        return switches;
    }

    private Button makeAngleButton(String label) {
        Button button = makeMaterialButton(label, Color.TRANSPARENT, colOnSurface, 14, 12f, 1.3f, true);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void styleAngleSwitches(Button rad, Button deg) {
        boolean radians = engine.isRadiansMode();
        styleAngleButton(rad, radians);
        styleAngleButton(deg, !radians);
    }

    private void styleAngleButton(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? colOnPrimary : colOnSurface);
        button.setBackground(selected ? materialRipple(colPrimary, 14) : outlinedPill(colOnSurfaceVariant, dp(1), 14));
    }

    private void changeAngleMode(boolean radians, Button rad, Button deg, Runnable modeChanged) {
        (radians ? rad : deg).performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (engine.isRadiansMode() == radians) return;
        engine.setRadiansMode(radians);
        styleAngleSwitches(rad, deg);
        saveState();
        modeChanged.run();
    }

    private View makeScientificKey(KeyDef key, float textSize) {
        View view;
        if (key.symbol != null) {
            ImageButton button = new ImageButton(this);
            button.setContentDescription(key.label);
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setPadding(dp(2), dp(2), dp(2), dp(2));
            button.setBackground(materialRipple(Color.TRANSPARENT, 14));
            button.setImageDrawable(new MathKeyDrawable(key.symbol, dp(34), colOnSurface));
            view = button;
        } else {
            int color = key.token.equals("TOGGLE_VAR_MORE") ? colPrimary : colOnSurface;
            Button button = makeMaterialButton(key.label, Color.TRANSPARENT, color, 14, textSize, 1.4f);
            button.setSingleLine(true);
            button.setIncludeFontPadding(false);
            button.setPadding(dp(2), dp(2), dp(2), dp(2));
            if (key.label.contains("_")) {
                button.setText(formatSubscriptSymbol(key.label));
                button.setContentDescription(key.label);
            }
            view = button;
        }
        view.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            handleKeyPress(key.token);
        });
        return view;
    }

    private static final String[] MULTI_CHAR_TOKENS = {
            " ÷ ", " × ", " + ", " − ", ", ",
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

    private void handleOperatorPress(String op) {
        EditText activeEt = formula.activeField();
        if (activeEt == null) return;
        int start = Math.max(0, activeEt.getSelectionStart());
        int end = Math.max(0, activeEt.getSelectionEnd());
        if (start > end) { int tmp = start; start = end; end = tmp; }
        Editable editable = activeEt.getText();
        if (editable == null) return;

        if (start != end) {
            insertAtCursor(" " + op + " ");
            return;
        }

        String cur = editable.toString();
        String before = cur.substring(0, start);

        // If field is empty and op is minus or plus, insert unary sign (e.g. -3 or +3)
        if (before.trim().isEmpty() && !formula.hasTemplateBeforeCaret() && (op.equals("−") || op.equals("+") || op.equals("-"))) {
            String sign = op.equals("+") ? "+" : "−";
            insertAtCursor(sign);
            return;
        }

        String trimmedBefore = before.replaceAll("\\s+$", "");

        // Scientific exponent sign check: e.g. "1e", "2.5e" or "2,5e"
        if (trimmedBefore.matches(".*\\d+([.,]\\d+)?[eE]$") && (op.equals("−") || op.equals("+") || op.equals("-"))) {
            // Exponent sign: NO SPACES!
            String expSign = op.equals("+") ? "+" : "−";
            insertAtCursor(expSign);
            return;
        }
        if (trimmedBefore.matches(".*\\d+([.,]\\d+)?[eE][−\\-\\+]$") && (op.equals("−") || op.equals("+") || op.equals("-"))) {
            // Replace previous exponent sign
            String expSign = op.equals("+") ? "+" : "−";
            int signPos = trimmedBefore.length() - 1;
            formula.replace(signPos, start, expSign);
            return;
        }

        // If preceding text ends with an operator: " + ", " − ", " × ", " ÷ ", "+", "-", "*", "/"
        if (trimmedBefore.endsWith("+") || trimmedBefore.endsWith("−") || trimmedBefore.endsWith("-")
                || trimmedBefore.endsWith("×") || trimmedBefore.endsWith("*")
                || trimmedBefore.endsWith("÷") || trimmedBefore.endsWith("/")) {
            // Unary minus after an operator (e.g. 5 × −3)
            if (op.equals("−") && !trimmedBefore.endsWith("−") && !trimmedBefore.endsWith("-")) {
                insertAtCursor("−");
                return;
            }
            int opStart = trimmedBefore.length() - 1;
            while (opStart > 0 && cur.charAt(opStart - 1) == ' ') opStart--;
            formula.replace(opStart, start, " " + op + " ");
            return;
        }

        insertAtCursor(" " + op + " ");
    }

    private void showMatrixDialog(SpecialInput operation) {
        String[] sizes = {"2 × 2", "3 × 3", "2 × 2 Einheitsmatrix",
                "3 × 3 Einheitsmatrix", "2 × 2 Beispielmatrix", "Eigene Größe …"};
        String title = operation == SpecialInput.DET ? "Determinante"
                : operation == SpecialInput.INVERSE ? "Inverse Matrix"
                : operation == SpecialInput.TRANSPOSE ? "Transponierte Matrix" : "Matrix einfügen";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(sizes, (dialog, choice) -> {
                    if (choice == sizes.length - 1) {
                        showMatrixSize(operation);
                        return;
                    }
                    int size = choice == 1 || choice == 3 ? 3 : 2;
                    List<String> cells = new ArrayList<>();
                    if (choice == 2 || choice == 3) {
                        for (int i = 0; i < size * size; i++) {
                            cells.add(i / size == i % size ? "1" : "0");
                        }
                    } else if (choice == 4) {
                        cells.addAll(java.util.Arrays.asList("1", "2", "3", "4"));
                    }
                    formula.matrix(size, size, cells, operation);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void showMatrixSize(SpecialInput operation) {
        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.VERTICAL);
        inputs.setPadding(dp(24), 0, dp(24), dp(8));
        EditText rows = matrixDimension(inputs, "Zeilen");
        EditText columns = matrixDimension(inputs, "Spalten");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Matrixgröße")
                .setView(inputs)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Einfügen", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int height = Integer.parseInt(rows.getText().toString().trim());
                int width = Integer.parseInt(columns.getText().toString().trim());
                if (height <= 0 || width <= 0 || (long) height * width > 100) {
                    columns.setError("Mindestens eine Zeile und Spalte, höchstens 100 Zellen");
                    return;
                }
                dialog.dismiss();
                formula.matrix(height, width, java.util.Collections.emptyList(), operation);
            } catch (NumberFormatException e) {
                columns.setError("Bitte Zeilen und Spalten als ganze Zahlen eingeben");
            }
        });
    }

    private EditText matrixDimension(LinearLayout inputs, String name) {
        EditText dimension = new EditText(this);
        dimension.setHint(name);
        dimension.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dimension.setSingleLine(true);
        inputs.addView(dimension);
        return dimension;
    }

    private void showCalculusChoice(SpecialInput first, SpecialInput second, String firstLabel, String secondLabel) {
        new AlertDialog.Builder(this)
                .setTitle(first == SpecialInput.INTEGRAL ? "Integral" : "Ableitung")
                .setItems(new String[]{firstLabel, secondLabel},
                        (dialog, which) -> formula.function(which == 0 ? first : second))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void handleKeyPress(String key) {
        if (resultWritePending) return;
        if (key.equals("TOGGLE_VAR_MORE")) {
            showMoreVariables = !showMoreVariables;
            saveState();
            refreshKeyboardSection();
            return;
        }
        if (key.equals("TOGGLE_ANGLE")) {
            engine.setRadiansMode(!engine.isRadiansMode());
            saveState();
            updatePreviewOnly();
            refreshKeyboardSection();
            return;
        }
        if (key.equals("SHOW_MATRIX_DIALOG")) {
            showMatrixDialog(null);
            return;
        }
        SpecialInput special = SpecialInput.forKey(key);
        if (special != null) {
            if (special == SpecialInput.DET || special == SpecialInput.INVERSE || special == SpecialInput.TRANSPOSE) {
                showMatrixDialog(special);
            } else if (special == SpecialInput.INTEGRAL) {
                showCalculusChoice(special, SpecialInput.ANTIDERIVATIVE, "Bestimmtes Integral", "Stammfunktion");
            } else if (special == SpecialInput.DERIVATIVE) {
                showCalculusChoice(special, SpecialInput.DERIVATIVE_AT, "Symbolische Ableitung", "Ableitung an einer Stelle");
            } else {
                formula.function(special);
            }
            return;
        }
        if ((key.equals(", ") && formula.advanceField()) || (key.equals(")") && formula.closeTemplate())) return;
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
            clearInput();
            return;
        }
        if (key.equals("÷") || key.equals("/")) {
            formula.fraction();
            return;
        }
        if (key.equals("TEMPLATE_POWER") || key.equals("TEMPLATE_POWER_10") || key.equals("TEMPLATE_SQUARE")) {
            formula.power(key.equals("TEMPLATE_POWER_10") ? "10" : null,
                    key.equals("TEMPLATE_SQUARE") ? "2" : "");
            return;
        }

        if (key.equals("=")) {
            if (formula.focusMissingField()) {
                formula.performHapticFeedback(HapticFeedbackConstants.REJECT);
                return;
            }
            final String expression = formula.expression();
            if (!expression.trim().isEmpty()) {
                previewGeneration.incrementAndGet();
                if (pendingPreview != null) {
                    pendingPreview.cancel(true);
                    pendingPreview = null;
                }
                final String layoutState = formula.layoutState();
                final long submittedDraft = draftRevision;
                final CalculatorEngine snapshot = new CalculatorEngine(engine);
                resultWritePending = true;
                evalExecutor.execute(() -> {
                    try {
                        final CalculatorEngine.Result result = snapshot.evaluateAndCommitWithTimeout(expression, 4000);
                        final String rep = getIntegrationReportText(snapshot, expression);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                resultWritePending = false;
                                return;
                            }
                            CalculatorEngine.Value previousAns = engine.getLastAns();
                            double[][] previousMatrixAns = engine.getLastMatrixAns();
                            Map<String, CalculatorEngine.Value> previousVariables = engine.getUserVariables();
                            Map<String, double[][]> previousMatrixVariables = engine.getMatrixVariables();
                            engine.copyEvaluatedStateFrom(snapshot);
                            boolean initialExact = result.isExact && showExactFirst
                                    && (result.exactText == null || result.exactText.length() <= 400);
                            HistoryEntry entry = new HistoryEntry(expression, result.exactText, result.decimalText,
                                    result.reusableText, rep, initialExact, result.isExact);
                            entry.formulaLayout = layoutState;
                            persistResult(entry, previousAns, previousMatrixAns, previousVariables, previousMatrixVariables, submittedDraft);
                        });
                    } catch (Exception e) {
                        final String msg = e.getMessage() != null ? e.getMessage() : "";
                        runOnUiThread(() -> {
                            resultWritePending = false;
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            formula.performHapticFeedback(HapticFeedbackConstants.REJECT);
                            showPreviewError(msg, expression, true);
                        });
                    }
                });
            }
        } else if (key.equals("DEL")) {
            formula.backspace();
        } else if (key.equals("×") || key.equals("+") || key.equals("−")) {
            handleOperatorPress(key);
        } else if (key.equals("*")) {
            handleOperatorPress("×");
        } else if (key.equals("-")) {
            handleOperatorPress("−");
        } else if (key.equals(":=")) {
            EditText field = formula.activeField();
            int start = Math.max(0, field.getSelectionStart());
            insertAtCursor(start > 0 && field.getText().charAt(start - 1) != ' ' ? " := " : ":= ");
        } else if (key.startsWith("AC")) {
            history.clear();
            saveState();
            refreshHistoryViews(true);
        } else {
            insertCalculatorSymbol(key);
        }
    }

    private FormulaEditor makePlotFormula(String label, int color, String layout, String expression) {
        FormulaEditor editor = new FormulaEditor(this, color, colPrimary, this::plotFormulaChanged,
                null, MULTI_CHAR_TOKENS);
        editor.setCommaDecimal(useCommaDecimal);
        editor.enableSystemKeyboard(label);
        editor.restore(layout, expression);
        return editor;
    }

    private void plotFormulaChanged(FormulaEditor editor) {
        preparePlotFormula(editor);
        scheduleDraftSave();
        invalidatePlot();
    }

    private void preparePlotFormula(FormulaEditor editor) {
        String expression = plottableExpression(editor);
        boolean first = editor == plotFormula1;
        String previous = first ? compiledPlotExpression1 : compiledPlotExpression2;
        CalculatorEngine.PlotFunction function = first ? compiledPlotFn1 : compiledPlotFn2;
        String error = null;
        try {
            if (function != null && expression.equals(previous)) {
                engine.validatePlotFunction(function);
            } else {
                function = engine.compilePlotFunction(expression);
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
            function = null;
        }
        editor.setInputError(error);
        if (first) {
            compiledPlotExpression1 = expression;
            compiledPlotFn1 = function;
        } else {
            compiledPlotExpression2 = expression;
            compiledPlotFn2 = function;
        }
    }

    private static String plottableExpression(FormulaEditor editor) {
        return editor.hasMissingField() ? "" : editor.expression().trim();
    }

    private void invalidatePlot() {
        if (plotView != null) plotView.requestCurves();
    }

    private void finishPlotEditing() {
        plotFormula1.finishSystemEditing();
        plotFormula2.finishSystemEditing();
    }

    private void buildPlotterTab(FrameLayout parent) {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(8), dp(10), dp(8));
        col.setFocusableInTouchMode(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(roundedRect(colSurfaceContainer, 16));
        header.setPadding(dp(10), landscape ? dp(4) : dp(8), dp(10), landscape ? dp(4) : dp(8));

        LinearLayout formulas = new LinearLayout(this);
        formulas.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        float formulaSize = landscape ? 16f : 19f;
        addPlotFormula(formulas, "f₁(x)", plotFormula1, colPrimary, formulaSize, landscape);
        addPlotFormula(formulas, "f₂(x)", plotFormula2, COL_PLOT_CURVE_2, formulaSize, landscape);
        preparePlotFormula(plotFormula1);
        preparePlotFormula(plotFormula2);
        header.addView(formulas);

        String draft = formula.expression().trim();
        if (draft.isEmpty() && !history.isEmpty()) {
            HistoryEntry latest = history.get(history.size() - 1);
            addPlotImportButton(header, latest.expr, latest.formulaLayout);
        } else if (!draft.isEmpty() && !formula.hasMissingField()) {
            addPlotImportButton(header, draft, formula.layoutState());
        }

        col.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        plotView = new Plotter2DView(this);
        plotView.setContentDescription("Funktionsgraph");
        LinearLayout.LayoutParams plotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        plotParams.topMargin = dp(6);
        col.addView(plotView, plotParams);
        parent.addView(col);
        col.requestFocus();
    }

    private void addPlotFormula(LinearLayout container, String label, FormulaEditor editor, int color,
                                float formulaSize, boolean landscape) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(color);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.rightMargin = dp(6);
        row.addView(dot, dotParams);

        TextView name = new TextView(this);
        name.setText(label + " =");
        name.setTextColor(color);
        setScaledTextSize(name, 13f, 1.4f);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.rightMargin = dp(3);
        row.addView(name, nameParams);

        if (editor.getParent() instanceof ViewGroup) {
            ((ViewGroup) editor.getParent()).removeView(editor);
        }
        row.addView(editor, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editor.show(formulaSize);

        Button clear = makeMaterialButton("×", Color.TRANSPARENT, colOnSurfaceVariant, 12, 16f, 1.4f);
        clear.setContentDescription(label + " löschen");
        clear.setOnClickListener(v -> editor.clear());
        row.addView(clear, new LinearLayout.LayoutParams(dp(36), dp(40)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                landscape ? 1f : 0f);
        container.addView(row, params);
    }

    private void addPlotImportButton(LinearLayout header, String expression, String layout) {
        Button button = makeMaterialButton(
                "Rechner-Formel übernehmen", Color.TRANSPARENT, colPrimary, 12, 11f, 1.2f, true);
        button.setBackground(outlinedPill(colPrimary, dp(1), 12));
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            plotFormula1.replaceDraft(layout, expression);
        });
        header.addView(button);
    }

    private class Plotter2DView extends View {
        private static final double MIN_SCALE = 1e-6;
        private static final double MAX_SCALE = 1e6;
        private final Paint minorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint majorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curve1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curve2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaleDetector;
        private ScheduledThreadPoolExecutor plotExecutor;
        private Future<?> pendingCurves;
        private int formulaVersion;
        private CalculatorEngine plotContext;
        private boolean workerRunning;
        private boolean recomputeQueued;
        private float[] cachedSegments1;
        private float[] cachedSegments2;
        private double cachedCenterX;
        private double cachedCenterY;
        private double cachedScale = 64.0;
        private int cachedWidth;
        private int cachedHeight;
        private final Runnable computeCurves = this::scheduleCurveCompute;
        private double centerX = 0.0;
        private double centerY = 0.0;
        private double scale = 64.0;
        private float lastTouchX;
        private float lastTouchY;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;

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

            labelPaint.setColor(colOnSurface);
            labelPaint.setTextSize(dp(11));
            labelPaint.setTypeface(Typeface.DEFAULT_BOLD);

            curve1Paint.setStrokeWidth(4.0f);
            curve1Paint.setStyle(Paint.Style.STROKE);
            curve1Paint.setStrokeCap(Paint.Cap.ROUND);
            curve1Paint.setStrokeJoin(Paint.Join.ROUND);
            curve1Paint.setColor(colPrimary);

            curve2Paint.setStrokeWidth(4.0f);
            curve2Paint.setStyle(Paint.Style.STROKE);
            curve2Paint.setStrokeCap(Paint.Cap.ROUND);
            curve2Paint.setStrokeJoin(Paint.Join.ROUND);
            curve2Paint.setColor(COL_PLOT_CURVE_2);

            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    double factor = detector.getScaleFactor();
                    if (!Double.isFinite(factor) || factor <= 0.0) return false;
                    double newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
                    if (newScale != scale && getWidth() > 0 && getHeight() > 0) {
                        double fx = detector.getFocusX() - getWidth() * 0.5;
                        double fy = detector.getFocusY() - getHeight() * 0.5;
                        double worldX = centerX + fx / scale;
                        double worldY = centerY - fy / scale;
                        scale = newScale;
                        centerX = worldX - fx / scale;
                        centerY = worldY + fy / scale;
                        onViewportChanged();
                    }
                    return true;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (scaleDetector.isInProgress()) {
                int idx = activePointerId != MotionEvent.INVALID_POINTER_ID
                        ? event.findPointerIndex(activePointerId) : 0;
                if (idx >= 0) {
                    lastTouchX = event.getX(idx);
                    lastTouchY = event.getY(idx);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                finishPlotEditing();
                int idx = event.getActionIndex();
                activePointerId = event.getPointerId(idx);
                lastTouchX = event.getX(idx);
                lastTouchY = event.getY(idx);
                return true;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                int idx = activePointerId != MotionEvent.INVALID_POINTER_ID
                        ? event.findPointerIndex(activePointerId) : 0;
                if (idx >= 0) {
                    lastTouchX = event.getX(idx);
                    lastTouchY = event.getY(idx);
                }
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (event.getPointerCount() == 1) {
                    int idx = activePointerId != MotionEvent.INVALID_POINTER_ID
                            ? event.findPointerIndex(activePointerId) : 0;
                    if (idx < 0) idx = 0;
                    float x = event.getX(idx);
                    float y = event.getY(idx);
                    double dx = x - lastTouchX;
                    double dy = y - lastTouchY;
                    if (dx != 0.0 || dy != 0.0) {
                        centerX -= dx / scale;
                        centerY += dy / scale;
                        onViewportChanged();
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                }
                return true;
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                int leavingIdx = event.getActionIndex();
                int leavingId = event.getPointerId(leavingIdx);
                if (leavingId == activePointerId && event.getPointerCount() > 1) {
                    int nextIdx = (leavingIdx == 0) ? 1 : 0;
                    activePointerId = event.getPointerId(nextIdx);
                    lastTouchX = event.getX(nextIdx);
                    lastTouchY = event.getY(nextIdx);
                }
                onViewportChanged();
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                onViewportChanged();
                return true;
            }
            return super.onTouchEvent(event);
        }

        private String trimFixedDecimals(double val, int decimals) {
            String s = String.format(java.util.Locale.US, "%." + decimals + "f", val);
            int end = s.length();
            if (decimals > 0 && s.indexOf('.') >= 0) {
                while (end > 0 && s.charAt(end - 1) == '0') end--;
                if (end > 0 && s.charAt(end - 1) == '.') end--;
            }
            String out = s.substring(0, end);
            if (out.equals("-0") || out.isEmpty()) return "0";
            return out;
        }

        private String formatAxisValue(double v, double step) {
            if (Math.abs(v) <= 1e-9 * step) return "0";
            double absV = Math.abs(v);
            if (absV >= 1e6 || absV < 1e-3) {
                int exp = (int) Math.floor(Math.log10(absV) + 1e-12);
                double pow10 = Math.pow(10.0, exp);
                double mant = v / pow10;
                if (Math.abs(Math.abs(mant) - 10.0) < 1e-6) {
                    mant /= 10.0;
                    exp += 1;
                }
                String sup = toSuperscript(exp);
                if (Math.abs(Math.abs(mant) - 1.0) < 1e-4) {
                    return (mant < 0 ? "−10" : "10") + sup;
                }
                String mStr = trimFixedDecimals(mant, 2);
                if (useCommaDecimal) mStr = mStr.replace('.', ',');
                return mStr.replace('-', '−') + "×10" + sup;
            }
            int decimals = step >= 1.0 ? 0 : Math.min(4, (int) Math.ceil(-Math.log10(step) - 1e-9));
            String s = trimFixedDecimals(v, decimals);
            if (useCommaDecimal) s = s.replace('.', ',');
            return s.replace('-', '−');
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(colSurfaceContainer);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            double cx = w * 0.5;
            double cy = h * 0.5;

            double xMin = centerX - cx / scale;
            double xMax = centerX + cx / scale;
            double yMin = centerY - cy / scale;
            double yMax = centerY + cy / scale;

            double rawStep = dp(56) / scale;
            double mag = Math.pow(10.0, Math.floor(Math.log10(Math.max(rawStep, 1e-12))));
            double norm = rawStep / mag;
            double majorStep = (norm < 1.5) ? mag : (norm < 3.5) ? 2.0 * mag : (norm < 7.5) ? 5.0 * mag : 10.0 * mag;
            double minorStep = majorStep / 2.0;

            long minStartX = (long) Math.floor(xMin / minorStep);
            long minEndX = (long) Math.ceil(xMax / minorStep);
            if (minEndX - minStartX <= 200) {
                for (long i = minStartX; i <= minEndX; i++) {
                    float sx = (float) (cx + (i * minorStep - centerX) * scale);
                    canvas.drawLine(sx, 0, sx, h, minorPaint);
                }
            }
            long minStartY = (long) Math.floor(yMin / minorStep);
            long minEndY = (long) Math.ceil(yMax / minorStep);
            if (minEndY - minStartY <= 200) {
                for (long i = minStartY; i <= minEndY; i++) {
                    float sy = (float) (cy - (i * minorStep - centerY) * scale);
                    canvas.drawLine(0, sy, w, sy, minorPaint);
                }
            }

            long majStartX = (long) Math.floor(xMin / majorStep);
            long majEndX = (long) Math.ceil(xMax / majorStep);
            if (majEndX - majStartX <= 200) {
                for (long i = majStartX; i <= majEndX; i++) {
                    float sx = (float) (cx + (i * majorStep - centerX) * scale);
                    canvas.drawLine(sx, 0, sx, h, majorPaint);
                }
            }
            long majStartY = (long) Math.floor(yMin / majorStep);
            long majEndY = (long) Math.ceil(yMax / majorStep);
            if (majEndY - majStartY <= 200) {
                for (long i = majStartY; i <= majEndY; i++) {
                    float sy = (float) (cy - (i * majorStep - centerY) * scale);
                    canvas.drawLine(0, sy, w, sy, majorPaint);
                }
            }

            double x0d = cx - centerX * scale;
            double y0d = cy + centerY * scale;
            if (x0d >= -4 && x0d <= w + 4) {
                canvas.drawLine((float) x0d, 0, (float) x0d, h, axisPaint);
            }
            if (y0d >= -4 && y0d <= h + 4) {
                canvas.drawLine(0, (float) y0d, w, (float) y0d, axisPaint);
            }

            float x0 = (float) Math.max(-1e4, Math.min(1e4, x0d));
            float y0 = (float) Math.max(-1e4, Math.min(1e4, y0d));
            float tick = dp(3);
            float xTickY = Math.max(tick, Math.min(h - tick, y0));
            float xLabelY = Math.max(dp(14), Math.min(h - dp(6), y0 + dp(14)));
            labelPaint.setTextAlign(Paint.Align.CENTER);
            if (majEndX - majStartX <= 200) {
                for (long i = majStartX; i <= majEndX; i++) {
                    if (i == 0L) continue;
                    double gx = i * majorStep;
                    float sx = (float) (cx + (gx - centerX) * scale);
                    if (sx >= dp(18) && sx <= w - dp(18)) {
                        if (y0d >= -tick && y0d <= h + tick) {
                            canvas.drawLine(sx, xTickY - tick, sx, xTickY + tick, axisPaint);
                        }
                        canvas.drawText(formatAxisValue(gx, majorStep), sx, xLabelY, labelPaint);
                    }
                }
            }

            float yTickX = Math.max(tick, Math.min(w - tick, x0));
            float yLabelX = Math.max(dp(6), Math.min(w - dp(46), x0 + dp(6)));
            labelPaint.setTextAlign(Paint.Align.LEFT);
            if (majEndY - majStartY <= 200) {
                for (long i = majStartY; i <= majEndY; i++) {
                    if (i == 0L) continue;
                    double gy = i * majorStep;
                    float sy = (float) (cy - (gy - centerY) * scale);
                    if (sy >= dp(14) && sy <= h - dp(10)) {
                        if (x0d >= -tick && x0d <= w + tick) {
                            canvas.drawLine(yTickX - tick, sy, yTickX + tick, sy, axisPaint);
                        }
                        canvas.drawText(formatAxisValue(gy, majorStep), yLabelX, sy + dp(4), labelPaint);
                    }
                }
            }

            if (x0d >= dp(14) && x0d <= w - dp(10) && y0d >= dp(10) && y0d <= h - dp(16)) {
                canvas.drawText("0", (float) x0d - dp(10), (float) y0d + dp(14), labelPaint);
            }

            if ((cachedSegments1 != null || cachedSegments2 != null) && cachedWidth > 0 && cachedHeight > 0 && cachedScale > 0) {
                float ratio = (float) (scale / cachedScale);
                float cachedCx = cachedWidth * 0.5f;
                float cachedCy = cachedHeight * 0.5f;
                float mappedCachedCenterX = (float) (cx + (cachedCenterX - centerX) * scale);
                float mappedCachedCenterY = (float) (cy - (cachedCenterY - centerY) * scale);
                canvas.save();
                canvas.translate(mappedCachedCenterX, mappedCachedCenterY);
                canvas.scale(ratio, ratio);
                canvas.translate(-cachedCx, -cachedCy);
                float invRatio = 1.0f / Math.max(0.25f, Math.min(20.0f, ratio));
                curve1Paint.setStrokeWidth(4.0f * invRatio);
                curve2Paint.setStrokeWidth(4.0f * invRatio);
                if (cachedSegments1 != null && cachedSegments1.length >= 4) {
                    canvas.drawLines(cachedSegments1, curve1Paint);
                }
                if (cachedSegments2 != null && cachedSegments2.length >= 4) {
                    canvas.drawLines(cachedSegments2, curve2Paint);
                }
                canvas.restore();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            plotExecutor = new ScheduledThreadPoolExecutor(1);
            plotExecutor.setRemoveOnCancelPolicy(true);
            workerRunning = false;
            recomputeQueued = false;
            requestCurves();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
            super.onSizeChanged(w, h, oldWidth, oldHeight);
            requestCurves();
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelCurves();
            if (plotExecutor != null) {
                plotExecutor.shutdownNow();
                plotExecutor = null;
            }
            super.onDetachedFromWindow();
        }

        private void cancelCurves() {
            ++formulaVersion;
            workerRunning = false;
            recomputeQueued = false;
            removeCallbacks(computeCurves);
            if (pendingCurves != null) {
                pendingCurves.cancel(true);
                pendingCurves = null;
            }
        }

        void requestCurves() {
            ++formulaVersion;
            plotContext = engine.snapshotForPlot();
            if (compiledPlotFn1 == null) cachedSegments1 = null;
            if (compiledPlotFn2 == null) cachedSegments2 = null;
            removeCallbacks(computeCurves);
            if (pendingCurves != null) {
                pendingCurves.cancel(true);
                pendingCurves = null;
            }
            workerRunning = false;
            recomputeQueued = false;
            invalidate();
            if (plotExecutor != null) postOnAnimation(computeCurves);
        }

        private void onViewportChanged() {
            invalidate();
            if (plotExecutor == null) return;
            if (workerRunning) {
                recomputeQueued = true;
            } else {
                removeCallbacks(computeCurves);
                scheduleCurveCompute();
            }
        }

        private void scheduleCurveCompute() {
            int w = getWidth();
            int h = getHeight();
            if (plotExecutor == null || w <= 0 || h <= 0) return;
            if (compiledPlotFn1 == null && compiledPlotFn2 == null) {
                cachedSegments1 = null;
                cachedSegments2 = null;
                return;
            }
            if (workerRunning) {
                recomputeQueued = true;
                return;
            }
            if (plotContext == null) {
                plotContext = engine.snapshotForPlot();
            }
            final int reqVersion = formulaVersion;
            final double reqX = centerX;
            final double reqY = centerY;
            final double reqScale = scale;
            final int reqW = w;
            final int reqH = h;
            final CalculatorEngine context = plotContext;
            final CalculatorEngine.PlotFunction fn1 = compiledPlotFn1 == null ? null : compiledPlotFn1.withContext(context);
            final CalculatorEngine.PlotFunction fn2 = compiledPlotFn2 == null ? null : compiledPlotFn2.withContext(context);
            workerRunning = true;
            recomputeQueued = false;
            pendingCurves = plotExecutor.submit(() -> {
                try {
                    float[] first = computeCurveSegments(fn1, reqW, reqH, reqX, reqY, reqScale);
                    float[] second = computeCurveSegments(fn2, reqW, reqH, reqX, reqY, reqScale);
                    post(() -> {
                        workerRunning = false;
                        if (plotExecutor == null) return;
                        if (reqVersion == formulaVersion) {
                            cachedSegments1 = first;
                            cachedSegments2 = second;
                            cachedCenterX = reqX;
                            cachedCenterY = reqY;
                            cachedScale = reqScale;
                            cachedWidth = reqW;
                            cachedHeight = reqH;
                            invalidate();
                        }
                        if (recomputeQueued || reqVersion != formulaVersion
                                || reqX != centerX || reqY != centerY || reqScale != scale) {
                            recomputeQueued = false;
                            scheduleCurveCompute();
                        }
                    });
                } catch (CancellationException ignored) {
                    post(() -> {
                        workerRunning = false;
                        if (plotExecutor != null && recomputeQueued) {
                            recomputeQueued = false;
                            scheduleCurveCompute();
                        }
                    });
                }
            });
        }

        private float clampScreenY(double y, int h) {
            return (float) Math.max(-2.0 * h, Math.min(3.0 * h, y));
        }

        private float[] computeCurveSegments(CalculatorEngine.PlotFunction fn, int w, int h,
                                             double reqCenterX, double reqCenterY, double reqScale) {
            if (fn == null) return null;
            double cx = w * 0.5;
            double cy = h * 0.5;
            int pxStart = -w / 2;
            int pxEnd = w + w / 2;
            int maxSegs = ((pxEnd - pxStart) / 3) + 4;
            float[] buf = new float[maxSegs * 4];
            int count = 0;
            double prevXv = 0.0, prevYv = 0.0;
            float prevPx = 0f;
            double prevSy = 0.0;
            boolean hasPrev = false;
            for (int px = pxStart; px <= pxEnd; px += 3) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                double xv = reqCenterX + (px - cx) / reqScale;
                try {
                    double yv = fn.evaluate(xv);
                    if (Double.isFinite(yv)) {
                        double sy = cy - (yv - reqCenterY) * reqScale;
                        if (hasPrev && count + 4 <= buf.length) {
                            boolean sameSideOffscreen = (prevSy < -h && sy < -h) || (prevSy > 2.0 * h && sy > 2.0 * h);
                            if (!sameSideOffscreen) {
                                boolean draw = Math.abs(sy - prevSy) < h;
                                if (!draw) {
                                    try {
                                        double midYv = fn.evaluate(0.5 * (prevXv + xv));
                                        draw = Double.isFinite(midYv)
                                                && ((midYv >= prevYv && midYv <= yv) || (midYv <= prevYv && midYv >= yv));
                                    } catch (RuntimeException ignored) {
                                        draw = false;
                                    }
                                }
                                if (draw) {
                                    buf[count++] = prevPx;
                                    buf[count++] = clampScreenY(prevSy, h);
                                    buf[count++] = px;
                                    buf[count++] = clampScreenY(sy, h);
                                }
                            }
                        }
                        prevXv = xv;
                        prevYv = yv;
                        prevPx = px;
                        prevSy = sy;
                        hasPrev = true;
                    } else {
                        hasPrev = false;
                    }
                } catch (CancellationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    hasPrev = false;
                }
            }
            return Arrays.copyOf(buf, count);
        }
    }

    static CharSequence formatSubscriptSymbol(String sym) {
        int idx = sym.indexOf('_');
        if (idx < 0 || idx == sym.length() - 1) return sym;
        String base = sym.substring(0, idx);
        String sub = sym.substring(idx + 1);
        SpannableString ss = new SpannableString(base + sub);
        ss.setSpan(new SubscriptSpan(), base.length(), base.length() + sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new RelativeSizeSpan(0.75f), base.length(), base.length() + sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private static final java.util.Set<Integer> expandedCategories = new java.util.HashSet<>(java.util.Collections.singletonList(1));

    private static class ConstantDef {
        final String symbol;
        final String token;
        final String name;
        final String displayValue;
        final boolean exactSi;

        ConstantDef(String symbol, String token, String name, String displayValue) {
            this(symbol, token, name, displayValue, false);
        }

        ConstantDef(String symbol, String token, String name, String displayValue, boolean exactSi) {
            this.symbol = symbol;
            this.token = token;
            this.name = name;
            this.displayValue = displayValue;
            this.exactSi = exactSi;
        }
    }

    private static class CategoryDef {
        final String title;
        final ConstantDef[] items;

        CategoryDef(String title, ConstantDef[] items) {
            this.title = title;
            this.items = items;
        }
    }

    private CategoryDef[] getConstantCategories() {
        return new CategoryDef[]{
                new CategoryDef("Benutzerdefinierte Variablen", new ConstantDef[]{}),
                new CategoryDef("Universelle physikalische Konstanten", new ConstantDef[]{
                        new ConstantDef("k_B", "k_B", "Boltzmann-Konstante", "1,380 649×10⁻²³", true),
                        new ConstantDef("ε₀", "eps0", "Elektrische Feldkonstante", "8,854 188×10⁻¹²"),
                        new ConstantDef("e⁻", "e_charge", "Elementarladung", "1,602 176 634×10⁻¹⁹", true),
                        new ConstantDef("α", "alpha_fs", "Feinstrukturkonstante", "7,297 353×10⁻³"),
                        new ConstantDef("G", "G", "Gravitationskonstante", "6,674 30×10⁻¹¹"),
                        new ConstantDef("g", "g", "Gravitationsbeschleunigung", "9,806 65", true),
                        new ConstantDef("c₀", "c0", "Lichtgeschwindigkeit", "299 792 458", true),
                        new ConstantDef("μ₀", "mu0", "Magnetische Feldkonstante", "1,256 637×10⁻⁶"),
                        new ConstantDef("h", "h", "Plancksches Wirkungsquantum", "6,626 070 15×10⁻³⁴", true)
                }),
                new CategoryDef("Elektromagnetische Konstanten", new ConstantDef[]{
                        new ConstantDef("Z₀", "Z0", "Wellenwiderstand des Vakuums", "376,730 3"),
                        new ConstantDef("Φ₀", "Phi0", "Magnetisches Flussquant", "2,067 834×10⁻¹⁵", true),
                        new ConstantDef("K_J", "K_J", "Josephson-Konstante", "4,835 978×10¹⁴", true),
                        new ConstantDef("R_K", "R_K", "Von-Klitzing-Konstante", "25 812,81", true),
                        new ConstantDef("G₀", "G0", "Leitwertquant", "7,748 092×10⁻⁵", true),
                        new ConstantDef("σ", "sigma", "Stefan-Boltzmann-Konstante", "5,670 374×10⁻⁸", true),
                        new ConstantDef("R_∞", "R_inf", "Rydberg-Konstante", "1,097 373×10⁷"),
                        new ConstantDef("E_h", "E_h", "Hartree-Energie", "4,359 745×10⁻¹⁸"),
                        new ConstantDef("μ_B", "mu_B", "Bohrsches Magneton", "9,274 010×10⁻²⁴"),
                        new ConstantDef("μ_e", "mu_e", "Magnetisches Moment d. Elektrons", "−9,284 765×10⁻²⁴")
                }),
                new CategoryDef("Chemische Konstanten", new ConstantDef[]{
                        new ConstantDef("N_A", "N_A", "Avogadro-Konstante", "6,022 140 76×10²³", true),
                        new ConstantDef("m_u", "m_u", "Atomare Masseneinheit", "1,660 539×10⁻²⁷"),
                        new ConstantDef("F", "F", "Faraday-Konstante", "96 485,33", true),
                        new ConstantDef("R", "R", "Universelle Gaskonstante", "8,314 463", true),
                        new ConstantDef("m_e", "m_e", "Elektronenmasse", "9,109 384×10⁻³¹"),
                        new ConstantDef("m_p", "m_p", "Protonenmasse", "1,672 622×10⁻²⁷"),
                        new ConstantDef("m_n", "m_n", "Neutronenmasse", "1,674 928×10⁻²⁷")
                }),
                new CategoryDef("Planck- & Naturkonstanten", new ConstantDef[]{
                        new ConstantDef("m_P", "m_P", "Planck-Masse", "2,176 434×10⁻⁸"),
                        new ConstantDef("t_P", "t_P", "Planck-Zeit", "5,391 247×10⁻⁴⁴"),
                        new ConstantDef("l_P", "l_P", "Planck-Länge", "1,616 255×10⁻³⁵")
                }),
                new CategoryDef("Mathematische Konstanten", new ConstantDef[]{
                        new ConstantDef("π", "pi", "Kreiszahl Pi", "3,14159…"),
                        new ConstantDef("e", "e", "Eulersche Zahl", "2,71828…"),
                        new ConstantDef("i", "i", "Imaginäre Einheit", "√(−1)")
                })
        };
    }

    private void showVariableOptionsDialog(final String vName) {
        String curVal = engine.formatVariable(vName, true);
        String valDesc = (curVal != null) ? formatDisplayNumber(curVal) : "nicht gesetzt";

        new AlertDialog.Builder(this)
                .setTitle("Variable " + vName + " (" + valDesc + ")")
                .setItems(new String[]{
                        "Wert zuweisen (im Rechner: " + vName + " := )",
                        "Wert direkt festlegen…",
                        "Variable zurücksetzen (unset)",
                        "In Rechner einsetzen (" + vName + ")",
                        "Zur Tastatur hinzufügen (Variablen a..z)"
                }, (dialog, which) -> {
                    if (which == 0) {
                        insertConstantOrVariable(vName + " := ");
                    } else if (which == 1) {
                        showSetDirectValueDialog(vName);
                    } else if (which == 2) {
                        engine.unsetVariable(vName);
                        saveState();
                        rebuildScreen();
                        Toast.makeText(this, "Variable " + vName + " zurückgesetzt", Toast.LENGTH_SHORT).show();
                    } else if (which == 3) {
                        insertConstantOrVariable(vName);
                    } else if (which == 4) {
                        showMoreVariables = !showMoreVariables;
                        saveState();
                        rebuildScreen();
                        Toast.makeText(this, showMoreVariables ? "Tastatur-Variablen eingeblendet" : "Standard-Tastatur aktiv", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void insertConstantOrVariable(String token) {
        insertCalculatorSymbol(token);
        activeTab = 0;
        rebuildScreen();
    }

    private void insertCalculatorSymbol(String token) {
        for (CategoryDef category : constantCategories) {
            for (ConstantDef constant : category.items) {
                if (constant.token.equals(token) && !constant.symbol.equals(token)) {
                    formula.symbol(constant.symbol, token);
                    return;
                }
            }
        }
        formula.insert(token);
    }

    private void showSetDirectValueDialog(final String vName) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(colOnSurface);
        input.setHint("z. B. 42 oder 1/2 oder i");
        input.setHintTextColor(colOnSurfaceVariant);
        String curVal = engine.formatVariable(vName, true);
        if (curVal != null) {
            input.setText(curVal);
            input.setSelection(input.getText().length());
        }

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(20);
        lp.topMargin = dp(8);
        container.addView(input, lp);

        new AlertDialog.Builder(this)
                .setTitle("Wert für " + vName + " festlegen")
                .setView(container)
                .setPositiveButton("Speichern", (dialog, which) -> {
                    String text = input.getText().toString().trim().replace(",", ".");
                    if (!text.isEmpty()) {
                        try {
                            engine.evaluateAndCommit(vName + " := " + text);
                            saveState();
                            rebuildScreen();
                            Toast.makeText(this, vName + " := " + text, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            try {
                                double d = Double.parseDouble(text);
                                engine.setVariable(vName, d);
                                saveState();
                                rebuildScreen();
                                Toast.makeText(this, vName + " := " + text, Toast.LENGTH_SHORT).show();
                            } catch (Exception ex) {
                                Toast.makeText(this, "Ungültiger Wert", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private LinearLayout buildUserVariablesGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        final String[] uVars = {"a", "b", "c", "d", "k", "l", "m", "n", "x", "y", "z"};
        int cols = 3;
        int rows = (int) Math.ceil((double) uVars.length / cols);

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(6));

            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx < uVars.length) {
                    final String vName = uVars[idx];
                    String curVal = engine.formatVariable(vName, true);
                    String valStr = curVal != null ? formatDisplayNumber(curVal) : "nicht gesetzt";

                    LinearLayout tile = new LinearLayout(this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER_HORIZONTAL);
                    tile.setPadding(dp(4), dp(4), dp(4), dp(6));

                    FrameLayout circle = new FrameLayout(this);
                    circle.setBackground(roundedRect(colSurfaceHigh, 25));
                    FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(dp(50), dp(50));
                    cLp.gravity = Gravity.CENTER;

                    TextView symTv = new TextView(this);
                    symTv.setText(vName);
                    symTv.setTextColor(colPrimary);
                    symTv.setTypeface(Typeface.DEFAULT_BOLD);
                    symTv.setGravity(Gravity.CENTER);
                    setScaledTextSize(symTv, 18f, 1.4f);
                    circle.addView(symTv, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    tile.addView(circle, cLp);

                    TextView valTv = new TextView(this);
                    valTv.setText(valStr);
                    valTv.setTextColor(colOnSurfaceVariant);
                    valTv.setGravity(Gravity.CENTER);
                    valTv.setSingleLine(true);
                    setScaledTextSize(valTv, 11f, 1.3f);
                    LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    valLp.topMargin = dp(4);
                    tile.addView(valTv, valLp);

                    View.OnLongClickListener onLong = v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        showVariableOptionsDialog(vName);
                        return true;
                    };
                    tile.setOnLongClickListener(onLong);
                    circle.setOnLongClickListener(onLong);

                    tile.setClickable(true);
                    tile.setFocusable(true);
                    tile.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        insertConstantOrVariable(vName);
                    });

                    LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    row.addView(tile, tileLp);
                } else {
                    View empty = new View(this);
                    row.addView(empty, new LinearLayout.LayoutParams(0, 0, 1f));
                }
            }
            grid.addView(row);
        }
        // Optional quick keyboard shortcut button inside the variables category
        LinearLayout kbRow = new LinearLayout(this);
        kbRow.setOrientation(LinearLayout.HORIZONTAL);
        kbRow.setGravity(Gravity.CENTER);
        kbRow.setPadding(0, dp(6), 0, dp(4));

        Button toggleKbBtn = makeMaterialButton(
                showMoreVariables ? "Tastatur: Standard-Belegung wiederherstellen" : "Tastatur: Variablen a..z dauerhaft einblenden",
                Color.TRANSPARENT, colPrimary, 16, 11.5f, 1.3f, true);
        toggleKbBtn.setBackground(outlinedPill(colPrimary, dp(1.2f), 16));
        toggleKbBtn.setPadding(dp(14), dp(4), dp(14), dp(4));
        toggleKbBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            showMoreVariables = !showMoreVariables;
            saveState();
            rebuildScreen();
        });
        kbRow.addView(toggleKbBtn);
        grid.addView(kbRow);

        return grid;
    }

    private LinearLayout buildConstantGrid(ConstantDef[] items) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        int cols = 3;
        int rows = (int) Math.ceil((double) items.length / cols);

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(6));

            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx < items.length) {
                    final ConstantDef item = items[idx];
                    LinearLayout tile = new LinearLayout(this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER_HORIZONTAL);
                    tile.setPadding(dp(4), dp(4), dp(4), dp(6));

                    // 1. Circular Symbol Badge
                    FrameLayout circle = new FrameLayout(this);
                    circle.setBackground(roundedRect(colSurfaceHigh, 25));
                    FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(dp(50), dp(50));
                    cLp.gravity = Gravity.CENTER;

                    TextView symTv = new TextView(this);
                    symTv.setText(formatSubscriptSymbol(item.symbol));
                    symTv.setTextColor(colOnSurface);
                    symTv.setTypeface(Typeface.DEFAULT_BOLD);
                    symTv.setGravity(Gravity.CENTER);
                    setScaledTextSize(symTv, 16f, 1.4f);
                    circle.addView(symTv, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    tile.addView(circle, cLp);

                    // 2. Numerical Value
                    TextView valTv = new TextView(this);
                    valTv.setText(useCommaDecimal ? item.displayValue : item.displayValue.replace(',', '.'));
                    valTv.setTextColor(colOnSurfaceVariant);
                    valTv.setGravity(Gravity.CENTER);
                    valTv.setSingleLine(true);
                    valTv.setAutoSizeTextTypeUniformWithConfiguration(
                            7, 11, 1, TypedValue.COMPLEX_UNIT_DIP);
                    LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
                    valLp.topMargin = dp(4);
                    tile.addView(valTv, valLp);

                    // 3. Name (e.g. Plancksches Wirkungsquantum or Boltzmann-Konstante)
                    TextView nameTv = new TextView(this);
                    String formattedName = item.name
                            .replace("Gravitationsbeschleunigung", "Gravitations-\nbeschleunigung")
                            .replace("Feinstrukturkonstante", "Feinstruktur-\nkonstante")
                            .replace("Stefan-Boltzmann-Konstante", "Stefan-Boltzmann-\nKonstante")
                            .replace("Josephson-Konstante", "Josephson-\nKonstante")
                            .replace("Von-Klitzing-Konstante", "Von-Klitzing-\nKonstante");
                    nameTv.setText(formattedName);
                    nameTv.setTextColor(colOnSurface);
                    nameTv.setGravity(Gravity.CENTER);
                    nameTv.setMaxLines(2);
                    setScaledTextSize(nameTv, item.name.length() > 18 ? 9.5f : (item.name.length() > 14 ? 10.5f : 11f), 1.3f);
                    LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    nameLp.topMargin = dp(2);
                    tile.addView(nameTv, nameLp);

                    if (item.exactSi) {
                        TextView exactSiTv = new TextView(this);
                        exactSiTv.setText("exakt (SI)");
                        exactSiTv.setTextColor(colPrimary);
                        exactSiTv.setGravity(Gravity.CENTER);
                        exactSiTv.setSingleLine(true);
                        setScaledTextSize(exactSiTv, 8.5f, 1.3f);
                        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        exLp.topMargin = dp(1);
                        tile.addView(exactSiTv, exLp);
                    }

                    tile.setClickable(true);
                    tile.setFocusable(true);
                    tile.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        insertConstantOrVariable(item.token);
                    });

                    LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    row.addView(tile, tileLp);
                } else {
                    View empty = new View(this);
                    row.addView(empty, new LinearLayout.LayoutParams(0, 0, 1f));
                }
            }
            grid.addView(row);
        }
        return grid;
    }

    private void buildConstantsTab(FrameLayout parent) {
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(10), dp(12), dp(16));

        // Accordion Category Cards (No top pills, clean direct view)
        CategoryDef[] cats = constantCategories;
        for (int cIdx = 0; cIdx < cats.length; cIdx++) {
            final int catIndex = cIdx;
            final CategoryDef cat = cats[cIdx];
            final boolean isExp = expandedCategories.contains(catIndex);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(roundedRect(colSurfaceContainer, 16));
            card.setPadding(dp(12), dp(12), dp(12), dp(12));

            // Header Row (Chevron + Title)
            LinearLayout hdr = new LinearLayout(this);
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            hdr.setClickable(true);
            hdr.setFocusable(true);
            hdr.setPadding(dp(4), dp(4), dp(4), dp(4));
            hdr.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (expandedCategories.contains(catIndex)) {
                    expandedCategories.remove(catIndex);
                } else {
                    expandedCategories.add(catIndex);
                }
                rebuildScreen();
            });

            TextView chev = new TextView(this);
            chev.setText(isExp ? "∧  " : "∨  ");
            chev.setTextColor(colOnSurfaceVariant);
            chev.setTypeface(Typeface.DEFAULT_BOLD);
            setScaledTextSize(chev, 15f, 2.0f);
            hdr.addView(chev);

            TextView titleTv = new TextView(this);
            titleTv.setText(cat.title);
            titleTv.setTextColor(colOnSurface);
            setScaledTextSize(titleTv, 15f, 2.0f);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            hdr.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            card.addView(hdr);

            if (isExp) {
                View div = new View(this);
                div.setBackgroundColor(0x22FFFFFF);
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                divLp.topMargin = dp(8);
                divLp.bottomMargin = dp(10);
                card.addView(div, divLp);

                if (catIndex == 0) {
                    LinearLayout varGrid = buildUserVariablesGrid();
                    card.addView(varGrid);
                } else {
                    LinearLayout grid = buildConstantGrid(cat.items);
                    card.addView(grid);
                }
            }

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(8);
            list.addView(card, cardLp);
        }

        sv.addView(list);

        TextView codataFooter = new TextView(this);
        codataFooter.setText("Stand: CODATA 2022 / SI 2019");
        codataFooter.setTextColor(colOnSurfaceVariant);
        codataFooter.setGravity(Gravity.CENTER);
        setScaledTextSize(codataFooter, 11f, 1.5f);
        codataFooter.setPadding(0, dp(4), 0, dp(4));
        list.addView(codataFooter);

        parent.addView(sv);
    }

    private interface SwitchChangeListener {
        void onChanged(boolean checked);
    }

    private static class SwitchThumbDrawable extends Drawable {
        private final int widthPx;
        private final int heightPx;
        private final float radiusPx;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SwitchThumbDrawable(int widthPx, int heightPx, float radiusPx, int color) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.radiusPx = radiusPx;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radiusPx, paint);
        }

        @Override public int getIntrinsicWidth() { return widthPx; }
        @Override public int getIntrinsicHeight() { return heightPx; }
        @Override public boolean getPadding(Rect padding) { padding.set(0, 0, 0, 0); return false; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private Drawable makeMaterialSwitchTrack() {
        StateListDrawable sld = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.RECTANGLE);
        on.setCornerRadius(dp(16));
        on.setSize(dp(60), dp(32));
        on.setColor(colPrimary);

        GradientDrawable off = new GradientDrawable();
        off.setShape(GradientDrawable.RECTANGLE);
        off.setCornerRadius(dp(16));
        off.setSize(dp(60), dp(32));
        off.setColor(colSurfaceHigh);
        off.setStroke(dp(2), colOnSurfaceVariant);

        sld.addState(new int[]{android.R.attr.state_checked}, on);
        sld.addState(new int[]{}, off);
        return sld;
    }

    private Drawable makeMaterialSwitchThumb() {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_checked},
                new SwitchThumbDrawable(dp(30), dp(32), dp(12), colOnPrimary));
        sld.addState(new int[]{},
                new SwitchThumbDrawable(dp(30), dp(32), dp(8), colOnSurfaceVariant));
        return sld;
    }

    private LinearLayout buildSwitchSettingCard(String title, String subtitle, boolean initialValue,
                                                SwitchChangeListener onChanged, TextView[] subtitleOut) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(materialRipple(colSurfaceContainer, 14));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(colOnSurface);
        setScaledTextSize(titleTv, 14.5f, 1.8f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText(subtitle);
        subTv.setTextColor(colOnSurfaceVariant);
        setScaledTextSize(subTv, 12f, 1.8f);
        subTv.setPadding(0, dp(2), 0, 0);
        textCol.addView(subTv);
        if (subtitleOut != null && subtitleOut.length > 0) {
            subtitleOut[0] = subTv;
        }

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.rightMargin = dp(12);
        card.addView(textCol, textLp);

        Switch sw = new Switch(this);
        sw.setShowText(false);
        sw.setText("");
        sw.setSwitchMinWidth(dp(60));
        sw.setSplitTrack(false);
        sw.setTrackDrawable(makeMaterialSwitchTrack());
        sw.setThumbDrawable(makeMaterialSwitchThumb());
        sw.setChecked(initialValue);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            buttonView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onChanged.onChanged(isChecked);
        });
        card.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(v -> sw.toggle());
        return card;
    }

    private TextView buildSettingSectionLabel(String title) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(colOnSurface);
        setScaledTextSize(label, 14f, 1.8f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(dp(2), 0, 0, dp(6));
        return label;
    }

    private Button addSegmentedOption(LinearLayout row, String label, boolean selected,
                                      boolean addLeftMargin, boolean addRightMargin,
                                      View.OnClickListener onClick) {
        Button btn = makeMaterialButton(
                label,
                selected ? colPrimary : colSurfaceContainer,
                selected ? colOnPrimary : colOnSurface,
                14, 13.5f, 1.8f, selected);
        btn.setSingleLine(true);
        btn.setAutoSizeTextTypeUniformWithConfiguration(
                9, 14, 1, TypedValue.COMPLEX_UNIT_DIP);
        btn.setPadding(dp(8), dp(10), dp(8), dp(10));
        btn.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (addLeftMargin) lp.leftMargin = dp(4);
        if (addRightMargin) lp.rightMargin = dp(4);
        row.addView(btn, lp);
        return btn;
    }

    private static String formatThousandsExample() {
        if (thousandsGrouping == 1) {
            return useCommaDecimal ? "Beispiel: 2.443,73" : "Beispiel: 2,443.73";
        } else if (thousandsGrouping == 2) {
            return useCommaDecimal ? "Beispiel: 2 443,73" : "Beispiel: 2 443.73";
        }
        return useCommaDecimal ? "Beispiel: 2443,73" : "Beispiel: 2443.73";
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
        desc.setText("Rechenmodus, Zahlenformat, Winkelmaß und Ergebnisanzeige");
        desc.setTextColor(colOnSurfaceVariant);
        setScaledTextSize(desc, 13.5f, 2.0f);
        desc.setPadding(0, dp(4), 0, dp(14));
        col.addView(desc);

        // Setting 0: Number format (Europa vs. US)
        col.addView(buildSettingSectionLabel("Zahlenformat (Punkt & Komma)"));

        LinearLayout nfRow = new LinearLayout(this);
        nfRow.setOrientation(LinearLayout.HORIZONTAL);
        nfRow.setGravity(Gravity.CENTER_VERTICAL);
        addSegmentedOption(nfRow, "Europa (Komma ,)", useCommaDecimal, false, true, v -> {
            if (!useCommaDecimal) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                useCommaDecimal = true;
                applyNumberFormatToEditors();
                saveState();
                rebuildScreen();
            }
        });
        addSegmentedOption(nfRow, "US (Punkt .)", !useCommaDecimal, true, false, v -> {
            if (useCommaDecimal) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                useCommaDecimal = false;
                applyNumberFormatToEditors();
                saveState();
                rebuildScreen();
            }
        });
        col.addView(nfRow);

        View sp0 = new View(this);
        col.addView(sp0, new LinearLayout.LayoutParams(dp(1), dp(12)));

        // Setting 1: Thousands grouping segmented selector
        col.addView(buildSettingSectionLabel("Tausendertrennzeichen"));
        TextView groupExampleTv = new TextView(this);
        groupExampleTv.setText(formatThousandsExample());
        groupExampleTv.setTextColor(colOnSurfaceVariant);
        setScaledTextSize(groupExampleTv, 12f, 1.6f);
        groupExampleTv.setPadding(dp(2), 0, 0, dp(6));
        col.addView(groupExampleTv);

        LinearLayout grpRow = new LinearLayout(this);
        grpRow.setOrientation(LinearLayout.HORIZONTAL);
        grpRow.setGravity(Gravity.CENTER_VERTICAL);
        String[] grpLabels = {"Aus", useCommaDecimal ? "Punkt" : "Komma", "Leerzeichen"};
        for (int gIdx = 0; gIdx < grpLabels.length; gIdx++) {
            final int mode = gIdx;
            addSegmentedOption(grpRow, grpLabels[gIdx], thousandsGrouping == mode, gIdx > 0, gIdx < 2, v -> {
                if (thousandsGrouping != mode) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    thousandsGrouping = mode;
                    saveState();
                    rebuildScreen();
                }
            });
        }
        col.addView(grpRow);

        View sp0b = new View(this);
        col.addView(sp0b, new LinearLayout.LayoutParams(dp(1), dp(12)));

        // Setting 2: Exact results first switch
        LinearLayout exactCard = buildSwitchSettingCard(
                "Exakte Ergebnisse zuerst anzeigen",
                "Brüche, Wurzeln und ganze Zahlen bevorzugt exakt anzeigen",
                showExactFirst,
                checked -> {
                    showExactFirst = checked;
                    saveState();
                },
                null
        );
        col.addView(exactCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View sp1 = new View(this);
        col.addView(sp1, new LinearLayout.LayoutParams(dp(1), dp(10)));

        // Setting 3: Modulo always positive switch
        final TextView[] modSub = new TextView[1];
        String modSubtitle = engine.isModuloAlwaysPositive()
                ? "−7 mod 3 = 2 (immer ≥ 0)"
                : "−7 mod 3 = −1 (Rest mit Vorzeichen)";
        LinearLayout moduloCard = buildSwitchSettingCard(
                "Modulo immer positiv",
                modSubtitle,
                engine.isModuloAlwaysPositive(),
                checked -> {
                    engine.setModuloAlwaysPositive(checked);
                    if (modSub[0] != null) {
                        modSub[0].setText(checked
                                ? "−7 mod 3 = 2 (immer ≥ 0)"
                                : "−7 mod 3 = −1 (Rest mit Vorzeichen)");
                    }
                    saveState();
                },
                modSub
        );
        col.addView(moduloCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View sp2 = new View(this);
        col.addView(sp2, new LinearLayout.LayoutParams(dp(1), dp(12)));

        // Setting 4: Angle mode segmented selector
        col.addView(buildSettingSectionLabel("Standard-Winkelmodus"));
        LinearLayout angRow = new LinearLayout(this);
        angRow.setOrientation(LinearLayout.HORIZONTAL);
        angRow.setGravity(Gravity.CENTER_VERTICAL);
        addSegmentedOption(angRow, "RAD (Bogenmaß)", engine.isRadiansMode(), false, true, v -> {
            if (!engine.isRadiansMode()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                engine.setRadiansMode(true);
                saveState();
                rebuildScreen();
            }
        });
        addSegmentedOption(angRow, "DEG (Grad)", !engine.isRadiansMode(), true, false, v -> {
            if (engine.isRadiansMode()) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                engine.setRadiansMode(false);
                saveState();
                rebuildScreen();
            }
        });
        col.addView(angRow);

        View sp3 = new View(this);
        col.addView(sp3, new LinearLayout.LayoutParams(dp(1), dp(14)));

        // Setting 5: Clear history
        Button clearHist = makeMaterialButton(
                "Verlauf vollständig leeren",
                colSecondaryContainer, colOnSecondaryContainer, 14, 14f, 2.0f);
        clearHist.setPadding(dp(12), dp(12), dp(12), dp(12));
        clearHist.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Verlauf leeren")
                    .setMessage("Möchten Sie den gesamten Rechenverlauf wirklich löschen?")
                    .setPositiveButton("Löschen", (dialog, which) -> {
                        history.clear();
                        saveState();
                        rebuildScreen();
                    })
                    .setNegativeButton("Abbrechen", null)
                    .show();
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

    private void applyNumberFormatToEditors() {
        if (formula != null) formula.setCommaDecimal(useCommaDecimal);
        if (plotFormula1 != null) plotFormula1.setCommaDecimal(useCommaDecimal);
        if (plotFormula2 != null) plotFormula2.setCommaDecimal(useCommaDecimal);
    }

    private static String groupIntegerDigits(String intPart) {
        if (thousandsGrouping == 0 || intPart == null || intPart.isEmpty()) return intPart;
        String sign = "";
        String digits = intPart;
        if (digits.charAt(0) == '+' || digits.charAt(0) == '-') {
            sign = digits.substring(0, 1);
            digits = digits.substring(1);
        }
        if (digits.length() <= 3) return intPart;
        char sep = thousandsGrouping == 1 ? (useCommaDecimal ? '.' : ',') : ' ';
        StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3);
        sb.append(sign);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(sep);
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    private static String groupDigitsForWrap(String intPart) {
        if (intPart == null || intPart.isEmpty()) return "";
        String sign = "";
        String digits = intPart;
        if (digits.charAt(0) == '+' || digits.charAt(0) == '-') {
            sign = digits.substring(0, 1);
            digits = digits.substring(1);
        }
        if (digits.length() <= 3) return sign + digits;
        String sep;
        if (thousandsGrouping == 2) {
            sep = " ";
        } else if (thousandsGrouping == 1) {
            sep = (useCommaDecimal ? "." : ",") + "\u200B";
        } else {
            sep = "\u200B";
        }
        StringBuilder sb = new StringBuilder(digits.length() * 2);
        sb.append(sign);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(sep);
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    private static String groupFractionDigits(String fracPart) {
        if (fracPart == null || fracPart.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(fracPart.length() + fracPart.length() / 3);
        for (int i = 0; i < fracPart.length(); i++) {
            if (i > 0 && i % 3 == 0) sb.append(' ');
            sb.append(fracPart.charAt(i));
        }
        return sb.toString();
    }

    public static String formatExactDisplayNumber(String numStr) {
        if (numStr == null || numStr.isEmpty()) return "";
        if (numStr.contains("[")) {
            return numStr;
        }
        int assignIdx = numStr.indexOf(":=");
        if (assignIdx >= 0) {
            String lhs = numStr.substring(0, assignIdx + 2).trim();
            String rhs = numStr.substring(assignIdx + 2).trim();
            return lhs + " " + formatExactDisplayNumber(rhs);
        }
        if (numStr.contains("i")) {
            return useCommaDecimal
                    ? numStr.replaceAll("(\\d)\\.(\\d)", "$1,$2")
                    : FormulaEditor.localizeDecimalLiterals(numStr, false);
        }
        String clean = numStr.trim().replace(',', '.');
        if (clean.matches("[+-]?\\d+")) {
            return groupDigitsForWrap(clean);
        }
        if (clean.matches("^[+-]?\\d+(?:\\.\\d+)?[eE][+-]?\\d+$")) {
            int eIdx = Math.max(clean.indexOf('e'), clean.indexOf('E'));
            String mant = clean.substring(0, eIdx);
            try {
                int exp = Integer.parseInt(clean.substring(eIdx + 1));
                int mDot = mant.indexOf('.');
                String mInt = mDot >= 0 ? mant.substring(0, mDot) : mant;
                String mFrac = mDot >= 0 ? mant.substring(mDot + 1) : "";
                String sign = "";
                if (mInt.startsWith("+") || mInt.startsWith("-")) {
                    sign = mInt.substring(0, 1);
                    mInt = mInt.substring(1);
                }
                String decSep = useCommaDecimal ? "," : ".";
                StringBuilder res = new StringBuilder();
                res.append(sign).append(mInt);
                if (!mFrac.isEmpty()) {
                    res.append(decSep).append(groupFractionDigits(mFrac));
                }
                return res.append(" × 10").append(toSuperscript(exp)).toString();
            } catch (NumberFormatException ignored) {
            }
        }
        if (clean.matches("[+-]?\\d+\\.\\d+")) {
            int dot = clean.indexOf('.');
            String intPart = groupDigitsForWrap(clean.substring(0, dot));
            String fracPart = groupFractionDigits(clean.substring(dot + 1));
            String decSep = useCommaDecimal ? "," : ".";
            return intPart + decSep + fracPart;
        }
        return formatUnchainedSuperscripts(numStr);
    }

    public static String formatDisplayNumber(String numStr) {
        if (numStr == null || numStr.isEmpty()) return "";
        if (numStr.contains("[")) {
            return numStr;
        }
        int assignIdx = numStr.indexOf(":=");
        if (assignIdx >= 0) {
            String lhs = numStr.substring(0, assignIdx + 2).trim();
            String rhs = numStr.substring(assignIdx + 2).trim();
            return lhs + " " + formatDisplayNumber(rhs);
        }
        if (numStr.contains("i")) {
            return useCommaDecimal
                    ? numStr.replaceAll("(\\d)\\.(\\d)", "$1,$2")
                    : FormulaEditor.localizeDecimalLiterals(numStr, false);
        }
        String clean = numStr.trim().replace(',', '.');
        if (clean.matches("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")) {
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(clean);
                if (bd.signum() == 0) return "0";
                int exp = bd.precision() - bd.scale() - 1;
                if (exp >= 6 || exp <= -5 || clean.contains("e") || clean.contains("E")) {
                    java.math.BigDecimal mant = bd.movePointLeft(exp).setScale(6, java.math.RoundingMode.HALF_UP);
                    if (mant.abs().compareTo(java.math.BigDecimal.TEN) >= 0) {
                        mant = mant.movePointLeft(1).setScale(6, java.math.RoundingMode.HALF_UP);
                        exp += 1;
                    }
                    String mStr = mant.toPlainString();
                    int mDot = mStr.indexOf('.');
                    String mInt = mDot >= 0 ? mStr.substring(0, mDot) : mStr;
                    String mFrac = mDot >= 0 ? mStr.substring(mDot + 1) : "000000";
                    while (mFrac.length() < 6) mFrac += "0";
                    String decSep = useCommaDecimal ? "," : ".";
                    return mInt + decSep + mFrac.substring(0, 3) + " " + mFrac.substring(3, 6) + " × 10" + toSuperscript(exp);
                }
            } catch (Exception ignored) {
            }
        }
        if (clean.matches("[+-]?\\d+")) {
            return groupIntegerDigits(clean);
        }
        int dot = clean.indexOf('.');
        if (dot >= 0 && clean.matches("[+-]?\\d+\\.\\d+")) {
            String fracPart = clean.substring(dot + 1);
            if (fracPart.length() > 10) {
                try {
                    double d = Double.parseDouble(clean);
                    if (d != 0 && Math.abs(d) < 1e-4) {
                        return formatDisplayNumber(String.format(java.util.Locale.US, "%.10g", d));
                    }
                    clean = String.format(java.util.Locale.US, "%.10f", d)
                            .replaceAll("(\\.\\d*?[1-9])0+$", "$1")
                            .replaceAll("\\.0+$", "");
                    dot = clean.indexOf('.');
                    if (dot < 0) return groupIntegerDigits(clean);
                } catch (Exception ignored) {
                }
            }
            String intPart = groupIntegerDigits(clean.substring(0, dot));
            String decSep = useCommaDecimal ? "," : ".";
            return intPart + decSep + groupFractionDigits(clean.substring(dot + 1));
        }
        return formatUnchainedSuperscripts(numStr);
    }

    private static String toSuperscript(int n) {
        String s = Integer.toString(n);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '-': sb.append('⁻'); break;
                case '+': sb.append('⁺'); break;
                case '0': sb.append('⁰'); break;
                case '1': sb.append('¹'); break;
                case '2': sb.append('²'); break;
                case '3': sb.append('³'); break;
                case '4': sb.append('⁴'); break;
                case '5': sb.append('⁵'); break;
                case '6': sb.append('⁶'); break;
                case '7': sb.append('⁷'); break;
                case '8': sb.append('⁸'); break;
                case '9': sb.append('⁹'); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    private View createFractionView(String num, String den) {
        return createFractionView(num, den, 18f);
    }

    private View createFractionView(String num, String den, float spSize) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);

        boolean isNegative = num.startsWith("-");
        String cleanNum = isNegative ? num.substring(1) : num;

        if (isNegative) {
            TextView minusTv = new TextView(this);
            minusTv.setText("− ");
            minusTv.setTextColor(colOnSurface);
            setScaledTextSize(minusTv, spSize + 2f, 2.0f);
            minusTv.setTypeface(Typeface.DEFAULT_BOLD);
            minusTv.setGravity(Gravity.CENTER);
            outer.addView(minusTv);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView numTv = new TextView(this);
        numTv.setText(cleanNum);
        numTv.setTextColor(colOnSurface);
        numTv.setGravity(Gravity.CENTER);
        setScaledTextSize(numTv, spSize, 2.0f);
        numTv.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(numTv);

        int maxLen = Math.max(cleanNum.length(), den.length());
        int lineW = Math.max(dp(Math.round(maxLen * spSize * 0.65f + 8)), dp(28));

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
        setScaledTextSize(denTv, spSize, 2.0f);
        denTv.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(denTv);

        outer.addView(box);
        return outer;
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
        return makeMaterialButton(text, bg, fg, cornerDp, spSize, 2.0f, false);
    }

    private Button makeMaterialButton(String text, int bg, int fg, int cornerDp, float spSize, float maxScale) {
        return makeMaterialButton(text, bg, fg, cornerDp, spSize, maxScale, false);
    }

    private Button makeMaterialButton(String text, int bg, int fg, int cornerDp, float spSize, float maxScale, boolean bold) {
        Button b = new Button(this);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(fg);
        setScaledTextSize(b, spSize, maxScale);
        b.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setPadding(dp(2), dp(1), dp(2), dp(1));
        b.setBackground(materialRipple(bg, cornerDp));
        return b;
    }

    private RippleDrawable materialRipple(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                roundedRect(fill, radius), roundedRect(Color.WHITE, radius));
    }


}

