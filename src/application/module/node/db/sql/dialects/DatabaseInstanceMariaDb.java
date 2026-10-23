package application.module.node.db.sql.dialects;

import application.module.logging.context.NodeLoggers;
import application.module.node.props.PropertyService;
import com.zaxxer.hikari.HikariConfig;
import org.jooq.SQLDialect;

public class DatabaseInstanceMariaDb extends DatabaseInstanceBaseImpl {

    protected DatabaseInstanceMariaDb(PropertyService propertyService, NodeLoggers loggers) {
        super(propertyService, loggers);
    }

    @Override
    protected HikariConfig configureImpl(HikariConfig config) {
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "512");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "4096");
        config.addDataSourceProperty("characterEncoding", "utf8mb4");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("useLocalTransactionState", "true");
        config.addDataSourceProperty("useUnicode", "true");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");
        config.addDataSourceProperty("useUnbufferedIO", "false");
        config.addDataSourceProperty("useReadAheadInput", "false");
        config.setConnectionInitSql("SET NAMES utf8mb4;");
        return config;
    }

    @Override
    protected void onShutdownImpl() {
    }

    @Override
    public SQLDialect getDialect() {
        return SQLDialect.MARIADB;
    }

    @Override
    protected void onStartupImpl() {
    }

    @Override
    public String getDatabaseVersionSQLScript() {
        return "SELECT VERSION()";
    }

    @Override
    public SupportStatus getSupportStatus() {
        return SupportStatus.STABLE;
    }
}
