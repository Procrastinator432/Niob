package com.niobcalculator.materialyou;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.RectF;
import android.content.Context;
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
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.TextPaint;
import android.text.SpannableString;
import android.text.Spanned;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Android 12 (API 31+) Material You (Monet Dynamic Color) Scientific Calculator.
 * 100% Offline: Uses Android 12 system_accent1_* and system_neutral1_* wallpaper colors.
 */
public class MainActivity extends Activity {

    private final CalculatorEngine engine = new CalculatorEngine();
    private final CategoryDef[] constantCategories = getConstantCategories();
    private boolean moduloPositive = true;
    private boolean showExactFirst = true;

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

    private static class HistoryEntry {
        String expr;
        String exactText;
        String decimalText;
        String reportText;
        boolean showingExact;
        String formulaLayout = "";

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

    private TextView previewDisplayView;
    private FrameLayout previewMatrix;

    private ScrollView historyScrollView;
    private LinearLayout historyListContainer;
    private FrameLayout keyboardBodyContainer;
    private LinearLayout sectionHeaderRow;
    private FormulaEditor plotFormula1;
    private FormulaEditor plotFormula2;
    private CalculatorEngine.PlotFunction compiledPlotFn1 = null;
    private CalculatorEngine.PlotFunction compiledPlotFn2 = null;
    private Plotter2DView plotView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        resolveAndroid12DynamicColors();

        // Load saved state (Preferences & History & Calculations & UI Drafts)
        android.content.SharedPreferences prefs = getSharedPreferences("niob_calculator", MODE_PRIVATE);
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
            if ("__UNSET__".equals(vStr)) {
                engine.unsetVariable(varName);
            } else if (vStr != null) {
                engine.setUserVariable(varName, engine.deserializeValue(vStr));
            }
        }

        formula = new FormulaEditor(this, colOnSurface, colPrimary, editor -> {
            saveDraft();
            updatePreviewOnly();
        }, () -> handleKeyPress("="), MULTI_CHAR_TOKENS);
        formula.restore(prefs.getString("formula_draft", ""), prefs.getString("current_input", ""));

        plotFormula1 = makePlotFormula("f₁(x)", colPrimary,
                prefs.getString("plot_layout_1", ""), prefs.getString("plot_expr_1", "sin(x) * cos(x/2)"));
        plotFormula2 = makePlotFormula("f₂(x)", colOnSecondaryContainer,
                prefs.getString("plot_layout_2", ""), prefs.getString("plot_expr_2", "x^2 / 4 - 2"));
        compiledPlotFn1 = engine.compilePlotFunction(plottableExpression(plotFormula1));
        compiledPlotFn2 = engine.compilePlotFunction(plottableExpression(plotFormula2));

        int histCount = prefs.getInt("history_count", -1);
        if (histCount >= 0) {
            for (int i = 0; i < histCount; i++) {
                String expr = prefs.getString("hist_expr_" + i, "");
                String ex = prefs.getString("hist_ex_" + i, "");
                String dec = prefs.getString("hist_dec_" + i, "");
                String rep = prefs.getString("hist_rep_" + i, "");
                boolean showEx = prefs.getBoolean("hist_show_ex_" + i, showExactFirst);
                if (!expr.isEmpty()) {
                    HistoryEntry entry = new HistoryEntry(expr, ex, dec, rep.isEmpty() ? null : rep, showEx);
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
        setContentView(rootContainer);

        rebuildScreen();
    }

    private void saveDraft() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences("niob_calculator", MODE_PRIVATE).edit();
        if (formula != null) {
            ed.putString("current_input", formula.expression());
            ed.putString("formula_draft", formula.layoutState());
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

    private void saveHistory() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences("niob_calculator", MODE_PRIVATE).edit();
        ed.putInt("history_count", history.size());
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry h = history.get(i);
            ed.putString("hist_expr_" + i, h.expr);
            ed.putString("hist_layout_" + i, h.formulaLayout);
            ed.putString("hist_ex_" + i, h.exactText);
            ed.putString("hist_dec_" + i, h.decimalText);
            ed.putString("hist_rep_" + i, h.reportText != null ? h.reportText : "");
            ed.putBoolean("hist_show_ex_" + i, h.showingExact);
        }
        ed.apply();
    }

    private void saveState() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences("niob_calculator", MODE_PRIVATE).edit();
        ed.putBoolean("show_exact_first", showExactFirst);
        ed.putBoolean("show_more_variables", showMoreVariables);
        ed.putBoolean("radians_mode", engine.isRadiansMode());
        ed.putBoolean("modulo_always_positive", engine.isModuloAlwaysPositive());
        ed.putString("ans_value", engine.serializeValue(engine.getLastAns()));
        if (formula != null) {
            ed.putString("current_input", formula.expression());
            ed.putString("formula_draft", formula.layoutState());
        }
        if (plotFormula1 != null) {
            ed.putString("plot_expr_1", plotFormula1.expression());
            ed.putString("plot_layout_1", plotFormula1.layoutState());
        }
        if (plotFormula2 != null) {
            ed.putString("plot_expr_2", plotFormula2.expression());
            ed.putString("plot_layout_2", plotFormula2.layoutState());
        }

        Map<String, CalculatorEngine.Value> userVars = engine.getUserVariables();
        for (String varName : CalculatorEngine.USER_VARIABLES) {
            if (userVars.containsKey(varName)) {
                ed.putString("var_" + varName, engine.serializeValue(userVars.get(varName)));
            } else {
                ed.putString("var_" + varName, "__UNSET__");
            }
        }

        ed.putInt("history_count", history.size());
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry h = history.get(i);
            ed.putString("hist_expr_" + i, h.expr);
            ed.putString("hist_layout_" + i, h.formulaLayout);
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
                String badge = r.converged ? "konvergiert" : "langsame Konv.";
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
                if (text.equals(",")) {
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

            LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.36f);
            col.addView(historyScroll, histLp);

            LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            col.addView(inputCard, inLp);

            LinearLayout.LayoutParams upperLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f);
            kbContainer.addView(upperKb, upperLp);

            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.55f);
            kbContainer.addView(numpadCard, numLp);

            LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.64f);
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
        return sb.toString().replaceAll(" {2,}", " ").trim();
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
        String toInsert;
        if (isComposite && (precededByOp || (!beforeCursor.isEmpty() && !beforeCursor.endsWith("(")))) {
            toInsert = "(" + rawVal + ")";
        } else {
            toInsert = rawVal;
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

            Button exactBadge = makeMaterialButton(
                    item.showingExact ? "dezimal" : "exakt",
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
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                item.showingExact = !item.showingExact;
                saveState();
                refreshHistoryViews(false);
            });
            resRow.addView(exactBadge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView eqTv = new TextView(this);
            eqTv.setText("  =  ");
            eqTv.setTextColor(colPrimary);
            setScaledTextSize(eqTv, 18f, 2.0f);
            eqTv.setTypeface(Typeface.DEFAULT_BOLD);
            resRow.addView(eqTv);

            View resSpacer = new View(this);
            resRow.addView(resSpacer, new LinearLayout.LayoutParams(0, 0, 1f));

            String rawShown = item.showingExact ? item.exactText : item.decimalText;
            String cleanFrac = rawShown != null ? rawShown.replace(" ", "") : "";
            boolean isFractionResult = item.showingExact && cleanFrac.matches("^-?\\d+/\\d+$");

            View matrixResult = formula.matrixResult(rawShown, 15f, () -> insertHistoryValue(item.exactText));
            if (matrixResult != null) {
                resRow.addView(matrixResult);
            } else if (isFractionResult) {
                String[] fracParts = cleanFrac.split("/");
                View fracResultView = createFractionView(fracParts[0].trim(), fracParts[1].trim(), 20f);
                fracResultView.setOnClickListener(v -> {
                    insertHistoryValue(item.exactText);
                });
                LinearLayout.LayoutParams fracLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fracLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                resRow.addView(fracResultView, fracLp);
            } else {
                TextView resTv = new TextView(this);
                String shown = formatDisplayNumber(rawShown);
                resTv.setText(shown);
                resTv.setGravity(Gravity.END);
                resTv.setTextColor(colOnSurface);
                setScaledTextSize(resTv, 23f, 2.0f);
                resTv.setTypeface(Typeface.DEFAULT_BOLD);
                resTv.setOnClickListener(v -> {
                    insertHistoryValue(item.exactText);
                });
                resRow.addView(resTv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        formula.clear();
    }

    private void updatePreviewOnly() {
        if (previewDisplayView == null) return;
        previewDisplayView.setVisibility(View.VISIBLE);
        previewMatrix.removeAllViews();
        previewMatrix.setVisibility(View.GONE);
        if (formula.hasMissingField()) {
            previewDisplayView.setText("");
            return;
        }
        String cur = formula.expression().trim();
        if (cur.isEmpty()) {
            String ansStr = engine.formatAns(false);
            if (ansStr != null && !ansStr.isEmpty() && !ansStr.equals("0")) {
                previewDisplayView.setText("ANS = " + ansStr);
            } else {
                previewDisplayView.setText("ANS = 0");
            }
            previewDisplayView.setTextColor(colPrimary);
            return;
        }

        try {
            CalculatorEngine.Result result = engine.evaluate(cur);
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
            if (result.exactText != null && !result.exactText.isEmpty() && !result.exactText.equals(result.decimalText)) {
                previewDisplayView.setText("= " + dec + "  (" + result.exactText + ")");
            } else {
                previewDisplayView.setText("= " + dec);
            }
            previewDisplayView.setTextColor(colPrimary);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Division durch Null") || msg.contains("Division by zero")) {
                previewDisplayView.setText("⚠ Division durch Null");
                previewDisplayView.setTextColor(0xFFFFB4AB);
            } else {
                boolean isIncomplete = cur.endsWith("+") || cur.endsWith("-") || cur.endsWith("*")
                        || cur.endsWith("/") || cur.endsWith("÷") || cur.endsWith("×") || cur.endsWith("−")
                        || cur.endsWith("^") || cur.endsWith("(") || cur.endsWith(",") || cur.endsWith(":=");
                if (isIncomplete) {
                    previewDisplayView.setText("");
                } else {
                    previewDisplayView.setText("⚠ Ungültiger Ausdruck");
                    previewDisplayView.setTextColor(colOnSurfaceVariant);
                }
            }
        }
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

        // Scientific exponent sign check: e.g. "1e", "2.5e"
        if (trimmedBefore.matches(".*\\d+(\\.\\d+)?[eE]$") && (op.equals("−") || op.equals("+") || op.equals("-"))) {
            // Exponent sign: NO SPACES!
            String expSign = op.equals("+") ? "+" : "−";
            insertAtCursor(expSign);
            return;
        }
        if (trimmedBefore.matches(".*\\d+(\\.\\d+)?[eE][−\\-\\+]$") && (op.equals("−") || op.equals("+") || op.equals("-"))) {
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
            String expression = formula.expression();
            if (!expression.trim().isEmpty()) {
                try {
                    CalculatorEngine.Result result = engine.evaluateAndCommit(expression);
                    String rep = getIntegrationReportText(expression);
                    HistoryEntry entry = new HistoryEntry(expression, result.exactText, result.decimalText,
                            rep, showExactFirst);
                    entry.formulaLayout = formula.layoutState();
                    history.add(entry);
                    saveHistory();
                    saveDraft();
                    clearInput();
                    refreshHistoryViews(true);
                } catch (Exception e) {
                    formula.performHapticFeedback(HapticFeedbackConstants.REJECT);
                    updatePreviewOnly();
                }
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
        editor.enableSystemKeyboard(label);
        editor.restore(layout, expression);
        return editor;
    }

    private void plotFormulaChanged(FormulaEditor editor) {
        String expression = plottableExpression(editor);
        String error = null;
        if (!expression.isEmpty()) {
            try {
                engine.validatePlotSyntax(expression);
            } catch (Exception e) {
                error = "Ungültiger Ausdruck";
            }
        }
        editor.setInputError(error);
        if (editor == plotFormula1) {
            compiledPlotFn1 = (error == null && !expression.isEmpty()) ? engine.compilePlotFunction(expression) : null;
        } else if (editor == plotFormula2) {
            compiledPlotFn2 = (error == null && !expression.isEmpty()) ? engine.compilePlotFunction(expression) : null;
        }
        saveDraft();
        invalidatePlot();
    }

    private static String plottableExpression(FormulaEditor editor) {
        return editor.hasMissingField() ? "" : editor.expression().trim();
    }

    private void invalidatePlot() {
        if (plotView != null) plotView.invalidate();
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
        addPlotFormula(formulas, "f₂(x)", plotFormula2, colOnSecondaryContainer, formulaSize, landscape);
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
                finishPlotEditing();
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

            if (compiledPlotFn1 == null) {
                String e1 = plottableExpression(plotFormula1);
                if (!e1.isEmpty()) compiledPlotFn1 = engine.compilePlotFunction(e1);
            }
            if (compiledPlotFn2 == null) {
                String e2 = plottableExpression(plotFormula2);
                if (!e2.isEmpty()) compiledPlotFn2 = engine.compilePlotFunction(e2);
            }
            drawCurve(canvas, compiledPlotFn1, curve1Paint, w, cx, cy);
            drawCurve(canvas, compiledPlotFn2, curve2Paint, w, cx, cy);
        }

        private void drawCurve(Canvas canvas, CalculatorEngine.PlotFunction fn, Paint paint, int w, float cx, float cy) {
            if (fn == null) return;
            float prevX = 0f, prevY = 0f;
            boolean hasPrev = false;
            for (int px = 0; px <= w; px += 3) {
                double xv = centerX + (px - cx) / scale;
                try {
                    double yv = fn.evaluate(xv);
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

        ConstantDef(String symbol, String token, String name, String displayValue) {
            this.symbol = symbol;
            this.token = token;
            this.name = name;
            this.displayValue = displayValue;
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
                        new ConstantDef("k_B", "k_B", "Boltzmann-Konstante", "1,380 6×10⁻²³"),
                        new ConstantDef("ε₀", "eps0", "Elektrische Feldkonstante", "8,854 2×10⁻¹²"),
                        new ConstantDef("e⁻", "e_charge", "Elementarladung", "1,602 2×10⁻¹⁹"),
                        new ConstantDef("α", "alpha_fs", "Feinstrukturkonstante", "7,297 4×10⁻³"),
                        new ConstantDef("G", "G", "Gravitationskonstante", "6,673 8×10⁻¹¹"),
                        new ConstantDef("g", "g", "Gravitationsbeschleunigung", "9,806 7"),
                        new ConstantDef("c₀", "c0", "Lichtgeschwindigkeit", "2,997 9×10⁸"),
                        new ConstantDef("μ₀", "mu0", "Magnetische Feldkonstante", "12,566×10⁻⁷"),
                        new ConstantDef("h", "h", "Plancksches Wirkungsquantum", "6,626 1×10⁻³⁴")
                }),
                new CategoryDef("Elektromagnetische Konstanten", new ConstantDef[]{
                        new ConstantDef("Z₀", "Z0", "Wellenwiderstand des Vakuums", "376,73"),
                        new ConstantDef("Φ₀", "Phi0", "Magnetisches Flussquant", "2,067 8×10⁻¹⁵"),
                        new ConstantDef("K_J", "K_J", "Josephson-Konstante", "4,835×10¹⁴"),
                        new ConstantDef("R_K", "R_K", "Von-Klitzing-Konstante", "25813,0"),
                        new ConstantDef("G₀", "G0", "Leitwertquant", "7,748 1×10⁻⁵"),
                        new ConstantDef("σ", "sigma", "Stefan-Boltzmann-Konstante", "5,670 4×10⁻⁸"),
                        new ConstantDef("R_∞", "R_inf", "Rydberg-Konstante", "1,097 4×10⁷"),
                        new ConstantDef("E_h", "E_h", "Hartree-Energie", "4,359 7×10⁻¹⁸"),
                        new ConstantDef("μ_B", "mu_B", "Bohrsches Magneton", "1,399 6×10¹⁰"),
                        new ConstantDef("μ_e", "mu_e", "Magnetisches Moment d. Elektrons", "−9,284 8×10⁻²⁴")
                }),
                new CategoryDef("Chemische Konstanten", new ConstantDef[]{
                        new ConstantDef("N_A", "N_A", "Avogadro-Konstante", "6,022 1×10²³"),
                        new ConstantDef("m_u", "m_u", "Atomare Masseneinheit", "1,660 5×10⁻²⁷"),
                        new ConstantDef("F", "F", "Faraday-Konstante", "96485,0"),
                        new ConstantDef("R", "R", "Universelle Gaskonstante", "8,314 5"),
                        new ConstantDef("m_e", "m_e", "Elektronenmasse", "9,109 4×10⁻³¹"),
                        new ConstantDef("m_p", "m_p", "Protonenmasse", "1,674 9×10⁻²⁷"),
                        new ConstantDef("m_n", "m_n", "Neutronenmasse", "1,672 6×10⁻²⁷")
                }),
                new CategoryDef("Planck- & Naturkonstanten", new ConstantDef[]{
                        new ConstantDef("m_P", "m_P", "Planck-Masse", "2,176 5×10⁻⁸"),
                        new ConstantDef("t_P", "t_P", "Planck-Zeit", "5,391 1×10⁻⁴⁴"),
                        new ConstantDef("l_P", "l_P", "Planck-Länge", "1,616 2×10⁻³⁵")
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
                    valTv.setText(item.displayValue);
                    valTv.setTextColor(colOnSurfaceVariant);
                    valTv.setGravity(Gravity.CENTER);
                    valTv.setSingleLine(true);
                    setScaledTextSize(valTv, 11f, 1.3f);
                    LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
                "Verlauf vollständig leeren",
                colSecondaryContainer, colOnSecondaryContainer, 14, 14f, 2.0f);
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

    public static String formatDisplayNumber(String numStr) {
        if (numStr == null || numStr.isEmpty()) return "";
        if (numStr.contains("i") || numStr.contains("[")) {
            return numStr;
        }
        if (numStr.matches("^-?\\d+(\\.\\d+)?[eE][+-]?\\d+$")) {
            String[] p = numStr.split("[eE]");
            String mantissa = formatDisplayNumber(p[0]);
            try {
                int exp = Integer.parseInt(p[1]);
                return mantissa + " × 10" + toSuperscript(exp);
            } catch (Exception ignored) {
                return numStr;
            }
        }
        String clean = numStr.replace(',', '.');
        int dot = clean.indexOf('.');
        if (dot >= 0 && clean.matches("[+-]?\\d+\\.\\d+")) {
            String fracPart = clean.substring(dot + 1);
            if (fracPart.length() > 6) {
                try {
                    double d = Double.parseDouble(clean);
                    if (d != 0 && Math.abs(d) < 1e-4) {
                        return formatDisplayNumber(String.format(java.util.Locale.US, "%.6g", d));
                    }
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

