package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * ScrollView that deliberately bypasses framework scrollbar drawing.
 *
 * Some Android/OEM builds can reach View.onDrawScrollBars() with a null
 * ScrollBarDrawable, causing a fatal NPE during View.draw(). The app does
 * not need visual scrollbars; touch scrolling, fling, focus scrolling and
 * programmatic scrollTo/scrollBy remain provided by ScrollView.
 */
public class SafeScrollView extends ScrollView {
    public SafeScrollView(Context context) {
        super(context);
        disableScrollbarRendering();
    }

    public SafeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        disableScrollbarRendering();
    }

    public SafeScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        disableScrollbarRendering();
    }

    private void disableScrollbarRendering() {
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setScrollbarFadingEnabled(true);
    }

    @Override
    protected void onDrawScrollBars(Canvas canvas) {
        // Intentionally empty: avoid the platform's null ScrollBarDrawable path.
    }
}
