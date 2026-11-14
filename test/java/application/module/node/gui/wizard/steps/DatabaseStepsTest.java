package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.configuration.JdbcConfigurationPanel;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.SqliteDatabasePaths;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
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

    @BeforeAll
    static void registerIconFont() {
        // The application registers the icon font at startup (AppearanceModule#init);
        // do the same here so the connection step's HelpButton glyph resolves.
        IconFontSwing.register(FontAwesome.getIconFont());
    }

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
        assertTrue(findEditorPane(step.getPanel(), "SQLite") != null,
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
        assertTrue(findEditorPane(step.getPanel(), "MariaDB") != null);
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
        assertEquals(FontAwesome.COG, new NodeConfigurationStep().getHeaderIcon());
        assertEquals(FontAwesome.TAG, new ProfileNameStep(() -> java.util.Set.of()).getHeaderIcon());
        assertEquals(FontAwesome.CHECK, new SummaryStep().getHeaderIcon());
    }

    @Test
    @DisplayName("the wizard's heading keywords render 1.2x larger (and bold) than the default font")
    void headingKeywordsAreScaled() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        float expected = base.getSize2D() * WizardStep.KEYWORD_FONT_SCALE;

        // step title — a selectable text pane (the wizard's display texts are JEditorPanes)
        javax.swing.JEditorPane title = findEditorPane(step.getPanel(), "Select the database engine for this node");
        assertNotNull(title);
        assertEquals(expected, title.getFont().getSize2D(), 0.0001f, "step title must be 1.2x the default font");
        assertTrue(title.getFont().isBold(), "step title must be bold");

        // the engine name in the engine-details panel
        javax.swing.JEditorPane engineName = findEditorPane(step.getPanel(), "SQLite");
        assertNotNull(engineName);
        assertEquals(expected, engineName.getFont().getSize2D(), 0.0001f,
                "the engine name must be 1.2x the default font");
        assertTrue(engineName.getFont().isBold());

        // the details section keywords
        for (String keyword : new String[] { "Description", "Setup required", "Best for" }) {
            javax.swing.JEditorPane kw = findEditorPane(step.getPanel(), keyword);
            assertNotNull(kw, "keyword pane missing: " + keyword);
            assertEquals(expected, kw.getFont().getSize2D(), 0.0001f,
                    "keyword '" + keyword + "' must be 1.2x the default font");
            assertTrue(kw.getFont().isBold(), "keyword '" + keyword + "' must be bold");
        }

        // the other step titles (no hard-coded numbers: the wizard's header
        // already carries the "Step X of Y" position)
        assertEquals(expected, findEditorPane(new ProfileNameStep(() -> java.util.Set.of()).getPanel(),
                "Name the profile").getFont().getSize2D(), 0.0001f);
        assertEquals(expected, findEditorPane(new NodeConfigurationStep().getPanel(),
                "Node Profile configuration").getFont().getSize2D(), 0.0001f);
        assertEquals(expected, findEditorPane(new SummaryStep().getPanel(),
                "Summary — review before creating the profile").getFont().getSize2D(), 0.0001f);

        // the section body texts stay at the default font size
        assertEquals(base.getSize2D(), detailBodySize(step.getPanel()), 0.0001f);
    }

    /** The (default-sized) body text of the shown engine's description section. */
    private static float detailBodySize(javax.swing.JComponent root) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (c instanceof javax.swing.JEditorPane p
                    && p.getText() != null && p.getText().contains("file-based")) {
                return p.getFont().getSize2D();
            }
        }
        throw new IllegalStateException("details body pane not found");
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
    @DisplayName("connection step: the linked row + the JDBC editor are both pinned to the left edge (no centering drift)")
    void connectionStepRowsLeftPinned() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.onEnter(ctx);

        // "Linked Database Profile" sits in a FlowLayout row; a bare FlowLayout panel
        // defaults to alignmentX = 0.5f (centered) — pinning it to 0f keeps the row on
        // the left edge with the other rows (the former "rows drift apart" regression).
        java.awt.Container linkedRow = findLinkedRow(step.getPanel());
        assertNotNull(linkedRow, "the 'Linked Database Profile' row is present");
        assertEquals(0f, linkedRow.getAlignmentX(),
                "the linked row is pinned to the left edge (not the 0.5f FlowLayout default)");

        // The JDBC editor is pinned to the same left edge (the two rows must line up).
        JdbcConfigurationPanel jdbc = jdbcPanel(step);
        assertEquals(0f, jdbc.getAlignmentX(), "the JDBC editor is pinned to the left edge");
    }

    @Test
    @DisplayName("connection step: the radio row is flush with the text's left edge and keeps the same gap as name→description")
    void connectionStepRadioRowFlushAndGap() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.onEnter(ctx);
        // A bare, unreparented panel is never "displayable", so validate()/doLayout()
        // are no-ops — drive the layout managers directly instead (this is the same
        // math the wizard dialog runs when it shows the step).
        javax.swing.JComponent root = step.getPanel();
        root.setSize(root.getPreferredSize());
        root.getLayout().layoutContainer(root);
        javax.swing.JComponent content = (javax.swing.JComponent) root.getComponent(0);
        content.setSize(content.getPreferredSize());
        content.getLayout().layoutContainer(content);
        java.awt.Container linkedRow = findLinkedRow(root);
        assertNotNull(linkedRow, "the 'Linked Database Profile' row is present");
        linkedRow.setSize(content.getWidth(), linkedRow.getPreferredSize().height);
        linkedRow.getLayout().layoutContainer(linkedRow);
        java.util.List<java.awt.Component> all = new java.util.ArrayList<>();
        collectChildren(root, all);
        java.awt.Component selector = null;
        java.awt.Component namePane = null;
        java.awt.Component descPane = null;
        for (java.awt.Component c : all) {
            if (selector == null && c instanceof application.module.node.gui.configuration.LinkedDbModeSelector) {
                selector = c;
            }
            if (c instanceof javax.swing.JEditorPane pane) {
                String t = (pane.getText() == null ? "" : pane.getText()).replaceAll("<[^>]+>", "").trim();
                if (namePane == null && t.equals("SQLite")) {
                    namePane = c;
                }
                if (descPane == null && t.contains("file-based")) {
                    descPane = c;
                }
            }
        }
        assertNotNull(selector, "the linked-mode selector is present");
        assertNotNull(namePane, "the engine name pane is present");
        assertNotNull(descPane, "the engine description pane is present");

        // The selector (and its radios) starts at the row's left edge — the row is
        // pinned to the content's left edge (see connectionStepRowsLeftPinned) — so
        // the radios are flush with the text rows above (a FlowLayout's horizontal
        // gap used to drift them 8px to the right).
        assertTrue(linkedRow.getWidth() > linkedRow.getPreferredSize().width,
                "the test stretches the linked row beyond its preferred width to catch centered GridBag layouts");
        assertEquals(0, selector.getX(),
                "the radio row's first component is flush with the row's (the text's) left edge");
        javax.swing.JComponent selectorComponent = (javax.swing.JComponent) selector;
        selectorComponent.setSize(selectorComponent.getPreferredSize());
        selectorComponent.getLayout().layoutContainer(selectorComponent);

        // The trailing "+" / "?" buttons sit on the FIRST radio's row (top-anchored) —
        // GridBag's default anchor would float them to the two-radio stack's vertical
        // center, BETWEEN the two radios.
        application.module.node.gui.configuration.LinkedDbModeSelector modeSelector =
                (application.module.node.gui.configuration.LinkedDbModeSelector) selector;
        java.awt.Component firstRadio = modeSelector.getLinkedRadio();
        assertEquals(0, firstRadio.getX(), "the first radio starts at the row's left edge");
        assertEquals(0, modeSelector.getCustomRadio().getX(), "the second radio shares the same left edge");
        int firstRadioTop = selector.getY() + firstRadio.getY();
        int trailingButtons = 0;
        JButton plusButton = null;
        application.utils.gui.HelpButton helpButton = null;
        for (java.awt.Component rowComp : linkedRow.getComponents()) {
            if (rowComp instanceof JButton || rowComp instanceof application.utils.gui.HelpButton) {
                trailingButtons++;
                assertEquals(firstRadioTop, rowComp.getY(), 2.0,
                        rowComp.getClass().getSimpleName() + " must sit on the first radio's row (top "
                                + firstRadioTop + "px, actual " + rowComp.getY() + "px)");
                if (rowComp instanceof application.utils.gui.HelpButton help) {
                    helpButton = help;
                } else if (rowComp instanceof JButton plus) {
                    plusButton = plus;
                }
            }
        }
        assertEquals(2, trailingButtons, "the '+' and '?' buttons are both in the radio row");
        assertNotNull(plusButton);
        assertNotNull(helpButton);
        assertEquals(plusButton.getHeight(), helpButton.getHeight(),
                "the '+' and '?' have equal button heights so their icons share a centerline");
        assertEquals(plusButton.getX() + plusButton.getWidth() + 8, helpButton.getX(),
                "the '?' follows the '+' with the same horizontal gap as the other row controls");

        // The empty line above the radios is the SAME size as the one between the
        // engine name and the description (both are a normal empty line).
        int nameBottom = absOffset(namePane, root, false) + namePane.getHeight();
        int descTop = absOffset(descPane, root, false);
        int descBottom = descTop + descPane.getHeight();
        int radioTop = absOffset(selector, root, false);
        int nameDescGap = descTop - nameBottom;
        int descRadioGap = radioTop - descBottom;
        assertEquals(nameDescGap, descRadioGap, 2,
                "the gap above the radios (" + descRadioGap + "px) matches the name-to-description gap (" + nameDescGap + "px)");
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
        JdbcConfigurationPanel sqliteEditor = jdbcPanel(step);
        assertNull(step.validate(ctx), "the default SQLite database file is valid");
        setEditorItem(sqliteEditor.getDbFileComboComponent(), "ledger.data");
        String sqliteError = step.validate(ctx);
        assertNotNull(sqliteError);
        assertTrue(sqliteError.contains(".db"));
        setEditorItem(sqliteEditor.getDbFileComboComponent(), "signum.sqlite.db");
        assertNull(step.validate(ctx), "SQLite files with the .db extension are valid");
    }

    @Test
    @DisplayName("SQLite step 2: the configuration tab's unified DB.Url editor (engine locked, defaults, node-profile prefill, selectable URL preview)")
    void connectionSqliteShowsDbUrlEditor() throws Exception {
        // The suggested (node) profile name is prefilled into the profile
        // select ONLY when a database profile of that name already exists —
        // the dropdown lists existing profiles exclusively (a missing one is
        // the "+" button's job), so the test materializes the profile first.
        java.nio.file.Path myNodeDir = SqliteDatabasePaths.profileDir("my-node");
        boolean myNodeExisted = java.nio.file.Files.exists(myNodeDir);
        try {
            java.nio.file.Files.createDirectories(myNodeDir);
            runConnectionSqliteShowsDbUrlEditor();
        } finally {
            if (!myNodeExisted) {
                java.nio.file.Files.deleteIfExists(myNodeDir);
            }
        }
    }

    private static void runConnectionSqliteShowsDbUrlEditor() {
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
        // the Profile prefill is the node profile's own name — selected because
        // a database profile of that name exists (the link's target, SSOT with
        // the auto-link; the dropdown lists existing profiles only)
        assertEquals("my-node", db.getProfileName());
        // the default trio: the convention base path + the profile folder (the
        // node profile name — the path is consistent with the confirmation
        // dialog) + the default database file
        assertEquals("./database/SQLite/my-node", editorItem(db.getPathFieldComponent()),
                "the per-profile convention path (with the node profile name) is pre-filled (SSOT)");
        assertEquals(ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME, editorItem(db.getDbFileComboComponent()));
        assertEquals("jdbc:sqlite:file:./database/SQLite/my-node/signum.sqlite.db", db.getJdbcUrl());
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
        assertEquals("jdbc:sqlite:file:./database/SQLite/my-node/signum.sqlite.db", preview.getText());
        assertEquals(javax.swing.UIManager.getFont("Label.font").getSize2D(), preview.getFont().getSize2D(),
                0.0001f, "the preview keeps the fields' font");
        // the server rows and the Test-connection row are hidden for SQLite
        assertFalse(db.getHostField().isVisible());
        assertFalse(db.getPortField().isVisible());
        javax.swing.JButton testButton = findByText(step.getPanel(), javax.swing.JButton.class, "Test connection");
        assertFalse(testButton.getParent().isVisible(), "the Test-connection row must be hidden for SQLite");
    }

    @Test
    @DisplayName("connection step: an empty line separates the engine title from its description; the link row carries the info button")
    void connectionStepTitleGapAndLinkHelp() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        step.onEnter(new WizardContext()); // the engine title / description are filled on step entry
        java.util.List<java.awt.Component> children = new java.util.ArrayList<>();
        collectChildren(step.getPanel(), children);

        // The selector's radios are immediately followed by the "+"
        // create-profile button, which is followed by the "?" info button
        // (the node-configuration HelpButton pattern — the "+" comes BEFORE the
        // "?").
        int linkIdx = -1;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) instanceof javax.swing.JRadioButton rb
                    && "Linked Database Profile".equals(rb.getText())) {
                linkIdx = i;
                break;
            }
        }
        assertTrue(linkIdx >= 0, "the 'Linked Database Profile' radio is present");
        assertTrue(children.get(linkIdx + 1) instanceof javax.swing.JRadioButton
                        && "Custom db.URL".equals(((javax.swing.JRadioButton) children.get(linkIdx + 1)).getText()),
                "the 'Custom db.URL' radio sits right after the 'Linked Database Profile' radio");
        assertTrue(children.get(linkIdx + 2) instanceof javax.swing.JButton
                        && !(children.get(linkIdx + 2) instanceof application.utils.gui.HelpButton),
                "the create-profile '+' button sits right after the selector's radios");
        assertTrue(children.get(linkIdx + 3) instanceof application.utils.gui.HelpButton,
                "the info button sits right after the '+' button");

        // the engine title is separated from its description by one empty line
        java.util.List<javax.swing.JEditorPane> panes = new java.util.ArrayList<>();
        for (java.awt.Component c : children) {
            if (c instanceof javax.swing.JEditorPane p) {
                panes.add(p);
            }
        }
        assertEquals("SQLite", plainText(panes.get(0)).trim(), "the engine title pane");
        assertEquals("", plainText(panes.get(1)).trim(), "an empty line after the engine title");
        assertTrue(plainText(panes.get(2)).contains("file-based"), "the description follows the empty line");
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

    /** The FlowLayout row holding the database-URL source selector (and its buttons). */
    private static java.awt.Container findLinkedRow(javax.swing.JComponent root) {
        java.util.List<java.awt.Component> all = new java.util.ArrayList<>();
        collectChildren(root, all);
        for (java.awt.Component c : all) {
            if (c instanceof application.module.node.gui.configuration.LinkedDbModeSelector) {
                return c.getParent();
            }
        }
        return null;
    }

    /** The step's discreet, text-less "+" create-profile button (not the "?" HelpButton). */
    private static javax.swing.JButton findCreateProfileButton(javax.swing.JComponent root) {
        java.util.List<java.awt.Component> all = new java.util.ArrayList<>();
        collectChildren(root, all);
        for (java.awt.Component c : all) {
            if (c instanceof javax.swing.JButton b
                    && !(c instanceof application.utils.gui.HelpButton)
                    && (b.getText() == null || b.getText().isEmpty())) {
                return b;
            }
        }
        throw new IllegalStateException("create-profile button not found");
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
    @DisplayName("SQLite step 2: the database-URL source selector drives the editor's link mode end-to-end")
    void linkedCheckboxDrivesLinkMode() throws Exception {
        java.nio.file.Path probe = SqliteDatabasePaths.profileDir("wizard-linked-probe");
        java.nio.file.Files.createDirectories(probe);
        try {
            DatabaseConnectionStep step = new DatabaseConnectionStep();
            WizardContext ctx = new WizardContext();
            ctx.setEngine(DatabaseEngine.SQLITE);
            step.onEnter(ctx);
            JdbcConfigurationPanel db = jdbcPanel(step);
            javax.swing.JRadioButton linked =
                    findByText(step.getPanel(), javax.swing.JRadioButton.class, "Linked Database Profile");
            javax.swing.JRadioButton custom =
                    findByText(step.getPanel(), javax.swing.JRadioButton.class, "Custom db.URL");
            assertTrue(custom.isSelected(), "a fresh step starts in manual (Custom db.URL) mode");
            assertFalse(linked.isSelected(), "a fresh step starts in manual mode");

            linked.doClick();
            assertTrue(db.isLinkedMode(), "selecting the radio links the editor to a database profile");
            ((javax.swing.JComboBox<?>) db.getProfileComboComponent()).setSelectedItem("wizard-linked-probe");
            String url = db.getJdbcUrl();
            assertEquals("jdbc:sqlite:file:./database/SQLite/wizard-linked-probe/signum.sqlite.db", url);
            step.onExit(ctx);
            assertEquals(url, ctx.getSqliteDbUrl());
            assertEquals("wizard-linked-probe", ctx.getSqliteDbProfileName());

            custom.doClick();
            assertFalse(db.isLinkedMode(), "selecting Custom db.URL returns to the manual mode");
            assertEquals("wizard-linked-probe", db.getProfileName(), "the profile selection is retained");
        } finally {
            for (java.nio.file.Path p : java.nio.file.Files.list(probe).toList()) {
                java.nio.file.Files.deleteIfExists(p);
            }
            java.nio.file.Files.deleteIfExists(probe);
        }
    }

    @Test
    @DisplayName("the discreet '+' button opens the engine's DB wizard (prefilled with the suggested name) and links the created profile")
    void createProfileButtonLinksCreatedProfile() throws Exception {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();
        ctx.setEngine(DatabaseEngine.SQLITE);
        step.bindContext(ctx);
        // The fake launcher materializes the profile the SAME way the real
        // wizard does — the profile folder appears on disk (discovery is
        // folder-based), so the editor's dropdown (existing profiles only)
        // lists it after the click.
        java.nio.file.Path createdDir = SqliteDatabasePaths.profileDir("created-db-profile");
        boolean createdExisted = java.nio.file.Files.exists(createdDir);
        final String[] suggested = new String[1];
        step.setProfileWizardLauncher((engine, name) -> {
            suggested[0] = name;
            try {
                java.nio.file.Files.createDirectories(createdDir);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return "created-db-profile";
        });
        try {
            step.onEnter(ctx); // initialize the editor for the engine
            JdbcConfigurationPanel db = jdbcPanel(step);
            assertFalse(db.isLinkedMode(), "a fresh step starts in manual mode");

            findCreateProfileButton(step.getPanel()).doClick();

            assertTrue(db.isLinkedMode(), "the created profile is linked to the node");
            assertEquals("created-db-profile", db.getProfileName(), "the created profile is selected");
            assertEquals(application.module.node.profile.ProfileNameSuggester.baseName(false, null, false, 0),
                    suggested[0],
                    "the profile wizard is prefilled with the node's suggested profile name");
            assertTrue(ctx.getSessionDbProfilesCreated().contains("created-db-profile"),
                    "the created profile is tracked in the session");
        } finally {
            if (!createdExisted) {
                java.nio.file.Files.deleteIfExists(createdDir);
            }
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

    @Test
    @DisplayName("connection step onEnter re-resolves the editor from the context (the DB/engine decided in the node-configuration step)")
    void connectionOnEnterReResolvesFromContext() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext ctx = new WizardContext();

        // A server database decided elsewhere (the node-configuration step's editable
        // DB.Url / the full advanced editor) is mirrored into the context:
        ctx.setEngine(DatabaseEngine.POSTGRESQL);
        ctx.setSkipDbSetup(false);
        ctx.setDbHost("db.example.com");
        ctx.setDbPort(5432);
        ctx.setDbUser("signum_app");
        ctx.setDbPassword("s3cret");
        ctx.setDbName("signum_prod");
        ctx.setDbUrlSuffix("?ssl=true");

        step.onEnter(ctx);
        JdbcConfigurationPanel db = jdbcPanel(step);

        assertEquals(DatabaseEngine.POSTGRESQL, db.getEngine(),
                "the editor is in the connection settings of the context's engine");
        assertEquals("db.example.com", ((javax.swing.JTextField) db.getHostField()).getText().trim());
        assertEquals("5432", ((javax.swing.JTextField) db.getPortField()).getText().trim());
        assertEquals("signum_prod", ((javax.swing.JTextField) db.getDatabaseField()).getText().trim());
        assertEquals("?ssl=true", ((javax.swing.JTextField) db.getSuffixField()).getText().trim());
        assertEquals("signum_app", ((javax.swing.JTextField) db.getUserEditComponent()).getText().trim());
        assertEquals("s3cret", ((javax.swing.JPasswordField) db.getPassField()).getText().trim());

        // Re-entering with the SAME context keeps the resolved values (no spurious reset):
        step.onEnter(ctx);
        assertEquals("db.example.com", ((javax.swing.JTextField) db.getHostField()).getText().trim());
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
     * A selectable display-text pane (the wizard renders its titles / keywords /
     * bodies as non-editable {@link javax.swing.JEditorPane}s) whose plain text
     * (tags stripped) equals the given text.
     */
    private static javax.swing.JEditorPane findEditorPane(javax.swing.JComponent root, String text) {
        java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
        collectChildren(root, out);
        for (java.awt.Component c : out) {
            if (c instanceof javax.swing.JEditorPane p && text.equals(plainText(p))) {
                return p;
            }
        }
        return null;
    }

    /**
     * The pane's text without the HTML wrapper/tags and with the HTML entities
     * decoded (the parsed document re-serializes non-ASCII characters as
     * {@code &#NNNN;} entities in {@code getText()}).
     */
    private static String plainText(javax.swing.JEditorPane p) {
        String t = p.getText();
        if (t == null) {
            return "";
        }
        String plain = t.replaceAll("<[^>]*>", " ");
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);").matcher(plain);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1);
            int cp = (code.startsWith("x") || code.startsWith("X"))
                    ? Integer.parseInt(code.substring(1), 16)
                    : Integer.parseInt(code);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) cp)));
        }
        m.appendTail(sb);
        plain = sb.toString()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&nbsp;", " ");
        return plain.replaceAll("\\s+", " ").trim();
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
        // the selectable display texts (titles / keywords / bodies) are JEditorPanes
        if (c instanceof javax.swing.JEditorPane p && p.getText() != null) {
            sb.append(plainText(p)).append(' ');
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
            } else if (c instanceof javax.swing.JRadioButton rb) {
                t = rb.getText();
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

    /** The component's cumulative X/Y offset up to (but not including) the given root. */
    private static int absOffset(java.awt.Component c, java.awt.Component root, boolean isX) {
        int offset = 0;
        for (java.awt.Component cur = c; cur != null && cur != root; cur = cur.getParent()) {
            offset += isX ? cur.getX() : cur.getY();
        }
        return offset;
    }
}
