package application.module.node.db.sql.dialects;

import application.module.node.props.PropertyService;
import com.zaxxer.hikari.HikariConfig;
import org.jooq.SQLDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseInstancePostgres extends DatabaseInstanceBaseImpl {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseInstancePostgres.class);

    protected DatabaseInstancePostgres(PropertyService propertyService) {
        super(propertyService);
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
