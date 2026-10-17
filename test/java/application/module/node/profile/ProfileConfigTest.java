package application.module.node.profile;

import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.utils.config.ModuleIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ProfileConfig} reload-on-write contract: every
 * mutating method starts from a fresh disk read, so a long-lived instance
 * (e.g. the GUI's app-lifetime tab-order instance in {@code NodePanel}) can
 * never serialize a stale snapshot and clobber a write made by another
 * {@link ProfileConfig} instance (the creation-time logging seed being the
 * canonical victim — it was wiped by the following tab-order save in the field).
 * <p>
 * Uses an explicit {@code confRoot} sandbox (a JUnit {@link TempDir}) so the
 * tests never touch the real {@code conf/} tree. Follows AAA + JUnit 5
 * conventions.
 */
@DisplayName("ProfileConfig Tests")
class ProfileConfigTest {

    @TempDir
    Path tempDir;

    private String confRoot() {
        return tempDir.toString();
    }

    @Test
    @DisplayName("the creation-time seed survives a stale long-lived ProfileConfig tab-order save (NodePanel clobber)")
    void createEmpty_SeededAssignmentSurvivesStaleTabOrderSave() throws IOException {
        // Arrange — an applied (on-disk) logging profile of the node module.
        LoggingProfileRepository repo = new LoggingProfileRepository(confRoot());
        String appliedName = "zz-seed-applied";
        repo.create(ModuleIds.NODE, appliedName, new Properties());
        repo.setApplied(ModuleIds.NODE, appliedName);
        try {
            // A long-lived ProfileConfig instance loaded BEFORE the creation —
            // the NodePanel's tab-order instance lives for the whole app: its
            // in-memory cache holds the pre-creation state of profiles.json.
            ProfileConfig longLived = new ProfileConfig(
                    tempDir.resolve("node").resolve("profiles.json"));
            List<String> order = new ArrayList<>(
                    longLived.getTabOrder() == null ? List.of() : longLived.getTabOrder());

            // Act — create the profile (which seeds the applied profile) and
            // then the GUI's addProfileTab tab-order save against the stale
            // instance (the exact sequence that clobbered the seed in the
            // field: create + seed first, tab-order save a moment later).
            String created = ProfileRuntimeService.createEmptyProfile(confRoot(), "node");
            order.add(created);
            longLived.setTabOrder(order);

            // Assert — the seeded assignment must survive the stale save.
            assertEquals(appliedName,
                    new LoggingAssignmentStore(confRoot()).getAssignment(created).get(ModuleIds.NODE),
                    "a stale long-lived ProfileConfig save must not discard the creation-time seed");
        } finally {
            repo.setApplied(ModuleIds.NODE, null);
            repo.delete(ModuleIds.NODE, appliedName);
        }
    }

    @Test
    @DisplayName("legacy node metadata migrates to profiles.json and is removed after a successful save")
    void legacyMetadata_migratesToCanonicalFile() throws IOException {
        Path nodeDir = tempDir.resolve("node");
        Files.createDirectories(nodeDir);
        Files.writeString(nodeDir.resolve("profile.json"),
                "{\"appliedProfile\":\"mainnet\",\"profileLinks\":{\"mainnet\":{"
                        + "\"logging\":\"verbose\",\"database\":\"MariaDB:db1\","
                        + "\"dbAutoStart\":true,\"dbAutoStop\":false}}}");
        Path profilesJson = nodeDir.resolve("profiles.json");

        ProfileConfig config = new ProfileConfig(profilesJson);
        assertEquals("mainnet", config.load().getAppliedProfile());
        assertEquals(Map.of(ModuleIds.NODE, "verbose"), config.getLoggingPresets("mainnet"));
        assertEquals("MariaDB:db1", config.getDatabaseProfileLink("mainnet"));
        assertEquals(Boolean.TRUE, config.getDatabaseAutoStart("mainnet"));
        assertEquals(Boolean.FALSE, config.getDatabaseAutoStop("mainnet"));
        assertFalse(Files.exists(nodeDir.resolve("profile.json")));
        assertTrue(Files.exists(profilesJson));
    }

    @Test
    @DisplayName("canonical profile metadata wins over conflicting legacy fields")
    void legacyMetadata_doesNotOverrideCanonicalValues() throws IOException {
        Path nodeDir = tempDir.resolve("node");
        Files.createDirectories(nodeDir);
        Path profilesJson = nodeDir.resolve("profiles.json");
        Files.writeString(profilesJson,
                "{\"appliedProfile\":\"canonical\",\"profiles\":{\"mainnet\":{"
                        + "\"loggingPresets\":{\"node\":\"standard\"},"
                        + "\"databaseProfileLink\":\"PostgreSQL:canonical\","
                        + "\"databaseAutoStart\":false,\"databaseAutoStop\":true}}}");
        Files.writeString(nodeDir.resolve("profile.json"),
                "{\"appliedProfile\":\"legacy\",\"profileLinks\":{\"mainnet\":{"
                        + "\"logging\":\"verbose\",\"database\":\"MariaDB:legacy\","
                        + "\"dbAutoStart\":true,\"dbAutoStop\":false}}}");

        ProfileConfig config = new ProfileConfig(profilesJson);
        assertEquals("canonical", config.load().getAppliedProfile());
        assertEquals(Map.of(ModuleIds.NODE, "standard"), config.getLoggingPresets("mainnet"));
        assertEquals("PostgreSQL:canonical", config.getDatabaseProfileLink("mainnet"));
        assertEquals(Boolean.FALSE, config.getDatabaseAutoStart("mainnet"));
        assertEquals(Boolean.TRUE, config.getDatabaseAutoStop("mainnet"));
        assertFalse(Files.exists(nodeDir.resolve("profile.json")));
    }

    @Test
    @DisplayName("legacy server links and auto flags are discarded for a SQLite node profile")
    void legacyMetadata_discardsDatabaseLinksForSqliteProfiles() throws IOException {
        Path nodeDir = tempDir.resolve("node");
        Path profileDir = nodeDir.resolve("profiles");
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("mainnet.properties"),
                "DB.Url=jdbc:sqlite:file:./database/SQLite/mainnet/signum.sqlite.db");
        Path legacy = nodeDir.resolve("profile.json");
        Files.writeString(legacy,
                "{\"profileLinks\":{\"mainnet\":{\"database\":\"MariaDB:stale\","
                        + "\"dbAutoStart\":true,\"dbAutoStop\":true}}}");

        ProfileConfig config = new ProfileConfig(nodeDir.resolve("profiles.json"));
        assertNull(config.getDatabaseProfileLink("mainnet"));
        assertNull(config.getDatabaseAutoStart("mainnet"));
        assertNull(config.getDatabaseAutoStop("mainnet"));
        assertFalse(Files.exists(legacy));
    }

    @Test
    @DisplayName("unknown legacy metadata is retained instead of being discarded")
    void legacyMetadata_retainsUnknownFields() throws IOException {
        Path nodeDir = tempDir.resolve("node");
        Files.createDirectories(nodeDir);
        Path legacy = nodeDir.resolve("profile.json");
        Files.writeString(legacy,
                "{\"appliedProfile\":\"mainnet\",\"futureOption\":{\"enabled\":true}}");

        ProfileConfig config = new ProfileConfig(nodeDir.resolve("profiles.json"));
        assertEquals("mainnet", config.load().getAppliedProfile());
        assertTrue(Files.exists(legacy));
        assertTrue(Files.readString(legacy).contains("futureOption"));
    }
}
