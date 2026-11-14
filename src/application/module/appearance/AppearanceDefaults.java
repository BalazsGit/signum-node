package application.module.appearance;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Hardcoded L0 (baseline) default appearance values for the Signum Node GUI.
 *
 * <p>In the two-layer profile architecture, this class is the single source of
 * truth for the "Default" appearance. The virtual "Default" profile entry in the
 * GUI is backed by these values — it is NOT a file on disk.</p>
 *
 * <p>The effective appearance for any profile is resolved as:
 * <pre>
 *   L0: AppearanceDefaults (hardcoded, this class)
 *      ← L2: conf/appearance/profiles/{name}.properties
 * </pre>
 * Keys not present in the L2 file fall through to the L0 values.</p>
 */
public final class AppearanceDefaults {

    /** Default Look &amp; Feel class. */
    public static final String DEFAULT_THEME_CLASS = FlatDarkLaf.class.getName();

    /** Default global font family (null = system default). */
    public static final String DEFAULT_GLOBAL_FONT_FAMILY = null;

    /** Default global font style (0 = plain; null = system default). */
    public static final Integer DEFAULT_GLOBAL_FONT_STYLE = null;

    /** Default global font size (null = system default). */
    public static final Integer DEFAULT_GLOBAL_FONT_SIZE = null;

    /** Default console font family (null = system default). */
    public static final String DEFAULT_CONSOLE_FONT_FAMILY = null;

    /** Default console font style (null = system default). */
    public static final Integer DEFAULT_CONSOLE_FONT_STYLE = null;

    /** Default console font size (null = system default). */
    public static final Integer DEFAULT_CONSOLE_FONT_SIZE = null;

    private AppearanceDefaults() {
        // utility class
    }

    /**
     * Creates a new {@link AppearanceProfile} seeded with the L0 default values.
     *
     * @return a profile named "Default" with the hardcoded baseline appearance
     */
    public static AppearanceProfile createDefaultProfile() {
        AppearanceProfile profile = new AppearanceProfile("Default");
        profile.setThemeClassName(DEFAULT_THEME_CLASS);
        // Fonts and colors are null/empty = "system default" (no override).
        return profile;
    }
}