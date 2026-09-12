package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WizardContext defaults")
class WizardContextTest {

    @Test
    @DisplayName("defaults follow the recommended path")
    void defaults() {
        WizardContext c = new WizardContext();
        assertEquals(DatabaseEngine.SQLITE, c.getEngine());
        assertTrue(c.isSkipDbSetup());
        assertNull(c.getName());
        assertFalse(c.isTestnet());
        assertTrue(c.isUseDefaultPorts());
        assertNull(c.getApiPort());
        assertNull(c.getP2pPort());
        assertNull(c.getWsPort());
        assertEquals("standard", c.getLoggingPreset());
        assertTrue(c.isStartImmediately());
    }
}