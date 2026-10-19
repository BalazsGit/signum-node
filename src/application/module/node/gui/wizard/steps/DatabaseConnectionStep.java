package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.configuration.JdbcConfigurationPanel;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.ProfileNameSuggester;
import application.module.node.profile.SqliteDatabasePaths;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Wizard step 2 — database settings for the node profile.
 * <p>
 * The step embeds the <b>same</b> unified JDBC {@code DB.Url} editor the node
 * profile configuration panel's DB.Url row uses ({@link JdbcConfigurationPanel}
 * — one engine- and link-mode-aware panel for every engine: the identical
 * structure, URL-composition logic, value formats and the selectable,
 * borderless JDBC URL preview as the configuration tab) together with the
 * configuration tab's "Linked Database Profile" checkbox. The engine itself is
 * fixed by the selection step — the editor's engine combo stays locked to it
 * (change it by going back to step 1).
 * </p>
 * <p>
 * Above the editor the step shows the engine's logo + name and the
 * engine-specific description (HTML-wrapped so the full text stays visible
 * within the panel width) — the engine decision made in the selection step
 * stays visible on this step.
 * </p>
 * <p>
 * Server engines keep the optional TCP reachability check (Test connection).
 * The values are persisted into the node profile via the
 * {@link ProfileCreateDefaults} SSOT at finish time (see
 * {@link application.module.node.gui.wizard.WizardFinish}). For SQLite an
 * untouched setup (the convention base path + the default file, no link) keeps
 * the per-profile SSOT URL derived at finish from the final profile name; an
 * edited or linked setup persists the composed URL (the companion database
 * profile is named after the URL's convention folder).
 * </p>
 */
public class DatabaseConnectionStep implements WizardStep {

    /** The height of the engine logo in the step's top header. */
    private static final int HEADER_LOGO_SIZE = 28;

    private final JPanel panel = new JPanel();
    private final JLabel engineIconLabel = new JLabel();
    private final JLabel engineNameLabel = new JLabel();
    private final JLabel descriptionLabel = new JLabel();
    private final JCheckBox linkedCheck = new JCheckBox("Linked Database Profile");
    private final JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JLabel statusLabel = new JLabel(" ");
    /** Guard for the checkbox syncs the panel initiates (no re-entrant requestLinkMode). */
    private boolean programmaticLinkChange = false;
    /** The engine the editor was last initialized for (null before the first entry). */
    private DatabaseEngine initializedFor = null;

    /**
     * The unified JDBC db.url editor — the SAME component the node profile
     * configuration panel's DB.Url row uses (SSOT structure + composition
     * logic + preview format).
     */
    private final JdbcConfigurationPanel jdbcPanel;

    public DatabaseConnectionStep() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // The engine header: the decision made in the selection step — the
        // engine's official logo next to its bold (keyword-sized) name.
        JPanel headerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        headerRow.setOpaque(false);
        headerRow.setAlignmentX(0f);
        engineNameLabel.setFont(GuiFontManager.getBoldScaledDefaultFont(KEYWORD_FONT_SCALE));
        headerRow.add(engineIconLabel);
        headerRow.add(engineNameLabel);
        panel.add(headerRow);

        // The engine-specific description — HTML-wrapped so the FULL text stays
        // visible within the panel width (a plain single-line label overflows
        // the panel and gets clipped, which made the old hint "not visible").
        GuiFontManager.applyDefaultFont(descriptionLabel);
        descriptionLabel.setHorizontalAlignment(SwingConstants.LEFT);
        descriptionLabel.setAlignmentX(0f);
        panel.add(descriptionLabel);

        panel.add(Box.createVerticalStrut(6));

        // The "Linked Database Profile" checkbox — the same control the
        // configuration tab's DB.Url row uses to drive the editor's link mode.
        linkedCheck.setOpaque(false);
        linkedCheck.setAlignmentX(0f);
        panel.add(linkedCheck);

        jdbcPanel = new JdbcConfigurationPanel(null, null, this::syncLinkedCheck);
        jdbcPanel.setAlignmentX(0f);
        panel.add(jdbcPanel);

        // Test connection (server engines only — SQLite is file-based).
        JButton testButton = new JButton("Test connection");
        testButton.setFocusable(false);
        testButton.addActionListener(e -> testConnection());
        GuiFontManager.applyDefaultFont(statusLabel);
        testRow.setOpaque(false);
        testRow.setAlignmentX(0f);
        testRow.add(testButton);
        testRow.add(statusLabel);
        panel.add(testRow);

        linkedCheck.addActionListener(e -> {
            if (programmaticLinkChange) {
                return;
            }
            jdbcPanel.requestLinkMode(linkedCheck.isSelected());
        });
    }

    /**
     * The manual-mode SQLite URL of the untouched setup: the convention base
     * path + the default database file (SSOT: {@link SqliteDatabasePaths} +
     * {@link ProfileCreateDefaults#DEFAULT_SQLITE_FILE_NAME}).
     */
    private String defaultSqliteUrl() {
        return SqliteDatabasePaths.composeUrl(SqliteDatabasePaths.conventionBase(),
                ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME);
    }

    /** The JDBC URL field's text (the unified panel's accessors return {@link JComponent}s). */
    private static String text(JComponent field) {
        return field instanceof JTextField textField ? textField.getText().trim() : "";
    }

    /** The server database host the editor's Host field holds. */
    public String getHost() {
        return text(jdbcPanel.getHostField());
    }

    /** The server database port the editor's Port field holds. */
    public String getPortText() {
        return text(jdbcPanel.getPortField());
    }

    /** The server database name the editor's Database field holds. */
    public String getDbName() {
        return text(jdbcPanel.getDatabaseField());
    }

    private int parsePort() {
        try {
            int p = Integer.parseInt(getPortText());
            if (p < 1 || p > 65535) {
                throw new NumberFormatException();
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + getPortText());
        }
    }

    /** Optional TCP reachability check of the server engine's host:port. */
    private void testConnection() {
        String host = getHost();
        int port;
        try {
            port = parsePort();
        } catch (IllegalArgumentException e) {
            statusLabel.setText("Invalid port.");
            return;
        }
        statusLabel.setText("Testing " + host + ":" + port + " ...");
        new Thread(() -> {
            boolean ok;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2500);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean result = ok;
            SwingUtilities.invokeLater(() -> statusLabel.setText(
                    result ? "Reachable — " + host + ":" + port
                            : "Not reachable: " + host + ":" + port + " (is the server running?)"));
        }, "wizard-db-test").start();
    }

    /** Keeps the "Linked Database Profile" checkbox in sync with the panel's link mode. */
    private void syncLinkedCheck(JdbcConfigurationPanel p, JdbcConfigurationPanel.JdbcState state) {
        if (linkedCheck.isSelected() != p.isLinkedMode()) {
            programmaticLinkChange = true;
            linkedCheck.setSelected(p.isLinkedMode());
            programmaticLinkChange = false;
        }
    }

    /**
     * Locks the editor's engine combo to the selection step's engine. The
     * programmatic {@code loadManual} of {@link #onEnter(WizardContext)}
     * already left the combo on this engine — re-asserting the SAME selection
     * would be dispatched as a user engine switch (the editor snapshots the
     * outgoing state and restores it in a deferred task that can race user
     * edits), so the combo is (re-)selected only when it actually differs and
     * then disabled.
     */
    private void lockEngine(DatabaseEngine engine) {
        JComponent combo = jdbcPanel.getEngineComboComponent();
        if (jdbcPanel.getEngine() != engine && combo instanceof JComboBox<?> select) {
            select.setSelectedItem(engine);
        }
        combo.setEnabled(false);
    }

    /**
     * Prefills the editor's Profile selection with the node profile's own
     * name — the database profile the "Linked Database Profile" link is meant
     * for (SSOT: the node-configuration panel's auto-link uses the node
     * profile name as the database profile name). The name is the link's
     * target, not necessarily a discovered profile yet, so it is made
     * selectable first: the manual mode reads the model selection (the editor
     * is only live in linked mode), and a later link toggle re-derives the
     * link from this selection. Before step 1 resolves a name, the standard
     * base name applies.
     */
    private void prefillProfile(WizardContext context) {
        String name = context.getName();
        if (name == null || name.isEmpty()) {
            name = ProfileNameSuggester.baseName(false, null, false, 0);
        }
        JComponent profile = jdbcPanel.getProfileComboComponent();
        if (profile instanceof JComboBox<?> combo) {
            if (!comboContains(combo, name)) {
                @SuppressWarnings("unchecked")
                JComboBox<Object> box = (JComboBox<Object>) combo;
                box.addItem(name);
            }
            combo.setSelectedItem(name);
        }
    }

    private static boolean comboContains(JComboBox<?> combo, Object item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (item.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getId() {
        return "database-connection";
    }

    @Override
    public String getTitle() {
        return "Database configuration";
    }

    @Override
    public FontAwesome getHeaderIcon() {
        return FontAwesome.DATABASE;
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        // File-based engine: no server connection to validate — the editor's
        // values are always a well-formed URL (composed from its own fields).
        if (context.getEngine() == DatabaseEngine.SQLITE) {
            return null;
        }
        if (getHost().isEmpty()) {
            return "Database host is required.";
        }
        try {
            parsePort();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return null;
    }

    @Override
    public void onExit(WizardContext context) {
        if (context.getEngine() == DatabaseEngine.SQLITE) {
            // Collect the unified editor: an untouched setup (the convention
            // base path + the default file, no link) keeps the per-profile
            // SSOT URL (derived at finish from the final profile name); an
            // edited or linked setup persists the composed URL. The companion
            // database profile is named after the URL's convention folder
            // (null for a non-convention path — the node profile name applies).
            String url = jdbcPanel.getJdbcUrl();
            boolean untouched = !jdbcPanel.isLinkedMode() && defaultSqliteUrl().equals(url);
            if (untouched) {
                context.setSqliteDbUrl(null);
                context.setSqliteDbProfileName(null);
            } else {
                context.setSqliteDbUrl(url);
                SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
                context.setSqliteDbProfileName(parts == null ? null
                        : SqliteDatabasePaths.deriveProfileName(parts.dir()));
            }
            return;
        }
        context.setSkipDbSetup(false);
        context.setDbHost(getHost());
        context.setDbPort(parsePort());
        context.setDbUser(jdbcPanel.getUsername());
        context.setDbPassword(jdbcPanel.getPassword());
        context.setDbName(getDbName());
    }

    @Override
    public void onEnter(WizardContext context) {
        DatabaseEngine engine = context.getEngine() == null ? DatabaseEngine.MARIADB : context.getEngine();
        boolean sqlite = engine == DatabaseEngine.SQLITE;

        // The engine decision made in the selection step, visible on this step:
        // the official logo + the bold engine name + the full (wrapped)
        // engine-specific description.
        engineIconLabel.setIcon(engine.getLogoIcon(HEADER_LOGO_SIZE));
        engineNameLabel.setText(engine.getDisplayName());
        descriptionLabel.setText("<html>" + (sqlite
                ? "SQLite is file-based — no server connection is required. The database file "
                        + "(DB.Url) is kept in the profile's own data directory; leave the "
                        + "defaults for the per-profile database or adjust the location as needed."
                : engine.getDisplayName() + " — connection settings used by the node profile.")
                + "</html>");

        testRow.setVisible(!sqlite);

        // Initialize the editor for a (new) engine once — the manual-mode
        // defaults the configuration tab shows for a fresh profile (entering
        // the step again with the same engine keeps the user's edits).
        if (initializedFor != engine) {
            if (sqlite) {
                jdbcPanel.loadManual(defaultSqliteUrl(), "", "");
                prefillProfile(context);
            } else {
                String protocol = engine == DatabaseEngine.POSTGRESQL ? "postgresql" : "mariadb";
                jdbcPanel.loadManual("jdbc:" + protocol + "://localhost:"
                        + engine.getDefaultPort() + "/signum", "signum", "");
            }
            initializedFor = engine;
        }
        lockEngine(engine);
    }

    @Override
    public boolean autoSkip(WizardContext context) {
        return false; // shown for every engine — for SQLite it is the DB-configuration step
    }
}

