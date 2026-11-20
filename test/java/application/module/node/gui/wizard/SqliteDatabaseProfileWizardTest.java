package application.module.node.gui.wizard;

import application.module.database.profile.SQLiteProfile;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.profile.ProfileCreateDefaults;
import application.utils.io.PathUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SqliteDatabaseProfileWizard} — the headless creation core
 * (validation, URL composition, existence check, single file-write entry point)
 * plus a lightweight GUI-construction check of the dialog's defaults.
 */
@DisplayName("SqliteDatabaseProfileWizard (SQLite database profile wizard)")
class SqliteDatabaseProfileWizardTest {

    @Test
    @DisplayName("validateInput rejects blank / over-long / separator / non-.db input")
    void validateInputRejectsInvalid() {
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("", "x.db"), "blank name");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput(null, "x.db"), "null name");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("a".repeat(33), "x.db"), "too long");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("a/b", "x.db"), "forward slash");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("a\\b", "x.db"), "backslash");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("a:b", "x.db"), "colon");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("..", "x.db"), "dot dot");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("alpha", ""), "blank file");
        assertNotNull(SqliteDatabaseProfileWizard.validateInput("alpha", "noext"), "non .db file");
    }

    @Test
    @DisplayName("validateInput accepts a valid (name, .db file) pair")
    void validateInputAcceptsValid() {
        assertNull(SqliteDatabaseProfileWizard.validateInput("alpha", "signum.sqlite.db"));
        assertNull(SqliteDatabaseProfileWizard.validateInput("  alpha  ", "  custom.db  "));
        assertNull(SqliteDatabaseProfileWizard.validateInput("a", "X.DB"), ".db is case-insensitive");
    }

    @Test
    @DisplayName("composeUrl equals the per-profile SSOT URL for the default file")
    void composeUrlMatchesSsotForDefaultFile() {
        assertEquals(ProfileCreateDefaults.sqliteDbUrl("alpha"),
                SqliteDatabaseProfileWizard.composeUrl("alpha", ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME));
    }

    @Test
    @DisplayName("composeUrl composes a custom file name into the convention directory")
    void composeUrlWithCustomFile() {
        assertEquals("jdbc:sqlite:file:./database/SQLite/alpha/custom.db",
                SqliteDatabaseProfileWizard.composeUrl("alpha", "custom.db"));
    }

    @Test
    @DisplayName("createProfile writes ./database/SQLite/<name>/profile.json (engine settings only — the URL is derived)")
    void createProfileCreatesProfileJson() throws IOException {
        String name = "wiz_probe_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        try {
            assertEquals(name, SqliteDatabaseProfileWizard.createProfile(name, "signum.sqlite.db"));

            assertTrue(Files.isDirectory(dir), "the profile directory must exist");
            Path profileJson = dir.resolve("profile.json");
            assertTrue(Files.isRegularFile(profileJson), "the profile.json must exist");
            JsonObject json = JsonParser.parseString(Files.readString(profileJson)).getAsJsonObject();
            assertFalse(json.has(SQLiteProfile.CFG_URL),
                    "the database URL must NOT be stored in the profile JSON (it is derived; the node profile's DB.Url is the SSOT)");
            assertEquals(SqliteDatabaseProfileWizard.composeUrl(name, "signum.sqlite.db"),
                    new SQLiteProfile(name).getDbUrl(),
                    "the derived URL of the fresh profile equals the wizard's composed URL");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("createProfile rejects invalid input and creates nothing")
    void createProfileRejectsInvalidInput() throws IOException {
        String name = "wiz_invalid_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> SqliteDatabaseProfileWizard.createProfile("", "x.db"));
            assertThrows(IllegalArgumentException.class,
                    () -> SqliteDatabaseProfileWizard.createProfile("a/b", "x.db"));
            assertThrows(IllegalArgumentException.class,
                    () -> SqliteDatabaseProfileWizard.createProfile(name, "noext"));
            assertFalse(Files.exists(dir), "no profile may be created for invalid input");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("profileExists is false before and true after createProfile (discovery SSOT)")
    void profileExistsReflectsCreation() throws IOException {
        String name = "wiz_exists_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        try {
            assertFalse(SqliteDatabaseProfileWizard.profileExists(name), "must not exist yet");
            SqliteDatabaseProfileWizard.createProfile(name, "signum.sqlite.db");
            assertTrue(SqliteDatabaseProfileWizard.profileExists(name), "must be discoverable after creation");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("checkConflict flags an existing profile paired with an already existing file")
    void checkConflictFlagsExistingPair() throws IOException {
        String name = "wiz_conflict_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        try {
            SqliteDatabaseProfileWizard.createProfile(name, "signum.sqlite.db");
            // The database file of the profile (SQLite would create it on first
            // write; the conflict check sees the on-disk file):
            Files.createFile(dir.resolve("signum.sqlite.db"));
            // The (profile, existing file) pair is a conflict...
            String conflict = SqliteDatabaseProfileWizard.checkConflict(name, "signum.sqlite.db");
            assertNotNull(conflict, "existing profile + existing file must conflict");
            assertTrue(conflict.contains("please change the database profile name or the database file name"),
                    "the conflict message must tell the user to change a name: " + conflict);
            // ...but a NEW file into the existing profile is free (the new purpose)...
            assertNull(SqliteDatabaseProfileWizard.checkConflict(name, "second.db"),
                    "a not-yet-existing file into an existing profile is free");
            // ...as is a brand-new profile.
            assertNull(SqliteDatabaseProfileWizard.checkConflict("wiz_new_" + System.nanoTime(), "signum.sqlite.db"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("createProfile into an EXISTING profile leaves profile.json untouched (no overwrite)")
    void createProfileIntoExistingProfileKeepsProfileJson() throws IOException {
        String name = "wiz_existing_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        try {
            SqliteDatabaseProfileWizard.createProfile(name, "signum.sqlite.db");
            Path profileJson = dir.resolve("profile.json");
            String before = Files.readString(profileJson);

            // Place a new database file into the existing profile (no file on disk yet:
            // SQLite creates it on first write — only the directory must be ensured).
            assertEquals(name, SqliteDatabaseProfileWizard.createProfile(name, "second.db"));
            assertEquals(before, Files.readString(profileJson),
                    "the existing profile's settings must stay untouched");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("the dialog constructs with the SSOT default file and an empty name (on the EDT)")
    void dialogConstructsWithDefaults() throws Exception {
        @SuppressWarnings("unchecked")
        final SqliteDatabaseProfileWizard[] holder = new SqliteDatabaseProfileWizard[1];
        final Object[] out = new Object[4];
        SwingUtilities.invokeAndWait(() -> {
            SqliteDatabaseProfileWizard wizard = new SqliteDatabaseProfileWizard(null);
            holder[0] = wizard;
            out[0] = wizard.getTitle();
            out[1] = comboText(wizard.getDbFileField());
            out[2] = comboText(wizard.getNameField());
            out[3] = wizard.getCreatedProfileName();
        });
        try {
            assertEquals(SqliteDatabaseProfileWizard.TITLE, out[0]);
            assertEquals(ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME, out[1]);
            assertEquals("", out[2]);
            assertNull(out[3], "no profile is created until Create is pressed");
        } finally {
            SwingUtilities.invokeAndWait(holder[0]::dispose);
        }
    }

    @Test
    @DisplayName("the dialog's selects are editable dropdowns listing profiles / db files (on the EDT)")
    void dialogSelectsListProfilesAndDbFiles() throws Exception {
        String name = "wiz_listing_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        final Object[] out = new Object[4]; // profile listed, file has second.db, file has default
        try {
            SqliteDatabaseProfileWizard.createProfile(name, "signum.sqlite.db");
            Files.createFile(dir.resolve("second.db")); // a second *.db file of the profile

            SwingUtilities.invokeAndWait(() -> {
                SqliteDatabaseProfileWizard wizard = new SqliteDatabaseProfileWizard(null);
                try {
                    javax.swing.JComboBox<String> nameCombo = wizard.getNameField();
                    javax.swing.JComboBox<String> fileCombo = wizard.getDbFileField();
                    assertTrue(nameCombo.isEditable(), "the profile select must be editable");
                    assertTrue(fileCombo.isEditable(), "the file select must be editable");
                    boolean listed = false;
                    for (int i = 0; i < nameCombo.getItemCount(); i++) {
                        if (name.equals(nameCombo.getItemAt(i))) {
                            listed = true;
                        }
                    }
                    out[0] = listed;
                    // Select the existing profile: the file list must refresh to its *.db files.
                    ((javax.swing.JTextField) nameCombo.getEditor().getEditorComponent()).setText(name);
                    boolean hasSecond = false;
                    boolean hasDefault = false;
                    for (int i = 0; i < fileCombo.getItemCount(); i++) {
                        String item = fileCombo.getItemAt(i);
                        if ("second.db".equals(item)) {
                            hasSecond = true;
                        }
                        if (ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME.equals(item)) {
                            hasDefault = true;
                        }
                    }
                    out[1] = hasSecond;
                    out[2] = hasDefault;
                } finally {
                    wizard.dispose();
                }
            });
            assertTrue((Boolean) out[0], "the created profile must be in the profile dropdown");
            assertTrue((Boolean) out[1], "the profile's second.db must be in the file dropdown");
            assertTrue((Boolean) out[2], "the default file name must stay offered");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("the description/preview/status text is left-aligned with the form (not floating right)")
    void displayTextIsLeftAligned() throws Exception {
        final java.util.List<java.awt.Component> panes = new java.util.ArrayList<>();
        final java.awt.Component[] content = new java.awt.Component[1];
        SwingUtilities.invokeAndWait(() -> {
            SqliteDatabaseProfileWizard w = new SqliteDatabaseProfileWizard(null);
            ((javax.swing.JTextField) w.getNameField().getEditor().getEditorComponent()).setText("valami");
            w.pack();
            w.validate();
            content[0] = w.getContentPane();
            collectTextPanes(w.getContentPane(), panes);
            w.dispose();
        });
        assertEquals(3, panes.size(), "the three display text panes (description, preview, status)");
        for (java.awt.Component pane : panes) {
            assertTrue(absXWithin(pane, content[0]) <= 30,
                    "the display text pane must be left-aligned with the form (at the content's left edge)");
        }
    }

    @Test
    @DisplayName("a multi-line warning does not move the preview or status rows")
    void warningDoesNotMovePreviewRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SqliteDatabaseProfileWizard wizard = new SqliteDatabaseProfileWizard(null);
            try {
                ((javax.swing.JTextField) wizard.getNameField().getEditor().getEditorComponent())
                        .setText("valami");
                wizard.pack();
                wizard.validate();

                java.util.List<java.awt.Component> panes = new java.util.ArrayList<>();
                collectTextPanes(wizard.getContentPane(), panes);
                assertEquals(3, panes.size());
                javax.swing.JEditorPane preview = (javax.swing.JEditorPane) panes.get(1);
                javax.swing.JEditorPane status = (javax.swing.JEditorPane) panes.get(2);
                java.awt.Container previewSlot = preview.getParent();
                java.awt.Container statusSlot = status.getParent();
                java.awt.Container previewBlock = previewSlot.getParent();
                assertEquals(previewBlock, statusSlot.getParent());
                previewBlock.setSize(previewBlock.getPreferredSize());
                previewBlock.getLayout().layoutContainer(previewBlock);
                previewSlot.doLayout();
                statusSlot.doLayout();

                int previewY = previewSlot.getY() + preview.getY();
                int statusY = statusSlot.getY() + status.getY();
                status.setText(WizardTexts.wrap(
                        "The database file already exists in this SQLite profile. "
                                + "Choose a different profile name or database file name to continue."));
                previewBlock.getLayout().layoutContainer(previewBlock);
                previewSlot.doLayout();
                statusSlot.doLayout();

                assertEquals(previewY, previewSlot.getY() + preview.getY(),
                        "the preview stays in its reserved row");
                assertEquals(statusY, statusSlot.getY() + status.getY(),
                        "the warning stays in its reserved row");
            } finally {
                wizard.dispose();
            }
        });
    }

    @Test
    @DisplayName("new-profile-only mode rejects an existing profile name")
    void newProfileOnlyModeRejectsExistingProfile() throws Exception {
        String name = "wiz_new_only_" + System.nanoTime();
        Path dir = sqliteProfileDir(name);
        try {
            SqliteDatabaseProfileWizard.createProfile(name, "existing.db");
            SwingUtilities.invokeAndWait(() -> {
                SqliteDatabaseProfileWizard wizard = null;
                try {
                    Constructor<SqliteDatabaseProfileWizard> constructor =
                            SqliteDatabaseProfileWizard.class.getDeclaredConstructor(Window.class, boolean.class);
                    constructor.setAccessible(true);
                    wizard = constructor.newInstance(null, false);
                    assertEquals(0, wizard.getNameField().getItemCount(),
                            "existing profiles should not be offered as targets");
                    ((javax.swing.JTextField) wizard.getNameField().getEditor().getEditorComponent()).setText(name);

                    Method create = SqliteDatabaseProfileWizard.class.getDeclaredMethod("onCreate");
                    create.setAccessible(true);
                    create.invoke(wizard);

                    assertNull(wizard.getCreatedProfileName(),
                            "typing an existing profile name must not add a database to it");
                    java.lang.reflect.Field statusField =
                            SqliteDatabaseProfileWizard.class.getDeclaredField("status");
                    statusField.setAccessible(true);
                    javax.swing.JEditorPane status = (javax.swing.JEditorPane) statusField.get(wizard);
                    assertTrue(status.getText().contains("choose a new"), status.getText());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                } finally {
                    if (wizard != null) {
                        wizard.dispose();
                    }
                }
            });
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void collectTextPanes(java.awt.Component c, java.util.List<java.awt.Component> out) {
        if (c instanceof javax.swing.JEditorPane) {
            out.add(c);
        }
        if (c instanceof java.awt.Container cont) {
            for (java.awt.Component child : cont.getComponents()) {
                collectTextPanes(child, out);
            }
        }
    }

    /** The component's x offset accumulated up to (but not including) the given root. */
    private static int absXWithin(java.awt.Component c, java.awt.Component root) {
        int x = 0;
        for (java.awt.Component cur = c; cur != null && cur != root; cur = cur.getParent()) {
            x += cur.getX();
        }
        return x;
    }

    /** The profile directory of the given name (SSOT: the same base SQLiteProfile resolves). */
    private static Path sqliteProfileDir(String name) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve("SQLite").resolve(name);
    }

    /** The visible (typed or selected) text of the wizard's editable combo fields. */
    private static String comboText(javax.swing.JComboBox<?> combo) {
        Object editorComponent = combo.getEditor() == null ? null : combo.getEditor().getEditorComponent();
        if (editorComponent instanceof javax.swing.JTextField field) {
            return field.getText();
        }
        return combo.getSelectedItem() == null ? "" : String.valueOf(combo.getSelectedItem());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup: the unique name keeps the tree clean
                }
            });
        }
    }
}