package brs.gui;

import brs.props.Props;
import brs.util.PathUtils;
import brs.gui.laf.FlatLafPanel;
import brs.gui.laf.LookAndFeelsComboBox;
import brs.gui.laf.FlatLafPrefs;
import brs.gui.laf.intellijthemes.IJThemesPanel;
import com.formdev.flatlaf.*;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.LoggingFacade;
import com.formdev.flatlaf.util.SystemInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.UIManager.LookAndFeelInfo;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.nimbus.NimbusLookAndFeel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class LookAndFeelPanel extends JPanel {

    private static LookAndFeelPanel instance;
    private final String confFolder;
    private final Runnable backAction;
    private JComboBox<String> profileComboBox;
    private ColorSettingsPanel colorSettingsPanel;

    public static LookAndFeelPanel getInstance() {
        return instance;
    }

    public ColorSettingsPanel getColorSettingsPanel() {
        return colorSettingsPanel;
    }

    public LookAndFeelPanel(Runnable restartAction, String confFolder, Runnable backAction) {
        super(new BorderLayout());
        this.confFolder = confFolder;
        this.backAction = backAction;

        // Initialize FlatLafPrefs as some FlatLaf demo components might rely on it
        instance = this;
        FlatLafPrefs.init("/flatlaf-settings");

        initUI();
        // loadSettings();
    }

    private void initUI() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(5, 5, 5, 5));

        JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftHeader.setOpaque(false);
        JButton backButton = new JButton("Back to Console",
                IconFontSwing.buildIcon(FontAwesome.ARROW_LEFT, GuiConstants.getHelpIconSize(),
                        UIManager.getColor("Label.foreground")));
        backButton.addActionListener(e -> {
            if (backAction != null)
                backAction.run();
        });
        leftHeader.add(backButton);

        JLabel titleLabel = new JLabel("Look and Feel Settings", SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));

        header.add(leftHeader, BorderLayout.WEST);
        header.add(titleLabel, BorderLayout.CENTER);

        // --- Profile Panel ---
        JPanel profilePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Look and Feel Profile:"));

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPreferredSize(new Dimension(200, 25));
        profilePanel.add(profileComboBox);

        JButton loadProfileBtn = new JButton("Load Profile");
        loadProfileBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN, GuiConstants.getHelpIconSize(),
                GuiColors.getButtonIcon()));
        loadProfileBtn.setToolTipText("Load selected profile");
        loadProfileBtn.addActionListener(e -> loadProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(loadProfileBtn);

        JButton saveProfileBtn = new JButton("Save Profile");
        saveProfileBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        saveProfileBtn.setToolTipText("Save Look and Feel Profile");
        saveProfileBtn.addActionListener(e -> saveProfile());
        profilePanel.add(saveProfileBtn);

        JButton renameProfileBtn = new JButton("Rename Profile");
        renameProfileBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.PENCIL_SQUARE_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        renameProfileBtn.setToolTipText("Rename selected profile");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        JButton deleteProfileBtn = new JButton("Delete Profile");
        deleteProfileBtn.setIcon(
                IconFontSwing.buildIcon(FontAwesome.TRASH_O, GuiConstants.getHelpIconSize(),
                        GuiColors.getButtonIcon()));
        deleteProfileBtn.setToolTipText("Delete selected profile");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        profileComboBox.addActionListener(e -> {
            String selected = (String) profileComboBox.getSelectedItem();
            boolean isDefault = "Default".equals(selected);
            renameProfileBtn.setEnabled(!isDefault);
            deleteProfileBtn.setEnabled(!isDefault);
        });

        JButton helpBtn = new JButton(
                IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, GuiConstants.getHelpIconSize(),
                        GuiColors.getHelpIcon()));
        helpBtn.setBorder(BorderFactory.createEmptyBorder());
        helpBtn.setContentAreaFilled(false);
        helpBtn.setFocusPainted(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info about Look and Feel Profiles");
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn);

        loadProfiles(profileComboBox);

        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(header, BorderLayout.CENTER);
        topContainer.add(profilePanel, BorderLayout.SOUTH);
        topContainer.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH); // Moved separator to top of
                                                                                         // container or remove if
                                                                                         // needed, but let's keep
                                                                                         // structure clean
        // Re-structuring topContainer to hold header and profile panel
        JPanel headerWrapper = new JPanel(new BorderLayout());
        headerWrapper.add(header, BorderLayout.NORTH);
        headerWrapper.add(profilePanel, BorderLayout.CENTER);
        headerWrapper.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH);

        add(headerWrapper, BorderLayout.NORTH);

        JTabbedPane tabbedPane = new JTabbedPane();

        FlatLafPanel themePanel = new FlatLafPanel();
        themePanel.setCloseAction(backAction);
        tabbedPane.addTab("Themes & Fonts", themePanel);

        colorSettingsPanel = new ColorSettingsPanel();
        tabbedPane.addTab("Color Settings", colorSettingsPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    public JComboBox<String> getProfileComboBox() {
        return profileComboBox;
    }

    public void saveProfile() {
        String currentProfile = (String) profileComboBox.getSelectedItem();
        String suggestedName = ("Default".equals(currentProfile) || currentProfile == null) ? "" : currentProfile;
        String name = (String) JOptionPane.showInputDialog(this, "Enter profile name:", "Save Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, suggestedName);
        if (name == null || name.trim().isEmpty() || "Default".equalsIgnoreCase(name.trim()))
            return;

        saveProfileInternal(name, true);
    }

    private void saveProfileInternal(String name, boolean confirmOverwrite) {
        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }

            if (!settings.has("lookAndFeelProfiles")) {
                settings.add("lookAndFeelProfiles", new JsonObject());
            }
            JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");

            if (confirmOverwrite && profiles.has(name)) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Profile '" + name + "' already exists. Do you want to overwrite it?",
                        "Confirm Overwrite",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            JsonObject lafSettings = new JsonObject();
            lafSettings.addProperty("theme", UIManager.getLookAndFeel().getClass().getName());

            Font font = UIManager.getFont("defaultFont");
            if (font != null) {
                JsonObject fontSettings = new JsonObject();
                fontSettings.addProperty("family", font.getFamily());
                fontSettings.addProperty("style", font.getStyle());
                fontSettings.addProperty("size", font.getSize());
                lafSettings.add("font", fontSettings);
            }

            Map<String, Color> overrides = colorSettingsPanel.getCurrentOverrides();
            if (overrides != null && !overrides.isEmpty()) {
                JsonObject overridesJson = new JsonObject();
                for (Map.Entry<String, Color> entry : overrides.entrySet()) {
                    overridesJson.addProperty(entry.getKey(), toHexString(entry.getValue()));
                }
                lafSettings.add("colorOverrides", overridesJson);
            }

            profiles.add(name, lafSettings);
            settings.addProperty("lastSelectedLafProfile", name);

            if (Files.notExists(settingsPath.getParent())) {
                Files.createDirectories(settingsPath.getParent());
            }

            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }

            // Update combobox
            boolean exists = false;
            for (int i = 0; i < profileComboBox.getItemCount(); i++) {
                if (profileComboBox.getItemAt(i).equals(name)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                profileComboBox.addItem(name);
            }
            profileComboBox.setSelectedItem(name);

            JOptionPane.showMessageDialog(this, "Profile '" + name + "' saved successfully.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void loadProfiles(JComboBox<String> comboBox) {
        try {
            comboBox.addItem("Default");
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lookAndFeelProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                        for (String profileName : profiles.keySet()) {
                            comboBox.addItem(profileName);
                        }
                    }
                    if (settings.has("lastSelectedLafProfile")) {
                        comboBox.setSelectedItem(settings.get("lastSelectedLafProfile").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void renameProfile(String oldProfileName) {
        if (oldProfileName == null || oldProfileName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No profile selected to rename.", "Rename Profile",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String newProfileName = (String) JOptionPane.showInputDialog(
                this,
                "Enter new name for profile '" + oldProfileName + "':",
                "Rename Profile",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                oldProfileName);

        if (newProfileName == null || newProfileName.trim().isEmpty() || newProfileName.equals(oldProfileName)) {
            return; // User cancelled or entered the same name
        }

        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                JOptionPane.showMessageDialog(this, "Settings file not found.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (!settings.has("lookAndFeelProfiles")) {
                JOptionPane.showMessageDialog(this, "No profiles found in settings file.", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");

            if (profiles.has(newProfileName)) {
                JOptionPane.showMessageDialog(this, "A profile with the name '" + newProfileName + "' already exists.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (profiles.has(oldProfileName)) {
                JsonElement profileData = profiles.remove(oldProfileName);
                profiles.add(newProfileName, profileData);

                if (settings.has("lastSelectedLafProfile")
                        && settings.get("lastSelectedLafProfile").getAsString().equals(oldProfileName)) {
                    settings.addProperty("lastSelectedLafProfile", newProfileName);
                }

                try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    writer.write(gson.toJson(settings));
                }

                // Update combobox
                profileComboBox.removeItem(oldProfileName);
                profileComboBox.addItem(newProfileName);
                profileComboBox.setSelectedItem(newProfileName);

                JOptionPane.showMessageDialog(this,
                        "Profile '" + oldProfileName + "' renamed to '" + newProfileName + "' successfully.", "Success",
                        JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "Profile '" + oldProfileName + "' not found.", "Error",
                        JOptionPane.ERROR_MESSAGE);
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error renaming profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    public void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty())
            return;

        if ("Default".equals(profileName)) {
            resetToDefault();
            // Save "Default" as the last selected profile
            try {
                Path settingsPath = getGuiSettingsPath();
                JsonObject settings;
                if (Files.exists(settingsPath)) {
                    try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                        settings = JsonParser.parseReader(reader).getAsJsonObject();
                    } catch (Exception e) {
                        settings = new JsonObject();
                    }
                } else {
                    settings = new JsonObject();
                }
                settings.addProperty("lastSelectedLafProfile", "Default");
                try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(settings, writer);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("lookAndFeelProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                        if (profiles.has(profileName)) {
                            JsonObject lafSettings = profiles.getAsJsonObject(profileName);
                            Map<String, Color> colorOverrides = null;

                            // Apply settings
                            String themeClassName = null;
                            if (lafSettings.has("theme")) {
                                themeClassName = lafSettings.get("theme").getAsString();
                            }

                            if (lafSettings.has("colorOverrides")) {
                                colorOverrides = parseColorOverrides(lafSettings.getAsJsonObject("colorOverrides"));
                            }

                            if (themeClassName != null) {
                                if (themeClassName.contains("NimbusLookAndFeel")) {
                                    SignumGUI.setupLegacyNimbus();
                                }
                                UIManager.setLookAndFeel(themeClassName);
                            }

                            if (lafSettings.has("font")) {
                                JsonObject fontSettings = lafSettings.getAsJsonObject("font");
                                String family = fontSettings.get("family").getAsString();
                                int style = fontSettings.get("style").getAsInt();
                                int size = fontSettings.get("size").getAsInt();
                                Font font = new Font(family, style, size);
                                UIManager.put("defaultFont", font);
                            }

                            SignumGUI.updateAllUIs();
                            colorSettingsPanel.setProfileOverrides(colorOverrides);

                            // Update last selected
                            settings.addProperty("lastSelectedLafProfile", profileName);
                            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath,
                                    StandardCharsets.UTF_8)) {
                                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                                writer.write(gson.toJson(settings));
                            }

                            JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' loaded successfully.",
                                    "Success", JOptionPane.INFORMATION_MESSAGE);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void resetToDefault() {
        try {
            // Reset to a known default theme, e.g., FlatDarkLaf
            UIManager.setLookAndFeel(FlatDarkLaf.class.getName());
            // Reset font
            UIManager.put("defaultFont", null); // Or set to a specific default font
            // Reset colors
            ColorPaletteManager.updatePalette(null);
            colorSettingsPanel.setProfileOverrides(null);
            SignumGUI.updateAllUIs();
            JOptionPane.showMessageDialog(this, "Settings have been reset to the default theme.", "Reset to Default",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error resetting to default: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Map<String, Color> parseColorOverrides(JsonObject overridesJson) {
        if (overridesJson == null) {
            return null;
        }
        Map<String, Color> overrides = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : overridesJson.entrySet()) {
            try {
                overrides.put(entry.getKey(), Color.decode(entry.getValue().getAsString()));
            } catch (NumberFormatException e) {
                // Consider logging this warning
            }
        }
        return overrides;
    }

    private static String toHexString(Color color) {
        if (color.getAlpha() == 255) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        } else {
            return String.format("#%02X%02X%02X%02X", color.getAlpha(), color.getRed(), color.getGreen(),
                    color.getBlue());
        }
    }

    private void showProfileHelp() {
        String message = "<html><body style='width: 350px'>" +
                "<h2>Look and Feel Profiles</h2>" +
                "<p>Look and Feel Profiles allow you to save and load different visual themes and font settings.</p>" +
                "<ul>" +
                "<li><b>Load Profile</b>: Loads the theme and font settings from the selected profile in the dropdown.</li>"
                +
                "<li><b>Save Profile</b>: Saves the current theme and font settings into a named profile. If you use an existing profile name, it will be overwritten after confirmation.</li>"
                +
                "<li><b>Rename Profile</b>: Renames the currently selected profile.</li>" +
                "<li><b>Delete Profile</b>: Deletes the currently selected profile after confirmation.</li>" +
                "</ul>" +
                "<p>Profiles are stored in the <code>gui-settings.json</code> file in your settings directory.</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "About Look and Feel Profiles", JOptionPane.INFORMATION_MESSAGE);
    }

    public void deleteProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) {
            return;
        }
        if ("Default".equals(profileName)) {
            JOptionPane.showMessageDialog(this, "The 'Default' profile cannot be deleted.", "Action Not Allowed",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete profile '" + profileName + "'?",
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                JsonObject settings;
                try (BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }

                if (settings.has("lookAndFeelProfiles")) {
                    JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                    if (profiles.has(profileName)) {
                        profiles.remove(profileName);
                        if (settings.has("lastSelectedLafProfile")
                                && settings.get("lastSelectedLafProfile").getAsString().equals(profileName)) {
                            settings.remove("lastSelectedLafProfile");
                        }
                        try (BufferedWriter writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            writer.write(gson.toJson(settings));
                        }
                        profileComboBox.removeItem(profileName);
                        JOptionPane.showMessageDialog(this, "Profile '" + profileName + "' deleted successfully.",
                                "Success", JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error deleting profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private Path getGuiSettingsPath() {
        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        Path nodePropsFile = PathUtils.resolvePath(confFolder).resolve("node.properties");
        if (Files.exists(nodePropsFile)) {
            try (FileInputStream in = new FileInputStream(nodePropsFile.toFile())) {
                Properties nodeProps = new Properties();
                nodeProps.load(in);
                settingsDir = nodeProps.getProperty(Props.SETTINGS_DIR.getName(), settingsDir);
            } catch (Exception e) {
                // ignore
            }
        }
        return PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
    }
}