package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.utils.gui.GuiFontManager;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Wizard step 1.2 — database connection settings for the node profile.
 * <p>
 * Collected here (separated from the installation step, plan §1.2): host, port,
 * username, password and database name, with an optional TCP reachability check.
 * These values are persisted into the node profile via the
 * {@link ProfileCreateDefaults} SSOT at finish time (see
 * {@link application.module.node.gui.wizard.WizardFinish}).
 * </p>
 * <p>
 * Auto-skipped for SQLite (file-based — the per-profile file URL is derived at finish).
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
    private boolean engineResolved = false;

    public DatabaseConnectionStep() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        hintLabel.setFont(hintLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        GuiFontManager.applyDefaultFont(hintLabel);
        panel.add(hintLabel);

        JPanel fieldsPanel = new JPanel();
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
        panel.add(fieldsPanel);
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
        return "Database connection";
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
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
        hintLabel.setText(engine.getDisplayName() + " — connection settings used by the node profile.");
        if (!engineResolved) {
            portField.setText(String.valueOf(engine.getDefaultPort()));
            engineResolved = true;
        }
    }

    @Override
    public boolean autoSkip(WizardContext context) {
        return context.getEngine() == DatabaseEngine.SQLITE;
    }
}