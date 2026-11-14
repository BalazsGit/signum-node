package application.module.node.gui.wizard;

import application.module.logging.LoggingProfileRepository;
import application.utils.config.ModuleIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Headless tests for {@link NodeLoggingProfileWizard} — the single SSOT creation path
 * (plan AD-03), driven without the GUI. A unique profile is created in the default conf
 * root and removed again (the same probe pattern as the other wizard tests).
 */
@DisplayName("NodeLoggingProfileWizard (G5: node logging profile creation)")
class NodeLoggingProfileWizardTest {

    @Test
    @DisplayName("createProfile persists the chosen root + file levels (single SSOT write)")
    void createProfilePersistsLevels() throws Exception {
        String name = "wiz_logging_probe_" + System.nanoTime();
        try (Probe ignored = new Probe(name)) {
            assertEquals(name, NodeLoggingProfileWizard.createProfile(name, "DEBUG", "WARN"));
            Properties props = new LoggingProfileRepository().loadProps(ModuleIds.NODE, name);
            assertEquals("DEBUG", props.getProperty("rootLogger.level"));
            assertEquals("WARN", props.getProperty("node.fileLog.level"));
        }
    }

    @Test
    @DisplayName("createProfile defaults a blank level to INFO (the file level follows the root)")
    void createProfileDefaultsLevels() throws Exception {
        String name = "wiz_logging_probe_" + System.nanoTime();
        try (Probe ignored = new Probe(name)) {
            NodeLoggingProfileWizard.createProfile(name, null, "   ");
            Properties props = new LoggingProfileRepository().loadProps(ModuleIds.NODE, name);
            assertEquals("INFO", props.getProperty("rootLogger.level"));
            assertEquals("INFO", props.getProperty("node.fileLog.level"));
        }
    }

    @Test
    @DisplayName("createProfile rejects a blank name")
    void createProfileRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> NodeLoggingProfileWizard.createProfile("", "INFO", "INFO"));
        assertThrows(IllegalArgumentException.class, () -> NodeLoggingProfileWizard.createProfile("   ", "INFO", "INFO"));
        assertThrows(IllegalArgumentException.class, () -> NodeLoggingProfileWizard.createProfile(null, "INFO", "INFO"));
    }

    @Test
    @DisplayName("createProfile rejects the reserved canonical profile name")
    void createProfileRejectsReservedName() {
        assertThrows(IllegalArgumentException.class,
                () -> NodeLoggingProfileWizard.createProfile("default", "INFO", "INFO"));
    }

    /** Cleanup guard: removes the probe logging profile (best effort). */
    private static final class Probe implements AutoCloseable {
        private final String name;

        Probe(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            try {
                new LoggingProfileRepository().delete(ModuleIds.NODE, name);
            } catch (Exception ignored) {
                // the probe profile may not exist on failure paths
            }
        }
    }
}