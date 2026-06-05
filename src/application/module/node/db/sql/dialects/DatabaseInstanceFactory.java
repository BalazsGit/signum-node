package application.module.node.db.sql.dialects;

import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.JDBCUtils;

public class DatabaseInstanceFactory {

    public static DatabaseInstance createInstance(PropertyService propertyService) {
        String dbUrl = propertyService.getString(Props.DB_URL);
        SQLDialect dialect = JDBCUtils.dialect(dbUrl);

        switch (dialect) {
            case MARIADB:
            case MYSQL:
                return new DatabaseInstanceMariaDb(propertyService);
            case SQLITE:
                return new DatabaseInstanceSqlite(propertyService);
            case POSTGRES:
                return new DatabaseInstancePostgres(propertyService);
            default:
                throw new IllegalArgumentException("Database dialect not supported: " + dialect);
        }

    }
}
