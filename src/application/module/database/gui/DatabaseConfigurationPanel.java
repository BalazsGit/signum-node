package application.module.database.gui;

import application.module.database.databaseConfiguration.DatabaseConfigurationUtils;
import application.module.node.Constants;
import application.module.node.Signum;
import application.module.node.props.Prop;
import application.module.node.props.Props;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.io.PathUtils;
import application.module.node.gui.configuration.ConfigurationUtils;
import application.module.node.gui.configuration.LoggerConfigurationPanel;
import application.module.node.gui.configuration.NodeConfigurationPanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DatabaseConfigurationPanel extends JPanel {

    public enum DatabaseEngine {
        MARIADB("MariaDB", 3306, "mariaDb"),
        POSTGRESQL("PostgreSQL", 5432, "postgresql"),
        SQLITE("SQLite", 0, "sqlite");

        private final String displayName;
        private final int defaultPort;
        private final String settingsKey;

        @Override
        public String toString() {
            return displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getDefaultPort() {
            return defaultPort;
        }

        DatabaseEngine(String displayName, int defaultPort, String settingsKey) {
            this.displayName = displayName;
            this.defaultPort = defaultPort;
            this.settingsKey = settingsKey;
        }

        public String getSettingsKey() {
            return settingsKey;
        }

        public static DatabaseEngine fromDisplayName(String displayName) {
            for (DatabaseEngine engine : values()) {
                if (engine.displayName.equalsIgnoreCase(displayName)) {
                    return engine;
                }
            }
            return null;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfigurationPanel.class);
    private final String confFolder;

    private JsonObject globalSettings = new JsonObject(); // New: For settings.json
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonObject profileSettings = new JsonObject();
    private JsonObject appliedProfileSettings = new JsonObject();
    private final Map<String, Supplier<String>> valueSuppliers = new HashMap<>();
    private final Map<String, JComponent> propertyComponents = new HashMap<>();
    private final Map<String, String> helpTexts = new HashMap<>();
    private final Map<String, String> defaultValues = new HashMap<>();
    private Path activeProfilePath;
    private final Icon checkIcon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE,
            GuiConstants.getHelpIconSize(),
            GuiColors.getApplied());
    private final Icon errorIcon = IconFontSwing.buildIcon(FontAwesome.TIMES_CIRCLE, GuiConstants.getHelpIconSize(),
            GuiColors.getContrastRed());
    private JComboBox<String> versionComboBox; // New: For portable versions
    private JButton downloadDatabaseBtn; // New: For download button
    private JComboBox<DatabaseEngine> engineComboBox;
    private JComboBox<String> profileComboBox;
    private final List<PropertyRow> allPropertyRows = new ArrayList<>();
    private JPanel searchResultsPanel;
    private CardLayout contentCardLayout;
    private JButton downloadBtn;
    private JButton saveProfileBtn;
    private JButton applyProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private JButton newProfileBtn;
    private JButton reloadProfileBtn;
    private JButton resetToDefaultsBtn;
    private JButton refreshProfilesBtn;
    private JPanel contentContainer;
    private JComponent verticalFiller;
    private String runningProfileName;
    private String activeProfileName;
    private String loadedProfileName;
    private DatabaseEngine currentEngine = DatabaseEngine.MARIADB;
    private JLabel step1StatusIcon; // Status icon for download/install step
    private JComboBox<String> mainVersionComboBox; // New: For main release versions
    private JComboBox<String> subVersionComboBox; // Renamed/Refactored from versionComboBox
    private Map<String, MariaDBConfigurationPanel.MainVersionInfo> allVersionsMap = new HashMap<>(); // Stores all
                                                                                                     // fetched
    // version data for the
    // current engine
    private String currentOsName;
    private JLabel step2StatusIcon; // New: Status icon for setup step
    private JLabel downloadStatusLabel; // New: For download status
    private JLabel pathLabel;
    private String currentOsArch; // Missing field added
    private JTabbedPane tabbedPane;

    public DatabaseConfigurationPanel() {
        super(new BorderLayout());

        this.confFolder = Signum.CONF_FOLDER;

        this.currentOsName = DatabaseConfigurationUtils.getOsName();
        this.currentOsArch = DatabaseConfigurationUtils.getOsArch();

        this.globalSettings = DatabaseConfigurationUtils.loadGlobalSettings();
        DatabaseConfigurationUtils.ensureDirectoryStructure();

        // Determine the currently applied profile name from metadata once at startup
        String lastProfile = ConfigurationUtils
                .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, "database"));

        // Format in metadata for DB is "Engine:ProfileName"
        String lastEngine = "MariaDB";
        String lastProfileName = "default";

        if (lastProfile != null && lastProfile.contains(":")) {
            String[] parts = lastProfile.split(":");
            lastEngine = parts[0];
            lastProfileName = parts[1];
        }

        this.currentEngine = DatabaseEngine.fromDisplayName(lastEngine);
        if (this.currentEngine == null)
            this.currentEngine = DatabaseEngine.MARIADB;

        this.runningProfileName = lastProfileName;
        this.activeProfileName = this.runningProfileName;
        this.loadedProfileName = this.runningProfileName;

        this.activeProfilePath = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(this.currentEngine.toString())
                .resolve(this.loadedProfileName);

        tabbedPane = new JTabbedPane();

        // Initialize sub-panels
        // Null is passed for the internal 'switch' actions as they are now handled by
        // tabs.
        SQLiteConfigurationPanel sqliteConfig = new SQLiteConfigurationPanel();
        PostgreSQLConfigurationPanel postgresqlConfig = new PostgreSQLConfigurationPanel();
        MariaDBConfigurationPanel mariadbConfig = new MariaDBConfigurationPanel();

        tabbedPane.addTab("SQLite", sqliteConfig);
        tabbedPane.addTab("PostgreSQL", postgresqlConfig);
        tabbedPane.addTab("MariaDB", mariadbConfig);

        add(tabbedPane, BorderLayout.CENTER);
    }

    static class PropertyRow {
        final String propertyKey;
        final String labelText;
        final JPanel originalParent;
        JLabel label;
        String labelConstraints;
        JComponent input;
        String inputConstraints;
        JComponent extra;
        String extraConstraints;
        JButton help;
        String helpConstraints;
        JSeparator separator;
        String separatorConstraints;

        PropertyRow(String propertyKey, String labelText, JPanel originalParent) {
            this.propertyKey = propertyKey;
            this.labelText = labelText;
            this.originalParent = originalParent;
        }
    }
}
