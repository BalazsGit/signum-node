package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.configuration.JdbcConfigurationPanel;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.SqliteDatabasePaths;
import jiconfont.icons.font_awesome.FontAwesome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Database wizard steps (installation + connection)")
class DatabaseStepsTest {

    @Test
    @DisplayName("selection step defaults to SQLite and exposes the selected engine")
    void selectionStep() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        assertEquals(DatabaseEngine.SQLITE, step.getSelectedEngine());
        assertNull(step.validate(new WizardContext()));
        step.onExit(new WizardContext());
    }

    @Test
    @DisplayName("selection step keeps plain radios; details default to SQLite with its official logo")
    void selectionStepLayoutAndDefaultDetails() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            JRadioButton radio = findRadio(step.getPanel(), engine.getDisplayName());
            assertNull(radio.getIcon(),
                    "radio '" + engine + "' must stay a traditional (icon-less) radio button");
            assertEquals(engine.getDisplayName(), radio.getText());
        }
        String details = detailText(step.getPanel());
        assertTrue(details.contains("Setup required"));
        assertTrue(details.contains("file-based"), "SQLite details should be shown by default");
        assertTrue(findLabel(step.getPanel(), "SQLite") != null,
                "details header should show the preselected engine name");
        javax.swing.Icon logo = detailsLogoIcon(step.getPanel());
        assertNotNull(logo, "details header should carry the engine logo");
        assertEquals(48, logo.getIconHeight(), "the logo is scaled to the details header size");
    }

    @Test
    @DisplayName("selection step updates the details (and logo) when another engine is selected")
    void selectionStepDetailsUpdate() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        javax.swing.Icon sqliteLogo = detailsLogoIcon(step.getPanel());
        findRadio(step.getPanel(), "MariaDB").doClick();
        assertEquals(DatabaseEngine.MARIADB, step.getSelectedEngine());
        String details = detailText(step.getPanel());
        assertTrue(details.contains("MySQL-compatible"), "MariaDB details should be shown");
        assertFalse(details.contains("file-based"), "SQLite details should be gone");
        assertTrue(findLabel(step.getPanel(), "MariaDB") != null);
        assertNotSame(sqliteLogo, detailsLogoIcon(step.getPanel()),
                "the details logo should follow the selection");

        findRadio(step.getPanel(), "PostgreSQL").doClick();
        assertEquals(DatabaseEngine.POSTGRESQL, step.getSelectedEngine());
        assertTrue(detailText(step.getPanel()).contains("full-text search"));
    }

    @Test
    @DisplayName("each engine's official logo loads from the classpath (SSOT path + scaled icon)")
    void engineLogosLoadFromClasspath() {
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            assertNotNull(engine.getLogoResourcePath());
            assertFalse(engine.getLogoResourcePath().isBlank());
            javax.swing.Icon icon = engine.getLogoIcon(24);
            assertNotNull(icon, "logo icon for " + engine);
            assertEquals(24, icon.getIconHeight());
            assertTrue(icon.getIconWidth() > 0);
        }
        assertNotSame(DatabaseEngine.SQLITE.getLogoIcon(24), DatabaseEngine.MARIADB.getLogoIcon(24));
    }

    @Test
    @DisplayName("wizard step header icons are topic-relevant")
    void stepHeaderIcons() {
        assertEquals(FontAwesome.DATABASE, new DatabaseSelectionStep().getHeaderIcon());
        assertEquals(FontAwesome.DOWNLOAD,
                new DatabaseInstallationStep(engine -> new JPanel(), engine -> false).getHeaderIcon());
        assertEquals(FontAwesome.DATABASE, new DatabaseConnectionStep().getHeaderIcon());
        assertEquals(FontAwesome.COG,
                new NodeConfigurationStep(() -> java.util.Set.of()).getHeaderIcon());
        assertEquals(FontAwesome.CHECK, new SummaryStep().getHeaderIcon());
    }

    @Test
    @DisplayName("the wizard's heading keywords render 1.2x larger (and bold) than the default font")
    void headingKeywordsAreScaled() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        float expected = base.getSize2D() * WizardStep.KEYWORD_FONT_SCALE;

        // step title ("1. …")
        javax.swing.JLabel title = findLabel(step.getPanel(), "1. Select the database engine for this node");
        assertNotNull(title);
        assertEquals(expected, title.getFont().getSize2D(), 0.0001f, "step title must be 1.2x the default font");
        assertTrue(title.getFont().isBold(), "step title must be bold");

        // the engine name in the engine-details panel
        javax.swing.JLabel engineName = findLabel(step.getPanel(), "SQLite");
        assertNotNull(engineName);
        assertEquals(expected, engineName.getFont().getSize2D(), 0.0001f,
                "the engine name must be 1.2x the default font");
        assertTrue(engineName.getFont().isBold());

        // the details section keywords
        for (String keyword : new String[] { "Description", "Setup required", "Best for" }) {
            javax.swing.JLabel kw = findLabel(step.getPanel(), keyword);
            assertNotNull(kw, "keyword label missing: " + keyword);
            assertEquals(expected, kw.getFont().getSize2D(), 0.0001f,
                    "keyword '" + keyword + "' must be 1.2x the default font");
        }

        // the other step titles ("2. …", "3. …")
        assertEquals(expected, findLabel(new NodeConfigurationStep(() -> java.util.Set.of()).getPanel(),
                "2. Configure the node profile").getFont().getSize2D(), 0.0001f);
        assertEquals(expected, findLabel(new SummaryStep().getPanel(),
                "3. Summary — review before creating the profile").getFont().getSize2D(), 0.0001f);

        // the section body texts stay at the default font size
        assertEquals(base.getSize2D(), detailBodySize(step.getPanel()), 0.0001f);
    }

    /** The (default-sized) body text of the shown engine's "Setup required" section. */
    private static float detailBodySize(javax.swing.JComponent root) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (c instanceof javax.swing.JLabel l
                    && l.getText() != null && l.getText().contains("<html>")) {
                return l.getFont().getSize2D();
            }
        }
        throw new IllegalStateException("details body label not found");
    }

    @Test
    @DisplayName("engine SSOT exposes non-empty description, setup requirements and usage notes")
    void engineDetailsTexts() {
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            assertNotNull(engine.getDescription());
            assertFalse(engine.getDescription().isBlank());
            assertFalse(engine.getSetupRequirements().isBlank());
            assertFalse(engine.getUsageNotes().isBlank());
        }
    }

    // ── installation step ──────────────────────────────────────────────

    @Test
    @DisplayName("installation step auto-skips for SQLite")
    void installationAutoSkipsSqlite() {
        DatabaseInstallationStep step = new DatabaseInstallationStep(engine -> new JPanel(), engine -> false);
        WizardContext sqlite = new WizardContext();
        sqlite.setEngine(DatabaseEngine.SQLITE);
        assertTrue(step.autoSkip(sqlite));
    }

    @Test
    @DisplayName("installation step blocks next when the engine is not installed and skip is off")
    void installationBlocksWhenNotInstalled() {
        AtomicInteger builds = new AtomicInteger();
        DatabaseInstallationStep step = new DatabaseInstallationStep(
                engine -> {
                    builds.incrementAndGet();
                    return new JPanel();
                },
                engine -> false);
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.MARIADB);
        step.onEnter(ctx);
        assertEquals(1, builds.get()); // installer shown → embedded panel lazily built
        String error = step.validate(ctx);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("install"));
    }

    @Test
    @DisplayName("installation step allows next when the user selects 'skip'")
    void installationSkipAllowsNext() {
        DatabaseInstallationStep step = new DatabaseInstallationStep(engine -> new JPanel(), engine -> false);
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.POSTGRESQL);
        step.onEnter(ctx);
        findCheck(step.getPanel()).setSelected(true);
        assertNull(step.validate(ctx));
        step.onExit(ctx);
        assertTrue(ctx.isDbInstallSkipped());
    }

    @Test
    @DisplayName("installation step pre-selects 'skip' when the engine is already installed")
    void installationPreselectsSkipWhenInstalled() {
        AtomicInteger builds = new AtomicInteger();
        DatabaseInstallationStep step = new DatabaseInstallationStep(
                engine -> {
                    builds.incrementAndGet();
                    return new JPanel();
                },
                engine -> true);
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.MARIADB);
        step.onEnter(ctx);
        assertTrue(findCheck(step.getPanel()).isSelected());
        assertEquals(0, builds.get()); // skip pre-selected → panel not built yet
        findCheck(step.getPanel()).doClick(); // user wants to review/reconfigure
        assertEquals(1, builds.get()); // lazily built when 'skip' is unchecked
        assertNull(step.validate(ctx));
        step.onExit(ctx);
        assertFalse(ctx.isDbInstallSkipped());
    }

    // ── connection step ────────────────────────────────────────────────

    @Test
    @DisplayName("connection step is shown for every engine (for SQLite it is the DB-configuration step)")
    void connectionStepIsAlwaysVisible() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            WizardContext ctx = new WizardContext();
            ctx.setEngine(engine);
            assertFalse(step.autoSkip(ctx), "autoSkip must be false for " + engine);
        }
    }

    @Test
    @DisplayName("connection step validates host + port (server engines); SQLite has nothing to validate")
    void connectionValidation() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.MARIADB);
        step.onEnter(ctx);
        JdbcConfigurationPanel db = jdbcPanel(step);
        assertNull(step.validate(ctx), "the unified editor's defaults (localhost:3306) are valid");
        ((javax.swing.JTextField) db.getPortField()).setText("not-a-port");
        String error = step.validate(ctx);
        assertNotNull(error);
        assertTrue(error.toLowerCase().contains("port"));
        ((javax.swing.JTextField) db.getHostField()).setText("");
        assertNotNull(step.validate(ctx), "an empty host is invalid");

        ctx.setEngine(DatabaseEngine.SQLITE);
        step.onEnter(ctx);
        assertNull(step.validate(ctx), "SQLite: file-based — no server connection to validate");
    }

    @Test
    @DisplayName("SQLite step 2: the configuration tab's unified DB.Url editor (engine locked, defaults, node-profile prefill, selectable URL preview)")
    void connectionSqliteShowsDbUrlEditor() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.SQLITE);
        ctx.setName("my-node");
        step.onEnter(ctx);
        JdbcConfigurationPanel db = jdbcPanel(step);

        // the engine is fixed by the selection step — the editor's combo stays locked
        javax.swing.JComponent engineCombo = db.getEngineComboComponent();
        assertEquals(DatabaseEngine.SQLITE, ((javax.swing.JComboBox<?>) engineCombo).getSelectedItem());
        assertFalse(engineCombo.isEnabled());
        assertFalse(db.isLinkedMode());
        // the Profile prefill is the node profile's own name (the link's target, SSOT with the auto-link)
        assertEquals("my-node", db.getProfileName());
        // the default trio: the convention base path + the default database file
        assertEquals("./database/SQLite", editorItem(db.getPathFieldComponent()),
                "the per-profile convention base is pre-filled (SSOT)");
        assertEquals(ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME, editorItem(db.getDbFileComboComponent()));
        assertEquals("jdbc:sqlite:file:./database/SQLite/signum.sqlite.db", db.getJdbcUrl());
        // the JDBC URL preview: the fields' font, read-only but selectable, no input-field frame
        javax.swing.JLabel previewLabel = findLabel(step.getPanel(), "JDBC URL:");
        assertNotNull(previewLabel, "the JDBC URL preview row must be present");
        java.awt.Component[] siblings = previewLabel.getParent().getComponents();
        int idx = java.util.Arrays.asList(siblings).indexOf(previewLabel);
        javax.swing.JTextField preview = (javax.swing.JTextField) siblings[idx + 1];
        assertFalse(preview.isEditable(), "the URL preview must stay read-only");
        assertTrue(preview.isFocusable(), "the URL preview must stay selectable");
        assertTrue(preview.getBorder() instanceof javax.swing.border.EmptyBorder,
                "the URL preview must not look like an input field");
        assertEquals("jdbc:sqlite:file:./database/SQLite/signum.sqlite.db", preview.getText());
        assertEquals(javax.swing.UIManager.getFont("Label.font").getSize2D(), preview.getFont().getSize2D(),
                0.0001f, "the preview keeps the fields' font");
        // the server rows and the Test-connection row are hidden for SQLite
        assertFalse(db.getHostField().isVisible());
        assertFalse(db.getPortField().isVisible());
        javax.swing.JButton testButton = findByText(step.getPanel(), javax.swing.JButton.class, "Test connection");
        assertFalse(testButton.getParent().isVisible(), "the Test-connection row must be hidden for SQLite");
    }

    @Test
    @DisplayName("server engines: the unified editor's server rows + Test-connection row; the SQLite rows stay hidden")
    void connectionServerShowsServerCard() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.POSTGRESQL);
        step.onEnter(ctx);
        JdbcConfigurationPanel db = jdbcPanel(step);

        // the engine is fixed by the selection step — the editor's combo stays locked
        javax.swing.JComponent engineCombo = db.getEngineComboComponent();
        assertEquals(DatabaseEngine.POSTGRESQL, ((javax.swing.JComboBox<?>) engineCombo).getSelectedItem());
        assertFalse(engineCombo.isEnabled());
        // the manual defaults follow the engine (host/port/database)
        assertEquals("localhost", ((javax.swing.JTextField) db.getHostField()).getText());
        assertEquals("5432", ((javax.swing.JTextField) db.getPortField()).getText());
        assertEquals("signum", ((javax.swing.JTextField) db.getDatabaseField()).getText());
        assertEquals("jdbc:postgresql://localhost:5432/signum", db.getJdbcUrl());
        // the server rows are visible, the SQLite Path row hidden
        assertTrue(db.getHostField().isVisible());
        assertTrue(db.getPortField().isVisible());
        assertTrue(db.getSuffixField().isVisible());
        assertFalse(db.getPathFieldComponent().isVisible());
        // the Test-connection row is visible for server engines
        javax.swing.JButton testButton = findByText(step.getPanel(), javax.swing.JButton.class, "Test connection");
        assertTrue(testButton.getParent().isVisible());
    }

    /** The step's unified JDBC db.url editor (the configuration tab's component). */
    private static JdbcConfigurationPanel jdbcPanel(DatabaseConnectionStep step) {
        return findByType(step.getPanel(), JdbcConfigurationPanel.class);
    }

    /** The live editor text of an editable combo (the model only commits on Enter/selection). */
    private static String editorItem(javax.swing.JComponent combo) {
        Object item = ((javax.swing.JComboBox<?>) combo).getEditor().getItem();
        return item == null ? "" : item.toString();
    }

    /** Sets the live editor text of an editable combo (fires the panel's change listeners). */
    private static void setEditorItem(javax.swing.JComponent combo, String value) {
        ((javax.swing.JComboBox<?>) combo).getEditor().setItem(value);
    }

    @Test
    @DisplayName("SQLite step 2 onExit: untouched editor keeps the per-profile SSOT URL; an edited DB file persists the composed URL")
    void connectionSqliteOnExit() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.onEnter(ctx);
        JdbcConfigurationPanel db = jdbcPanel(step);

        step.onExit(ctx);
        assertNull(ctx.getSqliteDbUrl(),
                "an untouched editor keeps the per-profile SSOT URL (derived at finish)");
        assertNull(ctx.getSqliteDbProfileName());

        setEditorItem(db.getDbFileComboComponent(), "custom.sqlite.db");
        step.onExit(ctx);
        assertEquals("jdbc:sqlite:file:./database/SQLite/custom.sqlite.db", ctx.getSqliteDbUrl(),
                "an edited editor persists the composed URL");
        assertNull(ctx.getSqliteDbProfileName(),
                "the convention base path names no profile — the node profile name applies");
    }

    @Test
    @DisplayName("SQLite step 2: the 'Linked Database Profile' checkbox drives the editor's link mode end-to-end")
    void linkedCheckboxDrivesLinkMode() throws Exception {
        java.nio.file.Path probe = SqliteDatabasePaths.profileDir("wizard-linked-probe");
        java.nio.file.Files.createDirectories(probe);
        try {
            DatabaseConnectionStep step = new DatabaseConnectionStep();
            WizardContext ctx = new WizardContext();
            ctx.setEngine(DatabaseEngine.SQLITE);
            step.onEnter(ctx);
            JdbcConfigurationPanel db = jdbcPanel(step);
            javax.swing.JCheckBox linked =
                    findByText(step.getPanel(), javax.swing.JCheckBox.class, "Linked Database Profile");
            assertFalse(linked.isSelected(), "a fresh step starts in manual mode");

            linked.doClick();
            assertTrue(db.isLinkedMode(), "checking the box links the editor to a database profile");
            ((javax.swing.JComboBox<?>) db.getProfileComboComponent()).setSelectedItem("wizard-linked-probe");
            String url = db.getJdbcUrl();
            assertEquals("jdbc:sqlite:file:./database/SQLite/wizard-linked-probe/signum.sqlite.db", url);
            step.onExit(ctx);
            assertEquals(url, ctx.getSqliteDbUrl());
            assertEquals("wizard-linked-probe", ctx.getSqliteDbProfileName());

            linked.doClick();
            assertFalse(db.isLinkedMode(), "unchecking returns to the manual mode");
            assertEquals("wizard-linked-probe", db.getProfileName(), "the profile selection is retained");
        } finally {
            for (java.nio.file.Path p : java.nio.file.Files.list(probe).toList()) {
                java.nio.file.Files.deleteIfExists(p);
            }
            java.nio.file.Files.deleteIfExists(probe);
        }
    }

    @Test
    @DisplayName("connection step onExit (server): collects host/port/user/password/dbName from the unified editor")
    void connectionOnExit() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.POSTGRESQL);
        step.onEnter(ctx);
        JdbcConfigurationPanel db = jdbcPanel(step);

        ((javax.swing.JTextField) db.getHostField()).setText("mydb");
        ((javax.swing.JTextField) db.getPortField()).setText("5433");
        ((javax.swing.JTextField) db.getDatabaseField()).setText("signum_prod");
        ((javax.swing.JTextField) db.getUserEditComponent()).setText("signum_app");
        ((javax.swing.JPasswordField) db.getPassField()).setText("s3cret");

        step.onExit(ctx);
        assertEquals(false, ctx.isSkipDbSetup());
        assertEquals("mydb", ctx.getDbHost());
        assertEquals(5433, ctx.getDbPort());
        assertEquals("signum_app", ctx.getDbUser());
        assertEquals("s3cret", ctx.getDbPassword());
        assertEquals("signum_prod", ctx.getDbName());
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static JRadioButton findRadio(javax.swing.JComponent root, String text) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (c instanceof JRadioButton rb && text.equals(rb.getText())) {
                return rb;
            }
        }
        throw new IllegalStateException("radio not found: " + text);
    }

    private static javax.swing.JLabel findLabel(javax.swing.JComponent root, String text) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (c instanceof javax.swing.JLabel l && text.equals(l.getText())) {
                return l;
            }
        }
        return null;
    }

    /**
     * The engine-details content (concatenated label texts of the "Engine details"
     * panel — the section keyword headings and the body texts are separate labels).
     */
    private static String detailText(javax.swing.JComponent root) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component comp : out) {
            javax.swing.JComponent c = (javax.swing.JComponent) comp;
            if (c.getBorder() instanceof javax.swing.border.TitledBorder
                    && "Engine details".equals(((javax.swing.border.TitledBorder) c.getBorder()).getTitle())) {
                StringBuilder sb = new StringBuilder();
                for (java.awt.Component d : c.getComponents()) {
                    appendLabelTexts(d, sb);
                }
                return sb.toString();
            }
        }
        throw new IllegalStateException("engine details panel not found");
    }

    private static void appendLabelTexts(java.awt.Component c, StringBuilder sb) {
        if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append(' ');
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                appendLabelTexts(child, sb);
            }
        }
    }

    /** The engine logo icon of the details header (the only label with an icon in the panel). */
    private static javax.swing.Icon detailsLogoIcon(javax.swing.JComponent root) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (c instanceof javax.swing.JLabel l && l.getIcon() != null) {
                return l.getIcon();
            }
        }
        return null;
    }

    private static javax.swing.JCheckBox findCheck(javax.swing.JComponent root) {
        return findByText(root, javax.swing.JCheckBox.class,
                "Skip — the database is already installed / I will install it manually");
    }

    private static <T extends javax.swing.JComponent> T findByText(
            javax.swing.JComponent root, Class<T> type, String text) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (!type.isInstance(c)) {
                continue;
            }
            String t;
            if (c instanceof javax.swing.JTextField f) {
                t = f.getText();
            } else if (c instanceof javax.swing.JCheckBox cb) {
                t = cb.getText();
            } else if (c instanceof javax.swing.JButton b) {
                t = b.getText();
            } else {
                continue;
            }
            if (text.equals(t)) {
                return type.cast(c);
            }
        }
        throw new IllegalStateException("not found: " + text);
    }

    private static <T extends javax.swing.JComponent> T findByType(javax.swing.JComponent root, Class<T> type) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        throw new IllegalStateException("not found: " + type.getSimpleName());
    }

    private static void collectChildren(javax.swing.JComponent root, java.util.List<java.awt.Component> out) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JComponent) {
                out.add(c);
                collectChildren((javax.swing.JComponent) c, out);
            }
        }
    }
}
