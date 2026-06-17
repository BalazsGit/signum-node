package application.module.database.gui;

import com.google.gson.JsonObject;

import javax.swing.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public interface DatabaseEnginePanel {
    String getEngineName();

    Path getProfilePath(String profileName);

    void loadProfile(String profileName, GlobalSettings globalSettings);

    void resetToDefaults(GlobalSettings globalSettings);

    String getUnsavedChangesReport();

    boolean hasUnsavedChanges();

    void updateUIFromData();

    void refreshUIColors();

    void setLoadedProfileName(String name);

    String getLoadedProfileName();

    void setRunningProfileName(String name);

    String getRunningProfileName();

    void setActiveProfileName(String name);

    String getActiveProfileName();

    JsonObject getCurrentProfileSettings();

    void setAppliedProfileSettings(JsonObject settings);

    void setOnDirtyStatusChanged(Runnable listener);

    List<DatabaseConfigurationPanel.PropertyRow> getAllPropertyRows();

    Map<String, JComponent> getPropertyComponents();

    Map<String, Supplier<String>> getValueSuppliers();

    Map<String, String> getHelpTexts();

    Map<String, String> getDefaultValues();

    void setPathLabel(JLabel label);

    void setDownloadDatabaseBtn(JButton button);

    void setDownloadStatusLabel(JLabel label);

    void setStep1StatusIcon(JLabel label);

    void setStep2StatusIcon(JLabel label);

    void setGlobalSettings(GlobalSettings globalSettings);
}