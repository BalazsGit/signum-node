package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.gui.configuration.JdbcConfigurationPanel;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import application.utils.io.PathUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;

/**
 * Self-contained modal wizard that creates a new <b>server database profile</b>
 * (MariaDB or PostgreSQL) — the connection form (host / port / database / user /
 * password) of a database profile that one or more node profiles can reference.
 * <p>
 * The profile lives in the database module's convention area
 * ({@code ./database/<engine>/<name>/profile.json}) and is written in the generic
 * {@code databases} format that the discovery/link SSOT
 * {@link DatabaseConfigurationUtils#loadProfile} parses for both server engines
 * (an instance carrying the JDBC URL + its user). Creation therefore never needs a
 * live server: the wizard only records the connection.
 * </p>
 * <p>
 * The connection editor is the unified {@link JdbcConfigurationPanel} (locked to the
 * selected engine, manual mode) — the same SSOT editor the node wizard and the
 * configuration tab use — so the fields and the composed URL behave exactly like
 * everywhere else. The engine is fixed by the wizard (the engine combo is disabled).
 * </p>
 * <p>
 * Creation runs through a single headless entry point
 * ({@link #createProfile(String, String, String, String)}) so the logic is
 * unit-testable without the GUI and the file write lives in exactly one place
 * (plan AD-03). The wizard is 0..N: the caller may open it repeatedly to create
 * several profiles.
 * </p>
 */
public class ServerDatabaseProfileWizard extends JDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerDatabaseProfileWizard.class);

    /** Pretty-printing writer for the profile.json (the same style the profile classes use). */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The maximum length of a new database profile name (SSOT: {@link DatabaseConfigurationUtils#isValidProfileName}). */
    private static final int MAX_NAME_LENGTH = 64;

    /** The wrap width of the dialog's body/preview text panes (selectable, wraps inside the dialog). */
    private static final int TEXT_WRAP_WIDTH = 560;

    // ------------------------------------------------------------------
    // Headless creation core (the single source of the file write)
    // ------------------------------------------------------------------

    /**
     * Validates the (name, URL, user) input of a new server database profile.
     *
     * @param rawName the profile name
     * @param rawUrl  the JDBC URL (composed by the editor from host/port/database)
     * @param rawUser the database user
     * @return {@code null} when the input is acceptable, otherwise a user-facing
     *         error message
     */
    static String validateInput(String rawName, String rawUrl, String rawUser) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return "The profile name must not be empty.";
        }
        if (!DatabaseConfigurationUtils.isValidProfileName(name)) {
            return "Invalid profile name: use letters, digits, '-' or '_', starting with a letter or '_' "
                    + "(at most " + MAX_NAME_LENGTH + " characters).";
        }
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            return "The database URL is empty — set the host, port and database name.";
        }
        if (!DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url).matches()) {
            return "The database URL is not a valid MariaDB/PostgreSQL JDBC URL.";
        }
        String user = rawUser == null ? "" : rawUser.trim();
        if (user.isEmpty()) {
            return "The database user must not be empty.";
        }
        return null;
    }

    /**
     * The engine's profile-directory name a JDBC URL points to (SSOT via
     * {@link DatabaseConfigurationUtils#JDBC_URL_PATTERN}):
     * {@code jdbc:mariadb://...} → {@code MariaDB}, {@code jdbc:postgresql://...} →
     * {@code PostgreSQL}. {@code null} when the URL is not a server JDBC URL.
     */
    static String engineForUrl(String url) {
        Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url == null ? "" : url.trim());
        if (!m.matches()) {
            return null;
        }
        String protocol = m.group(1).toLowerCase();
        if ("mariadb".equals(protocol)) {
            return DatabaseEngine.MARIADB.toString();
        }
        if ("postgresql".equals(protocol)) {
            return DatabaseEngine.POSTGRESQL.toString();
        }
        return null;
    }

    /**
     * The database (schema) name a JDBC URL points at (the last path segment, SSOT
     * via {@link DatabaseConfigurationUtils#JDBC_URL_PATTERN}); empty when the URL
     * is not a server JDBC URL or carries no database name.
     */
    static String databaseForUrl(String url) {
        Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url == null ? "" : url.trim());
        if (!m.matches()) {
            return "";
        }
        String db = m.group(4);
        return db == null ? "" : db;
    }

    /**
     * Whether a database profile with the given name already exists for the engine
     * (discovery SSOT: {@link DatabaseConfigurationUtils#getProfileNames}).
     */
    static boolean profileExists(String engine, String name) {
        if (engine == null || engine.isEmpty() || name == null || name.trim().isEmpty()) {
            return false;
        }
        return DatabaseConfigurationUtils.getProfileNames(null, engine).contains(name.trim());
    }

    /**
     * Creates (or updates) the named server database profile pointing at the URL —
     * the single file-write entry point. The {@code profile.json} uses the generic
     * {@code databases} format read by {@link DatabaseConfigurationUtils#loadProfile},
     * so the profile is discoverable and linkable by the unified editor for both
     * server engines.
     *
     * @param rawName     the profile name
     * @param rawUrl      the JDBC URL (its protocol selects the engine directory)
     * @param rawUser     the database user
     * @param rawPassword the database password (may be empty)
     * @return the normalized profile name
     * @throws IllegalArgumentException when the input fails {@link #validateInput}
     * @throws IllegalStateException    when the profile.json cannot be written
     */
    static String createProfile(String rawName, String rawUrl, String rawUser, String rawPassword) {
        String error = validateInput(rawName, rawUrl, rawUser);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        String name = rawName.trim();
        String url = rawUrl.trim();
        String engine = engineForUrl(url);
        if (engine == null) {
            throw new IllegalArgumentException("Could not determine the engine of the URL.");
        }
        String db = databaseForUrl(url);
        String user = rawUser.trim();
        String pass = rawPassword == null ? "" : rawPassword.trim();
        try {
            writeServerProfileJson(engine, name, url, db, user, pass);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write the profile for '" + name + "'", ex);
        }
        LOGGER.info("Created/updated the {} database profile '{}' -> {}", engine, name, url);
        return name;
    }

    /**
     * The single place that writes the server profile.json:
     * {@code ./database/<engine>/<name>/profile.json} in the generic
     * {@code databases} format (one instance carrying the JDBC URL + its user).
     */
    private static void writeServerProfileJson(String engine, String name, String url, String db, String user,
            String pass) throws IOException {
        Path profileDir = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(engine)
                .resolve(name);
        Files.createDirectories(profileDir);

        JsonObject userObj = new JsonObject();
        userObj.addProperty("username", user);
        userObj.addProperty("password", pass == null ? "" : pass);
        JsonArray users = new JsonArray();
        users.add(userObj);

        JsonObject instance = new JsonObject();
        instance.addProperty("name", (db == null || db.isBlank()) ? name : db);
        instance.addProperty("url", url);
        instance.add("users", users);

        JsonArray databases = new JsonArray();
        databases.add(instance);

        JsonObject root = new JsonObject();
        root.add("databases", databases);

        Files.write(profileDir.resolve("profile.json"), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    private final DatabaseEngine engine;
    private final JTextField nameField = new JTextField(24);
    private final JdbcConfigurationPanel jdbcPanel;
    private final JButton testButton = new JButton("Test connection");
    private final JLabel testStatus = new JLabel(" ");
    private final javax.swing.JEditorPane preview = WizardTexts.info("", TEXT_WRAP_WIDTH);
    private final javax.swing.JEditorPane status = WizardTexts.info(" ", TEXT_WRAP_WIDTH);

    /** The profile name created by a successful "Create" (null until then / on cancel). */
    private String createdProfileName = null;

    /**
     * @param owner  the parent window (may be {@code null} for a root-level dialog)
     * @param engine the server engine of the profile to create (SQLite is redirected to MariaDB)
     */
    public ServerDatabaseProfileWizard(Window owner, DatabaseEngine engine) {
        super(owner);
        this.engine = (engine != null && engine != DatabaseEngine.SQLITE) ? engine : DatabaseEngine.MARIADB;
        setTitle(titleFor(this.engine));
        setModal(true);

        // The unified connection editor, locked to the wizard's engine and loaded
        // with the manual-mode defaults the node wizard uses for a fresh profile.
        this.jdbcPanel = new JdbcConfigurationPanel(null, this::onJdbcChanged, (panel, state) -> {
        });
        String protocol = this.engine == DatabaseEngine.POSTGRESQL ? "postgresql" : "mariadb";
        jdbcPanel.loadManual("jdbc:" + protocol + "://localhost:" + this.engine.getDefaultPort() + "/signum",
                "signum", "");
        lockEngine();

        buildUi();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });
    }

    /** The window title for the given engine (shared with the tests). */
    public static String titleFor(DatabaseEngine engine) {
        return "New " + engine.getDisplayName() + " Database Profile";
    }

    private void buildUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(18, 22, 14, 22));

        // Header: the engine's official logo + the bold title.
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        header.setOpaque(false);
        header.add(new JLabel(engine.getLogoIcon(GuiConstants.ICON_SIZE_DIALOG)));
        header.add(buildTitleLabel());
        content.add(header);
        content.add(Box.createVerticalStrut(10));

        // Description (selectable, HTML-wrapped so it wraps within the dialog).
        javax.swing.JEditorPane description = WizardTexts.info(
                "Create a new " + engine.getDisplayName()
                        + " database profile. It stores the server connection (host, port, database and user)"
                        + " and is kept in the database module's convention area (./database/"
                        + engine.getDisplayName() + "/&lt;name&gt;/). One or more node profiles can reference it.",
                TEXT_WRAP_WIDTH);
        description.setAlignmentX(0f);
        content.add(description);
        content.add(Box.createVerticalStrut(14));

        // The profile name + the unified connection editor.
        content.add(buildNameForm());
        content.add(Box.createVerticalStrut(8));
        content.add(jdbcPanel);
        content.add(Box.createVerticalStrut(8));

        // Test connection (server engines only — a plain reachability check, no SQL).
        testButton.setFocusable(false);
        testButton.addActionListener(e -> testConnection());
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        testRow.setOpaque(false);
        testRow.setAlignmentX(0f);
        testRow.add(testButton);
        testRow.add(testStatus);
        content.add(testRow);
        content.add(Box.createVerticalStrut(10));

        // The live preview: the profile directory + the composed JDBC URL.
        preview.setAlignmentX(0f);
        content.add(preview);
        content.add(Box.createVerticalStrut(6));

        // The live validation / status line.
        status.setAlignmentX(0f);
        content.add(status);
        content.add(Box.createVerticalStrut(16));

        // The action row.
        content.add(buildButtons());

        setContentPane(content);
        GuiFontManager.applyFontToTree(this, UIManager.getFont("Label.font"));
        pack();
        setLocationRelativeTo(getOwner());

        installListeners();
        updatePreviewAndStatus();
    }

    private JLabel buildTitleLabel() {
        JLabel title = new JLabel(titleFor(engine));
        title.setFont(GuiFontManager.getBoldScaledDefaultFont(WizardStep.KEYWORD_FONT_SCALE));
        return title;
    }

    private JPanel buildNameForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        ConfigurationUtils.styleInputComponent(nameField);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        form.add(formLabel("Profile name:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(nameField, c);
        return form;
    }

    private static JLabel formLabel(String text) {
        JLabel l = new JLabel(text);
        GuiFontManager.applyDefaultFont(l);
        return l;
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton create = new JButton("Create");
        JButton cancel = new JButton("Cancel");
        create.setFocusable(false);
        cancel.setFocusable(false);
        create.addActionListener(e -> onCreate());
        cancel.addActionListener(e -> onCancel());
        buttons.add(create);
        buttons.add(cancel);
        getRootPane().setDefaultButton(create);
        return buttons;
    }

    /** Disables the editor's engine combo — the profile engine is fixed by this wizard. */
    private void lockEngine() {
        JComponent combo = jdbcPanel.getEngineComboComponent();
        if (combo instanceof JComboBox<?> box) {
            box.setEnabled(false);
            box.setToolTipText("The profile engine is fixed by this wizard.");
        }
    }

    private void installListeners() {
        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updatePreviewAndStatus();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updatePreviewAndStatus();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updatePreviewAndStatus();
            }
        });
    }

    /** The editor's change callback (fires after every effective field/URL change). */
    private void onJdbcChanged() {
        updatePreviewAndStatus();
    }

    /**
     * Refreshes the live preview (profile directory + composed URL) and the status
     * line (validation error / "already exists" note) from the current field values.
     */
    private void updatePreviewAndStatus() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String url = jdbcPanel.getJdbcUrl();
        String engineName = engineForUrl(url);

        if (engineName != null) {
            String displayDir = DatabaseConfigurationUtils.DATABASE_BASE_DIR
                    + "/" + engineName + "/" + (name.isEmpty() ? "<name>" : name);
            preview.setText(WizardTexts.wrap(
                    "Profile directory: <b>" + WizardTexts.escape(displayDir) + "</b><br>"
                            + "Database URL: " + WizardTexts.escape(url)));
        } else {
            preview.setText(WizardTexts.wrap(" "));
        }

        if (name.isEmpty()) {
            status.setForeground(GuiColors.getFaintText());
            status.setText(WizardTexts.wrap("Enter a profile name to continue."));
        } else {
            String error = validateInput(name, url, jdbcPanel.getUsername());
            if (error != null) {
                status.setForeground(GuiColors.getContrastRed());
                status.setText(WizardTexts.wrap(WizardTexts.escape(error)));
            } else if (engineName != null && profileExists(engineName, name)) {
                status.setForeground(GuiColors.getUnsaved());
                status.setText(WizardTexts.wrap(WizardTexts.escape(
                        "Profile '" + name + "' already exists — creating will update its connection.")));
            } else {
                status.setForeground(GuiColors.getFaintText());
                status.setText(WizardTexts.wrap("Ready to create."));
            }
        }
        preview.revalidate();
        status.revalidate();
    }

    /**
     * Optional reachability check: a plain TCP connect to the URL's host/port (a
     * background thread, so the dialog stays responsive). It never blocks creation.
     */
    private void testConnection() {
        String url = jdbcPanel.getJdbcUrl();
        Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url == null ? "" : url.trim());
        if (!m.matches()) {
            testStatus.setForeground(GuiColors.getContrastRed());
            testStatus.setText("No valid host/port to test.");
            return;
        }
        String host = m.group(2);
        int port;
        try {
            port = m.group(3) != null ? Integer.parseInt(m.group(3)) : engine.getDefaultPort();
        } catch (NumberFormatException ex) {
            testStatus.setForeground(GuiColors.getContrastRed());
            testStatus.setText("Invalid port: " + m.group(3));
            return;
        }
        testStatus.setForeground(GuiColors.getFaintText());
        testStatus.setText("Testing " + host + ":" + port + " ...");
        testButton.setEnabled(false);
        new Thread(() -> {
            final String[] outcome = new String[2]; // [0] = "ok" | "error", [1] = detail
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2500);
                outcome[0] = "ok";
                outcome[1] = "";
            } catch (IOException ex) {
                outcome[0] = "error";
                outcome[1] = ex.getMessage() == null ? "unreachable" : ex.getMessage();
            }
            SwingUtilities.invokeLater(() -> {
                testButton.setEnabled(true);
                if ("ok".equals(outcome[0])) {
                    testStatus.setForeground(GuiColors.getStatusConsistent());
                    testStatus.setText("Reachable: " + host + ":" + port);
                } else {
                    testStatus.setForeground(GuiColors.getContrastRed());
                    testStatus.setText("Unreachable (" + outcome[1] + ")");
                }
            });
        }, "server-db-profile-wizard-test").start();
    }

    private void onCreate() {
        String name = nameField.getText();
        String url = jdbcPanel.getJdbcUrl();
        String user = jdbcPanel.getUsername();
        String pass = jdbcPanel.getPassword();
        if (validateInput(name, url, user) != null) {
            updatePreviewAndStatus();
            return;
        }
        try {
            createdProfileName = createProfile(name, url, user, pass);
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not create the {} database profile '{}'", engine, name, ex);
            status.setForeground(GuiColors.getContrastRed());
            status.setText(WizardTexts.wrap(WizardTexts.escape("Could not create the profile: " + ex.getMessage())));
            status.revalidate();
            return;
        }
        dispose();
    }

    private void onCancel() {
        createdProfileName = null;
        dispose();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** @return the name of the profile created by "Create", or {@code null} when cancelled. */
    public String getCreatedProfileName() {
        return createdProfileName;
    }

    /** Shows the (already constructed) wizard modally. Must run on the event dispatch thread. */
    public void showAndWait() {
        setVisible(true);
    }

    /**
     * Shows the wizard modally and returns the created profile name (or {@code null}
     * when the user cancelled). Marshals to the event dispatch thread when called
     * from another thread, so hosts may call it from any thread.
     *
     * @param parent the parent component (its owner window is used for modality; may
     *               be {@code null} for a root-level dialog)
     * @param engine the server engine of the profile to create
     * @return the created profile name, or {@code null} when cancelled
     */
    public static String show(Component parent, DatabaseEngine engine) {
        return show(parent, engine, null);
    }

    /**
     * Shows the wizard modally, prefilling the profile name field with the given
     * suggested name ({@code null}/blank: no prefill), and returns the created
     * profile name (or {@code null} when the user cancelled). Marshals to the event
     * dispatch thread when called from another thread, so hosts may call it from any
     * thread.
     *
     * @param parent          the parent component (its owner window is used for
     *                        modality; may be {@code null} for a root-level dialog)
     * @param engine          the server engine of the profile to create
     * @param suggestedName   the profile name to prefill, or {@code null}
     * @return the created profile name, or {@code null} when cancelled
     */
    public static String show(Component parent, DatabaseEngine engine, String suggestedName) {
        Window owner = ownerOf(parent);
        final String[] result = new String[1];
        Runnable task = () -> {
            ServerDatabaseProfileWizard wizard = new ServerDatabaseProfileWizard(owner, engine);
            if (suggestedName != null && !suggestedName.isBlank()) {
                wizard.getNameField().setText(suggestedName.trim());
            }
            wizard.setVisible(true); // modal: blocks until the user finishes
            result[0] = wizard.getCreatedProfileName();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                LOGGER.warn("The server database profile wizard failed", ex);
            }
        }
        return result[0];
    }

    /** The modal owner window for the parent component (walking up past nested dialogs). */
    private static Window ownerOf(Component parent) {
        if (parent == null) {
            return null;
        }
        Window owner = SwingUtilities.windowForComponent(parent);
        while (owner instanceof JDialog) {
            Window next = ((JDialog) owner).getOwner();
            if (next == null) {
                break;
            }
            owner = next;
        }
        return owner;
    }

    // ------------------------------------------------------------------
    // Test accessors (package-private: the GUI regression tests locate the fields)
    // ------------------------------------------------------------------

    JTextField getNameField() {
        return nameField;
    }

    JdbcConfigurationPanel getJdbcPanel() {
        return jdbcPanel;
    }

    Component getRootPanel() {
        return getContentPane();
    }
}