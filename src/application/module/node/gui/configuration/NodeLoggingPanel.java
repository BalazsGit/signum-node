package application.module.node.gui.configuration;

import application.module.logging.gui.ModuleLoggingProfilePanel;
import application.module.logging.LoggingAssignmentStore;
import application.module.node.logging.NodeLoggingProvider;
import application.utils.config.ModuleIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thin, node-specific adapter over the generic {@link ModuleLoggingProfilePanel}.
 *
 * <p>Owns ONLY the node concerns and delegates all generic logging-profile editing to the core:
 * the File Handler rows, the apply→restart hook, the optional "link to node profile" control,
 * node help text, and the row search filter. All storage goes through the shared
 * {@code LoggingProfileRepository} (module {@code node}) at {@code ./conf/node/logging/}, exactly
 * where the legacy {@code LoggerConfigurationPanel} stored profiles.</p>
 *
 * <p>Construct on the EDT (the host panels already do).</p>
 *
 * @see ModuleLoggingProfilePanel
 * @see LoggerConfigurationPanel
 */
public final class NodeLoggingPanel extends ModuleLoggingProfilePanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeLoggingPanel.class);

    /**
     * The node profile this tab is bound to (e.g. {@code "mainnet"}), or {@code null} when the
     * panel is used standalone (tests) and should not record a per-node assignment. When set,
     * <b>Apply</b> persists the selection to the per-node assignment SSOT
     * ({@code conf/node/profiles.json} via {@link LoggingAssignmentStore}) in addition to the
     * shared module marker, so each node profile can use its own logging profile.
     */
    private final String nodeProfileName;

    /** Node-logging help text (HTML). */
    private static final String NODE_HELP_HTML =
            "<html><b>Signum Node logging profile</b><br>"
            + "Each row is a key/value entry written to the node's JUL {@code Properties}. Rows with a "
            + "log level are dropdowns; others are free text (e.g. FileHandler limit/count).<br><br>"
            + "<b>Apply</b> records this profile as the node's active logging profile for <i>this</i> node "
            + "profile; a node restart is required for the change to take effect."
            + "</html>";

    /**
     * Creates the node logging tab wired to the given apply action (used by the
     * {@code NodeProfilePanel} "Logging" tab, which has no link control).
     * <p>
     * This convenience overload does not bind a node profile, so Apply only marks the shared
     * module profile as applied (no per-node assignment is recorded).
     * </p>
     *
     * @param applyAction invoked (EDT) after a profile is applied, to restart the node; may be null
     */
    public NodeLoggingPanel(Runnable applyAction) {
        this(null, applyAction, null, null, null);
    }

    /**
     * Creates the node logging tab bound to a specific node profile, wired to the given apply
     * action (the {@code NodeProfilePanel} "Logging" tab has no link control).
     *
     * @param nodeProfileName the node profile this tab belongs to (for the per-node assignment)
     * @param applyAction     invoked (EDT) after a profile is applied, to restart the node; may be null
     */
    public NodeLoggingPanel(String nodeProfileName, Runnable applyAction) {
        this(nodeProfileName, applyAction, null, null, null);
    }

    /**
     * Creates the node logging tab with an optional "link to node profile" control,
     * without binding a node profile for per-node assignment.
     *
     * @param applyAction               invoked after Apply to restart the node; may be null
     * @param onLinkAction              invoked when the user picks a linked node profile; null to disable linking
     * @param activeNodeProfileSupplier current node profile name (for the link control); null to disable linking
     * @param linkedProfileSupplier     last selected linked profile (for the link control); null to disable linking
     */
    public NodeLoggingPanel(Runnable applyAction,
                            Consumer<String> onLinkAction,
                            Supplier<String> activeNodeProfileSupplier,
                            Supplier<String> linkedProfileSupplier) {
        this(null, applyAction, onLinkAction, activeNodeProfileSupplier, linkedProfileSupplier);
    }

    /**
     * Creates the node logging tab bound to a specific node profile.
     *
     * @param nodeProfileName           the node profile this tab belongs to (for the per-node
     *                                  assignment SSOT); null to skip recording the assignment
     * @param applyAction               invoked after Apply to restart the node; may be null
     * @param onLinkAction              invoked when the user picks a linked node profile; null to disable linking
     * @param activeNodeProfileSupplier current node profile name (for the link control); null to disable linking
     * @param linkedProfileSupplier     last selected linked profile (for the link control); null to disable linking
     */
    public NodeLoggingPanel(String nodeProfileName,
                            Runnable applyAction,
                            Consumer<String> onLinkAction,
                            Supplier<String> activeNodeProfileSupplier,
                            Supplier<String> linkedProfileSupplier) {
        super(new NodeLoggingProvider());
        this.nodeProfileName = nodeProfileName;

        // Node-specific File Handler rows (not present in the provider defaults).
        addExtraField("java.util.logging.FileHandler.level",   "File Level",            "INFO");
        addExtraField("java.util.logging.FileHandler.pattern", "Log File Pattern",      "logs/signum%u.log");
        addExtraField("java.util.logging.FileHandler.limit",   "File Size Limit (bytes)", "0");
        addExtraField("java.util.logging.FileHandler.count",   "File Count",            "1");

        // Apply → record the per-node assignment (SSOT) then restart the node.
        // (The core already persisted the shared module marker and showed the confirmation.)
        if (applyAction != null) {
            setApplyHook(name -> {
                persistPerNodeAssignment(name);
                LOGGER.info("Node logging profile '{}' applied for node profile '{}' — restarting node",
                        name, nodeProfileName);
                applyAction.run();
            });
        }

        // Optional "link to node profile" control.
        if (onLinkAction != null) {
            setLinkControl(buildLinkControl(onLinkAction, linkedProfileSupplier));
        }

        setHelpSupplier(() -> NODE_HELP_HTML);
        enableSearch();

        LOGGER.debug("NodeLoggingPanel constructed (module: node)");
    }

    /** The underlying core panel (this panel IS the core panel). */
    public ModuleLoggingProfilePanel getCorePanel() {
        return this;
    }

    /**
     * @return the node profile this tab is bound to (for the per-node assignment), or null
     */
    public String getNodeProfileName() {
        return nodeProfileName;
    }

    /**
     * Records the just-applied profile as the <b>per-node</b> assignment for the bound node
     * profile in the SSOT ({@code conf/node/profiles.json}). No-op when this panel is not bound
     * to a node profile. Defensive: never throws (a persistence error must not break Apply).
     *
     * @param profileName the logging profile name just applied
     */
    private void persistPerNodeAssignment(String profileName) {
        if (nodeProfileName == null || nodeProfileName.isBlank() || profileName == null || profileName.isBlank()) {
            return;
        }
        try {
            new LoggingAssignmentStore().setAssignmentForModule(nodeProfileName, ModuleIds.NODE, profileName);
        } catch (Exception e) {
            LOGGER.warn("Failed to record per-node logging assignment for node profile '{}': {}",
                    nodeProfileName, e.getMessage());
        }
    }

    private static JComponent buildLinkControl(Consumer<String> onLinkAction, Supplier<String> linkedProfileSupplier) {
        JCheckBox check = new JCheckBox("Link to Node:");
        JComboBox<String> combo = new JComboBox<>();
        combo.addItem("");
        if (linkedProfileSupplier != null) {
            String last = linkedProfileSupplier.get();
            if (last != null && !last.isEmpty()) {
                combo.addItem(last);
                combo.setSelectedItem(last);
            }
        }
        check.addActionListener(e -> combo.setEnabled(check.isSelected()));
        combo.addActionListener(e -> {
            String sel = (String) combo.getSelectedItem();
            if (sel != null && !sel.isEmpty() && onLinkAction != null) {
                onLinkAction.accept(sel);
            }
        });
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        strip.setOpaque(false);
        strip.add(check);
        strip.add(combo);
        return strip;
    }
}
