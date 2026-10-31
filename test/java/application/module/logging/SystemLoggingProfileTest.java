package application.module.logging;

import application.utils.config.ModuleIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SystemLoggingProfile} — the L0 defaults of the single
 * system (JVM-global) logging context exposed as the "System" tab.
 */
@DisplayName("SystemLoggingProfile L0 defaults")
class SystemLoggingProfileTest {

    private final SystemLoggingProfile profile = new SystemLoggingProfile();

    @Test
    @DisplayName("the system profile targets the 'system' module with a 'System' display name")
    void identity() {
        assertEquals(ModuleIds.SYSTEM, profile.getModuleId(), "the module id is 'system'");
        assertEquals("System", profile.getDisplayName(), "the tab title is 'System'");
    }

    @Test
    @DisplayName("L0 defaults carry the global root level and the system console buffer")
    void defaults() {
        Map<String, String> d = profile.getDefaults();
        assertEquals("INFO", d.get("level"), "the global (system) root level L0 default");
        assertEquals("1500", d.get("console.maxLines"), "the system console buffer L0 default (SSOT, F4)");
    }
}
