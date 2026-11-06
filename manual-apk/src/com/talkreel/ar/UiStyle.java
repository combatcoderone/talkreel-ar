package com.talkreel.ar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

// Modern buttons, no default grey Android buttons.
// Primary is orange, like the circle. Secondary is dark grey.
// All buttons share the same radius, padding and text size.
public class UiStyle {
    public static final int ORANGE = 0xFFFF8A00;
    public static final int ORANGE_DARK = 0xFFE67E00;
    public static final int DARK = 0xFF232323;
    public static final int DARK2 = 0xFF333333;

    public static Button primary(Context c, String text) {
        return make(c, text, ORANGE, Color.WHITE, true);
    }

    public static Button dark(Context c, String text) {
        return make(c, text, DARK, Color.WHITE, false);
    }

    public static Button make(Context c, String text, int bg, int fg, boolean isPrimary) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(dp(c, 44));
        b.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(c, 14));
        if (isPrimary) {
            d.setStroke(0, bg);
        } else {
            d.setStroke(dp(c, 1), 0xFF444444);
        }
        b.setBackground(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        b.setLayoutParams(p);
        return b;
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable panelBg(Context c) {
        // v0.5: much more transparent by default, text is the important part.
        // 0x59 is about 35 percent black, instead of 0xE6, about 90 percent.
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x59000000);
        d.setCornerRadius(dp(c, 14));
        d.setStroke(dp(c, 1), 0x22FFFFFF);
        return d;
    }
}
