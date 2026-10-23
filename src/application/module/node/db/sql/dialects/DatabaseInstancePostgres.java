package application.module.node.db.sql.dialects;

import application.module.logging.context.NodeLoggers;
import application.module.node.props.PropertyService;
import com.zaxxer.hikari.HikariConfig;
import org.jooq.SQLDialect;

public class DatabaseInstancePostgres extends DatabaseInstanceBaseImpl {

    protected DatabaseInstancePostgres(PropertyService propertyService, NodeLoggers loggers) {
        super(propertyService, loggers);
    }

    @Override
    protected HikariConfig configureImpl(HikariConfig config) {
        // check https://jdbc.postgresql.org/documentation/use/ for more options
        return config;
    }

    @Override
    protected void onShutdownImpl() {
    }

    @Override
    public SQLDialect getDialect() {
        return SQLDialect.POSTGRES;
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
        return SupportStatus.EXPERIMENTAL;
    }
}
