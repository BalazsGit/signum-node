package application.module.node.gui.configuration;

import net.miginfocom.swing.MigLayout;
import javax.swing.*;

import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.props.Props;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.SearchMatchLabel;

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
    /**
     * Depth of programmatic (non-user) updates. A depth counter — not a plain
     * boolean — because the combo action listeners fire synchronously inside
     * the mutating calls and their cascading refreshes must not be able to
     * reset the guard mid-update.
     */
    private int programmaticDepth = 0;
    private DatabaseConfigurationUtils.DbProfile currentProfile;

    public JdbcProfileConfigurationPanel(String confFolder, Runnable onChange) {
        // hidemode 3 (full): hidden engine-specific rows are removed from the
        // layout entirely (default hidemode 0 reserved their vertical space,
        // leaving a dead gap, and their wide fields still widened the column).
        super(new MigLayout("insets 0, fillx, gap 2, hidemode 3", "[][grow]", ""));
        this.confFolder = confFolder;
        this.onChange = onChange;
        setOpaque(false);

        engineCombo = new JComboBox<>(DatabaseConfigurationPanel.DatabaseEngine.values());
        // SQLite is the recommended (default) engine — the same default the
        // manual panel shows first.
        engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
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
        ConfigurationUtils.styleJdbcPreview(resultField);

        engineLabel = new JLabel("Engine:");
        profileLabel = new JLabel("Profile:");
        dbLabel = new JLabel("Database:");
        hostLabel = new JLabel("Host:");
        portLabel = new JLabel("Port:");
        suffixLabel = new JLabel("Suffix:");
        // The user/password rows are the exact Props.DB_USERNAME /
        // Props.DB_PASSWORD properties — show their keys in the unified style.
        userLabel = SearchMatchLabel.withKeyText("User:", Props.DB_USERNAME.getName());
        passLabel = SearchMatchLabel.withKeyText("Password:", Props.DB_PASSWORD.getName());

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
            // A new engine gets a fresh server input structure: a stale host
            // (e.g. the empty one left behind by a SQLite database) must not
            // leak into the new engine's fields.
            if (!DatabaseConfigurationPanel.DatabaseEngine.SQLITE
                    .equals(engineCombo.getSelectedItem()))
                hostCombo.setSelectedItem(hostCombo.getItemAt(0));
            refreshProfiles();
            updateFieldVisibility();
            if (programmaticDepth == 0 && onChange != null)
                onChange.run();
        });
        profileCombo.addActionListener(e -> {
            refreshDatabases();
            if (programmaticDepth == 0 && onChange != null)
                onChange.run();
        });
        dbCombo.addActionListener(e -> {
            refreshUsers();
            if (programmaticDepth == 0 && onChange != null)
                onChange.run();
        });

        hostCombo.addActionListener(e -> {
            updatePreview();
            if (programmaticDepth == 0 && onChange != null)
                onChange.run();
        });

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            private void update() {
                updatePreview();
                if (programmaticDepth == 0 && onChange != null)
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
            if (programmaticDepth == 0 && onChange != null)
                onChange.run();
        });
        refreshProfiles();
        updateFieldVisibility();
    }

    /** Only the fields relevant for the selected engine stay visible. */
    private void updateFieldVisibility() {
        boolean sqlite = DatabaseConfigurationPanel.DatabaseEngine.SQLITE
                .equals(engineCombo.getSelectedItem());
        hostLabel.setVisible(!sqlite);
        hostCombo.setVisible(!sqlite);
        portLabel.setVisible(!sqlite);
        portField.setVisible(!sqlite);
        suffixLabel.setVisible(!sqlite);
        suffixField.setVisible(!sqlite);
        userLabel.setVisible(!sqlite);
        userCombo.setVisible(!sqlite);
        passLabel.setVisible(!sqlite);
        passField.setVisible(!sqlite);
        showPass.setVisible(!sqlite);
        revalidate();
        repaint();
    }

    private void refreshProfiles() {
        programmaticDepth++;
        profileCombo.removeAllItems();
        profileCombo.addItem("");
        currentProfile = null;
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        if (engine != null) {
            DatabaseConfigurationUtils.getProfileNames(confFolder, engine.toString()).forEach(profileCombo::addItem);
        }
        if (programmaticDepth > 0)
            programmaticDepth--;
        refreshDatabases();
    }

    private void refreshDatabases() {
        programmaticDepth++;
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
        dbCombo.setSelectedIndex(-1); // a re-populated list must not keep a stale "selection"
        if (programmaticDepth > 0)
            programmaticDepth--;
        refreshUsers();
    }

    private void refreshUsers() {
        programmaticDepth++;
        userCombo.removeAllItems();
        DatabaseConfigurationUtils.DbInstance db = (DatabaseConfigurationUtils.DbInstance) dbCombo.getSelectedItem();
        if (db != null) {
            // Add users specific to this database instance
            db.users.forEach(userCombo::addItem);
            if (userCombo.getItemCount() > 0) {
                // Default to the instance's first user (the listener fills the password row)
                userCombo.setSelectedItem(userCombo.getItemAt(0));
            }
            updateFieldsFromUrl(db.url);
        } else {
            userCombo.setSelectedIndex(-1); // a re-populated list must not keep a stale "selection"
            portField.setText("");
            suffixField.setText("");
            passField.setText("");
        }
        updatePreview();
        if (programmaticDepth > 0)
            programmaticDepth--;
    }

    /**
     * Materializes a database profile link in this card: selects the engine,
     * refreshes the profile list (a combo fires no event for an unchanged
     * selection, so the list is re-scanned explicitly), selects the profile
     * and picks the database instance the {@code preferredDbUrl} points at —
     * falling back to the first instance — so every dependent row (database,
     * host, port, suffix, user, password, URL preview) reflects the linked
     * profile's real configuration instead of a half-empty card.
     *
     * @param engine            the link's engine; {@code null} rejects the link
     * @param profileName       the link's profile name; an empty name keeps the
     *                          engine selection but resets the profile/instance rows
     * @param preferredDbUrl    the database URL to prefer in the instance list
     *                          (e.g. the node profile's saved JDBC URL), or {@code null}
     * @param preferredUsername the user to prefer in the instance's user list
     *                          (e.g. the node profile's saved user), or {@code null}
     * @return {@code true} when a non-empty profile exists and the card now shows it
     */
    public boolean applyLinkedProfile(DatabaseConfigurationPanel.DatabaseEngine engine, String profileName,
            String preferredDbUrl, String preferredUsername) {
        if (engine == null) {
            return false;
        }
        programmaticDepth++;
        try {
            boolean engineUnchanged = engine.equals(engineCombo.getSelectedItem());
            engineCombo.setSelectedItem(engine);
            if (engineUnchanged) {
                refreshProfiles(); // no event on an unchanged selection — keep the list current
            }
            if (profileName == null || profileName.isEmpty()) {
                // Transient state: the engine is set, the profile/instance rows reset.
                if (!"".equals(profileCombo.getSelectedItem())) {
                    profileCombo.setSelectedItem("");
                } else {
                    refreshDatabases();
                }
                return false;
            }
            if (!comboContains(profileCombo, profileName)) {
                return false; // unknown or deleted profile — the link cannot be materialized
            }
            boolean profileUnchanged = profileName.equals(profileCombo.getSelectedItem());
            profileCombo.setSelectedItem(profileName);
            if (profileUnchanged) {
                refreshDatabases(); // no event on an unchanged selection — keep the instances current
            }
            selectDatabaseInstance(preferredDbUrl, preferredUsername);
            return true;
        } finally {
            if (programmaticDepth > 0)
                programmaticDepth--;
        }
    }

    /**
     * Resets the card to its neutral, unlinked state: no profile, no database
     * instance, the dependent rows empty (the manual card takes over).
     */
    public void clearLinkedProfile() {
        programmaticDepth++;
        try {
            if (!comboContains(profileCombo, "")) {
                refreshProfiles(); // the list is empty — rebuild it ("" is item 0)
            }
            if (!"".equals(profileCombo.getSelectedItem())) {
                profileCombo.setSelectedItem("");
            } else if (dbCombo.getItemCount() > 0) {
                refreshDatabases(); // the "" selection did not fire — clear the instance rows
            }
        } finally {
            if (programmaticDepth > 0)
                programmaticDepth--;
        }
    }

    /**
     * Fills the database row's selection gap after a link was applied: picks
     * the instance whose URL matches {@code preferredDbUrl} when the profile
     * offers it, otherwise the first instance — and that instance's user
     * matching {@code preferredUsername}, otherwise the first user, so the
     * credential rows carry the profile's real values. A selection already
     * present (a user's pick) is kept as-is.
     */
    private void selectDatabaseInstance(String preferredDbUrl, String preferredUsername) {
        if (dbCombo.getSelectedItem() != null || dbCombo.getItemCount() == 0) {
            return;
        }
        DatabaseConfigurationUtils.DbInstance preferred = null;
        String wantedUrl = preferredDbUrl != null ? preferredDbUrl.trim() : "";
        if (!wantedUrl.isEmpty()) {
            for (int i = 0; i < dbCombo.getItemCount(); i++) {
                DatabaseConfigurationUtils.DbInstance db = (DatabaseConfigurationUtils.DbInstance) dbCombo.getItemAt(i);
                if (db != null && db.url != null && wantedUrl.equalsIgnoreCase(db.url.trim())) {
                    preferred = db;
                    break;
                }
            }
        }
        dbCombo.setSelectedItem(preferred != null ? preferred : dbCombo.getItemAt(0));
        // The instance's action listener repopulated the user list and selected
        // its first user — honor the preferred username when it is offered.
        if (userCombo.getItemCount() > 0) {
            String wantedUser = preferredUsername != null ? preferredUsername.trim() : "";
            if (!wantedUser.isEmpty()) {
                for (int i = 0; i < userCombo.getItemCount(); i++) {
                    DatabaseConfigurationUtils.DbUser user = (DatabaseConfigurationUtils.DbUser) userCombo.getItemAt(i);
                    if (user != null && wantedUser.equalsIgnoreCase(user.username)) {
                        userCombo.setSelectedItem(user);
                        break;
                    }
                }
            }
        }
    }

    /** Whether the given item is in the combo's list (JComboBox has no index lookup). */
    private static boolean comboContains(JComboBox<?> combo, Object item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (java.util.Objects.equals(combo.getItemAt(i), item)) {
                return true;
            }
        }
        return false;
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