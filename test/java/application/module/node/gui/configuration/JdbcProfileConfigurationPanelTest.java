package application.module.node.gui.configuration;

import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JdbcProfileConfigurationPanel#applyLinkedProfile} — the
 * "Linked Database Profile" card must materialize the linked profile's FULL
 * configuration: engine, profile, the database instance (the one the node's
 * saved JDBC URL points at, otherwise the first) and the matching user, so
 * the dependent rows and the URL preview never show a half-empty card.
 * <p>
 * The profile area is the real {@code ./database} directory (the utility
 * resolves it relative to the working directory); each test creates uniquely
 * named profiles and removes them again.
 */
@DisplayName("JdbcProfileConfigurationPanel (linked profile materialization)")
class JdbcProfileConfigurationPanelTest {

    private final List<Path> createdDirs = new ArrayList<>();
    private JdbcProfileConfigurationPanel panel;
    private AtomicInteger changeCount;

    @BeforeEach
    void setUp() {
        changeCount = new AtomicInteger();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Path dir : createdDirs) {
            Files.deleteIfExists(dir.resolve("profile.json"));
            Files.deleteIfExists(dir);
        }
        createdDirs.clear();
    }

    private static void onEdt(Runnable action) {
        final AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    error.set(t);
                }
            });
        } catch (Exception e) {
            error.set(e);
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private void createProfile(String engineDir, String name, String json) throws Exception {
        Path dir = Path.of("database", engineDir, name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("profile.json"), json);
        createdDirs.add(dir);
    }

    private void buildPanel() {
        final AtomicReference<JdbcProfileConfigurationPanel> ref = new AtomicReference<>();
        onEdt(() -> ref.set(new JdbcProfileConfigurationPanel("./conf", changeCount::incrementAndGet)));
        panel = ref.get();
    }

    @Test
    @DisplayName("a linked SQLite profile materializes the whole card (instance + preview) without onChange")
    void linkedProfileMaterializesTheWholeCard() throws Exception {
        String name = "mat-sqlite-" + System.nanoTime();
        String url = "jdbc:sqlite:file:./database/SQLite/" + name + "/signum.sqlite.db";
        createProfile("SQLite", name, "{\"DB.Url\": \"" + url + "\"}");
        buildPanel();

        final AtomicReference<Boolean> applied = new AtomicReference<>();
        onEdt(() -> applied.set(panel.applyLinkedProfile(
                DatabaseConfigurationPanel.DatabaseEngine.SQLITE, name, url, null)));
        assertTrue(applied.get(), "the existing profile must be applied");

        onEdt(() -> {
            assertEquals(DatabaseConfigurationPanel.DatabaseEngine.SQLITE,
                    ((JComboBox<?>) panel.getEngineCombo()).getSelectedItem(),
                    "the engine row shows the linked engine");
            assertEquals(name, ((JComboBox<?>) panel.getProfileCombo()).getSelectedItem(),
                    "the profile row shows the linked profile");
            Object db = ((JComboBox<?>) panel.getDbCombo()).getSelectedItem();
            assertNotNull(db, "the database instance must be selected");
            assertEquals(url, ((DatabaseConfigurationUtils.DbInstance) db).url,
                    "the instance the node's saved URL points at must be selected");
            assertEquals(url, panel.getJdbcUrl(),
                    "the card composes the linked profile's real JDBC URL");
            assertEquals(0, changeCount.get(),
                    "a programmatic link application must not fire onChange");
        });

        onEdt(() -> applied.set(panel.applyLinkedProfile(
                DatabaseConfigurationPanel.DatabaseEngine.SQLITE, name, url, null)));
        assertTrue(applied.get(), "re-applying the same link must stay idempotent");
    }

    @Test
    @DisplayName("the preferred database instance and user are selected for a server engine")
    void preferredInstanceAndUserAreSelected() throws Exception {
        String name = "mat-pg-" + System.nanoTime();
        String url = "jdbc:postgresql://localhost:5432/two";
        String json = "{\"databases\": ["
                + "{\"name\": \"one\", \"url\": \"jdbc:postgresql://localhost:5432/one\"},"
                + "{\"name\": \"two\", \"url\": \"" + url + "\", \"users\": ["
                + "{\"username\": \"alice\", \"password\": \"a-pw\"},"
                + "{\"username\": \"bob\", \"password\": \"b-pw\"}]}]}";
        createProfile("PostgreSQL", name, json);
        buildPanel();

        final AtomicReference<Boolean> applied = new AtomicReference<>();
        onEdt(() -> applied.set(panel.applyLinkedProfile(
                DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL, name, url, "bob")));
        assertTrue(applied.get(), "the existing profile must be applied");

        onEdt(() -> {
            DatabaseConfigurationUtils.DbInstance db = (DatabaseConfigurationUtils.DbInstance) ((JComboBox<?>) panel
                    .getDbCombo()).getSelectedItem();
            assertEquals("two", db.name,
                    "the instance matching the preferred URL must be selected (not the first one)");
            DatabaseConfigurationUtils.DbUser user = (DatabaseConfigurationUtils.DbUser) ((JComboBox<?>) panel
                    .getUserCombo()).getSelectedItem();
            assertEquals("bob", user.username, "the user matching the preferred username must be selected");
            assertEquals("b-pw", panel.getPassword(), "the password follows the selected user");
            assertEquals("localhost", ((JComboBox<?>) panel.getHostField()).getSelectedItem(),
                    "the host is parsed from the instance URL");
            assertEquals("5432", ((JTextField) panel.getPortField()).getText(),
                    "the port is parsed from the instance URL");
            assertEquals(url, panel.getJdbcUrl(), "the card composes the linked profile's real JDBC URL");
        });
    }

    @Test
    @DisplayName("an unknown engine or a missing profile is rejected without touching the card")
    void invalidLinksAreRejected() throws Exception {
        String name = "mat-inv-" + System.nanoTime();
        createProfile("SQLite", name,
                "{\"DB.Url\": \"jdbc:sqlite:./database/SQLite/" + name + "/signum.sqlite.db\"}");
        buildPanel();

        final AtomicReference<Boolean> nullEngine = new AtomicReference<>();
        onEdt(() -> nullEngine.set(panel.applyLinkedProfile(null, name, null, null)));
        assertFalse(nullEngine.get(), "a null engine must be rejected");

        final AtomicReference<Boolean> missingProfile = new AtomicReference<>();
        onEdt(() -> missingProfile.set(panel.applyLinkedProfile(
                DatabaseConfigurationPanel.DatabaseEngine.SQLITE, "missing-" + System.nanoTime(), null, null)));
        assertFalse(missingProfile.get(), "a missing profile must be rejected");

        onEdt(() -> {
            assertEquals(DatabaseConfigurationPanel.DatabaseEngine.SQLITE,
                    ((JComboBox<?>) panel.getEngineCombo()).getSelectedItem(), "the engine stays at its default");
            Object profileSel = ((JComboBox<?>) panel.getProfileCombo()).getSelectedItem();
            assertTrue(profileSel == null || "".equals(profileSel), "the profile row stays empty");
        });
    }

    @Test
    @DisplayName("clearLinkedProfile resets the card to its neutral state")
    void clearResetsTheCard() throws Exception {
        String name = "mat-clr-" + System.nanoTime();
        String url = "jdbc:sqlite:file:./database/SQLite/" + name + "/signum.sqlite.db";
        createProfile("SQLite", name, "{\"DB.Url\": \"" + url + "\"}");
        buildPanel();

        onEdt(() -> {
            assertTrue(panel.applyLinkedProfile(
                    DatabaseConfigurationPanel.DatabaseEngine.SQLITE, name, url, null));
            panel.clearLinkedProfile();
            assertEquals("", ((JComboBox<?>) panel.getProfileCombo()).getSelectedItem(),
                    "the profile row is reset to the empty entry");
            assertNull(((JComboBox<?>) panel.getDbCombo()).getSelectedItem(),
                    "no database instance is selected");
            assertNull(((JComboBox<?>) panel.getUserCombo()).getSelectedItem(),
                    "no user is selected");
        });
    }
}