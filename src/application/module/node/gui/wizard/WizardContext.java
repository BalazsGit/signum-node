package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.profile.Network;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mutable state carried across the setup wizard steps.
 * <p>
 * Plain data holder (no Swing): each step reads what it needs on {@code onExit} and the
 * summary step renders from it. Defaults reflect the "recommended" path
 * (SQLite, mainnet, default ports, start immediately).
 * </p>
 */
public class WizardContext {

    // ── Database engine selection ─────────────────────────────────────────
    private DatabaseEngine engine = DatabaseEngine.SQLITE;

    // ── Database installation / connection ────────────────────────────────
    private boolean dbInstallSkipped = true; // true → download/install not completed in the wizard
    private boolean skipDbSetup = true;
    private String dbHost = "localhost";
    private int dbPort = -1; // -1 → engine default resolved at apply time
    private String dbUser = "signum";
    private String dbPassword = "";
    private String dbName = "signum";
    private String dbUrlSuffix; // the query string (with the leading '?'), e.g. "?useSSL=false"

    // ── SQLite DB.Url (the configuration panel's db.url editor) ───────────
    private String sqliteDbUrl;         // null → the per-profile SSOT URL applies at finish
    private String sqliteDbProfileName; // null → the companion DB profile is the node profile name
    // G3: database-source selection (custom URL / an existing profile / a new profile).
    /**
     * The database-profile name selected in the database step (an existing profile,
     * or one created via the database-profile wizard). {@code null} for the "custom
     * database URL" source — the manual connection step then applies.
     */
    private String selectedDbProfileName;
    // G5: logging-profile selection (an existing profile, or one created via the logging-profile wizard).
    /**
     * The logging-profile name selected in the logging step (an existing profile, or
     * one created via the logging-profile wizard). {@code null} → the default logging
     * seeding applies (SSOT:
     * {@link application.module.node.profile.ProfileRuntimeService#seedDefaultLoggingAssignment}).
     */
    private String selectedLoggingProfileName;
    // G6 (AD-03): the database profiles CREATED during this wizard session. On a
    // cancel they REMAIN on disk (v2, AD-06) — they are independent Database-module
    // entities the user manages there. Tracked so the wizard's own re-navigation can
    // never delete a pre-existing (non-session-created) profile.
    private final Set<String> sessionDbProfilesCreated = new LinkedHashSet<>();

    // ── Step 1: profile name ─────────────────────────────────────────────
    /**
     * The user's custom profile name (step 1). {@code null} when the "use
     * suggested name" option is selected — the suggested name is then resolved
     * from the final context by the wizard controller (SSOT:
     * {@link application.module.node.profile.ProfileNameSuggester}).
     */
    private String customName;
    /**
     * The resolved profile name — the live preview shown in the wizard's title
     * bar (and the final name after finish). Suggested names track the context
     * (network / engine / ports) as it changes; a custom name is fixed.
     */
    private String name;
    /**
     * The wizard's <b>virtual in-memory profile</b> — the full property set of
     * the node profile being created, held in memory (a
     * {@link application.module.node.profile.NodeProfile} WITHOUT a file path)
     * from the first visit of the node-configuration step
     * until the finish step. The profile FILE is created only on Finish
     * (SSOT: {@link WizardFinish}): while the wizard runs, the suggested name
     * can never count the session's own profile as "taken" (no file exists to
     * discover) and a cancel leaves nothing to clean up. {@code null} until
     * the node-configuration step is first visited — a finish without it
     * persists the plain {@link WizardFinish#buildProperties} mapping of this
     * context.
     */
    private application.module.node.profile.NodeProfile virtualProfile;
    // ── Node configuration step: network + ports ─────────────────────────
    /** The network decided in the FIRST step (part of the suggested name, SSOT: {@link Network}). */
    private Network network = Network.MAINNET;
    private Integer apiPort; // null → not collected yet (the step's fields hold the default)
    private Integer p2pPort; // null → not collected yet
    private Integer wsPort;  // null → not collected yet

    // ── Summary step ──────────────────────────────────────────────────────
    private boolean startImmediately = true;

    public DatabaseEngine getEngine() {
        return engine;
    }

    public void setEngine(DatabaseEngine engine) {
        this.engine = engine;
    }

    public boolean isDbInstallSkipped() {
        return dbInstallSkipped;
    }

    public void setDbInstallSkipped(boolean dbInstallSkipped) {
        this.dbInstallSkipped = dbInstallSkipped;
    }

    public boolean isSkipDbSetup() {
        return skipDbSetup;
    }

    public void setSkipDbSetup(boolean skipDbSetup) {
        this.skipDbSetup = skipDbSetup;
    }

    public String getDbHost() {
        return dbHost;
    }

    public void setDbHost(String dbHost) {
        this.dbHost = dbHost;
    }

    public int getDbPort() {
        return dbPort;
    }

    public void setDbPort(int dbPort) {
        this.dbPort = dbPort;
    }

    public String getDbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    /**
     * The server JDBC URL suffix — the query string with its leading {@code ?}
     * (e.g. {@code ?useSSL=false}), matching the editor's Suffix field (SSOT:
     * {@code DatabaseConfigurationUtils#JDBC_URL_PATTERN} group 5). {@code null}
     * when the URL carries no query string.
     */
    public String getDbUrlSuffix() {
        return dbUrlSuffix;
    }

    public void setDbUrlSuffix(String dbUrlSuffix) {
        this.dbUrlSuffix = dbUrlSuffix;
    }

    public String getCustomName() {
        return customName;
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the wizard's virtual in-memory profile (see {@link #virtualProfile}),
     * or {@code null} until the advanced editor is opened
     */
    public application.module.node.profile.NodeProfile getVirtualProfile() {
        return virtualProfile;
    }

    /**
     * @param virtualProfile the wizard's virtual in-memory profile (null clears it)
     */
    public void setVirtualProfile(application.module.node.profile.NodeProfile virtualProfile) {
        this.virtualProfile = virtualProfile;
    }

    public Network getNetwork() {
        return network;
    }

    public void setNetwork(Network network) {
        this.network = network == null ? Network.MAINNET : network;
    }

    public Integer getApiPort() {
        return apiPort;
    }

    public void setApiPort(Integer apiPort) {
        this.apiPort = apiPort;
    }

    public Integer getP2pPort() {
        return p2pPort;
    }

    public void setP2pPort(Integer p2pPort) {
        this.p2pPort = p2pPort;
    }

    public Integer getWsPort() {
        return wsPort;
    }

    public void setWsPort(Integer wsPort) {
        this.wsPort = wsPort;
    }

    /**
     * The SQLite {@code DB.Url} the user configured in the database step (the same
     * Profile/Path/DB-file trio and URL-composition logic as the configuration
     * panel's DB.Url row). {@code null} when the trio was left untouched — in that
     * case the finish step derives the per-profile SSOT URL from the final name.
     */
    public String getSqliteDbUrl() {
        return sqliteDbUrl;
    }

    public void setSqliteDbUrl(String sqliteDbUrl) {
        this.sqliteDbUrl = sqliteDbUrl;
    }

    /**
     * The database-profile name (the last folder of the per-profile convention
     * path) the configured SQLite database belongs to — the companion database
     * profile is created under this name. {@code null} when the trio was left
     * untouched (the node profile name applies).
     */
    public String getSqliteDbProfileName() {
        return sqliteDbProfileName;
    }

    public void setSqliteDbProfileName(String sqliteDbProfileName) {
        this.sqliteDbProfileName = sqliteDbProfileName;
    }

    public boolean isStartImmediately() {
        return startImmediately;
    }

    public void setStartImmediately(boolean startImmediately) {
        this.startImmediately = startImmediately;
    }

    public String getSelectedDbProfileName() {
        return selectedDbProfileName;
    }

    public void setSelectedDbProfileName(String selectedDbProfileName) {
        this.selectedDbProfileName = selectedDbProfileName;
    }

    public String getSelectedLoggingProfileName() {
        return selectedLoggingProfileName;
    }

    public void setSelectedLoggingProfileName(String selectedLoggingProfileName) {
        this.selectedLoggingProfileName = selectedLoggingProfileName;
    }

    /**
     * G6 (AD-03): records a database profile created during this wizard session
     * (null / blank-safe). Such profiles REMAIN on disk after a cancel (v2, AD-06).
     */
    public void markDbProfileCreated(String name) {
        if (name != null && !name.isBlank()) {
            sessionDbProfilesCreated.add(name.trim());
        }
    }

    /** @return an unmodifiable view of the database profiles created this session (AD-03). */
    public Set<String> getSessionDbProfilesCreated() {
        return Collections.unmodifiableSet(sessionDbProfilesCreated);
    }
}