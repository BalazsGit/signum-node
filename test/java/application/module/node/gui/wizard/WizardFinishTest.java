package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.props.Props;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link WizardFinish} — the SQLite {@code DB.Url} mapping decision at
 * profile creation (the user-configured DB.Url of the database step wins, an
 * untouched setup derives the per-profile SSOT URL from the final name).
 * <p>
 * E2E probe pattern (like {@code ProfileCreateDefaultsTest}): a unique profile is
 * created in the default conf root and fully removed afterwards (profile file +
 * companion SQLite data directory).
 * </p>
 */
@DisplayName("WizardFinish (context → profile persistence)")
class WizardFinishTest {

    @Test
    @DisplayName("SQLite: an untouched trio → the per-profile SSOT DB.Url derived from the final name")
    void sqliteUntouchedTrioUsesPerProfileUrl() throws Exception {
        String name = "wizard_probe_" + System.nanoTime();
        try (Probe ignored = new Probe(name)) {
            assertEquals(name, WizardFinish.createProfile(context(name, null, null)));
            assertEquals(ProfileCreateDefaults.sqliteDbUrl(name), dbUrlOf(name));
        }
    }

    @Test
    @DisplayName("SQLite: an edited trio → the composed DB.Url is persisted")
    void sqliteEditedTrioUsesComposedUrl() throws Exception {
        String name = "wizard_probe_" + System.nanoTime();
        String dbUrl = "jdbc:sqlite:file:./database/SQLite/" + name + "/custom.sqlite.db";
        try (Probe ignored = new Probe(name)) {
            assertEquals(name, WizardFinish.createProfile(context(name, dbUrl, name)));
            assertEquals(dbUrl, dbUrlOf(name));
        }
    }

    private static WizardContext context(String name, String sqliteDbUrl, String sqliteDbProfileName) {
        WizardContext c = new WizardContext();
        c.setName(name);
        c.setEngine(DatabaseEngine.SQLITE);
        c.setStartImmediately(false); // never start the probe node
        c.setSqliteDbUrl(sqliteDbUrl);
        c.setSqliteDbProfileName(sqliteDbProfileName);
        return c;
    }

    private static String dbUrlOf(String name) {
        NodeProfile p = NodeProfileRepository.loadByName(name);
        return p.getProperty(Props.DB_URL.getName());
    }

    /** Cleanup guard: removes the probe profile and its companion SQLite data directory. */
    private static final class Probe implements AutoCloseable {
        private final String name;

        Probe(String name) {
            this.name = name;
        }

        @Override
        public void close() throws IOException {
            try {
                NodeProfileRepository.deleteProfile(name);
            } catch (Exception ignored) {
                // the probe profile may not exist on failure paths
            }
            deleteRecursively(Path.of("database", "SQLite", name));
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        }
    }
}
