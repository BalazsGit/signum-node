package application.module.browser.engine.scheme;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * LAF palette to CSS-variable conversion of the internal pages (plan D6, F6,
 * A4): the app's active Swing palette is sampled by the GUI layer and turned
 * into a {@code <style>} block that overrides the {@code :root} design tokens
 * of {@code css/browser.css}, so every {@code signum://} page follows the
 * app's theme (light/dark, custom profiles) at request time.
 * <p>
 * This class is pure (no CEF, no Swing component access) — fully
 * unit-testable. The {@code org.cef} handler layer calls
 * {@link #inject} with the style block supplied by the registered theme
 * provider (see {@link InternalPage#setThemeStyleProvider}).
 */
public final class PageTheme {

    private PageTheme() {
        // utility class — never instantiated
    }

    /**
     * Builds the theme override block.
     *
     * @param tokens token name (without {@code --}) to color, in insertion
     *               order; {@code null} values are skipped
     * @return the {@code <style>:root{...}</style>} block, or the empty string
     *         when there is nothing to override
     */
    public static String styleBlock(Map<String, Color> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<style>/* signum theme (LAF) */:root{");
        boolean first = true;
        for (Map.Entry<String, Color> entry : tokens.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append(';');
            }
            sb.append("--").append(entry.getKey()).append(": ").append(hex(entry.getValue()));
            first = false;
        }
        if (first) {
            return "";
        }
        return sb.append("}</style>").toString();
    }

    /** @return the 24-bit color as {@code #rrggbb} (lowercase, alpha ignored). */
    public static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Inserts the theme block into the page, right before the closing
     * {@code </head>} — AFTER the {@code <link>} to {@code css/browser.css},
     * so the overrides win the cascade (equal specificity, later wins).
     *
     * @param html       the page bytes
     * @param styleBlock the block to inject (may be empty)
     * @return the page with the block injected, or the original bytes when the
     *         block is empty or the page has no {@code </head>}
     */
    public static byte[] inject(byte[] html, String styleBlock) {
        if (html == null || styleBlock == null || styleBlock.isEmpty()) {
            return html;
        }
        String doc = new String(html, StandardCharsets.UTF_8);
        int head = doc.lastIndexOf("</head>");
        if (head < 0) {
            return html;
        }
        String out = doc.substring(0, head) + styleBlock + doc.substring(head);
        return out.getBytes(StandardCharsets.UTF_8);
    }
}
