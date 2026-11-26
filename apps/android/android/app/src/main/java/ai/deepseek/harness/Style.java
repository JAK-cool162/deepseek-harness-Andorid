package ai.deepseek.harness;

import android.content.Context;

/** The built-in style preset the app layers over the harness page in both modes. */
final class Style {
    private static final String PREFS = "style";
    private static final String KEY_PRESET = "preset";
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

    /**
     * @param preset preset name other than {@link #DEFAULT}
     * @return the asset path of its stylesheet
     */
    static String assetPath(String preset) {
        return "www/android/presets/" + preset + ".css";
    }
}
