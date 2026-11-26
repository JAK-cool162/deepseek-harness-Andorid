package ai.deepseek.harness;

import android.content.Context;

/** The built-in style preset the app layers over the harness page in both modes. */
final class Style {
    private static final String PREFS = "style";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_CORNERS = "corners";

    /** Accent choices: label resource name, then color; the first keeps the harness's own blue. */
    static final String[][] ACCENTS = {
        { "default", "" }, { "teal", "#0EA5A4" }, { "violet", "#8B5CF6" }, { "rose", "#E11D74" }, { "amber", "#D97706" },
    };
    /** Corner choices: name, then small, medium, large, extra-large, and panel radii in px; the first keeps the harness's own. */
    static final String[][] CORNERS = {
        { "default", "" }, { "rounder", "12,18,24,28,32" }, { "sharper", "3,5,8,10,14" },
    };
    /** Blue scale steps the harness derives its accents from, with how much of the accent each mixes in. */
    private static final String[][] LIGHT_STEPS = {
        { "50", "8%" }, { "75", "12%" }, { "100", "18%" }, { "300", "45%" }, { "400", "70%" }, { "450", "85%" }, { "500", "100%" },
    };
    private static final String[][] DARK_STEPS = { { "600", "15%" }, { "800", "40%" }, { "900", "55%" }, { "950", "70%" } };
    /** No preset: the harness's own look. */
    static final String DEFAULT = "default";
    /** Frosted surfaces over a drifting color field. */
    static final String LIQUID_GLASS = "liquid-glass";

    private Style() {
    }

    /**
     * @param context any context of this app
     * @return the selected preset name
     */
    static String preset(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PRESET, DEFAULT);
    }

    /**
     * @param context any context of this app
     * @param preset {@link #DEFAULT} or {@link #LIQUID_GLASS}
     */
    static void setPreset(Context context, String preset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PRESET, preset).apply();
    }

    static String accent(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCENT, DEFAULT);
    }

    static void setAccent(Context context, String accent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACCENT, accent).apply();
    }

    static String corners(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CORNERS, DEFAULT);
    }

    static void setCorners(Context context, String corners) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CORNERS, corners).apply();
    }

    /**
     * CSS for the chosen accent and corners. The harness sets its design tokens inline on
     * {@code <body>}, so the overrides are {@code !important} declarations on {@code body}.
     *
     * @param context any context of this app
     * @return the stylesheet, empty when both choices are the defaults
     */
    static String lookCss(Context context) {
        StringBuilder css = new StringBuilder();
        String color = lookup(ACCENTS, accent(context));
        if (!color.isEmpty()) {
            for (String[] step : LIGHT_STEPS) {
                css.append("--dsw-static-blue-").append(step[0]).append(":color-mix(in oklab,").append(color)
                        .append(' ').append(step[1]).append(",white)!important;");
            }
            for (String[] step : DARK_STEPS) {
                css.append("--dsw-static-blue-").append(step[0]).append(":color-mix(in oklab,").append(color)
                        .append(",black ").append(step[1]).append(")!important;");
            }
            css.append("--dsw-alias-button-info-fill:").append(color).append("!important;");
        }
        String radii = lookup(CORNERS, corners(context));
        if (!radii.isEmpty()) {
            String[] px = radii.split(",");
            String[] names = { "sm", "md", "lg", "xl", "panel" };
            for (int index = 0; index < names.length; index++) {
                css.append("--dsw-radius-").append(names[index]).append(':').append(px[index]).append("px!important;");
            }
        }
        return css.length() == 0 ? "" : "body{" + css + "}";
    }

    private static String lookup(String[][] table, String name) {
        for (String[] row : table) if (row[0].equals(name)) return row[1];
        return "";
    }

    /**
     * @param preset preset name other than {@link #DEFAULT}
     * @return the asset path of its stylesheet
     */
    static String assetPath(String preset) {
        return "www/android/presets/" + preset + ".css";
    }
}
