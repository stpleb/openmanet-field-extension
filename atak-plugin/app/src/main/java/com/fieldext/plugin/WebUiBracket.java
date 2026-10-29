package com.fieldext.plugin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * "WEBUI" centred over the two link buttons, with a line out to each side that
 * turns down into an arrow pointing at the middle of each button.
 */
final class WebUiBracket extends View {
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    WebUiBracket(Context ctx, float density) {
        super(ctx);
        d = density;
        line.setColor(FieldPanel.CYAN);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(FieldPanel.CYAN);
        label.setTextSize(12 * d);
        label.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        label.setLetterSpacing(0.14f);
        label.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float mid = w / 2, gap = 4 * d;                       // buttons are equal halves with a 4dp gap each side
        float left = (mid - gap) / 2, right = mid + gap + (mid - gap) / 2;
        float textY = 12 * d, lineY = textY - 4 * d;         // line runs through the middle of the caps
        float half = label.measureText("WEBUI") / 2 + 8 * d;
        float bottom = h - 2 * d, arrow = 5 * d;
        c.drawText("WEBUI", mid, textY, label);
        path.reset();
        path.moveTo(mid - half, lineY);                       // left: out, then down to the button
        path.lineTo(left, lineY);
        path.lineTo(left, bottom);
        path.moveTo(left - arrow, bottom - arrow);
        path.lineTo(left, bottom);
        path.lineTo(left + arrow, bottom - arrow);
        path.moveTo(mid + half, lineY);                       // right: mirror image
        path.lineTo(right, lineY);
        path.lineTo(right, bottom);
        path.moveTo(right - arrow, bottom - arrow);
        path.lineTo(right, bottom);
        path.lineTo(right + arrow, bottom - arrow);
        c.drawPath(path, line);
    }
}
