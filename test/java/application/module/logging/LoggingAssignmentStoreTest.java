package application.module.logging;

import application.utils.config.ModuleIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LoggingAssignmentStore}.
 * <p>
 * Verifies canonical read/write, per-module updates, clearing, and read-migration
 * from the two legacy mechanisms ({@code profileLinks} and the {@code logging.preset}
 * property), plus reverse lookup.
 */
@DisplayName("LoggingAssignmentStore Tests")
class LoggingAssignmentStoreTest {

    @TempDir
    Path tempDir;

    private LoggingAssignmentStore store;

    @BeforeEach
    void setUp() {
        store = new LoggingAssignmentStore(tempDir.toString());
    }

    @Test
    @DisplayName("setAssignment then getAssignment round-trips the canonical map")
    void canonical_roundTrips() {
        store.setAssignment("mainnet", Map.of("node", "standard", "database", "verbose"));

        Map<String, String> got = store.getAssignment("mainnet");
        assertEquals("standard", got.get("node"));
        assertEquals("verbose", got.get("database"));
        assertTrue(store.isManaged("mainnet"));
    }

    @Test
    @DisplayName("setAssignmentForModule updates a single module entry")
    void setForModule_updatesEntry() {
        store.setAssignment("mainnet", Map.of("node", "standard", "database", "verbose"));

        store.setAssignmentForModule("mainnet", "database", "quiet");

        Map<String, String> got = store.getAssignment("mainnet");
        assertEquals("quiet", got.get("database"));
        assertEquals("standard", got.get("node"));
    }

    @Test
    @DisplayName("setAssignmentForModule with null clears that module")
    void setForModule_nullClearsEntry() {
        store.setAssignment("mainnet", Map.of("node", "standard", "database", "verbose"));

        store.setAssignmentForModule("mainnet", "database", null);

        Map<String, String> got = store.getAssignment("mainnet");
        assertFalse(got.containsKey("database"));
        assertEquals("standard", got.get("node"));
    }

    @Test
    @DisplayName("clearAssignment empties the assignment")
    void clearAssignment_empties() {
        store.setAssignment("mainnet", Map.of("node", "standard"));

        store.clearAssignment("mainnet");

        assertTrue(store.getAssignment("mainnet").isEmpty());
        assertFalse(store.isManaged("mainnet"));
    }

    @Test
    @DisplayName("legacy profileLinks (mechanism 3) is read-migrated to the node module")
    void legacyProfileLinks_readMigrated() throws IOException {
        Path nodeDir = tempDir.resolve(ModuleIds.NODE);
        Files.createDirectories(nodeDir);
        Files.writeString(nodeDir.resolve("profile.json"),
                "{\"profileLinks\":{\"mainnet\":{\"logging\":\"verbose\"}}}");

        Map<String, String> got = store.getAssignment("mainnet");
        assertEquals("verbose", got.get(ModuleIds.NODE));
    }

    @Test
    @DisplayName("legacy logging.preset property (mechanism 1) is read-migrated to the node module")
    void legacyPresetProperty_readMigrated() throws IOException {
        Path profilesDir = tempDir.resolve(ModuleIds.NODE).resolve("profiles");
        Files.createDirectories(profilesDir);
        Files.writeString(profilesDir.resolve("legacyone.properties"), "logging.preset=debug\n");

        Map<String, String> got = store.getAssignment("legacyone");
        assertEquals("debug", got.get(ModuleIds.NODE));
    }

    @Test
    @DisplayName("canonical assignment takes precedence over legacy sources")
    void canonical_precedenceOverLegacy() throws IOException {
        // Legacy says "verbose"
        Path nodeDir = tempDir.resolve(ModuleIds.NODE);
        Files.createDirectories(nodeDir);
        Files.writeString(nodeDir.resolve("profile.json"),
                "{\"profileLinks\":{\"mainnet\":{\"logging\":\"verbose\"}}}");

        // Canonical says "standard"
        store.setAssignment("mainnet", Map.of("node", "standard"));

        Map<String, String> got = store.getAssignment("mainnet");
        assertEquals("standard", got.get(ModuleIds.NODE));
    }

    @Test
    @DisplayName("findReferencingProfiles lists profiles whose assignment references the name")
    void findReferencingProfiles_listsMatches() throws IOException {
        Path profilesDir = tempDir.resolve(ModuleIds.NODE).resolve("profiles");
        Files.createDirectories(profilesDir);
        Files.writeString(profilesDir.resolve("mainnet.properties"), "n=1\n");
        Files.writeString(profilesDir.resolve("testnet.properties"), "n=1\n");

        store.setAssignment("mainnet", Map.of("node", "verbose"));
        store.setAssignment("testnet", Map.of("node", "quiet"));

        List<String> refs = store.findReferencingProfiles("verbose");
        assertEquals(List.of("mainnet"), refs);

        assertTrue(store.findReferencingProfiles("does-not-exist").isEmpty());
    }

    @Test
    @DisplayName("listNodeProfiles discovers profiles and excludes reserved defaults")
    void listNodeProfiles_discoversAndExcludesReserved() throws IOException {
        Path profilesDir = tempDir.resolve(ModuleIds.NODE).resolve("profiles");
        Files.createDirectories(profilesDir);
        Files.writeString(profilesDir.resolve("node.properties"), "n=1\n");
        Files.writeString(profilesDir.resolve("node-default.properties"), "n=1\n");
        Files.writeString(profilesDir.resolve("mainnet.properties"), "n=1\n");
        Files.writeString(profilesDir.resolve("testnet.properties"), "n=1\n");

        List<String> profiles = store.listNodeProfiles();
        assertTrue(profiles.contains("mainnet"));
        assertTrue(profiles.contains("testnet"));
        assertFalse(profiles.contains("node"));
        assertFalse(profiles.contains("node-default"));
    }

    @Test
    @DisplayName("resolveEffectiveForModule returns the assigned profile when it resolves")
    void resolveEffectiveForModule_valid() throws IOException {
        new LoggingProfileRepository(tempDir.toString()).create(ModuleIds.NODE, "myprofile", null);
        store.setAssignment("mainnet", Map.of(ModuleIds.NODE, "myprofile"));
        assertEquals("myprofile", store.resolveEffectiveForModule("mainnet", ModuleIds.NODE));
    }

    @Test
    @DisplayName("resolveEffectiveForModule falls back to the default when the assigned profile is missing")
    void resolveEffectiveForModule_invalidFallsBackToDefault() {
        store.setAssignment("mainnet", Map.of(ModuleIds.NODE, "deleted-profile"));
        assertEquals("logging-default", store.resolveEffectiveForModule("mainnet", ModuleIds.NODE));
    }

    @Test
    @DisplayName("resolveEffectiveForModule falls back to the default when there is no assignment")
    void resolveEffectiveForModule_noAssignmentFallsBackToDefault() {
        assertEquals("logging-default", store.resolveEffectiveForModule("mainnet", ModuleIds.NODE));
        assertEquals("logging-default", store.resolveEffectiveForModule("no-such-profile", ModuleIds.NODE));
    }
}