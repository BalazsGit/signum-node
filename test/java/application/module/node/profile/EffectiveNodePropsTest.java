package application.module.node.profile;

import application.module.node.props.CaselessProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two-layer (L0 &larr; L2) effective node
 * properties loading — see {@link EffectiveNodeProps}.
 * <p>
 * Uses an explicit conf-root sandbox (a JUnit {@link TempDir}) so the tests
 * never touch the real {@code conf/} tree. Follows AAA + JUnit 5 conventions.
 */
@DisplayName("EffectiveNodeProps two-layer loading Tests")
class EffectiveNodePropsTest {

    @TempDir
    Path tempDir;

    private Path profilesDir() {
        return tempDir.resolve("node").resolve("profiles");
    }

    private void writeProfile(String fileName, String content) throws IOException {
        Files.createDirectories(profilesDir());
        try (Writer writer = Files.newBufferedWriter(profilesDir().resolve(fileName),
                StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    // ── loadEffective (L2 only) ────────────────────────────────────────

    @Test
    @DisplayName("no profile file -> empty effective properties (pure L0 fallback)")
    void noProfileFile_ReturnsEmptyProperties() {
        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("profile file (L2) active lines are loaded")
    void profileFileActiveLines_AreLoaded() throws IOException {
        writeProfile("mainnet.properties", "API.Port=9100\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertEquals("9100", result.getProperty("API.Port"));
    }

    @Test
    @DisplayName("profile file comments are ignored")
    void profileFileComments_AreIgnored() throws IOException {
        writeProfile("mainnet.properties",
                "# a fully commented template line\n"
                        + "API.Port=9001\n"
                        + "# DB.Url=jdbc:sqlite:file:./db/x.sqlite.db\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertEquals("9001", result.getProperty("API.Port"));
        assertNull(result.getProperty("DB.Url"));
    }

    @Test
    @DisplayName("keys are case-insensitive in the profile file")
    void keysAreCaseInsensitive() throws IOException {
        writeProfile("mainnet.properties", "api.port=9100\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertEquals("9100", result.getProperty("API.Port"));
    }

    @Test
    @DisplayName("fully commented profile file contributes nothing")
    void fullyCommentedProfile_ContributesNothing() throws IOException {
        writeProfile("mainnet.properties",
                "# API.Port=9001\n"
                        + "# node.blockTime=120\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("the former L1 file (default.properties) is NOT loaded as a layer")
    void defaultPropertiesFile_NotLoadedAsLayer() throws IOException {
        writeProfile("default.properties", "API.Port=9001\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertTrue(result.isEmpty(),
                "the former L1 file must not contribute active lines in the 2-layer model");
    }

    @Test
    @DisplayName("the reference-only node-default template is not loaded as a layer")
    void legacyTemplateActiveLines_NotLoaded() throws IOException {
        writeProfile("node-default.properties", "API.Port=9001\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertTrue(result.isEmpty(),
                "the reference-only template must not contribute active lines");
    }

    // ── GUI baseline (L0 only in 2-layer) ──────────────────────────────

    @Test
    @DisplayName("baseline always returns the hardcoded L0 default")
    void baseline_ReturnsL0Default() {
        assertEquals("8888", EffectiveNodeProps.baselineValue(tempDir.toString(), "API.Port", 8888));
        assertEquals("true", EffectiveNodeProps.baselineValue(tempDir.toString(), "node.enabled", true));
    }

    @Test
    @DisplayName("baseline with a null L0 default -> empty string")
    void baseline_NullL0Default_ReturnsEmptyString() {
        assertEquals("", EffectiveNodeProps.baselineValue(tempDir.toString(), "node.missing", null));
    }

    @Test
    @DisplayName("baseline is not affected by any on-disk file")
    void baseline_NotAffectedByProfileFile() throws IOException {
        writeProfile("node.properties", "API.Port=9001\n");

        assertEquals("8888", EffectiveNodeProps.baselineValue(tempDir.toString(), "API.Port", 8888),
                "the baseline is the L0 default, not the profile file value");
    }
}