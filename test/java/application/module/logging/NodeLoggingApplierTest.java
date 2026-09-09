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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link NodeLoggingApplier} — per-node logging level application with
 * fallback to the default profile.
 */
@DisplayName("NodeLoggingApplier Tests")
class NodeLoggingApplierTest {

    private static final String MODULE = "node";
    private static final String PROFILE = "mainnet";

    @TempDir
    java.nio.file.Path tempDir;

    @BeforeEach
    void setUp() {
        LoggingModuleRegistry.getInstance().clear();
        new TestProvider().register();
    }

    @AfterEach
    void tearDown() {
        LoggingModuleRegistry.getInstance().clear();
        NodeLoggerRegistry.unregister(MODULE, PROFILE);
        NodeLoggerRegistry.unregister(MODULE, "other");
    }

    @Test
    @DisplayName("applies the assigned preset level to the node's ProfileLogger")
    void applyForNodeProfile_preset_setsLevel() {
        new LoggingAssignmentStore(tempDir.toString())
                .setAssignment(PROFILE, Map.of(MODULE, "verbose"));

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), PROFILE, MODULE);

        ProfileLogger logger = NodeLoggerRegistry.get(MODULE, PROFILE);
        assertEquals(LogLevel.DEBUG, logger.getLogLevel(), "verbose preset (node.level=FINE) → DEBUG");
    }

    @Test
    @DisplayName("falls back to the module default level when the assigned profile is missing")
    void applyForNodeProfile_fallbackToDefault() {
        new LoggingAssignmentStore(tempDir.toString())
                .setAssignment(PROFILE, Map.of(MODULE, "nonexistent-profile"));

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), PROFILE, MODULE);

        ProfileLogger logger = NodeLoggerRegistry.get(MODULE, PROFILE);
        assertEquals(LogLevel.INFO, logger.getLogLevel(), "missing profile falls back to default (node.level=INFO)");
    }

    @Test
    @DisplayName("an on-disk profile's level wins over the default")
    void applyForNodeProfile_onDiskWins() throws IOException {
        LoggingProfileRepository repo = new LoggingProfileRepository(tempDir.toString());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("node.level", "SEVERE");
        repo.saveProps(MODULE, "custom", props);

        new LoggingAssignmentStore(tempDir.toString())
                .setAssignment(PROFILE, Map.of(MODULE, "custom"));

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), PROFILE, MODULE);

        ProfileLogger logger = NodeLoggerRegistry.get(MODULE, PROFILE);
        assertEquals(LogLevel.ERROR, logger.getLogLevel(), "on-disk node.level=SEVERE → ERROR");
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
    @DisplayName("resolveLevel falls back to the module default when there is no file or preset")
    void resolveLevel_fallbackToDefault() {
        assertEquals("INFO", NodeLoggingApplier.resolveLevel(tempDir.toString(), "unknown", MODULE));
    }

    @Test
    @DisplayName("different node profiles get different levels (multi-node isolation)")
    void applyForNodeProfile_perProfileIsolation() {
        LoggingAssignmentStore store = new LoggingAssignmentStore(tempDir.toString());
        store.setAssignment("mainnet", Map.of(MODULE, "verbose"));   // FINE → DEBUG
        store.setAssignment("other", Map.of(MODULE, "minimal"));     // WARNING → WARN

        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), "mainnet", MODULE);
        NodeLoggingApplier.applyForNodeProfile(tempDir.toString(), "other", MODULE);

        assertEquals(LogLevel.DEBUG, NodeLoggerRegistry.get(MODULE, "mainnet").getLogLevel());
        assertEquals(LogLevel.WARN, NodeLoggerRegistry.get(MODULE, "other").getLogLevel());
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

        @Override
        public Map<String, Map<String, String>> getPresetOverrides() {
            return Map.of(
                    "minimal", Map.of("node.level", "WARNING"),
                    "standard", Map.of("node.level", "INFO"),
                    "verbose", Map.of("node.level", "FINE"),
                    "debug", Map.of("node.level", "FINEST"));
        }
    }

    static final class TestProvider extends ModuleLoggingProvider {
        private final ModuleLoggingProfile profile = new TestProfile();

        @Override
        public ModuleLoggingProfile getProfile() {
            return profile;
        }
    }
}