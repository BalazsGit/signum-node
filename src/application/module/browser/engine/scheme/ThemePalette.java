package application.module.browser.engine.scheme;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fixed internal-page palettes of the theme setting (plan F9, C4):
 * {@code follow-app} samples the live LAF palette ({@code LafCssTheme}),
 * while {@code light} and {@code dark} use these constants — the internal
 * pages can be pinned to a look independent of the app theme.
 * <p>
 * The token names are the design tokens of {@code css/browser.css}
 * (without the {@code --} prefix); the values mirror that file's static
 * tokens, so the "dark" palette is exactly what the pages showed before C4.
 * Pure — unit-testable.
 */
public final class ThemePalette {

    public static final String FOLLOW_APP = "follow-app";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    /** The static dark tokens of {@code css/browser.css} (the pre-C4 look). */
    public static final Color DARK_BG = new Color(0x14, 0x17, 0x1c);
    public static final Color DARK_CARD = new Color(0x1d, 0x21, 0x27);
    public static final Color DARK_FG = new Color(0xe6, 0xe9, 0xee);
    public static final Color DARK_MUTED = new Color(0x99, 0xa1, 0xad);
    public static final Color DARK_BORDER = new Color(0x2a, 0x30, 0x38);
    /** The accent is theme-independent (the brand blue of the app). */
    public static final Color ACCENT = new Color(0x4f, 0x8c, 0xff);

    private ThemePalette() {
        // utility class — never instantiated
    }

    /**
     * @param mode the theme mode ({@link #FOLLOW_APP} is handled by the GUI
     *             layer and yields no fixed tokens here)
     * @return the token map for light/dark, or the empty map for
     *         follow-app / unknown values
     */
    public static Map<String, Color> tokens(String mode) {
        if (LIGHT.equals(mode)) {
            return light();
        }
        if (DARK.equals(mode)) {
            return dark();
        }
        return Map.of();
    }

    /** @return the fixed light palette. */
    public static Map<String, Color> light() {
        Map<String, Color> tokens = new LinkedHashMap<>();
        tokens.put("bg", new Color(0xf2, 0xf4, 0xf7));
        tokens.put("card", Color.WHITE);
        tokens.put("fg", new Color(0x1e, 0x21, 0x26));
        tokens.put("muted", new Color(0x6b, 0x72, 0x80));
        tokens.put("accent", ACCENT);
        tokens.put("border", new Color(0xd2, 0xd7, 0xde));
        return tokens;
    }

    /** @return the fixed dark palette (the {@code css/browser.css} defaults). */
    public static Map<String, Color> dark() {
        Map<String, Color> tokens = new LinkedHashMap<>();
        tokens.put("bg", DARK_BG);
        tokens.put("card", DARK_CARD);
        tokens.put("fg", DARK_FG);
        tokens.put("muted", DARK_MUTED);
        tokens.put("accent", ACCENT);
        tokens.put("border", DARK_BORDER);
        return tokens;
    }

    /**
     * @param mode a theme mode string
     * @return {@code true} when the value is one of the known modes
     */
    public static boolean isKnown(String mode) {
        return FOLLOW_APP.equals(mode) || LIGHT.equals(mode) || DARK.equals(mode);
    }
}