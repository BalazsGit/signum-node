package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.configuration.JdbcManualConfigurationPanel;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.profile.NodeProfileRepository;
import application.module.node.profile.ProfileNameSuggester;
import application.utils.gui.GuiFontManager;
import jiconfont.icons.font_awesome.FontAwesome;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Wizard step 1.2 — database settings for the node profile.
 * <p>
 * Server engines (collected here, separated from the installation step, plan §1.2):
 * host, port, username, password and database name, with an optional TCP
 * reachability check. These values are persisted into the node profile via the
 * {@link ProfileCreateDefaults} SSOT at finish time (see
 * {@link application.module.node.gui.wizard.WizardFinish}).
 * </p>
 * <p>
 * Shown for <b>every</b> engine — for SQLite it is the "database configuration"
 * step of the 3-step flow: the node profile configuration panel's own {@code DB.Url}
 * editor ({@link JdbcManualConfigurationPanel} — the Profile/Path/DB-file trio with
 * the live JDBC URL preview, the same structure and URL-composition logic) is
 * embedded instead of the server fields (no host/port validation). An untouched
 * trio means the per-profile SQLite file URL is derived at finish from the
 * profile name; an edited trio persists the composed URL.
 * </p>
 */
public class DatabaseConnectionStep implements WizardStep {

    private final JPanel panel = new JPanel();
    private final JTextField hostField = new JTextField("localhost", 14);
    private final JTextField portField = new JTextField("3306", 6);
    private final JTextField userField = new JTextField("signum", 14);
    private final JTextField dbField = new JTextField("signum", 14);
    private final JPasswordField passwordField = new JPasswordField(14);
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel hintLabel = new JLabel();
    private final JPanel fieldsPanel = new JPanel();
    private final CardLayout contentCards = new CardLayout();
    private final JPanel contentPanel = new JPanel(contentCards);
    private boolean engineResolved = false;

    /**
     * The SQLite DB.Url editor — the same component the node profile
     * configuration panel's DB.Url row uses (SSOT structure + composition logic).
     */
    private final JdbcManualConfigurationPanel sqliteDbPanel;
    /** Existing profile names (SSOT: repository discovery; injectable for tests). */
    private final Supplier<Set<String>> takenNames;

    public DatabaseConnectionStep() {
        this(() -> new HashSet<>(NodeProfileRepository.discoverProfileNames()));
    }

    /** @param takenNames source of existing profile names (injectable for tests). */
    public DatabaseConnectionStep(Supplier<Set<String>> takenNames) {
        this.takenNames = takenNames;
        this.sqliteDbPanel = new JdbcManualConfigurationPanel(null, suggestedProfileName());
        // The engine is fixed by the selection step — the editor's engine combo
        // stays locked to SQLite (the step is the SQLite configuration card).
        ((JComponent) sqliteDbPanel.getEngineCombo()).setEnabled(false);

        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        hintLabel.setFont(hintLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        GuiFontManager.applyDefaultFont(hintLabel);
        panel.add(hintLabel);

        contentPanel.setOpaque(false);

        fieldsPanel.setLayout(new BoxLayout(fieldsPanel, BoxLayout.Y_AXIS));
        fieldsPanel.setOpaque(false);
        fieldsPanel.add(fieldRow("Host:", hostField));
        fieldsPanel.add(fieldRow("Port:", portField));
        fieldsPanel.add(fieldRow("Username:", userField));
        fieldsPanel.add(fieldRow("Password:", passwordField));
        fieldsPanel.add(fieldRow("Database:", dbField));

        javax.swing.JButton testButton = new javax.swing.JButton("Test connection");
        testButton.setFocusable(false);
        testButton.addActionListener(e -> testConnection());
        fieldsPanel.add(testButton);
        GuiFontManager.applyDefaultFont(statusLabel);
        fieldsPanel.add(statusLabel);

        contentPanel.add(fieldsPanel, "SERVER");
        sqliteDbPanel.setAlignmentX(0f);
        contentPanel.add(sqliteDbPanel, "SQLITE");
        panel.add(contentPanel);
    }

    /**
     * The profile name the SQLite database will default to: the same suggestion
     * (SSOT: {@link ProfileNameSuggester}) the node-configuration step offers in
     * its default state. Prefilling the Profile field of the SQLite trio keeps
     * the URL preview in sync with the per-profile default; when the user later
     * renames the profile and the trio stays untouched, the finish step derives
     * the per-profile SSOT URL from the final name.
     */
    private String suggestedProfileName() {
        return ProfileNameSuggester.nextAvailableName(
                ProfileNameSuggester.baseName(false, null, false, 0), takenNames.get());
    }

    private JPanel fieldRow(String label, JTextField field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        l.setPreferredSize(new java.awt.Dimension(90, 24));
        GuiFontManager.applyDefaultFont(l);
        GuiFontManager.applyDefaultFont(field);
        row.add(l);
        row.add(field);
        return row;
    }

    // ── Accessors (used by onExit/validate and tests) ────────────────────

    public String getHost() {
        return hostField.getText().trim();
    }

    public String getPortText() {
        return portField.getText().trim();
    }

    public String getUser() {
        return userField.getText().trim();
    }

    public String getPassword() {
        return new String(passwordField.getPassword());
    }

    public String getDbName() {
        return dbField.getText().trim();
    }

    private int parsePort() {
        try {
            int p = Integer.parseInt(getPortText());
            if (p < 1 || p > 65535) {
                throw new NumberFormatException();
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + getPortText());
        }
    }

    private void testConnection() {
        String host = getHost();
        int port;
        try {
            port = parsePort();
        } catch (IllegalArgumentException e) {
            statusLabel.setText("Invalid port.");
            return;
        }
        statusLabel.setText("Testing " + host + ":" + port + " ...");
        new Thread(() -> {
            boolean ok;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2500);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean result = ok;
            SwingUtilities.invokeLater(() -> statusLabel.setText(
                    result ? "Reachable — " + host + ":" + port
                            : "Not reachable: " + host + ":" + port + " (is the server running?)"));
        }, "wizard-db-test").start();
    }

    @Override
    public String getId() {
        return "database-connection";
    }

    @Override
    public String getTitle() {
        return "Database configuration";
    }

    @Override
    public FontAwesome getHeaderIcon() {
        return FontAwesome.DATABASE;
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        // File-based engine: the server connection fields do not apply — no validation.
        if (context.getEngine() == DatabaseEngine.SQLITE) {
            return null;
        }
        if (getHost().isEmpty()) {
            return "Database host is required.";
        }
        try {
            parsePort();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return null;
    }

    @Override
    public void onExit(WizardContext context) {
        if (context.getEngine() == DatabaseEngine.SQLITE) {
            // Collect the DB.Url editor: an edited trio persists the composed URL;
            // an untouched trio keeps the per-profile default (derived at finish
            // from the final profile name).
            if (sqliteDbPanel.isSqliteTrioEdited()) {
                context.setSqliteDbUrl(sqliteDbPanel.getJdbcUrl());
                context.setSqliteDbProfileName(sqliteDbPanel.getSqliteProfile());
            } else {
                context.setSqliteDbUrl(null);
                context.setSqliteDbProfileName(null);
            }
            return;
        }
        context.setSkipDbSetup(false);
        context.setDbHost(getHost());
        context.setDbPort(parsePort());
        context.setDbUser(getUser());
        context.setDbPassword(getPassword());
        context.setDbName(getDbName());
    }

    @Override
    public void onEnter(WizardContext context) {
        DatabaseEngine engine = context.getEngine() == null ? DatabaseEngine.MARIADB : context.getEngine();
        boolean sqlite = engine == DatabaseEngine.SQLITE;
        contentCards.show(contentPanel, sqlite ? "SQLITE" : "SERVER");
        if (sqlite) {
            hintLabel.setText("SQLite is file-based — no server connection is required. The database file "
                    + "(DB.Url) is kept in the profile's own data directory; leave the defaults for the "
                    + "per-profile database or adjust the location as needed.");
            return;
        }
        hintLabel.setText(engine.getDisplayName() + " — connection settings used by the node profile.");
        if (!engineResolved) {
            portField.setText(String.valueOf(engine.getDefaultPort()));
            engineResolved = true;
        }
    }

    @Override
    public boolean autoSkip(WizardContext context) {
        return false; // shown for every engine — for SQLite it is the DB-configuration step
    }
}