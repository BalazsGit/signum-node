package application.module.node.profile;

import application.module.logging.LoggingAssignmentStore;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.utils.config.ModuleIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProfileRuntimeService}: {@code cloneProfile} (profile file
 * creation from a minimal-override payload + logging-association clone) and
 * {@code renameProfile} (state-preserving rename chain — file + metadata moves,
 * {@code DB.Url} rewrite / SQLite data-dir move, {@code profile.json} re-pointing,
 * callback ordering, name validation).
 * <p>
 * Uses an explicit {@code confRoot} sandbox (a JUnit {@link TempDir}) so the
 * tests never touch the real {@code conf/} tree. Follows AAA + JUnit 5
 * conventions.
 */
@DisplayName("ProfileRuntimeService Tests")
class ProfileRuntimeServiceTest {

    @TempDir
    Path tempDir;

    private String confRoot() {
        return tempDir.toString();
    }

    private Path profileFile(String name) {
        return tempDir.resolve("node").resolve("profiles").resolve(name + ".properties");
    }

    private void createSourceProfile() throws IOException {
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
    }

    @Test
    @DisplayName("creates the clone file with the minimal-override payload")
    void clone_CreatesProfileFileWithOverrides() throws IOException {
        // Arrange
        createSourceProfile();
        Properties overrides = new Properties();
        overrides.setProperty("API.Port", "9999");

        // Act
        String created = ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "mainnet_clone", overrides);

        // Assert
        assertEquals("mainnet_clone", created);
        assertTrue(Files.exists(profileFile("mainnet_clone")));
        NodeProfile clone = NodeProfileRepository.loadProfile(confRoot(), "mainnet_clone");
        assertEquals("9999", clone.getProperties().getProperty("API.Port"));
    }

    @Test
    @DisplayName("clones the source logging association for the node module")
    void clone_CopiesLoggingAssignment() throws IOException {
        // Arrange
        createSourceProfile();
        new LoggingAssignmentStore(confRoot())
                .setAssignmentForModule("mainnet", ModuleIds.NODE, "verbose");

        // Act
        ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "mainnet_clone", new Properties());

        // Assert
        Map<String, String> assignment = new LoggingAssignmentStore(confRoot()).getAssignment("mainnet_clone");
        assertEquals("verbose", assignment.get(ModuleIds.NODE));
    }

    @Test
    @DisplayName("leaves the clone without a logging assignment when the source has none")
    void clone_NoSourceAssignment_NoCloneAssignment() throws IOException {
        // Arrange
        createSourceProfile();

        // Act
        ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "mainnet_clone", new Properties());

        // Assert
        Map<String, String> assignment = new LoggingAssignmentStore(confRoot()).getAssignment("mainnet_clone");
        assertFalse(assignment.containsKey(ModuleIds.NODE));
    }

    @Test
    @DisplayName("leaves the source profile untouched (file + logging)")
    void clone_SourceUntouched() throws IOException {
        // Arrange
        Properties sourceProps = new Properties();
        sourceProps.setProperty("P2P.Port", "8123");
        NodeProfileRepository.createProfile(confRoot(), "mainnet", sourceProps);
        new LoggingAssignmentStore(confRoot())
                .setAssignmentForModule("mainnet", ModuleIds.NODE, "standard");

        // Act
        ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "mainnet_clone", new Properties());

        // Assert
        NodeProfile source = NodeProfileRepository.loadProfile(confRoot(), "mainnet");
        assertEquals("8123", source.getProperties().getProperty("P2P.Port"));
        assertEquals("standard", new LoggingAssignmentStore(confRoot()).getAssignment("mainnet").get(ModuleIds.NODE));
    }

    @Test
    @DisplayName("creates an empty profile when the payload is null or empty")
    void clone_NullOrEmptyPayload_CreatesEmptyProfile() throws IOException {
        // Arrange
        createSourceProfile();

        // Act
        ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "empty1", null);
        ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "empty2", new Properties());

        // Assert
        assertTrue(NodeProfileRepository.loadProfile(confRoot(), "empty1").getProperties().isEmpty());
        assertTrue(NodeProfileRepository.loadProfile(confRoot(), "empty2").getProperties().isEmpty());
    }

    @Test
    @DisplayName("rejects a duplicate name")
    void clone_DuplicateName_Throws() throws IOException {
        // Arrange
        createSourceProfile();

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "mainnet", new Properties()));
    }

    @Test
    @DisplayName("rejects a reserved name")
    void clone_ReservedName_Throws() throws IOException {
        // Arrange
        createSourceProfile();

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "node-default", new Properties()));
    }

    @Test
    @DisplayName("rejects an invalid or blank name")
    void clone_InvalidName_Throws() throws IOException {
        // Arrange
        createSourceProfile();

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "bad name!", new Properties()));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.cloneProfile(confRoot(), "mainnet", "   ", new Properties()));
    }

    @Test
    @DisplayName("rejects a blank source name")
    void clone_BlankSource_Throws() {
        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.cloneProfile(confRoot(), " ", "clone1", new Properties()));
    }

    @Test
    @DisplayName("the name suggester avoids the taken clone name")
    void suggester_AvoidsTakenCloneName() {
        // Arrange
        Set<String> taken = Set.of("mainnet", "mainnet_clone");

        // Act
        String suggested = ProfileNameSuggester.nextAvailableName("mainnet_clone", taken);

        // Assert
        assertEquals("mainnet_clone_01", suggested);
    }

    // ── renameProfile ───────────────────────────────────────────────────

    @Test
    @DisplayName("rename moves the file and updates tab order + logging association")
    void rename_MovesFileAndUpdatesMetadata() throws IOException {
        // Arrange
        Properties props = new Properties();
        props.setProperty("API.Port", "9001");
        NodeProfileRepository.createProfile(confRoot(), "mainnet", props);
        new LoggingAssignmentStore(confRoot())
                .setAssignmentForModule("mainnet", ModuleIds.NODE, "verbose");
        new ProfileConfig(profilesJson()).setTabOrder(new ArrayList<>(List.of("mainnet", "other")));

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", null);

        // Assert — file moved with its properties
        assertFalse(Files.exists(profileFile("mainnet")));
        assertTrue(Files.exists(profileFile("mainnet2")));
        assertEquals("9001", NodeProfileRepository.loadProfile(confRoot(), "mainnet2")
                .getProperties().getProperty("API.Port"));
        // Logging association re-keyed
        assertEquals("verbose", new LoggingAssignmentStore(confRoot()).getAssignment("mainnet2").get(ModuleIds.NODE));
        assertFalse(new LoggingAssignmentStore(confRoot()).getAssignment("mainnet").containsKey(ModuleIds.NODE));
        // Tab order re-keyed
        assertEquals(List.of("mainnet2", "other"), new ProfileConfig(profilesJson()).getTabOrder());
    }

    @Test
    @DisplayName("rename rewrites a per-profile SQLite DB.Url to the new name")
    void rename_RewritesSqliteDbUrl() throws IOException {
        // Arrange
        Properties props = new Properties();
        props.setProperty("DB.Url", ProfileCreateDefaults.sqliteDbUrl("mainnet"));
        NodeProfileRepository.createProfile(confRoot(), "mainnet", props);

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", null);

        // Assert
        assertEquals(ProfileCreateDefaults.sqliteDbUrl("mainnet2"),
                NodeProfileRepository.loadProfile(confRoot(), "mainnet2").getProperties().getProperty("DB.Url"));
    }

    @Test
    @DisplayName("rename leaves a server-DB (mariadb) DB.Url untouched")
    void rename_SkipsServerDbUrl() throws IOException {
        // Arrange
        String serverUrl = "jdbc:mariadb://dbhost:3306/signum";
        Properties props = new Properties();
        props.setProperty("DB.Url", serverUrl);
        NodeProfileRepository.createProfile(confRoot(), "mainnet", props);

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", null);

        // Assert
        assertEquals(serverUrl,
                NodeProfileRepository.loadProfile(confRoot(), "mainnet2").getProperties().getProperty("DB.Url"));
    }

    @Test
    @DisplayName("rename moves an existing SQLite data directory (and is a no-op when absent)")
    void rename_MovesExistingSqliteDataDir() throws IOException {
        // Arrange — the SQLite data dir is CWD-relative (./database/SQLite/<name>);
        // use unique names and clean up exactly what this test creates.
        Path from = ProfileCreateDefaults.sqliteDataDir("rrt_src");
        Path to = ProfileCreateDefaults.sqliteDataDir("rrt_dst");
        Files.createDirectories(from);
        Files.writeString(from.resolve("signum.sqlite.db"), "marker");
        try {
            // Act
            ProfileRuntimeService.moveSqliteDataDir("rrt_src", "rrt_dst");

            // Assert — moved with content
            assertFalse(Files.exists(from));
            assertTrue(Files.exists(to));
            assertEquals("marker", Files.readString(to.resolve("signum.sqlite.db")));
        } finally {
            if (Files.exists(to)) {
                Files.delete(to.resolve("signum.sqlite.db"));
                Files.delete(to);
            }
        }

        // No-op when the source dir does not exist (never-started profile)
        ProfileRuntimeService.moveSqliteDataDir("rrt_missing", "rrt_missing2");
        assertFalse(Files.exists(ProfileCreateDefaults.sqliteDataDir("rrt_missing2")));
    }

    @Test
    @DisplayName("rename re-points profile.json appliedProfile + profileLinks when they match the old name")
    void rename_UpdatesAppliedProfileWhenItMatches() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        writeProfileJson("{\"appliedProfile\" : \"mainnet\", \"profileLinks\" : {\"mainnet\" : {\"database\" : \"db1\"}}}");

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", null);

        // Assert — appliedProfile re-pointed, profileLinks entry moved, content preserved
        String json = Files.readString(profileJson());
        assertTrue(json.contains("\"appliedProfile\": \"mainnet2\""));
        assertTrue(json.contains("\"mainnet2\""));
        assertTrue(json.contains("\"db1\""));
        assertFalse(json.contains("\"mainnet\""));
    }

    @Test
    @DisplayName("rename leaves profile.json appliedProfile untouched when it differs")
    void rename_LeavesAppliedProfileWhenDifferent() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        NodeProfileRepository.createProfile(confRoot(), "other", new Properties());
        writeProfileJson("{\"appliedProfile\" : \"other\"}");

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", null);

        // Assert — the file is untouched (original text/format preserved)
        assertEquals("{\"appliedProfile\" : \"other\"}", Files.readString(profileJson()).trim());
    }

    @Test
    @DisplayName("rename invokes onMetaRenamed after the file move and before returning")
    void rename_CallbackRunsAfterFileMove() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        List<String> events = new ArrayList<>();
        Runnable onMeta = () -> events.add(
                "oldExists=" + Files.exists(profileFile("mainnet"))
                        + ",newExists=" + Files.exists(profileFile("mainnet2")));

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", onMeta);

        // Assert — the callback saw the ALREADY-MOVED file
        assertEquals(List.of("oldExists=false,newExists=true"), events);
    }

    @Test
    @DisplayName("rename rejects the same name")
    void rename_SameName_Throws() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet", null));
    }

    @Test
    @DisplayName("rename rejects a duplicate, reserved, or invalid new name")
    void rename_InvalidNewName_Throws() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        NodeProfileRepository.createProfile(confRoot(), "taken", new Properties());

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "taken", null));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "node-default", null));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "bad name!", null));
    }

    @Test
    @DisplayName("rename rejects a missing source profile (and a blank source name)")
    void rename_MissingSource_Throws() {
        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.renameProfile(confRoot(), "ghost", "mainnet2", null));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.renameProfile(confRoot(), "  ", "mainnet2", null));
    }

    @Test
    @DisplayName("rename tears down the instance registered under the old name (no ghost in the registry)")
    void rename_RemovesOldInstanceFromRegistry() throws IOException {
        // Arrange — a node registered under the old name (stopped snapshot, not running:
        // the rename chain must still remove it, since the restart runs under the NEW name).
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        Signum registered = Mockito.mock(Signum.class);
        Mockito.when(registered.getProfileName()).thenReturn("mainnet");
        NodeModule module = NodeModule.getInstance();
        module.addNode(registered);

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", null);

        // Assert — the old name's context is gone from the registry, and the instance was
        // disposed (ProfileLogger + console subscribers closed, NodeLoggerRegistry unregistered).
        assertNull(module.get("mainnet"), "the old-name instance must be removed from the registry");
        Mockito.verify(registered).dispose();
    }

    @Test
    @DisplayName("rename disposes and unregisters a stopped (non-running) instance as well")
    void rename_RemovesStoppedInstanceToo() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        Signum registered = Mockito.mock(Signum.class);
        Mockito.when(registered.getProfileName()).thenReturn("mainnet");
        Mockito.when(registered.getState()).thenReturn(Signum.State.STOPPED);
        NodeModule module = NodeModule.getInstance();
        module.addNode(registered);

        // Act
        ProfileRuntimeService.renameProfile(confRoot(), "mainnet", "mainnet2", null);

        // Assert — a stopped instance is kept registered by stopNode()'s design; the rename
        // must not leave a ghost under the dead name.
        assertNull(module.get("mainnet"));
        Mockito.verify(registered).dispose();
    }

    // ── delete ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete removes the profile file and its tab-order entry")
    void delete_RemovesProfileFileAndTabOrder() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        NodeProfileRepository.createProfile(confRoot(), "backup", new Properties());
        new ProfileConfig(profilesJson()).setTabOrder(new ArrayList<>(List.of("mainnet", "backup")));

        // Act
        ProfileRuntimeService.deleteProfile(confRoot(), "mainnet", false);

        // Assert
        assertFalse(Files.exists(profileFile("mainnet")));
        assertTrue(Files.exists(profileFile("backup")));
        List<String> order = new ProfileConfig(profilesJson()).getTabOrder();
        assertFalse(order.contains("mainnet"));
        assertTrue(order.contains("backup"));
    }

    @Test
    @DisplayName("delete clears the canonical logging assignment of the deleted profile")
    void delete_ClearsLoggingAssignment() throws IOException {
        // Arrange
        createSourceProfile();
        new LoggingAssignmentStore(confRoot())
                .setAssignmentForModule("mainnet", ModuleIds.NODE, "verbose");

        // Act
        ProfileRuntimeService.deleteProfile(confRoot(), "mainnet", false);

        // Assert — no orphaned presets left in profiles.json
        LoggingAssignmentStore store = new LoggingAssignmentStore(confRoot());
        assertFalse(store.isManaged("mainnet"));
        assertFalse(store.getAssignment("mainnet").containsKey(ModuleIds.NODE));
    }

    @Test
    @DisplayName("delete tears down the instance registered under the name (no ghost in the registry)")
    void delete_RemovesRegisteredInstanceFromRegistry() throws IOException {
        // Arrange — a (stopped) node registered under the name: the delete chain must still
        // remove it, since a deleted profile must never be restarted.
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        Signum registered = Mockito.mock(Signum.class);
        Mockito.when(registered.getProfileName()).thenReturn("mainnet");
        Mockito.when(registered.getState()).thenReturn(Signum.State.STOPPED);
        NodeModule module = NodeModule.getInstance();
        module.addNode(registered);

        // Act
        ProfileRuntimeService.deleteProfile(confRoot(), "mainnet", false);

        // Assert — the name's context is gone from the registry, and the instance was disposed
        // (ProfileLogger + console subscribers closed, NodeLoggerRegistry unregistered).
        assertNull(module.get("mainnet"), "the deleted profile's instance must be removed from the registry");
        Mockito.verify(registered).dispose();
        assertFalse(Files.exists(profileFile("mainnet")));
    }

    @Test
    @DisplayName("delete with deleteData removes the per-profile SQLite data directory")
    void delete_WithDeleteData_RemovesSqliteDatabaseData() throws IOException {
        // Arrange — a profile using its per-profile SQLite database (with a data directory).
        Properties props = new Properties();
        props.setProperty("DB.Url", ProfileCreateDefaults.sqliteDbUrl("del_src"));
        NodeProfileRepository.createProfile(confRoot(), "del_src", props);
        Path dir = ProfileCreateDefaults.sqliteDataDir("del_src");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("signum.sqlite.db"), "marker");
        try {
            // Act
            ProfileRuntimeService.deleteProfile(confRoot(), "del_src", true);

            // Assert — the data directory is deleted recursively (content included)
            assertFalse(Files.exists(dir));
            assertFalse(Files.exists(profileFile("del_src")));
        } finally {
            // No leftover when the delete succeeded; clean up on failure.
            if (Files.exists(dir)) {
                Files.deleteIfExists(dir.resolve("signum.sqlite.db"));
                Files.deleteIfExists(dir);
            }
        }
    }

    @Test
    @DisplayName("delete keeps the data directory of a profile using a server database")
    void delete_WithDeleteData_KeepsServerDatabaseData() throws IOException {
        // Arrange — a profile with a server-DB URL: the per-profile directory does not belong to it.
        Properties props = new Properties();
        props.setProperty("DB.Url", "jdbc:mariadb://localhost:3306/signum");
        NodeProfileRepository.createProfile(confRoot(), "del_srv", props);
        Path dir = ProfileCreateDefaults.sqliteDataDir("del_srv");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("signum.sqlite.db"), "marker");
        try {
            // Act
            ProfileRuntimeService.deleteProfile(confRoot(), "del_srv", true);

            // Assert — the profile is gone, its (foreign) data directory is kept
            assertFalse(Files.exists(profileFile("del_srv")));
            assertTrue(Files.exists(dir));
        } finally {
            Files.deleteIfExists(dir.resolve("signum.sqlite.db"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @DisplayName("delete without deleteData keeps the per-profile SQLite data directory")
    void delete_WithoutDeleteData_KeepsSqliteDatabaseData() throws IOException {
        // Arrange
        Properties props = new Properties();
        props.setProperty("DB.Url", ProfileCreateDefaults.sqliteDbUrl("del_keep"));
        NodeProfileRepository.createProfile(confRoot(), "del_keep", props);
        Path dir = ProfileCreateDefaults.sqliteDataDir("del_keep");
        Files.createDirectories(dir);
        try {
            // Act
            ProfileRuntimeService.deleteProfile(confRoot(), "del_keep", false);

            // Assert
            assertFalse(Files.exists(profileFile("del_keep")));
            assertTrue(Files.exists(dir));
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @DisplayName("delete strips profile.json appliedProfile + profileLinks entries naming the profile")
    void delete_StripsProfileJsonMetadata() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        NodeProfileRepository.createProfile(confRoot(), "other", new Properties());
        writeProfileJson("{\"appliedProfile\" : \"mainnet\", \"profileLinks\" : "
                + "{\"mainnet\" : {\"database\" : \"db1\"}, \"other\" : {\"database\" : \"db2\"}}}");

        // Act
        ProfileRuntimeService.deleteProfile(confRoot(), "mainnet", false);

        // Assert — the deleted profile's entries are gone, the other profile's link survives
        String json = Files.readString(profileJson());
        assertFalse(json.contains("appliedProfile"));
        assertFalse(json.contains("db1"));
        assertTrue(json.contains("\"other\""));
        assertTrue(json.contains("db2"));
    }

    @Test
    @DisplayName("delete leaves other profiles untouched (file, logging, tab order)")
    void delete_OtherProfilesUntouched() throws IOException {
        // Arrange — two profiles; the surviving one has a logging preset + tab order.
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
        NodeProfileRepository.createProfile(confRoot(), "backup", new Properties());
        new LoggingAssignmentStore(confRoot())
                .setAssignmentForModule("backup", ModuleIds.NODE, "verbose");
        new ProfileConfig(profilesJson()).setTabOrder(new ArrayList<>(List.of("mainnet", "backup")));

        // Act
        ProfileRuntimeService.deleteProfile(confRoot(), "mainnet", false);

        // Assert — multi-node isolation: only 'mainnet' is gone
        assertTrue(Files.exists(profileFile("backup")));
        assertEquals("verbose",
                new LoggingAssignmentStore(confRoot()).getAssignment("backup").get(ModuleIds.NODE));
        List<String> order = new ProfileConfig(profilesJson()).getTabOrder();
        assertTrue(order.contains("backup"));
        assertFalse(order.contains("mainnet"));
    }

    @Test
    @DisplayName("delete rejects a blank or missing profile")
    void delete_InvalidOrMissing_Throws() {
        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.deleteProfile(confRoot(), "  ", false));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.deleteProfile(confRoot(), "ghost", false));
    }

    // ── create empty default (F5: "+" tab, New Empty Default Profile) ─────

    @Test
    @DisplayName("creates a zero-override profile file with the default logging assignment")
    void createEmpty_CreatesZeroOverrideFileAndDefaultLogging() throws IOException {
        // Arrange — an existing profile must remain untouched.
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());

        // Act
        String created = ProfileRuntimeService.createEmptyProfile(confRoot(), "node");

        // Assert — the file exists, is truly empty (zero overrides), and gets the
        // default logging preset (SSOT: NodeProfile.DEFAULT_LOGGING_PRESET).
        assertEquals("node", created);
        assertTrue(Files.exists(profileFile("node")));
        NodeProfile profile = NodeProfileRepository.loadProfile(confRoot(), "node");
        assertTrue(profile.getProperties().isEmpty(), "the profile file must contain zero overrides");
        Map<String, String> assignment = new LoggingAssignmentStore(confRoot()).getAssignment("node");
        assertEquals(NodeProfile.DEFAULT_LOGGING_PRESET, assignment.get(ModuleIds.NODE));
    }

    @Test
    @DisplayName("createEmpty rejects a taken name")
    void createEmpty_TakenName_Throws() throws IOException {
        // Arrange
        NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.createEmptyProfile(confRoot(), "mainnet"));
    }

    @Test
    @DisplayName("createEmpty rejects a reserved name")
    void createEmpty_ReservedName_Throws() {
        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.createEmptyProfile(confRoot(), "node-default"));
    }

    @Test
    @DisplayName("createEmpty rejects a blank or invalid name")
    void createEmpty_BlankOrInvalidName_Throws() {
        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.createEmptyProfile(confRoot(), "  "));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileRuntimeService.createEmptyProfile(confRoot(), "bad name!"));
    }

    @Test
    @DisplayName("createEmpty leaves other profiles untouched")
    void createEmpty_OtherProfilesUntouched() throws IOException {
        // Arrange
        Properties mainnetProps = new Properties();
        mainnetProps.setProperty("API.Port", "9001");
        NodeProfileRepository.createProfile(confRoot(), "mainnet", mainnetProps);
        new LoggingAssignmentStore(confRoot()).setAssignmentForModule("mainnet", ModuleIds.NODE, "verbose");

        // Act
        ProfileRuntimeService.createEmptyProfile(confRoot(), "node");

        // Assert
        NodeProfile mainnet = NodeProfileRepository.loadProfile(confRoot(), "mainnet");
        assertEquals("9001", mainnet.getProperties().getProperty("API.Port"));
        assertEquals("verbose",
                new LoggingAssignmentStore(confRoot()).getAssignment("mainnet").get(ModuleIds.NODE));
    }

    // ── delete helpers ──────────────────────────────────────────────────

    // ── rename helpers ──────────────────────────────────────────────────

    private Path profilesJson() {
        return tempDir.resolve("node").resolve("profiles.json");
    }

    private Path profileJson() {
        return tempDir.resolve("node").resolve("profile.json");
    }

    private void writeProfileJson(String json) throws IOException {
        Files.createDirectories(profileJson().getParent());
        Files.writeString(profileJson(), json);
    }
}

