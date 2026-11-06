package com.talkreel.ar;

import android.content.Context;
import android.widget.ScrollView;

/**
 * ScrollView with both scrollbar axes disabled.
 *
 * The app retains native touch scrolling, fling and programmatic scrollBy/
 * scrollTo. The app theme supplies non-null transparent scrollbar drawables
 * as a guard for framework/OEM implementations that still initialize a
 * scrollbar cache while the scrollbar is hidden.
 */
public class SafeScrollView extends ScrollView {
    public SafeScrollView(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setScrollbarFadingEnabled(true);
    }
}
