package application.module.database.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL specific configuration POJO.
 */
public class PostgresConfig extends DatabaseConfig {
    private String adminUsername = "postgres";
    private String adminPassword = "";
    private List<DatabaseInfo> createdDatabases = new ArrayList<>();
    private List<UserInfo> createdUsers = new ArrayList<>();

    public static class DatabaseInfo {
        public String id;
        public String name;
    }

    public static class UserInfo {
        public String id;
        public String username;
        public String password;
        public String host;
        public List<UserGrant> grants = new ArrayList<>();
    }

    public static class UserGrant {
        public String databaseId;
        public String permissions;
    }

    @Override
    public String getEngineKey() {
        return "postgresql";
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String u) {
        this.adminUsername = u;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String p) {
        this.adminPassword = p;
    }

    public List<DatabaseInfo> getCreatedDatabases() {
        return createdDatabases;
    }

    public void setCreatedDatabases(List<DatabaseInfo> dbs) {
        this.createdDatabases = dbs;
    }

    public List<UserInfo> getCreatedUsers() {
        return createdUsers;
    }

    public void setCreatedUsers(List<UserInfo> users) {
        this.createdUsers = users;
    }
}