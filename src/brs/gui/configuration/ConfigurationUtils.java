package brs.gui.configuration;

import brs.gui.GuiColors;
import brs.gui.GuiConstants;
import brs.gui.util.HelpButton;
import brs.util.PathUtils;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.*;
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
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigurationUtils {

    public static void styleTextField(JComponent field) {
        if (field instanceof JTextField || field instanceof JPasswordField) {
            field.setFont(UIManager.getFont("TextField.font"));
            field.setBorder(BorderFactory.createCompoundBorder(
                    UIManager.getBorder("TextField.border"),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        }
    }

    public static void fixComponentSize(JComponent comp) {
        comp.setPreferredSize(null);
        comp.setMinimumSize(null);
        JButton dummy = new JButton("P",
                IconFontSwing.buildIcon(FontAwesome.CIRCLE, GuiConstants.getHelpIconSize(), Color.BLACK));
        Dimension pref = dummy.getPreferredSize();
        Dimension currentPref = comp.getPreferredSize();

        int targetHeight = Math.max(currentPref.height, pref.height) + 2;
        comp.setPreferredSize(new Dimension(currentPref.width + 2, targetHeight));
        comp.setMinimumSize(new Dimension(currentPref.width + 2, targetHeight));
    }

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

    public static String escapePropertyValue(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\f", "\\f");
    }

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

    public static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    public static Path resolveProfilePath(String confFolder, String subFolder, String fileName) {
        return PathUtils.resolvePath(confFolder).resolve(subFolder).resolve(fileName);
    }

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

    private static JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel colorBox = new JLabel("\u25A0");
        colorBox.setForeground(color);
        item.add(colorBox);
        item.add(new JLabel(text));
        return item;
    }

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

    public static Path getProfileMetadataPath(String confFolder, String subFolder) {
        return PathUtils.resolvePath(confFolder).resolve(subFolder).resolve("profile.json");
    }

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
}
