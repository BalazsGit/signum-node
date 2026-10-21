package application.module.node;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the three-layer node configuration chain of
 * {@link Signum#loadPropertiesForProfile(String, String)}:
 * hardcoded defaults (Props.java, L0) &larr; the user-editable L1 layer
 * ({@code default.properties}) &larr; the profile file (L2).
 * <p>
 * Uses an explicit conf-root sandbox (a JUnit {@link TempDir}) so the tests
 * never touch the real {@code conf/} tree. Follows AAA + JUnit 5 conventions.
 */
@DisplayName("Signum.loadPropertiesForProfile layered chain Tests")
class SignumLoadPropertiesForProfileTest {

    @TempDir
    Path tempDir;

    private void writeProfileLayer(String fileName, String content) throws IOException {
        Path profilesDir = tempDir.resolve("node").resolve("profiles");
        Files.createDirectories(profilesDir);
        try (Writer writer = Files.newBufferedWriter(profilesDir.resolve(fileName),
                StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    @Test
    @DisplayName("no layer files -> the service falls back to the hardcoded Props defaults")
    void missingLayers_FallBackToHardcodedDefaults() {
        PropertyService service = Signum.loadPropertiesForProfile(tempDir.toString(), "ghost");

        assertEquals(Props.API_PORT.getDefaultValue(), service.getInt(Props.API_PORT));
        assertEquals(Props.SETTINGS_DIR.getDefaultValue(), service.getString(Props.SETTINGS_DIR));
    }

    @Test
    @DisplayName("an active L1 line (default.properties) overrides the hardcoded default for every profile")
    void defaultTemplateOverridesHardcoded() throws IOException {
        writeProfileLayer("default.properties", "API.Port=9001\n");

        PropertyService service = Signum.loadPropertiesForProfile(tempDir.toString(), "mainnet");

        assertEquals(9001, service.getInt(Props.API_PORT));
    }

    @Test
    @DisplayName("the profile file (L2) overrides the L1 layer")
    void profileOverridesDefaultTemplate() throws IOException {
        writeProfileLayer("default.properties", "API.Port=9001\n");
        writeProfileLayer("mainnet.properties", "API.Port=9002\n");

        PropertyService service = Signum.loadPropertiesForProfile(tempDir.toString(), "mainnet");

        assertEquals(9002, service.getInt(Props.API_PORT));
    }

    @Test
    @DisplayName("the SETTINGS_DIR materialization still works (from the default-template layer)")
    void settingsDirFromDefaultTemplate_IsMaterialized() throws IOException {
        writeProfileLayer("default.properties",
                Props.SETTINGS_DIR.getName() + "=./custom-settings\n");

        PropertyService service = Signum.loadPropertiesForProfile(tempDir.toString(), "mainnet");

        assertEquals("./custom-settings", service.getString(Props.SETTINGS_DIR));
    }
}