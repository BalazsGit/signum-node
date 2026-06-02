package brs.gui.configuration.databaseConfiguration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import brs.Signum;
import brs.gui.GuiColors;
import brs.gui.GuiConstants;
import brs.gui.configuration.ConfigurationUtils;
import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationPanel.DatabaseEngine;
import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationPanel.PropertyRow;
import brs.gui.util.GuiUtils;
import brs.gui.util.CustomDrawingComponent;
import brs.gui.util.CustomDrawings;
import brs.gui.util.HelpButton;
import brs.util.PathUtils;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Comparator;

/**
 * PostgreSQL specific configuration and portable installation panel.
 */
public class PostgreSQLConfigurationPanel extends JPanel implements DatabaseEnginePanel {
    private static final Logger logger = LoggerFactory.getLogger(PostgreSQLConfigurationPanel.class);
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/zonkyio/embedded-postgres-binaries/releases";

    private Runnable restartAction;
    private String confFolder;
    private Runnable backAction;
    private JsonObject globalSettings = new JsonObject();

    private PostgresProfile currentProfile;
    private JsonObject appliedProfileSettings = new JsonObject();
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final List<PropertyRow> allPropertyRows = new ArrayList<>();
    private final Icon checkIcon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE, GuiConstants.getHelpIconSize(),
            GuiColors.getApplied());
    private final Icon errorIcon = IconFontSwing.buildIcon(FontAwesome.TIMES_CIRCLE, GuiConstants.getHelpIconSize(),
            GuiColors.getContrastRed());

    private JButton downloadDatabaseBtn, removeDatabaseBtn;
    private JComboBox<String> profileComboBox;
    private JButton startDbBtn, stopDbBtn;
    private JTextPane consoleTextPane;
    private JPanel consoleWrapper;
    private String loadedProfileName;
    private String runningProfileName = "";
    private String activeProfileName = "";
    private JComboBox<String> majorVersionCombo;
    private JComboBox<String> patchVersionCombo;
    private Map<String, List<String>> allVersionsMap = new HashMap<>();
    private String currentOsName;
    private String currentOsArch;
    private JLabel step1StatusIcon, step2StatusIcon, step3StatusIcon;
    private JLabel downloadStatusLabel, installedVersionLabel, pathLabel;
    private JPanel step2ContentPanel, step3ContentPanel, dbListPanel, userListPanel;
    private boolean isInitialized = false;

    @Override
    public String getEngineName() {
        return DatabaseEngine.POSTGRESQL.getDisplayName();
    }

    @Override
    public Path getProfilePath(String profileName) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(DatabaseEngine.POSTGRESQL.toString()).resolve(profileName);
    }

    @Override
    public void loadProfile(String profileName, JsonObject globalSettings) {
        loadProfileInternal(profileName);
    }

    @Override
    public void saveProfile(String profileName, JsonObject globalSettings) {
        if (currentProfile != null)
            try {
                currentProfile.saveToProfileJson(new HashMap<>());
            } catch (Exception e) {
            }
    }

    @Override
    public void resetToDefaults(JsonObject gs) {
        if (currentProfile != null) {
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
        return false;
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
    public void setGlobalSettings(JsonObject gs) {
        this.globalSettings = gs;
    }

    @Override
    public void setConfFolder(String f) {
        this.confFolder = f;
    }

    @Override
    public void setRestartAction(Runnable a) {
        this.restartAction = a;
    }

    @Override
    public void setBackAction(Runnable a) {
        this.backAction = a;
    }

    @Override
    public void refreshUIColors() {
    }

    public PostgreSQLConfigurationPanel() {
        super(new BorderLayout());
        this.restartAction = null;
        this.confFolder = "conf";
        this.backAction = null;
        this.currentOsName = DatabaseConfigurationUtils.getOsName();
        this.currentOsArch = DatabaseConfigurationUtils.getOsArch();
        initUI();
        this.isInitialized = true;
        updateMajorVersions();
    }

    private void initUI() {
        JPanel body = new JPanel(new BorderLayout());
        step1StatusIcon = new JLabel();
        step2StatusIcon = new JLabel();
        step3StatusIcon = new JLabel();

        JPanel profilePanel = new JPanel(new MigLayout("insets 5, gap 5"));
        profilePanel.add(new JLabel("Profile:"));
        profileComboBox = new JComboBox<>();
        ConfigurationUtils.fixComponentSize(profileComboBox);
        profilePanel.add(profileComboBox);
        JButton newBtn = new JButton("New");
        newBtn.addActionListener(e -> createNewProfile());
        profilePanel.add(newBtn);
        profileComboBox.addActionListener(e -> {
            if (isInitialized)
                loadProfileInternal((String) profileComboBox.getSelectedItem());
        });

        JPanel controlPanel = new JPanel(new MigLayout("insets 5, fillx"));
        startDbBtn = new JButton("Start", IconFontSwing.buildIcon(FontAwesome.PLAY, 14, GuiColors.getApplied()));
        stopDbBtn = new JButton("Stop", IconFontSwing.buildIcon(FontAwesome.STOP, 14, GuiColors.getContrastRed()));
        startDbBtn.addActionListener(e -> executeDbAction("Starting", currentProfile::ensureInstanceRunning));
        stopDbBtn.addActionListener(e -> executeDbAction("Stopping", currentProfile::stopInstance));
        controlPanel.add(startDbBtn);
        controlPanel.add(stopDbBtn, "wrap");

        consoleTextPane = new JTextPane();
        consoleTextPane.setEditable(false);
        consoleWrapper = new JPanel(new BorderLayout());
        consoleWrapper.setPreferredSize(new Dimension(10, 150));
        consoleWrapper.add(new JScrollPane(consoleTextPane));
        controlPanel.add(consoleWrapper, "growx, span");

        JPanel settings = new JPanel(new MigLayout("fillx, insets 10", "[][grow]"));
        addHeader(settings, "Step 1: Installation", step1StatusIcon);
        installedVersionLabel = new JLabel("None");
        settings.add(installedVersionLabel, "span, wrap");
        majorVersionCombo = new JComboBox<>();
        patchVersionCombo = new JComboBox<>();
        majorVersionCombo.addActionListener(e -> updatePatchVersions());
        settings.add(new JLabel("Version:"));
        settings.add(majorVersionCombo, "split 2");
        settings.add(patchVersionCombo, "wrap");
        downloadDatabaseBtn = new JButton("Download & Install");
        downloadDatabaseBtn.addActionListener(e -> downloadAndInstall());
        settings.add(downloadDatabaseBtn, "span, wrap");
        downloadStatusLabel = new JLabel("");
        settings.add(downloadStatusLabel, "span, wrap");

        addHeader(settings, "Step 2: Initialization", step2StatusIcon);
        step2ContentPanel = new JPanel(new MigLayout("fillx, insets 0"));
        settings.add(step2ContentPanel, "span, growx, wrap");

        addHeader(settings, "Step 3: Setup", step3StatusIcon);
        step3ContentPanel = new JPanel(new MigLayout("fillx, insets 0"));
        settings.add(step3ContentPanel, "span, growx, wrap");

        body.add(profilePanel, BorderLayout.NORTH);
        body.add(new JScrollPane(settings), BorderLayout.CENTER);
        body.add(controlPanel, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);
    }

    private void addHeader(JPanel p, String t, JLabel s) {
        JLabel l = new JLabel(t);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        p.add(l, "split 2, growx, gaptop 10");
        p.add(s, "wrap");
    }

    private void populateStep2Content(JPanel p) {
        p.add(new JLabel("Port:"), "align label");
        JTextField portF = new JTextField(currentProfile.getPort());
        valueSuppliers.put("port", portF::getText);
        p.add(portF, "growx, wrap");
        JButton initBtn = new JButton("Initialize DB");
        initBtn.addActionListener(e -> initializeDB());
        p.add(initBtn, "span, wrap");
    }

    private void populateStep3Content(JPanel p) {
        p.add(new JLabel("Admin:"));
        JTextField adminF = new JTextField(currentProfile.getAdminUsername());
        p.add(adminF, "growx, wrap");
        dbListPanel = new JPanel(new MigLayout("fillx, insets 0"));
        p.add(dbListPanel, "span, growx, wrap");
        JButton setupBtn = new JButton("Run Setup");
        setupBtn.addActionListener(e -> runSetup());
        p.add(setupBtn, "span, wrap");
    }

    private void updateMajorVersions() {
        new SwingWorker<Map<String, List<String>>, Void>() {
            @Override
            protected Map<String, List<String>> doInBackground() throws Exception {
                Map<String, List<String>> map = new HashMap<>();
                URL url = new URL(GITHUB_RELEASES_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (conn.getResponseCode() == 200) {
                    JsonArray releases = JsonParser.parseReader(new InputStreamReader(conn.getInputStream()))
                            .getAsJsonArray();
                    for (JsonElement r : releases) {
                        String tag = r.getAsJsonObject().get("tag_name").getAsString().replace("v", "");
                        String major = tag.split("\\.")[0];
                        map.computeIfAbsent(major, k -> new ArrayList<>()).add(tag);
                    }
                }
                return map;
            }

            @Override
            protected void done() {
                try {
                    allVersionsMap = get();
                    majorVersionCombo.removeAllItems();
                    allVersionsMap.keySet().stream().sorted(Comparator.reverseOrder())
                            .forEach(majorVersionCombo::addItem);
                } catch (Exception e) {
                }
            }
        }.execute();
    }

    private void updatePatchVersions() {
        patchVersionCombo.removeAllItems();
        String major = (String) majorVersionCombo.getSelectedItem();
        if (major != null && allVersionsMap.containsKey(major))
            allVersionsMap.get(major).forEach(patchVersionCombo::addItem);
    }

    private void downloadAndInstall() {
        String ver = (String) patchVersionCombo.getSelectedItem();
        if (ver == null)
            return;
        String os = currentOsName.equalsIgnoreCase("windows") ? "windows" : "linux";
        String arch = currentOsArch.equalsIgnoreCase("x64") ? "amd64" : "arm64";
        String url = String.format(
                "https://github.com/zonkyio/embedded-postgres-binaries/releases/download/v%s/embedded-postgres-binaries-%s-%s-%s.zip",
                ver, os, arch, ver);

        new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.install(url, ver, currentOsArch, (msg, p) -> publish(p));
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                downloadStatusLabel.setText("Installing... " + chunks.get(chunks.size() - 1) + "%");
            }

            @Override
            protected void done() {
                updateUIFromData();
            }
        }.execute();
    }

    private void initializeDB() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.initializeInstance((msg, p) -> appendLog(msg));
                return null;
            }

            @Override
            protected void done() {
                updateUIFromData();
            }
        }.execute();
    }

    private void runSetup() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                currentProfile.setupDatabase((msg, p) -> appendLog(msg));
                return null;
            }

            @Override
            protected void done() {
                updateUIFromData();
            }
        }.execute();
    }

    @Override
    public void updateUIFromData() {
        if (currentProfile == null)
            return;
        installedVersionLabel
                .setText(currentProfile.getInstalledVersion() != null ? currentProfile.getInstalledVersion() : "None");
        setStepStatus(step1StatusIcon, currentProfile.isStep1Completed());
        setStepStatus(step2StatusIcon, currentProfile.isStep2Completed());
        setStepStatus(step3StatusIcon, currentProfile.isStep3Completed());
        refreshStep2();
        refreshStep3();
    }

    private void refreshStep2() {
        step2ContentPanel.removeAll();
        if (currentProfile.isStep1Completed())
            populateStep2Content(step2ContentPanel);
        step2ContentPanel.revalidate();
    }

    private void refreshStep3() {
        step3ContentPanel.removeAll();
        if (currentProfile.isStep2Completed())
            populateStep3Content(step3ContentPanel);
        step3ContentPanel.revalidate();
    }

    private void loadProfileInternal(String name) {
        this.loadedProfileName = name;
        if (name == null)
            this.currentProfile = new PostgresProfile(null);
        else {
            Path json = getProfilePath(name).resolve("profile.json");
            if (Files.exists(json)) {
                try {
                    this.currentProfile = new PostgresProfile(name,
                            JsonParser.parseReader(Files.newBufferedReader(json)).getAsJsonObject());
                } catch (Exception e) {
                    this.currentProfile = new PostgresProfile(name);
                }
            } else
                this.currentProfile = new PostgresProfile(name);
        }
        updateUIFromData();
    }

    private void createNewProfile() {
        String n = JOptionPane.showInputDialog("Profile Name:");
        if (n != null && !n.isEmpty()) {
            try {
                Files.createDirectories(getProfilePath(n));
                refreshProfileList();
                profileComboBox.setSelectedItem(n);
            } catch (Exception e) {
            }
        }
    }

    private void refreshProfileList() {
        profileComboBox.removeAllItems();
        DatabaseConfigurationUtils.getProfileNames(confFolder, "PostgreSQL").forEach(profileComboBox::addItem);
    }

    private void applyProfile() {
        ConfigurationUtils.updateAppliedProfile(
                ConfigurationUtils.getProfileMetadataPath(confFolder, Signum.DATABASE_SUBFOLDER),
                "PostgreSQL:" + loadedProfileName);
    }

    private void setStepStatus(JLabel l, boolean s) {
        l.setIcon(s ? checkIcon : errorIcon);
        l.setVisible(true);
    }

    private void appendLog(String s) {
        SwingUtilities.invokeLater(() -> {
            try {
                consoleTextPane.getDocument().insertString(consoleTextPane.getDocument().getLength(), s + "\n", null);
            } catch (Exception e) {
            }
        });
    }

    private interface DbAction {
        void run(DatabaseConfigurationUtils.ProgressListener l) throws Exception;
    }

    private void executeDbAction(String n, DbAction a) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                a.run((msg, p) -> appendLog(msg));
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    updateUIFromData();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(null, e.getMessage());
                }
            }
        }.execute();
    }

    private void removeAndUninstallDatabase() {
        try {
            currentProfile.uninstall();
            updateUIFromData();
        } catch (Exception e) {
        }
    }

    private void reloadProfile() {
        loadProfileInternal(loadedProfileName);
    }

    private void updateConfigFile() {
        try {
            currentProfile.writeConfigFile();
        } catch (Exception e) {
        }
    }
}
