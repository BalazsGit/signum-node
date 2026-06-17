package application.module.database.gui;

import com.google.gson.annotations.SerializedName;

/**
 * Representation of settings.json.
 */
public class GlobalSettings {
    @SerializedName("mariaDb")
    private EngineGlobalSettings mariaDb = new EngineGlobalSettings();

    @SerializedName("postgresql")
    private EngineGlobalSettings postgresql = new EngineGlobalSettings();

    @SerializedName("sqlite")
    private EngineGlobalSettings sqlite = new EngineGlobalSettings();

    public static class EngineGlobalSettings {
        private String versionInfoUrl = "";
        private String downloadBaseUrl = "";

        public String getVersionInfoUrl() {
            return versionInfoUrl;
        }

        public void setVersionInfoUrl(String url) {
            this.versionInfoUrl = url;
        }

        public String getDownloadBaseUrl() {
            return downloadBaseUrl;
        }

        public void setDownloadBaseUrl(String url) {
            this.downloadBaseUrl = url;
        }
    }

    public EngineGlobalSettings getMariaDb() {
        return mariaDb;
    }

    public void setMariaDb(EngineGlobalSettings settings) {
        this.mariaDb = settings;
    }

    public EngineGlobalSettings getPostgresql() {
        return postgresql;
    }

    public void setPostgresql(EngineGlobalSettings settings) {
        this.postgresql = settings;
    }

    public EngineGlobalSettings getSqlite() {
        return sqlite;
    }

    public void setSqlite(EngineGlobalSettings settings) {
        this.sqlite = settings;
    }

    /**
     * Helper to get settings by engine key.
     */
    public EngineGlobalSettings getSettingsForEngine(String key) {
        if ("mariaDb".equals(key))
            return mariaDb;
        if ("postgresql".equals(key))
            return postgresql;
        if ("sqlite".equals(key))
            return sqlite;
        return new EngineGlobalSettings();
    }
}