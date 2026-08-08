package application.module.appearance;

import application.api.Module;
import application.api.ModuleContext;
import application.module.appearance.gui.AppearancePanel;
import application.module.appearance.gui.laf.FlatLafPrefs;
import application.module.node.gui.GuiResources;
import application.module.node.props.Props;
import application.utils.gui.ColorPaletteManager;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiFontManager;
import application.utils.io.PathUtils;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import java.awt.Color;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AppearanceModule implements Module {
    private static final Logger logger = LoggerFactory.getLogger(AppearanceModule.class);
    private AppearancePanel settingsPanel;
    private static final List<Runnable> appearanceListeners = new ArrayList<>();

    @Override
    public String getId() {
        return "gui-appearance";
    }

    @Override
    public String getDisplayName() {
        return "Appearance";
    }

    @Override
    public void init(ModuleContext context) {
        logger.info("Initializing Appearance Module...");

        // Register the icon font before any UI components are created
        IconFontSwing.register(FontAwesome.getIconFont());

        // Initialize FlatLaf preferences globally to prevent NPE in sub-panels
        FlatLafPrefs.init();

        // Register custom defaults source for FlatLaf (accent colors, etc.)
        String packageName = GuiResources.FLATLAF_RESOURCE_PATH;
        if (packageName.endsWith("/")) {
            packageName = packageName.substring(0, packageName.length() - 1);
        }
        FlatLaf.registerCustomDefaultsSource(packageName);

        // Initialize GuiManager BEFORE LAF setup so defaults are applied
        // The signum.properties file in resources/flatlaf will be picked up automatically
        application.utils.gui.GuiManager.getInstance().init(getGuiSettingsPath(null));

        // Apply saved settings
        setupInitialLookAndFeel(null);

        // Now it is safe to create the UI panel
        this.settingsPanel = new AppearancePanel(null, null);
    }

    // Local constants to eliminate Signum bridge dependency (v4.1 migration 2026-08-07)
    private static final String CONF_FOLDER = "conf";
    private static final org.apache.commons.cli.Option CONF_FOLDER_OPTION =
            new org.apache.commons.cli.Option("c", "config-dir", true,
                    "Path to configuration directory (default: ./conf)");

    private static Path getGuiSettingsPath(String[] args) {
        String confFolder = CONF_FOLDER;
        try {
            if (args != null) {
                org.apache.commons.cli.Options options = new org.apache.commons.cli.Options();
                options.addOption(CONF_FOLDER_OPTION);
                CommandLine cmd = new DefaultParser().parse(options, args);
                if (cmd.hasOption(CONF_FOLDER_OPTION.getOpt())) {
                    confFolder = cmd.getOptionValue(CONF_FOLDER_OPTION.getOpt());
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing command line arguments for config folder", e);
        }

        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        Path confPath = PathUtils.resolvePath(confFolder);
        Path nodePath = confPath.resolve("node");
        // Inline Signum.resolvePropertiesPath() to remove bridge dependency (v4.1 migration 2026-08-07)
        Path nodePropsFile = null;
        Path candidate = nodePath.resolve("node.properties");
        if (Files.exists(candidate)) {
            nodePropsFile = candidate;
        } else {
            candidate = confPath.resolve("node-default.properties");
            if (Files.exists(candidate)) {
                nodePropsFile = candidate;
            }
        }

        if (nodePropsFile != null && Files.exists(nodePropsFile)) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(nodePropsFile.toFile())) {
                Properties nodeProps = new Properties();
                nodeProps.load(in);
                settingsDir = nodeProps.getProperty(Props.SETTINGS_DIR.getName(), settingsDir);
            } catch (Exception e) {
                // ignore
            }
        }
        return PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
    }

    /**
     * Sets up the Look and Feel based on saved settings.
     * Can be called before the module system is fully initialized (e.g. from main).
     * 
     * Init order follows FlatLaf best practices:
     *   1. registerCustomDefaultsSource() — BEFORE any LAF setup
     *   2. UIManager.setLookAndFeel() — LAF loads its internal defaults
     *   3. GuiManager.applyDefaultsAfterLaf() — Our overrides on top of LAF defaults
     *   4. Font/Color palette updates
     *   5. Swing components created — inherit correct values
     */
    public static void setupInitialLookAndFeel(String[] args) {
        try {
            Path settingsPath = getGuiSettingsPath(args);

            // Initialize FlatLaf preferences early so ColorPaletteManager can access them
            FlatLafPrefs.init();

            String themeClassName = FlatDarkLaf.class.getName(); // Default theme
            Map<String, Color> colorOverrides = null;
            Font fontToApply = null;
            Font consoleFontToApply = null;

            if (Files.exists(settingsPath)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        JsonObject settings = parsed.getAsJsonObject();

                        // Read enableGPU from nested "appearance" object (hierarchical storage)
                        // Falls back to root level for backward compatibility
                        boolean enableGPU = false;
                        if (settings.has("appearance") && settings.get("appearance").isJsonObject()) {
                            JsonObject appearanceObj = settings.get("appearance").getAsJsonObject();
                            if (appearanceObj.has("enableGPU")) {
                                enableGPU = appearanceObj.get("enableGPU").getAsBoolean();
                            }
                        } else if (settings.has("enableGPU")) {
                            // Backward compatibility: read from root level
                            enableGPU = settings.get("enableGPU").getAsBoolean();
                        }
                        if (enableGPU) {
                            System.setProperty("sun.java2d.opengl", "true");
                        }

                        String profileName = settings.has("lastSelectedLafProfile")
                                ? settings.get("lastSelectedLafProfile").getAsString()
                                : "gui";

                        AppearanceProfile profile = ConfigurationUtils.loadLookAndFeelProfile(settingsPath,
                                profileName);
                        if (profile != null) {
                            if (profile.getThemeClassName() != null) {
                                themeClassName = profile.getThemeClassName();
                            }
                            fontToApply = profile.getGlobalFont();
                            consoleFontToApply = profile.getConsoleFont();
                            colorOverrides = profile.getColorOverrides();
                        }
                    }
                }
            }

            // Register custom defaults source for FlatLaf (accent colors, etc.)
            String packageName = GuiResources.FLATLAF_RESOURCE_PATH;
            if (packageName.endsWith("/")) {
                packageName = packageName.substring(0, packageName.length() - 1);
            }
            FlatLaf.registerCustomDefaultsSource(packageName);

            if (themeClassName != null) {
                // Step 2: Set LookAndFeel — FlatLaf loads its internal defaults here
                UIManager.setLookAndFeel(themeClassName);
                
                // Step 3: Apply GuiManager defaults AFTER LAF setup.
                // CRITICAL: Must be after setLookAndFeel() so our overrides are NOT
                // silently overwritten by FlatLaf's internal defaults.
                application.utils.gui.GuiManager.getInstance().applyDefaultsAfterLaf();
                
                updateCommonFontKeys(fontToApply);
                updateCommonConsoleFontKeys(consoleFontToApply);
                ColorPaletteManager.updatePalette(colorOverrides);
                
                logger.info("[GUI-DEBUG] Initial LAF setup complete. Theme={}, TabLayoutPolicy={}", 
                        themeClassName, 
                        application.utils.gui.GuiManager.getInstance().getTabLayoutPolicyName());
            } else {
                FlatDarkLaf.setup();
                application.utils.gui.GuiManager.getInstance().applyDefaultsAfterLaf();
                ColorPaletteManager.updatePalette(null);
            }
        } catch (Exception e) {
            logger.warn("Could not apply saved Look and Feel, falling back to default.", e);
            FlatDarkLaf.setup();
            // Even on fallback, apply our defaults after LAF setup
            application.utils.gui.GuiManager.getInstance().applyDefaultsAfterLaf();
            ColorPaletteManager.updatePalette(null);
        }
    }

    /**
     * Regisztrál egy komponenst vagy logikát, amit értesíteni kell a kinézet
     * változásáról.
     */
    public static void registerAppearanceListener(Runnable listener) {
        appearanceListeners.add(listener);
    }

    public static void removeAppearanceListener(Runnable listener) {
        appearanceListeners.remove(listener);
    }

    /**
     * Updates all windows and components in the application to reflect UI changes.
     */
    public static void updateAllUIs() {
        Map<String, Color> overrides = null;
        var panel = AppearancePanel.getInstance();
        if (panel != null && panel.getColorSettingsPanel() != null) {
            overrides = panel.getColorSettingsPanel().getCurrentOverrides();
        }

        // 1. Update the color palette
        ColorPaletteManager.updatePalette(overrides);

        // 2. Refresh basic Swing UI
        if (UIManager.getLookAndFeel() instanceof FlatLaf) {
            FlatLaf.updateUI();
        } else {
            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
            }
        }

        // Re-apply global tab layout policy after UI refresh to ensure it persists
        // across runtime theme changes (flatlaf properties may be reset by updateUI)
        UIManager.put("TabbedPane.tabLayoutPolicy", application.utils.gui.GuiManager.getInstance().getTabLayoutPolicy());

        FlatLaf.revalidateAndRepaintAllFramesAndDialogs();

        // 3. Értesítjük a feliratkozottakat (pl. SignumGUI), hogy végezzék el az egyedi
        // frissítéseket
        for (Runnable listener : appearanceListeners) {
            listener.run();
        }
    }

    /**
     * Updates the common font keys in UIManager.
     * 
     * @param font The new font to apply, or null to revert to system defaults.
     */
    public static void updateCommonFontKeys(Font font) {
        GuiFontManager.updateUIManager(font);
    }

    /**
     * Updates the common font keys for monospaced/console areas.
     */
    public static void updateCommonConsoleFontKeys(Font font) {
        GuiFontManager.updateConsoleFont(font);
    }

    public static Font getActiveCustomFont() {
        return UIManager.getFont("Label.font");
    }

    public static Font getActiveConsoleFont() {
        Font f = UIManager.getFont("TextArea.font");
        return f != null ? f : new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }

    @Override
    public void start() {
        logger.info("Appearance Module started.");
    }

    @Override
    public void stop() {
    }

    @Override
    public JComponent getUI() {
        return settingsPanel;
    }
}