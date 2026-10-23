package application.module.logging;

import application.utils.config.ModuleIds;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import application.utils.logging.NodeLoggerRegistry;
import application.utils.logging.ProfileLogger;
import application.utils.logging.event.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeLoggingApplier} — per-node logging level application with
 * fallback to the default profile.
 */
@DisplayName("NodeLoggingApplier Tests")
class NodeLoggingApplierTest {

    private static final String MODULE = "node";
    private static final String MODULE_DB = "database";
    private static final String PROFILE = "mainnet";

    @TempDir
    java.nio.file.Path tempDir;

    @BeforeEach
    void setUp() {
        LoggingModuleRegistry.getInstance().clear();
        new TestProvider().register();
        new DbProvider().register();
    }

    @AfterEach
    void tearDown() {
        LoggingModuleRegistry.getInstance().clear();
        NodeLoggerRegistry.unregister(MODULE, PROFILE);
        NodeLoggerRegistry.unregister(MODULE, "other");
        NodeLoggerRegistry.unregister(MODULE_DB, PROFILE);
        NodeLoggerRegistry.unregister(MODULE_DB, "other");
    }

    @Test
    @DisplayName("P2.4: the node module is applied through the isolated context — with no live node it is a defensive no-op")
    void applyForNodeProfile_nodeModule_withoutLiveNode_isDefensiveNoOp() throws IOException {
        LoggingProfileRepository repo = new LoggingProfileRepository(tempDir.toString());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("node.level", "SEVERE");
        repo.saveProps(MODULE, "custom", props);

        new LoggingAssignmentStore(tempDir.toString())
                .setAssignment(PROFILE, Map.of(MODULE, "custom"));

        // Capture the bus state BEFORE: the P2.4 routing must not touch the
        // legacy bus at all (the context's config is the single level gate).
        ProfileLogger logger = NodeLoggerRegistry.getOrCreate(MODULE, PROFILE);
        LogLevel levelBefore = logger.getLogLevel();

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), PROFILE, MODULE);

        assertEquals(levelBefore, logger.getLogLevel(),
                "without a live Signum instance the node module apply must not write the legacy bus level");
        assertFalse(Files.exists(repo.getLoggingDir(MODULE).resolve("applied")),
                "per-node application must not create shared applied snapshot files");
    }

    @Test
    @DisplayName("legacy path: a non-node module still falls back to its module default level when the assigned profile is missing")
    void applyForNodeProfile_fallbackToDefault() {
        new LoggingAssignmentStore(tempDir.toString())
                .setAssignment(PROFILE, Map.of(MODULE_DB, "nonexistent-profile"));

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), PROFILE, MODULE_DB);

        ProfileLogger logger = NodeLoggerRegistry.get(MODULE_DB, PROFILE);
        assertEquals(LogLevel.INFO, logger.getLogLevel(), "missing profile falls back to default (database.level=INFO)");
    }

    @Test
    @DisplayName("legacy path: a non-node module's on-disk profile level wins over the default")
    void applyForNodeProfile_onDiskWins() throws IOException {
        LoggingProfileRepository repo = new LoggingProfileRepository(tempDir.toString());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("database.level", "SEVERE");
        repo.saveProps(MODULE_DB, "custom", props);

        new LoggingAssignmentStore(tempDir.toString())
                .setAssignment(PROFILE, Map.of(MODULE_DB, "custom"));

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), PROFILE, MODULE_DB);

        ProfileLogger logger = NodeLoggerRegistry.get(MODULE_DB, PROFILE);
        assertEquals(LogLevel.ERROR, logger.getLogLevel(), "on-disk database.level=SEVERE → ERROR");
    }

    @Test
    @DisplayName("legacy path: applying a per-node logger level does not write a shared profile snapshot")
    void applyForNodeProfile_doesNotPersistSharedSnapshot() throws IOException {
        LoggingProfileRepository repo = new LoggingProfileRepository(tempDir.toString());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("database.level", "WARNING");
        repo.saveProps(MODULE_DB, "custom", props);

        new LoggingAssignmentStore(tempDir.toString())
                .setAssignment(PROFILE, Map.of(MODULE_DB, "custom"));

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), PROFILE, MODULE_DB);

        assertEquals(LogLevel.WARN, NodeLoggerRegistry.get(MODULE_DB, PROFILE).getLogLevel(),
                "the assigned database.level is still applied to its profile logger");
        assertFalse(Files.exists(repo.getLoggingDir(MODULE_DB).resolve("applied")),
                "per-node application must not create shared applied snapshot files");
    }

    @Test
    @DisplayName("parseLogLevel maps JUL level names onto the unified LogLevel")
    void parseLogLevel_mapping() {
        assertEquals(LogLevel.TRACE, NodeLoggingApplier.parseLogLevel("ALL"));
        assertEquals(LogLevel.TRACE, NodeLoggingApplier.parseLogLevel("FINEST"));
        assertEquals(LogLevel.DEBUG, NodeLoggingApplier.parseLogLevel("FINE"));
        assertEquals(LogLevel.DEBUG, NodeLoggingApplier.parseLogLevel("finer"));
        assertEquals(LogLevel.INFO, NodeLoggingApplier.parseLogLevel("INFO"));
        assertEquals(LogLevel.INFO, NodeLoggingApplier.parseLogLevel("CONFIG"));
        assertEquals(LogLevel.WARN, NodeLoggingApplier.parseLogLevel("WARNING"));
        assertEquals(LogLevel.ERROR, NodeLoggingApplier.parseLogLevel("SEVERE"));
        assertEquals(LogLevel.OFF, NodeLoggingApplier.parseLogLevel("OFF"));
        assertEquals(LogLevel.INFO, NodeLoggingApplier.parseLogLevel(null));
        assertEquals(LogLevel.INFO, NodeLoggingApplier.parseLogLevel("bogus"));
    }

    @Test
    @DisplayName("resolveLevel falls back to the module default when there is no on-disk file")
    void resolveLevel_fallbackToDefault() {
        assertEquals("INFO", NodeLoggingApplier.resolveLevel(tempDir.toString(), "unknown", MODULE));
    }

    @Test
    @DisplayName("resolveLevel falls back to the reserved default file (L1) before the module default (L0)")
    void resolveLevel_fallsBackToReservedDefaultFile() throws IOException {
        java.nio.file.Path file = tempDir.resolve("node").resolve("logging")
                .resolve(LoggingProfileRepository.RESERVED_PROFILE_NAME + ".properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "node.level = FINE\n");

        assertEquals("FINE", NodeLoggingApplier.resolveLevel(tempDir.toString(), "unknown", MODULE),
                "the reserved default file's active lines must override the hardcoded module default");
    }

    @Test
    @DisplayName("the on-disk profile level (L2) wins over the reserved default file (L1)")
    void resolveLevel_onDiskWinsOverReservedDefault() throws IOException {
        java.nio.file.Path file = tempDir.resolve("node").resolve("logging")
                .resolve(LoggingProfileRepository.RESERVED_PROFILE_NAME + ".properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "node.level = FINE\n");

        LoggingProfileRepository repo = new LoggingProfileRepository(tempDir.toString());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("node.level", "SEVERE");
        repo.saveProps(MODULE, "custom", props);

        assertEquals("SEVERE", NodeLoggingApplier.resolveLevel(tempDir.toString(), "custom", MODULE));
    }

    @Test
    @DisplayName("the legacy logging-default template is no longer consulted as L1")
    void resolveLevel_legacyTemplateNoLongerL1() throws IOException {
        java.nio.file.Path file = tempDir.resolve("node").resolve("logging")
                .resolve("logging-default.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "node.level = FINE\n");

        assertEquals("INFO", NodeLoggingApplier.resolveLevel(tempDir.toString(), "unknown", MODULE),
                "the reference-only template must not override the hardcoded module default anymore");
    }

    @Test
    @DisplayName("legacy path: different node profiles get different levels (multi-node isolation)")
    void applyForNodeProfile_perProfileIsolation() throws IOException {
        LoggingProfileRepository repo = new LoggingProfileRepository(tempDir.toString());
        java.util.Properties loud = new java.util.Properties();
        loud.setProperty("database.level", "FINE");
        repo.saveProps(MODULE_DB, "loud", loud);
        java.util.Properties quiet = new java.util.Properties();
        quiet.setProperty("database.level", "WARNING");
        repo.saveProps(MODULE_DB, "quiet", quiet);

        LoggingAssignmentStore store = new LoggingAssignmentStore(tempDir.toString());
        store.setAssignment("mainnet", Map.of(MODULE_DB, "loud"));    // FINE → DEBUG
        store.setAssignment("other", Map.of(MODULE_DB, "quiet"));     // WARNING → WARN

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), "mainnet", MODULE_DB);
        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), "other", MODULE_DB);

        assertEquals(LogLevel.DEBUG, NodeLoggerRegistry.get(MODULE_DB, "mainnet").getLogLevel());
        assertEquals(LogLevel.WARN, NodeLoggerRegistry.get(MODULE_DB, "other").getLogLevel());
    }

    // ── Test fixtures ──────────────────────────────────────────────────

    static final class TestProfile extends ModuleLoggingProfile {
        @Override
        public String getModuleId() {
            return ModuleIds.NODE;
        }

        @Override
        public String getDisplayName() {
            return "Test Node";
        }

        @Override
        public String getDescription() {
            return "Test";
        }

        @Override
        public Map<String, String> getDefaults() {
            return Map.of("node.level", "INFO");
        }
    }

    static final class TestProvider extends ModuleLoggingProvider {
        private final ModuleLoggingProfile profile = new TestProfile();

        @Override
        public ModuleLoggingProfile getProfile() {
            return profile;
        }
    }

    /** The legacy (un-migrated) module path keeps its own provider + defaults. */
    static final class DbProfile extends ModuleLoggingProfile {
        @Override
        public String getModuleId() {
            return MODULE_DB;
        }

        @Override
        public String getDisplayName() {
            return "Test Database";
        }

        @Override
        public String getDescription() {
            return "Test";
        }

        @Override
        public Map<String, String> getDefaults() {
            return Map.of("database.level", "INFO");
        }
    }

    static final class DbProvider extends ModuleLoggingProvider {
        private final ModuleLoggingProfile profile = new DbProfile();

        @Override
        public ModuleLoggingProfile getProfile() {
            return profile;
        }
    }
}