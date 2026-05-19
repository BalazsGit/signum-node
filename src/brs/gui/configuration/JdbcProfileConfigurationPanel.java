package brs.gui.configuration;

import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationPanel;
import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationUtils;
import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.util.List;

public class JdbcProfileConfigurationPanel extends JPanel {
    private final JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo;
    private final JComboBox<String> profileCombo;
    private final JComboBox<DatabaseConfigurationUtils.DbInstance> dbCombo;
    private final JComboBox<DatabaseConfigurationUtils.DbUser> userCombo;
    private final JPasswordField passField;
    private final JCheckBox showPass;
    private final JTextField resultField;
    private final String confFolder;
    private final Runnable onChange;
    private boolean isProgrammatic = false;

    public JdbcProfileConfigurationPanel(String confFolder, Runnable onChange) {
        super(new MigLayout("insets 0, fillx, gap 2", "[][grow]", ""));
        this.confFolder = confFolder;
        this.onChange = onChange;
        setOpaque(false);

        engineCombo = new JComboBox<>(DatabaseConfigurationPanel.DatabaseEngine.values());
        profileCombo = new JComboBox<>();
        dbCombo = new JComboBox<>();
        userCombo = new JComboBox<>();
        passField = new JPasswordField();
        passField.setEditable(false);
        showPass = new JCheckBox("Show Password");
        showPass.setOpaque(false);
        resultField = new JTextField();
        resultField.setEditable(false);
        resultField.setBorder(null);
        resultField.setOpaque(false);
        resultField.setFont(resultField.getFont().deriveFont(java.awt.Font.BOLD));

        ConfigurationUtils.styleInputComponent(passField);

        add(new JLabel("Engine:"), "gapright 5");
        add(engineCombo, "growx, wrap");
        add(new JLabel("Profile:"), "gapright 5");
        add(profileCombo, "growx, wrap");
        add(new JLabel("Database:"), "gapright 5");
        add(dbCombo, "growx, wrap");
        add(new JLabel("User:"), "gapright 5");
        add(userCombo, "growx, wrap");
        add(new JLabel("Password:"), "gapright 5");
        add(passField, "growx, wrap");
        add(showPass, "skip 1, wrap, gapbottom 5");
        add(new JLabel("JDBC URL Preview:"), "span 2, gaptop 5, wrap");
        add(resultField, "span 2, growx");

        char defaultEchoChar = passField.getEchoChar();
        showPass.addActionListener(e -> passField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar));

        engineCombo.addActionListener(e -> refreshProfiles());
        profileCombo.addActionListener(e -> refreshDatabases());
        dbCombo.addActionListener(e -> refreshUsers());
        userCombo.addActionListener(e -> {
            DatabaseConfigurationUtils.DbUser user = (DatabaseConfigurationUtils.DbUser) userCombo.getSelectedItem();
            if (user != null)
                passField.setText(user.password);
            if (!isProgrammatic && onChange != null)
                onChange.run();
        });
        refreshProfiles();
    }

    private void refreshProfiles() {
        isProgrammatic = true;
        profileCombo.removeAllItems();
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        if (engine != null) {
            DatabaseConfigurationUtils.getProfileNames(confFolder, engine.name()).forEach(profileCombo::addItem);
        }
        isProgrammatic = false;
        refreshDatabases();
    }

    private void refreshDatabases() {
        isProgrammatic = true;
        dbCombo.removeAllItems();
        String pName = (String) profileCombo.getSelectedItem();
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        if (pName != null && engine != null) {
            DatabaseConfigurationUtils.DbProfile profile = DatabaseConfigurationUtils.loadProfile(confFolder,
                    engine.name(), pName);
            if (profile != null)
                profile.databases.forEach(dbCombo::addItem);
        }
        isProgrammatic = false;
        refreshUsers();
    }

    private void refreshUsers() {
        isProgrammatic = true;
        userCombo.removeAllItems();
        DatabaseConfigurationUtils.DbInstance db = (DatabaseConfigurationUtils.DbInstance) dbCombo.getSelectedItem();
        if (db != null) {
            db.users.forEach(userCombo::addItem);
            resultField.setText(db.url);
        }
        isProgrammatic = false;
    }

    public String getJdbcUrl() {
        return resultField.getText();
    }

    public String getUsername() {
        return userCombo.getSelectedItem() != null ? userCombo.getSelectedItem().toString() : "";
    }

    public String getPassword() {
        return new String(passField.getPassword());
    }
}