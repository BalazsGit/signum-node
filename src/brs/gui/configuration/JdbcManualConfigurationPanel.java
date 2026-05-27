package brs.gui.configuration;

import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationPanel;
import brs.gui.configuration.databaseConfiguration.DatabaseConfigurationUtils;
import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;
import java.util.regex.Matcher;

public class JdbcManualConfigurationPanel extends JPanel {
    private final JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo;
    private final JComboBox<String> hostCombo;
    private final JTextField portField, dbNameField, postfixField, userField;
    private final JLabel engineLabel, hostLabel, portLabel, dbNameLabel, postfixLabel, userLabel, passLabel;
    private final JPasswordField passField;
    private final JTextField resultField;
    private final JCheckBox showPass;
    private final Runnable onChange;

    public JdbcManualConfigurationPanel(Runnable onChange) {
        super(new MigLayout("insets 0, fillx, gap 2", "[][grow]", ""));
        this.onChange = onChange;
        setOpaque(false);

        engineCombo = new JComboBox<>(DatabaseConfigurationPanel.DatabaseEngine.values());
        hostCombo = new JComboBox<>(new String[] { "localhost", "127.0.0.1", "::1", "0.0.0.0", "::" });
        hostCombo.setEditable(true);
        hostCombo.setSelectedItem("localhost");
        portField = new JTextField();
        dbNameField = new JTextField();
        postfixField = new JTextField();
        userField = new JTextField();
        passField = new JPasswordField();
        showPass = new JCheckBox("Show Password");
        resultField = new JTextField();
        resultField.setEditable(false);
        resultField.setBorder(null);
        resultField.setOpaque(false);
        resultField.setFont(resultField.getFont().deriveFont(Font.BOLD));

        engineLabel = new JLabel("Engine:");
        hostLabel = new JLabel("Host:");
        portLabel = new JLabel("Port:");
        dbNameLabel = new JLabel("Database:");
        postfixLabel = new JLabel("Postfix:");
        userLabel = new JLabel("Username:");
        passLabel = new JLabel("Password:");

        ConfigurationUtils.styleInputComponent(hostCombo);
        ConfigurationUtils.fixComponentSize(hostCombo);
        ConfigurationUtils.styleInputComponent(portField);
        ConfigurationUtils.styleInputComponent(dbNameField);
        ConfigurationUtils.styleInputComponent(postfixField);
        ConfigurationUtils.styleInputComponent(userField);
        ConfigurationUtils.styleInputComponent(passField);

        add(engineLabel, "gapright 5");
        add(engineCombo, "growx, wrap");
        add(hostLabel, "gapright 5");
        add(hostCombo, "growx, wrap");
        add(portLabel, "gapright 5");
        add(portField, "growx, wrap");
        add(dbNameLabel, "gapright 5");
        add(dbNameField, "growx, wrap");
        add(postfixLabel, "gapright 5");
        add(postfixField, "growx, wrap");
        add(userLabel, "gapright 5");
        add(userField, "growx, wrap");
        add(passLabel, "gapright 5");
        add(passField, "growx, wrap");
        add(showPass, "skip 1, wrap, gapbottom 5");
        add(new JLabel("JDBC URL Preview:"), "span 2, gaptop 5, wrap");
        add(resultField, "span 2, growx");

        char defaultEchoChar = passField.getEchoChar();
        showPass.addActionListener(e -> passField.setEchoChar(showPass.isSelected() ? (char) 0 : defaultEchoChar));

        engineCombo.addActionListener(e -> {
            DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                    .getSelectedItem();
            boolean sqlite = engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE;
            hostCombo.setEnabled(!sqlite);
            portField.setEnabled(!sqlite);
            postfixField.setEnabled(!sqlite);
            userField.setEnabled(!sqlite);
            passField.setEnabled(!sqlite);
            showPass.setEnabled(!sqlite);
            updatePreview();
            if (onChange != null)
                onChange.run();
        });

        hostCombo.addActionListener(e -> {
            updatePreview();
            if (onChange != null)
                onChange.run();
        });

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updatePreview();
                if (onChange != null)
                    onChange.run();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updatePreview();
                if (onChange != null)
                    onChange.run();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updatePreview();
                if (onChange != null)
                    onChange.run();
            }
        };
        ((JTextField) hostCombo.getEditor().getEditorComponent()).getDocument().addDocumentListener(dl);
        portField.getDocument().addDocumentListener(dl);
        dbNameField.getDocument().addDocumentListener(dl);
        postfixField.getDocument().addDocumentListener(dl);
        userField.getDocument().addDocumentListener(dl);
        passField.getDocument().addDocumentListener(dl);
    }

    private void updatePreview() {
        resultField.setText(getJdbcUrl());
    }

    public void updateFromUrl(String url) {
        if (url == null || url.isEmpty())
            return;
        if (url.startsWith("jdbc:sqlite:")) {
            engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
            dbNameField.setText(url.substring("jdbc:sqlite:".length()));
        } else {
            Matcher m = DatabaseConfigurationUtils.JDBC_URL_PATTERN.matcher(url);
            if (m.find()) {
                String engine = m.group(1);
                if ("mariadb".equalsIgnoreCase(engine))
                    engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.MARIADB);
                else if ("postgresql".equalsIgnoreCase(engine))
                    engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.POSTGRESQL);
                hostCombo.setSelectedItem(m.group(2));
                portField.setText(m.group(3) != null ? m.group(3) : "");
                dbNameField.setText(m.group(4));
                postfixField.setText(m.group(5) != null ? m.group(5) : "");
            }
        }
        updatePreview();
    }

    public String getJdbcUrl() {
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE)
            return "jdbc:sqlite:" + dbNameField.getText();
        String protocol = engine == DatabaseConfigurationPanel.DatabaseEngine.MARIADB ? "mariadb" : "postgresql";
        String host = hostCombo.getSelectedItem() != null ? hostCombo.getSelectedItem().toString() : "";
        StringBuilder sb = new StringBuilder("jdbc:").append(protocol).append("://").append(host);
        if (!portField.getText().trim().isEmpty())
            sb.append(":").append(portField.getText().trim());
        sb.append("/").append(dbNameField.getText());
        if (!postfixField.getText().trim().isEmpty())
            sb.append(postfixField.getText().trim());
        return sb.toString();
    }

    public String getUsername() {
        return userField.getText();
    }

    public String getPassword() {
        return new String(passField.getPassword());
    }

    public void setCredentials(String u, String p) {
        userField.setText(u);
        passField.setText(p);
    }

    public JComponent getEngineCombo() {
        return engineCombo;
    }

    public JComponent getHostField() {
        return hostCombo;
    }

    public JComponent getPortField() {
        return portField;
    }

    public JComponent getDbNameField() {
        return dbNameField;
    }

    public JComponent getPostfixField() {
        return postfixField;
    }

    public JComponent getUserField() {
        return userField;
    }

    public JComponent getPassField() {
        return passField;
    }

    public JComponent getResultField() {
        return resultField;
    }

    public JComponent getShowPass() {
        return showPass;
    }

    public JLabel getEngineLabel() {
        return engineLabel;
    }

    public JLabel getHostLabel() {
        return hostLabel;
    }

    public JLabel getPortLabel() {
        return portLabel;
    }

    public JLabel getDbNameLabel() {
        return dbNameLabel;
    }

    public JLabel getPostfixLabel() {
        return postfixLabel;
    }

    public JLabel getUserLabel() {
        return userLabel;
    }

    public JLabel getPassLabel() {
        return passLabel;
    }
}