package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.configuration.JdbcConfigurationPanel;
import application.module.node.gui.configuration.LinkedDbModeSelector;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.gui.wizard.WizardTexts;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.ProfileNameSuggester;
import application.module.node.profile.SqliteDatabasePaths;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.HelpButton;
import application.utils.gui.ProfileActionIcon;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.BooleanSupplier;

/**
 * Wizard step 3 — database settings for the node profile.
 * <p>
 * The step embeds the <b>same</b> unified JDBC {@code DB.Url} editor the node
 * profile configuration panel's DB.Url row uses ({@link JdbcConfigurationPanel}
 * — one engine- and link-mode-aware panel for every engine: the identical
 * structure, URL-composition logic, value formats and the selectable,
 * borderless JDBC URL preview as the configuration tab) together with the
 * configuration tab's database-URL source selector (the "Linked Database
 * Profile" / "Custom db.URL" radio buttons). The engine itself is
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
    private static final int HEADER_LOGO_SIZE = 36;

    private final JPanel panel = new JPanel();
    private final JLabel engineIconLabel = new JLabel();
    private final javax.swing.JEditorPane engineNameLabel = WizardTexts.heading("");
    private final javax.swing.JEditorPane descriptionLabel = WizardTexts.info("");
    private final LinkedDbModeSelector linkedModeSelector = new LinkedDbModeSelector();
    private final JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final javax.swing.JEditorPane statusLabel = WizardTexts.info(" ");
    /** The untouched SQLite setup's prefill URL (the per-profile SSOT URL of the node profile name). */
    private String sqlitePrefillUrl = null;
    /** Guard for the checkbox syncs the panel initiates (no re-entrant requestLinkMode). */
    private boolean programmaticLinkChange = false;
    /**
     * Test hook replacing the exit-confirmation dialog (null → the real
     * OK/Cancel dialog is shown). Lets the GUI regression tests walk the
     * wizard headlessly.
     */
    private BooleanSupplier exitConfirmationHook = null;
    /** The engine the editor was last initialized for (null before the first entry). */
    private DatabaseEngine initializedFor = null;

    // The live navigation context (bound by the controller) + the database-profile
    // wizard launcher (injectable — headless-safe). SSOT:
    // DatabaseSelectionStep.defaultProfileWizardLauncher (the same engine → wizard
    // mapping as the node-configuration step).
    private WizardContext boundContext = null;
    private DatabaseSelectionStep.DatabaseProfileWizardLauncher profileWizardLauncher =
            DatabaseSelectionStep::defaultProfileWizardLauncher;
    /** The discreet "+" button that opens the database-profile wizard (prefilled with the
     * suggested profile name) and links the created profile. */
    private final JButton createProfileBtn = new JButton();

    /**
     * The unified JDBC db.url editor — the SAME component the node profile
     * configuration panel's DB.Url row uses (SSOT structure + composition
     * logic + preview format).
     */
    private final JdbcConfigurationPanel jdbcPanel;

    public DatabaseConnectionStep() {
        // Top-anchored root: the dialog stretches every step to the full height — a
        // bare BoxLayout would hand that extra space to the stretchable text panes
        // (their max height is unbounded), inflating each and opening visible gaps
        // between the rows. The content sits at the NORTH so the surplus height drops
        // to the bottom (the same structure as the other wizard steps).
        panel.setLayout(new java.awt.BorderLayout());
        panel.setOpaque(false);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // The engine header: the decision made in the selection step — the
        // official logo at the very top, a small gap, then the rest of the text
        // (the bold keyword-sized engine name + the engine-specific
        // description, both selectable display panes).
        engineIconLabel.setAlignmentX(0f);
        content.add(engineIconLabel);
        content.add(Box.createVerticalStrut(4));
        engineNameLabel.setAlignmentX(0f);
        content.add(engineNameLabel);

        // A normal empty line between the engine title and the description.
        javax.swing.JEditorPane nameGap = WizardTexts.info("");
        nameGap.setAlignmentX(0f);
        content.add(nameGap);

        // The engine-specific description — selectable, HTML-wrapped so the FULL
        // text stays visible within the panel width (a plain single-line label
        // overflows the panel and gets clipped, which made the old hint "not visible").
        descriptionLabel.setAlignmentX(0f);
        content.add(descriptionLabel);

        // A normal empty line between the description and the database-URL source
        // selector — the SAME size as the one between the engine name and the
        // description (the two gaps must read as equal, not a 4px sliver).
        javax.swing.JEditorPane selectorGap = WizardTexts.info("");
        selectorGap.setAlignmentX(0f);
        content.add(selectorGap);

        // The database-URL source selector (two stacked radio buttons:
        // "Linked Database Profile" / "Custom db.URL") — the same control the
        // configuration tab's DB.Url row uses to drive the editor's link mode —
        // followed by the "+" create-profile button and the "?" info button
        // (the same HelpButton + info dialog pattern the node configuration
        // menu's property rows use) explaining the link mode.
        // GridBag keeps the radios flush with the content's left edge; the weighted
        // trailing column absorbs the row's extra width so GridBag does not center
        // the controls when the row is stretched by BoxLayout.
        JPanel linkedRow = new JPanel(new GridBagLayout());
        linkedRow.setOpaque(false);
        // BoxLayout alignment: pin the row to the left edge like every other row
        // (a bare non-BoxLayout panel defaults to alignmentX = 0.5f and would
        // float to the CENTER of the full-width content).
        linkedRow.setAlignmentX(0f);
        // The row holds the two-radio selector (the tall component) plus the
        // trailing buttons — top-anchored so the "+" / "?" sit on the FIRST
        // radio's row (GridBag's default anchor would float them to the
        // stack's vertical center, between the two radios).
        GridBagConstraints linkedC = new GridBagConstraints();
        linkedC.anchor = GridBagConstraints.FIRST_LINE_START;
        linkedC.gridx = 0;
        linkedC.insets = new Insets(0, 0, 0, 8);
        linkedRow.add(linkedModeSelector, linkedC);
        // The discreet "+" — the profile-toolbar flat icon-button style (the same
        // look as the profile panel's New Profile button): opens the engine's
        // database-profile wizard (prefilled with this node's suggested profile
        // name) and links the created profile to this node. It sits BEFORE the
        // "?" info button (creating a new profile is the linked mode's way to
        // gain an entry in the existing-profiles dropdown). The glyph is the
        // shared FontAwesome plus (ProfileActionIcon.NEW_PROFILE) — the same plus the
        // profile toolbar's New Profile button and the node/profiles tab strip's
        // trailing "+" use.
        createProfileBtn.setFocusable(false);
        createProfileBtn.setToolTipText(ConfigurationUtils.profileTooltip(
                "Create a new database profile for this engine with the database-profile "
                        + "wizard (prefilled with this node's suggested profile name), and "
                        + "use it as this node's database."));
        ConfigurationUtils.styleNewProfileButton(createProfileBtn);
        createProfileBtn.addActionListener(e -> onCreateProfileClick());
        linkedC.gridx = 1;
        linkedRow.add(createProfileBtn, linkedC);
        HelpButton linkedHelpBtn = new HelpButton();
        linkedHelpBtn.setPreferredSize(createProfileBtn.getPreferredSize());
        linkedHelpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        linkedHelpBtn.setToolTipText("Click for more info");
        linkedHelpBtn.addActionListener(e -> showLinkedProfileHelp());
        linkedC.gridx = 2;
        linkedC.insets = new Insets(0, 0, 0, 0);
        linkedRow.add(linkedHelpBtn, linkedC);
        linkedC.gridx = 3;
        linkedC.weightx = 1;
        linkedC.fill = GridBagConstraints.HORIZONTAL;
        linkedRow.add(Box.createHorizontalGlue(), linkedC);
        content.add(linkedRow);

        jdbcPanel = new JdbcConfigurationPanel(null, null, this::syncLinkedCheck);
        jdbcPanel.setAlignmentX(0f);
        // The editor's natural width (driven by the widest combo entry / the full
        // JDBC URL preview) fits the step's content — NO width cap: capping it
        // compressed the rows and pushed the select fields out of the panel.
        content.add(jdbcPanel);

        // Test connection (server engines only — SQLite is file-based).
        JButton testButton = new JButton("Test connection");
        testButton.setFocusable(false);
        testButton.addActionListener(e -> testConnection());
        testRow.setOpaque(false);
        testRow.setAlignmentX(0f);
        testRow.add(testButton);
        testRow.add(statusLabel);
        content.add(testRow);

        panel.add(content, java.awt.BorderLayout.NORTH);

        // The selector's radios fire the same link-mode request as the former
        // check box did (either radio selection is a mode change).
        java.awt.event.ActionListener modeListener = e -> {
            if (programmaticLinkChange) {
                return;
            }
            jdbcPanel.requestLinkMode(linkedModeSelector.isLinkedMode());
        };
        linkedModeSelector.getLinkedRadio().addActionListener(modeListener);
        linkedModeSelector.getCustomRadio().addActionListener(modeListener);
        // A fresh step starts in the editor's initial (manual) mode — the load
        // pass never fires the link callback, so the radios are synced here.
        programmaticLinkChange = true;
        linkedModeSelector.setLinkedMode(jdbcPanel.isLinkedMode());
        programmaticLinkChange = false;
    }

    /**
     * The info dialog behind the "?" button next to the database-URL source
     * selector — the same HelpButton + INFORMATION_MESSAGE pattern the node
     * configuration menu's property rows use (see
     * {@code NodeConfigurationPanel#showHelp}).
     */
    private void showLinkedProfileHelp() {
        String html = "<p><b>Linked Database Profile</b> / <b>Custom db.URL</b></p>"
                + "<p><b>Linked Database Profile:</b> the node reuses an <b>existing database profile</b>"
                + " from the Database settings instead of getting a dedicated one: the connection"
                + " URL is taken from the selected profile (the engine and the link mode follow the"
                + " selection).</p>"
                + "<p>The profile select lists <b>only database profiles that already exist</b> for"
                + " the selected engine — without a single one the linked mode cannot be selected."
                + " Create a new database profile with the <b>+</b> button: it opens the engine's"
                + " database-profile wizard (prefilled with this node's suggested profile name) and"
                + " links the created profile to this node automatically.</p>"
                + "<p><b>Custom db.URL:</b> the node keeps a dedicated database (the default): it"
                + " gets its own database URL (SQLite) or its own connection settings (server"
                + " engines).</p>";
        javax.swing.JEditorPane message = WizardTexts.info(html, WizardTexts.DIALOG_WRAP_WIDTH);
        message.setAlignmentX(0f);
        JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT));
        content.add(message);
        JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(panel), content,
                "Linked Database Profile", JOptionPane.INFORMATION_MESSAGE,
                IconFontSwing.buildIcon(FontAwesome.QUESTION_CIRCLE, GuiConstants.ICON_SIZE_DIALOG,
                        GuiColors.getButtonIcon()));
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

    /**
     * The untouched SQLite setup's prefill URL for the given context: the
     * per-profile SSOT URL of the node profile name (the displayed path
     * carries the profile name — consistent with the confirmation dialog and
     * the URL the finish step persists); the bare convention base applies
     * when the name is not known yet.
     */
    private String sqlitePrefillUrlFor(WizardContext context) {
        String name = context == null ? null : context.getName();
        if (name == null || name.isBlank()) {
            return defaultSqliteUrl();
        }
        return ProfileCreateDefaults.sqliteDbUrl(name);
    }

    /** Whether the given URL is the untouched setup (the prefill or the bare convention default). */
    private boolean isUntouchedSqliteUrl(String url) {
        return !jdbcPanel.isLinkedMode()
                && (url.equals(sqlitePrefillUrl) || url.equals(defaultSqliteUrl()));
    }

    /** Whether the (regular) database file exists on disk (the summary's existing/new wording). */
    private static boolean databaseFileExists(java.nio.file.Path file) {
        return file != null && java.nio.file.Files.exists(file);
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

    /** The server database URL suffix the editor's Suffix field holds (the query string, with the leading '?'). */
    public String getSuffix() {
        return text(jdbcPanel.getSuffixField());
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
            statusLabel.setText(WizardTexts.wrap("Invalid port."));
            return;
        }
        statusLabel.setText(WizardTexts.wrap("Testing " + host + ":" + port + " ..."));
        new Thread(() -> {
            boolean ok;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2500);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean result = ok;
            SwingUtilities.invokeLater(() -> statusLabel.setText(WizardTexts.wrap(
                    result ? "Reachable — " + host + ":" + port
                            : "Not reachable: " + host + ":" + port + " (is the server running?)")));
        }, "wizard-db-test").start();
    }

    /** Keeps the database-URL source selector in sync with the panel's link mode. */
    private void syncLinkedCheck(JdbcConfigurationPanel p, JdbcConfigurationPanel.JdbcState state) {
        if (linkedModeSelector.isLinkedMode() != p.isLinkedMode()) {
            programmaticLinkChange = true;
            linkedModeSelector.setLinkedMode(p.isLinkedMode());
            programmaticLinkChange = false;
        }
    }

    /**
     * The suggested database profile name of this wizard run (SSOT: the node
     * profile's own name, the standard base name before step 1 resolves one) —
     * the prefill of the database-profile wizard the "+" button launches (the
     * same name the link mode is meant for: the node profile name as the
     * database profile name).
     */
    private String suggestedProfileName(WizardContext context) {
        String name = context != null ? context.getName() : null;
        if (name == null && boundContext != null) {
            name = boundContext.getName();
        }
        if (name == null || name.isBlank()) {
            name = ProfileNameSuggester.baseName(false, null, false, 0);
        }
        return name.trim();
    }

    /**
     * The discreet "+" button: opens the engine's database-profile wizard (G1 for
     * SQLite, G2 for the server engines) prefilled with this node's suggested
     * profile name, and links the created profile to this node (the editor
     * switches to the link mode with the created profile selected — the profile
     * select itself lists only existing profiles, so creation goes through this
     * button, never through typing into the dropdown). A cancelled wizard changes
     * nothing.
     */
    private void onCreateProfileClick() {
        DatabaseEngine engine = jdbcPanel.getEngine();
        if (engine == null) {
            return;
        }
        String suggested = suggestedProfileName(null); // the bound context of the running wizard
        String created;
        try {
            created = profileWizardLauncher.create(engine, suggested);
        } catch (RuntimeException e) {
            return; // a wizard failure is non-fatal — the user stays and can retry
        }
        if (created == null || created.trim().isEmpty()) {
            return; // cancelled
        }
        // Re-discover the profiles (the new one exists on disk now), select the
        // created profile in the editor, then enable the link mode.
        jdbcPanel.refreshProfiles();
        JComponent profile = jdbcPanel.getProfileComboComponent();
        if (profile instanceof JComboBox<?> combo) {
            combo.setSelectedItem(created.trim());
        }
        jdbcPanel.requestLinkMode(true);
        if (boundContext != null) {
            boundContext.markDbProfileCreated(created.trim());
        }
    }

    @Override
    public void bindContext(WizardContext context) {
        this.boundContext = context;
    }

    /**
     * Test hook: replaces the database-profile wizard launcher (null restores the
     * default). Lets the headless tests create profiles without a modal Swing dialog.
     */
    void setProfileWizardLauncher(DatabaseSelectionStep.DatabaseProfileWizardLauncher launcher) {
        this.profileWizardLauncher =
                launcher != null ? launcher : DatabaseSelectionStep::defaultProfileWizardLauncher;
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
     * profile name as the database profile name). The name is selected ONLY
     * when a database profile of that name already exists: the profile select
     * lists existing profiles exclusively (never a to-be-created one), and a
     * suggested name without a profile on disk is the "+" button's job (the
     * profile wizard is prefilled with the very same name). Before step 1
     * resolves a name, the standard base name applies.
     */
    private void prefillProfile(WizardContext context) {
        String name = suggestedProfileName(context);
        JComponent profile = jdbcPanel.getProfileComboComponent();
        if (profile instanceof JComboBox<?> combo && comboContains(combo, name)) {
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
        // File-based engine: validate the selected SQLite file instead of
        // server connection fields.
        if (context.getEngine() == DatabaseEngine.SQLITE) {
            return jdbcPanel.getValidationError();
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
            boolean untouched = isUntouchedSqliteUrl(url);
            if (untouched) {
                context.setSqliteDbUrl(null);
                context.setSqliteDbProfileName(null);
            } else {
                context.setSqliteDbUrl(url);
                SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
                context.setSqliteDbProfileName(parts == null || !SqliteDatabasePaths.isDbFile(parts.fileName()) ? null
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
        context.setDbUrlSuffix(getSuffix());
    }

    @Override
    public void onEnter(WizardContext context) {
        DatabaseEngine engine = context.getEngine() == null ? DatabaseEngine.MARIADB : context.getEngine();
        boolean sqlite = engine == DatabaseEngine.SQLITE;

        // The engine decision made in the selection step, visible on this step:
        // the official logo + the bold engine name + the full (wrapped)
        // engine-specific description.
        engineIconLabel.setIcon(engine.getLogoIcon(HEADER_LOGO_SIZE));
        engineNameLabel.setText(WizardTexts.wrap(WizardTexts.escape(engine.getDisplayName())));
        descriptionLabel.setText(WizardTexts.wrap(sqlite
                ? "SQLite is a file-based database engine.<br>"
                        + "The database URL is kept in the database profile's own data directory.<br>"
                        + "Configure the database URL as needed.<br>"
                        + "Select an existing database by choosing the database profile and "
                        + "database file, or create a new one with the database profile name."
                : engine.getDisplayName() + " — connection settings used by the node profile."));

        testRow.setVisible(!sqlite);

        // Load the editor FROM THE CONTEXT (the single source of truth): the database
        // decided on this step OR re-decided in the node-configuration step (step 4 —
        // its editable DB.Url, or the full advanced editor) is resolved from its URL
        // exactly the way the node-configuration panel does (JdbcConfigurationPanel#
        // loadManual derives the engine and the fields from the URL). A NEW engine is
        // always (re)initialized; the same engine reloads only when the context's
        // database actually changed — otherwise the editor keeps its current state
        // (its in-progress values, or the linked-database-profile mode).
        if (engine != initializedFor || !panelMatchesContext(context, engine)) {
            applyContextToPanel(context, engine);
        }
        lockEngine(engine);
    }

    /**
     * Loads the unified editor from the shared context for the given engine — the
     * database this step (or the node-configuration step) currently holds — falling
     * back to the manual-mode defaults of a fresh profile when no connection was
     * configured yet. The URL is what {@link #panelMatchesContext} compares against,
     * so a reload lands exactly on the state the context describes.
     */
    private void applyContextToPanel(WizardContext context, DatabaseEngine engine) {
        if (engine == DatabaseEngine.SQLITE) {
            String url = context.getSqliteDbUrl();
            if (url == null || url.isBlank()) {
                url = sqlitePrefillUrlFor(context);
            }
            // The untouched baseline is always the per-profile SSOT URL (the finish
            // step derives the final URL from it); the loaded URL may be a custom one.
            sqlitePrefillUrl = sqlitePrefillUrlFor(context);
            jdbcPanel.loadManual(url, "", "");
            prefillProfile(context);
        } else if (context.getDbHost() == null || context.getDbHost().isBlank()) {
            String protocol = engine == DatabaseEngine.POSTGRESQL ? "postgresql" : "mariadb";
            jdbcPanel.loadManual("jdbc:" + protocol + "://localhost:" + engine.getDefaultPort() + "/signum",
                    "signum", "");
        } else {
            String protocol = engine == DatabaseEngine.POSTGRESQL ? "postgresql" : "mariadb";
            int port = context.getDbPort() > 0 ? context.getDbPort() : engine.getDefaultPort();
            String db = context.getDbName() == null ? "" : context.getDbName();
            String suffix = context.getDbUrlSuffix() == null ? "" : context.getDbUrlSuffix();
            jdbcPanel.loadManual("jdbc:" + protocol + "://" + context.getDbHost() + ":" + port + "/" + db + suffix,
                    context.getDbUser(), context.getDbPassword());
        }
        initializedFor = engine;
    }

    /**
     * Whether the editor already shows the database the context holds — so entering
     * the step again with the same engine can keep the editor's current state (its
     * in-progress values or the linked-database-profile mode) instead of reloading.
     * Compared on the effective values, not the raw fields.
     */
    private boolean panelMatchesContext(WizardContext context, DatabaseEngine engine) {
        if (engine == DatabaseEngine.SQLITE) {
            String expected = context.getSqliteDbUrl();
            if (expected == null || expected.isBlank()) {
                expected = sqlitePrefillUrlFor(context);
            }
            return expected.equals(jdbcPanel.getJdbcUrl());
        }
        String host = context.getDbHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        int port = context.getDbPort() > 0 ? context.getDbPort() : engine.getDefaultPort();
        String db = context.getDbName() == null ? "" : context.getDbName();
        String suffix = context.getDbUrlSuffix() == null ? "" : context.getDbUrlSuffix();
        return host.equals(getHost())
                && Integer.toString(port).equals(getPortText())
                && db.equals(getDbName())
                && suffix.equals(getSuffix())
                && nz(context.getDbUser()).equals(jdbcPanel.getUsername())
                && nz(context.getDbPassword()).equals(jdbcPanel.getPassword());
    }

    /** Null-safe string (the editor's empty value is the empty string, not null). */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    public boolean autoSkip(WizardContext context) {
        // Never skipped: the database source (a dedicated URL vs. a linked database
        // profile) is decided on THIS step — the "Linked Database Profile" checkbox
        // + the profile combo — so the step always runs for every engine (for
        // SQLite it is the DB-configuration step).
        return false;
    }

    // ── Exit confirmation (SQLite: the database that will be created) ──────

    /**
     * Confirms the SQLite database configuration before leaving the step: the
     * dialog summarizes what will happen at finish — a NEW database is created
     * (the DB profile, the database file and the DB URL as selectable,
     * stacked lines) or an EXISTING database profile is used — with OK (proceed)
     * and Cancel (stay, keep editing). Server engines need no confirmation
     * (their connection settings are not "created" by the wizard).
     */
    @Override
    public boolean confirmExit(java.awt.Window parent, WizardContext context) {
        if (context.getEngine() != DatabaseEngine.SQLITE) {
            return true;
        }
        if (exitConfirmationHook != null) {
            return exitConfirmationHook.getAsBoolean();
        }
        String[] summary = buildSqliteExitSummary(context);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        javax.swing.JEditorPane heading = WizardTexts.heading(summary[0], WizardTexts.DIALOG_WRAP_WIDTH);
        heading.setAlignmentX(0f);
        content.add(heading);
        content.add(Box.createVerticalStrut(8));
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i < summary.length; i++) {
            if (i > 1) {
                lines.append("<br>");
            }
            lines.append(WizardTexts.escape(summary[i]));
        }
        javax.swing.JEditorPane body = WizardTexts.info(lines.toString(), WizardTexts.DIALOG_WRAP_WIDTH);
        body.setAlignmentX(0f);
        content.add(body);
        int result = JOptionPane.showOptionDialog(parent, content, "Database configuration",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                IconFontSwing.buildIcon(FontAwesome.DATABASE, GuiConstants.ICON_SIZE_DIALOG,
                        GuiColors.getButtonIcon()),
                new String[] { "OK", "Cancel" }, "OK");
        return result == JOptionPane.OK_OPTION;
    }

    /**
     * The confirmation dialog's summary (first element: the heading, the rest:
     * one selectable line per filled-in value, stacked). Package-private for
     * the unit tests (the dialog itself is not driven headlessly).
     */
    String[] buildSqliteExitSummary(WizardContext context) {
        String url = jdbcPanel.getJdbcUrl();
        if (jdbcPanel.isLinkedMode()) {
            String profile = jdbcPanel.getProfileName();
            return new String[] {
                    "An existing database profile will be used:",
                    "Database profile: " + (profile.isEmpty() ? "(none selected)" : profile),
                    "Database URL: " + url,
            };
        }
        if (isUntouchedSqliteUrl(url)) {
            // Untouched setup: the per-profile SSOT database (derived from the
            // profile name chosen in step 1).
            String name = context.getName() == null || context.getName().isBlank()
                    ? ProfileNameSuggester.baseName(false, null, false, 0)
                    : context.getName();
            java.nio.file.Path file = SqliteDatabasePaths.profileDir(name)
                    .resolve(ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME);
            return new String[] {
                    databaseFileExists(file) ? "An existing database will be selected:"
                            : "A new database will be created:",
                    "Database profile: " + name,
                    "Database file: " + file.toString().replace('\\', '/'),
                    "Database URL: " + ProfileCreateDefaults.sqliteDbUrl(name),
            };
        }
        // Edited manual setup: the composed URL's own location applies.
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        String dbProfile = parts == null ? null : SqliteDatabasePaths.deriveProfileName(parts.dir());
        java.nio.file.Path file = parts == null || parts.fileName().isEmpty()
                ? null : parts.dir().resolve(parts.fileName());
        String fileDisplay = file != null ? file.toString().replace('\\', '/') : url;
        return new String[] {
                databaseFileExists(file) ? "An existing database will be selected:"
                        : "A new database will be created:",
                "Database profile: " + (dbProfile != null ? dbProfile : "(custom location)"),
                "Database file: " + fileDisplay,
                "Database URL: " + url,
        };
    }

    /**
     * Test hook replacing the exit-confirmation dialog (null → the real
     * OK/Cancel dialog is shown). Public so the GUI-level dialog tests
     * (a different package) can keep the tests non-blocking.
     */
    public void setExitConfirmationHook(BooleanSupplier hook) {
        this.exitConfirmationHook = hook;
    }
}
