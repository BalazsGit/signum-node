package application.module.node.gui.wizard.steps;

import application.module.database.gui.DatabaseConfigurationPanel.DatabaseEngine;
import application.module.node.gui.wizard.ConflictHighlighter;
import application.module.node.gui.wizard.WizardContext;
import application.module.node.gui.wizard.WizardStep;
import application.module.node.profile.NodeProfile;
import application.module.node.profile.ProfileConflictDetector;
import application.module.node.profile.ProfileCreateDefaults;
import application.utils.gui.GuiFontManager;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import java.awt.Color;
import java.util.List;
import java.util.Properties;

/**
 * Wizard step 4 — read-only summary + conflict pre-check + "start immediately".
 * <p>
 * Re-renders on every {@code onEnter} (the controller calls it when the step becomes
 * visible), so the summary always reflects the final context. Conflicts are computed by
 * the same SSOT ({@link ProfileConflictDetector}) the runtime arbitration uses.
 * </p>
 */
public class SummaryStep implements WizardStep {

    private final JPanel panel = new JPanel();
    private final JTextArea text = new JTextArea(12, 46);
    private final JLabel conflictLabel = new JLabel();
    private final JCheckBox startNow = new JCheckBox("Start the node immediately after creation", true);

    public SummaryStep() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JLabel title = new JLabel("4. Summary — review before creating the profile");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        GuiFontManager.applyDefaultFont(title);
        panel.add(title);

        text.setEditable(false);
        text.setLineWrap(true);
        GuiFontManager.applyDefaultFont(text);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new java.awt.Dimension(460, 180));
        panel.add(scroll);

        GuiFontManager.applyDefaultFont(conflictLabel);
        panel.add(conflictLabel);
        panel.add(startNow);
    }

    /** Re-renders the summary from the context (called by the controller on entry). */
    @Override
    public void onEnter(WizardContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Profile name : ").append(nvl(context.getName())).append('\n');
        sb.append("Network      : ").append(context.isTestnet() ? "testnet" : "mainnet").append('\n');
        sb.append("Database     : ").append(describeDb(context)).append('\n');
        sb.append("Ports        : ").append(describePorts(context)).append('\n');
        sb.append("Logging      : ").append(nvl(context.getLoggingPreset())).append('\n');
        text.setText(sb.toString());

        List<ProfileConflictDetector.Conflict> conflicts =
                ConflictHighlighter.findConflicts(candidate(context));
        if (conflicts.isEmpty()) {
            conflictLabel.setText("No resource conflicts with existing profiles.");
            conflictLabel.setForeground(Color.GREEN.darker());
        } else {
            StringBuilder warn = new StringBuilder("CONFLICTS: ");
            for (ProfileConflictDetector.Conflict c : conflicts) {
                warn.append(ConflictHighlighter.describeConflict(c)).append("; ");
            }
            conflictLabel.setText(warn.toString());
            conflictLabel.setForeground(new Color(198, 40, 40));
        }
    }

    private String describeDb(WizardContext context) {
        if (context.getEngine() == DatabaseEngine.SQLITE) {
            String nameOrPlaceholder = context.getName() == null ? "<name>" : context.getName();
            return ProfileCreateDefaults.sqliteDbUrl(nameOrPlaceholder);
        }
        if (context.isSkipDbSetup()) {
            return context.getEngine().getDisplayName() + " (connection not configured — shared default applies)";
        }
        String base = context.getEngine().getDisplayName() + " @ " + nvl(context.getDbHost()) + ":" + context.getDbPort()
                + "/" + nvl(context.getDbName());
        if (context.isDbInstallSkipped()) {
            return base + "  [warning: installation was skipped in the wizard — make sure the server is installed and running]";
        }
        return base;
    }

    private String describePorts(WizardContext context) {
        if (context.isUseDefaultPorts()) {
            return "defaults (per-profile band assigned at creation)";
        }
        return "API=" + nvl(String.valueOf(context.getApiPort()))
                + " P2P=" + nvl(String.valueOf(context.getP2pPort()))
                + " WS=" + nvl(String.valueOf(context.getWsPort()));
    }

    private String nvl(String s) {
        return s == null || s.isEmpty() ? "(unset)" : s;
    }

    /** Builds the candidate profile used for the conflict pre-check (props via the SSOT mapper). */
    private NodeProfile candidate(WizardContext context) {
        String name = context.getName() == null ? "wizard-candidate" : context.getName();
        NodeProfile candidate = new NodeProfile(name);
        Properties props = new Properties();
        ProfileCreateDefaults.applyNetwork(props, context.isTestnet());
        if (context.getEngine() == DatabaseEngine.SQLITE) {
            ProfileCreateDefaults.applySqliteDatabase(props, name);
        } else if (!context.isSkipDbSetup()) {
            ProfileCreateDefaults.applyServerDatabase(props, context.getEngine() == DatabaseEngine.POSTGRESQL,
                    context.getDbHost(), context.getDbPort(),
                    context.getDbUser(), context.getDbPassword(), context.getDbName());
        }
        if (context.isUseDefaultPorts()) {
            ProfileCreateDefaults.applyPorts(props, name);
        } else {
            if (context.getApiPort() != null) props.setProperty("API.Port", String.valueOf(context.getApiPort()));
            if (context.getP2pPort() != null) props.setProperty("P2P.Port", String.valueOf(context.getP2pPort()));
            if (context.getWsPort() != null) props.setProperty("API.WebSocketPort", String.valueOf(context.getWsPort()));
        }
        for (String key : props.stringPropertyNames()) {
            candidate.setProperty(key, props.getProperty(key));
        }
        return candidate;
    }

    public boolean isStartImmediately() {
        return startNow.isSelected();
    }

    @Override
    public String getId() {
        return "summary";
    }

    @Override
    public String getTitle() {
        return "Summary";
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    public String validate(WizardContext context) {
        return context.getName() == null || context.getName().isBlank() ? "Profile name is missing." : null;
    }

    @Override
    public void onExit(WizardContext context) {
        context.setStartImmediately(startNow.isSelected());
    }
}