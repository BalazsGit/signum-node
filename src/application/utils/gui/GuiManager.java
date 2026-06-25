package application.utils.gui;

import com.formdev.flatlaf.FlatLaf;

import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import java.awt.Color;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized manager for application-wide GUI defaults.
 * 
 * Provides a layered architecture for GUI configuration:
 * ┌─────────────────────────────────────────────────┐
 * │ Layer 3: User Settings (gui-settings.json)      │  ← Highest priority, runtime editable
 * ├─────────────────────────────────────────────────┤
 * │ Layer 2: AppearanceProfile overrides            │  ← Per-LAF-profile settings
 * ├─────────────────────────────────────────────────┤
 * │ Layer 1: Application Defaults                   │  ← FlatLaf gui-defaults.properties  
 * ├─────────────────────────────────────────────────┤
 * │ Layer 0: Framework Defaults                     │  ← Lowest (FlatLaf built-in defaults)
 * └─────────────────────────────────────────────────┘
 * 
 * Key features:
 * - Application-level defaults (e.g., tabLayoutPolicy = SCROLL_TAB_LAYOUT by default)
 * - Overrideable via gui-settings.json at runtime
 * - Runtime API for dynamic changes via AppearancePanel
 * - Persistence to gui-settings.json
 * - Extensible architecture for additional GUI properties (colors, fonts, etc.)
 */
public class GuiManager {

    // Singleton instance
    private static volatile GuiManager instance;

    /**
     * Returns the singleton instance of GuiManager.
     */
    public static GuiManager getInstance() {
        if (instance == null) {
            synchronized (GuiManager.class) {
                if (instance == null) {
                    instance = new GuiManager();
                }
            }
        }
        return instance;
    }

    // ─── Default Settings ──────────────────────────────
    static final int DEFAULT_TAB_LAYOUT_POLICY = JTabbedPane.SCROLL_TAB_LAYOUT;

    // ─── Configurable State ────────────────────────────
    private int tabLayoutPolicy = DEFAULT_TAB_LAYOUT_POLICY;
    
    /** 
     * Color overrides: UIManager key -> hex color value.
     * Extensible to other custom UI colors used throughout the application.
     */
    private final Map<String, String> colorOverrides = new HashMap<>();
    
    private Path settingsPath;
    private boolean initialized = false;
    private static final Logger LOGGER = LoggerFactory.getLogger(GuiManager.class);

    private GuiManager() {
        // Private constructor for singleton
    }

    // ─── Initialization ────────────────────────────────

    /**
     * Initializes the GuiManager with global GUI defaults.
     * Must be called ONCE before any UI components are created, typically during
     * application startup (before LookAndFeel setup).
     * 
     * IMPORTANT: This method only LOADS settings from JSON. It does NOT apply them to UIManager yet.
     * Call applyDefaultsAfterLaf() AFTER UIManager.setLookAndFeel() to ensure FlatLaf defaults
     * are loaded first, then our overrides are applied on top.
     * 
     * @param guiSettingsPath Path to gui-settings.json for loading saved overrides.
     */
    public void init(Path guiSettingsPath) {
        if (initialized) {
            LOGGER.debug("[GUI-DEBUG] GuiManager already initialized, skipping re-init");
            return;
        }
        this.settingsPath = guiSettingsPath;
        loadFromJson(guiSettingsPath);
        LOGGER.info("[GUI-DEBUG] GuiManager initialized. Loaded tabLayoutPolicy={}, colorOverrides={}", 
                getTabLayoutPolicyName(), colorOverrides.size());
        initialized = true;
    }

    /**
     * Applies the configured GUI defaults via UIManager.put().
     * 
     * CRITICAL: This MUST be called AFTER UIManager.setLookAndFeel() in the init flow.
     * FlatLaf loads its own defaults during setLookAndFeel(), so any UIManager.put() calls
     * BEFORE setLookAndFeel() will be silently overwritten by FlatLaf's internal defaults.
     * 
     * Correct init order (FlatLaf docs):
     *   1. FlatLaf.registerCustomDefaultsSource(packageName)
     *   2. UIManager.setLookAndFeel(themeClass)       ← LAF loads its defaults here
     *   3. GuiManager.applyDefaultsAfterLaf()          ← Our overrides applied AFTER
     *   4. Swing components created                    ← Inherit correct values
     */
    public void applyDefaultsAfterLaf() {
        if (!initialized) {
            LOGGER.warn("[GUI-DEBUG] applyDefaultsAfterLaf() called before init(). Applying defaults anyway.");
        }
        
        UIManager.put("TabbedPane.tabLayoutPolicy", tabLayoutPolicy);
        int actualValue = UIManager.getInt("TabbedPane.tabLayoutPolicy");
        LOGGER.info("[GUI-DEBUG] Applied TabbedPane.tabLayoutPolicy={}. UIManager now reports: {} (SCROLL={})", 
                tabLayoutPolicy, actualValue, JTabbedPane.SCROLL_TAB_LAYOUT);
        
        for (Map.Entry<String, String> entry : colorOverrides.entrySet()) {
            try {
                UIManager.put(entry.getKey(), Color.decode(entry.getValue()));
            } catch (Exception e) {
                LOGGER.debug("[GUI-DEBUG] Ignoring invalid color override for {}: {}", entry.getKey(), e.getMessage());
            }
        }
        if (!colorOverrides.isEmpty()) {
            LOGGER.info("[GUI-DEBUG] Applied {} color overrides", colorOverrides.size());
        }
    }

    /**
     * @deprecated Use applyDefaultsAfterLaf() instead.
     * Kept for backward compatibility but will not be called in the standard init flow.
     */
    @Deprecated
    private void applyDefaults() {
        applyDefaultsAfterLaf();
    }

    /**
     * Loads GUI settings from the gui-settings.json file.
     * Reads "lookAndFeelSettings.tabLayoutPolicy" and "lookAndFeelSettings.colorOverrides" if they exist.
     */
    private void loadFromJson(Path settingsPath) {
        if (settingsPath == null || !Files.exists(settingsPath)) {
            return; // Use defaults
        }

        try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return;
            }

            com.google.gson.JsonObject settings = parsed.getAsJsonObject();
            if (settings.has("lookAndFeelSettings")) {
                com.google.gson.JsonObject lafSettings = settings.getAsJsonObject("lookAndFeelSettings");
                
                // Load tab layout policy
                if (lafSettings.has("tabLayoutPolicy")) {
                    String policyStr = lafSettings.get("tabLayoutPolicy").getAsString().toLowerCase();
                    this.tabLayoutPolicy = parseTabLayoutPolicy(policyStr);
                }
                
                // Load color overrides
                if (lafSettings.has("colorOverrides")) {
                    com.google.gson.JsonObject colors = lafSettings.getAsJsonObject("colorOverrides");
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : colors.entrySet()) {
                        colorOverrides.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        } catch (Exception e) {
            // Ignore parse errors, use defaults
        }
    }

    // ─── Persistence ──────────────────────────────────

    /**
     * Saves the current GUI settings to gui-settings.json.
     * This persists lookAndFeelSettings section (tabLayoutPolicy + colorOverrides).
     */
    public void saveToJson() {
        if (settingsPath == null) {
            return;
        }
        
        try {
            com.google.gson.JsonObject existingSettings = new com.google.gson.JsonObject();
            
            // Load existing settings to preserve unrelated data
            if (Files.exists(settingsPath)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        existingSettings = parsed.getAsJsonObject();
                    }
                }
            }
            
            // Update lookAndFeelSettings section
            com.google.gson.JsonObject lafSettings = new com.google.gson.JsonObject();
            lafSettings.addProperty("tabLayoutPolicy", getTabLayoutPolicyName());
            
            if (!colorOverrides.isEmpty()) {
                com.google.gson.JsonObject colorObj = new com.google.gson.JsonObject();
                for (Map.Entry<String, String> entry : colorOverrides.entrySet()) {
                    colorObj.addProperty(entry.getKey(), entry.getValue());
                }
                lafSettings.add("colorOverrides", colorObj);
            }
            
            existingSettings.add("lookAndFeelSettings", lafSettings);
            
            // Write back
            if (!Files.exists(settingsPath.getParent())) {
                Files.createDirectories(settingsPath.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(existingSettings, writer);
            }
        } catch (Exception e) {
            // Silently fail - settings persistence is best-effort
        }
    }

    // ─── Tab Layout Policy API ────────────────────────

    /**
     * Parses a string representation of tab layout policy to its integer constant.
     * Case-insensitive: "scroll", "Scroll", "SCROLL" all map to SCROLL_TAB_LAYOUT.
     * Any unrecognized value defaults to SCROLL_TAB_LAYOUT.
     */
    static int parseTabLayoutPolicy(String policyStr) {
        if (policyStr != null && "wrap".equalsIgnoreCase(policyStr)) {
            return JTabbedPane.WRAP_TAB_LAYOUT;
        }
        return JTabbedPane.SCROLL_TAB_LAYOUT;
    }

    /**
     * Returns the current tab layout policy.
     */
    public int getTabLayoutPolicy() {
        return tabLayoutPolicy;
    }

    /**
     * Sets a new tab layout policy and applies it globally.
     */
    public void setTabLayoutPolicy(String policy) {
        int newPolicy = parseTabLayoutPolicy(policy.toLowerCase());
        if (this.tabLayoutPolicy != newPolicy) {
            this.tabLayoutPolicy = newPolicy;
            UIManager.put("TabbedPane.tabLayoutPolicy", newPolicy);
            FlatLaf.updateUI();
        }
    }

    /**
     * Returns a human-readable string representation of the current tab layout policy.
     */
    public String getTabLayoutPolicyName() {
        return tabLayoutPolicy == JTabbedPane.SCROLL_TAB_LAYOUT ? "scroll" : "wrap";
    }

    // ─── Color Overrides API ──────────────────────────

    /**
     * Adds or updates a color override.
     * 
     * @param uiKey The UIManager key (e.g., "Button.background")
     * @param hexColor Hex color value (e.g., "#FF5733")
     */
    public void setColorOverride(String uiKey, String hexColor) {
        colorOverrides.put(uiKey, hexColor);
        try {
            UIManager.put(uiKey, Color.decode(hexColor));
        } catch (Exception e) {
            // Ignore invalid colors
        }
    }

    /**
     * Removes a color override.
     */
    public void removeColorOverride(String uiKey) {
        colorOverrides.remove(uiKey);
    }

    /**
     * Returns all current color overrides (unmodifiable view).
     */
    public Map<String, String> getColorOverrides() {
        return Map.copyOf(colorOverrides);
    }

    // ─── State ────────────────────────────────────────

    /**
     * Checks if the GuiManager has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
