package application.module.node.gui.configuration;

import java.util.Objects;
import java.util.Properties;

public class NodeProfile {
    private final Properties properties = new Properties();
    private final String profileName;

    public NodeProfile(String profileName) {
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

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public void setProperty(String key, String value) {
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NodeProfile that = (NodeProfile) o;
        return properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(properties);
    }
}
