package application.module.database.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for database specific configuration parameters.
 */
public abstract class DatabaseConfig {
    protected String profileName;
    protected String port;
    protected String datadir;
    protected String installedVersion;
    protected String downloadedVersion;
    protected String downloadedOs;
    protected String downloadedArch;
    protected boolean step1Completed;
    protected boolean step2Completed;
    protected boolean step3Completed;
    protected boolean isReady;
    protected String configFilePath;
    protected Map<String, String> properties = new HashMap<>();

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String name) {
        this.profileName = name;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getDatadir() {
        return datadir;
    }

    public void setDatadir(String datadir) {
        this.datadir = datadir;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public void setInstalledVersion(String v) {
        this.installedVersion = v;
    }

    public String getDownloadedVersion() {
        return downloadedVersion;
    }

    public void setDownloadedVersion(String v) {
        this.downloadedVersion = v;
    }

    public String getDownloadedOs() {
        return downloadedOs;
    }

    public void setDownloadedOs(String os) {
        this.downloadedOs = os;
    }

    public String getDownloadedArch() {
        return downloadedArch;
    }

    public void setDownloadedArch(String arch) {
        this.downloadedArch = arch;
    }

    public boolean isStep1Completed() {
        return step1Completed;
    }

    public void setStep1Completed(boolean b) {
        this.step1Completed = b;
    }

    public boolean isStep2Completed() {
        return step2Completed;
    }

    public void setStep2Completed(boolean b) {
        this.step2Completed = b;
    }

    public boolean isStep3Completed() {
        return step3Completed;
    }

    public void setStep3Completed(boolean b) {
        this.step3Completed = b;
    }

    public boolean isReady() {
        return isReady;
    }

    public void setReady(boolean b) {
        this.isReady = b;
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    public void setConfigFilePath(String path) {
        this.configFilePath = path;
    }

    public Map<String, String> getConfiguration() {
        return properties;
    }

    public void setConfiguration(Map<String, String> config) {
        this.properties = config;
    }

    public abstract String getEngineKey();
}