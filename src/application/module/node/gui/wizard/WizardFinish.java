package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.logging.LoggingAssignmentStore;
import application.module.node.NodeModule;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.profile.ProfileRuntimeService;
import application.module.node.props.Props;
import application.utils.config.ModuleIds;

import java.util.Properties;

/**
 * Terminal step of the wizard: turns the collected {@link WizardContext} into a persisted
 * node profile (+ optional immediate start).
 * <p>
 * All property mapping goes through the SSOT ({@link ProfileCreateDefaults}); persistence
 * through {@link NodeProfileRepository}; the start through {@link NodeModule} (the only
 * composition root for node lifecycle).
 * </p>
 * <p>
 * The new profile's logging assignment is either the logging profile selected in the
 * logging step (G5 — written via {@code LoggingAssignmentStore}) or, when left on the
 * default, seeded from the node module's currently applied logging profile (a snapshot
 * at creation time — SSOT: {@link ProfileRuntimeService#seedDefaultLoggingAssignment});
 * while "Default" is the applied profile no assignment is written.
 * </p>
 */
public final class WizardFinish {

    private WizardFinish() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates the profile from the context.
     * <p>
     * This is the ONLY place the wizard writes the profile file: while the
     * wizard runs, the profile's properties live in the virtual in-memory
     * session profile ({@link WizardContext#getVirtualProfile()}); the file is
     * created here, on finish.
     * </p>
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

        // The virtual in-memory session profile (accumulated during the wizard —
        // wizard values + the user's own full-editor edits) is the authoritative
        // source of the profile's properties. When it is absent (a programmatic
        // caller that never visited the node-configuration step) the context is
        // mapped directly. The profile file is created here either way.
        NodeProfile virtual = c.getVirtualProfile();
        Properties props = (virtual != null) ? virtual.getProperties() : buildProperties(c);
        NodeProfileRepository.createProfile(name, props);

        // The new profile's logging assignment (G5): an explicitly selected logging
        // profile (the logging step) is assigned to the node (SSOT:
        // LoggingAssignmentStore); otherwise the default seeding applies — the node
        // module's currently applied logging profile (nothing is seeded while
        // "Default" is applied). Same rule as every other profile-creation path.
        String selectedLogging = c.getSelectedLoggingProfileName();
        if (selectedLogging != null && !selectedLogging.isBlank()) {
            new LoggingAssignmentStore().setAssignmentForModule(name, ModuleIds.NODE, selectedLogging);
        } else {
            ProfileRuntimeService.seedDefaultLoggingAssignment(name);
        }

        // A SQLite setup gets its companion database profile immediately (the
        // same rule the configuration panel's save applies): a complete SQLite
        // setup creates ./database/SQLite/<name>/ with its profile.json, so the
        // profile is discoverable right away.
        String dbUrl = props.getProperty(Props.DB_URL.getName());
        // A database profile explicitly selected in the database step (G3) already
        // exists on disk — do not re-create a companion for it.
        if (dbUrl != null && dbUrl.startsWith("jdbc:sqlite:") && c.getSelectedDbProfileName() == null) {
            // The companion database profile is named after the database's own
            // profile folder (the Profile field of the edited trio), or after
            // the node profile for the per-profile default.
            String companion = c.getSqliteDbProfileName() != null
                    ? c.getSqliteDbProfileName() : name;
            ProfileCreateDefaults.ensureSqliteDatabaseProfile(companion, dbUrl);
        }

        if (c.isStartImmediately()) {
            NodeModule.getInstance().startNode(name);
        }
        return name;
    }

    /**
     * Maps the wizard context to the profile's properties (the SSOT mapping,
     * see the class javadoc). Used both by the plain wizard flow (the profile
     * file is created from these at finish when no virtual in-memory session
     * profile exists yet) and for pre-filling the virtual in-memory session
     * profile (the node-configuration step).
     */
    public static Properties buildProperties(WizardContext c) {
        String name = c.getName() == null ? "" : c.getName();
        Properties props = new Properties();
        ProfileCreateDefaults.applyNetwork(props, c.getNetwork());

        DatabaseEngine engine = c.getEngine() == null ? DatabaseEngine.SQLITE : c.getEngine();
        // G3: a database profile selected in the database step is the source of the
        // node's DB connection — the profile's own JDBC URL (and, for server engines,
        // its first user) is applied as-is; the profile already exists on disk (no
        // companion re-creation — see createProfile).
        DatabaseConfigurationUtils.DbProfile selectedProfile = c.getSelectedDbProfileName() == null
                ? null
                : DatabaseConfigurationUtils.loadProfile(null, engine.toString(), c.getSelectedDbProfileName());
        if (selectedProfile != null && !selectedProfile.databases.isEmpty()) {
            DatabaseConfigurationUtils.DbInstance instance = selectedProfile.databases.get(0);
            props.setProperty(Props.DB_URL.getName(), instance.url);
            if (engine != DatabaseEngine.SQLITE && !instance.users.isEmpty()) {
                DatabaseConfigurationUtils.DbUser user = instance.users.get(0);
                props.setProperty(Props.DB_USERNAME.getName(), user.username == null ? "" : user.username);
                props.setProperty(Props.DB_PASSWORD.getName(), user.password == null ? "" : user.password);
            }
        } else if (engine == DatabaseEngine.SQLITE) {
            // The user-configured DB.Url of the database step wins; an untouched
            // setup keeps the per-profile file-based DB — independently
            // runnable out of the box (plan .6).
            if (c.getSqliteDbUrl() != null) {
                props.setProperty(Props.DB_URL.getName(), c.getSqliteDbUrl());
            } else {
                ProfileCreateDefaults.applySqliteDatabase(props, name);
            }
        } else if (!c.isSkipDbSetup()) {
            ProfileCreateDefaults.applyServerDatabase(props, engine == DatabaseEngine.POSTGRESQL,
                    c.getDbHost(), c.getDbPort(), c.getDbUser(), c.getDbPassword(), c.getDbName());
            // skipDbSetup: leave DB.* unset — the shared default applies
        }

        // Ports: the wizard always collects explicit values (the step's fields
        // are pre-filled with the defaults — what is shown is what the profile
        // gets). Only a programmatic caller that set NO port at all falls back
        // to the deterministic per-profile band (independent-by-default, plan
        // §2.6 — the CLI's path).
        if (c.getApiPort() == null && c.getP2pPort() == null && c.getWsPort() == null) {
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
        return props;
    }
}