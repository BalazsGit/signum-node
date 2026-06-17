package application.module.database.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.gui.DatabaseConfigurationPanel.PropertyRow;
import application.module.database.profile.SQLiteProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.Signum;
import application.module.node.gui.configuration.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.HelpButton;
import application.utils.io.PathUtils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SQLite specific configuration and profile management.
 */
public class SQLiteConfigurationPanel extends JPanel implements DatabaseEnginePanel {

    private static final Logger logger = LoggerFactory.getLogger(SQLiteConfigurationPanel.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String[] STORAGE_TYPES = { "file:", "memory:" };
    private String confFolder;
    private Runnable restartAction;
    private Runnable backAction;

    private SQLiteProfile currentProfile;
    private GlobalSettings globalSettings = new GlobalSettings();
    private JsonObject appliedProfileSettings = new JsonObject();
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final List<PropertyRow> allPropertyRows = new ArrayList<>();

    private String loadedProfileName;
    private String runningProfileName = "";
    private String activeProfileName = "";

    private JComboBox<String> profileComboBox;
    private JButton newProfileBtn, renameProfileBtn, deleteProfileBtn, reloadProfileBtn, refreshProfilesBtn;
    private JLabel pathLabel;
    private JPanel searchResultsPanel;
    private JPanel settingsContentPanel;
    private CardLayout contentCardLayout;
    private JPanel contentContainer;
    private boolean isInitialized = false;

    private final Icon checkIcon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE,
            GuiConstants.getHelpIconSize(),
            GuiColors.getApplied());

    public SQLiteConfigurationPanel() {
        super(new BorderLayout());

        this.confFolder = Signum.CONF_FOLDER;

        String lastProfile = ConfigurationUtils.loadAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER));
        if (lastProfile != null && lastProfile.startsWith("SQLite:")) {
            this.runningProfileName = lastProfile.split(":")[1];
            this.activeProfileName = this.runningProfileName;
        }

        initUI();
        this.isInitialized = true;
        refreshProfileList();
    }

    private void initUI() {
        JPanel northPanel = new JPanel(new BorderLayout());

        // --- Profile Toolbar ---
        JPanel profilePanel = new JPanel(new MigLayout("insets 5 10 5 5, gap 5"));
        profilePanel.add(new JLabel("Profile:"));
        profileComboBox = new JComboBox<>();
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
        profileComboBox.setRenderer(
                ConfigurationUtils.createProfileComboBoxRenderer(() -> runningProfileName, () -> activeProfileName));
        profileComboBox.addActionListener(e -> {
            if (isInitialized)
                loadProfileInternal((String) profileComboBox.getSelectedItem());
        });
        profilePanel.add(profileComboBox);

        newProfileBtn = new JButton("New Profile");
        newProfileBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newProfileBtn);

        renameProfileBtn = new JButton("Rename Profile");
        renameProfileBtn.addActionListener(e -> renameProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(renameProfileBtn);

        deleteProfileBtn = new JButton("Delete Profile");
        deleteProfileBtn.addActionListener(e -> deleteProfile((String) profileComboBox.getSelectedItem()));
        profilePanel.add(deleteProfileBtn);

        reloadProfileBtn = new JButton("Reload");
        reloadProfileBtn.addActionListener(e -> reloadProfile());
        profilePanel.add(reloadProfileBtn);

        refreshProfilesBtn = new JButton("Refresh Profiles List");
        refreshProfilesBtn.addActionListener(e -> refreshProfileList());
        profilePanel.add(refreshProfilesBtn);

        JScrollPane profileScroll = new JScrollPane(profilePanel);
        profileScroll.setBorder(BorderFactory.createEmptyBorder());
        profileScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        northPanel.add(profileScroll, BorderLayout.NORTH);

        // --- Search Bar ---
        JPanel searchPanel = new JPanel(new MigLayout("insets 5 10 5 5, fillx", "[][grow]"));
        searchPanel.add(new JLabel("Search:"));
        JTextField searchField = new JTextField();
        ConfigurationUtils.styleInputComponent(searchField);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                filterProperties(searchField.getText());
            }

            public void removeUpdate(DocumentEvent e) {
                filterProperties(searchField.getText());
            }

            public void changedUpdate(DocumentEvent e) {
                filterProperties(searchField.getText());
            }
        });
        searchPanel.add(searchField, "growx");
        northPanel.add(searchPanel, BorderLayout.SOUTH);

        add(northPanel, BorderLayout.NORTH);

        // --- Content ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        settingsContentPanel = new JPanel(new MigLayout("fillx, insets 10, gap 10", "[][grow]"));
        JScrollPane settingsScroll = new JScrollPane(settingsContentPanel);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(settingsScroll, "SETTINGS");

        searchResultsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]"));
        JScrollPane searchScroll = new JScrollPane(searchResultsPanel);
        searchScroll.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(searchScroll, "SEARCH");

        add(contentContainer, BorderLayout.CENTER);

        // --- Bottom ---
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(new EmptyBorder(5, 10, 5, 5));
        pathLabel = new JLabel("Profile Path: N/A");
        pathLabel.setForeground(GuiColors.getFaintText());
        bottom.add(pathLabel, BorderLayout.WEST);
        add(bottom, BorderLayout.SOUTH);

        updateProfileButtonsUI();
    }

    private void loadProfileInternal(String name) {
        if (name == null || name.isEmpty())
            return;
        this.loadedProfileName = name;
        this.activeProfileName = name;

        Path profileDir = getProfilePath(name);
        Path jsonFile = profileDir.resolve("profile.json");

        if (Files.exists(jsonFile)) {
            try {
                JsonObject json = JsonParser.parseReader(Files.newBufferedReader(jsonFile)).getAsJsonObject();
                this.currentProfile = new SQLiteProfile(name, json);
            } catch (Exception e) {
                logger.error("Failed to load SQLite profile", e);
                this.currentProfile = new SQLiteProfile(name);
            }
        } else {
            this.currentProfile = new SQLiteProfile(name);
        }

        refreshSettingsUI();
        updateUIFromData();
        updateProfileButtonsUI();
    }

    private void refreshSettingsUI() {
        settingsContentPanel.removeAll();
        allPropertyRows.clear();
        propertyComponents.clear();
        valueSuppliers.clear();

        addSectionHeader(settingsContentPanel, "Database File Configuration", true);

        // Parse existing URL: jdbc:sqlite:file:./db/signum.sqlite.db
        String fullUrl = currentProfile.getDbUrl();
        String prefix = "jdbc:sqlite:";
        String content = fullUrl.startsWith(prefix) ? fullUrl.substring(prefix.length()) : fullUrl;

        String selectedType = "file:";
        String pathPart = content;

        for (String type : STORAGE_TYPES) {
            if (content.startsWith(type)) {
                selectedType = type;
                pathPart = content.substring(type.length());
                break;
            }
        }

        // Storage Type Selection
        JComboBox<String> typeCombo = new JComboBox<>(STORAGE_TYPES);
        typeCombo.setSelectedItem(selectedType);
        ConfigurationUtils.styleInputComponent(typeCombo);
        ConfigurationUtils.fixComponentSize(typeCombo);
        settingsContentPanel.add(new JLabel("Storage Type:"), "align label");
        settingsContentPanel.add(typeCombo, "growx, wrap");

        // Filename/Path Field
        JTextField pathField = new JTextField(pathPart);
        addProperty(settingsContentPanel, "sqlite_path", "Database Filename", pathField);

        valueSuppliers.put(SQLiteProfile.CFG_URL,
                () -> "jdbc:sqlite:" + typeCombo.getSelectedItem() + pathField.getText());

        JButton saveBtn = new JButton("Save all to profile.json");
        saveBtn.addActionListener(e -> saveConfigurationToProfile());
        settingsContentPanel.add(saveBtn, "span, gaptop 10");

        settingsContentPanel.revalidate();
        settingsContentPanel.repaint();
    }

    private void addProperty(JPanel panel, String key, String labelText, JTextField existingField) {
        PropertyRow row = new PropertyRow(key, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;

        JTextField field = (existingField != null) ? existingField : new JTextField();
        if (existingField == null)
            ConfigurationUtils.styleInputComponent(field);

        ConfigurationUtils.styleInputComponent(field);
        propertyComponents.put(key, field);
        valueSuppliers.put(key, field::getText);

        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                updateColor(field, key);
            }

            public void removeUpdate(DocumentEvent e) {
                updateColor(field, key);
            }

            public void changedUpdate(DocumentEvent e) {
                updateColor(field, key);
            }
        });

        panel.add(label, "align label");
        panel.add(field, "growx, split 2");
        panel.add(new HelpButton(), "wrap");

        row.input = field;
        allPropertyRows.add(row);
    }

    private void updateColor(JComponent comp, String key) {
        comp.setForeground(GuiColors.getUnsaved());
    }

    private void createNewProfile() {
        String name = JOptionPane.showInputDialog(this, "New SQLite Profile Name:");
        if (name != null && !name.trim().isEmpty()) {
            try {
                this.currentProfile = new SQLiteProfile(name);
                currentProfile.saveToProfileJson();
                refreshProfileList();
                profileComboBox.setSelectedItem(name);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
            }
        }
    }

    private void saveProfile() {
        String sel = (String) profileComboBox.getSelectedItem();
        if (sel == null)
            return;
        ConfigurationUtils.updateAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                "SQLite:" + sel);
        this.runningProfileName = sel;
        this.activeProfileName = sel;
        profileComboBox.repaint();
    }

    private void renameProfile(String old) {
        if (old == null)
            return;
        String n = (String) JOptionPane.showInputDialog(this, "Enter new name for profile '" + old + "':",
                "Rename Profile", JOptionPane.PLAIN_MESSAGE, null, null, old);
        if (n == null || n.trim().isEmpty() || n.equals(old))
            return;

        Path oldPath = getProfilePath(old);
        Path newPath = getProfilePath(n);

        if (Files.exists(newPath)) {
            JOptionPane.showMessageDialog(this, "Profile '" + n + "' already exists.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // For SQLite, if this is the running profile, the Node holds a lock on the
        // file.
        boolean isRunning = old.equals(runningProfileName);

        if (isRunning) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "The profile '" + old + "' is currently in use.\n" +
                            "Renaming it will cause the Node to shut down and restart automatically to release file locks.\n\n"
                            +
                            "Do you want to proceed?",
                    "Confirm Node Restart",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION)
                return;
        }

        executeRenameProfileWorker(old, n, oldPath, newPath, isRunning);
    }

    private void executeRenameProfileWorker(String oldName, String newName, Path oldPath, Path newPath,
            boolean isRunning) {
        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing...");

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Renaming Profile", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isRunning) {
                    publish(new ProgressInfo("Stopping Signum Node core...", 20));
                    Signum.shutdownNode();
                    Thread.sleep(2000);
                }

                publish(new ProgressInfo("Moving profile folder...", 50));
                Files.move(oldPath, newPath);

                publish(new ProgressInfo("Updating metadata...", 70));
                ConfigurationUtils.updateAppliedProfile(
                        ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                        "SQLite:" + newName);

                if (oldName.equals(runningProfileName)) {
                    runningProfileName = newName;
                    activeProfileName = newName;
                    if (loadedProfileName != null && loadedProfileName.equals(oldName)) {
                        loadedProfileName = newName;
                    }
                }

                if (isRunning) {
                    publish(new ProgressInfo("Restarting Signum Node core...", 90));
                    Signum.startNode();
                }
                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    refreshProfileList();
                    profileComboBox.setSelectedItem(newName);
                    updateUIFromData();
                    JOptionPane.showMessageDialog(SQLiteConfigurationPanel.this, "Profile renamed successfully.");
                } catch (Exception e) {
                    logger.error("Rename operation failed", e);
                    JOptionPane.showMessageDialog(SQLiteConfigurationPanel.this,
                            "Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private static class ProgressInfo {
        final String message;
        final int progress;

        ProgressInfo(String m, int p) {
            this.message = m;
            this.progress = p;
        }
    }

    private void deleteProfile(String n) {
        if (n == null)
            return;
        if (JOptionPane.showConfirmDialog(this, "Delete profile '" + n + "'?") == JOptionPane.YES_OPTION) {
            try {
                DatabaseConfigurationUtils.deleteDirectoryRecursively(getProfilePath(n));
                refreshProfileList();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Delete failed");
            }
        }
    }

    private void refreshProfileList() {
        profileComboBox.removeAllItems();
        DatabaseConfigurationUtils.getProfileNames(confFolder, "SQLite").forEach(profileComboBox::addItem);
        if (activeProfileName != null)
            profileComboBox.setSelectedItem(activeProfileName);
        updateProfileButtonsUI();
    }

    private void updateProfileButtonsUI() {
        boolean hasSel = profileComboBox.getSelectedItem() != null;
        renameProfileBtn.setEnabled(hasSel);
        deleteProfileBtn.setEnabled(hasSel);
    }

    private void filterProperties(String text) {
        if (text == null || text.trim().isEmpty()) {
            contentCardLayout.show(contentContainer, "SETTINGS");
            return;
        }
        searchResultsPanel.removeAll();
        String low = text.toLowerCase();
        for (PropertyRow row : allPropertyRows) {
            if (row.propertyKey != null
                    && (row.propertyKey.toLowerCase().contains(low) || row.labelText.toLowerCase().contains(low))) {
                searchResultsPanel.add(new JLabel(row.labelText), "align label");
                searchResultsPanel.add(propertyComponents.get(row.propertyKey), "growx, wrap");
            }
        }
        contentCardLayout.show(contentContainer, "SEARCH");
    }

    private void addSectionHeader(JPanel panel, String title, boolean isFirst) {
        PropertyRow row = new PropertyRow(null, title, panel);
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        Color separatorColor = GuiColors.getSeparator();
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor));
        panel.add(label, (isFirst ? "" : "gaptop 15, ") + "span, growx, wrap, gapbottom 5");
        row.label = label;
        allPropertyRows.add(row);
    }

    // --- DatabaseEnginePanel Implementation ---

    @Override
    public String getEngineName() {
        return "SQLite";
    }

    @Override
    public Path getProfilePath(String profileName) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR).resolve("SQLite")
                .resolve(profileName);
    }

    @Override
    public void loadProfile(String profileName, GlobalSettings gs) {
        this.globalSettings = gs;
        loadProfileInternal(profileName);
    }

    private void saveConfigurationToProfile() {
        try {
            // Sync UI to profile object
            if (valueSuppliers.containsKey(SQLiteProfile.CFG_URL)) {
                currentProfile.getConfiguration().put(SQLiteProfile.CFG_URL,
                        valueSuppliers.get(SQLiteProfile.CFG_URL).get());
            }
            // Note: Journal Mode and Cache Size are preserved in the background profile
            // object
            // but not modified by this UI anymore.

            // Save profile.json
            currentProfile.saveToProfileJson();

            // Create database folder
            currentProfile.ensureDatabaseDirectory();

            JOptionPane.showMessageDialog(this, "Profile saved and database directory verified.");
            loadProfileInternal(loadedProfileName);
        } catch (IOException e) {
            logger.error("Save failed", e);
        }
    }

    @Override
    public void resetToDefaults(GlobalSettings gs) {
        this.currentProfile = new SQLiteProfile(loadedProfileName);
        refreshSettingsUI();
    }

    @Override
    public String getUnsavedChangesReport() {
        return null;
    }

    @Override
    public boolean hasUnsavedChanges() {
        return false;
    }

    @Override
    public void updateUIFromData() {
        pathLabel.setText("Profile Path: "
                + (loadedProfileName != null ? getProfilePath(loadedProfileName).toAbsolutePath() : "N/A"));
    }

    @Override
    public void refreshUIColors() {
    }

    @Override
    public void setLoadedProfileName(String n) {
        this.loadedProfileName = n;
    }

    @Override
    public String getLoadedProfileName() {
        return loadedProfileName;
    }

    @Override
    public void setRunningProfileName(String n) {
        this.runningProfileName = n;
    }

    @Override
    public String getRunningProfileName() {
        return runningProfileName;
    }

    @Override
    public void setActiveProfileName(String n) {
        this.activeProfileName = n;
    }

    @Override
    public String getActiveProfileName() {
        return activeProfileName;
    }

    @Override
    public JsonObject getCurrentProfileSettings() {
        return new JsonObject();
    }

    @Override
    public void setAppliedProfileSettings(JsonObject s) {
        this.appliedProfileSettings = s;
    }

    @Override
    public void setOnDirtyStatusChanged(Runnable l) {
        this.onDirtyStatusChanged = l;
    }

    @Override
    public List<PropertyRow> getAllPropertyRows() {
        return allPropertyRows;
    }

    @Override
    public Map<String, JComponent> getPropertyComponents() {
        return propertyComponents;
    }

    @Override
    public Map<String, Supplier<String>> getValueSuppliers() {
        return valueSuppliers;
    }

    @Override
    public Map<String, String> getHelpTexts() {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getDefaultValues() {
        return Collections.emptyMap();
    }

    @Override
    public void setPathLabel(JLabel l) {
        this.pathLabel = l;
    }

    @Override
    public void setDownloadDatabaseBtn(JButton b) {
    }

    @Override
    public void setDownloadStatusLabel(JLabel l) {
    }

    @Override
    public void setStep1StatusIcon(JLabel l) {
    }

    @Override
    public void setStep2StatusIcon(JLabel l) {
    }

    @Override
    public void setGlobalSettings(GlobalSettings gs) {
        this.globalSettings = gs;
    }

    private Runnable onDirtyStatusChanged;

    private void reloadProfile() {
        loadProfileInternal(loadedProfileName);
    }
}