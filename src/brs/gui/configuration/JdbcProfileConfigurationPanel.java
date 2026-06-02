package brs.gui.configuration;

import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationPanel;
import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationUtils;
import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.util.List;
import java.util.regex.Matcher;

public class JdbcProfileConfigurationPanel extends JPanel {
    private final JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo;
    private final JComboBox<String> profileCombo;
    private final JComboBox<DatabaseConfigurationUtils.DbInstance> dbCombo;
    private final JComboBox<String> hostCombo;
    private final JTextField portField;
    private final JTextField suffixField;
    private final JComboBox<DatabaseConfigurationUtils.DbUser> userCombo;
    private final JPasswordField passField;
    private final JLabel engineLabel, profileLabel, dbLabel, hostLabel, portLabel, suffixLabel, userLabel, passLabel;
    private final JCheckBox showPass;
    private final JTextField resultField;
    private final String confFolder;
    private final Runnable onChange;
    private boolean isProgrammatic = false;
    private DatabaseConfigurationUtils.DbProfile currentProfile;

    public JdbcProfileConfigurationPanel(String confFolder, Runnable onChange) {
        super(new MigLayout("insets 0, fillx, gap 2", "[][grow]", ""));
        this.confFolder = confFolder;
        this.onChange = onChange;
        setOpaque(false);

        engineCombo = new JComboBox<>(DatabaseConfigurationPanel.DatabaseEngine.values());
        profileCombo = new JComboBox<>();
        dbCombo = new JComboBox<>();
        hostCombo = new JComboBox<>(new String[] { "localhost", "127.0.0.1", "::1", "0.0.0.0", "::" });
        hostCombo.setEditable(true);
        hostCombo.setSelectedItem("localhost");
        portField = new JTextField();
        suffixField = new JTextField();
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

        engineLabel = new JLabel("Engine:");
        profileLabel = new JLabel("Profile:");
        dbLabel = new JLabel("Database:");
        hostLabel = new JLabel("Host:");
        portLabel = new JLabel("Port:");
        suffixLabel = new JLabel("Suffix:");
        userLabel = new JLabel("User:");
        passLabel = new JLabel("Password:");

        ConfigurationUtils.styleInputComponent(hostCombo);
        ConfigurationUtils.styleInputComponent(portField);
        ConfigurationUtils.styleInputComponent(suffixField);
        ConfigurationUtils.styleInputComponent(passField);

        add(engineLabel, "gapright 5");
        add(engineCombo, "growx, wrap");
        add(profileLabel, "gapright 5");
        add(profileCombo, "growx, wrap");
        add(dbLabel, "gapright 5");
        add(dbCombo, "growx, wrap");
        add(hostLabel, "gapright 5");
        add(hostCombo, "growx, wrap");
        add(portLabel, "gapright 5");
        add(portField, "growx, wrap");
        add(suffixLabel, "gapright 5");
        add(suffixField, "growx, wrap");
        add(userLabel, "gapright 5");
        add(userCombo, "growx, wrap");
        add(passLabel, "gapright 5");
        add(passField, "growx, wrap");
        add(showPass, "skip 1, wrap, gapbottom 5");
        add(new JLabel("JDBC URL Preview:"), "span 2, gaptop 5, wrap");
        add(resultField, "span 2, growx");

        char defaultEchoChar = passField.getEchoChar();
        showPass.addActionListener(e -> {
            passField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar);
        });

        engineCombo.addActionListener(e -> {
            refreshProfiles();
            if (!isProgrammatic && onChange != null)
                onChange.run();
        });
        profileCombo.addActionListener(e -> {
            refreshDatabases();
            if (!isProgrammatic && onChange != null)
                onChange.run();
        });
        dbCombo.addActionListener(e -> {
            refreshUsers();
            if (!isProgrammatic && onChange != null)
                onChange.run();
        });

        hostCombo.addActionListener(e -> {
            updatePreview();
            if (!isProgrammatic && onChange != null)
                onChange.run();
        });

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            private void update() {
                updatePreview();
                if (!isProgrammatic && onChange != null)
                    onChange.run();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                update();
            }
        };
        ((JTextField) hostCombo.getEditor().getEditorComponent()).getDocument().addDocumentListener(dl);
        portField.getDocument().addDocumentListener(dl);
        suffixField.getDocument().addDocumentListener(dl);

        userCombo.addActionListener(e -> {
            Object selected = userCombo.getSelectedItem();
            if (selected instanceof DatabaseConfigurationUtils.DbUser) {
                DatabaseConfigurationUtils.DbUser user = (DatabaseConfigurationUtils.DbUser) selected;
                passField.setText(user.password != null ? user.password : "");
            } else {
                passField.setText("");
            }
            if (!isProgrammatic && onChange != null)
                onChange.run();
        });
        refreshProfiles();
    }

    private void refreshProfiles() {
        isProgrammatic = true;
        profileCombo.removeAllItems();
        profileCombo.addItem("");
        currentProfile = null;
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        if (engine != null) {
            DatabaseConfigurationUtils.getProfileNames(confFolder, engine.toString()).forEach(profileCombo::addItem);
        }
        isProgrammatic = false;
        refreshDatabases();
    }

    private void refreshDatabases() {
        isProgrammatic = true;
        dbCombo.removeAllItems();
        currentProfile = null;
        String pName = (String) profileCombo.getSelectedItem();
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        if (pName != null && engine != null) {
            DatabaseConfigurationUtils.DbProfile profile = DatabaseConfigurationUtils.loadProfile(confFolder,
                    engine.toString(), pName);
            if (profile != null) {
                this.currentProfile = profile;
                profile.databases.forEach(dbCombo::addItem);
            }
        }
        isProgrammatic = false;
        refreshUsers();
    }

    private void refreshUsers() {
        isProgrammatic = true;
        userCombo.removeAllItems();
        DatabaseConfigurationUtils.DbInstance db = (DatabaseConfigurationUtils.DbInstance) dbCombo.getSelectedItem();
        if (db != null) {
            // Add users specific to this database instance
            db.users.forEach(userCombo::addItem);
            updateFieldsFromUrl(db.url);
        } else {
            portField.setText("");
            suffixField.setText("");
            passField.setText("");
        }
        updatePreview();
        isProgrammatic = false;
    }

    private void updateFieldsFromUrl(String url) {
        if (url == null || url.isEmpty())
            return;
        if (url.startsWith("jdbc:sqlite:")) {
            hostCombo.setSelectedItem("");
            portField.setText("");
            suffixField.setText("");
        } else {
            Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url);
            if (m.find()) {
                hostCombo.setSelectedItem(m.group(2));
                portField.setText(m.group(3) != null ? m.group(3) : "");
                suffixField.setText(m.group(5) != null ? m.group(5) : "");
            }
        }
    }

    private void updatePreview() {
        resultField.setText(getJdbcUrl());
    }

    public String getJdbcUrl() {
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        DatabaseConfigurationUtils.DbInstance db = (DatabaseConfigurationUtils.DbInstance) dbCombo.getSelectedItem();
        String dbName = db != null ? db.name : "";
        if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE) {
            if (db != null && db.url.startsWith("jdbc:sqlite:"))
                return db.url;
            return "jdbc:sqlite:" + dbName;
        }
        String protocol = engine == DatabaseConfigurationPanel.DatabaseEngine.MARIADB ? "mariadb" : "postgresql";
        String host = hostCombo.getSelectedItem() != null ? hostCombo.getSelectedItem().toString() : "";
        StringBuilder sb = new StringBuilder("jdbc:").append(protocol).append("://").append(host);
        String port = portField.getText().trim();
        if (!port.isEmpty())
            sb.append(":").append(port);
        sb.append("/").append(dbName);
        String suffix = suffixField.getText().trim();
        if (!suffix.isEmpty())
            sb.append(suffix);
        return sb.toString();
    }

    public String getUsername() {
        return userCombo.getSelectedItem() != null ? userCombo.getSelectedItem().toString() : "";
    }

    public String getPassword() {
        return new String(passField.getPassword());
    }

    public JComponent getEngineCombo() {
        return engineCombo;
    }

    public JComponent getProfileCombo() {
        return profileCombo;
    }

    public JComponent getDbCombo() {
        return dbCombo;
    }

    public JComponent getHostField() {
        return hostCombo;
    }

    public JComponent getPortField() {
        return portField;
    }

    public JComponent getSuffixField() {
        return suffixField;
    }

    public JComponent getUserCombo() {
        return userCombo;
    }

    public JComponent getPassField() {
        return passField;
    }

    public JLabel getEngineLabel() {
        return engineLabel;
    }

    public JLabel getProfileLabel() {
        return profileLabel;
    }

    public JLabel getDbLabel() {
        return dbLabel;
    }

    public JLabel getHostLabel() {
        return hostLabel;
    }

    public JLabel getPortLabel() {
        return portLabel;
    }

    public JLabel getSuffixLabel() {
        return suffixLabel;
    }

    public JLabel getUserLabel() {
        return userLabel;
    }

    public JLabel getPassLabel() {
        return passLabel;
    }
}