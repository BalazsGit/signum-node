package application.module.node.gui.configuration;

import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link JdbcConfigurationPanel} — the unified, mode-aware
 * JDBC row: the deterministic {@code loadInitial} link resolution
 * (explicit link &gt; SQLite convention auto-link &gt; manual), the
 * effective-state getters ({@code getLink} / {@code getJdbcUrl} /
 * {@code getUsername} / {@code getPassword}), the {@code JdbcState}
 * normalization, and the link-mode toggle with its change notifications.
 * <p>
 * The database-profile resolution is CWD-relative ({@code ./database/SQLite/
 * <profile>/profile.json}), so the test creates a uniquely named fixture
 * profile and removes it again unless the directory pre-existed.
 */
@DisplayName("JdbcConfigurationPanel (mode-aware unified JDBC row)")
class JdbcConfigurationPanelTest {

    private static final String PROFILE = "jdbc-panel-unit-test";
    private static final String PROFILE_URL = "jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/signum.sqlite.db";
    private static final Path PROFILE_DIR = Path.of("database", "SQLite", PROFILE);
    private static final String FOLDER_ONLY_PROFILE = "jdbc-panel-folder-only";
    private static final Path FOLDER_ONLY_DIR = Path.of("database", "SQLite", FOLDER_ONLY_PROFILE);
    private static final String FOLDER_ONLY_URL = "jdbc:sqlite:file:./database/SQLite/"
            + FOLDER_ONLY_PROFILE + "/signum.sqlite.db";
    private static final String MARIADB_URL = "jdbc:mariadb://db.example:3306/appdb";

    private boolean fixtureCreated;
    private final List<JdbcConfigurationPanel.JdbcState> linkChanges = new CopyOnWriteArrayList<>();

    private static <T> T onEdt(Callable<T> work) throws Exception {
        final Object[] out = new Object[1];
        final Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                out[0] = work.call();
            } catch (Exception e) {
                err[0] = e;
            }
        });
        if (err[0] != null)
            throw new RuntimeException(err[0]);
        return (T) out[0];
    }

    private static JdbcConfigurationPanel buildPanel(List<JdbcConfigurationPanel.JdbcState> changes) {
        return new JdbcConfigurationPanel("./conf", null, (panel, state) -> changes.add(state));
    }

    private static JdbcConfigurationPanel buildPanel(Runnable onChange,
            List<JdbcConfigurationPanel.JdbcState> changes) {
        return new JdbcConfigurationPanel("./conf", onChange, (panel, state) -> changes.add(state));
    }

    @BeforeEach
    void setUp() throws IOException {
        fixtureCreated = false;
        if (!Files.exists(PROFILE_DIR.resolve("profile.json"))) {
            Files.createDirectories(PROFILE_DIR);
            Files.writeString(PROFILE_DIR.resolve("profile.json"),
                    "{\"DB.Url\": \"" + PROFILE_URL + "\"}");
            fixtureCreated = true;
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (fixtureCreated) {
            Files.deleteIfExists(PROFILE_DIR.resolve("profile.json"));
            Files.deleteIfExists(PROFILE_DIR);
        }
    }

    @Test
    @DisplayName("an explicit link that materializes wins over convention derivation")
    void explicitLinkWinsOverAppliedProfile() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, "jdbc:sqlite:file:./elsewhere/other.db",
                    "alice", "secret");
            return null;
        });
        assertTrue(panel.isLinkedMode(), "the explicit link must win (linked mode)");
        assertEquals(PROFILE, panel.getProfileName(), "the explicit link's profile must be selected");
        assertEquals(DatabaseConfigurationPanel.DatabaseEngine.SQLITE, panel.getEngine());
        // the profile's database file is selected in the instance list
        assertEquals(PROFILE_URL, panel.getJdbcUrl());
        assertEquals("SQLite:" + PROFILE, panel.getLink());
    }

    @Test
    @DisplayName("an unmaterializable explicit link falls back to manual mode")
    void unmaterializableExplicitLinkFallsBackToManual() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:no-such-profile", MARIADB_URL, "alice", "secret");
            return null;
        });
        assertFalse(panel.isLinkedMode(), "a missing profile must not produce a link");
        assertEquals(MARIADB_URL, panel.getJdbcUrl(), "the manual URL must be preserved");
        assertEquals("alice", panel.getUsername());
        assertEquals("secret", panel.getPassword());
    }

    @Test
    @DisplayName("a convention-following SQLite URL auto-links to its profile")
    void conventionAutoLink() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial(null, PROFILE_URL, "", "");
            return null;
        });
        assertTrue(panel.isLinkedMode(), "the convention URL must auto-link");
        assertEquals(PROFILE, panel.getProfileName());
    }

    @Test
    @DisplayName("a convention-following SQLite URL auto-links to a folder-only profile (no profile.json)")
    void folderOnlyProfileAutoLinks() throws Exception {
        boolean created = ensureFolderOnlyProfile();
        try {
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial(null, FOLDER_ONLY_URL, "", "");
                return null;
            });
            assertTrue(panel.isLinkedMode(),
                    "a convention folder is a SQLite profile even without a profile.json");
            assertEquals(FOLDER_ONLY_PROFILE, panel.getProfileName());
            assertEquals("SQLite:" + FOLDER_ONLY_PROFILE, panel.getLink());
            assertEquals(FOLDER_ONLY_URL, panel.getJdbcUrl(),
                    "the URL composes from the profile folder and the selected file");
        } finally {
            if (created) {
                Files.deleteIfExists(FOLDER_ONLY_DIR);
            }
        }
    }

    @Test
    @DisplayName("an explicit link to a folder-only SQLite profile materializes (no profile.json required)")
    void explicitLinkToFolderOnlyProfile() throws Exception {
        boolean created = ensureFolderOnlyProfile();
        try {
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + FOLDER_ONLY_PROFILE, FOLDER_ONLY_URL, "", "");
                return null;
            });
            assertTrue(panel.isLinkedMode(), "a folder-only profile must materialize the link");
            assertEquals(FOLDER_ONLY_PROFILE, panel.getProfileName());
            assertEquals(FOLDER_ONLY_URL, panel.getJdbcUrl());
        } finally {
            if (created) {
                Files.deleteIfExists(FOLDER_ONLY_DIR);
            }
        }
    }

    @Test
    @DisplayName("a convention URL whose folder does not exist stays manual (explicit link falls back too)")
    void missingSqliteFolderStaysManual() throws Exception {
        String ghost = "jdbc-panel-ghost-" + System.nanoTime();
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial(null, "jdbc:sqlite:file:./database/SQLite/" + ghost + "/signum.sqlite.db",
                    "", "");
            return null;
        });
        assertFalse(panel.isLinkedMode(),
                "a convention URL pointing at a non-existing folder must not auto-link");

        onEdt(() -> {
            panel.loadInitial("SQLite:" + ghost, MARIADB_URL, "alice", "secret");
            return null;
        });
        assertFalse(panel.isLinkedMode(), "a link to a non-existing folder is unmaterializable");
        assertEquals(MARIADB_URL, panel.getJdbcUrl());
    }

    private boolean ensureFolderOnlyProfile() throws IOException {
        if (Files.exists(FOLDER_ONLY_DIR)) {
            return false;
        }
        Files.createDirectories(FOLDER_ONLY_DIR);
        return true;
    }

    @Test
    @DisplayName("typing into the Profile field signals a live unsaved change without committing the link")
    void profileTypingIsLiveChangeNotLinkCommit() throws Exception {
        JdbcConfigurationPanel.JdbcState baseline =
                new JdbcConfigurationPanel.JdbcState("SQLite:" + PROFILE, PROFILE_URL, "", "");
        int[] changes = { 0 };
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(() -> changes[0]++, linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            panel.setBaselines(baseline, baseline);
            panel.refreshColors();
            return null;
        });
        assertTrue(panel.isLinkedMode());
        assertEquals(GuiColors.getApplied(), panel.getProfileComboComponent().getForeground(),
                "the untouched recorded link is applied (green)");

        int changesBefore = changes[0];
        int linksBefore = linkChanges.size();
        onEdt(() -> {
            JComboBox<?> profileCombo = (JComboBox<?>) panel.getProfileComboComponent();
            JTextField editor = (JTextField) profileCombo.getEditor().getEditorComponent();
            editor.getDocument().insertString(editor.getDocument().getLength(), "x", null);
            return null;
        });
        assertTrue(changes[0] > changesBefore,
                "a keystroke must notify the host for a live recolor/dirty signal");
        assertEquals(linksBefore, linkChanges.size(),
                "a keystroke must not commit (persist) the in-flight link");
        onEdt(() -> {
            panel.refreshColors();
            return null;
        });
        assertEquals(GuiColors.getUnsaved(), panel.getProfileComboComponent().getForeground(),
                "the in-flight profile must color the row unsaved");
    }

    @Test
    @DisplayName("the editable combos are styled like the configuration's unified dropdowns")
    void editableCombosAreStyledLikeUnifiedDropdowns() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            // manual mode: both the Profile and the DB-file combo are editable
            panel.loadInitial(null, "jdbc:sqlite:file:./elsewhere/other.db", "", "");
            return null;
        });
        final Object[] results = new Object[1];
        onEdt(() -> {
            List<String> errors = new ArrayList<>();
            // the SINGLE standard height — the one place it is defined
            int plainH = ConfigurationUtils.standardControlHeight();
            checkEditableCombo(errors, "Profile", (JComboBox<?>) panel.getProfileComboComponent(), plainH);
            checkEditableCombo(errors, "DB file", (JComboBox<?>) panel.getDbFileComboComponent(), plainH);
            results[0] = errors;
            return null;
        });
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) results[0];
        assertTrue(errors.isEmpty(),
                "the db.url section's editable combos must be styled like every other dropdown: " + errors);
    }

    /** One editable combo of the db.url section: no L&F editor border, unified height. */
    private static void checkEditableCombo(List<String> errors, String name, JComboBox<?> combo, int plainH) {
        JComponent editor = (JComponent) combo.getEditor().getEditorComponent();
        JTextField field = null;
        if (editor instanceof JTextField tf) {
            field = tf;
        } else if (editor instanceof java.awt.Container cont) {
            for (Component child : cont.getComponents()) {
                if (child instanceof JTextField tf) {
                    field = tf;
                }
            }
        }
        if (field == null) {
            errors.add(name + ": no editor text field found");
            return;
        }
        if (!(field.getBorder() instanceof EmptyBorder)) {
            errors.add(name + ": the editor field must carry no L&F border (got: " + field.getBorder() + ")");
        }
        int h = combo.getPreferredSize().height;
        if (h != plainH) {
            errors.add(name + ": the combo's preferred height must equal the plain combo's (combo H=" + h
                    + ", plain H=" + plainH + ")");
        }
    }

    @Test
    @DisplayName("rediscoverSqliteLink re-derives the link from a convention URL (manual rows only)")
    void rediscoverSqliteLink() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial(null, "jdbc:sqlite:file:./elsewhere/other.db", "", "");
            return null;
        });
        assertFalse(panel.isLinkedMode(), "a non-convention URL stays manual at load");
        assertFalse(onEdt(() -> panel.rediscoverSqliteLink()),
                "no convention match: must stay manual");
        onEdt(() -> {
            panel.forceManual(PROFILE_URL, "", "");
            return null;
        });
        assertTrue(onEdt(() -> panel.rediscoverSqliteLink()),
                "a convention URL must re-derive the link");
        assertTrue(panel.isLinkedMode(), "the row must become linked");
        assertEquals(PROFILE, panel.getProfileName());
        assertTrue(onEdt(() -> panel.rediscoverSqliteLink()),
                "an already linked row stays linked");
        onEdt(() -> {
            panel.forceManual(MARIADB_URL, "alice", "secret");
            return null;
        });
        assertFalse(onEdt(() -> panel.rediscoverSqliteLink()),
                "server engines are never auto-linked");
    }

    @Test
    @DisplayName("a non-matching applied profile leaves the row in manual mode")
    void manualFallback() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial(null, MARIADB_URL, "alice", "secret");
            return null;
        });
        assertFalse(panel.isLinkedMode(), "nothing matches: the row must stay manual");
        assertEquals(MARIADB_URL, panel.getJdbcUrl());
        assertEquals("alice", panel.getUsername());
        assertEquals("secret", panel.getPassword());
    }

    @Test
    @DisplayName("forceManual switches a linked row back to manual mode")
    void forceManualClearsTheLink() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            return null;
        });
        assertTrue(panel.isLinkedMode());
        onEdt(() -> {
            panel.forceManual(MARIADB_URL, "bob", "pass2");
            return null;
        });
        assertFalse(panel.isLinkedMode(), "forceManual must leave linked mode");
        assertEquals(MARIADB_URL, panel.getJdbcUrl());
        assertEquals("bob", panel.getUsername());
        assertEquals("pass2", panel.getPassword());
    }

    @Test
    @DisplayName("requestLinkMode notifies the listener and composes unlinked URLs from the manual inputs")
    void linkModeToggleNotifiesAndPreservesValues() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        int before = linkChanges.size();
        onEdt(() -> {
            panel.requestLinkMode(true);
            return null;
        });
        assertTrue(panel.isLinkedMode(), "the link must turn on (a SQLite profile exists)");
        assertTrue(panel.getLink().startsWith("SQLite:"), "the link must carry a profile");
        String urlWhileLinked = panel.getJdbcUrl();
        assertTrue(linkChanges.size() > before, "the link change must be notified");

        int before2 = linkChanges.size();
        onEdt(() -> {
            panel.requestLinkMode(false);
            return null;
        });
        assertFalse(panel.isLinkedMode(), "the link must turn off");
        // The untouched manual Path and DB-file defaults remain independent
        // from the linked profile's database directory.
        assertEquals("jdbc:sqlite:file:./database/SQLite/signum.sqlite.db", panel.getJdbcUrl(),
                "unlinking must compose the URL from the manual inputs, not the linked profile path");
        assertNotEquals(urlWhileLinked, panel.getJdbcUrl(),
                "the linked URL must differ from the manual URL here");
        assertTrue(linkChanges.size() > before2, "the unlink must be notified too");
    }

    @Test
    @DisplayName("link toggles keep the profile, manual path, and DB-file inputs independent")
    void linkTogglePreservesUnsavedProfileAndDbFile() throws Exception {
        String profileName = "jdbc-link-toggle-" + System.nanoTime();
        Path profileDir = Path.of("database", "SQLite", profileName);
        Path firstFile = profileDir.resolve("a-initial.db");
        Files.createDirectories(profileDir);
        Files.createFile(firstFile);
        try {
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                JdbcConfigurationPanel.JdbcState baseline = panel.getState();
                panel.setBaselines(baseline, baseline);
                ((JComboBox<?>) panel.getProfileComboComponent()).setSelectedItem(profileName);
                JComboBox<?> dbCombo = (JComboBox<?>) panel.getDbFileComboComponent();
                dbCombo.getEditor().setItem("z-unsaved.db");
                return null;
            });

            String expectedUrl = "jdbc:sqlite:file:./database/SQLite/" + profileName + "/z-unsaved.db";
            String manualUrl = "jdbc:sqlite:file:./database/SQLite/z-unsaved.db";
            String manualPath = onEdt(() -> comboText((JComboBox<?>) panel.getPathFieldComponent()));
            assertEquals(profileName, panel.getProfileName(), "the new profile selection is currently unsaved");
            assertEquals(expectedUrl, panel.getJdbcUrl(), "the selected DB file forms the current URL");
            assertFalse(onEdt(() -> comboContains((JComboBox<?>) panel.getDbFileComboComponent(), "z-unsaved.db")),
                    "a custom current DB-file value must stay in the editor, not be added to the dropdown options");

            onEdt(() -> {
                panel.requestLinkMode(false);
                return null;
            });
            assertFalse(panel.isLinkedMode());
            assertEquals(manualUrl, panel.getJdbcUrl(),
                    "manual mode must compose the independent Path and DB file, not use the linked profile path");
            assertEquals(manualPath, onEdt(() -> comboText((JComboBox<?>) panel.getPathFieldComponent())),
                    "unlinking must not copy the linked profile path into the manual Path field");
            assertEquals(profileName,
                    onEdt(() -> ((JComboBox<?>) panel.getProfileComboComponent()).getSelectedItem()),
                    "unlinking must not discard the current profile selection");
            assertEquals("z-unsaved.db",
                    onEdt(() -> comboText((JComboBox<?>) panel.getDbFileComboComponent())),
                    "unlinking must not discard the current DB-file selection");
            assertFalse(onEdt(() -> comboContains((JComboBox<?>) panel.getDbFileComboComponent(), "z-unsaved.db")),
                    "unlinking must not add the current DB-file value to the option list");

            onEdt(() -> {
                panel.requestLinkMode(true);
                return null;
            });
            assertTrue(panel.isLinkedMode());
            assertEquals(profileName, panel.getProfileName(),
                    "relinking must restore the selected profile instead of the first SQLite profile");
            assertEquals(manualPath, onEdt(() -> comboText((JComboBox<?>) panel.getPathFieldComponent())),
                    "relinking must not change the independent manual Path field");
            assertEquals("z-unsaved.db",
                    onEdt(() -> comboText((JComboBox<?>) panel.getDbFileComboComponent())),
                    "relinking must preserve the DB-file editor value even when options are refreshed");
            assertEquals(expectedUrl, panel.getJdbcUrl(),
                    "relinking must restore the unsaved DB-file selection");
        } finally {
            Files.deleteIfExists(firstFile);
            Files.deleteIfExists(profileDir);
        }
    }

    @Test
    @DisplayName("manual SQLite Path is a styled editable dropdown with directory suggestions")
    void manualPathIsAnEditableDropdown() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial(null, "jdbc:sqlite:file:./database/SQLite/custom-location/custom.db", "", "");
            return null;
        });

        JComboBox<?> pathCombo = onEdt(() -> (JComboBox<?>) panel.getPathFieldComponent());
        assertTrue(onEdt(pathCombo::isEditable), "the Path input must accept arbitrary user-entered paths");
        assertEquals("./database/SQLite/custom-location", onEdt(() -> comboText(pathCombo)),
                "a custom current Path must remain in the editor");
        assertTrue(onEdt(() -> comboContains(pathCombo, "./database/SQLite")),
                "the dropdown should suggest the standard SQLite directory");
        assertFalse(onEdt(() -> comboContains(pathCombo, "./database/SQLite/custom-location")),
                "an arbitrary current Path must not be injected into the suggestion list");
    }

    private static String comboText(JComboBox<?> combo) {
        Object editorValue = combo.getEditor().getItem();
        return editorValue == null ? "" : editorValue.toString().trim();
    }

    private static boolean comboContains(JComboBox<?> combo, Object wanted) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (wanted.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("JdbcState trims/nulls every element; linkOf needs an engine and a profile")
    void jdbcStateNormalization() {
        JdbcConfigurationPanel.JdbcState s = new JdbcConfigurationPanel.JdbcState(
                null, " x ", null, null);
        assertEquals("", s.link());
        assertEquals("x", s.url());
        assertEquals("", s.username());
        assertEquals("", s.password());

        assertEquals("SQLite:p", JdbcConfigurationPanel.JdbcState.linkOf(
                DatabaseConfigurationPanel.DatabaseEngine.SQLITE, "  p "));
        assertEquals("", JdbcConfigurationPanel.JdbcState.linkOf(
                DatabaseConfigurationPanel.DatabaseEngine.MARIADB, " "));
        assertEquals("", JdbcConfigurationPanel.JdbcState.linkOf(null, "p"));
    }

    @Test
    @DisplayName("the star marks unsaved rows only; an applied (green) baseline carries none")
    void starAppearsOnlyForUnsavedState() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            return null;
        });

        // applied == current: every row is green (applied) — no star anywhere
        onEdt(() -> {
            JdbcConfigurationPanel.JdbcState current = panel.getState();
            panel.setBaselines(current, current);
            panel.refreshColors();
            return null;
        });
        assertFalse(labelTexts(panel).stream().anyMatch(t -> t.endsWith(" *")),
                "an applied baseline must carry no unsaved star; " + labelTexts(panel));

        // both baselines differ from the current values: unsaved — the star shows
        onEdt(() -> {
            JdbcConfigurationPanel.JdbcState empty =
                    new JdbcConfigurationPanel.JdbcState("", "", "", "");
            panel.setBaselines(empty, empty);
            panel.refreshColors();
            return null;
        });
        assertTrue(labelTexts(panel).stream().anyMatch(t -> t.endsWith(" *")),
                "an unsaved row must carry the star; " + labelTexts(panel));
    }

    @Test
    @DisplayName("typing a profile flags only the Profile row and the URL preview — Engine and DB file stay applied")
    void typingFlagsOnlyTheProfileRow() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            return null;
        });
        // Baseline == current: every row applied — no star anywhere.
        onEdt(() -> {
            JdbcConfigurationPanel.JdbcState current = panel.getState();
            panel.setBaselines(current, current);
            panel.refreshColors();
            return null;
        });
        assertFalse(labelTexts(panel).stream().anyMatch(t -> t.endsWith(" *")),
                "a matching baseline must flag nothing; " + labelTexts(panel));

        // A keystroke (no Enter): the Profile row and the URL preview flip to
        // unsaved; the untouched Engine select and the DB file select must
        // NOT be flagged (they were not changed by the user).
        final Object[] out = new Object[1];
        onEdt(() -> {
            JComboBox<?> combo = (JComboBox<?>) panel.getProfileComboComponent();
            JTextField f = (JTextField) combo.getEditor().getEditorComponent();
            f.getDocument().insertString(0, "x", null);
            out[0] = f;
            return null;
        });
        JTextField typed = (JTextField) out[0];
        onEdt(() -> {
            panel.refreshColors();
            return null;
        });
        try {
            assertTrue(labelTexts(panel).stream().anyMatch(t -> t.startsWith("Profile") && t.endsWith(" *")),
                    "the Profile row must be flagged while typing; " + labelTexts(panel));
            assertEquals(GuiColors.getUnsaved(),
                    onEdt(() -> panel.getProfileComboComponent().getForeground()),
                    "the profile select must be unsaved while typing");
            assertEquals(GuiColors.getUnsaved(),
                    onEdt(() -> panel.getPreviewField().getForeground()),
                    "the URL preview must be unsaved while typing (the URL differs)");
            assertFalse(labelTexts(panel).stream().anyMatch(
                            t -> (t.startsWith("Engine") || t.startsWith("DB file")) && t.endsWith(" *")),
                    "the untouched Engine and DB file rows must NOT be flagged; " + labelTexts(panel));
            assertEquals(GuiColors.getApplied(),
                    onEdt(() -> panel.getEngineComboComponent().getForeground()),
                    "the engine select must stay applied while typing the profile");
        } finally {
            typed.setText("");
        }
    }

    @Test
    @DisplayName("selecting or typing another SQLite DB file flags the DB file row unsaved immediately")
    void dbFileChangeFlagsTheRowUnsaved() throws Exception {
        boolean altCreated = false;
        try {
            // a second real .db file so the dropdown offers a switch
            if (!Files.exists(PROFILE_DIR.resolve("alt-unit.db"))) {
                Files.writeString(PROFILE_DIR.resolve("alt-unit.db"), "");
                altCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                JdbcConfigurationPanel.JdbcState current = panel.getState();
                panel.setBaselines(current, current);
                panel.refreshColors();
                return null;
            });
            assertEquals(GuiColors.getApplied(),
                    onEdt(() -> panel.getDbFileComboComponent().getForeground()),
                    "the untouched DB file row is applied");

            // 1) dropdown selection: the switch must flag the row immediately
            onEdt(() -> {
                JComboBox<?> combo = (JComboBox<?>) panel.getDbFileComboComponent();
                combo.setSelectedItem("alt-unit.db");
                return null;
            });
            onEdt(() -> {
                panel.refreshColors();
                return null;
            });
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/alt-unit.db",
                    panel.getJdbcUrl(), "the composed URL must follow the selection");
            assertEquals(GuiColors.getUnsaved(),
                    onEdt(() -> panel.getDbFileComboComponent().getForeground()),
                    "a switched DB file must be unsaved immediately");

            // 2) typing a new file name (no Enter): the composed URL and the
            //    row must track the LIVE editor text, not the uncommitted model
            final Object[] out = new Object[1];
            onEdt(() -> {
                JComboBox<?> combo = (JComboBox<?>) panel.getDbFileComboComponent();
                JTextField f = (JTextField) combo.getEditor().getEditorComponent();
                f.getDocument().insertString(f.getDocument().getLength(), "x", null); // alt-unit.dbx
                out[0] = f;
                return null;
            });
            onEdt(() -> {
                panel.refreshColors();
                return null;
            });
            JTextField typed = (JTextField) out[0];
            try {
                assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/alt-unit.dbx",
                        panel.getJdbcUrl(), "the composed URL must track the typed (uncommitted) file name");
                assertEquals(GuiColors.getUnsaved(),
                        onEdt(() -> panel.getDbFileComboComponent().getForeground()),
                        "the DB file row must be unsaved while typing");
                assertTrue(labelTexts(panel).stream().anyMatch(t -> t.startsWith("DB file") && t.endsWith(" *")),
                        "the DB file label must carry the unsaved star while typing; " + labelTexts(panel));
            } finally {
                typed.setText("");
            }
        } finally {
            if (altCreated) {
                Files.deleteIfExists(PROFILE_DIR.resolve("alt-unit.db"));
            }
        }
    }

    @Test
    @DisplayName("the DB file label stays visible in linked mode")
    void dbFileLabelVisibleInLinkedMode() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            return null;
        });
        assertTrue(panel.isLinkedMode(), "the explicit link must be in linked mode");
        assertTrue(labelTexts(panel).contains("DB file:"),
                "the linked SQLite row must keep its \"DB file:\" label; " + labelTexts(panel));
    }

    @Test
    @DisplayName("the JDBC URL preview is a read-only selectable text WITHOUT a text-box frame")
    void previewIsPlainSelectableText() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            return null;
        });
        final Object[] out = new Object[1];
        onEdt(() -> {
            out[0] = panel.getPreviewField();
            return null;
        });
        JTextField field = (JTextField) out[0];
        assertFalse(field.isEditable(), "the preview must not be editable");
        assertFalse(field.isOpaque(), "the preview must not paint a field background");
        // The requirement: the URL must stay selectable but must NOT sit in a
        // text-box-like frame — the L&F field border is gone (empty border),
        // while the unified one-line height of the regular inputs is kept.
        assertTrue(field.getBorder() instanceof javax.swing.border.EmptyBorder,
                "the preview must NOT carry the L&F text-box frame; got " + field.getBorder());
        assertEquals(ConfigurationUtils.standardControlHeight(), field.getPreferredSize().height,
                "the preview must share the standard one-line height of the regular inputs");
    }

    @Test
    @DisplayName("an engine switch in linked mode keeps the link (the checkbox stays on)")
    void engineSwitchKeepsTheLink() throws Exception {
        Path mariaDir = Path.of("database", "MariaDB", PROFILE);
        boolean mariaCreated = false;
        try {
            if (!Files.exists(mariaDir.resolve("profile.json"))) {
                Files.createDirectories(mariaDir);
                Files.writeString(mariaDir.resolve("profile.json"),
                        "{\"databases\":[{\"name\":\"appdb\",\"url\":\"" + MARIADB_URL + "\","
                                + "\"users\":[{\"username\":\"alice\",\"password\":\"secret\"}]}]}");
                mariaCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                return null;
            });
            assertTrue(panel.isLinkedMode());

            int before = linkChanges.size();
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.MARIADB);
                return null;
            });
            onEdt(() -> null); // flush the deferred follow-up work
            assertTrue(panel.isLinkedMode(), "the link must survive the engine switch");
            assertEquals(DatabaseConfigurationPanel.DatabaseEngine.MARIADB, panel.getEngine());
            assertEquals("MariaDB:" + PROFILE, panel.getLink(),
                    "the same-named profile must be kept under the new engine");
            assertEquals(MARIADB_URL, panel.getJdbcUrl(),
                    "the profile's instance must be selected");
            assertEquals("alice", panel.getUsername());
            assertEquals("secret", panel.getPassword());
            assertTrue(linkChanges.size() > before, "the engine switch must notify the link change");

            // No profile exists for this engine: the checkbox still stays on —
            // a link exists only with a profile, so the link value is empty.
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL);
                return null;
            });
            onEdt(() -> null); // flush the deferred follow-up work
            assertTrue(panel.isLinkedMode(),
                    "no profile for the new engine: the checkbox still stays on");
            assertEquals("", panel.getLink(), "a link exists only with a profile");
        } finally {
            if (mariaCreated) {
                Files.deleteIfExists(mariaDir.resolve("profile.json"));
                Files.deleteIfExists(mariaDir);
            }
        }
    }

    @Test
    @DisplayName("every one-line control of the section shares the standard height (unified, font-derived)")
    void allOneLineControlsShareTheStandardHeight() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            return null;
        });
        final Object[] out = new Object[2];
        onEdt(() -> {
            out[0] = panel;
            out[1] = Integer.valueOf(ConfigurationUtils.standardControlHeight());
            return null;
        });
        JdbcConfigurationPanel p = (JdbcConfigurationPanel) out[0];
        int standard = (Integer) out[1];
        List<String> errors = new ArrayList<>();
        checkHeight(errors, "engine select", p.getEngineComboComponent(), standard);
        checkHeight(errors, "profile select", p.getProfileComboComponent(), standard);
        checkHeight(errors, "db file select", p.getDbFileComboComponent(), standard);
        checkHeight(errors, "database field", p.getDatabaseField(), standard);
        checkHeight(errors, "host field", p.getHostField(), standard);
        checkHeight(errors, "port field", p.getPortField(), standard);
        checkHeight(errors, "suffix field", p.getSuffixField(), standard);
        checkHeight(errors, "username field", p.getUserEditComponent(), standard);
        checkHeight(errors, "password field", p.getPassField(), standard);
        checkHeight(errors, "preview field", p.getPreviewField(), standard);
        assertTrue(errors.isEmpty(),
                "all one-line controls must share the standard one-line height (standard H="
                        + standard + "): " + errors);
    }

    private static void checkHeight(List<String> errors, String name, JComponent comp, int standard) {
        int h = comp.getPreferredSize().height;
        if (h != standard) {
            errors.add(name + " H=" + h);
        }
    }

    @Test
    @DisplayName("the JDBC URL preview is selectable text WITHOUT a text-box frame (no L&F field border)")
    void previewIsFramelessButSelectable() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            return null;
        });
        final Object[] out = new Object[3];
        onEdt(() -> {
            JTextField preview = (JTextField) panel.getPreviewField();
            out[0] = preview.getBorder();
            out[1] = preview.isEditable();
            out[2] = preview.isOpaque();
            return null;
        });
        Border border = (Border) out[0];
        assertNotNull(border, "the preview keeps an (empty) border for its text insets");
        assertTrue(border instanceof EmptyBorder,
                "the preview must NOT carry the L&F text-box frame (the field border) — its border must be empty, got "
                        + border.getClass().getName());
        assertFalse((Boolean) out[1], "the preview stays read-only (it is a preview, not an input)");
        // A non-editable text field is still fully selectable/copyable.
        assertFalse(((JTextField) panel.getPreviewField()).isEditable());
        assertFalse((Boolean) out[2], "the preview has no field background (transparent)");
        // The preview text still aligns with the real inputs' text start
        // (the shared plain-combo text margin — only the FRAME is gone).
        onEdt(() -> {
            EmptyBorder eb = (EmptyBorder) panel.getPreviewField().getBorder();
            int left = eb.getBorderInsets(panel.getPreviewField()).left;
            int plainComboTextX = ConfigurationUtils.plainComboTextX();
            assertTrue(left >= plainComboTextX - 2 && left <= plainComboTextX + 2,
                    "the preview text must start at the unified text margin (left=" + left + ", margin=" + plainComboTextX + ")");
            return null;
        });
    }

    @Test
    @DisplayName("linked server mode shows the instance select (not an empty text field) and the derived host/port")
    void linkedServerShowsTheInstanceSelectAndDerivedFields() throws Exception {
        Path mariaDir = Path.of("database", "MariaDB", PROFILE);
        boolean mariaCreated = false;
        try {
            if (!Files.exists(mariaDir.resolve("profile.json"))) {
                Files.createDirectories(mariaDir);
                Files.writeString(mariaDir.resolve("profile.json"),
                        "{\"databases\":[{\"name\":\"appdb\",\"url\":\"" + MARIADB_URL + "\","
                                + "\"users\":[{\"username\":\"alice\",\"password\":\"secret\"}]}]}");
                mariaCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("MariaDB:" + PROFILE, MARIADB_URL, "alice", "secret");
                return null;
            });
            assertTrue(panel.isLinkedMode(), "the materializing recorded link must be linked");
            // The db row must show the INSTANCE SELECT (the FILE card) with the
            // profile's instance — an empty manual text field used to be shown
            // instead, so the section's input "did not appear" for linked
            // server profiles.
            JComponent dbCombo = panel.getDbFileComboComponent();
            JComponent databaseField = panel.getDatabaseField();
            assertTrue(dbCombo.isVisible(),
                    "the linked server mode must show the instance select");
            assertFalse(databaseField.isVisible(),
                    "the manual database text field must be hidden in linked mode");
            // The read-only host/port rows mirror the selected instance's URL
            // (AD-03) — they used to keep the construction-time values
            // (the section looked empty / stale for linked server profiles).
            assertEquals("db.example", ((JTextField) panel.getHostField()).getText(),
                    "the host row must derive from the selected instance's URL");
            assertEquals("3306", ((JTextField) panel.getPortField()).getText(),
                    "the port row must derive from the selected instance's URL");
            assertEquals(MARIADB_URL, panel.getJdbcUrl());
            assertEquals("alice", panel.getUsername());
        } finally {
            if (mariaCreated) {
                Files.deleteIfExists(mariaDir.resolve("profile.json"));
                Files.deleteIfExists(mariaDir);
            }
        }
    }

    @Test
    @DisplayName("an engine toggle that changed nothing restores the recorded profile, file and URL")
    void engineToggleRestoresTheRecordedProfile() throws Exception {
        // The first (alphabetical) folder entry of BOTH engines — the
        // by-product the old fallback logic drifted to on the round trip.
        Path sqlFirst = Path.of("database", "SQLite", "aaa-sql-first");
        Path mariaFirst = Path.of("database", "MariaDB", "aaa-maria-first");
        boolean sqlCreated = false;
        boolean mariaCreated = false;
        try {
            if (!Files.exists(sqlFirst)) {
                Files.createDirectories(sqlFirst);
                sqlCreated = true;
            }
            if (!Files.exists(mariaFirst.resolve("profile.json"))) {
                Files.createDirectories(mariaFirst);
                Files.writeString(mariaFirst.resolve("profile.json"),
                        "{\"databases\":[{\"name\":\"appdb\",\"url\":\"" + MARIADB_URL + "\","
                                + "\"users\":[{\"username\":\"alice\",\"password\":\"secret\"}]}]}");
                mariaCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                // the host's baselines: saved == applied == the loaded state
                JdbcConfigurationPanel.JdbcState current = panel.getState();
                panel.setBaselines(current, current);
                return null;
            });

            // SQLite -> MariaDB: no recorded MariaDB profile — the first one
            // is picked (the documented fallback)
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.MARIADB);
                return null;
            });
            onEdt(() -> null); // flush the deferred follow-up work
            // no recorded MariaDB profile: the first available one is picked
            // (the fixture guarantees the MariaDB folder is non-empty)
            String firstMaria = DatabaseConfigurationUtils.getProfileNames(".", "MariaDB").get(0);
            assertEquals("MariaDB:" + firstMaria, panel.getLink(),
                    "no recorded MariaDB profile: the first available is picked");
            // A link change is an unsaved (dirty) change: the host persists the
            // assignment but does NOT move the saved/applied baselines (only
            // load, save, and apply do). The baselines therefore stay at the
            // loaded state, so the restored profile comes back clean.

            // MariaDB -> SQLite: the recorded SQLite profile must be restored
            // — not the by-product fallback pick of the MariaDB phase — and
            // the recorded file/URL must come back with it.
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
                return null;
            });
            onEdt(() -> null); // flush the deferred follow-up work
            assertEquals(PROFILE, panel.getProfileName(),
                    "the recorded SQLite profile must be restored, not the previous fallback pick");
            assertEquals("SQLite:" + PROFILE, panel.getLink());
            assertEquals(PROFILE_URL, panel.getJdbcUrl(),
                    "the recorded file must be re-selected from the baseline URL");
            onEdt(() -> {
                panel.refreshColors();
                return null;
            });
            assertEquals(GuiColors.getApplied(),
                    onEdt(() -> panel.getProfileComboComponent().getForeground()),
                    "an engine toggle that changed nothing must come back applied (green)");
            assertFalse(labelTexts(panel).stream().anyMatch(t -> t.endsWith(" *")),
                    "nothing was changed: no unsaved star anywhere; " + labelTexts(panel));
        } finally {
            if (mariaCreated) {
                Files.deleteIfExists(mariaFirst.resolve("profile.json"));
                Files.deleteIfExists(mariaFirst);
            }
            if (sqlCreated) {
                Files.deleteIfExists(sqlFirst);
            }
        }
    }

    @Test
    @DisplayName("selecting another profile is an unsaved (red) change against the unchanged baseline")
    void selectingAnotherProfileIsUnsavedNotSaved() throws Exception {
        Path otherDir = Path.of("database", "SQLite", "zzz-other-profile");
        boolean otherCreated = false;
        try {
            if (!Files.exists(otherDir)) {
                Files.createDirectories(otherDir);
                otherCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                JdbcConfigurationPanel.JdbcState current = panel.getState();
                panel.setBaselines(current, current);
                return null;
            });
            onEdt(() -> {
                ((JComboBox<?>) panel.getProfileComboComponent()).setSelectedItem("zzz-other-profile");
                return null;
            });
            onEdt(() -> null); // flush
            assertEquals("SQLite:zzz-other-profile", panel.getLink());
            assertEquals("jdbc:sqlite:file:./database/SQLite/zzz-other-profile/signum.sqlite.db",
                    panel.getJdbcUrl());
            onEdt(() -> {
                panel.refreshColors();
                return null;
            });
            assertEquals(GuiColors.getUnsaved(),
                    onEdt(() -> panel.getProfileComboComponent().getForeground()),
                    "a link change is UNSAVED against the baseline — never SAVED (yellow) before an explicit save");
        } finally {
            if (otherCreated) {
                Files.deleteIfExists(otherDir);
            }
        }
    }

    @Test
    @DisplayName("an engine switch away and back restores the per-engine working state (saved and unsaved)")
    void engineSwitchRestoresThePerEngineWorkingState() throws Exception {
        Path otherDir = Path.of("database", "SQLite", "zzz-other-profile");
        Path mariaDir = Path.of("database", "MariaDB", "aaa-maria-first");
        boolean otherCreated = false;
        boolean mariaCreated = false;
        try {
            if (!Files.exists(otherDir)) {
                Files.createDirectories(otherDir);
                otherCreated = true;
            }
            if (!Files.exists(mariaDir.resolve("profile.json"))) {
                Files.createDirectories(mariaDir);
                Files.writeString(mariaDir.resolve("profile.json"),
                        "{\"databases\":[{\"name\":\"appdb\",\"url\":\"" + MARIADB_URL + "\","
                                + "\"users\":[{\"username\":\"alice\",\"password\":\"secret\"}]}]}");
                mariaCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                JdbcConfigurationPanel.JdbcState current = panel.getState();
                panel.setBaselines(current, current);
                return null;
            });

            // The user switches to another SQLite profile — an UNSAVED change
            onEdt(() -> {
                ((JComboBox<?>) panel.getProfileComboComponent()).setSelectedItem("zzz-other-profile");
                return null;
            });
            onEdt(() -> null);
            assertEquals("SQLite:zzz-other-profile", panel.getLink());

            // Away to MariaDB (no recorded profile there: the fallback pick)
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.MARIADB);
                return null;
            });
            onEdt(() -> null);
            assertTrue(panel.getLink().startsWith("MariaDB:"),
                    "on the MariaDB side the link is a MariaDB link; got " + panel.getLink());

            // Back to SQLite: the UNSAVED selection must come back — not the
            // baseline's profile and not a fallback pick.
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
                return null;
            });
            onEdt(() -> null);
            assertEquals("zzz-other-profile", panel.getProfileName(),
                    "the unsaved profile selection of SQLite must survive the round trip");
            assertEquals("SQLite:zzz-other-profile", panel.getLink());
            assertEquals("jdbc:sqlite:file:./database/SQLite/zzz-other-profile/signum.sqlite.db",
                    panel.getJdbcUrl(), "the file selection of the round trip comes back too");
            onEdt(() -> {
                panel.refreshColors();
                return null;
            });
            assertEquals(GuiColors.getUnsaved(),
                    onEdt(() -> panel.getProfileComboComponent().getForeground()),
                    "the restored change is still unsaved (the baselines did not move)");

            // ...and after an explicit save (the baselines move), the same
            // round trip restores it as APPLIED (green).
            onEdt(() -> {
                JdbcConfigurationPanel.JdbcState saved = panel.getState();
                panel.setBaselines(saved, saved);
                return null;
            });
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.MARIADB);
                return null;
            });
            onEdt(() -> null);
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
                return null;
            });
            onEdt(() -> null);
            assertEquals("zzz-other-profile", panel.getProfileName());
            assertEquals("jdbc:sqlite:file:./database/SQLite/zzz-other-profile/signum.sqlite.db",
                    panel.getJdbcUrl());
            onEdt(() -> {
                panel.refreshColors();
                return null;
            });
            assertEquals(GuiColors.getApplied(),
                    onEdt(() -> panel.getProfileComboComponent().getForeground()),
                    "a saved change is restored applied (green)");
        } finally {
            if (mariaCreated) {
                Files.deleteIfExists(mariaDir.resolve("profile.json"));
                Files.deleteIfExists(mariaDir);
            }
            if (otherCreated) {
                Files.deleteIfExists(otherDir);
            }
        }
    }

    @Test
    @DisplayName("an unsaved DB instance/user pick is remembered per profile across a profile toggle")
    void profileSwitchRestoresThePerProfileFileSelection() throws Exception {
        Path mariaDir = Path.of("database", "MariaDB", "aaa-per-profile");
        Path mariaOther = Path.of("database", "MariaDB", "zzz-per-profile-other");
        boolean mariaCreated = false;
        boolean otherCreated = false;
        try {
            if (!Files.exists(mariaDir.resolve("profile.json"))) {
                Files.createDirectories(mariaDir);
                Files.writeString(mariaDir.resolve("profile.json"),
                        "{\"databases\":[{\"name\":\"appdb\",\"url\":\"" + MARIADB_URL + "\",\"users\":[{\"username\":\"alice\",\"password\":\"secret\"}]},"
                                + "{\"name\":\"alt\",\"url\":\"jdbc:mariadb://db.example:3306/altdb\",\"users\":[{\"username\":\"bob\",\"password\":\"other\"}]}]}");
                mariaCreated = true;
            }
            if (!Files.exists(mariaOther.resolve("profile.json"))) {
                Files.createDirectories(mariaOther);
                Files.writeString(mariaOther.resolve("profile.json"),
                        "{\"databases\":[{\"name\":\"c\",\"url\":\"jdbc:mariadb://db.example:3306/otherdb\",\"users\":[{\"username\":\"carol\",\"password\":\"pw\"}]}]}");
                otherCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("MariaDB:aaa-per-profile", MARIADB_URL, "alice", "secret");
                JdbcConfigurationPanel.JdbcState current = panel.getState();
                panel.setBaselines(current, current);
                return null;
            });
            assertTrue(panel.isLinkedMode());
            assertEquals(MARIADB_URL, panel.getJdbcUrl());

            // The user picks the OTHER instance of the same profile — an
            // unsaved change (the baseline still records the first one).
            onEdt(() -> {
                ((JComboBox<?>) panel.getDbFileComboComponent()).setSelectedItem(
                        ((JComboBox<?>) panel.getDbFileComboComponent()).getItemAt(1));
                return null;
            });
            onEdt(() -> null); // flush
            assertEquals("jdbc:mariadb://db.example:3306/altdb", panel.getJdbcUrl());
            assertEquals("bob", panel.getUsername());

            // Toggle to the other profile and back: the unsaved instance and
            // user picks of the first profile must come back.
            onEdt(() -> {
                ((JComboBox<?>) panel.getProfileComboComponent()).setSelectedItem("zzz-per-profile-other");
                return null;
            });
            onEdt(() -> null); // flush
            assertEquals("jdbc:mariadb://db.example:3306/otherdb", panel.getJdbcUrl());
            onEdt(() -> {
                ((JComboBox<?>) panel.getProfileComboComponent()).setSelectedItem("aaa-per-profile");
                return null;
            });
            onEdt(() -> null); // flush
            assertEquals("jdbc:mariadb://db.example:3306/altdb", panel.getJdbcUrl(),
                    "the unsaved instance pick of the profile must survive the toggle");
            assertEquals("bob", panel.getUsername(),
                    "the unsaved user pick of the profile must survive the toggle");
            onEdt(() -> {
                panel.refreshColors();
                return null;
            });
            assertEquals(GuiColors.getUnsaved(),
                    onEdt(() -> panel.getDbFileComboComponent().getForeground()),
                    "the restored pick is still unsaved (the baselines did not move)");
        } finally {
            if (mariaCreated) {
                Files.deleteIfExists(mariaDir.resolve("profile.json"));
                Files.deleteIfExists(mariaDir);
            }
            if (otherCreated) {
                Files.deleteIfExists(mariaOther.resolve("profile.json"));
                Files.deleteIfExists(mariaOther);
            }
        }
    }

    @Test
    @DisplayName("resetWorkingState discards the remembered selections: a reload re-derives from the baselines")
    void resetWorkingStateDiscardsTheRememberedSelections() throws Exception {
        Path otherDir = Path.of("database", "SQLite", "zzz-other-profile");
        boolean otherCreated = false;
        boolean altCreated = false;
        try {
            if (!Files.exists(otherDir)) {
                Files.createDirectories(otherDir);
                otherCreated = true;
            }
            Path altDb = PROFILE_DIR.resolve("alt-test.db");
            if (!Files.exists(altDb)) {
                Files.writeString(altDb, "");
                altCreated = true;
            }
            JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
            onEdt(() -> {
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                JdbcConfigurationPanel.JdbcState current = panel.getState();
                panel.setBaselines(current, current);
                return null;
            });

            // An unsaved file pick + a profile toggle (the pick is remembered
            // per profile).
            onEdt(() -> {
                ((JComboBox<?>) panel.getDbFileComboComponent()).setSelectedItem("alt-test.db");
                return null;
            });
            onEdt(() -> null); // flush
            assertEquals("jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/alt-test.db", panel.getJdbcUrl());
            onEdt(() -> {
                ((JComboBox<?>) panel.getProfileComboComponent()).setSelectedItem("zzz-other-profile");
                return null;
            });
            onEdt(() -> null); // flush

            // The reload: the remembered working states are discarded and the
            // row re-derives from the (unchanged) disk values.
            onEdt(() -> {
                panel.resetWorkingState();
                panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
                return null;
            });

            // An engine round trip must restore the re-derived (recorded)
            // state — NOT the pre-reset unsaved pick.
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.MARIADB);
                return null;
            });
            onEdt(() -> null); // flush
            onEdt(() -> {
                ((JComboBox<?>) panel.getEngineComboComponent())
                        .setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
                return null;
            });
            onEdt(() -> null); // flush
            assertEquals(PROFILE, panel.getProfileName(),
                    "after a reset the re-derived (recorded) profile must be restored");
            assertEquals(PROFILE_URL, panel.getJdbcUrl(),
                    "after a reset the recorded file must come back — not the pre-reset unsaved pick");
        } finally {
            if (otherCreated) {
                Files.deleteIfExists(otherDir);
            }
            if (altCreated) {
                Files.deleteIfExists(PROFILE_DIR.resolve("alt-test.db"));
            }
        }
    }

    @Test
    @DisplayName("the popup lists color the applied (green) and saved (yellow) entries against the baselines")
    void popupListColorsAppliedAndSavedEntries() throws Exception {
        JdbcConfigurationPanel panel = onEdt(() -> buildPanel(linkChanges));
        onEdt(() -> {
            panel.loadInitial("SQLite:" + PROFILE, PROFILE_URL, "", "");
            panel.setBaselines(
                    new JdbcConfigurationPanel.JdbcState("SQLite:zzz-other-profile",
                            "jdbc:sqlite:file:./database/SQLite/zzz-other-profile/signum.sqlite.db", "", ""),
                    new JdbcConfigurationPanel.JdbcState("SQLite:" + PROFILE, PROFILE_URL, "", ""));
            return null;
        });
        final Object[] colors = new Object[3];
        onEdt(() -> {
            javax.swing.JList<Object> dummy = new javax.swing.JList<>(
                    new Object[]{PROFILE, "zzz-other-profile", "signum.sqlite.db"});
            @SuppressWarnings("unchecked")
            javax.swing.ListCellRenderer<Object> profileRenderer =
                    (javax.swing.ListCellRenderer<Object>) ((JComboBox<?>) panel.getProfileComboComponent()).getRenderer();
            @SuppressWarnings("unchecked")
            javax.swing.ListCellRenderer<Object> dbRenderer =
                    (javax.swing.ListCellRenderer<Object>) ((JComboBox<?>) panel.getDbFileComboComponent()).getRenderer();
            colors[0] = profileRenderer.getListCellRendererComponent(dummy, PROFILE, 0, false, false).getForeground();
            colors[1] = profileRenderer.getListCellRendererComponent(dummy, "zzz-other-profile", 1, false, false).getForeground();
            colors[2] = dbRenderer.getListCellRendererComponent(dummy, "signum.sqlite.db", 2, false, false).getForeground();
            return null;
        });
        assertEquals(GuiColors.getApplied(), colors[0],
                "the profile entry the node runs with must be applied (green) in the popup");
        assertEquals(GuiColors.getSaved(), colors[1],
                "the profile entry recorded on disk must be saved (yellow) in the popup");
        assertEquals(GuiColors.getApplied(), colors[2],
                "the DB file the node runs with must be applied (green) in the file list");
    }

    private static List<String> labelTexts(JdbcConfigurationPanel panel) {
        List<String> texts = new CopyOnWriteArrayList<>();
        for (Component c : panel.getComponents()) {
            if (c instanceof JLabel) {
                texts.add(((JLabel) c).getText());
            }
        }
        return texts;
    }
}