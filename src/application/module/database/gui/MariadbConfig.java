package application.module.database.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * MariaDB specific configuration POJO.
 */
public class MariadbConfig extends DatabaseConfig {
    private String adminUsername = "root";
    private String adminPassword = "";
    private String appUsername = "";
    private String appUserPassword = "";
    private String appUserPermissions = "ALL";
    private List<DatabaseInfo> createdDatabases = new ArrayList<>();
    private List<UserInfo> createdUsers = new ArrayList<>();

    public static class DatabaseInfo {
        public String id;
        public String name;
        public String user;
        public String permissions;
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
        return "mariaDb";
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

    public String getAppUsername() {
        return appUsername;
    }

    public void setAppUsername(String u) {
        this.appUsername = u;
    }

    public String getAppUserPassword() {
        return appUserPassword;
    }

    public void setAppUserPassword(String p) {
        this.appUserPassword = p;
    }

    public String getAppUserPermissions() {
        return appUserPermissions;
    }

    public void setAppUserPermissions(String p) {
        this.appUserPermissions = p;
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