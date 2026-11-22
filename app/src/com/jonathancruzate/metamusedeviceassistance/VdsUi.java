package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * VdsUi — the Muse design system (VDS, decomposed from the Muse
 * app; skill local-muse-design + MUSE-DESIGN-TOKENS.md) translated
 * into this app's framework-only programmatic UI. First applied in
 * v1.31.0 to MainActivity's Plugins section (the rest of this
 * app's screens follow on their next passes, per Jonathan's
 * 2026-11-22 design order). There is no
 * Compose here, so the tokens are applied as values:
 *
 *  Colors (semantic, light/dark): background #FCFCFC / #050505,
 *  card #FFFFFF / #1F1F1F, text primary #111112 / #F5F5F5 with
 *  secondary/tertiary derived as primary at 60% / 35-40% alpha,
 *  accent + primary button #0064D4 / #007FFD, secondary button
 *  #F3F4F5 / #28292B, border #E9EAEB / #1A1A1A, destructive
 *  #C01F37 / #E7354A, success #25B159.
 *  Type: system font stands in for Optimistic (proprietary; the
 *  divergence is deliberate and recorded in DESIGN.md). Scale:
 *  Title 2 24/29, Title 3 20/25, Body 17/22, Subheadline 15/21,
 *  Footnote 13/18, Caption 12/16; weights Regular 400 / Medium 500
 *  / Emphasized 600.
 *  Shapes: card radius 20dp, buttons pill (fully round), gutter
 *  16dp. Settings card min height 56dp where a row sets one.
 *
 * Framework limits (recorded, not hidden): no bouncy indication
 * (stock press states), Switch geometry is the framework's (VDS
 * records a 64x28 track / 24 thumb; the framework switch is
 * tinted with the accent instead), and elevation/shadow tokens
 * have no direct GradientDrawable equivalent.
 */
public final class VdsUi {
    private VdsUi() {}

    public static boolean isDark(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int bg(Context ctx) {
        return isDark(ctx) ? Color.rgb(0x05, 0x05, 0x05) : Color.rgb(0xFC, 0xFC, 0xFC);
    }

    public static int cardBg(Context ctx) {
        return isDark(ctx) ? Color.rgb(0x1F, 0x1F, 0x1F) : Color.WHITE;
    }

    public static int textPrimary(Context ctx) {
        return isDark(ctx) ? Color.rgb(0xF5, 0xF5, 0xF5) : Color.rgb(0x11, 0x11, 0x12);
    }

    /** Primary at 60% alpha (both schemes). */
    public static int textSecondary(Context ctx) {
        return Color.argb(153, Color.red(textPrimary(ctx)),
                Color.green(textPrimary(ctx)), Color.blue(textPrimary(ctx)));
    }

    /** Primary at 35% (light) / 40% (dark) alpha. */
    public static int textTertiary(Context ctx) {
        int a = isDark(ctx) ? 102 : 89;
        return Color.argb(a, Color.red(textPrimary(ctx)),
                Color.green(textPrimary(ctx)), Color.blue(textPrimary(ctx)));
    }

    public static int accent(Context ctx) {
        return isDark(ctx) ? Color.rgb(0x00, 0x7F, 0xFD) : Color.rgb(0x00, 0x64, 0xD4);
    }

    public static int secondaryBtnBg(Context ctx) {
        return isDark(ctx) ? Color.rgb(0x28, 0x28, 0x2B) : Color.rgb(0xF3, 0xF4, 0xF5);
    }

    public static int border(Context ctx) {
        return isDark(ctx) ? Color.rgb(0x1A, 0x1A, 0x1A) : Color.rgb(0xE9, 0xEA, 0xEB);
    }

    public static int destructive(Context ctx) {
        return isDark(ctx) ? Color.rgb(0xE7, 0x35, 0x4A) : Color.rgb(0xC0, 0x1F, 0x37);
    }

    public static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    public static Typeface weight(int w) {
        if (Build.VERSION.SDK_INT >= 28) {
            return Typeface.create(Typeface.DEFAULT, w, false);
        }
        return w >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    public static TextView text(Context ctx, String s, float sp, int weight, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(weight(weight));
        t.setTextColor(color);
        t.setLetterSpacing(0.015f);
        return t;
    }

    /** A VDS card: 20dp radius, card surface, 1dp border, 16dp padding. */
    public static LinearLayout card(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(cardBg(ctx));
        g.setCornerRadius(dp(ctx, 20));
        g.setStroke((int) Math.max(1, dp(ctx, 1)), border(ctx));
        l.setBackground(g);
        int p = (int) dp(ctx, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, (int) dp(ctx, 12));
        l.setLayoutParams(lp);
        return l;
    }

    /** Pill button. Primary = accent fill, white text (Large: min
     *  height 48dp). Secondary = secondary fill, primary text. */
    public static Button pillButton(Context ctx, String label, boolean primary) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(weight(600));
        b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? accent(ctx) : secondaryBtnBg(ctx));
        g.setCornerRadius(dp(ctx, 500));
        b.setBackground(g);
        b.setTextColor(primary ? Color.WHITE : textPrimary(ctx));
        b.setMinHeight((int) dp(ctx, 48));
        int h = (int) dp(ctx, 28);
        b.setPadding(h, (int) dp(ctx, 12), h, (int) dp(ctx, 12));
        b.setStateListAnimator(null);
        return b;
    }

    /** Framework Switch tinted with the VDS accent (geometry is
     *  the framework's; see class doc). */
    public static Switch toggle(Context ctx, String label) {
        Switch s = new Switch(ctx);
        s.setText(label);
        s.setTextSize(17);
        s.setTypeface(weight(400));
        s.setTextColor(textPrimary(ctx));
        s.setMinHeight((int) dp(ctx, 56));
        if (Build.VERSION.SDK_INT >= 23) {
            s.setThumbTintList(ColorStateList.valueOf(accent(ctx)));
            s.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked},
                            new int[]{-android.R.attr.state_checked}},
                    new int[]{accent(ctx), secondaryBtnBg(ctx)}));
        }
        return s;
    }

    public static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(border(ctx));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) dp(ctx, 1))));
        return v;
    }
}
