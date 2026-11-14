package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.profile.Network;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertNull(c.getCustomName(), "no custom name until the name step is left");
        assertNull(c.getVirtualProfile(), "no virtual session profile until the node-configuration step creates it");
        assertEquals(Network.MAINNET, c.getNetwork(), "mainnet is the default network");
        assertNull(c.getApiPort());
        assertNull(c.getP2pPort());
        assertNull(c.getWsPort());
        assertTrue(c.isStartImmediately());
    }

    @Test
    @DisplayName("session-created database profiles are tracked (null / blank-safe, trimmed, deduped)")
    void markDbProfileCreatedTracks() {
        WizardContext c = new WizardContext();
        assertTrue(c.getSessionDbProfilesCreated().isEmpty(), "no DB profile until one is created");
        c.markDbProfileCreated("  alpha  ");
        c.markDbProfileCreated("alpha"); // duplicate after trim -> a single entry
        c.markDbProfileCreated("beta");
        c.markDbProfileCreated(null); // ignored
        c.markDbProfileCreated("   "); // blank -> ignored
        assertEquals(List.of("alpha", "beta"), new ArrayList<>(c.getSessionDbProfilesCreated()),
                "created DB profiles are recorded in order, trimmed and deduped");
    }

    @Test
    @DisplayName("the session DB-profile view is unmodifiable")
    void sessionDbProfilesViewUnmodifiable() {
        WizardContext c = new WizardContext();
        c.markDbProfileCreated("alpha");
        assertThrows(UnsupportedOperationException.class,
                () -> c.getSessionDbProfilesCreated().add("intruder"));
    }

    @Test
    @DisplayName("the virtual in-memory session profile is held until finish (getter/setter)")
    void virtualProfileHeld() {
        WizardContext c = new WizardContext();
        assertNull(c.getVirtualProfile());
        application.module.node.profile.NodeProfile virtual =
                new application.module.node.profile.NodeProfile("virtual_holder");
        c.setVirtualProfile(virtual);
        assertSame(virtual, c.getVirtualProfile(), "the same in-memory instance is held");
        c.setVirtualProfile(null);
        assertNull(c.getVirtualProfile(), "setting null clears the virtual profile");
    }
}