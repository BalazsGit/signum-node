package application.module.node.gui.configuration;

import application.module.logging.gui.ModuleLoggingProfilePanel;
import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.module.node.logging.NodeLoggingProvider;
import application.utils.config.ModuleIds;
import application.utils.gui.CheckboxGroupPanel;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
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

    /**
     * Per-row value state (node-logging exclusive): the row's editor value
     * compared against what is saved and what is applied.
     */
    private enum RowState {
        /** Editor value differs from the selected profile's saved value (dirty). */
        UNSAVED(GuiColors.getUnsaved()),
        /** Editor value is saved to the selected profile, but differs from the applied profile. */
        SAVED(GuiColors.getSaved()),
        /** Editor value matches what the (restarted) node is actually running. */
        APPLIED(GuiColors.getApplied());

        private final Color color;

        RowState(Color color) {
            this.color = color;
        }

        Color color() {
            return color;
        }
    }

    /** Last computed per-row state (key → state); drives the coloring and the "Show values" filters. */
    private final Map<String, RowState> rowStates = new LinkedHashMap<>();
    private CheckboxGroupPanel statusPanel;
    private JCheckBox showUnsavedBox;
    private JCheckBox showSavedBox;
    private JCheckBox showAppliedBox;

    /** Node-logging help text (HTML). */
    private static final String NODE_HELP_HTML =
            "<html><b>Signum Node logging profile</b><br>"
            + "Each row is a key/value entry written to the node's JUL {@code Properties}. Rows with a "
            + "log level are dropdowns; others are free text (e.g. FileHandler limit/count).<br><br>"
            + "<b>Row colors</b> (node-logging): <b>unsaved</b> = the value differs from the selected "
            + "profile's saved content; <b>saved</b> = saved in the selected profile but differs from the "
            + "applied profile; <b>applied</b> = the value matches what the applied profile (the running "
            + "configuration) uses. The \"Show values\" boxes filter rows by these states.<br><br>"
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

        // Node-logging exclusive: the "Show values" status filters (rendered
        // next to the search box) plus the per-row unsaved/saved/applied
        // coloring — see reevaluateRowStates()/styleRow()/isRowVisible().
        buildStatusFilter();

        setHelpSupplier(() -> NODE_HELP_HTML);
        enableSearch();

        LOGGER.debug("NodeLoggingPanel constructed (module: node)");
    }

    // ── Node-logging exclusive: row states (Unsaved / Saved / Applied) ──────

    /**
     * Builds the "Show values" status filter next to the search box. Each
     * checkbox toggles the visibility of rows in the corresponding state;
     * toggling re-renders the rows.
     */
    private void buildStatusFilter() {
        statusPanel = new CheckboxGroupPanel("Show values");
        statusPanel.setChangeListener(button -> reevaluateRowStates());

        showUnsavedBox = statusPanel.addCheckbox("Unsaved values", GuiColors.getUnsaved(), true);
        showUnsavedBox.setToolTipText(
                "Show / hide the values that differ from the selected profile's saved content (dirty edits).");

        showSavedBox = statusPanel.addCheckbox("Saved values", GuiColors.getSaved(), true);
        showSavedBox.setToolTipText(
                "Show / hide the values that are saved in the selected profile but differ from the applied profile.");

        showAppliedBox = statusPanel.addCheckbox("Applied values", GuiColors.getApplied(), true);
        showAppliedBox.setToolTipText(
                "Show / hide the values that match exactly what the applied profile (the running configuration) uses.");

        getFilterBox().add(statusPanel);

        // The state computation that ran during the super-constructor was
        // skipped (this subclass's fields are not initialized yet) — do the
        // first real recompute now that everything is in place.
        reevaluateRowStates();
    }

    /**
     * Recomputes the state of every row against the two reference profiles:
     * <ul>
     * <li><b>Unsaved</b> — the editor value differs from the selected
     *     profile's saved content (or differs from the built-in default while
     *     the virtual Default entry is selected — Default cannot be saved).</li>
     * <li><b>Applied</b> — the editor value matches the applied profile's
     *     value (or the built-in default when no profile — or only the
     *     reserved sample config — is the applied marker).</li>
     * <li><b>Saved</b> — otherwise: saved in the selected profile, but not
     *     what is applied.</li>
     * </ul>
     */
    @Override
    protected void recomputeRowStates() {
        if (rowStates == null) {
            return; // super-constructor in progress
        }
        rowStates.clear();
        String selected = selectedProfileName();
        boolean selectedIsDefault = ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY.equals(selected);
        Properties selectedProps = selectedIsDefault ? new Properties() : loadProfilePropsSafely(selected);

        String applied = appliedProfileName();
        boolean appliedIsDefault = applied == null || applied.isBlank()
                || LoggingProfileRepository.RESERVED_PROFILE_NAME.equals(applied);
        Properties appliedProps = appliedIsDefault ? null : loadProfilePropsSafely(applied);

        for (String key : rowKeys()) {
            String value = editorValueOf(key);
            if (value == null) {
                value = "";
            }
            String def = defaultRowValue(key);

            // 1) Unsaved: dirty against the selected profile's saved content.
            boolean dirty;
            if (selectedIsDefault) {
                dirty = !value.equals(def);
            } else {
                String saved = selectedProps.getProperty(key);
                dirty = saved == null ? !value.equals(def) : !value.equals(saved);
            }
            if (dirty) {
                rowStates.put(key, RowState.UNSAVED);
                continue;
            }

            // 2) Applied: matches the applied profile's value (built-in
            //    defaults when nothing — or only the sample config — is applied).
            String appliedValue = appliedProps == null ? def : appliedProps.getProperty(key, def);
            if (value.equals(appliedValue)) {
                rowStates.put(key, RowState.APPLIED);
                continue;
            }

            // 3) Saved: persisted in the selected profile, but not applied.
            rowStates.put(key, RowState.SAVED);
        }
    }

    /** @return true when the row's state is currently shown by the "Show values" filters. */
    @Override
    protected boolean isRowVisible(String key) {
        // null while the super-constructor is still running (the core renders
        // rows before this subclass's fields are initialized).
        if (rowStates == null) {
            return true;
        }
        RowState state = rowStates.get(key);
        if (state == null) {
            return true;
        }
        return switch (state) {
            case UNSAVED -> showUnsavedBox != null && showUnsavedBox.isSelected();
            case SAVED -> showSavedBox != null && showSavedBox.isSelected();
            case APPLIED -> showAppliedBox != null && showAppliedBox.isSelected();
        };
    }

    /** Colors the row's label and editor with the state color (node-logging exclusive). */
    @Override
    protected void styleRow(String key, JLabel label, JComponent editor) {
        // null while the super-constructor is still running.
        if (rowStates == null) {
            return;
        }
        RowState state = rowStates.get(key);
        if (state == null) {
            return;
        }
        label.setForeground(state.color());
        editor.setForeground(state.color());
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
