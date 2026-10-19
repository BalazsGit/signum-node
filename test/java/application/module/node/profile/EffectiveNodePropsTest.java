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
 * Unit tests for the layered (L0 &larr; L1 &larr; L2) effective node
 * properties loading — see {@link EffectiveNodeProps}.
 * <p>
 * Uses an explicit conf-root sandbox (a JUnit {@link TempDir}) so the tests
 * never touch the real {@code conf/} tree. Follows AAA + JUnit 5 conventions.
 */
@DisplayName("EffectiveNodeProps layered loading Tests")
class EffectiveNodePropsTest {

    @TempDir
    Path tempDir;

    private Path profilesDir() {
        return tempDir.resolve("node").resolve("profiles");
    }

    private void writeLayer(String fileName, String content) throws IOException {
        Files.createDirectories(profilesDir());
        try (Writer writer = Files.newBufferedWriter(profilesDir().resolve(fileName),
                StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    @Test
    @DisplayName("no layers at all -> empty effective properties (pure L0 fallback)")
    void noLayers_ReturnsEmptyProperties() {
        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("default template (L1) active lines are loaded; comments are ignored")
    void defaultTemplateActiveLines_AreLoaded() throws IOException {
        writeLayer("node-default.properties",
                "# a fully commented template line\n"
                        + "API.Port=9001\n"
                        + "# DB.Url=jdbc:sqlite:file:./db/x.sqlite.db\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertEquals("9001", result.getProperty("API.Port"));
        assertNull(result.getProperty("DB.Url"));
    }

    @Test
    @DisplayName("profile file (L2) active lines are loaded")
    void profileFileActiveLines_AreLoaded() throws IOException {
        writeLayer("mainnet.properties", "API.Port=9100\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertEquals("9100", result.getProperty("API.Port"));
    }

    @Test
    @DisplayName("the profile file overrides the default template per key; non-conflicting L1 keys survive")
    void profileOverridesDefaultTemplate_PerKey() throws IOException {
        writeLayer("node-default.properties", "API.Port=9001\nnode.blockTime=120\n");
        writeLayer("mainnet.properties", "API.Port=9002\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertEquals("9002", result.getProperty("API.Port"), "L2 must win on the conflicting key");
        assertEquals("120", result.getProperty("node.blockTime"), "non-conflicting L1 key must survive");
    }

    @Test
    @DisplayName("fully commented layers contribute nothing")
    void fullyCommentedLayers_ContributeNothing() throws IOException {
        writeLayer("node-default.properties", "# API.Port=9001\n# DB.Url=jdbc:sqlite:file:./db/x.sqlite.db\n");
        writeLayer("mainnet.properties", "# node.blockTime=120\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("keys are case-insensitive across layers")
    void keysAreCaseInsensitive_AcrossLayers() throws IOException {
        writeLayer("node-default.properties", "Node.ApiPort=9001\n");
        writeLayer("mainnet.properties", "node.apiport=9002\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "mainnet");

        assertEquals("9002", result.getProperty("NODE.APIPORT"));
    }

    @Test
    @DisplayName("a reserved profile name loads the template only (no double-load of itself)")
    void reservedProfileName_LoadsTemplateOnly() throws IOException {
        writeLayer("node-default.properties", "API.Port=9001\n");

        CaselessProperties result = EffectiveNodeProps.loadEffective(tempDir.toString(), "node-default");

        assertEquals("9001", result.getProperty("API.Port"));
        assertEquals(1, result.size(), "the template must not be loaded twice");
    }

    // ── GUI baseline (effective L0 <- L1) ────────────────────────────────

    @Test
    @DisplayName("baseline without a template -> the hardcoded L0 default in String form")
    void baseline_NoTemplate_ReturnsL0Default() {
        assertEquals("8888", EffectiveNodeProps.baselineValue(tempDir.toString(), "API.Port", 8888));
        assertEquals("true", EffectiveNodeProps.baselineValue(tempDir.toString(), "node.enabled", true));
    }

    @Test
    @DisplayName("baseline with a null L0 default -> empty string")
    void baseline_NullL0Default_ReturnsEmptyString() {
        assertEquals("", EffectiveNodeProps.baselineValue(tempDir.toString(), "node.missing", null));
    }

    @Test
    @DisplayName("baseline with an active template line -> L1 overrides L0 for every profile")
    void baseline_ActiveTemplateLine_OverridesL0() throws IOException {
        writeLayer("node-default.properties", "# documented default\nAPI.Port=9001\n");

        assertEquals("9001", EffectiveNodeProps.baselineValue(tempDir.toString(), "API.Port", 8888));
        assertEquals("8889", EffectiveNodeProps.baselineValue(tempDir.toString(), "node.other", "8889"),
                "non-overridden keys must keep their L0 value");
    }

    @Test
    @DisplayName("a template line without a value is not an override -> L0 stays effective")
    void baseline_EmptyTemplateLine_FallsBackToL0() throws IOException {
        writeLayer("node-default.properties", "API.Port=\n");

        assertEquals("8888", EffectiveNodeProps.baselineValue(tempDir.toString(), "API.Port", 8888));
    }

    @Test
    @DisplayName("baseline keys are case-insensitive")
    void baseline_KeysAreCaseInsensitive() throws IOException {
        writeLayer("node-default.properties", "node.blocktime=120\n");

        assertEquals("120", EffectiveNodeProps.baselineValue(tempDir.toString(), "Node.BlockTime", "45"));
    }

    @Test
    @DisplayName("a template change on disk is picked up (fingerprint-based cache invalidation)")
    void baseline_TemplateChangedOnDisk_IsPickedUp() throws IOException {
        writeLayer("node-default.properties", "API.Port=9001\n");
        assertEquals("9001", EffectiveNodeProps.baselineValue(tempDir.toString(), "API.Port", 8888));

        // The comment changes the file size, so the fingerprint must change
        // even when the modification time rounds to the same millisecond.
        writeLayer("node-default.properties", "API.Port=9002\n# template edited\n");
        assertEquals("9002", EffectiveNodeProps.baselineValue(tempDir.toString(), "API.Port", 8888),
                "the cached template must be re-read once the file changed");
    }
}