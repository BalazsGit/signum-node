package application.module.logging;

import application.module.logging.EffectiveProfileResolver.EffectiveKey;
import application.module.logging.EffectiveProfileResolver.Source;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.AfterEach;
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
 * Unit tests for {@link EffectiveProfileResolver}.
 * <p>
 * Verifies the effective composition values and per-key source labels across the
 * five layers (base, default, preset, on-disk, runtime override).
 */
@DisplayName("EffectiveProfileResolver Tests")
class EffectiveProfileResolverTest {

    private static final String MODULE = "resmod";

    @TempDir
    Path tempDir;

    private EffectiveProfileResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        LoggingModuleRegistry.getInstance().clear();
        new TestProvider().register();
        resolver = new EffectiveProfileResolver(tempDir.toString());

        // Global base defaults layer
        Files.writeString(tempDir.resolve("logging-default.properties"), "base.only=ALL\n");
    }

    @AfterEach
    void tearDown() {
        LoggingModuleRegistry.getInstance().clear();
    }

    private static EffectiveKey findKey(List<EffectiveKey> keys, String key) {
        return keys.stream().filter(k -> k.key().equals(key)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("base defaults appear with GLOBAL_BASE source")
    void preview_baseDefaults_labeledGlobalBase() {
        List<EffectiveKey> keys = resolver.previewComposition("p");

        EffectiveKey base = findKey(keys, "base.only");
        assertNotNull(base);
        assertEquals("ALL", base.value());
        assertEquals(Source.GLOBAL_BASE, base.source());
    }

    @Test
    @DisplayName("module defaults appear with MODULE_DEFAULT source when no preset selected")
    void preview_moduleDefaults_labeledDefault() {
        List<EffectiveKey> keys = resolver.previewComposition("p");

        EffectiveKey a = findKey(keys, "resmod.a");
        assertNotNull(a);
        assertEquals("INFO", a.value());
        assertEquals(Source.MODULE_DEFAULT, a.source());

        assertEquals(Source.MODULE_DEFAULT, findKey(keys, "resmod.common").source());
    }

    @Test
    @DisplayName("a selected preset overrides the default value and is labeled PRESET")
    void preview_presetOverridesDefault() {
        List<EffectiveKey> keys = resolver.previewComposition("p", Map.of(MODULE, "quiet"), Map.of());

        EffectiveKey a = findKey(keys, "resmod.a");
        assertNotNull(a);
        assertEquals("SEVERE", a.value());
        assertEquals(Source.PRESET, a.source());
    }

    @Test
    @DisplayName("on-disk profile values win and are labeled ON_DISK")
    void preview_onDiskOverridesAll() throws IOException {
        Path loggingDir = tempDir.resolve(MODULE).resolve("logging");
        Files.createDirectories(loggingDir);
        Files.writeString(loggingDir.resolve("p.properties"),
                "resmod.a=FINER\ndisk.only=X\n");

        List<EffectiveKey> keys = resolver.previewComposition("p");

        EffectiveKey a = findKey(keys, "resmod.a");
        assertEquals("FINER", a.value());
        assertEquals(Source.ON_DISK, a.source());

        EffectiveKey disk = findKey(keys, "disk.only");
        assertEquals("X", disk.value());
        assertEquals(Source.ON_DISK, disk.source());
    }

    @Test
    @DisplayName("runtime overrides have the highest precedence and RUNTIME_OVERRIDES source")
    void preview_runtimeOverrideHighest() {
        Map<String, String> overrides = Map.of("override.key", "V", "resmod.a", "OFF");
        List<EffectiveKey> keys = resolver.previewComposition("p", Map.of(), overrides);

        assertEquals("V", findKey(keys, "override.key").value());
        assertEquals(Source.RUNTIME_OVERRIDES, findKey(keys, "override.key").source());

        assertEquals("OFF", findKey(keys, "resmod.a").value());
        assertEquals(Source.RUNTIME_OVERRIDES, findKey(keys, "resmod.a").source());
    }

    @Test
    @DisplayName("result list is sorted by key for stable UI ordering")
    void preview_keysAreSorted() {
        List<EffectiveKey> keys = resolver.previewComposition("p");
        for (int i = 1; i < keys.size(); i++) {
            assertTrue(keys.get(i - 1).key().compareTo(keys.get(i).key()) <= 0);
        }
    }

    @Test
    @DisplayName("findReferencingProfiles delegates to the assignment store")
    void findReferencingProfiles_delegates() {
        List<String> result = resolver.findReferencingProfiles("does-not-exist");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── Test fixtures ──────────────────────────────────────────────────

    static final class TestProfile extends ModuleLoggingProfile {
        @Override
        public String getModuleId() {
            return MODULE;
        }

        @Override
        public String getDisplayName() {
            return "Test Res";
        }

        @Override
        public String getDescription() {
            return "Test";
        }

        @Override
        public Map<String, String> getDefaults() {
            return Map.of("resmod.a", "INFO", "resmod.common", "WARNING");
        }

        @Override
        public Map<String, Map<String, String>> getPresetOverrides() {
            return Map.of(
                    "quiet", Map.of("resmod.a", "SEVERE"),
                    "loud", Map.of("resmod.a", "FINEST"));
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