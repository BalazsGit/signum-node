package application.module.database.gui;
import application.utils.config.ModuleIds;

import application.module.database.api.MariaDbApiModels.MainVersionInfo;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.Constants;
import application.module.node.Signum;
import application.module.node.props.Prop;
import application.module.node.props.Props;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.utils.io.PathUtils;
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
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
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
        MARIADB("MariaDB", 3306, "mariaDb",
                "Client/server relational database (MySQL-compatible). Runs as a separate server process, locally or on a remote machine.",
                "A running server is required: host, port, user, password and database name are configured in the following steps; the server itself can be downloaded and initialized from the wizard.",
                "Use when several nodes must share one database (multi-node cluster) or the database should run on a different machine. Uses more resources than SQLite."),
        POSTGRESQL("PostgreSQL", 5432, "postgresql",
                "Client/server relational database with advanced features (JSON, full-text search, extensibility). Runs as a separate server process, locally or on a remote machine.",
                "A running server is required: host, port, user, password and database name are configured in the following steps; the server itself can be downloaded and initialized from the wizard.",
                "An alternative to MariaDB for server-based setups — e.g. when PostgreSQL is already part of the environment; multi-node clusters share one instance. Uses more resources than SQLite."),
        SQLITE("SQLite", 0, "sqlite",
                "Embedded, file-based database. The data lives in a single file that belongs to the profile — no database server is installed, started or administered.",
                "None. No server, no credentials: the database file is created for the profile automatically.",
                "Simplest and lightest setup — recommended for a single node on this machine. Not suitable when multiple nodes must share one database.");

        private final String displayName;
        private final int defaultPort;
        private final String settingsKey;
        private final String description;
        private final String setupRequirements;
        private final String usageNotes;

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

        DatabaseEngine(String displayName, int defaultPort, String settingsKey,
                String description, String setupRequirements, String usageNotes) {
            this.displayName = displayName;
            this.defaultPort = defaultPort;
            this.settingsKey = settingsKey;
            this.description = description;
            this.setupRequirements = setupRequirements;
            this.usageNotes = usageNotes;
        }

        public String getSettingsKey() {
            return settingsKey;
        }

        /** Short factual description of the engine (SSOT of the wizard's engine details). */
        public String getDescription() {
            return description;
        }

        /** What kind of setup/configuration the engine requires (SSOT of the wizard's engine details). */
        public String getSetupRequirements() {
            return setupRequirements;
        }

        /** Typical usage / who should pick this engine (SSOT of the wizard's engine details). */
        public String getUsageNotes() {
            return usageNotes;
        }

        /**
         * Classpath location of the engine's <b>official logo</b> asset
         * (see {@code resources/images/databases/} and its {@code LOGO_LICENSES.txt}).
         * SSOT of the engine branding used in the GUI.
         */
        public String getLogoResourcePath() {
            return switch (this) {
                case MARIADB -> "images/databases/mariadb.png";
                case POSTGRESQL -> "images/databases/postgresql.png";
                case SQLITE -> "images/databases/sqlite.png";
            };
        }

        /**
         * The engine's official logo, scaled to the given height (aspect ratio kept).
         * <p>
         * Falls back to the programmatically drawn {@link DatabaseEngineBadgeIcon}
         * (brand-color monogram badge) when the logo asset is missing from the
         * classpath, so the GUI never shows a broken icon.
         * </p>
         *
         * @param height the desired icon height in pixels (width is derived from the logo's ratio)
         * @return a ready-to-use {@link Icon} (never null)
         */
        public Icon getLogoIcon(int height) {
            if (height <= 0) {
                throw new IllegalArgumentException("height must be positive");
            }
            try (InputStream in = DatabaseEngine.class.getClassLoader()
                    .getResourceAsStream(getLogoResourcePath())) {
                if (in == null) {
                    return new DatabaseEngineBadgeIcon(this, height);
                }
                BufferedImage logo = ImageIO.read(in);
                if (logo == null) {
                    return new DatabaseEngineBadgeIcon(this, height);
                }
                int width = Math.max(1,
                        (int) Math.round(logo.getWidth() * (double) height / logo.getHeight()));
                Image scaled = logo.getScaledInstance(width, height, Image.SCALE_SMOOTH);
                return new ImageIcon(scaled);
            } catch (IOException e) {
                return new DatabaseEngineBadgeIcon(this, height);
            }
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

    private GlobalSettings globalSettings = new GlobalSettings(); // Refactored: GlobalSettings POJO
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
    private JButton applyProfileBtn;
    private JButton renameProfileBtn;
    private JButton deleteProfileBtn;
    private JButton newProfileBtn;
    private JButton reloadProfileBtn;
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
    private Map<String, MainVersionInfo> allVersionsMap = new HashMap<>(); // Stores all
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

        JsonObject settingsJson = DatabaseConfigurationUtils.loadGlobalSettings();
        this.globalSettings = GSON.fromJson(settingsJson, GlobalSettings.class);
        DatabaseConfigurationUtils.ensureDirectoryStructure();

        // Determine the currently applied profile name from metadata once at startup
        String lastProfile = ConfigurationUtils
                .loadAppliedProfile(ConfigurationUtils.getProfileMetadataPath(confFolder, ModuleIds.DATABASE));

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
        GuiUtils.applyDefaultTabLayoutPolicy(tabbedPane);

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
