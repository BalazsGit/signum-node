package brs.gui;

import brs.util.PathUtils;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class LoggerConfigurationPanel extends JPanel {

    private static final String[] LOG_LEVELS = { "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST",
            "ALL", "OFF" };
    private final Runnable restartAction;
    private final Properties props;
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private final String confFolder;
    private final Path propertiesFile;

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

        initHelpTexts();
        initUI();
    }

    private void initUI() {
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
        JTextField filePatternField = new JTextField(
                props.getProperty("java.util.logging.FileHandler.pattern", defaultPattern));
        addProperty(contentPanel, "Log File Pattern", "java.util.logging.FileHandler.pattern", filePatternField,
                defaultPattern);

        // File Limit
        String defaultLimit = "0";
        JTextField fileLimitField = new JTextField(
                props.getProperty("java.util.logging.FileHandler.limit", defaultLimit));
        addProperty(contentPanel, "File Size Limit (bytes)", "java.util.logging.FileHandler.limit", fileLimitField,
                defaultLimit);

        // File Count
        String defaultCount = "1";
        JTextField fileCountField = new JTextField(
                props.getProperty("java.util.logging.FileHandler.count", defaultCount));
        addProperty(contentPanel, "File Count", "java.util.logging.FileHandler.count", fileCountField, defaultCount);

        // Push everything to top
        contentPanel.add(new JLabel(), "pushy");

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // --- Buttons ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton saveApplyBtn = new JButton("Save and Apply");
        saveApplyBtn.setFont(saveApplyBtn.getFont().deriveFont(Font.BOLD));
        saveApplyBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.REFRESH, 16, Color.BLACK));
        saveApplyBtn.addActionListener(e -> {
            saveProperties(globalLevelCombo, consoleLevelCombo, fileLevelCombo, filePatternField, fileLimitField,
                    fileCountField);
            if (restartAction != null) {
                restartAction.run();
            }
        });
        buttonPanel.add(saveApplyBtn);

        JButton resetBtn = new JButton("Reset to Current");
        resetBtn.setFont(resetBtn.getFont().deriveFont(Font.BOLD));
        resetBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.UNDO, 16, Color.BLACK));
        resetBtn.addActionListener(e -> resetToCurrent());
        buttonPanel.add(resetBtn);

        JButton saveBtn = new JButton("Save Configuration");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        saveBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.FLOPPY_O, 16, Color.BLACK));
        saveBtn.addActionListener(e -> {
            saveProperties(globalLevelCombo, consoleLevelCombo, fileLevelCombo, filePatternField, fileLimitField,
                    fileCountField);
            JOptionPane.showMessageDialog(this,
                    "Logger configuration saved successfully!\nPlease restart the node to apply changes.", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
        });
        buttonPanel.add(saveBtn);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void saveProperties(JComboBox<String> globalLevelCombo, JComboBox<String> consoleLevelCombo,
            JComboBox<String> fileLevelCombo, JTextField filePatternField, JTextField fileLimitField,
            JTextField fileCountField) {
        props.setProperty(".level", (String) globalLevelCombo.getSelectedItem());
        props.setProperty("java.util.logging.ConsoleHandler.level", (String) consoleLevelCombo.getSelectedItem());
        props.setProperty("java.util.logging.FileHandler.level", (String) fileLevelCombo.getSelectedItem());
        props.setProperty("java.util.logging.FileHandler.pattern", filePatternField.getText());
        props.setProperty("java.util.logging.FileHandler.limit", fileLimitField.getText());
        props.setProperty("java.util.logging.FileHandler.count", fileCountField.getText());
        try (FileOutputStream out = new FileOutputStream(propertiesFile.toFile())) {
            props.store(out, "Signum Logger Configuration");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error saving properties: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }

        updateColor(globalLevelCombo, ".level", "INFO");
        updateColor(consoleLevelCombo, "java.util.logging.ConsoleHandler.level", "INFO");
        updateColor(fileLevelCombo, "java.util.logging.FileHandler.level", "INFO");
        updateColor(filePatternField, "java.util.logging.FileHandler.pattern", "logs/signum%u.log");
        updateColor(fileLimitField, "java.util.logging.FileHandler.limit", "0");
        updateColor(fileCountField, "java.util.logging.FileHandler.count", "1");

        globalLevelCombo.repaint();
        consoleLevelCombo.repaint();
        fileLevelCombo.repaint();
    }

    private void ensurePropertiesFileExists() {
        if (!Files.exists(propertiesFile)) {
            Path defaultFile = PathUtils.resolvePath(confFolder).resolve("logging-default.properties");
            if (Files.exists(defaultFile)) {
                try {
                    Files.copy(defaultFile, propertiesFile);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void addProperty(JPanel panel, String labelText, String propertyKey, JComponent inputComponent,
            String defaultValue) {
        // Label
        panel.add(new JLabel(labelText + ":"), "align label");

        // Input
        panel.add(inputComponent, "split 2, growx");

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
                    if (value != null && value.toString().equals(current)) {
                        c.setForeground(new Color(0, 128, 0));
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
            ((JTextField) inputComponent).getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    updateColor(inputComponent, propertyKey, defaultValue);
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    updateColor(inputComponent, propertyKey, defaultValue);
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    updateColor(inputComponent, propertyKey, defaultValue);
                }
            });
        }

        // Initial color update
        updateColor(inputComponent, propertyKey, defaultValue);

        // Help Button
        JButton helpBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, 16, Color.LIGHT_GRAY));
        helpBtn.setBorderPainted(false);
        helpBtn.setContentAreaFilled(false);
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

    private void updateColor(JComponent comp, String propName, String defaultValue) {
        String current = props.getProperty(propName);
        if (current == null)
            current = defaultValue;

        String value = "";
        if (comp instanceof JComboBox) {
            value = (String) ((JComboBox<?>) comp).getSelectedItem();
        } else if (comp instanceof javax.swing.text.JTextComponent) {
            value = ((javax.swing.text.JTextComponent) comp).getText().trim();
            current = current.trim();
        }

        if (value != null && value.equals(current)) {
            comp.setForeground(new Color(0, 128, 0));
        } else {
            Color textColor = UIManager.getColor("text");
            if (textColor == null)
                textColor = Color.BLACK;
            comp.setForeground(textColor);
        }
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
        helpTexts.put(".level", "The default logging level for the entire application.\n" +
                "Levels: SEVERE (highest), WARNING, INFO, CONFIG, FINE, FINER, FINEST (lowest).");
        helpTexts.put("java.util.logging.ConsoleHandler.level", "Logging level for console output.\n" +
                "Controls which messages appear in the GUI console window.");
        helpTexts.put("java.util.logging.FileHandler.level", "Logging level for file output.\n" +
                "Controls which messages are written to the log files.");
        helpTexts.put("java.util.logging.FileHandler.pattern", "Pattern for the log file name.\n" +
                "%h = user home directory, %u = unique number to resolve conflicts.");
        helpTexts.put("java.util.logging.FileHandler.limit",
                "Maximum size of a log file in bytes before rotating to a new file.\n" +
                        "Set to 0 for no limit.");
        helpTexts.put("java.util.logging.FileHandler.count", "Number of log files to cycle through.\n" +
                "When the limit is reached, the oldest file is overwritten.");
    }

    private void addSectionHeader(JPanel panel, String title) {
        JLabel label = new JLabel(title);
        label.setFont(new Font(label.getFont().getName(), Font.BOLD, 14));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));
        panel.add(label, "span, growx, gaptop 15, gapbottom 5, wrap");
    }
}