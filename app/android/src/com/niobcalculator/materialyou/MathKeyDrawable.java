package com.niobcalculator.materialyou;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Mathematical notation for the keys whose layout needs more than one line. */
final class MathKeyDrawable extends Drawable {
    enum Symbol { ROOT, INDEXED_ROOT, BINOMIAL, DERIVATIVE, MATRIX, INVERSE, TRANSPOSE }

    private static final float VIEWPORT = 36f;
    private final Symbol symbol;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    MathKeyDrawable(Symbol symbol, int size, int color) {
        this.symbol = symbol;
        this.size = size;
        paint.setColor(color);
        paint.setStrokeWidth(1.35f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float scale = Math.min(bounds.width(), bounds.height()) / VIEWPORT;
        canvas.save();
        canvas.translate(bounds.exactCenterX() - scale * VIEWPORT / 2f,
                bounds.exactCenterY() - scale * VIEWPORT / 2f);
        canvas.scale(scale, scale);

        switch (symbol) {
            case ROOT:
            case INDEXED_ROOT:
                boolean indexed = symbol == Symbol.INDEXED_ROOT;
                paint.setStyle(Paint.Style.STROKE);
                path.reset();
                traceRadical(path, indexed ? 7 : 4, 8, indexed ? 17 : 15, 32, 28);
                canvas.drawPath(path, paint);
                drawText(canvas, "x", 24, 24, 16);
                if (indexed) drawText(canvas, "n", 5.5f, 13, 9.5f);
                break;
            case BINOMIAL:
                paint.setStyle(Paint.Style.STROKE);
                path.reset();
                path.moveTo(10, 4);
                path.quadTo(1, 18, 10, 32);
                path.moveTo(26, 4);
                path.quadTo(35, 18, 26, 32);
                canvas.drawPath(path, paint);
                drawText(canvas, "n", 18, 15, 12);
                drawText(canvas, "k", 18, 28, 12);
                break;
            case DERIVATIVE:
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(8, 18, 28, 18, paint);
                drawText(canvas, "d", 18, 14, 13);
                drawText(canvas, "dx", 18, 31, 13);
                break;
            case MATRIX:
            case INVERSE:
            case TRANSPOSE:
                drawMatrix(canvas);
                break;
        }
        canvas.restore();
    }

    static void traceRadical(Path path, float left, float top, float stem, float right, float bottom) {
        float width = stem - left;
        float hook = bottom - Math.min((bottom - top) * 0.45f, width * 0.8f);
        path.moveTo(left, hook);
        path.lineTo(left + width * 0.23f, hook - width * 0.14f);
        path.lineTo(left + width * 0.53f, bottom);
        path.lineTo(stem, top);
        path.lineTo(right, top);
    }

    private void drawMatrix(Canvas canvas) {
        boolean hasScript = symbol != Symbol.MATRIX;
        canvas.save();
        canvas.translate(hasScript ? -3 : 0, 0);
        paint.setStyle(Paint.Style.STROKE);
        path.reset();
        path.moveTo(10.5f, 10);
        path.lineTo(6.5f, 10);
        path.lineTo(6.5f, 29);
        path.lineTo(10.5f, 29);
        path.moveTo(25.5f, 10);
        path.lineTo(29.5f, 10);
        path.lineTo(29.5f, 29);
        path.lineTo(25.5f, 29);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                canvas.drawCircle(12 + column * 6, 14 + row * 5.5f, 0.8f, paint);
            }
        }
        canvas.restore();
        if (hasScript) {
            drawText(canvas, symbol == Symbol.INVERSE ? "−1" : "T", 30, 10, 9.5f);
        }
    }

    private void drawText(Canvas canvas, String text, float x, float baseline, float textSize) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(textSize);
        canvas.drawText(text, x, baseline, paint);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
