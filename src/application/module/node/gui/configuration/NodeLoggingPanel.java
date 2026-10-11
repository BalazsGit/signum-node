package application.module.node.gui.configuration;

import application.module.logging.gui.ModuleLoggingProfilePanel;
import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.logging.NodeLoggingProvider;
import application.utils.config.ModuleIds;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thin, node-specific adapter over the generic {@link ModuleLoggingProfilePanel}.
 *
 * <p>Owns ONLY the node concerns and delegates all generic logging-profile editing to the core
 * (including the live row search and the "Show values" unsaved/saved/applied row-state filter —
 * both core features of the {@link ModuleLoggingProfilePanel}): the File Handler rows, the
 * apply→restart hook, the optional "link to node profile" control and the node help text.
 * All storage goes through the shared
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
     * ({@code conf/node/profiles.json} via {@link LoggingAssignmentStore}) — NOT to the
     * shared module marker (the node tab never touches the module's applied state) —
     * so each node profile can use its own logging profile.
     */
    private final String nodeProfileName;

    /** Node-logging help text (HTML), rendered in the compact {@code HelpDialog} frame. */
    private static final String NODE_HELP_HTML =
            "<html><body style='width: 500px'>"
            + "<b>Signum Node logging profile</b><br>"
            + "Each row is a key/value entry written to the node's JUL <b>Properties</b>. Rows with a "
            + "log level are dropdowns; others are free text (e.g. FileHandler limit/count)."
            + "<br><br>"
            + "<b>Row colors</b> (node-logging):"
            + "<ul>"
            + "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getUnsaved()) + "'>\u25A0 Unsaved:</font> "
            + "the value differs from the selected profile's saved content. Unsaved rows are additionally "
            + "marked with a trailing <b>*</b> star on their label (the same convention as the node configuration panel).</li>"
            + "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getSaved()) + "'>\u25A0 Saved:</font> "
            + "saved in the selected profile, but not what the runtime is currently using.</li>"
            + "<li><b><font color='" + ConfigurationUtils.toHex(GuiColors.getApplied()) + "'>\u25A0 Applied:</font> "
            + "what the runtime is currently using (the applied profile's values).</li>"
            + "</ul>"
            + "The \u201CShow values\u201D boxes filter rows by these states."
            + "<br><br>"
            + "<b>Apply</b> records this profile as the active logging profile for <i>this</i> node profile "
            + "(any unsaved changes are saved to the profile first). Pressing <b>Yes</b> in the confirmation "
            + "dialog restarts the node for the change to take effect (the node is not started if it was not "
            + "running; a paused node is restored to the paused state after the restart). The assignment is "
            + "specific to this node profile — other node profiles keep their own assignment, and a newly "
            + "created node profile inherits the module's currently applied logging profile (or the built-in "
            + "'logging-default' profile while 'Default' is the applied one) as its default assignment."
            + "</body></html>";

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

        // The applied profile of the NODE tab is the per-node assignment
        // (SSOT), not the shared module marker: override the core's applied
        // resolution so the row-state "applied" baseline and the combo's
        // applied marker follow this node's own profile.
        if (nodeProfileName != null && !nodeProfileName.isBlank()) {
            setAppliedProfileNameSupplier(this::resolveNodeAppliedProfileName);
        }

        // Node-specific File Handler rows (not present in the provider defaults).
        addExtraField("java.util.logging.FileHandler.level",   "File Level",            "INFO");
        addExtraField("java.util.logging.FileHandler.pattern", "Log File Pattern",      "logs/signum%u.log");
        addExtraField("java.util.logging.FileHandler.limit",   "File Size Limit (bytes)", "0");
        addExtraField("java.util.logging.FileHandler.count",   "File Count",            "1");

        // Apply → record the per-node assignment (SSOT) then restart the node.
        // (The core already persisted the shared module marker and showed the confirmation.)
        if (applyAction != null) {
            // The confirmation dialog must name the NODE PROFILE (not the module) and state
            // explicitly what pressing Yes will do — the core's module-centric default
            // message is misleading on this tab.
            setApplyConfirmationProvider((profileName, isDefault) -> nodeApplyMessage(profileName, isDefault));
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

        // The FileHandler rows were registered AFTER the super-constructor's
        // initial profile load — recompute their row states (the unsaved/
        // saved/applied coloring and the "Show values" filters) now.
        reevaluateRowStates();

        // The applied profile is the menu's default selection: the per-node
        // assignment (SSOT); without one, the virtual "Default" entry stands
        // (the node runs with the built-in logging-default, and the shared
        // module marker is NOT this tab's applied state).
        selectAppliedProfileByDefault();

        setHelpSupplier(() -> NODE_HELP_HTML);

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
     * The node-tab Apply confirmation message. States EXACTLY what pressing Yes will do:
     * the profile is recorded as the <b>per-node</b> assignment of the bound node profile
     * (affecting ONLY that node profile — other node profiles keep their own assignment,
     * and newly created node profiles inherit the module's currently applied logging
     * profile — or the built-in {@code logging-default} while "Default" is the applied
     * one — as their default), then the node restarts (state-preserved).
     *
     * @param profileName the profile name being applied (the virtual Default entry included)
     * @param isDefault   whether the virtual "Default" entry is applied (clears the node's custom assignment)
     * @return the confirmation dialog message
     */
    String nodeApplyMessage(String profileName, boolean isDefault) {
        boolean bound = nodeProfileName != null && !nodeProfileName.isBlank();
        String target = bound ? "node profile '" + nodeProfileName + "'" : "the node";
        String message = "Apply logging profile '" + profileName + "' to " + target + "?\n\n"
                + (isDefault
                        ? "This restores the application's built-in default logging configuration: it CLEARS "
                                + "this node profile's custom logging assignment, so the node runs with the "
                                + "built-in '" + LoggingProfileRepository.RESERVED_PROFILE_NAME + "' profile."
                        : "This records '" + profileName + "' as this node profile's assigned (applied) logging "
                                + "profile — the profile the node uses for its logging configuration.");
        message += "\n\nThis assignment is specific to " + target + ". Other node profiles keep their own "
                + "assignment, and newly created node profiles inherit the module's currently applied "
                + "logging profile (or the built-in '" + LoggingProfileRepository.RESERVED_PROFILE_NAME
                + "' while 'Default' is the applied one) as their default.";
        message += (isBoundNodeRunning()
                ? "\n\nPressing Yes will RESTART the node (stop + start) for the change to take effect."
                        + (isBoundNodePaused() ? " The paused state is restored after the restart." : "")
                : "\n\nThe node is not running, so it will NOT be started — the change takes "
                        + "effect the next time the node starts.");
        return message;
    }

    /**
     * Records the just-applied profile as the <b>per-node</b> assignment for the bound node
     * profile in the SSOT ({@code conf/node/profiles.json}). The virtual "Default" entry
     * CLEARS this module's assignment for the node (so the runtime falls back to the
     * built-in defaults) instead of persisting the literal "Default" string. No-op when
     * this panel is not bound to a node profile. Package-private so tests can drive it
     * without the modal confirmation dialog. Defensive: never throws (a persistence
     * error must not break Apply).
     *
     * @param profileName the logging profile name just applied
     */
    void persistPerNodeAssignment(String profileName) {
        if (nodeProfileName == null || nodeProfileName.isBlank() || profileName == null || profileName.isBlank()) {
            return;
        }
        try {
            if (ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY.equals(profileName)) {
                new LoggingAssignmentStore().setAssignmentForModule(nodeProfileName, ModuleIds.NODE, null);
            } else {
                new LoggingAssignmentStore().setAssignmentForModule(nodeProfileName, ModuleIds.NODE, profileName);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to record per-node logging assignment for node profile '{}': {}",
                    nodeProfileName, e.getMessage());
        }
    }

    /**
     * The bound node profile's node, or {@code null} when not bound / unresolvable.
     * Read live when Apply is confirmed so the dialog can state exactly what pressing Yes
     * will do. Defensive: any failure (e.g. the node module is not initialized in tests)
     * yields {@code null}.
     */
    private Signum boundNode() {
        if (nodeProfileName == null || nodeProfileName.isBlank()) {
            return null;
        }
        try {
            return NodeModule.getInstance().get(nodeProfileName);
        } catch (Exception e) {
            LOGGER.debug("Could not resolve node profile '{}': {}", nodeProfileName, e.getMessage());
            return null;
        }
    }

    /** Whether the bound node is currently RUNNING. */
    private boolean isBoundNodeRunning() {
        Signum node = boundNode();
        return node != null && node.getState() == Signum.State.RUNNING;
    }

    /** Whether the bound node is currently paused by the user. */
    private boolean isBoundNodePaused() {
        Signum node = boundNode();
        return node != null && node.getOperatingState() == Signum.OperatingState.PAUSED_USER;
    }

    /**
     * The logging profile this node profile currently applies: the explicit
     * per-node assignment (the SSOT in {@code conf/node/profiles.json} via
     * {@link LoggingAssignmentStore}); when there is none (missing, blank or
     * the reserved name), the built-in {@code logging-default} profile — an
     * unassigned node profile runs with the built-in defaults, and the
     * shared module applied marker must NOT leak into the node tab (it is
     * the generic module tab's applied state only). Defensive: a read
     * failure resolves to the built-in default as well — the runtime-true
     * answer, never a stale marker.
     */
    private String resolveNodeAppliedProfileName() {
        try {
            String name = new LoggingAssignmentStore().getAssignment(nodeProfileName).get(ModuleIds.NODE);
            if (name != null && !name.isBlank() && !LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(name)) {
                return name;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read the per-node logging assignment for node profile '{}': {}",
                    nodeProfileName, e.getMessage());
        }
        return LoggingProfileRepository.RESERVED_PROFILE_NAME;
    }

    /**
     * Pre-selects the profile this node profile <b>applied</b> in the profile menu:
     * the per-node assignment (the SSOT in {@code conf/node/profiles.json} via
     * {@link LoggingAssignmentStore}) — when this panel is bound to a node profile.
     * <p>
     * Without an explicit assignment the selection is reset to the virtual
     * "Default" entry: the node runs with the built-in {@code logging-default}
     * profile, and the shared module applied marker is NOT this tab's applied
     * state — the core constructor may have pre-selected the module's applied
     * profile, so the reset is explicit (that would show a profile the node
     * does not actually use). A missing or unknown name (e.g. the applied
     * profile was deleted since) keeps the virtual "Default" selection.
     * Defensive: a read failure must never break construction.
     * </p>
     */
    private void selectAppliedProfileByDefault() {
        if (nodeProfileName == null || nodeProfileName.isBlank()) {
            return; // unbound panel: the core constructor's selection stands
        }
        String name = null;
        try {
            name = new LoggingAssignmentStore().getAssignment(nodeProfileName).get(ModuleIds.NODE);
        } catch (Exception e) {
            LOGGER.warn("Failed to read the per-node logging assignment for node profile '{}': {}",
                    nodeProfileName, e.getMessage());
        }
        if (name == null || name.isBlank() || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(name)) {
            // Nothing custom applied: the node runs with the built-in default.
            // Reset the selection to the virtual "Default" entry (the core
            // constructor may have pre-selected the shared module marker).
            selectProfile(DEFAULT_PROFILE_ENTRY);
            return;
        }
        if (selectProfile(name)) {
            LOGGER.info("Pre-selected applied logging profile '{}' in the profile menu (node profile '{}')",
                    name, nodeProfileName);
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
