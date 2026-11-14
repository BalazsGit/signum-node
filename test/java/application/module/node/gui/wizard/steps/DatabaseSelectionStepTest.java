package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardFinish;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.props.Props;
import application.utils.io.PathUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JRadioButton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DatabaseSelectionStep}: the database-engine selection. The
 * database source (a dedicated URL vs. a linked database profile) is decided on
 * the next, database-configuration step — the "Linked Database Profile"
 * checkbox — so this step only picks the engine. The downstream finish
 * resolution of a linked database profile is covered too. Headless: sandbox
 * profiles are created in the app's {@code ./database} area and cleaned up.
 */
@DisplayName("DatabaseSelectionStep (engine selection)")
class DatabaseSelectionStepTest {

    @BeforeAll
    static void registerIconFont() {
        // The application registers the icon font at startup; do the same here so
        // the engine header/logo glyphs resolve in headless construction.
        IconFontSwing.register(FontAwesome.getIconFont());
    }

    @Test
    @DisplayName("defaults to the SQLite engine and validate passes (an engine is always chosen)")
    void defaultsToSqliteAndValidatePasses() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        assertEquals(DatabaseEngine.SQLITE, step.getSelectedEngine(), "SQLite is the preselected engine");
        assertNull(step.validate(new WizardContext()), "an engine is always chosen — validation passes");
    }

    @Test
    @DisplayName("selecting an engine radio mirrors the engine into the bound context live")
    void engineRadioMirrorsIntoContext() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        WizardContext ctx = new WizardContext();
        step.bindContext(ctx);
        assertEquals(DatabaseEngine.SQLITE, ctx.getEngine(), "binding mirrors the preselected engine");
        findRadio(step.getPanel(), "MariaDB").doClick();
        assertEquals(DatabaseEngine.MARIADB, ctx.getEngine(), "the radio choice mirrors into the context live");
    }

    @Test
    @DisplayName("onExit persists the selected engine into the context")
    void onExitPersistsEngine() {
        DatabaseSelectionStep step = new DatabaseSelectionStep();
        findRadio(step.getPanel(), "MariaDB").doClick();
        WizardContext ctx = new WizardContext();
        step.onExit(ctx);
        assertEquals(DatabaseEngine.MARIADB, ctx.getEngine(), "the selected engine is persisted on exit");
    }

    @Test
    @DisplayName("the database-configuration step is never auto-skipped (the source is decided on it)")
    void connectionStepNeverSkips() {
        DatabaseConnectionStep step = new DatabaseConnectionStep();
        WizardContext withProfile = new WizardContext();
        withProfile.setSelectedDbProfileName("some-profile");
        assertFalse(step.autoSkip(withProfile), "a linked profile does not skip the step");
        assertFalse(step.autoSkip(new WizardContext()), "the step always runs (the source is chosen on it)");
    }

    @Test
    @DisplayName("finish resolves a selected SQLite profile into the node's DB.Url (no companion re-creation)")
    void finishResolvesSelectedSqliteProfile() throws IOException {
        String name = "g3_fin_sqlite_" + System.nanoTime();
        String dbUrl = "jdbc:sqlite:file:./database/SQLite/" + name + "/signum.sqlite.db";
        ProfileCreateDefaults.ensureSqliteDatabaseProfile(name, dbUrl);
        try {
            WizardContext ctx = new WizardContext();
            ctx.setName("node_" + name);
            ctx.setEngine(DatabaseEngine.SQLITE);
            ctx.setSelectedDbProfileName(name);
            Properties props = WizardFinish.buildProperties(ctx);
            assertEquals(dbUrl, props.getProperty(Props.DB_URL.getName()),
                    "the selected SQLite profile's own URL is the node's DB.Url");
        } finally {
            deleteRecursively(profileDir("SQLite", name));
        }
    }

    @Test
    @DisplayName("finish resolves a selected server profile into DB.Url + user credentials")
    void finishResolvesSelectedServerProfile() throws IOException {
        String name = "g3_fin_mariadb_" + System.nanoTime();
        String url = "jdbc:mariadb://db.example:3306/appdb";
        writeGenericProfile("MariaDB", name, url, "appuser", "apppass", "appdb");
        try {
            WizardContext ctx = new WizardContext();
            ctx.setName("node_" + name);
            ctx.setEngine(DatabaseEngine.MARIADB);
            ctx.setSelectedDbProfileName(name);
            Properties props = WizardFinish.buildProperties(ctx);
            assertEquals(url, props.getProperty(Props.DB_URL.getName()));
            assertEquals("appuser", props.getProperty(Props.DB_USERNAME.getName()));
            assertEquals("apppass", props.getProperty(Props.DB_PASSWORD.getName()));
        } finally {
            deleteRecursively(profileDir("MariaDB", name));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Path profileDir(String engine, String name) {
        return PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(engine).resolve(name);
    }

    /** Writes a profile in the generic {@code databases} format (the linkable discovery SSOT). */
    private static void writeGenericProfile(String engine, String name, String url,
                                            String user, String password, String db) throws IOException {
        Path dir = profileDir(engine, name);
        Files.createDirectories(dir);
        JsonObject userObj = new JsonObject();
        userObj.addProperty("username", user);
        userObj.addProperty("password", password);
        JsonArray users = new JsonArray();
        users.add(userObj);
        JsonObject instance = new JsonObject();
        instance.addProperty("name", db);
        instance.addProperty("url", url);
        instance.add("users", users);
        JsonArray dbs = new JsonArray();
        dbs.add(instance);
        JsonObject json = new JsonObject();
        json.add("databases", dbs);
        Files.writeString(dir.resolve("profile.json"), json.toString());
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignore) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignore) {
            // best-effort cleanup
        }
    }

    private static JRadioButton findRadio(JComponent root, String text) {
        for (java.awt.Component c : collectChildren(root)) {
            if (c instanceof JRadioButton rb && text.equals(rb.getText())) {
                return rb;
            }
        }
        throw new IllegalStateException("radio not found: " + text);
    }

    private static List<java.awt.Component> collectChildren(JComponent root) {
        List<java.awt.Component> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(java.awt.Component c, List<java.awt.Component> out) {
        if (c instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                out.add(child);
                collect(child, out);
            }
        }
    }
}
