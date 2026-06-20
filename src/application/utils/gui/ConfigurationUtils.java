package application.utils.gui;

import application.module.appearance.AppearanceProfile;
import application.module.node.Signum;
import application.module.node.gui.configuration.LoggerProfile;
import application.module.node.gui.configuration.NodeProfile;
import application.utils.io.PathUtils;

import com.google.gson.JsonElement;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.*;
import java.io.FileInputStream;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigurationUtils {

    /**
     * Styles input components consistently using the global label font and standard
     * padding.
     *
     * @param comp The component to style.
     */
    public static void styleInputComponent(JComponent comp) {
        comp.setFont(UIManager.getFont("Label.font"));
        if (comp instanceof JTextField || comp instanceof JPasswordField) {
            comp.setBorder(BorderFactory.createCompoundBorder(
                    UIManager.getBorder("TextField.border"),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        }
    }

    /**
     * Calculates and sets a fixed height for a component based on font metrics and
     * standard icon sizes to ensure consistent UI layout across different systems.
     *
     * @param comp The component whose size should be fixed.
     */
    public static void fixComponentSize(JComponent comp) {
        comp.setPreferredSize(null);
        comp.setMinimumSize(null);

        // Calculate target height based on font metrics to ensure it scales with font
        // size
        Font font = comp.getFont() != null ? comp.getFont() : UIManager.getFont("Label.font");
        FontMetrics fm = comp.getFontMetrics(font);
        int fontHeight = fm.getHeight();

        // Ensure height is at least enough for the help icons used in the rows (usually
        // 16-18px)
        int iconHeight = Math.round(GuiConstants.getHelpIconSize());
        int targetHeight = Math.max(fontHeight + 10, iconHeight + 6);

        Dimension currentPref = comp.getPreferredSize();
        Dimension size = new Dimension(currentPref.width + 4, targetHeight);
        comp.setPreferredSize(size);
        comp.setMinimumSize(size);
        comp.setMaximumSize(new Dimension(Short.MAX_VALUE, targetHeight));
    }

    /**
     * Creates a custom {@link ListCellRenderer} for profile selection combo boxes.
     * Highlights the running and active profiles using bold fonts and
     * status-specific colors.
     *
     * @param runningProfileSupplier Supplier for the name of the currently running
     *                               profile.
     * @param activeProfileSupplier  Supplier for the name of the currently active
     *                               (applied) profile.
     * @return A custom cell renderer.
     */
    public static ListCellRenderer<Object> createProfileComboBoxRenderer(Supplier<String> runningProfileSupplier,
            Supplier<String> activeProfileSupplier) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String valStr = (value != null) ? value.toString().trim() : "";
                if (!valStr.isEmpty()) {
                    String running = runningProfileSupplier.get();
                    String active = activeProfileSupplier.get();
                    boolean isRunning = valStr.equals(running);
                    boolean isActive = valStr.equals(active);

                    if (isRunning || isActive) {
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    }
                    if (!isSelected) {
                        if (isRunning) {
                            c.setForeground(GuiColors.getApplied());
                        } else if (isActive) {
                            c.setForeground(GuiColors.getSaved());
                        } else {
                            c.setForeground(GuiColors.getUnsaved());
                        }
                    }
                }
                return c;
            }
        };
    }

    /**
     * Saves a set of properties to a file while attempting to preserve the existing
     * formatting and comments found in the original file.
     *
     * @param file        The path to the properties file.
     * @param props       The properties to save.
     * @param managedKeys A set of keys that are managed by the application. Keys
     *                    not in this
     *                    set found in the file will be preserved if not present in
     *                    the props.
     * @throws IOException If an I/O error occurs.
     */
    public static void savePropertiesPreservingFormat(Path file, Properties props, Set<String> managedKeys)
            throws IOException {
        List<String> lines = Files.exists(file) ? Files.readAllLines(file) : new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                newLines.add(line);
                continue;
            }

            int sepIdx = -1;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    sepIdx = i;
                    break;
                }
            }

            if (sepIdx != -1) {
                String key = line.substring(0, sepIdx).trim();
                if (props.containsKey(key)) {
                    String val = props.getProperty(key);
                    newLines.add(key + "=" + escapePropertyValue(val));
                    processedKeys.add(key);
                } else {
                    if (managedKeys == null || !managedKeys.contains(key)) {
                        newLines.add(line);
                    }
                }
            } else {
                newLines.add(line);
            }
        }

        for (String key : props.stringPropertyNames()) {
            if (!processedKeys.contains(key)) {
                newLines.add(key + "=" + escapePropertyValue(props.getProperty(key)));
            }
        }
        Files.write(file, newLines);
    }

    /**
     * Escapes special characters (tab, newline, etc.) in a string for safe storage
     * in a properties file.
     *
     * @param value The value to escape.
     * @return The escaped string.
     */
    public static String escapePropertyValue(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\f", "\\f");
    }

    /**
     * Ensures that a configuration file exists. Creates parent directories and an
     * empty file if it's missing.
     *
     * @param file The path to the file.
     */
    public static void ensureConfigFileExists(Path file) {
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.createFile(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Converts a {@link Color} object to a hexadecimal color string (e.g.,
     * "#ff0000").
     *
     * @param color The color to convert.
     * @return The hex string representation.
     */
    public static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Resolves the absolute path to a profile file within a specific configuration
     * structure.
     *
     * @param confFolder The base configuration folder.
     * @param subFolder  The sub-folder (e.g., "node", "logging").
     * @param fileName   The name of the file.
     * @return The resolved {@link Path}.
     */
    public static Path resolveProfilePath(String confFolder, String subFolder, String fileName) {
        return PathUtils.resolvePath(confFolder).resolve(subFolder).resolve(fileName);
    }

    /**
     * Loads the name of the applied profile from a metadata JSON file.
     *
     * @param profileJson Path to the profile.json metadata file.
     * @return The name of the applied profile, or null if not found.
     */
    public static String loadAppliedProfile(Path profileJson) {
        if (Files.exists(profileJson)) {
            try (BufferedReader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("appliedProfile")) {
                    return json.get("appliedProfile").getAsString();
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        return null;
    }

    /**
     * Updates the metadata JSON file with the name of the currently applied
     * profile.
     *
     * @param profileJson Path to the profile.json metadata file.
     * @param profileName The name of the profile being applied.
     */
    public static void updateAppliedProfile(Path profileJson, String profileName) {
        try {
            JsonObject metadata;
            if (Files.exists(profileJson)) {
                try (BufferedReader reader = Files.newBufferedReader(profileJson, StandardCharsets.UTF_8)) {
                    metadata = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception e) {
                    metadata = new JsonObject();
                }
            } else {
                metadata = new JsonObject();
            }

            metadata.addProperty("appliedProfile", profileName);
            if (Files.notExists(profileJson.getParent())) {
                Files.createDirectories(profileJson.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(profileJson, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(metadata, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Renames a profile file. Checks if the destination file already exists before
     * moving.
     *
     * @param parent  The parent component for showing error dialogs.
     * @param oldFile Path to the existing profile file.
     * @param newFile Path to the new profile destination.
     * @param oldName Original profile name.
     * @param newName New profile name.
     * @return true if the rename was successful.
     * @throws IOException If a file movement error occurs.
     */
    public static boolean confirmAndRenameProfile(Component parent, Path oldFile, Path newFile, String oldName,
            String newName) throws IOException {
        if (Files.exists(newFile)) {
            JOptionPane.showMessageDialog(parent, "A profile with the name '" + newName + "' already exists.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (Files.exists(oldFile)) {
            Files.move(oldFile, newFile);
            return true;
        }
        return false;
    }

    /**
     * Configures a set of toolbar buttons with consistent icons and sizes for
     * profile management.
     *
     * @param newBtn             Button for creating a new profile.
     * @param saveBtn            Button for saving the current profile.
     * @param applyBtn           Button for applying the profile.
     * @param renameBtn          Button for renaming the profile.
     * @param deleteBtn          Button for deleting the profile.
     * @param reloadBtn          Button for reloading from disk.
     * @param refreshBtn         Button for refreshing the profile list.
     * @param resetToDefaultsBtn Button for resetting to application defaults.
     */
    public static void configureProfileToolbar(
            JButton newBtn, JButton saveBtn, JButton applyBtn,
            JButton renameBtn, JButton deleteBtn, JButton reloadBtn, JButton refreshBtn, JButton resetToDefaultsBtn) {
        float iconSize = GuiConstants.getHelpIconSize();
        Color iconColor = GuiColors.getButtonIcon();

        if (newBtn != null) {
            newBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FILE_O, iconSize, iconColor));
            fixComponentSize(newBtn);
        }
        if (saveBtn != null) {
            saveBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, iconSize, iconColor));
            fixComponentSize(saveBtn);
        }
        if (applyBtn != null) {
            applyBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE_O, iconSize, iconColor));
            fixComponentSize(applyBtn);
        }
        if (renameBtn != null) {
            renameBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.PENCIL_SQUARE_O, iconSize, iconColor));
            fixComponentSize(renameBtn);
        }
        if (deleteBtn != null) {
            deleteBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH_O, iconSize, iconColor));
            fixComponentSize(deleteBtn);
        }
        if (reloadBtn != null) {
            reloadBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.RECYCLE, iconSize, iconColor));
            fixComponentSize(reloadBtn);
        }
        if (refreshBtn != null) {
            refreshBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.REFRESH, iconSize, iconColor));
            fixComponentSize(refreshBtn);
        }
        if (resetToDefaultsBtn != null) {
            resetToDefaultsBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.UNDO, iconSize, iconColor));
            fixComponentSize(resetToDefaultsBtn);
        }
    }

    /**
     * Creates a {@link JPanel} containing a visual legend for the configuration
     * status colors.
     *
     * @param parent The parent component used for help dialog positioning.
     * @return A panel showing color boxes and descriptions.
     */
    public static JPanel createLegendPanel(Component parent) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        panel.setBorder(new EmptyBorder(0, 0, 5, 0));

        panel.add(createLegendItem(GuiColors.getUnsaved(), "Unsaved values"));
        panel.add(createLegendItem(GuiColors.getSaved(), "Saved values"));
        panel.add(createLegendItem(GuiColors.getApplied(), "Applied values"));

        JButton helpBtn = new HelpButton();
        helpBtn.setToolTipText("Detailed Color Legend");
        helpBtn.addActionListener(e -> showColorLegendHelp(parent));
        panel.add(helpBtn);

        return panel;
    }

    /**
     * Helper to create an individual legend item consisting of a color box and
     * label.
     *
     * @param color The status color.
     * @param text  The description of what the color signifies.
     * @return A configured {@link JPanel}.
     */
    private static JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel colorBox = new JLabel("\u25A0");
        colorBox.setForeground(color);
        item.add(colorBox);
        item.add(new JLabel(text));
        return item;
    }

    /**
     * Shows a detailed information dialog explaining the color-coding scheme used
     * in
     * configuration panels.
     *
     * @param parent The component used as the dialog's owner.
     */
    public static void showColorLegendHelp(Component parent) {
        String msg = "<html><body style='width: 350px'>" +
                "<h3>Color Coding Legend</h3>" +
                "<p>The configuration values are color-coded to indicate their current status:</p>" +
                "<ul>" +
                "<li><b><font color='" + toHex(GuiColors.getUnsaved()) + "'>\u25A0 Unsaved Values:</font></b> " +
                "These values have been modified in the UI but have not yet been saved to the configuration file. " +
                "Properties with unsaved changes are marked with an asterisk (*).</li>" +
                "<li><b><font color='" + toHex(GuiColors.getSaved()) + "'>\u25A0 Saved Values:</font></b> " +
                "These values are saved in the currently loaded profile on disk, but they differ from the values " +
                "currently being used by the running node.</li>" +
                "<li><b><font color='" + toHex(GuiColors.getApplied()) + "'>\u25A0 Applied Values:</font></b> " +
                "These values match exactly what the node is currently using. Note that most changes require a restart to take effect.</li>"
                +
                "</ul>" +
                "</body></html>";
        JOptionPane.showMessageDialog(parent, msg, "Color Legend", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Updates the foreground color of a profile selection combo box based on
     * whether the
     * selected item is currently running, active, or modified.
     *
     * @param combo   The combo box to update.
     * @param running The name of the running profile.
     * @param active  The name of the active (applied) profile.
     */
    public static void updateProfileComboBoxColor(JComboBox<String> combo, String running, String active) {
        String selected = (String) combo.getSelectedItem();
        if (selected != null && selected.trim().equals(running)) {
            combo.setForeground(GuiColors.getApplied());
        } else if (selected != null && selected.trim().equals(active)) {
            combo.setForeground(GuiColors.getSaved());
        } else {
            combo.setForeground(UIManager.getColor("ComboBox.foreground"));
        }
    }

    /**
     * Resolves the path to the profile metadata JSON file for a specific
     * configuration category.
     *
     * @param confFolder Base configuration folder.
     * @param subFolder  Configuration sub-folder (e.g., "node").
     * @return The path to profile.json.
     */
    public static Path getProfileMetadataPath(String confFolder, String subFolder) {
        return PathUtils.resolvePath(confFolder).resolve(subFolder).resolve("profile.json");
    }

    /**
     * Scans a directory for properties-based configuration profiles.
     *
     * @param folder          The directory to scan.
     * @param excludeFileName A filename to exclude (usually the base default file).
     * @return A list of profile names found (filename minus extension).
     */
    public static List<String> fetchProfileNames(Path folder, String excludeFileName) {
        if (Files.notExists(folder))
            return new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".properties") && !name.equals(excludeFileName))
                    .map(name -> name.substring(0, name.length() - 11))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Reads the logging configuration according to the priority order.
     */
    public static LoggerProfile loadEffectiveLoggerProfile(String confFolder, String profileName) {
        LoggerProfile effective = new LoggerProfile(profileName);
        effective.applyInternalDefaults();

        Path confPath = PathUtils.resolvePath(confFolder);
        Path logConfPath = confPath.resolve(Signum.NODE_LOGGING_SUBFOLDER);
        Path pathToLoad = null;

        // 1. Search based on the active name
        Path specificPath = logConfPath.resolve(profileName + ".properties");
        if (Files.exists(specificPath)) {
            pathToLoad = specificPath;
        } else {
            // 2. Fallback to logging.properties
            if (!Signum.LOGGING_PROPERTIES_NAME.equals(profileName)) {
                Path fallbackPath = logConfPath.resolve(Signum.LOGGING_PROPERTIES_NAME + ".properties");
                if (Files.exists(fallbackPath))
                    pathToLoad = fallbackPath;
            }
            // 3. Fallback to defaults (in logging folder, then in conf folder)
            if (pathToLoad == null) {
                Path defSub = logConfPath.resolve(Signum.DEFAULT_LOGGING_PROPERTIES_NAME + ".properties");
                if (Files.exists(defSub)) {
                    pathToLoad = defSub;
                } else {
                    Path defConf = confPath.resolve(Signum.DEFAULT_LOGGING_PROPERTIES_NAME + ".properties");
                    if (Files.exists(defConf))
                        pathToLoad = defConf;
                }
            }
        }

        if (pathToLoad != null) {
            try (FileInputStream is = new FileInputStream(pathToLoad.toFile())) {
                Properties fileProps = new Properties();
                fileProps.load(is);
                effective.getProperties().putAll(fileProps);
            } catch (IOException e) {
                // Logger initialization has not happened yet, using System.err
                System.err.println("Failed to load logger properties: " + e.getMessage());
            }
        }
        return effective;
    }

    /**
     * Reads the node configuration according to the priority order.
     */
    public static NodeProfile loadEffectiveNodeProfile(String confFolder, String profileName) {
        NodeProfile effective = new NodeProfile(profileName);
        Path confPath = PathUtils.resolvePath(confFolder);
        Path nodeConfPath = confPath.resolve(Signum.NODE_SUBFOLDER);
        Path pathToLoad = null;

        // 1. Search based on the active name
        Path specificPath = nodeConfPath.resolve(profileName + ".properties");
        if (Files.exists(specificPath)) {
            pathToLoad = specificPath;
        } else {
            // 2. Fallback to node.properties
            if (!Signum.PROPERTIES_NAME.equals(profileName)) {
                Path fallbackPath = nodeConfPath.resolve(Signum.PROPERTIES_NAME + ".properties");
                if (Files.exists(fallbackPath))
                    pathToLoad = fallbackPath;
            }
            // 3. & 4. Fallback to defaults (in node folder, then in conf folder)
            if (pathToLoad == null) {
                Path defSub = nodeConfPath.resolve(Signum.DEFAULT_PROPERTIES_NAME + ".properties");
                if (Files.exists(defSub)) {
                    pathToLoad = defSub;
                } else {
                    Path defConf = confPath.resolve(Signum.DEFAULT_PROPERTIES_NAME + ".properties");
                    if (Files.exists(defConf))
                        pathToLoad = defConf;
                }
            }
        }

        if (pathToLoad != null) {
            try (FileInputStream is = new FileInputStream(pathToLoad.toFile())) {
                effective.getProperties().load(is);
            } catch (IOException e) {
                System.err.println("Failed to load node properties: " + e.getMessage());
            }
        }
        return effective;
    }

    /**
     * Reads a Look and Feel profile from the gui-settings.json file.
     */
    public static AppearanceProfile loadLookAndFeelProfile(Path settingsPath, String profileName) {
        AppearanceProfile profile = new AppearanceProfile(profileName);
        if (Files.notExists(settingsPath))
            return profile;

        try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
            if (settings.has("lookAndFeelProfiles")) {
                JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                if (profiles.has(profileName)) {
                    JsonObject data = profiles.getAsJsonObject(profileName);

                    if (data.has("theme")) {
                        profile.setThemeClassName(data.get("theme").getAsString());
                    }
                    if (data.has("font")) {
                        profile.setGlobalFont(parseJsonFont(data.getAsJsonObject("font")));
                    }
                    if (data.has("consoleFont")) {
                        profile.setConsoleFont(parseJsonFont(data.getAsJsonObject("consoleFont")));
                    }
                    if (data.has("colorOverrides")) {
                        Map<String, Color> overrides = new java.util.HashMap<>();
                        JsonObject colors = data.getAsJsonObject("colorOverrides");
                        for (Map.Entry<String, JsonElement> entry : colors.entrySet()) {
                            overrides.put(entry.getKey(), Color.decode(entry.getValue().getAsString()));
                        }
                        profile.setColorOverrides(overrides);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load LAF profile: " + e.getMessage());
        }
        return profile;
    }

    private static Font parseJsonFont(JsonObject obj) {
        if (obj == null)
            return null;
        try {
            return new Font(
                    obj.get("family").getAsString(),
                    obj.get("style").getAsInt(),
                    obj.get("size").getAsInt());
        } catch (Exception e) {
            return null;
        }
    }
}
