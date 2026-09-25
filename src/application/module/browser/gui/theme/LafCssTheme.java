package application.module.browser.gui.theme;

import application.module.browser.engine.scheme.PageTheme;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.UIManager;

/**
 * The browser's theme provider (plan D6, F6): samples the active app LAF
 * palette (FlatLaf — light, dark or a custom profile) and produces the CSS
 * {@code :root} override block for the internal pages, so
 * {@code signum://newtab}, {@code history}, {@code settings}, ... always
 * match the look of the surrounding Swing UI.
 * <p>
 * Sampling happens per request (a handful of {@link UIManager#getColor}
 * lookups — negligible next to serving a page) so a runtime theme switch is
 * picked up on the next page load without any listener plumbing.
 * <p>
 * Every token has a fallback, and an unreadable palette simply yields the
 * empty block — the static dark tokens in {@code css/browser.css} stay in
 * effect. GUI layer (Swing is allowed here); the pure conversion lives in
 * {@link PageTheme} (engine, unit-tested).
 */
public final class LafCssTheme {

    /** Fallbacks mirroring the static dark tokens of {@code css/browser.css}. */
    private static final Color FALLBACK_BG = new Color(0x14, 0x17, 0x1c);
    private static final Color FALLBACK_FG = new Color(0xe6, 0xe9, 0xee);
    private static final Color FALLBACK_ACCENT = new Color(0x4f, 0x8c, 0xff);

    private LafCssTheme() {
        // utility class — never instantiated
    }

    /**
     * @return the active LAF palette as CSS design tokens, or an empty map
     *         when no usable palette can be sampled
     */
    public static Map<String, Color> tokens() {
        Color bg = color("Panel.background");
        if (bg == null) {
            bg = color("control");
        }
        if (bg == null) {
            return Map.of();
        }
        Color fg = color("Label.foreground");
        if (fg == null) {
            fg = color("foreground");
        }
        if (fg == null) {
            fg = FALLBACK_FG;
        }
        Map<String, Color> tokens = new LinkedHashMap<>();
        tokens.put("bg", bg);
        tokens.put("card", nonNull(color("TextField.background"), bg));
        tokens.put("fg", fg);
        tokens.put("muted", mix(fg, bg, 0.62f));
        tokens.put("accent", nonNull(color("Component.focusColor"), FALLBACK_ACCENT));
        tokens.put("border", nonNull(color("controlShadow"), mix(fg, bg, 0.22f)));
        return tokens;
    }

    /** @return the ready-to-inject {@code <style>} block ("" = no overrides). */
    public static String styleBlock() {
        try {
            return PageTheme.styleBlock(tokens());
        } catch (RuntimeException e) {
            return ""; // a palette glitch must never break page serving
        }
    }

    private static Color color(String key) {
        try {
            return UIManager.getColor(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Color nonNull(Color value, Color fallback) {
        return value != null ? value : fallback;
    }

    /** Alpha-blends {@code fg} over {@code bg} (a = the weight of fg, 0..1). */
    static Color mix(Color fg, Color bg, float a) {
        return new Color(
                (int) Math.round(fg.getRed() * a + bg.getRed() * (1 - a)),
                (int) Math.round(fg.getGreen() * a + bg.getGreen() * (1 - a)),
                (int) Math.round(fg.getBlue() * a + bg.getBlue() * (1 - a)));
    }
}
