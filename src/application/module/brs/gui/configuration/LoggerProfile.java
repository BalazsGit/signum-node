package application.module.brs.gui.configuration;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggerProfile {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerProfile.class);
    private final Properties properties = new Properties();
    private final String profileName;

    public LoggerProfile(String profileName) {
        this.profileName = profileName;
    }

    public String getName() {
        return profileName;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties newProps) {
        this.properties.clear();
        this.properties.putAll(newProps);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }

    public void applyInternalDefaults() {
        properties.setProperty("handlers", "java.util.logging.ConsoleHandler");
        properties.setProperty(".level", "SEVERE");
        properties.setProperty("brs.level", "INFO");
        properties.setProperty("java.util.logging.ConsoleHandler.level", "ALL");
        properties.setProperty("java.util.logging.ConsoleHandler.formatter", "brs.util.BriefLogFormatter");
        properties.setProperty("org.eclipse.jetty.level", "OFF");
        properties.setProperty("javax.servlet.level", "OFF");
        properties.setProperty("com.zaxxer.hikari.level", "WARNING");
        properties.setProperty("com.zaxxer.hikari.HikariConfig.level", "INFO");
        properties.setProperty("sun.rmi.level", "INFO");
        properties.setProperty("javax.management.level", "INFO");
        properties.setProperty("brs.db.store.DerivedTableManager.level", "OFF");
        properties.setProperty("org.jooq.Constants.level", "OFF");
        properties.setProperty("brs.gui.consoleLogSize", "100000");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        LoggerProfile that = (LoggerProfile) o;
        return properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(properties);
    }

    public LoggerProfile copy(String newName) {
        LoggerProfile clone = new LoggerProfile(newName);
        clone.setProperties(this.properties);
        return clone;
    }
}
