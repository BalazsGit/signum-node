package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.logging.LoggingAssignmentStore;
import application.module.node.NodeModule;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.props.Props;
import application.utils.config.ModuleIds;

import java.util.Properties;

/**
 * Terminal step of the wizard: turns the collected {@link WizardContext} into a persisted
 * node profile + logging assignment (+ optional immediate start).
 * <p>
 * All property mapping goes through the SSOT ({@link ProfileCreateDefaults}); persistence
 * through {@link NodeProfileRepository}; the logging association through
 * {@link LoggingAssignmentStore} (canonical {@code profiles.json} writer); the start
 * through {@link NodeModule} (the only composition root for node lifecycle).
 * </p>
 */
public final class WizardFinish {

    private WizardFinish() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates the profile from the context.
     *
     * @return the created profile name
     * @throws IllegalArgumentException on an invalid/missing name or a taken name
     * @throws java.io.IOException      on persistence failure
     */
    public static String createProfile(WizardContext c) throws Exception {
        String name = c.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profile name is missing");
        }

        Properties props = new Properties();
        ProfileCreateDefaults.applyNetwork(props, c.isTestnet());

        DatabaseEngine engine = c.getEngine() == null ? DatabaseEngine.SQLITE : c.getEngine();
        if (engine == DatabaseEngine.SQLITE) {
            // per-profile file-based DB — independently runnable out of the box (plan §2.6)
            ProfileCreateDefaults.applySqliteDatabase(props, name);
        } else if (!c.isSkipDbSetup()) {
            ProfileCreateDefaults.applyServerDatabase(props, engine == DatabaseEngine.POSTGRESQL,
                    c.getDbHost(), c.getDbPort(), c.getDbUser(), c.getDbPassword(), c.getDbName());
            // skipDbSetup → leave DB.* unset: the shared default applies
        }

        if (c.isUseDefaultPorts()) {
            ProfileCreateDefaults.applyPorts(props, name);
        } else {
            if (c.getApiPort() != null) {
                props.setProperty(Props.API_PORT.getName(), String.valueOf(c.getApiPort()));
            }
            if (c.getP2pPort() != null) {
                props.setProperty(Props.P2P_PORT.getName(), String.valueOf(c.getP2pPort()));
            }
            if (c.getWsPort() != null) {
                props.setProperty(Props.API_WEBSOCKET_PORT.getName(), String.valueOf(c.getWsPort()));
            }
        }

        NodeProfile profile = NodeProfileRepository.createProfile(name, props);

        // Logging association (SSOT: LoggingAssignmentStore → conf/node/profiles.json)
        new LoggingAssignmentStore().setAssignmentForModule(name, ModuleIds.NODE, c.getLoggingPreset());

        if (c.isStartImmediately()) {
            NodeModule.getInstance().startNode(name);
        }
        return profile.getName();
    }
}