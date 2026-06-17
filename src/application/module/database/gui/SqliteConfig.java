package application.module.database.gui;

/**
 * SQLite specific configuration POJO.
 */
public class SqliteConfig extends DatabaseConfig {
    private String dbUrl = "jdbc:sqlite:file:./db/signum.sqlite.db";
    private String sqliteJournalMode = "WAL";
    private String sqliteCacheSize = "-131072";

    @Override
    public String getEngineKey() {
        return "sqlite";
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public void setDbUrl(String url) {
        this.dbUrl = url;
    }

    public String getSqliteJournalMode() {
        return sqliteJournalMode;
    }

    public void setSqliteJournalMode(String mode) {
        this.sqliteJournalMode = mode;
    }

    public String getSqliteCacheSize() {
        return sqliteCacheSize;
    }

    public void setSqliteCacheSize(String size) {
        this.sqliteCacheSize = size;
    }
}