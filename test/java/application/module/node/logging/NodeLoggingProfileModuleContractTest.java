package application.module.node.logging;

import application.utils.profile.ProfileModule;
import application.utils.profile.ProfileRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the logging module is the FIRST implementation of the generic R1
 * {@code ProfileModule} contract (plan P6.1): a node logging profile satisfies the
 * interface and registers in the module-independent {@link ProfileRegistry}.
 */
@DisplayName("NodeLoggingProfile satisfies the generic R1 ProfileModule contract")
class NodeLoggingProfileModuleContractTest {

    @AfterEach
    void tearDown() {
        ProfileRegistry.getInstance().clear();
    }

    @Test
    @DisplayName("a node logging profile IS-A ProfileModule and registers in the generic registry")
    void nodeProfileIsAProfileModule() {
        NodeLoggingProfile profile = new NodeLoggingProfile();

        assertTrue(profile instanceof ProfileModule);
        assertEquals(NodeLoggingProfile.MODULE_ID, profile.getModuleId());
        assertEquals("logging", profile.getCategory());
        assertTrue(profile.getDefaultProfile().containsKey("node.level"),
                "the R1 L0 defaults must expose the node defaults");

        ProfileRegistry.getInstance().register(profile);
        assertEquals(profile, ProfileRegistry.getInstance().getModule(profile.getModuleId()));
        assertTrue(ProfileRegistry.getInstance().isRegistered(profile.getModuleId()));
    }
}
