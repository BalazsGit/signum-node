package brs.gui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.IntelliJTheme;

import brs.gui.configuration.LookAndFeelPanel;
import brs.gui.laf.FlatLafPrefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import java.awt.Color;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the application's dynamic color scheme through a layered palette
 * system.
 * <p>
 * The color loading follows a specific hierarchy, allowing for flexible and
 * theme-specific customization:
 * <ol>
 * <li><b>Base Palette:</b> The process starts by loading a foundational palette
 * based on the current
 * Look and Feel (LaF). For FlatLaf themes, this is either
 * {@code flat-light.palette.json} or
 * {@code flat-dark.palette.json}. For other LaFs like Nimbus, it might be
 * {@code nimbus.palette.json}.
 * If no specific palette is found, it falls back to a hardcoded default.</li>
 *
 * <li><b>Theme-Specific Palette:</b> After loading the base palette, the
 * manager looks for a more
 * specific palette that matches the current theme. The name is derived either
 * from the theme's
 * class name (for built-in themes) or the theme's file name (for IntelliJ
 * themes).
 * <ul>
 * <li>For a built-in theme like {@code com.formdev.flatlaf.FlatDarkLaf}, it
 * looks for {@code FlatDarkLaf.palette.json}.</li>
 * <li>For an IntelliJ theme like {@code arc-dark.theme.json}, it looks for
 * {@code arc-dark.palette.json}.</li>
 * </ul>
 * </ul>
 * If found, its colors are merged on top of the base palette. This allows any
 * theme to be fine-tuned by only providing the color differences in a specific
 * palette file.
 * <br>
 * <b>Location:</b> Base palettes (e.g., {@code flat-dark.palette.json}) are
 * located in {@code /flatlaf/palettes/}.
 * Theme-specific override palettes must be placed in the
 * {@code /flatlaf/palettes/themes/} subdirectory.</li>
 *
 * <li><b>User Profile Overrides:</b> Finally, if a user loads a custom "Look
 * and Feel Profile" from the
 * settings, any color overrides defined within that profile are applied. These
 * overrides take the highest
 * precedence, modifying the final colors displayed in the application.</li>
 * </ol>
 * This layered approach ensures that themes can be created with minimal effort
 * by extending existing base palettes,
 * and users can further customize their experience through profiles.
 *
 * @see ColorPalette
 * @see LookAndFeelPanel
 */
public final class ColorPaletteManager {
    private static final Logger logger = LoggerFactory.getLogger(ColorPaletteManager.class);

    private static final String PALETTES_PATH = "/flatlaf/palettes/";
    private static final String THEME_PALETTES_SUBPATH = "themes/";
    private static ColorPalette activePalette;
    private static ColorPalette defaultPalette;
    private static ColorPalette baseThemePalette;

    private ColorPaletteManager() {
    }

    static {
        // Load a default palette that contains the original hardcoded values.
        // This ensures the application works even if no theme palette is found.
        defaultPalette = createDefaultPalette();
        activePalette = defaultPalette;
        baseThemePalette = defaultPalette;
        // The initial updatePalette() is called from SignumGUI after the LookAndFeel is
        // set.
    }

    private static ColorPalette createDefaultPalette() {
        Map<String, Color> colors = new ConcurrentHashMap<>();
        colors.put("applied", new Color(0, 128, 0));
        colors.put("saved", Color.YELLOW);
        colors.put("peer.disconnected", Color.YELLOW);
        colors.put("peer.outdated.version", Color.ORANGE);
        colors.put("peer.up-to-date.version", Color.GREEN);
        colors.put("peer.outdated.height", Color.ORANGE);
        colors.put("peer.up-to-date.height", Color.GREEN);
        colors.put("peer.other.response.time", new Color(160, 85, 230));
        colors.put("peer.blacklisted", new Color(255, 100, 100));
        colors.put("peer.min.response.time", new Color(80, 170, 50));
        colors.put("peer.max.response.time", new Color(160, 0, 0));
        colors.put("peer.rx.response.time", new Color(0, 100, 0));
        colors.put("peer.rx.count", Color.GREEN);
        colors.put("peer.tx.response.time", Color.ORANGE);
        colors.put("peer.tx.count", new Color(70, 130, 255));
        colors.put("peer.other.count", new Color(255, 215, 0));
        colors.put("peer.connected", Color.GREEN);
        colors.put("peer.active", Color.CYAN);
        colors.put("peer.all", new Color(230, 230, 230));
        colors.put("blockgen.network.size", Color.WHITE);
        colors.put("blockgen.commitment", new Color(220, 130, 255));
        colors.put("blockgen.base.target", Color.YELLOW);
        colors.put("blockgen.node.miners", new Color(50, 205, 50));
        colors.put("blockgen.network.miners", new Color(0, 80, 0));
        colors.put("blockgen.active.miner", new Color(218, 165, 32));
        colors.put("blockgen.deadlines.rx", Color.PINK);
        colors.put("blockgen.node.share", Color.GREEN);
        colors.put("blockgen.chain.deadline", new Color(0, 100, 0));
        colors.put("blockgen.chain.deadline.ma", new Color(0, 100, 0).brighter());
        colors.put("blockgen.node.deadline.ma", new Color(50, 205, 50).darker());
        colors.put("blockgen.node.share.legend", Color.GREEN);
        colors.put("blockgen.network.share.legend", Color.CYAN);
        colors.put("blockgen.pie.others", Color.LIGHT_GRAY);
        colors.put("blockgen.pie.waiting", Color.DARK_GRAY);
        colors.put("blockgen.pie.filtered", Color.DARK_GRAY);
        colors.put("sync.system.tx.per.block", new Color(64, 64, 192));
        colors.put("sync.all.tx.per.block", new Color(235, 165, 50));
        colors.put("sync.upload.volume", new Color(185, 120, 95));
        colors.put("sync.download.volume", new Color(40, 165, 40));
        colors.put("sync.push.time", Color.BLUE);
        colors.put("sync.validation.time", Color.YELLOW);
        colors.put("sync.tx.loop.time", new Color(128, 0, 128));
        colors.put("sync.housekeeping.time", new Color(42, 223, 223));
        colors.put("sync.tx.apply.time", new Color(255, 165, 0));
        colors.put("sync.at.time", new Color(153, 0, 76));
        colors.put("sync.subscription.time", new Color(255, 105, 100));
        colors.put("sync.block.apply.time", new Color(0, 100, 100));
        colors.put("sync.commit.time", new Color(220, 130, 255));
        colors.put("sync.misc.time", Color.LIGHT_GRAY);
        colors.put("sync.payload.fullness", Color.WHITE);
        colors.put("sync.blocks.per.sec", Color.CYAN);
        colors.put("sync.all.tx.per.sec", Color.GREEN);
        colors.put("sync.system.tx.per.sec", new Color(135, 206, 250));
        colors.put("sync.at.count.per.block", new Color(153, 0, 76));
        colors.put("sync.upload.speed", new Color(128, 0, 0));
        colors.put("sync.download.speed", new Color(0, 100, 0));
        colors.put("gui.contrast.red", new Color(255, 120, 120));
        colors.put("gui.status.consistent", new Color(0, 128, 0));
        colors.put("gui.help.icon", new Color(128, 128, 128));
        return new ColorPalette("Default", colors);
    }

    /**
     * Updates the active color palette by reloading from the theme and applying
     * empty overrides.
     * This is typically called when the Look and Feel changes without a specific
     * profile being loaded.
     */
    public static void updatePalette() {
        updatePalette(Collections.emptyMap());
    }

    /**
     * Updates the active color palette by reloading the base and theme-specific
     * palettes,
     * and then applying a new set of overrides. This method orchestrates the full
     * layered loading logic.
     *
     * @param overrides A map of color keys to Color objects, typically from a
     *                  user-defined
     *                  Look and Feel profile. These are applied on top of all other
     *                  palettes.
     *                  Can be {@code null} or empty if no overrides are needed.
     */
    public static void updatePalette(Map<String, Color> overrides) {
        LookAndFeel laf = UIManager.getLookAndFeel();
        String basePaletteName;

        // 1. Determine base palette (light/dark)
        if (laf instanceof FlatLaf) {
            basePaletteName = ((FlatLaf) laf).isDark() ? "flat-dark" : "flat-light";
        } else {
            basePaletteName = "default";
        }

        logger.info("Attempting to load base color palette for Look and Feel: {}", basePaletteName);
        ColorPalette basePalette = ColorPalette.loadFromResources(PALETTES_PATH + basePaletteName + ".palette.json");

        if (basePalette.getName().equals("Empty") || basePalette.getName().equals("Error")) {
            logger.warn("Could not load base palette '{}', falling back to default.", basePaletteName);
            basePalette = defaultPalette;
        } else {
            logger.info("Successfully loaded base color palette: {}", basePalette.getName());
        }

        // This will be our working palette, starting with the base.
        ColorPalette themeSpecificPalette = basePalette;

        // 2. Determine theme-specific palette name for all themes
        String themePaletteName = null;
        if (laf instanceof IntelliJTheme.ThemeLaf) {
            String themeFileName = FlatLafPrefs.getState().get(FlatLafPrefs.KEY_LAF_THEME_FILE, null);
            if (themeFileName != null && !themeFileName.isEmpty()) {
                try {
                    // Replace slashes to create unique names for themes in subdirectories
                    themePaletteName = themeFileName.replace('/', '-').replaceFirst("\\.theme\\.json$", "");
                } catch (Exception e) {
                    logger.error("Error processing theme file name for palette: {}", themeFileName, e);
                }
            }
        } else { // For all other LaFs (built-in FlatLaf and standard Swing)
            themePaletteName = laf.getClass().getSimpleName();
        }

        // 3. Load and merge theme-specific palette if it exists
        if (themePaletteName != null) {
            final String specificPaletteResourcePath = PALETTES_PATH + THEME_PALETTES_SUBPATH + themePaletteName
                    + ".palette.json";
            logger.info("Attempting to load specific palette for theme '{}': {}", themePaletteName,
                    specificPaletteResourcePath);
            ColorPalette specificPalette = ColorPalette
                    .loadFromResources(specificPaletteResourcePath);

            if (!specificPalette.getName().equals("Empty") && !specificPalette.getName().equals("Error")) {
                logger.info("Found and loaded theme-specific palette '{}'. Merging with base palette.",
                        themePaletteName);
                themeSpecificPalette = new ColorPalette(basePalette, specificPalette.getColors());
            }
        }

        baseThemePalette = themeSpecificPalette;

        // 4. Apply user profile overrides
        if (overrides != null && !overrides.isEmpty()) {
            logger.info("Applying {} color overrides from profile.", overrides.size());
            activePalette = new ColorPalette(baseThemePalette, overrides);
        } else {
            activePalette = baseThemePalette;
        }
    }

    /**
     * Applies color overrides for a live preview without triggering a full UI
     * repaint.
     * This is used by components like the color chooser to show changes in
     * real-time
     * without the performance cost of {@code FlatLaf.updateUI()}.
     *
     * @param overrides A map of color keys to Color objects to be previewed.
     */
    public static void applyLiveOverrides(Map<String, Color> overrides) {
        if (baseThemePalette == null) {
            // Fallback to full update if base isn't set, though this shouldn't happen in
            // normal flow.
            updatePalette(overrides);
            return;
        }
        if (overrides != null && !overrides.isEmpty()) {
            activePalette = new ColorPalette(baseThemePalette, overrides);
        } else {
            activePalette = baseThemePalette;
        }
        // No FlatLaf.updateUI() call here for live preview.
    }

    /**
     * Applies color overrides and triggers a full UI repaint to make the changes
     * visible
     * across the entire application. This is used to finalize color settings from a
     * profile
     * or the color settings panel.
     *
     * @param overrides A map of color keys to Color objects to be applied.
     */
    public static void applyOverrides(Map<String, Color> overrides) {
        if (baseThemePalette == null) {
            updatePalette(overrides);
            return;
        }
        if (overrides != null && !overrides.isEmpty()) {
            activePalette = new ColorPalette(baseThemePalette, overrides);
        } else {
            activePalette = baseThemePalette;
        }
        // Force a UI update to apply the new colors everywhere
        SignumGUI.updateAllUIs();
    }

    /**
     * Retrieves a color from the currently active palette.
     * <p>
     * It first checks the {@code activePalette} (which includes theme and profile
     * overrides).
     * If the key is not found, it falls back to the hardcoded
     * {@code defaultPalette}.
     * If the key is still not found, it returns a bright magenta color to indicate
     * a missing key.
     *
     * @param key The key for the desired color (e.g., "peer.connected").
     * @return The resolved {@link Color}.
     */
    public static Color getColor(String key) {
        //
        // The default palette acts as a fallback for any keys not present in the active
        // theme palette.
        return activePalette.getColor(key, defaultPalette.getColor(key, Color.MAGENTA)); // Magenta as error color
    }

    /**
     * Returns a set of all known color keys from the default palette.
     * This is used to populate the color settings panel with all available options.
     *
     * @return A {@link Set} of all color keys.
     */
    public static Set<String> getAllKeys() {
        return defaultPalette.getAllKeys();
    }

    /**
     * Returns the color for a key from the base theme palette (without user profile
     * overrides).
     *
     * @param key The key for the desired color.
     * @return The resolved {@link Color} from the theme.
     */
    public static Color getThemeColor(String key) {
        return baseThemePalette.getColor(key, defaultPalette.getColor(key, Color.MAGENTA));
    }
}
