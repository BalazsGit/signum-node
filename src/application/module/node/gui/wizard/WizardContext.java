package application.module.node.gui.wizard;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;

/**
 * Mutable state carried across the setup wizard steps.
 * <p>
 * Plain data holder (no Swing): each step reads what it needs on {@code onExit} and the
 * summary step renders from it. Defaults reflect the "recommended" path
 * (SQLite, mainnet, default ports, standard logging, start immediately).
 * </p>
 */
public class WizardContext {

    // ── Step 1: database engine ──────────────────────────────────────────
    private DatabaseEngine engine = DatabaseEngine.SQLITE;

    // ── Step 1.1: database installation / connection ─────────────────────
    private boolean dbInstallSkipped = true; // true → download/install not completed in the wizard
    private boolean skipDbSetup = true;
    private String dbHost = "localhost";
    private int dbPort = -1; // -1 → engine default resolved at apply time
    private String dbUser = "signum";
    private String dbPassword = "";
    private String dbName = "signum";

    // ── Step 2: node configuration ───────────────────────────────────────
    private String name;
    private boolean testnet = false;
    private boolean useDefaultPorts = true;
    private Integer apiPort; // null → default
    private Integer p2pPort; // null → default
    private Integer wsPort;  // null → default

    // ── Step 3: logging ──────────────────────────────────────────────────
    private String loggingPreset = "standard";

    // ── Step 4: summary ──────────────────────────────────────────────────
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isTestnet() {
        return testnet;
    }

    public void setTestnet(boolean testnet) {
        this.testnet = testnet;
    }

    public boolean isUseDefaultPorts() {
        return useDefaultPorts;
    }

    public void setUseDefaultPorts(boolean useDefaultPorts) {
        this.useDefaultPorts = useDefaultPorts;
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

    public String getLoggingPreset() {
        return loggingPreset;
    }

    public void setLoggingPreset(String loggingPreset) {
        this.loggingPreset = loggingPreset;
    }

    public boolean isStartImmediately() {
        return startImmediately;
    }

    public void setStartImmediately(boolean startImmediately) {
        this.startImmediately = startImmediately;
    }
}