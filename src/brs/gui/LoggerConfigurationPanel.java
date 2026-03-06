package brs.gui;

import brs.props.Props;
import brs.util.PathUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class LoggerConfigurationPanel extends JPanel {

    private static final String[] LOG_LEVELS = { "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST",
            "ALL", "OFF" };
    private final Runnable restartAction;
    private final Properties props;
    private final Properties appliedProps;
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final String confFolder;
    private final Path propertiesFile;
    private JComboBox<String> profileComboBox;

    public LoggerConfigurationPanel(Runnable restartAction, String confFolder) {
        super(new BorderLayout());
        this.restartAction = restartAction;
        this.confFolder = confFolder;
        this.propertiesFile = PathUtils.resolvePath(confFolder).resolve("logging.properties");

        ensurePropertiesFileExists();

        this.props = new Properties();
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            this.props.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.appliedProps = new Properties();
        this.appliedProps.putAll(this.props);

        initHelpTexts();
        initUI();
    }

    private void initUI() {
        // --- Profile Panel ---
        JPanel profilePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Logger Configuration Profile:"));

        profileComboBox = new JComboBox<>();
        profileComboBox.setEditable(false);
        profileComboBox.setPreferredSize(new Dimension(200, 25));
        profilePanel.add(profileComboBox);

        JButton loadProfileBtn = new JButton("Load Profile");
        loadProfileBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN, 16, Color.BLACK));
        loadProfileBtn.setToolTipText("Load selected profile");
        loadProfileBtn.addActionListener(e -> loadProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(loadProfileBtn);

        JButton saveProfileBtn = new JButton("Save Profile");
        saveProfileBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 16, Color.BLACK));
        saveProfileBtn.setToolTipText("Save Logger Configuration Profile");
        saveProfileBtn.addActionListener(e -> saveProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(saveProfileBtn);

        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorder(BorderFactory.createEmptyBorder());
        helpBtn.setContentAreaFilled(false);
        helpBtn.setFocusPainted(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info about Configuration Profiles");
        helpBtn.addActionListener(e -> showProfileHelp());
        profilePanel.add(helpBtn);

        loadProfiles(profileComboBox);
        add(profilePanel, BorderLayout.NORTH);

        JPanel contentPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]", ""));

        addSectionHeader(contentPanel, "Global Settings");

        String defaultGlobalLevel = "INFO";
        JComboBox<String> globalLevelCombo = new JComboBox<>(LOG_LEVELS);
        globalLevelCombo.setSelectedItem(props.getProperty(".level", defaultGlobalLevel));
        addProperty(contentPanel, "Global Level", ".level", globalLevelCombo, defaultGlobalLevel);

        addSectionHeader(contentPanel, "Console Handler");

        String defaultConsoleLevel = "INFO";
        JComboBox<String> consoleLevelCombo = new JComboBox<>(LOG_LEVELS);
        consoleLevelCombo
                .setSelectedItem(props.getProperty("java.util.logging.ConsoleHandler.level", defaultConsoleLevel));
        addProperty(contentPanel, "Console Level", "java.util.logging.ConsoleHandler.level", consoleLevelCombo,
                defaultConsoleLevel);

        addSectionHeader(contentPanel, "File Handler");

        String defaultFileLevel = "INFO";
        JComboBox<String> fileLevelCombo = new JComboBox<>(LOG_LEVELS);
        fileLevelCombo.setSelectedItem(props.getProperty("java.util.logging.FileHandler.level", defaultFileLevel));
        addProperty(contentPanel, "File Level", "java.util.logging.FileHandler.level", fileLevelCombo,
                defaultFileLevel);

        // File Pattern
        String defaultPattern = "logs/signum%u.log";
        JTextField filePatternField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.pattern", defaultPattern));
        addProperty(contentPanel, "Log File Pattern", "java.util.logging.FileHandler.pattern", filePatternField,
                defaultPattern);

        // File Limit
        String defaultLimit = "0";
        JTextField fileLimitField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.limit", defaultLimit));
        addProperty(contentPanel, "File Size Limit (bytes)", "java.util.logging.FileHandler.limit", fileLimitField,
                defaultLimit);

        // File Count
        String defaultCount = "1";
        JTextField fileCountField = createStyledTextField(
                props.getProperty("java.util.logging.FileHandler.count", defaultCount));
        addProperty(contentPanel, "File Count", "java.util.logging.FileHandler.count", fileCountField, defaultCount);

        // Push everything to top
        contentPanel.add(new JLabel(), "pushy");

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // --- Bottom Panel with Buttons and File Path ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(new EmptyBorder(5, 10, 5, 5));

        // Legend
        bottomPanel.add(createLegendPanel(), BorderLayout.NORTH);

        // File path field
        JLabel pathLabel = new JLabel("Configuration File: " + propertiesFile.toAbsolutePath().toString());
        pathLabel.setForeground(Color.LIGHT_GRAY);
        bottomPanel.add(pathLabel, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        JButton resetBtn = new JButton("Reset to Saved Configuration");
        resetBtn.setFont(resetBtn.getFont().deriveFont(Font.BOLD));
        resetBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.UNDO, 16, Color.BLACK));
        resetBtn.addActionListener(e -> resetToCurrent());

        JButton resetAppliedBtn = new JButton("Reset to Applied Configuration");
        resetAppliedBtn.setFont(resetAppliedBtn.getFont().deriveFont(Font.BOLD));
        resetAppliedBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.HISTORY, 16, Color.BLACK));
        resetAppliedBtn.addActionListener(e -> resetToApplied());

        JButton resetToDefaultBtn = new JButton("Reset to Default Configuration");
        resetToDefaultBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.TRASH_O, 16, Color.BLACK));
        resetToDefaultBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "This will delete the content of the configuration file (" + propertiesFile.getFileName()
                            + ").\nAre you sure you want to proceed?",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                try {
                    Files.write(propertiesFile, new byte[0]); // Deletes content
                    resetToCurrent(); // Reloads from the now-empty file and updates UI

                    Object[] options = { "Restart to Apply Changes", "Cancel" };
                    int result = JOptionPane.showOptionDialog(this,
                            "The content of " + propertiesFile.getFileName() + " has been deleted.",
                            "Success",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null, options, options[0]);

                    if (result == 0 && restartAction != null) { // "Restart to Apply Changes"
                        restartAction.run();
                    }
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Error deleting file content: " + ex.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton saveBtn = new JButton("Save Configuration");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        saveBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 16, Color.BLACK));
        saveBtn.addActionListener(e -> {
            performSave();
            Object[] options = { "Restart and Apply Changes", "Cancel" };
            int result = JOptionPane.showOptionDialog(this,
                    "Configuration saved successfully!",
                    "Success",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null, options, options[0]);

            if (result == 0) { // "Restart and Apply Changes"
                if (restartAction != null) {
                    restartAction.run();
                }
            }
        });

        buttonPanel.add(resetAppliedBtn);
        buttonPanel.add(resetBtn);
        buttonPanel.add(resetToDefaultBtn);
        buttonPanel.add(saveBtn);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JTextField createStyledTextField(String text) {
        JTextField textField = new JTextField(text);
        styleTextField(textField);
        fixComponentSize(textField);
        return textField;
    }

    private void styleTextField(JComponent field) {
        field.setFont(UIManager.getFont("TextField.font"));
        field.setBorder(BorderFactory.createCompoundBorder(
                UIManager.getBorder("TextField.border"),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    private void fixComponentSize(JComponent comp) {
        JTextField dummy = new JTextField("Prototype");
        styleTextField(dummy);
        Dimension pref = dummy.getPreferredSize();
        comp.setPreferredSize(new Dimension(comp.getPreferredSize().width, pref.height));
        comp.setMinimumSize(new Dimension(comp.getMinimumSize().width, pref.height));
    }

    private void loadProfiles(JComboBox<String> comboBox) {
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("loggerConfigurationProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");
                        for (String profileName : profiles.keySet()) {
                            comboBox.addItem(profileName);
                        }
                    }
                    if (settings.has("lastSelectedLoggerProfile")) {
                        comboBox.setSelectedItem(settings.get("lastSelectedLoggerProfile").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveProfile(String profileName) {
        String name = (String) JOptionPane.showInputDialog(this, "Enter profile name:", "Save Profile",
                JOptionPane.PLAIN_MESSAGE, null, null, profileName);
        if (name == null || name.trim().isEmpty())
            return;

        try {
            Path settingsPath = getGuiSettingsPath();
            JsonObject settings;
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    settings = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                settings = new JsonObject();
            }

            if (!settings.has("loggerConfigurationProfiles")) {
                settings.add("loggerConfigurationProfiles", new JsonObject());
            }
            JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");

            if (profiles.has(name)) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Profile '" + name + "' already exists. Do you want to overwrite it?",
                        "Confirm Overwrite",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            JsonObject profileData = new JsonObject();
            Properties props = getPropertiesFromUI();
            for (String key : props.stringPropertyNames()) {
                profileData.addProperty(key, props.getProperty(key));
            }

            profiles.add(name, profileData);
            settings.addProperty("lastSelectedLoggerProfile", name);

            if (Files.notExists(settingsPath.getParent())) {
                Files.createDirectories(settingsPath.getParent());
            }

            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
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

    private void loadProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty())
            return;
        try {
            Path settingsPath = getGuiSettingsPath();
            if (Files.exists(settingsPath)) {
                try (BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonObject settings = JsonParser.parseReader(reader).getAsJsonObject();
                    if (settings.has("loggerConfigurationProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("loggerConfigurationProfiles");
                        if (profiles.has(profileName)) {
                            JsonObject profileData = profiles.getAsJsonObject(profileName);
                            Properties props = new Properties();
                            for (String key : profileData.keySet()) {
                                props.setProperty(key, profileData.get(key).getAsString());
                            }
                            updateUIFromProperties(props);

                            // Update last selected
                            settings.addProperty("lastSelectedLoggerProfile", profileName);
                            try (BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                                writer.write(gson.toJson(settings));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error loading profile: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void performSave() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = "";
            if (comp instanceof JComboBox)
                val = (String) ((JComboBox<?>) comp).getSelectedItem();
            else if (comp instanceof JTextComponent)
                val = ((JTextComponent) comp).getText();

            String def = defaultValues.get(key);
            if (val != null && !val.equals(def)) {
                props.setProperty(key, val);
            } else {
                props.remove(key);
            }
        }
        try {
            savePropertiesPreservingFormat(propertiesFile, props, propertyComponents.keySet());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving properties: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey(), defaultValues.get(entry.getKey()));
        }
    }

    private Properties getPropertiesFromUI() {
        Properties props = new Properties();
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = "";
            if (comp instanceof JComboBox) {
                val = (String) ((JComboBox<?>) comp).getSelectedItem();
            } else if (comp instanceof JTextComponent) {
                val = ((JTextComponent) comp).getText();
            }

            String def = defaultValues.get(key);
            if (val != null && !val.equals(def)) {
                props.setProperty(key, val);
            }
        }
        return props;
    }

    private void updateUIFromProperties(Properties loadedProps) {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String def = defaultValues.get(key);
            String val = loadedProps.getProperty(key, def);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            }
            updateColor(comp, key, def);
        }
    }

    private void showProfileHelp() {
        String message = "<html><body style='width: 350px'>" +
                "<h2>Configuration Profiles</h2>" +
                "<p>Configuration Profiles allow you to save and load different sets of logger configurations.</p>" +
                "<ul>" +
                "<li><b>Save Profile</b>: Saves the current settings from the panel into a named profile. If you use an existing profile name, it will be overwritten after confirmation.</li>"
                +
                "<li><b>Load Profile</b>: Loads the settings from the selected profile in the dropdown, updating the fields.</li>"
                +
                "</ul>" +
                "<p>Profiles are stored in the <code>gui-settings.json</code> file in your settings directory.</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "About Configuration Profiles", JOptionPane.INFORMATION_MESSAGE);
    }

    private Path getGuiSettingsPath() {
        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        // Try to read settings.dir from node.properties in the same conf folder
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

    private void savePropertiesPreservingFormat(Path file, Properties props, Set<String> managedKeys)
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
                    if (!managedKeys.contains(key)) {
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

    private String escapePropertyValue(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\f", "\\f");
    }

    private void ensurePropertiesFileExists() {
        if (!Files.exists(propertiesFile)) {
            try {
                Files.createDirectories(propertiesFile.getParent());
                Files.createFile(propertiesFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void addProperty(JPanel panel, String labelText, String propertyKey, JComponent inputComponent,
            String defaultValue) {
        // Label
        panel.add(new JLabel(labelText + ":"), "align label");

        // Input
        fixComponentSize(inputComponent);
        panel.add(inputComponent, "split 2, growx, height pref!");

        propertyComponents.put(propertyKey, inputComponent);
        defaultValues.put(propertyKey, defaultValue);

        if (inputComponent instanceof JComboBox) {
            JComboBox<?> comboBox = (JComboBox<?>) inputComponent;
            comboBox.addActionListener(e -> updateColor(inputComponent, propertyKey, defaultValue));
            comboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    String current = props.getProperty(propertyKey);
                    if (current == null)
                        current = defaultValue;

                    String applied = appliedProps.getProperty(propertyKey);
                    if (applied == null)
                        applied = defaultValue;

                    if (value != null && value.toString().equals(applied)) {
                        c.setForeground(new Color(0, 128, 0));
                    } else if (value != null && value.toString().equals(current)) {
                        c.setForeground(Color.YELLOW);
                    } else {
                        Color textColor = UIManager.getColor("text");
                        if (textColor == null)
                            textColor = Color.BLACK;
                        c.setForeground(textColor);
                    }
                    return c;
                }
            });
        } else if (inputComponent instanceof JTextField) {
            JTextField textField = (JTextField) inputComponent;
            textField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    SwingUtilities.invokeLater(() -> updateColor(textField, propertyKey, defaultValue));
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    SwingUtilities.invokeLater(() -> updateColor(textField, propertyKey, defaultValue));
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    // Ignore attribute changes to avoid infinite loop
                }
            });
        }

        updateColor(inputComponent, propertyKey, defaultValue);

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorder(BorderFactory.createEmptyBorder());
        helpBtn.setContentAreaFilled(false);
        helpBtn.setFocusPainted(false);
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> showHelp(labelText, propertyKey));
        panel.add(helpBtn, "wrap");
        panel.add(new JSeparator(), "span, growx, wrap, gaptop 2, gapbottom 2");
    }

    private void resetToCurrent() {
        try (FileInputStream in = new FileInputStream(propertiesFile.toFile())) {
            props.clear();
            props.load(in);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String def = defaultValues.get(key);
            String val = props.getProperty(key, def);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            }
        }
    }

    private void resetToApplied() {
        props.clear();
        props.putAll(appliedProps);

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String def = defaultValues.get(key);
            String val = props.getProperty(key, def);

            if (comp instanceof JComboBox) {
                ((JComboBox<?>) comp).setSelectedItem(val);
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                ((javax.swing.text.JTextComponent) comp).setText(val);
            }
        }
    }

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String current = props.getProperty(propName);
        if (current == null) {
            current = defaultValue;
        }
        String applied = appliedProps.getProperty(propName);
        if (applied == null)
            applied = defaultValue;

        String value = "";
        if (comp instanceof JComboBox) {
            value = (String) ((JComboBox<?>) comp).getSelectedItem();
        } else if (comp instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) comp).getText().trim();
            current = current.trim();
            applied = applied.trim();
        }

        Color color;
        if (value != null && value.equals(applied)) {
            color = new Color(0, 128, 0);
        } else if (value != null && value.equals(current)) {
            color = Color.YELLOW;
        } else {
            color = UIManager.getColor("text");
            if (color == null)
                color = Color.BLACK;
        }

        comp.setForeground(color);
    }

    private JPanel createLegendPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        panel.setBorder(new EmptyBorder(0, 0, 5, 0));

        Color defaultColor = UIManager.getColor("text");
        if (defaultColor == null)
            defaultColor = Color.BLACK;

        panel.add(createLegendItem(defaultColor, "Unsaved values"));
        panel.add(createLegendItem(Color.YELLOW, "Saved values"));
        panel.add(createLegendItem(new Color(0, 128, 0), "Applied values"));

        return panel;
    }

    private JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel colorBox = new JLabel("\u25A0");
        colorBox.setForeground(color);
        item.add(colorBox);
        item.add(new JLabel(text));
        return item;
    }

    private void showHelp(String labelText, String propertyKey) {
        String description = helpTexts.getOrDefault(propertyKey, "No detailed description available.");
        String message = "<html><body style='width: 300px'>" +
                "<h2>" + labelText + "</h2>" +
                "<p><b>Property Key:</b> <code>" + propertyKey + "</code></p>" +
                "<hr>" +
                "<p>" + description.replace("\n", "<br>") + "</p>" +
                "</body></html>";

        JOptionPane.showMessageDialog(this, message, "Property Information", JOptionPane.INFORMATION_MESSAGE);
    }

    private void initHelpTexts() {
        helpTexts.put(".level",
                "Sets the default logging level for all loggers in the application unless overridden."
                        + "<br><br><b>Available Levels (from most to least severe):</b>"
                        + "<ul>"
                        + "<li><b>SEVERE:</b> Critical errors that may cause the application to terminate.</li>"
                        + "<li><b>WARNING:</b> Potential problems or unexpected events.</li>"
                        + "<li><b>INFO:</b> General operational information (default).</li>"
                        + "<li><b>CONFIG:</b> Static configuration messages.</li>"
                        + "<li><b>FINE:</b> Detailed tracing information.</li>"
                        + "<li><b>FINER:</b> More detailed tracing.</li>"
                        + "<li><b>FINEST:</b> Highly detailed tracing for debugging.</li>"
                        + "<li><b>ALL:</b> Log all messages.</li>"
                        + "<li><b>OFF:</b> Turn off logging.</li>"
                        + "</ul>");
        helpTexts.put("java.util.logging.ConsoleHandler.level",
                "Sets the minimum logging level for messages displayed in the console window (the main text area of the GUI)."
                        + "<br><br>Only messages with this level or higher will be shown in the console."
                        + "<br>This allows you to see important messages in the GUI while logging more detailed information to a file.");
        helpTexts.put("java.util.logging.FileHandler.level",
                "Sets the minimum logging level for messages written to the log file(s)."
                        + "<br><br>Only messages with this level or higher will be saved to disk."
                        + "<br>It is common to set this to a more verbose level (e.g., FINE) than the console to capture detailed information for debugging.");
        helpTexts.put("java.util.logging.FileHandler.pattern",
                "Defines the location and naming pattern for the log files."
                        + "<br><br><b>Special Placeholders:</b>"
                        + "<ul>"
                        + "<li><code>%h</code>: User's home directory.</li>"
                        + "<li><code>%t</code>: System's temporary directory.</li>"
                        + "<li><code>%u</code>: A unique number to resolve naming conflicts.</li>"
                        + "<li><code>%g</code>: The generation number for rotating logs.</li>"
                        + "<li><code>/</code>: The platform-specific path separator.</li>"
                        + "</ul>"
                        + "<b>Example:</b> <code>logs/signum%u.log</code> will create log files like <code>signum0.log</code>, <code>signum1.log</code>, etc., inside a 'logs' subdirectory.");
        helpTexts.put("java.util.logging.FileHandler.limit",
                "The approximate maximum size of a single log file in bytes."
                        + "<br><br>When a log file reaches this limit, it will be closed, and a new file will be opened for subsequent messages."
                        + "<br>This works in conjunction with 'File Count' to manage log rotation."
                        + "<br><br><b>Note:</b> Set to <code>0</code> for no size limit (a single, ever-growing log file).");
        helpTexts.put("java.util.logging.FileHandler.count",
                "The number of log files to use in the rotation sequence."
                        + "<br><br>Once this many files have been created, the logger will start overwriting the oldest file (e.g., <code>...log.0</code>)."
                        + "<br>For example, if 'File Count' is <code>5</code>, the logs will be named <code>...log.0, ...log.1, ...log.2, ...log.3, ...log.4</code>.");
    }

    private void addSectionHeader(JPanel panel, String title) {
        JLabel label = new JLabel(title);
        label.setFont(new Font(label.getFont().getName(), Font.BOLD, 14));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));
        panel.add(label, "span, growx, gaptop 15, gapbottom 5, wrap");
    }
}