package application.utils.logging;

import application.utils.config.ModuleIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for the {@link LoggingProfileManager} three-layer (L0 &larr; L1
 * &larr; L2) composition — with the user-editable L1 layer
 * ({@code conf/node/logging/default.properties}).
 * <p>
 * Uses an explicit conf-root sandbox (a JUnit {@link TempDir}) so the tests
 * never touch the real {@code conf/} tree. Follows AAA + JUnit 5 conventions.
 */
@DisplayName("LoggingProfileManager layered composition Tests")
class LoggingProfileManagerTest {

    private static final String MODULE = "node";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        LoggingModuleRegistry.getInstance().clear();
        new TestProvider().register();
    }

    @AfterEach
    void tearDown() {
        LoggingModuleRegistry.getInstance().clear();
    }

    private void writeLoggingFile(String name, String content) throws IOException {
        Path file = tempDir.resolve("node").resolve("logging").resolve(name + ".properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the user-editable L1 layer (default.properties) overrides the module defaults (L0)")
    void l1DefaultFileOverridesModuleDefaults() throws IOException {
        writeLoggingFile("default", "node.level = FINE\n");

        Properties composite = new LoggingProfileManager()
                .createCompositeProfile(tempDir.toString(), "mainnet");

        assertEquals("FINE", composite.getProperty("node.level"),
                "the L1 layer's active lines must override the hardcoded module default");
    }

    @Test
    @DisplayName("the legacy logging-default template is no longer consulted as L1")
    void legacyTemplateNoLongerConsulted() throws IOException {
        writeLoggingFile("logging-default", "node.level = FINE\n");

        Properties composite = new LoggingProfileManager()
                .createCompositeProfile(tempDir.toString(), "mainnet");

        assertNotEquals("FINE", composite.getProperty("node.level"),
                "the reference-only template must not override the hardcoded module default anymore");
        assertEquals("INFO", composite.getProperty("node.level"), "the L0 default stays effective");
    }

    @Test
    @DisplayName("the on-disk profile file (L2) wins over the L1 layer")
    void l2ProfileFileWinsOverL1() throws IOException {
        writeLoggingFile("default", "node.level = FINE\n");
        writeLoggingFile("mainnet", "node.level = SEVERE\n");

        Properties composite = new LoggingProfileManager()
                .createCompositeProfile(tempDir.toString(), "mainnet");

        assertEquals("SEVERE", composite.getProperty("node.level"), "L2 must win per key");
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
}