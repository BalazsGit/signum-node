package application.utils.gui;

import jiconfont.IconCode;
import jiconfont.swing.IconFontSwing;

import java.awt.Color;
import java.awt.Dimension;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.Icon;

/**
 * Memoization cache for {@link IconFontSwing#buildIcon} results.
 * <p>
 * jiconfont has NO internal font caching: every {@code buildIcon} call re-creates
 * the underlying {@code java.awt.Font} from the raw font bytes (a temp-file + GDI
 * round-trip, see {@code IconFontSwing.buildFont(String)}) and re-renders the glyph
 * image. Heavy panels (e.g. {@code NodeConfigurationPanel.initUI} with ~200
 * {@link HelpButton}s, one per property row) therefore trigger hundreds of
 * redundant font loads on the EDT. A built icon is rendering-immutable, so the
 * icon for a given (code, size, color) triple can be shared by every consumer.
 * </p>
 */
public final class IconFontCache {

    /** Bounded-ish guard: never more entries than the number of distinct icon variants. */
    private static final int MAX_ENTRIES = 1000;

    /** Key: icon code name | size | rgba — value: the shared, rendering-immutable icon. */
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    private IconFontCache() {
    }

    /**
     * Returns the cached icon for the given triple, building (and caching) it on
     * the first request. Safe to call from any thread that may render Swing icons;
     * the first concurrent build for a triple may happen twice (harmless).
     */
    public static Icon buildIcon(IconCode code, float size, Color color) {
        String key = code.name() + '|' + size + '|' + color.getRGB();
        Icon icon = CACHE.get(key);
        if (icon == null) {
            icon = IconFontSwing.buildIcon(code, size, color);
            if (CACHE.size() < MAX_ENTRIES) {
                Icon previous = CACHE.putIfAbsent(key, icon);
                if (previous != null) {
                    icon = previous;
                }
            }
        }
        return icon;
    }

    /**
     * Returns the pixel dimensions that {@link #buildIcon} produces for {@code code}
     * at {@code pointSize} — the non-square box the FontAwesome siblings expose
     * (a width slightly smaller than the height, derived from the font metrics).
     * Used to size a non-FontAwesome glyph (the app's thin plus) to the same box
     * so a mixed toolbar keeps uniform button widths. The colour does not affect
     * the size, so a throwaway white build is used (and cached).
     *
     * @param code      the glyph to measure (any glyph — the box is a font property,
     *                  not a per-glyph one)
     * @param pointSize the icon point size
     * @return the (width, height) box {@code buildIcon} would produce
     */
    public static Dimension iconBox(IconCode code, float pointSize) {
        Icon icon = buildIcon(code, pointSize, Color.WHITE);
        return new Dimension(icon.getIconWidth(), icon.getIconHeight());
    }

    /** Drops all cached icons (e.g. after an appearance/theme reset). */
    public static void clear() {
        CACHE.clear();
    }
}