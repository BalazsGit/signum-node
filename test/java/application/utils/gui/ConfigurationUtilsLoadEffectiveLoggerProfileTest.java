package application.utils.gui;

import application.module.node.gui.configuration.LoggerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for the three-layer (L0 &larr; L1 &larr; L2) merge of
 * {@link ConfigurationUtils#loadEffectiveLoggerProfile(String, String)}.
 * <p>
 * Uses an explicit conf-root sandbox (a JUnit {@link TempDir}) so the tests
 * never touch the real {@code conf/} tree. Follows AAA + JUnit 5 conventions.
 */
@DisplayName("ConfigurationUtils.loadEffectiveLoggerProfile layered merge Tests")
class ConfigurationUtilsLoadEffectiveLoggerProfileTest {

    @TempDir
    Path tempDir;

    private Path loggingFile(String name) throws IOException {
        Path file = tempDir.resolve("node").resolve("logging").resolve(name + ".properties");
        Files.createDirectories(file.getParent());
        return file;
    }

    private void writeLoggingFile(String name, String content) throws IOException {
        Files.writeString(loggingFile(name), content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an empty shared logging.properties no longer preempts the L1 layer's active lines")
    void emptySharedFile_doesNotPreemptReservedDefault() throws IOException {
        writeLoggingFile("logging", ""); // the build-created empty L2 file
        writeLoggingFile("default", "node.level = FINE\n");

        LoggerProfile result = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "logging");

        assertEquals("FINE", result.getProperties().getProperty("node.level"),
                "the L1 layer's active lines must survive an empty shared file");
    }

    @Test
    @DisplayName("the shared logging.properties (L2) overrides the reserved default file (L1)")
    void sharedFileOverridesReservedDefault() throws IOException {
        writeLoggingFile("default", "node.level = FINE\n.level = SEVERE\n");
        writeLoggingFile("logging", "node.level = SEVERE\n");

        LoggerProfile result = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "logging");

        assertEquals("SEVERE", result.getProperties().getProperty("node.level"));
        assertEquals("SEVERE", result.getProperties().getProperty(".level"),
                "non-conflicting L1 keys must survive the L2 merge");
    }

    @Test
    @DisplayName("no reserved default file -> the hardcoded defaults (L0) stay effective")
    void missingReservedDefault_fallsBackToHardcodedDefaults() throws IOException {
        writeLoggingFile("logging", "node.level = SEVERE\n");

        LoggerProfile result = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "logging");

        assertEquals("java.util.logging.ConsoleHandler",
                result.getProperties().getProperty("handlers"), "L0 default must be present");
        assertEquals("SEVERE", result.getProperties().getProperty("node.level"));
    }

    @Test
    @DisplayName("a named profile file (L2) wins over the shared file; without it the shared file is used")
    void namedProfileWinsOverSharedFile() throws IOException {
        writeLoggingFile("default", "node.level = FINE\n");
        writeLoggingFile("logging", "node.level = WARNING\n");
        writeLoggingFile("verbose", "node.level = SEVERE\n");

        LoggerProfile named = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "verbose");
        assertEquals("SEVERE", named.getProperties().getProperty("node.level"));

        Files.delete(loggingFile("verbose"));
        LoggerProfile shared = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "verbose");
        assertEquals("WARNING", shared.getProperties().getProperty("node.level"),
                "the missing named profile must fall back to the shared file (which still overrides L1)");
    }

    @Test
    @DisplayName("the legacy logging-default template is no longer consulted as L1")
    void legacyTemplateActiveLines_NoLongerEffective() throws IOException {
        writeLoggingFile("logging", "");
        writeLoggingFile("logging-default", "node.level = FINE\n");

        LoggerProfile result = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "logging");

        assertNotEquals("FINE", result.getProperties().getProperty("node.level"),
                "the reference-only template must not override the hardcoded default anymore");
    }
}