package personal.muse.recorder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.Switch;

/**
 * "Cloud White Minimal" theme (v1.12 redesign): one palette for light,
 * one for dark, resolved from the dark-mode setting (follow system /
 * light / dark). Every color the UI paints with lives here so the four
 * tabs stay consistent, and custom views pull their colors from here too.
 */
public final class Ui {
    private Ui() {}

    public static final class Palette {
        public final boolean dark;
        public final int bg, card, card2, text, sub, accent, accent2, danger, tabBar, divider;

        Palette(boolean dark, int bg, int card, int card2, int text, int sub,
                int accent, int accent2, int danger, int tabBar, int divider) {
            this.dark = dark;
            this.bg = bg;
            this.card = card;
            this.card2 = card2;
            this.text = text;
            this.sub = sub;
            this.accent = accent;
            this.accent2 = accent2;
            this.danger = danger;
            this.tabBar = tabBar;
            this.divider = divider;
        }
    }

    private static final Palette LIGHT = new Palette(false,
            0xFFF6F7F9, 0xFFFFFFFF, 0xFFF0F2F6, 0xFF111827, 0xFF6B7280,
            0xFF2563EB, 0xFF7C3AED, 0xFFDC2626, 0xFFFFFFFF, 0xFFE4E7EC);
    private static final Palette DARK = new Palette(true,
            0xFF0E1014, 0xFF1A1D24, 0xFF20242D, 0xFFF0F3F8, 0xFF949CAD,
            0xFF488AFC, 0xFFA78BFA, 0xFFEB4444, 0xFF13161C, 0xFF282D38);

    public static boolean isDark(Context ctx) {
        int mode = Prefs.darkMode(ctx);
        if (mode == 1) return false;
        if (mode == 2) return true;
        int night = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Palette of(Context ctx) {
        return isDark(ctx) ? DARK : LIGHT;
    }

    public static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    /** Rounded solid fill. */
    public static GradientDrawable rounded(Context ctx, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    /** Rounded fill with a 1dp stroke (used for card outlines). */
    public static GradientDrawable rounded(Context ctx, int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = rounded(ctx, fill, radiusDp);
        d.setStroke((int) (dp(ctx, 1) + 0.5f), strokeColor);
        return d;
    }

    /** Card background: theme card color + subtle divider-colored outline. */
    public static GradientDrawable cardBg(Context ctx, Palette p, int radiusDp) {
        return rounded(ctx, p.card, radiusDp, p.divider);
    }

    /** Primary action button: accent fill, white text. */
    public static void stylePrimary(Button b, Palette p) {
        b.setBackground(rounded(b.getContext(), p.accent, 12));
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
    }

    /** Secondary button: card fill with outline, theme text color. */
    public static void styleGhost(Button b, Palette p) {
        b.setBackground(cardBg(b.getContext(), p, 12));
        b.setTextColor(p.text);
        b.setAllCaps(false);
    }

    /** Solid oval (dots, play buttons). */
    public static GradientDrawable oval(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    /** Settings switch: accent track when on, neutral track/thumb otherwise. */
    public static void styleSwitch(Switch sw, Palette p) {
        sw.setTextColor(p.text);
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{p.accent, p.dark ? 0xFF3A3F4A : 0xFFD1D5DB}));
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, p.dark ? 0xFFB0B7C3 : 0xFFF9FAFB}));
    }
}
