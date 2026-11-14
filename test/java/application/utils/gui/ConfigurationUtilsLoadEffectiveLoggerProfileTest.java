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
 * Unit tests for the two-layer (L0 &larr; L2) merge of
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
    @DisplayName("an empty shared logging.properties means pure L0 (no L1 layer exists)")
    void emptySharedFile_meansPureL0() throws IOException {
        writeLoggingFile("logging", ""); // the build-created empty L2 file
        writeLoggingFile("default", "node.level = FINE\n"); // on-disk file, but NOT a layer

        LoggerProfile result = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "logging");

        assertEquals("INFO", result.getProperties().getProperty("node.level"),
                "with an empty L2 file the hardcoded L0 default (node.level=INFO) must be effective; "
                        + "the on-disk default.properties is not a layer in the 2-layer architecture");
    }

    @Test
    @DisplayName("the shared logging.properties (L2) provides effective values; keys not in L2 fall to L0")
    void sharedFileProvidesEffectiveValues_fallsToL0ForMissingKeys() throws IOException {
        writeLoggingFile("default", "node.level = FINE\norg.eclipse.jetty.level = FINE\n"); // on-disk file, not a layer
        writeLoggingFile("logging", "node.level = SEVERE\n");

        LoggerProfile result = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "logging");

        assertEquals("SEVERE", result.getProperties().getProperty("node.level"),
                "the L2 value must be effective");
        assertEquals("OFF", result.getProperties().getProperty("org.eclipse.jetty.level"),
                "keys not in L2 fall to the L0 default (org.eclipse.jetty.level=OFF), not to the on-disk "
                        + "default.properties (which is not a layer)");
    }

    @Test
    @DisplayName("no reserved default file -> the hardcoded defaults (L0) stay effective")
    void missingReservedDefault_fallsBackToHardcodedDefaults() throws IOException {
        writeLoggingFile("logging", "node.level = SEVERE\n");

        LoggerProfile result = ConfigurationUtils.loadEffectiveLoggerProfile(tempDir.toString(), "logging");

        // F0-F4 (log4j2): the JUL L0 defaults (handlers=java.util.logging.ConsoleHandler, .level,
        // ConsoleHandler.level/formatter) were removed from LoggerProfile.applyInternalDefaults();
        // the L0 layer is now log4j2 *.level keys. Verify one of those stays effective with no L1 file.
        assertEquals("OFF",
                result.getProperties().getProperty("org.eclipse.jetty.level"),
                "L0 default must be present (no L1 file -> the hardcoded L0 stays effective)");
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
                "the missing named profile must fall back to the shared file (L2)");
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