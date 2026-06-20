package application.module.database.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import application.module.database.api.PostgresApiModels;
import application.module.database.api.PostgresApiModels.MainVersionInfo;
import application.module.database.api.PostgresApiModels.SubVersionInfo;
import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.gui.DatabaseConfigurationPanel.PropertyRow;
import application.module.database.profile.PostgresProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.database.utils.DatabaseConfigurationUtils.ProgressListener;
import application.module.node.Signum;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawings;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.io.PathUtils;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.Comparator;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * PostgreSQL specific configuration and portable installation panel.
 */
public class PostgreSQLConfigurationPanel extends JPanel implements DatabaseEnginePanel {
    private static final Logger logger = LoggerFactory.getLogger(PostgreSQLConfigurationPanel.class);
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/zonkyio/embedded-postgres-binaries/releases?per_page=100";

    private Runnable restartAction;
    private String confFolder;
    private Runnable backAction;

    private GlobalSettings globalSettings = new GlobalSettings(); // Refactored: GlobalSettings POJO
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PostgresProfile currentProfile; // Use PostgresProfile object
    private JsonObject appliedProfileSettings = new JsonObject();

    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final List<PropertyRow> allPropertyRows = new ArrayList<>();
    private final Icon checkIcon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE, GuiConstants.getHelpIconSize(),
            GuiColors.getApplied());
    private final Icon errorIcon = IconFontSwing.buildIcon(FontAwesome.TIMES_CIRCLE, GuiConstants.getHelpIconSize(),
            GuiColors.getContrastRed());

    private JButton downloadDatabaseBtn, removeDatabaseBtn;
    private JButton renameProfileBtn, deleteProfileBtn;
    private JButton newProfileBtn, reloadProfileBtn, refreshProfilesBtn;
    private JButton initializeDatabaseBtn, updateConfigFileBtn, openConfigFileBtn;
    private JComboBox<String> profileComboBox;
    private JButton startDbBtn, stopDbBtn, restartDbBtn;
    private JTextPane consoleTextPane;
    private JPanel consoleWrapper;
    private CustomDrawingComponent consoleChevron;
    private JLabel consoleTitle;
    private boolean isConsoleExpanded = false;
    private Timer consoleAnimator;
    private int consoleHeight = 250;
    private JPanel searchResultsPanel;
    private CardLayout contentCardLayout;
    private JComponent verticalFiller;
    private JPanel contentContainer;

    private String loadedProfileName;
    private String runningProfileName = "";
    private String activeProfileName = "";
    private JComboBox<String> majorVersionCombo;
    private JComboBox<String> minorVersionCombo;
    private JComboBox<String> patchVersionCombo;
    /** Cached version list from GitHub releases API */
    private List<MainVersionInfo> allMainVersions;
    private String currentOsName;
    private String currentOsArch;
    private JLabel step1StatusIcon, step2StatusIcon, step3StatusIcon, step2HeaderLabel, step3HeaderLabel;
    private JLabel downloadStatusLabel, installedVersionLabel, pathLabel;
    private JPanel step2ContentPanel, step3ContentPanel, dbListPanel, userListPanel;
    private boolean isInitialized = false; // Initialize to false

    private static final String[] COMMON_POSTGRES_PARAMS = {
            "port", "max_connections", "shared_buffers", "effective_cache_size", "maintenance_work_mem",
            "checkpoint_completion_target", "wal_buffers", "default_statistics_target", "random_page_cost",
            "effective_io_capacity", "work_mem", "huge_pages", "min_wal_size", "max_wal_size"
    };
    private final Set<String> configurationKeysTracked = new LinkedHashSet<>();
    private JPanel dbControlPanel;

    // Permission Constants
    private static final String[] TYPICAL_HOSTS = { "localhost", "%", "127.0.0.1", "::1" };
    private static final String ALL_PERMISSIONS_KEYWORD = "ALL";
    private static final String[] GRANULAR_PERMISSIONS = {
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "CREATE", "CONNECT",
            "TEMPORARY", "EXECUTE", "USAGE"
    };
    private final Map<String, List<JCheckBox>> permissionCheckboxMaps = new HashMap<>();
    private JPanel tempGrantsContainer;
    private final List<PostgresProfile.UserGrant> tempGrantsForNewUser = new ArrayList<>();

    @Override
    public String getEngineName() {
        return DatabaseEngine.POSTGRESQL.getDisplayName();
    }

    @Override
    public Path getProfilePath(String profileName) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(DatabaseEngine.POSTGRESQL.toString()).resolve(profileName);
    }

    public void loadProfile(String profileName, GlobalSettings globalSettings) {
        this.globalSettings = globalSettings;
        loadProfileInternal(profileName);
    }

    public void resetToDefaults(GlobalSettings gs) {
        if (currentProfile != null) {
            this.globalSettings = gs;
            this.currentProfile = new PostgresProfile(loadedProfileName);
            refreshStep2();
            updateUIFromData();
        }
    }

    @Override
    public String getUnsavedChangesReport() {
        return hasUnsavedChanges() ? "PostgreSQL changes not saved." : null;
    }

    @Override
    public boolean hasUnsavedChanges() {
        if (currentProfile == null)
            return false;
        for (String key : currentProfile.getVisibleProperties()) {
            String currentVal = valueSuppliers.get(key) != null ? valueSuppliers.get(key).get() : "";
            String savedVal = currentProfile.getConfiguration().getOrDefault(key, "");
            if (!currentVal.equals(savedVal))
                return true;
        }
        return false;
    }

    public void setLoadedProfileName(String n) {
        this.loadedProfileName = n;
    }

    public String getLoadedProfileName() {
        return loadedProfileName;
    }

    public void setRunningProfileName(String n) {
        this.runningProfileName = n;
    }

    public String getRunningProfileName() {
        return runningProfileName;
    }

    public void setActiveProfileName(String n) {
        this.activeProfileName = n;
    }

    public String getActiveProfileName() {
        return activeProfileName;
    }

    @Override
    public JsonObject getCurrentProfileSettings() {
        return currentProfile != null ? currentProfile.toJsonObject() : new JsonObject();
    }

    @Override
    public void setAppliedProfileSettings(JsonObject s) {
        this.appliedProfileSettings = s;
    }

    @Override
    public void setOnDirtyStatusChanged(Runnable l) {
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
        return new HashMap<>();
    }

    @Override
    public Map<String, String> getDefaultValues() {
        return new HashMap<>();
    }

    @Override
    public void setPathLabel(JLabel l) {
        this.pathLabel = l;
    }

    @Override
    public void setDownloadDatabaseBtn(JButton b) {
        this.downloadDatabaseBtn = b;
    }

    @Override
    public void setDownloadStatusLabel(JLabel l) {
        this.downloadStatusLabel = l;
    }

    @Override
    public void setStep1StatusIcon(JLabel l) {
        this.step1StatusIcon = l;
    }

    @Override
    public void setStep2StatusIcon(JLabel l) {
        this.step2StatusIcon = l;
    }

    @Override
    public void setGlobalSettings(GlobalSettings gs) {
        this.globalSettings = gs;
        updateMajorVersions();
    }

    @Override
    public void refreshUIColors() {
        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            updateColor(entry.getValue(), entry.getKey());
        }
    }

    public PostgreSQLConfigurationPanel() {
        super(new BorderLayout());

        this.confFolder = Signum.CONF_FOLDER;

        this.currentOsName = DatabaseConfigurationUtils.getOsName();
        this.currentOsArch = DatabaseConfigurationUtils.getOsArch();

        JsonObject settingsJson = DatabaseConfigurationUtils.loadGlobalSettings();
        this.globalSettings = GSON.fromJson(settingsJson, GlobalSettings.class);
        DatabaseConfigurationUtils.ensureDirectoryStructure();

        String lastProfile = ConfigurationUtils
                .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER));
        String lastProfileName = null;

        if (lastProfile != null && !lastProfile.trim().isEmpty() && lastProfile.contains(":")) {
            String[] parts = lastProfile.split(":");
            if (parts[0].equals(DatabaseEngine.POSTGRESQL.getDisplayName())) {
                lastProfileName = parts[1];
            }
        }

        this.runningProfileName = lastProfileName;
        this.activeProfileName = lastProfileName;

        this.currentProfile = new PostgresProfile(null);

        initUI();
        this.isInitialized = true;
        loadProfileInternal(lastProfileName);
    }

    private void initUI() {
        JPanel body = new JPanel(new BorderLayout());
        step1StatusIcon = new JLabel();
        step2StatusIcon = new JLabel();
        step3StatusIcon = new JLabel();
        pathLabel = new JLabel("Profile Directory: N/A");
        pathLabel.setForeground(GuiColors.getFaintText());
        isConsoleExpanded = false;

        // --- Profile Panel ---
        JPanel profilePanel = new JPanel(new MigLayout("insets 5 10 5 5, gap 5"));
        profilePanel.setBorder(new EmptyBorder(5, 10, 5, 5));
        profilePanel.add(new JLabel("Profile:"));
        profileComboBox = new JComboBox<>();
        profileComboBox.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXX");
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

        reloadProfileBtn = new JButton("Reload Profile");
        reloadProfileBtn.addActionListener(e -> reloadProfile());
        profilePanel.add(reloadProfileBtn);

        refreshProfilesBtn = new JButton("Refresh Profiles List");
        refreshProfilesBtn.addActionListener(e -> refreshProfileList());
        profilePanel.add(refreshProfilesBtn);

        updateProfileButtonsUI();

        JButton helpBtn = new HelpButton();
        helpBtn.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Manage PostgreSQL profiles. Steps 1-3 guide you through installation and setup.", "Help",
                JOptionPane.INFORMATION_MESSAGE));
        profilePanel.add(helpBtn);

        updateProfileButtonsUI();

        profileComboBox.setRenderer(
                ConfigurationUtils.createProfileComboBoxRenderer(() -> runningProfileName, () -> activeProfileName));
        profileComboBox.addActionListener(e -> {
            if (isInitialized)
                loadProfileInternal((String) profileComboBox.getSelectedItem());
        });

        JScrollPane profileScrollPane = new JScrollPane(profilePanel);
        profileScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        profileScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        profileScrollPane.setBorder(BorderFactory.createEmptyBorder());
        GuiUtils.addHorizontalScrollPadding(profileScrollPane, profilePanel, new Insets(5, 10, 5, 5));

        // --- Database Control Panel ---
        dbControlPanel = new JPanel(new MigLayout("insets 5 10 0 5, gap 5, fillx", "[grow]", "[]5[]0[]"));
        dbControlPanel.setOpaque(false);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        btnRow.setOpaque(false);

        startDbBtn = new JButton("Start Database",
                IconFontSwing.buildIcon(FontAwesome.PLAY, GuiConstants.getHelpIconSize(), GuiColors.getApplied()));
        stopDbBtn = new JButton("Stop Database",
                IconFontSwing.buildIcon(FontAwesome.STOP, GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        restartDbBtn = new JButton("Restart Database",
                IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(), GuiColors.getSaved()));

        ConfigurationUtils.fixComponentSize(startDbBtn);
        ConfigurationUtils.fixComponentSize(stopDbBtn);
        ConfigurationUtils.fixComponentSize(restartDbBtn);

        startDbBtn.addActionListener(
                e -> executeDbControlWorker("Starting", currentProfile::ensureInstanceRunning, "PostgreSQL started."));
        stopDbBtn.addActionListener(
                e -> executeDbControlWorker("Stopping", currentProfile::stopInstance, "PostgreSQL stopped."));
        restartDbBtn.addActionListener(
                e -> executeDbControlWorker("Restarting", currentProfile::restartInstance, "PostgreSQL restarted."));

        btnRow.add(startDbBtn);
        btnRow.add(stopDbBtn);
        btnRow.add(restartDbBtn);
        dbControlPanel.add(btnRow, "wrap");

        // --- Console Section ---
        JPanel consoleHeader = new JPanel(new MigLayout("insets 5 0 0 0, gap 5", "[][grow]", "[]"));
        consoleHeader.setOpaque(false);

        consoleChevron = new CustomDrawingComponent(CustomDrawings.Chevron.DOWN);
        consoleChevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        consoleChevron.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleConsole();
            }
        });

        consoleTitle = new JLabel("Console Output");
        consoleTitle.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 13f));
        consoleTitle.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleConsole();
            }
        });

        consoleHeader.add(consoleChevron);
        consoleHeader.add(consoleTitle, "growx");
        dbControlPanel.add(consoleHeader, "growx, wrap");
        dbControlPanel.add(new JSeparator(SwingConstants.HORIZONTAL), "growx, wrap, gaptop 2");

        consoleWrapper = new JPanel(new BorderLayout());
        consoleWrapper.setOpaque(false);
        consoleWrapper.setPreferredSize(new Dimension(10, 0));

        consoleTextPane = new JTextPane();
        consoleTextPane.setEditable(false);
        consoleTextPane.setBackground(UIManager.getColor("TextArea.background"));
        consoleTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane consoleScroll = new JScrollPane(consoleTextPane);
        consoleScroll.setBorder(BorderFactory.createEmptyBorder());
        consoleWrapper.add(consoleScroll, BorderLayout.CENTER);

        // --- Console Input Section ---
        JPanel consoleInputPanel = new JPanel(new BorderLayout(5, 0));
        consoleInputPanel.setOpaque(false);
        consoleInputPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        JPanel southContainer = new JPanel(new BorderLayout());
        southContainer.setOpaque(false);
        southContainer.add(consoleInputPanel, BorderLayout.CENTER);

        JComponent commandSymbol = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                CustomDrawings.COMMAND_SYMBOL.draw((Graphics2D) g, getWidth(), getHeight(), GuiColors.getButtonIcon());
            }

            @Override
            public Dimension getPreferredSize() {
                int size = Math.round(GuiConstants.getToolBarIconSize());
                return new Dimension(size, size);
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        commandSymbol.setToolTipText("Command Input");

        JTextField consoleInputField = new JTextField();
        consoleInputField.putClientProperty("JTextField.placeholderText", "Enter SQL command for 'postgres' db...");
        ConfigurationUtils.styleInputComponent(consoleInputField);

        JButton sendCommandBtn = new JButton("Send");
        ConfigurationUtils.fixComponentSize(sendCommandBtn);

        ActionListener sendAction = e -> {
            String cmd = consoleInputField.getText().trim();
            if (!cmd.isEmpty()) {
                appendLog("> " + cmd);
                new Thread(
                        () -> currentProfile.runClientCommand(cmd, new DatabaseConfigurationUtils.ProgressListener() {
                            @Override
                            public void onProgress(String message, int progress) {
                            }

                            @Override
                            public void onLog(String line) {
                                appendLog(line);
                            }
                        })).start();
                consoleInputField.setText("");
            }
        };
        consoleInputField.addActionListener(sendAction);
        sendCommandBtn.addActionListener(sendAction);

        JPanel consoleBtnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        consoleBtnRow.setOpaque(false);
        consoleBtnRow.add(sendCommandBtn);
        consoleBtnRow.add(new HelpButton());

        consoleInputPanel.add(commandSymbol, BorderLayout.WEST);
        consoleInputPanel.add(consoleInputField, BorderLayout.CENTER);
        consoleInputPanel.add(consoleBtnRow, BorderLayout.EAST);

        JPanel resizer = new JPanel();
        resizer.setPreferredSize(new Dimension(10, 5));
        resizer.setCursor(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR));
        resizer.setOpaque(false);
        MouseAdapter resizerAdapter = new MouseAdapter() {
            private int startY, startH;

            @Override
            public void mousePressed(MouseEvent e) {
                startY = e.getYOnScreen();
                startH = consoleWrapper.getHeight();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int newH = Math.max(50, startH + (e.getYOnScreen() - startY));
                consoleHeight = newH;
                consoleWrapper.setPreferredSize(new Dimension(consoleWrapper.getWidth(), newH));
                dbControlPanel.revalidate();
            }
        };
        resizer.addMouseListener(resizerAdapter);
        resizer.addMouseMotionListener(resizerAdapter);

        southContainer.add(resizer, BorderLayout.SOUTH);
        consoleWrapper.add(southContainer, BorderLayout.SOUTH);
        dbControlPanel.add(consoleWrapper, "growx, h pref!, hidemode 3");

        // --- Search Panel ---
        JPanel searchPanel = new JPanel(new MigLayout("insets 5 10 5 5, fillx", "[][grow]", "[]"));
        searchPanel.add(new JLabel("Search:"));
        JTextField searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Type to filter properties...");
        ConfigurationUtils.styleInputComponent(searchField);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (isInitialized)
                    filterProperties(searchField.getText());
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (isInitialized)
                    filterProperties(searchField.getText());
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (isInitialized)
                    filterProperties(searchField.getText());
            }
        });
        searchPanel.add(searchField, "growx");

        JPanel northPanel = new JPanel(new BorderLayout());
        JPanel northTopPanel = new JPanel(new BorderLayout());
        northTopPanel.add(dbControlPanel, BorderLayout.NORTH);
        northTopPanel.add(profileScrollPane, BorderLayout.CENTER);
        northPanel.add(northTopPanel, BorderLayout.NORTH);
        northPanel.add(searchPanel, BorderLayout.SOUTH);
        body.add(northPanel, BorderLayout.NORTH);

        // --- Settings Panel ---
        allPropertyRows.clear();
        JPanel dbSettingsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 10", "[][grow]", ""));

        // Step 1: Download
        addSectionHeader(dbSettingsPanel, "Step 1: Download and Install Database Engine", step1StatusIcon, true);
        JPanel step1ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5", "[][grow]", ""));
        step1ContentPanel.setOpaque(false);
        populateStep1Content(step1ContentPanel);
        dbSettingsPanel.add(step1ContentPanel, "span, growx, wrap");

        // Step 2: Initialize
        addSectionHeader(dbSettingsPanel, "Step 2: Initialize Database Engine", step2StatusIcon, false);
        step2ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5"));
        step2ContentPanel.setOpaque(false);
        dbSettingsPanel.add(step2ContentPanel, "span, growx, wrap, hidemode 3");

        // Step 3: Users/DBs
        addSectionHeader(dbSettingsPanel, "Step 3: Configure Database and Users", step3StatusIcon, false);
        step3ContentPanel = new JPanel(new MigLayout("fillx, insets 0 25 0 0, gap 5"));
        step3ContentPanel.setOpaque(false);
        dbSettingsPanel.add(step3ContentPanel, "span, growx, wrap, hidemode 3");

        verticalFiller = new JLabel();
        dbSettingsPanel.add(verticalFiller, "pushy");

        // --- Content Container (CardLayout) ---
        contentCardLayout = new CardLayout();
        contentContainer = new JPanel(contentCardLayout);

        JScrollPane settingsScroll = new JScrollPane(dbSettingsPanel);
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(settingsScroll, "SETTINGS");

        searchResultsPanel = new JPanel(new MigLayout("fillx, insets 10, gap 5", "[][grow]"));
        JScrollPane searchScroll = new JScrollPane(searchResultsPanel);
        searchScroll.setBorder(BorderFactory.createEmptyBorder());
        contentContainer.add(searchScroll, "SEARCH");

        body.add(contentContainer, BorderLayout.CENTER);

        // Bottom
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBorder(new EmptyBorder(5, 10, 5, 5));
        bottom.add(ConfigurationUtils.createLegendPanel(this), BorderLayout.NORTH);
        bottom.add(pathLabel, BorderLayout.CENTER);
        body.add(bottom, BorderLayout.SOUTH);

        add(body, BorderLayout.CENTER);

        refreshProfileList();
    }

    private void populateStep1Content(JPanel panel) {
        installedVersionLabel = new JLabel("No PostgreSQL version installed.");
        installedVersionLabel.setForeground(GuiColors.getFaintText());
        panel.add(installedVersionLabel, "span, wrap, gapbottom 5");

        panel.add(new JLabel("Version:"), "split 2, gaptop 5");
        JPanel vPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        vPanel.setOpaque(false);

        majorVersionCombo = new JComboBox<>();
        minorVersionCombo = new JComboBox<>();
        patchVersionCombo = new JComboBox<>();
        ConfigurationUtils.fixComponentSize(majorVersionCombo);
        ConfigurationUtils.fixComponentSize(minorVersionCombo);
        ConfigurationUtils.fixComponentSize(patchVersionCombo);

        majorVersionCombo.addActionListener(e -> updateMinorVersions());
        minorVersionCombo.addActionListener(e -> updatePatchVersions());

        vPanel.add(majorVersionCombo);
        vPanel.add(new JLabel("."));
        vPanel.add(minorVersionCombo);
        vPanel.add(new JLabel("."));
        vPanel.add(patchVersionCombo);
        panel.add(vPanel, "growx, height pref!, wrap, gaptop 5");

        downloadDatabaseBtn = new JButton("Download & Install");
        downloadDatabaseBtn.addActionListener(e -> downloadAndInstall());
        ConfigurationUtils.fixComponentSize(downloadDatabaseBtn);

        removeDatabaseBtn = new JButton("Remove & Uninstall");
        removeDatabaseBtn.addActionListener(e -> removeAndUninstallDatabase());
        ConfigurationUtils.fixComponentSize(removeDatabaseBtn);

        panel.add(downloadDatabaseBtn, "split 2, gapleft 5");
        panel.add(removeDatabaseBtn, "wrap");

        downloadStatusLabel = new JLabel("");
        downloadStatusLabel.setForeground(GuiColors.getFaintText());
        panel.add(downloadStatusLabel, "span, wrap");

        updateMajorVersions();
    }

    @Override
    public void updateUIFromData() {
        if (currentProfile == null)
            return;
        refreshStep2();
        refreshStep3();

        for (Map.Entry<String, JComponent> entry : propertyComponents.entrySet()) {
            String key = entry.getKey();
            JComponent comp = entry.getValue();
            String val = getProfileValue(key);
            if (comp instanceof JComboBox)
                ((JComboBox<?>) comp).setSelectedItem(val);
            else if (comp instanceof javax.swing.text.JTextComponent)
                ((javax.swing.text.JTextComponent) comp).setText(val);
            updateColor(comp, key);
        }

        if (currentProfile.getDownloadedVersion() != null) {
            installedVersionLabel.setText("Installed: " + currentProfile.getInstalledVersion());
            installedVersionLabel.setForeground(GuiColors.getSaved());
        }

        pathLabel.setText("Profile Directory: "
                + (currentProfile.getBaseDir() != null ? currentProfile.getBaseDir().toAbsolutePath().toString()
                        : "N/A"));

        boolean s1 = currentProfile.isStep1Completed();
        boolean s2 = s1 && currentProfile.isStep2Completed();
        boolean s3 = s2 && currentProfile.isStep3Completed();

        setStepStatus(step1StatusIcon, s1);
        setStepStatus(step2StatusIcon, s1 ? s2 : null);
        setStepStatus(step3StatusIcon, s2 ? s3 : null);

        if (step2HeaderLabel != null)
            step2HeaderLabel.setVisible(s1);
        if (step2ContentPanel != null)
            step2ContentPanel.setVisible(s1);
        if (step3HeaderLabel != null)
            step3HeaderLabel.setVisible(s2);
        if (step3ContentPanel != null)
            step3ContentPanel.setVisible(s2);

        updateProfileButtonStates();
    }

    private String getProfileValue(String key) {
        if (currentProfile == null)
            return "";
        switch (key) {
            case "adminUsername":
                return currentProfile.getAdminUsername();
            case "adminPassword":
                return currentProfile.getAdminPassword();
            default:
                return currentProfile.getConfiguration().getOrDefault(key, "");
        }
    }

    private void refreshStep2() {
        if (step2ContentPanel == null || currentProfile == null)
            return;
        step2ContentPanel.removeAll();
        allPropertyRows.removeIf(row -> row.originalParent == step2ContentPanel);

        for (String key : currentProfile.getVisibleProperties()) {
            addProperty(step2ContentPanel, key, key);
        }
        addQuickAddSection(step2ContentPanel);

        JPanel actions = new JPanel(new MigLayout("insets 0, gap 5"));
        actions.setOpaque(false);
        initializeDatabaseBtn = new JButton("Initialize Database");
        initializeDatabaseBtn.addActionListener(e -> initializeDB());
        actions.add(initializeDatabaseBtn);

        updateConfigFileBtn = new JButton("Save all to postgresql.conf"); // Renamed as per request
        updateConfigFileBtn.addActionListener(e -> updateConfigFile());
        actions.add(updateConfigFileBtn);

        openConfigFileBtn = new JButton("Open config");
        openConfigFileBtn.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(new File(currentProfile.getConfigFilePath()));
            } catch (Exception ex) {
            }
        });
        actions.add(openConfigFileBtn);
        step2ContentPanel.add(actions, "span, growx, wrap, gaptop 5");

        step2ContentPanel.revalidate();
        step2ContentPanel.repaint();
    }

    private void refreshStep3() {
        if (step3ContentPanel == null || currentProfile == null)
            return;
        step3ContentPanel.removeAll();

        // Admin section
        addSectionHeader(step3ContentPanel, "Admin Credentials", null, false);
        addProperty(step3ContentPanel, "adminUsername", "Admin Username");
        addPasswordProperty(step3ContentPanel, "adminPassword", "Admin Password");

        // DB list
        addSectionHeader(step3ContentPanel, "Created Databases", null, false);
        dbListPanel = new JPanel(new MigLayout("fillx, insets 0, gap 5", "[][grow][][]"));
        dbListPanel.setOpaque(false);
        updateDatabaseListUI();
        step3ContentPanel.add(dbListPanel, "span, growx, wrap, gaptop 10");

        JPanel addDbPanel = new JPanel(new MigLayout("insets 0, gap 5", "[][grow][]"));
        addDbPanel.setOpaque(false);
        JTextField dbNameF = createStyledTextField("signum");
        JButton addDbBtn = new JButton("ADD");
        addDbBtn.addActionListener(e -> {
            String name = dbNameF.getText().trim();
            if (!name.isEmpty()) {
                try {
                    currentProfile.addCreatedDatabase(name, "", "");
                    updateUIFromData();
                } catch (Exception ex) {
                    logger.error("Add DB fail", ex);
                }
            }
        });
        addDbPanel.add(new JLabel("New Database:"));
        addDbPanel.add(dbNameF, "growx");
        addDbPanel.add(addDbBtn, "wrap");
        step3ContentPanel.add(addDbPanel, "span, growx, wrap");

        // Users list
        addSectionHeader(step3ContentPanel, "Database Users", null, false);
        userListPanel = new JPanel(new MigLayout("fillx, insets 0, gap 5"));
        userListPanel.setOpaque(false);
        updateUserListUI();
        step3ContentPanel.add(userListPanel, "span, growx, wrap");

        // Add User section
        addSectionHeader(step3ContentPanel, "Add New User", null, false);
        JPanel addUserPanel = new JPanel(new MigLayout("insets 0, gap 5, fillx", "[pref!][grow][pref!][grow]", ""));
        addUserPanel.setOpaque(false);

        JTextField newU = createStyledTextField("");
        JComboBox<String> newH = new JComboBox<>(TYPICAL_HOSTS);
        newH.setEditable(true);
        JPasswordField newP = new JPasswordField();

        addUserPanel.add(new JLabel("User:"));
        addUserPanel.add(newU, "growx");
        addUserPanel.add(new JLabel("Host:"));
        addUserPanel.add(newH, "growx, wrap");
        addUserPanel.add(new JLabel("Password:"));
        addUserPanel.add(newP, "growx, span, wrap");

        tempGrantsContainer = new JPanel(new MigLayout("insets 0, fillx, gap 5"));
        tempGrantsContainer.setOpaque(false);
        addUserPanel.add(tempGrantsContainer, "span, growx, wrap");

        JButton addUserBtn = new JButton("ADD USER");
        addUserBtn.addActionListener(e -> {
            String name = newU.getText().trim();
            String pass = new String(newP.getPassword());
            String host = newH.getSelectedItem().toString();
            if (!name.isEmpty()) {
                try {
                    currentProfile.addCreatedUser(name, pass, host, new ArrayList<>(tempGrantsForNewUser));
                    tempGrantsForNewUser.clear();
                    updateUIFromData();
                    newU.setText("");
                    newP.setText("");
                } catch (Exception ex) {
                    logger.error("Add user fail", ex);
                }
            }
        });
        addUserPanel.add(addUserBtn, "span, wrap, gaptop 5");
        step3ContentPanel.add(addUserPanel, "span, growx, wrap");
        updateTempGrantsUI();

        JButton runSetupBtn = new JButton("Run Setup (Apply SQL)");
        runSetupBtn.addActionListener(e -> runSetup());
        step3ContentPanel.add(runSetupBtn, "span, wrap, gaptop 10");

        step3ContentPanel.revalidate();
        step3ContentPanel.repaint();
    }

    private void updateDatabaseListUI() {
        if (dbListPanel == null)
            return;
        dbListPanel.removeAll();
        for (PostgresProfile.DatabaseInfo db : currentProfile.getCreatedDatabases()) {
            dbListPanel.add(new JLabel("Database: " + db.name), "growx");
            JButton del = new JButton(IconFontSwing.buildIcon(FontAwesome.TRASH, GuiConstants.getHelpIconSize(),
                    GuiColors.getContrastRed()));
            del.addActionListener(e -> {
                try {
                    currentProfile.removeDatabase(db.id);
                    updateUIFromData();
                } catch (Exception ex) {
                }
            });
            dbListPanel.add(del, "wrap");
        }
    }

    private void updateUserListUI() {
        if (userListPanel == null)
            return;
        userListPanel.removeAll();
        if (currentProfile != null) {
            for (PostgresProfile.UserInfo user : currentProfile.getCreatedUsers()) {
                JPanel uPanel = new JPanel(new MigLayout("fillx, insets 5, gap 5", "[][grow][][]"));
                uPanel.setBorder(BorderFactory.createTitledBorder("User: " + user.username + "@" + user.host));
                uPanel.setOpaque(false);

                uPanel.add(new JLabel("Grants:"), "span, wrap");
                for (PostgresProfile.UserGrant grant : user.grants) {
                    String dbName = grant.databaseId.equals("global") ? "GLOBAL"
                            : currentProfile.getCreatedDatabases().stream().filter(d -> d.id.equals(grant.databaseId))
                                    .map(d -> d.name).findFirst().orElse("?");
                    uPanel.add(new JLabel(dbName + ": " + grant.permissions), "growx");

                    JButton delG = new JButton(IconFontSwing.buildIcon(FontAwesome.TRASH,
                            GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
                    delG.setToolTipText("Revoke");
                    delG.addActionListener(e -> {
                        try {
                            currentProfile.removeUserGrant(user.id, grant.databaseId);
                            updateUIFromData();
                        } catch (Exception ex) {
                        }
                    });
                    uPanel.add(delG, "wrap");
                }

                JButton delU = new JButton("Delete User", IconFontSwing.buildIcon(FontAwesome.USER_TIMES,
                        GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
                delU.addActionListener(e -> {
                    if (JOptionPane.showConfirmDialog(this,
                            "Delete user " + user.username + "?") == JOptionPane.YES_OPTION) {
                        try {
                            currentProfile.removeUser(user.id);
                            updateUIFromData();
                        } catch (Exception ex) {
                        }
                    }
                });
                uPanel.add(delU, "span, right");
                userListPanel.add(uPanel, "growx, wrap");
            }
        }
    }

    private void updateTempGrantsUI() {
        if (tempGrantsContainer == null)
            return;
        tempGrantsContainer.removeAll();
        tempGrantsContainer.setLayout(new MigLayout("fillx, insets 0, gap 5", "[][grow][][]"));

        if (!tempGrantsForNewUser.isEmpty()) {
            for (PostgresProfile.UserGrant g : tempGrantsForNewUser) {
                String dbName = g.databaseId.equals("global") ? "GLOBAL"
                        : currentProfile.getCreatedDatabases().stream().filter(d -> d.id.equals(g.databaseId))
                                .map(d -> d.name).findFirst().orElse("?");
                tempGrantsContainer.add(new JLabel(dbName + ": " + g.permissions), "growx");
                JButton del = new JButton(IconFontSwing.buildIcon(FontAwesome.TIMES, GuiConstants.getHelpIconSize(),
                        GuiColors.getContrastRed()));
                del.addActionListener(e -> {
                    tempGrantsForNewUser.remove(g);
                    updateTempGrantsUI();
                });
                tempGrantsContainer.add(del, "wrap");
            }
            tempGrantsContainer.add(new JSeparator(), "span, growx, wrap");
        }

        JComboBox<String> dbSel = new JComboBox<>();
        dbSel.addItem("GLOBAL");
        currentProfile.getCreatedDatabases().forEach(d -> dbSel.addItem(d.name + " (" + d.id + ")"));

        // Use CheckSelectionComboBox logic like in MariaDB
        String propKey = "temp_new_user_perms";
        JComponent permsPanel = createPermissionCheckboxesPanel(propKey, "ALL", true);

        tempGrantsContainer.add(new JLabel("New Grant:"));
        tempGrantsContainer.add(dbSel, "growx");
        tempGrantsContainer.add(permsPanel, "growx");

        JButton addG = new JButton("Add Grant");
        addG.addActionListener(e -> {
            String sel = (String) dbSel.getSelectedItem();
            String id = sel.equals("GLOBAL") ? "global" : sel.substring(sel.lastIndexOf('(') + 1, sel.length() - 1);
            tempGrantsForNewUser.add(new PostgresProfile.UserGrant(id, getPermissionsString(propKey)));
            updateTempGrantsUI();
        });
        tempGrantsContainer.add(addG, "wrap");

        tempGrantsContainer.revalidate();
        tempGrantsContainer.repaint();
    }

    private JComponent createPermissionCheckboxesPanel(String propertyKey, String initialPermissions,
            boolean isEditable) {
        DatabaseConfigurationUtils.CheckSelectionComboBox comboBox = new DatabaseConfigurationUtils.CheckSelectionComboBox(
                initialPermissions, GRANULAR_PERMISSIONS, ALL_PERMISSIONS_KEYWORD,
                newVal -> updateColor(propertyComponents.get(propertyKey), propertyKey));
        comboBox.setEnabled(isEditable);
        ConfigurationUtils.styleInputComponent(comboBox);
        ConfigurationUtils.fixComponentSize(comboBox);

        propertyComponents.put(propertyKey, comboBox);
        valueSuppliers.put(propertyKey, comboBox::getSelectedItemsString);
        return comboBox;
    }

    private String getPermissionsString(String propertyKey) {
        JComponent comp = propertyComponents.get(propertyKey);
        if (comp instanceof DatabaseConfigurationUtils.CheckSelectionComboBox) {
            return ((DatabaseConfigurationUtils.CheckSelectionComboBox) comp).getSelectedItemsString();
        }
        return "";
    }

    private void updateProfileButtonsUI() {
        // The saveProfileBtn was removed, ensure this argument is null
        ConfigurationUtils.configureProfileToolbar(newProfileBtn, null, null, renameProfileBtn, deleteProfileBtn,
                reloadProfileBtn, refreshProfilesBtn, null);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (allPropertyRows != null) {
            for (PropertyRow row : allPropertyRows) {
                if (row.propertyKey == null && row.label != null) {
                    row.label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
                }
                if (row.input != null) {
                    ConfigurationUtils.styleInputComponent(row.input);
                    ConfigurationUtils.fixComponentSize(row.input);
                }
            }
        }
        if (consoleTitle != null) {
            consoleTitle.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 13f));
        }
        updateProfileButtonsUI();
        if (startDbBtn != null) {
            startDbBtn.setIcon(
                    IconFontSwing.buildIcon(FontAwesome.PLAY, GuiConstants.getHelpIconSize(), GuiColors.getApplied()));
            stopDbBtn.setIcon(IconFontSwing.buildIcon(FontAwesome.STOP, GuiConstants.getHelpIconSize(),
                    GuiColors.getContrastRed()));
            restartDbBtn.setIcon(
                    IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(), GuiColors.getSaved()));
        }
    }

    private void addQuickAddSection(JPanel panel) {
        JPanel quickAdd = new JPanel(new MigLayout("insets 0, gap 5", "[][][grow][]"));
        quickAdd.setOpaque(false);
        quickAdd.add(new JLabel("Add Parameter:"), "align label");
        JComboBox<String> paramCombo = new JComboBox<>(COMMON_POSTGRES_PARAMS);
        paramCombo.setEditable(true);
        ConfigurationUtils.fixComponentSize(paramCombo);
        quickAdd.add(paramCombo);
        JTextField valField = createStyledTextField("");
        quickAdd.add(valField, "growx");
        JButton addBtn = new JButton("Add");
        addBtn.addActionListener(e -> {
            String k = (String) paramCombo.getSelectedItem();
            if (k != null && !k.trim().isEmpty()) {
                currentProfile.addVisibleProperty(k.trim(), valField.getText().trim());
                updateUIFromData();
            }
        });
        quickAdd.add(addBtn, "wrap");
        panel.add(quickAdd, "span, growx, wrap, gaptop 10");
    }

    private void addProperty(JPanel panel, String key, String labelText) {
        PropertyRow row = new PropertyRow(key, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;

        JTextField field = createStyledTextField(getProfileValue(key));
        propertyComponents.put(key, field);
        valueSuppliers.put(key, () -> field.getText());
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() {
                updateColor(field, key);
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }
        });

        row.input = field;
        panel.add(label, "align label");
        panel.add(field, "split 2, growx, height pref!");
        panel.add(new HelpButton(), "wrap");
        allPropertyRows.add(row);
    }

    private void addPasswordProperty(JPanel panel, String key, String labelText) {
        PropertyRow row = new PropertyRow(key, labelText, panel);
        JLabel label = new JLabel(labelText);
        row.label = label;
        panel.add(label, "align label");

        JPasswordField pf = new JPasswordField(currentProfile.getAdminPassword());
        ConfigurationUtils.styleInputComponent(pf);
        propertyComponents.put(key, pf);
        valueSuppliers.put(key, () -> new String(pf.getPassword()));
        panel.add(pf, "split 2, growx, height pref!");
        JCheckBox show = new JCheckBox("Show Password");
        char def = pf.getEchoChar();
        show.addActionListener(e -> pf.setEchoChar(show.isSelected() ? (char) 0 : def));
        panel.add(show, "wrap");
        allPropertyRows.add(row);
    }

    private void addSectionHeader(JPanel panel, String title, JLabel statusIcon, boolean isFirst) {
        PropertyRow row = new PropertyRow(null, title, panel);
        JLabel label = new JLabel(title);
        label.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 14f));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()));
        row.label = label;
        if (title.contains("Step 2"))
            step2HeaderLabel = label;
        if (title.contains("Step 3"))
            step3HeaderLabel = label;

        String gap = (isFirst ? "" : "gaptop 15, ") + "gapbottom 5, hidemode 3";
        panel.add(label, gap + ", split 2, growx");
        if (statusIcon != null)
            panel.add(statusIcon, gap + ", wrap");
        else
            panel.add(new JLabel(), "wrap");
        allPropertyRows.add(row);
    }

    private void loadProfileInternal(String name) {
        this.loadedProfileName = name;
        tempGrantsForNewUser.clear();
        if (name == null || name.trim().isEmpty())
            this.currentProfile = new PostgresProfile(null);
        else {
            Path json = getProfilePath(name).resolve("profile.json");
            if (Files.exists(json)) {
                try {
                    this.currentProfile = new PostgresProfile(name,
                            JsonParser.parseReader(Files.newBufferedReader(json)).getAsJsonObject());
                } catch (Exception e) {
                    logger.error("Error loading profile: {}", e.getMessage());
                    this.currentProfile = new PostgresProfile(name);
                }
            } else
                this.currentProfile = new PostgresProfile(name);
        }
        this.activeProfileName = name;
        refreshStep2();
        updateUIFromData();
        updateProfileComboBoxColor();
    }

    private void updateColor(JComponent comp, String key) {
        if (currentProfile == null)
            return;
        String saved = getProfileValue(key);
        String current = valueSuppliers.get(key) != null ? valueSuppliers.get(key).get() : "";

        String applied = appliedProfileSettings.has(key) ? appliedProfileSettings.get(key).getAsString() : "";

        if (!current.equals(saved))
            comp.setForeground(GuiColors.getUnsaved());
        else if (current.equals(applied))
            comp.setForeground(GuiColors.getApplied());
        else
            comp.setForeground(GuiColors.getSaved());
    }

    private void toggleConsole() {
        if (consoleAnimator != null && consoleAnimator.isRunning())
            return;
        isConsoleExpanded = !isConsoleExpanded;
        consoleChevron.setDrawing(isConsoleExpanded ? CustomDrawings.Chevron.UP : CustomDrawings.Chevron.DOWN);

        final int targetH = isConsoleExpanded ? consoleHeight : 0;
        final int startH = consoleWrapper.getHeight();

        consoleAnimator = new Timer(10, new ActionListener() {
            long start = System.currentTimeMillis();

            @Override
            public void actionPerformed(ActionEvent e) {
                float progress = Math.min(1f, (System.currentTimeMillis() - start) / 200f);
                progress = 1.0f - (float) Math.pow(1.0f - progress, 3); // Ease out

                int h = (int) (startH + (targetH - startH) * progress);
                consoleWrapper.setPreferredSize(new Dimension(consoleWrapper.getWidth(), h));
                dbControlPanel.revalidate();

                if (progress >= 1f)
                    ((Timer) e.getSource()).stop();
            }
        });
        consoleAnimator.start();
    }

    private JTextField createStyledTextField(String text) {
        JTextField tf = new JTextField(text);
        ConfigurationUtils.styleInputComponent(tf);
        ConfigurationUtils.fixComponentSize(tf);
        return tf;
    }

    private void createNewProfile() {
        String n = JOptionPane.showInputDialog(this, "Profile Name:", "New Profile", JOptionPane.QUESTION_MESSAGE);
        if (n != null && !n.isEmpty()) {
            try {
                Path p = getProfilePath(n);
                if (Files.exists(p)) {
                    JOptionPane.showMessageDialog(this, "Profile already exists.", "Error", JOptionPane.ERROR_MESSAGE);
                } else {
                    Files.createDirectories(p);
                    PostgresProfile np = new PostgresProfile(n);
                    np.saveToProfileJson(new HashMap<>());
                    refreshProfileList();
                    profileComboBox.setSelectedItem(n);
                }
            } catch (Exception e) {
                logger.error("Create fail", e);
                JOptionPane.showMessageDialog(this, "Failed: " + e.getMessage());
            }
        }
    }

    private void refreshProfileList() {
        profileComboBox.removeAllItems();
        DatabaseConfigurationUtils.getProfileNames(confFolder, "PostgreSQL").forEach(profileComboBox::addItem);

        if (profileComboBox.getItemCount() > 0) {
            if (activeProfileName != null)
                profileComboBox.setSelectedItem(activeProfileName);
            else if (loadedProfileName != null)
                profileComboBox.setSelectedItem(loadedProfileName);
            else
                profileComboBox.setSelectedIndex(0);
        }
        updateProfileButtonStates();
    }

    private void updateProfileButtonStates() {
        String sel = (String) profileComboBox.getSelectedItem();
        boolean hasProf = sel != null && !sel.trim().isEmpty();
        boolean s1 = currentProfile != null && currentProfile.isStep1Completed();
        boolean s2 = currentProfile != null && currentProfile.isStep2Completed();

        renameProfileBtn.setEnabled(hasProf);
        deleteProfileBtn.setEnabled(hasProf);
        if (startDbBtn != null)
            startDbBtn.setEnabled(s1 && s2);
        if (stopDbBtn != null)
            stopDbBtn.setEnabled(s1 && s2);
        if (restartDbBtn != null)
            restartDbBtn.setEnabled(s1 && s2);
    }

    private void renameProfile(String old) {
        if (old == null)
            return;
        String n = (String) JOptionPane.showInputDialog(this, "Enter new name for profile '" + old + "':",
                "Rename Profile", JOptionPane.PLAIN_MESSAGE,
                null, null, old);
        if (n == null || n.trim().isEmpty() || n.equals(old))
            return;

        Path oldPath = getProfilePath(old);
        Path newPath = getProfilePath(n);

        if (Files.exists(newPath)) {
            JOptionPane.showMessageDialog(this, "Profile '" + n + "' already exists.", "Hiba",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Ellenőrizzük, hogy ez-e a futó profil
        boolean isThisRunning = old.equals(runningProfileName);

        if (isThisRunning) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "The profile '" + old + "' is currently in use.\n" +
                            "Renaming it will cause the Node and Database to shut down and restart automatically.\n\n" +
                            "Do you want to proceed?",
                    "Confirm Node Restart",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION)
                return;
        }

        executeRenameProfileWorker(old, n, oldPath, newPath, isThisRunning);
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

        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- Renaming PostgreSQL profile: " + oldName + " -> " + newName + " ---");

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isRunning) {
                    publish(new ProgressInfo("Stopping Signum Node core...", 10));
                    Signum.shutdownNode();
                    Thread.sleep(2000);

                    publish(new ProgressInfo("Stopping PostgreSQL instance...", 30));
                    currentProfile.stopInstance((msg, p) -> publish(new ProgressInfo(msg, 30 + (p / 5))));
                }

                publish(new ProgressInfo("Moving profile folder...", 60));
                Files.move(oldPath, newPath);

                publish(new ProgressInfo("Updating metadata...", 70));
                ConfigurationUtils.updateAppliedProfile(
                        ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                        "PostgreSQL:" + newName);

                if (oldName.equals(loadedProfileName)) {
                    loadedProfileName = newName;
                    if (currentProfile != null)
                        currentProfile.setProfileName(newName);
                }
                if (oldName.equals(runningProfileName)) {
                    runningProfileName = newName;
                    activeProfileName = newName;
                }

                if (isRunning) {
                    publish(new ProgressInfo("Restarting PostgreSQL instance...", 80));
                    if (currentProfile != null) {
                        currentProfile.ensureInstanceRunning((msg, p) -> publish(new ProgressInfo(msg, 80 + (p / 10))));
                    }

                    publish(new ProgressInfo("Restarting Signum Node core...", 95));
                    Signum.startNode();
                }

                return null;
            }

            @Override
            protected void process(List<ProgressInfo> chunks) {
                ProgressInfo info = chunks.get(chunks.size() - 1);
                statusLabel.setText(info.message);
                progressBar.setValue(info.progress);
                appendLog(info.message);
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    refreshProfileList();
                    profileComboBox.setSelectedItem(newName);
                    updateUIFromData();
                    JOptionPane.showMessageDialog(PostgreSQLConfigurationPanel.this, "Profile renamed successfully.");
                } catch (Exception e) {
                    logger.error("Rename operation failed", e);
                    JOptionPane.showMessageDialog(PostgreSQLConfigurationPanel.this,
                            "Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void deleteProfile(String n) {
        if (n == null)
            return;
        if (JOptionPane.showConfirmDialog(this, "Delete profile '" + n + "' and all data?", "Confirm",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            try {
                DatabaseConfigurationUtils.deleteDirectoryRecursively(getProfilePath(n));
                refreshProfileList();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Delete failed");
            }
        }
    }

    private void filterProperties(String text) {
        boolean isSearch = text != null && !text.trim().isEmpty();
        if (isSearch) {
            searchResultsPanel.removeAll();
            String low = text.toLowerCase();
            for (PropertyRow row : allPropertyRows) {
                if (row.propertyKey != null
                        && (row.propertyKey.toLowerCase().contains(low) || row.labelText.toLowerCase().contains(low))) {
                    if (row.label != null)
                        searchResultsPanel.add(row.label, "align label");
                    if (row.input != null)
                        searchResultsPanel.add(row.input, "growx, height pref!");
                    if (row.help != null)
                        searchResultsPanel.add(row.help, "wrap");
                }
            }
            contentCardLayout.show(contentContainer, "SEARCH");
        } else {
            contentCardLayout.show(contentContainer, "SETTINGS");
        }
        revalidate();
        repaint();
    }

    private void saveProfile() {
        String selected = (String) profileComboBox.getSelectedItem();
        if (selected == null)
            return;

        ConfigurationUtils.updateAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                "PostgreSQL:" + selected);
        this.runningProfileName = selected;
        this.activeProfileName = selected;
        updateProfileComboBoxColor();
    }

    private void setStepStatus(JLabel label, Boolean success) {
        if (success == null) {
            label.setVisible(false);
            return;
        }
        label.setIcon(success ? checkIcon : errorIcon);
        label.setVisible(true);
    }

    private void appendLog(String s) {
        SwingUtilities.invokeLater(() -> {
            try {
                javax.swing.text.Document doc = consoleTextPane.getDocument();
                doc.insertString(doc.getLength(), s + "\n", null);
                consoleTextPane.setCaretPosition(doc.getLength());
            } catch (Exception e) {
            }
        });
    }

    private void updateProfileComboBoxColor() {
        ConfigurationUtils.updateProfileComboBoxColor(profileComboBox, runningProfileName, loadedProfileName);
    }

    @FunctionalInterface
    private interface DbControlAction {
        void execute(DatabaseConfigurationUtils.ProgressListener l) throws Exception;
    }

    private void executeDbControlWorker(String actionName, DbControlAction action, String successMsg) {
        if (currentProfile == null)
            return;
        if (!isConsoleExpanded)
            toggleConsole();
        appendLog("\n--- " + actionName + " PostgreSQL Operation ---");

        final JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        final JLabel statusLabel = new JLabel("Preparing...");
        statusLabel.setFont(UIManager.getFont("Label.font"));

        JPanel progressPanel = new JPanel(new MigLayout("fillx, insets 20", "[grow]", "[]10[]"));
        progressPanel.add(statusLabel, "wrap");
        progressPanel.add(progressBar, "growx");

        final JDialog progressDialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                actionName + " PostgreSQL", Dialog.ModalityType.APPLICATION_MODAL);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setMinimumSize(new Dimension(450, progressDialog.getHeight()));
        progressDialog.setLocationRelativeTo(this);

        new SwingWorker<Void, ProgressInfo>() {
            @Override
            protected Void doInBackground() throws Exception {
                action.execute((msg, p) -> publish(new ProgressInfo(msg, p)));
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
                    updateUIFromData();
                    JOptionPane.showMessageDialog(PostgreSQLConfigurationPanel.this, successMsg, "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(PostgreSQLConfigurationPanel.this,
                            "Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progressDialog.setVisible(true);
    }

    private void updateMajorVersions() {
        new SwingWorker<List<MainVersionInfo>, Void>() {
            @Override
            protected List<MainVersionInfo> doInBackground() throws Exception {
                URL url = new URL(GITHUB_RELEASES_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "SignumConfigTool");
                if (conn.getResponseCode() == 200) {
                    String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    JsonArray releasesJson = JsonParser.parseString(json).getAsJsonArray();
                    return PostgresApiModels.parseReleases(releasesJson, currentOsName, currentOsArch);
                }
                return Collections.emptyList();
            }

            @Override
            protected void done() {
                try {
                    allMainVersions = get();
                    majorVersionCombo.removeAllItems();
                    allMainVersions.stream()
                            .sorted(Comparator
                                    .comparingInt((MainVersionInfo v) -> Integer.parseInt(v.name))
                                    .reversed())
                            .forEach(v -> majorVersionCombo.addItem(v.name));
                } catch (Exception e) {
                    logger.warn("Version fetch fail: {}", e.getMessage());
                }
            }
        }.execute();
    }

    private void updateMinorVersions() {
        minorVersionCombo.removeAllItems();
        String major = (String) majorVersionCombo.getSelectedItem();
        if (major != null && allMainVersions != null) {
            Optional<MainVersionInfo> mainVer = allMainVersions.stream()
                    .filter(v -> v.name.equals(major)).findFirst();
            mainVer.ifPresent(v -> v.subVersions.stream()
                    .sorted(Comparator.comparingInt((SubVersionInfo a) -> Integer.parseInt(a.name))
                            .reversed())
                    .forEach(a -> minorVersionCombo.addItem(a.name)));
        }
    }

    private void updatePatchVersions() {
        patchVersionCombo.removeAllItems();
        String major = (String) majorVersionCombo.getSelectedItem();
        String minor = (String) minorVersionCombo.getSelectedItem();
        if (major != null && minor != null && allMainVersions != null) {
            Optional<SubVersionInfo> subVer = allMainVersions.stream()
                    .filter(v -> v.name.equals(major))
                    .flatMap(v -> v.subVersions.stream().filter(a -> a.name.equals(minor)))
                    .findFirst();
            subVer.ifPresent(v -> v.downloads.forEach(d -> {
                // Extract patch version from filename like
                // embedded-postgres-binaries-windows-amd64-17.6.0.zip
                String file = d.file;
                if (file != null && file.contains(".zip")) {
                    String verPart = file.replace(".zip", "");
                    String[] parts = verPart.split("-");
                    if (parts.length > 0) {
                        String fullVer = parts[parts.length - 1];
                        String[] verDots = fullVer.split("\\.");
                        patchVersionCombo.addItem(verDots.length >= 3 ? verDots[2] : fullVer);
                    }
                }
            }));
        }
    }

    private void downloadAndInstall() {
        String patch = (String) patchVersionCombo.getSelectedItem();
        if (patch == null)
            return;
        String ver = String.format("%s.%s.%s", majorVersionCombo.getSelectedItem(), minorVersionCombo.getSelectedItem(),
                patch);
        String os = currentOsName.equalsIgnoreCase("windows") ? "windows" : "linux";
        String arch = currentOsArch.equalsIgnoreCase("x64") ? "amd64" : "arm64";
        String url = String.format(
                "https://github.com/zonkyio/embedded-postgres-binaries/releases/download/v%s/embedded-postgres-binaries-%s-%s-%s.zip",
                ver, os, arch, ver);

        executeDbControlWorker("Downloading", l -> currentProfile.install(url, ver, currentOsArch, l),
                "Installation successful.");
    }

    private void initializeDB() {
        executeDbControlWorker("Initializing", currentProfile::initializeInstance, "Database instance initialized.");
    }

    private void runSetup() {
        executeDbControlWorker("Setup", currentProfile::setupDatabase, "SQL setup complete.");
    }

    private void reloadProfile() {
        loadProfileInternal(loadedProfileName);
    }

    private void updateConfigFile() {
        try {
            currentProfile.writeConfigFile();
            updateUIFromData();
        } catch (Exception e) {
            logger.error("Update fail", e);
        }
    }

    private void removeAndUninstallDatabase() {
        if (JOptionPane.showConfirmDialog(this, "Uninstall binaries and wipe data directory?", "Confirm",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            try {
                currentProfile.uninstall();
                updateUIFromData();
            } catch (Exception e) {
                logger.error("Uninstall fail", e);
            }
        }
    }

    private static class ProgressInfo {
        final String message;
        final int progress;

        ProgressInfo(String m, int p) {
            this.message = m;
            this.progress = p;
        }
    }
}
