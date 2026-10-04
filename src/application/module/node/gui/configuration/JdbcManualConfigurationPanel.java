package application.module.node.gui.configuration;

import net.miginfocom.swing.MigLayout;
import javax.swing.*;

import application.module.database.gui.DatabaseConfigurationPanel;
import application.module.database.utils.DatabaseConfigurationUtils;
import application.module.node.profile.ProfileCreateDefaults;
import application.module.node.props.Props;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.SearchMatchLabel;
import application.utils.io.PathUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Manual (profile-less) JDBC configuration editor for the node's {@code DB.Url}
 * property.
 * <p>
 * Engine-aware: only the fields relevant for the selected engine are shown.
 * SQLite composes the connection from three pre-filled fields (Profile, Path,
 * DB file) and shows an info label explaining that saving a new SQLite
 * configuration under the {@code ./database/SQLite} path automatically creates
 * and is discovered as a new SQLite database profile; the profile name is the
 * last folder of the composed path (the Path field holds the base directory
 * only). MariaDB/PostgreSQL show host, port, database, URL suffix and
 * credentials. A styled, read-only preview renders the composed JDBC URL.
 * </p>
 */
public class JdbcManualConfigurationPanel extends JPanel {
    private final JComboBox<DatabaseConfigurationPanel.DatabaseEngine> engineCombo;
    private final JComboBox<String> hostCombo;
    private final JTextField portField, dbNameField, suffixField, userField;
    private final JLabel engineLabel, hostLabel, portLabel, dbNameLabel, suffixLabel, userLabel, passLabel;
    private final JPasswordField passField;
    private final JCheckBox showPass;
    private final JTextField resultField;
    private final Runnable onChange;

    // SQLite-specific fields: database profile name + file path + database file name
    private final JTextField sqlProfileField, sqlPathField, sqlFileField;
    private final JLabel sqlProfileLabel, sqlPathLabel, sqlFileLabel, sqliteInfoLabel;
    private String originalSqliteUrl;
    private boolean sqliteFieldsEdited = false;
    /**
     * Invoked with the database profile name when the SQLite trio follows the
     * per-profile convention (Path = {@code ./database/SQLite}) and the
     * profile is discoverable in the database module's profile area — the
     * caller can then link the node to that database profile.
     */
    private Consumer<String> onProfileDiscovered;

    public JdbcManualConfigurationPanel(Runnable onChange, String defaultSqliteProfile) {
        super(new MigLayout("insets 0, fillx, gap 2", "[][grow]", ""));
        this.onChange = onChange;
        setOpaque(false);

        engineCombo = new JComboBox<>(DatabaseConfigurationPanel.DatabaseEngine.values());
        // SQLite is the recommended (default) engine: the pre-filled trio is
        // what the user sees first; updateFromUrl() switches the engine when
        // a loaded profile uses a server database.
        engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
        hostCombo = new JComboBox<>(new String[] { "localhost", "127.0.0.1", "::1", "0.0.0.0", "::" });
        hostCombo.setEditable(true);
        hostCombo.setSelectedItem("localhost");
        portField = new JTextField();
        dbNameField = new JTextField();
        suffixField = new JTextField();
        userField = new JTextField();
        passField = new JPasswordField();
        showPass = new JCheckBox("Show Password");
        resultField = new JTextField();
        ConfigurationUtils.styleJdbcPreview(resultField);

        String profileDefault = (defaultSqliteProfile != null && !defaultSqliteProfile.trim().isEmpty())
                ? defaultSqliteProfile.trim()
                : "signum";
        sqlProfileField = new JTextField(profileDefault);
        // The Path field holds the base directory only — the profile name is
        // the LAST folder of the composed path (the preview concatenates it).
        sqlPathField = new JTextField("./database/SQLite");
        sqlFileField = new JTextField(ProfileCreateDefaults.DEFAULT_SQLITE_FILE_NAME);
        sqliteInfoLabel = new JLabel(
                "<html><i>Every SQLite configuration saved under the ./database/SQLite path "
                        + "automatically creates its database profile — the profile name is the "
                        + "last folder of the path.</i></html>");
        sqliteInfoLabel.setOpaque(false);
        sqliteInfoLabel.setForeground(GuiColors.getFaintText());

        engineLabel = new JLabel("Engine:");
        sqlProfileLabel = new JLabel("Profile:");
        sqlPathLabel = new JLabel("Path:");
        sqlFileLabel = new JLabel("DB file:");
        hostLabel = new JLabel("Host:");
        portLabel = new JLabel("Port:");
        dbNameLabel = new JLabel("Database:");
        suffixLabel = new JLabel("Suffix:");
        // The user/password rows are the exact Props.DB_USERNAME /
        // Props.DB_PASSWORD properties — show their keys in the unified style.
        userLabel = SearchMatchLabel.withKeyText("Username:", Props.DB_USERNAME.getName());
        passLabel = SearchMatchLabel.withKeyText("Password:", Props.DB_PASSWORD.getName());

        ConfigurationUtils.styleInputComponent(hostCombo);
        ConfigurationUtils.fixComponentSize(hostCombo);
        ConfigurationUtils.styleInputComponent(portField);
        ConfigurationUtils.styleInputComponent(dbNameField);
        ConfigurationUtils.styleInputComponent(suffixField);
        ConfigurationUtils.styleInputComponent(userField);
        ConfigurationUtils.styleInputComponent(passField);
        ConfigurationUtils.styleInputComponent(sqlProfileField);
        ConfigurationUtils.styleInputComponent(sqlPathField);
        ConfigurationUtils.styleInputComponent(sqlFileField);

        add(engineLabel, "gapright 5");
        add(engineCombo, "growx, wrap");
        add(sqlProfileLabel, "gapright 5");
        add(sqlProfileField, "growx, wrap");
        add(sqlPathLabel, "gapright 5");
        add(sqlPathField, "growx, wrap");
        add(sqlFileLabel, "gapright 5");
        add(sqlFileField, "growx, wrap");
        add(sqliteInfoLabel, "span 2, wrap");
        add(hostLabel, "gapright 5");
        add(hostCombo, "growx, wrap");
        add(portLabel, "gapright 5");
        add(portField, "growx, wrap");
        add(dbNameLabel, "gapright 5");
        add(dbNameField, "growx, wrap");
        add(suffixLabel, "gapright 5");
        add(suffixField, "growx, wrap");
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
            updateFieldVisibility();
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
        suffixField.getDocument().addDocumentListener(dl);
        userField.getDocument().addDocumentListener(dl);
        passField.getDocument().addDocumentListener(dl);

        // SQLite path/file fields: mark the trio as user-edited so the composed
        // URL (instead of the original one) is reported.
        javax.swing.event.DocumentListener sqlDl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onSqliteTrioEdited();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onSqliteTrioEdited();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onSqliteTrioEdited();
            }
        };
        sqlPathField.getDocument().addDocumentListener(sqlDl);
        sqlFileField.getDocument().addDocumentListener(sqlDl);

        // The profile name is the last folder of the composed path — typing it
        // only updates the preview (the Path field stays at the base directory).
        sqlProfileField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onSqliteTrioEdited();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onSqliteTrioEdited();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onSqliteTrioEdited();
            }
        });

        updateFieldVisibility();
        updatePreview();
    }

    /**
     * A SQLite trio field was edited: the composed URL (instead of the
     * original one) must be reported, the preview refreshed, and — when the
     * trio follows the per-profile convention and the database profile is
     * discoverable there — the discovery callback fired.
     */
    private void onSqliteTrioEdited() {
        sqliteFieldsEdited = true;
        updatePreview();
        if (onChange != null)
            onChange.run();
        maybeDiscoverProfile();
    }

    /**
     * True when the given path is the per-profile convention base
     * {@code ./database/SQLite} (SSOT: {@link ProfileCreateDefaults#sqliteDataDir}
     * minus the profile folder itself).
     */
    private static boolean isConventionBasePath(String path) {
        String normalized = path.replace('\\', '/').replaceAll("^\\./", "").replaceAll("/+$", "");
        return normalized.equals("database/SQLite");
    }

    /**
     * Fires the discovery callback when the configured path is the per-profile
     * convention base and a database profile with the configured profile name
     * exists there (its profile.json is present — the same condition the
     * database module's profile load uses).
     */
    private void maybeDiscoverProfile() {
        if (onProfileDiscovered == null || !isSqliteSelected())
            return;
        String profile = sqlProfileField.getText().trim();
        if (profile.isEmpty() || !isConventionBasePath(sqlPathField.getText().trim()))
            return;
        Path profileJson = PathUtils.resolvePath(DatabaseConfigurationUtils.DATABASE_BASE_DIR)
                .resolve(DatabaseConfigurationPanel.DatabaseEngine.SQLITE.toString())
                .resolve(profile).resolve("profile.json");
        if (Files.exists(profileJson))
            onProfileDiscovered.accept(profile);
    }

    /** Only the fields relevant for the selected engine stay visible. */
    private void updateFieldVisibility() {
        boolean sqlite = isSqliteSelected();
        sqlProfileLabel.setVisible(sqlite);
        sqlProfileField.setVisible(sqlite);
        sqlPathLabel.setVisible(sqlite);
        sqlPathField.setVisible(sqlite);
        sqlFileLabel.setVisible(sqlite);
        sqlFileField.setVisible(sqlite);
        sqliteInfoLabel.setVisible(sqlite);
        hostLabel.setVisible(!sqlite);
        hostCombo.setVisible(!sqlite);
        portLabel.setVisible(!sqlite);
        portField.setVisible(!sqlite);
        dbNameLabel.setVisible(!sqlite);
        dbNameField.setVisible(!sqlite);
        suffixLabel.setVisible(!sqlite);
        suffixField.setVisible(!sqlite);
        userLabel.setVisible(!sqlite);
        userField.setVisible(!sqlite);
        passLabel.setVisible(!sqlite);
        passField.setVisible(!sqlite);
        showPass.setVisible(!sqlite);
        revalidate();
        repaint();
    }

    /** Whether the SQLite engine is currently selected. */
    public boolean isSqliteSelected() {
        return DatabaseConfigurationPanel.DatabaseEngine.SQLITE.equals(engineCombo.getSelectedItem());
    }
    private void updatePreview() {
        resultField.setText(getJdbcUrl());
    }

    /**
     * Populates the fields from the given JDBC URL. For SQLite URLs the path is split
     * into directory (Path) and file name (DB file); the database profile name is
     * derived when the path follows the per-profile convention.
     */
    public void updateFromUrl(String url) {
        if (url == null || url.isEmpty())
            return;
        if (url.startsWith("jdbc:sqlite:")) {
            engineCombo.setSelectedItem(DatabaseConfigurationPanel.DatabaseEngine.SQLITE);
            String pathStr = url.substring("jdbc:sqlite:".length());
            if (pathStr.startsWith("file:"))
                pathStr = pathStr.substring(5);
            int paramIdx = pathStr.indexOf('?');
            if (paramIdx != -1)
                pathStr = pathStr.substring(0, paramIdx);
            String normalized = pathStr.replace('\\', '/');
            int idx = normalized.lastIndexOf('/');
            String dir = idx >= 0 ? normalized.substring(0, idx) : ".";
            String file = idx >= 0 ? normalized.substring(idx + 1) : normalized;
            if (dir.isEmpty())
                dir = ".";
            String bareDir = dir.replace('\\', '/').replaceAll("^\\./", "").replaceAll("/+$", "");
            if (bareDir.startsWith("database/SQLite/")) {
                String candidate = bareDir.substring("database/SQLite/".length());
                if (candidate.indexOf('/') < 0 && !candidate.isEmpty()) {
                    // Per-profile convention: the profile name is the last
                    // folder of the path — the Path field holds the base
                    // directory only, the preview concatenates the profile.
                    sqlPathField.setText("./database/SQLite");
                    sqlProfileField.setText(candidate);
                } else {
                    sqlPathField.setText(dir);
                }
            } else {
                // a custom path: the profile name is NOT part of the path
                sqlPathField.setText(dir);
            }
            sqlFileField.setText(file);
            originalSqliteUrl = url;
            sqliteFieldsEdited = false;
        } else {
            originalSqliteUrl = null;
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
                suffixField.setText(m.group(5) != null ? m.group(5) : "");
            }
        }
        updateFieldVisibility();
        updatePreview();
    }

    public String getJdbcUrl() {
        DatabaseConfigurationPanel.DatabaseEngine engine = (DatabaseConfigurationPanel.DatabaseEngine) engineCombo
                .getSelectedItem();
        if (engine == DatabaseConfigurationPanel.DatabaseEngine.SQLITE) {
            String composed = composeSqliteUrl();
            // Preserve the original URL string as long as the trio is untouched,
            // so loading a profile does not introduce a spurious unsaved change.
            if (!sqliteFieldsEdited && originalSqliteUrl != null)
                return originalSqliteUrl;
            return composed;
        }
        String protocol = engine == DatabaseConfigurationPanel.DatabaseEngine.MARIADB ? "mariadb" : "postgresql";
        String host = hostCombo.getSelectedItem() != null ? hostCombo.getSelectedItem().toString() : "";
        StringBuilder sb = new StringBuilder("jdbc:").append(protocol).append("://").append(host);
        if (!portField.getText().trim().isEmpty())
            sb.append(":").append(portField.getText().trim());
        sb.append("/").append(dbNameField.getText());
        if (!suffixField.getText().trim().isEmpty())
            sb.append(suffixField.getText().trim());
        return sb.toString();
    }

    /**
     * Composes the canonical SQLite URL from the Profile/Path/DB file fields.
     * While the Path is the per-profile convention base
     * ({@code ./database/SQLite}), the profile name is the last folder of the
     * composed path — the field itself does not repeat it.
     */
    private String composeSqliteUrl() {
        String dir = sqlPathField.getText().trim().replace('\\', '/');
        while (dir.endsWith("/"))
            dir = dir.substring(0, dir.length() - 1);
        if (dir.isEmpty())
            dir = ".";
        if (!dir.startsWith("."))
            dir = "./" + dir;
        String profile = sqlProfileField.getText().trim();
        if (!profile.isEmpty() && isConventionBasePath(dir))
            dir = dir + "/" + profile;
        String file = sqlFileField.getText().trim();
        return file.isEmpty() ? "jdbc:sqlite:file:" + dir : "jdbc:sqlite:file:" + dir + "/" + file;
    }

    public String getUsername() {
        return isSqliteSelected() ? "" : userField.getText();
    }

    public String getPassword() {
        return isSqliteSelected() ? "" : new String(passField.getPassword());
    }

    /** The database profile name the SQLite configuration belongs to (or creates). */
    public String getSqliteProfile() {
        return sqlProfileField.getText().trim();
    }

    /** The directory the SQLite database file lives in. */
    public String getSqlitePath() {
        return sqlPathField.getText().trim();
    }

    /** The SQLite database file name. */
    public String getSqliteFile() {
        return sqlFileField.getText().trim();
    }

    /**
     * Sets the database-profile discovery callback (see
     * {@link #onProfileDiscovered}). Pass {@code null} to detach.
     */
    public void setOnProfileDiscovered(Consumer<String> onProfileDiscovered) {
        this.onProfileDiscovered = onProfileDiscovered;
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

    public JComponent getSuffixField() {
        return suffixField;
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

    public JComponent getSqlProfileField() {
        return sqlProfileField;
    }

    public JComponent getSqlPathField() {
        return sqlPathField;
    }

    public JComponent getSqlFileField() {
        return sqlFileField;
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

    public JLabel getSuffixLabel() {
        return suffixLabel;
    }

    public JLabel getUserLabel() {
        return userLabel;
    }

    public JLabel getPassLabel() {
        return passLabel;
    }

    public JLabel getSqlProfileLabel() {
        return sqlProfileLabel;
    }

    public JLabel getSqlPathLabel() {
        return sqlPathLabel;
    }

    public JLabel getSqlFileLabel() {
        return sqlFileLabel;
    }
}

