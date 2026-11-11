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
    private EditText inputDisplayView;
    private TextView previewDisplayView;
    private Button angleBadgeView;
    private View cursorView;
    private ScrollView historyScrollView;
    private LinearLayout historyListContainer;
    private FrameLayout keyboardBodyContainer;
    private LinearLayout sectionHeaderRow;
    private boolean isSyncingInput = false;
    private String plotExpr1 = "sin(x) * cos(x/2)";
    private String plotExpr2 = "x^2 / 4 - 2";

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
        android.content.SharedPreferences.Editor ed = getSharedPreferences("niob_calculator", MODE_PRIVATE).edit();
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

        // 1. Top App Bar (only shown on non-calculator tabs: 2D-Plot, Konstanten, Setup; no redundant back button)
        if (activeTab != 0) {
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setBackgroundColor(colSurfaceContainer);
            int padH = dp(16);
            int padV = dp(isLandscape ? 8 : 12);
            topBar.setPadding(padH, padV, padH, padV);

            TextView titleView = new TextView(this);
            String titleText = (activeTab == 1) ? "2D-Plotter" : (activeTab == 2 ? "Physikalische Konstanten" : "Einstellungen");
            titleView.setText(titleText);
            titleView.setTextColor(colOnSurface);
            setScaledTextSize(titleView, isLandscape ? 16f : 18f, 2.0f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            topBar.addView(titleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
                {"\u00f7", "/"},
                {"\u00d7", "*"},
                {"+", "+"},
                {"\u2212", "-"}
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
                    currentInput = "";
                    saveState();
                    updateInputAndPreview();
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

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText inputEt = new EditText(this);
        inputDisplayView = inputEt;
        inputEt.setBackground(null);
        inputEt.setPadding(0, 0, dp(6), 0);
        setScaledTextSize(inputEt, isLandscape ? 19f : 24f, 2.0f);
        inputEt.setTextColor(colOnSurface);
        inputEt.setTypeface(Typeface.DEFAULT_BOLD);
        inputEt.setShowSoftInputOnFocus(false);
        inputEt.setTextIsSelectable(true);
        inputEt.setCursorVisible(true);
        inputEt.setSingleLine(true);
        inputEt.setHorizontallyScrolling(true);
        inputEt.setContentDescription("input_expression");
        inputEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isSyncingInput) return;
                currentInput = s.toString();
                saveState();
                updatePreviewOnly();
            }
        });
        inputRow.addView(inputEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Persistent RAD/DEG Indicator Badge in the input bar
        Button angleBadge = makeMaterialButton(
                engine.isRadiansMode() ? "RAD" : "DEG",
                colSurfaceHigh,
                colPrimary,
                8,
                8.5f,
                1.1f
        );
        angleBadgeView = angleBadge;
        angleBadge.setSingleLine(true);
        angleBadge.setIncludeFontPadding(false);
        angleBadge.setPadding(dp(6), dp(1), dp(6), dp(1));
        angleBadge.setMinimumHeight(dp(19));
        angleBadge.setMinimumWidth(dp(32));
        angleBadge.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            engine.setRadiansMode(!engine.isRadiansMode());
            angleBadge.setText(engine.isRadiansMode() ? "RAD" : "DEG");
            saveState();
            updateInputAndPreview();
            refreshKeyboardSection();
        });
        inputRow.addView(angleBadge);
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

    private void insertHistoryValue(String rawVal) {
        if (rawVal == null || rawVal.isEmpty()) return;
        int start = 0;
        String curText = "";
        if (inputDisplayView != null) {
            start = Math.max(0, inputDisplayView.getSelectionStart());
            curText = inputDisplayView.getText() != null ? inputDisplayView.getText().toString() : "";
        }
        char prevChar = (start > 0 && start <= curText.length()) ? curText.charAt(start - 1) : ' ';
        boolean precededByOp = (prevChar == '/' || prevChar == '*' || prevChar == '+' || prevChar == '-' || prevChar == '^' || prevChar == '÷' || prevChar == '×' || prevChar == '−');
        boolean isComposite = rawVal.contains("/") || rawVal.contains("+") || rawVal.contains("-") || rawVal.contains(" ");
        String toInsert = (precededByOp && isComposite) ? ("(" + rawVal + ")") : rawVal;
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

            // Row 1: Expression (vertical fraction if simple fraction a/b)
            boolean isSimpleFraction = item.expr.matches("^-?\\d+/\\d+$");
            if (isSimpleFraction) {
                String[] parts = item.expr.split("/");
                View fracView = createFractionView(parts[0], parts[1]);
                fracView.setOnClickListener(v -> {
                    insertAtCursor(item.expr);
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
                    insertAtCursor(item.expr);
                });
                LinearLayout.LayoutParams expLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                expLp.bottomMargin = dp(4);
                itemBox.addView(exprTv, expLp);
            }

            // Row 2: Left-aligned pill badge "> dezi." / "> exakt" + "=" + right-aligned result
            LinearLayout resRow = new LinearLayout(this);
            resRow.setOrientation(LinearLayout.HORIZONTAL);
            resRow.setGravity(Gravity.CENTER_VERTICAL);

            Button exactBadge = makeMaterialButton(
                    item.showingExact ? "dezi." : "exakt",
                    colSurfaceHigh,
                    colPrimary,
                    9,
                    8.5f,
                    1.1f,
                    false
            );
            exactBadge.setSingleLine(true);
            exactBadge.setIncludeFontPadding(false);
            exactBadge.setMinimumWidth(dp(46));
            exactBadge.setMinimumHeight(dp(19));
            exactBadge.setPadding(dp(6), 0, dp(6), 0);
            exactBadge.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                item.showingExact = !item.showingExact;
                saveState();
                refreshHistoryViews(false);
            });
            resRow.addView(exactBadge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(19)));

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

            if (isFractionResult) {
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
        if (text == null || text.isEmpty()) return;
        if (inputDisplayView != null) {
            int start = Math.max(0, inputDisplayView.getSelectionStart());
            int end = Math.max(0, inputDisplayView.getSelectionEnd());
            if (start > end) { int tmp = start; start = end; end = tmp; }
            Editable editable = inputDisplayView.getText();
            if (editable != null) {
                isSyncingInput = true;
                try {
                    editable.replace(start, end, text);
                    inputDisplayView.setSelection(start + text.length());
                } finally {
                    isSyncingInput = false;
                }
                currentInput = inputDisplayView.getText().toString();
            } else {
                currentInput += text;
            }
        } else {
            currentInput += text;
        }
        saveState();
        updatePreviewOnly();
    }

    private void deleteAtCursor() {
        if (inputDisplayView != null) {
            int start = Math.max(0, inputDisplayView.getSelectionStart());
            int end = Math.max(0, inputDisplayView.getSelectionEnd());
            if (start > end) { int tmp = start; start = end; end = tmp; }
            Editable editable = inputDisplayView.getText();
            if (editable != null && editable.length() > 0) {
                isSyncingInput = true;
                try {
                    if (start != end) {
                        editable.delete(start, end);
                        inputDisplayView.setSelection(start);
                    } else if (start > 0) {
                        String sub = editable.toString().substring(0, start);
                        int delLen = 1;
                        for (String tok : MULTI_CHAR_TOKENS) {
                            if (sub.endsWith(tok)) {
                                delLen = tok.length();
                                break;
                            }
                        }
                        editable.delete(start - delLen, start);
                        inputDisplayView.setSelection(start - delLen);
                    }
                } finally {
                    isSyncingInput = false;
                }
                currentInput = inputDisplayView.getText().toString();
            }
        } else {
            currentInput = deleteLastToken(currentInput);
        }
        saveState();
        updatePreviewOnly();
    }

    private void clearInput() {
        if (inputDisplayView != null) {
            isSyncingInput = true;
            try {
                inputDisplayView.setText("");
            } finally {
                isSyncingInput = false;
            }
        }
        currentInput = "";
        saveState();
        updatePreviewOnly();
    }

    private void updatePreviewOnly() {
        if (previewDisplayView == null) return;
        if (angleBadgeView != null) {
            angleBadgeView.setText(engine.isRadiansMode() ? "RAD" : "DEG");
        }
        String cur = currentInput != null ? currentInput.trim() : "";
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
            CalculatorEngine.Result result = engine.evaluate(currentInput);
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

    private void updateInputAndPreview() {
        if (inputDisplayView != null) {
            String cur = currentInput != null ? currentInput : "";
            if (!inputDisplayView.getText().toString().equals(cur)) {
                isSyncingInput = true;
                try {
                    inputDisplayView.setText(cur);
                    inputDisplayView.setSelection(cur.length());
                } finally {
                    isSyncingInput = false;
                }
            }
        }
        updatePreviewOnly();
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
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        KeyDef[][][] sections = {
                // 0: ( ) √ ^
                {
                        {k("("), k(")"), k("π", "pi"), k("e")},
                        {k("x²", "^2"), k("xⁿ", "^("), k("√x", "sqrt("), k("ⁿ√x", "nthroot(")},
                        {k("ln", "ln("), k("logₐ(x)", "log("), k("10ⁿ", "10^("), k("ans", "ans")}
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
                        {k("[ Matrix ]", "SHOW_MATRIX_DIALOG"), k(",", ", "), k(")", ")"), k("det", "det(")}
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
                        {k("|x|", "abs("), k("⌊x⌋", "floor("), k("⌈x⌉", "ceil("), k("round", "round(")},
                        {k("gcd", "gcd("), k("lcm", "lcm("), k("sgn", "sign("), k("exp", "exp(")},
                        {k(",", ", "), k(")", ")"), k("inv", "inv("), k("trans", "trans(")}
                }
        };

        if (activeSection == 1) {
            LinearLayout angleBar = new LinearLayout(this);
            angleBar.setOrientation(LinearLayout.HORIZONTAL);
            angleBar.setGravity(Gravity.CENTER);
            angleBar.setPadding(dp(4), 0, dp(4), dp(2));

            Button openParenBtn = makeMaterialButton("(", Color.TRANSPARENT, colOnSurface, 8, 13f, 1.2f);
            openParenBtn.setPadding(dp(8), 0, dp(8), 0);
            openParenBtn.setMinimumHeight(dp(18));
            openParenBtn.setMinimumWidth(dp(30));
            openParenBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                handleKeyPress("(");
            });
            angleBar.addView(openParenBtn);

            View s1 = new View(this);
            angleBar.addView(s1, new LinearLayout.LayoutParams(dp(8), dp(1)));

            LinearLayout segPill = new LinearLayout(this);
            segPill.setOrientation(LinearLayout.HORIZONTAL);
            segPill.setBackground(roundedRect(colSurfaceHigh, 8));
            segPill.setPadding(dp(1), dp(1), dp(1), dp(1));

            boolean isRad = engine.isRadiansMode();

            Button radBtn = makeMaterialButton(
                    "RAD",
                    isRad ? colPrimary : Color.TRANSPARENT,
                    isRad ? colOnPrimary : colOnSurfaceVariant,
                    7,
                    8.5f,
                    1.1f,
                    isRad
            );
            radBtn.setPadding(dp(6), 0, dp(6), 0);
            radBtn.setMinimumHeight(dp(17));
            radBtn.setMinimumWidth(dp(30));
            radBtn.setIncludeFontPadding(false);
            radBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (!engine.isRadiansMode()) {
                    engine.setRadiansMode(true);
                    saveState();
                    updateInputAndPreview();
                    refreshKeyboardSection();
                }
            });
            segPill.addView(radBtn);

            Button degBtn = makeMaterialButton(
                    "DEG",
                    !isRad ? colPrimary : Color.TRANSPARENT,
                    !isRad ? colOnPrimary : colOnSurfaceVariant,
                    7,
                    8.5f,
                    1.1f,
                    !isRad
            );
            degBtn.setPadding(dp(6), 0, dp(6), 0);
            degBtn.setMinimumHeight(dp(17));
            degBtn.setMinimumWidth(dp(30));
            degBtn.setIncludeFontPadding(false);
            degBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (engine.isRadiansMode()) {
                    engine.setRadiansMode(false);
                    saveState();
                    updateInputAndPreview();
                    refreshKeyboardSection();
                }
            });
            segPill.addView(degBtn);
            angleBar.addView(segPill);

            View s2 = new View(this);
            angleBar.addView(s2, new LinearLayout.LayoutParams(dp(8), dp(1)));

            Button closeParenBtn = makeMaterialButton(")", Color.TRANSPARENT, colOnSurface, 8, 13f, 1.2f);
            closeParenBtn.setPadding(dp(8), 0, dp(8), 0);
            closeParenBtn.setMinimumHeight(dp(18));
            closeParenBtn.setMinimumWidth(dp(30));
            closeParenBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                handleKeyPress(")");
            });
            angleBar.addView(closeParenBtn);

            grid.addView(angleBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        KeyDef[][] rows = sections[Math.min(activeSection, sections.length - 1)];
        for (KeyDef[] row : rows) {
            LinearLayout rLayout = new LinearLayout(this);
            rLayout.setOrientation(LinearLayout.HORIZONTAL);
            for (final KeyDef keyDef : row) {
                final String tok = keyDef.token;
                final String lbl = keyDef.label;
                boolean isAccent = tok.equals("TOGGLE_VAR_MORE") || tok.equals("TAB_1") || tok.equals("TAB_3");
                int fg = isAccent ? colPrimary : colOnSurface;

                float btnFontSize = 15f;
                if (lbl.length() > 6) btnFontSize = 11f;
                else if (lbl.length() > 4) btnFontSize = 12.5f;

                Button kb = makeMaterialButton(lbl, Color.TRANSPARENT, fg, 14, btnFontSize, 1.4f, false);
                kb.setSingleLine(true);
                kb.setPadding(dp(2), dp(2), dp(2), dp(2));
                kb.setAutoSizeTextTypeUniformWithConfiguration(
                        8, Math.round(btnFontSize * 1.3f), 1, TypedValue.COMPLEX_UNIT_DIP);
                kb.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    handleKeyPress(tok);
                });
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                klp.setMargins(dp(2), dp(2), dp(2), dp(2));
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

    private void showMatrixDialog() {
        final String[] options = {
                "2×2 Einheitsmatrix: [[1, 0], [0, 1]]",
                "2×2 Matrix Vorlage: [[1, 2], [3, 4]]",
                "3×3 Einheitsmatrix: [[1, 0, 0], [0, 1, 0], [0, 0, 1]]",
                "Klammern [ ] einfügen",
                "Zeilentrenner ], [ einfügen",
                "Komma , einfügen",
                "Inversion: inv(",
                "Determinante: det(",
                "Transponieren: trans("
        };
        new AlertDialog.Builder(this)
                .setTitle("Matrix einfügen")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: insertAtCursor("[[1, 0], [0, 1]]"); break;
                        case 1: insertAtCursor("[[1, 2], [3, 4]]"); break;
                        case 2: insertAtCursor("[[1, 0, 0], [0, 1, 0], [0, 0, 1]]"); break;
                        case 3: insertAtCursor("[]"); break;
                        case 4: insertAtCursor("], ["); break;
                        case 5: insertAtCursor(", "); break;
                        case 6: insertAtCursor("inv("); break;
                        case 7: insertAtCursor("det("); break;
                        case 8: insertAtCursor("trans("); break;
                    }
                })
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
            updateInputAndPreview();
            refreshKeyboardSection();
            return;
        }
        if (key.equals("SHOW_MATRIX_DIALOG")) {
            showMatrixDialog();
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
            clearInput();
            return;
        }

        if (key.equals("=")) {
            if (currentInput != null && !currentInput.trim().isEmpty()) {
                try {
                    CalculatorEngine.Result result = engine.evaluateAndCommit(currentInput);
                    String dec = result.decimalText;
                    String ex = result.exactText;
                    String rep = getIntegrationReportText(currentInput);
                    history.add(new HistoryEntry(currentInput, ex, dec, rep, showExactFirst));
                    clearInput();
                    refreshHistoryViews(true);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    String errDesc = msg.contains("Division") ? "Division durch 0" : "Fehler";
                    history.add(new HistoryEntry(currentInput, errDesc, errDesc, true));
                    clearInput();
                    refreshHistoryViews(true);
                }
            }
        } else if (key.equals("DEL")) {
            deleteAtCursor();
        } else if (key.equals(":=")) {
            int start = Math.max(0, inputDisplayView != null ? inputDisplayView.getSelectionStart() : 0);
            String cur = currentInput != null ? currentInput : "";
            if (start > 0 && cur.charAt(start - 1) != ' ') {
                insertAtCursor(" := ");
            } else {
                insertAtCursor(":= ");
            }
        } else if (key.startsWith("AC")) {
            history.clear();
            saveState();
            refreshHistoryViews(true);
        } else {
            insertAtCursor(key);
        }
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
            String t = s.trim();
            if (!t.isEmpty()) {
                try {
                    double v = engine.evaluateForX(t, 1.0);
                    if (Double.isNaN(v)) {
                        f1Input.setError("Ungültiger Ausdruck");
                    } else {
                        f1Input.setError(null);
                    }
                } catch (Exception e) {
                    f1Input.setError("Ungültiger Ausdruck");
                }
            } else {
                f1Input.setError(null);
            }
            plotView.invalidate();
            saveState();
        }));
        f2Input.addTextChangedListener(new SimpleWatcher(s -> {
            plotExpr2 = s;
            String t = s.trim();
            if (!t.isEmpty()) {
                try {
                    double v = engine.evaluateForX(t, 1.0);
                    if (Double.isNaN(v)) {
                        f2Input.setError("Ungültiger Ausdruck");
                    } else {
                        f2Input.setError(null);
                    }
                } catch (Exception e) {
                    f2Input.setError("Ungültiger Ausdruck");
                }
            } else {
                f2Input.setError(null);
            }
            plotView.invalidate();
            saveState();
        }));

        if (currentInput != null && !currentInput.trim().isEmpty()) {
            Button importBtn = makeMaterialButton(
                    "Rechner-Formel übernehmen",
                    Color.TRANSPARENT, colPrimary, 12, 11f, 1.2f, true);
            importBtn.setBackground(outlinedPill(colPrimary, dp(1f), 12));
            importBtn.setPadding(dp(10), dp(2), dp(10), dp(2));
            importBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                plotExpr1 = currentInput.trim();
                f1Input.setText(plotExpr1);
            });
            header.addView(importBtn);
        }

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

    private static final boolean[] categoryExpanded = {false, true, false, false, false};

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
                        new ConstantDef("h", "h", "Plancksches Wirkungsquantum", "6,626 1×10⁻³⁴"),
                        new ConstantDef("m_P", "m_P", "Planck-Masse", "2,176 5×10⁻⁸"),
                        new ConstantDef("t_P", "t_P", "Planck-Zeit", "5,391 1×10⁻⁴⁴"),
                        new ConstantDef("l_P", "l_P", "Planck-Länge", "1,616 2×10⁻³⁵")
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
                        "In Rechner einsetzen (" + vName + ")"
                }, (dialog, which) -> {
                    if (which == 0) {
                        currentInput += vName + " := ";
                        activeTab = 0;
                        saveState();
                        rebuildScreen();
                    } else if (which == 1) {
                        showSetDirectValueDialog(vName);
                    } else if (which == 2) {
                        engine.unsetVariable(vName);
                        saveState();
                        rebuildScreen();
                        Toast.makeText(this, "Variable " + vName + " zurückgesetzt", Toast.LENGTH_SHORT).show();
                    } else if (which == 3) {
                        currentInput += vName;
                        activeTab = 0;
                        saveState();
                        rebuildScreen();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
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

                    Button insBtn = makeMaterialButton("+ Einsetzen", colPrimary, colOnPrimary, 10, 10f, 1.3f);
                    insBtn.setSingleLine(true);
                    insBtn.setPadding(dp(6), dp(1), dp(6), dp(1));
                    insBtn.setMinimumHeight(dp(24));
                    insBtn.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        currentInput += vName;
                        activeTab = 0;
                        saveState();
                        rebuildScreen();
                    });
                    LinearLayout.LayoutParams insLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    insLp.topMargin = dp(4);
                    tile.addView(insBtn, insLp);

                    View.OnLongClickListener onLong = v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        showVariableOptionsDialog(vName);
                        return true;
                    };
                    tile.setOnLongClickListener(onLong);
                    circle.setOnLongClickListener(onLong);
                    insBtn.setOnLongClickListener(onLong);

                    tile.setClickable(true);
                    tile.setFocusable(true);
                    tile.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        currentInput += vName;
                        activeTab = 0;
                        saveState();
                        rebuildScreen();
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
                    nameTv.setText(item.name);
                    nameTv.setTextColor(colOnSurface);
                    nameTv.setGravity(Gravity.CENTER);
                    nameTv.setMaxLines(2);
                    setScaledTextSize(nameTv, 11f, 1.3f);
                    LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    nameLp.topMargin = dp(2);
                    tile.addView(nameTv, nameLp);

                    // 4. Action Button "+ Einsetzen"
                    Button insBtn = makeMaterialButton("+ Einsetzen", colPrimary, colOnPrimary, 10, 10f, 1.3f);
                    insBtn.setSingleLine(true);
                    insBtn.setPadding(dp(6), dp(1), dp(6), dp(1));
                    insBtn.setMinimumHeight(dp(24));
                    insBtn.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        currentInput += item.token;
                        activeTab = 0;
                        saveState();
                        rebuildScreen();
                    });
                    LinearLayout.LayoutParams insLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    insLp.topMargin = dp(4);
                    tile.addView(insBtn, insLp);

                    tile.setClickable(true);
                    tile.setFocusable(true);
                    tile.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        currentInput += item.token;
                        activeTab = 0;
                        saveState();
                        rebuildScreen();
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

        // Top Action Pill: zur Tastatur hinzufügen
        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setGravity(Gravity.CENTER);
        topActions.setPadding(dp(4), dp(2), dp(4), dp(8));

        Button addKbBtn = makeMaterialButton(
                showMoreVariables ? "Erweiterte Variablen ausblenden" : "zur Tastatur hinzufügen",
                Color.TRANSPARENT, colPrimary, 16, 12f, 1.3f, true);
        addKbBtn.setBackground(outlinedPill(colPrimary, dp(1.2f), 16));
        addKbBtn.setPadding(dp(16), dp(3), dp(16), dp(3));
        addKbBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            showMoreVariables = !showMoreVariables;
            activeTab = 0;
            saveState();
            rebuildScreen();
        });
        topActions.addView(addKbBtn);
        list.addView(topActions);

        // Accordion Category Cards
        CategoryDef[] cats = getConstantCategories();
        for (int cIdx = 0; cIdx < cats.length; cIdx++) {
            final int catIndex = cIdx;
            final CategoryDef cat = cats[cIdx];
            final boolean isExp = categoryExpanded[catIndex];

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
                categoryExpanded[catIndex] = !categoryExpanded[catIndex];
                rebuildScreen();
            });

            TextView chev = new TextView(this);
            chev.setText(isExp ? "▲  " : "▼  ");
            chev.setTextColor(colOnSurface);
            setScaledTextSize(chev, 14f, 2.0f);
            hdr.addView(chev);

            TextView titleTv = new TextView(this);
            titleTv.setText(catIndex == 0 ? "Benutzerdefinierte Variablen (lange drücken)" : cat.title);
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
        if (numStr.contains("i") || numStr.contains("[") || numStr.contains("/")) {
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
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(fg);
        setScaledTextSize(b, spSize, maxScale);
        b.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
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

