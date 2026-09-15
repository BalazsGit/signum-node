package application.module.node.gui;
import application.utils.config.ModuleIds;
import application.utils.config.PropertiesProfileLoader;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.NodeModule;
import application.module.node.Signum;
import application.module.node.gui.configuration.NodeLoggingPanel;
import application.module.node.gui.configuration.NodeConfigurationPanel;
import application.module.node.profile.NodeProfile;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiUtils;
import application.utils.gui.ResponsiveToolbarScrollPane;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.DefaultSingleSelectionModel;
import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Profile panel for a single NodeProfile instance.
 * Layout structure:
 * - NORTH: NodeInfoBar (profile name, network, state, ports)
 * - BELOW NORTH: NodeToolbar (action buttons: Start/Stop/Restart/Sync/etc.)
 * - CENTER: JTabbedPane with Console, Configuration, Logging tabs
 * <p>
 * Uses constructor-injected {@link Signum} facade for per-instance access to
 * BlockchainProcessor, PropertyService, Blockchain, etc. The Signum facade is
 * the single per-instance entry point (Facade Pattern).
 */
@SuppressWarnings("serial")
public class NodeProfilePanel extends JPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeProfilePanel.class);

    private final JFrame parentFrame;
    private final NodeProfile profile;
    /**
     * Per-instance Signum facade (may be null if the node has not been started yet).
     * Not final: the console panel may start the node after panel construction
     * (late binding) and then hand the instance back via {@link #adoptSignum(Signum)}.
     */
    private volatile Signum signum;

    /**
     * The single {@link Signum.StateListener} (PUSH trigger) registered on the
     * adopted Signum. It only calls {@link #onNodeStateChanged(Signum.State, Signum.State)};
     * all data is always re-read from the Signum (single source of truth).
     */
    private volatile Signum.StateListener stateListener;

    /**
     * v5 (multi-node): NodeModule start-pending broadcast subscriber. Notified when
     * this profile's start is queued (or its task completes) — the panel then renders
     * the toolbar (spinner + disabled buttons) and info bar exactly like the STARTING
     * transition, immediately, without waiting for the state push. Stored in a field
     * (not a method reference at the call site) so add/remove use the SAME instance.
     */
    private final Runnable pendingListener = this::refreshPendingState;

    /**
     * SSOT tab-icon refresher: (re)renders the owning tab's status icon from the
     * combined node state (lifecycle + operating + maintenance). Registered by
     * {@link NodePanel} right after construction; invoked on operating-state
     * (pause/resume) and maintenance (trim/prune) changes.
     */
    private volatile Runnable tabIconRefresher;
    private final JTabbedPane innerTabbedPane;
    private final NodeConsolePanel consolePanel;
    private final NodeConfigurationPanel configurationPanel;
    private final NodeLoggingPanel loggingPanel;
    private final NodeInfoBar infoBar;
    private final NodeToolbar toolbar;
    private final String confFolder;

    // v4 (P1.3): the local syncPaused shadow flag was removed — pause state is
    // owned by the Signum instance (getOperatingState()/getPauseReason(), PUSH
    // via onOperatingStateChanged).

    /**
     * Creates a profile panel with an injected Signum facade.
     * The facade provides per-instance access to BlockchainProcessor, PropertyService, etc.,
     * replacing the former static Signum.getXxx() access.
     *
     * @param parentFrame Parent JFrame for dialogs
     * @param profile     The node profile
     * @param signum      Per-instance Signum facade (may be null if not yet started)
     * @since 4.0 Phase G - Greenfield wiring
     */
    public NodeProfilePanel(JFrame parentFrame, NodeProfile profile, Signum signum) {
        // Bind the profile log context EARLY so this panel's construction logs (emitted on
        // the Swing EDT, before the node has even started) are routed to the node's
        // ProfileLogger (Node Console tab) as well as the System Console. The profile is
        // known here, so we also ensure the ProfileLogger exists now; the Signum adopts
        // this same instance when the node starts, so nothing logged before then is lost.
        application.utils.logging.NodeLoggerRegistry.getOrCreate(ModuleIds.NODE, profile.getName());
        application.utils.logging.LogScope previousContext = application.utils.logging.NodeLogContext.current();
        application.utils.logging.NodeLogContext.set(ModuleIds.NODE, profile.getName());
        LOGGER.debug("NodeProfilePanel constructor START for profile: {}", profile.getName());
        
        try {
            this.parentFrame = parentFrame;
            this.profile = profile;
            this.signum = signum;
            this.confFolder = determineConfFolder();

            // In the multi-node architecture every GUI element belongs to its specific
            // Signum. Register this panel on the node so the Signum "knows" its GUI
            // (non-null in GUI mode, null in headless mode). This removes any need for
            // a global "active node" lookup.
            if (signum != null) {
                signum.setGuiPanel(this);
            }

            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

            LOGGER.debug("Creating NodeInfoBar for profile: {}", profile.getName());
            infoBar = new NodeInfoBar(profile);
            
            LOGGER.debug("Creating NodeToolbar for profile: {}", profile.getName());
            toolbar = new NodeToolbar(profile);
            // v4: when the toolbar starts the node, hand the instance back so this
            // panel can adopt it (single state listener) and attach the console.
            toolbar.setOnNodeStarted(this::onNodeStarted);

            // Wrap infoBar in a responsive scroll pane so info chips are accessible when window is narrow
            ResponsiveToolbarScrollPane infoBarScrollPane = new ResponsiveToolbarScrollPane(infoBar,
                    new java.awt.Insets(2, 4, 0, 4));
            infoBarScrollPane.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, GuiColors.getSeparator()),
                    BorderFactory.createEmptyBorder(0, 0, 2, 0)
            ));

            JPanel toolbarWrapper = new JPanel(new BorderLayout());
            toolbarWrapper.setOpaque(false);
            toolbarWrapper.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            toolbarWrapper.add(toolbar, BorderLayout.CENTER);

            JPanel northPanel = new JPanel(new BorderLayout());
            northPanel.setOpaque(false);
            northPanel.add(infoBarScrollPane, BorderLayout.NORTH);
            northPanel.add(toolbarWrapper, BorderLayout.SOUTH);
            add(northPanel, BorderLayout.NORTH);

            innerTabbedPane = new JTabbedPane(SwingConstants.TOP);
            GuiFontManager.applyDefaultFont(innerTabbedPane);
            GuiUtils.applyDefaultTabLayoutPolicy(innerTabbedPane);

            LOGGER.debug("Creating NodeConsolePanel for profile: {}", profile.getName());
            consolePanel = new NodeConsolePanel(parentFrame, profile);
            if (signum != null) {
                consolePanel.setSignum(signum);
            }
            consolePanel.setSwitchToConsoleAction(() -> switchToConsoleTab());
            // Late binding: if the console panel starts the node itself (this panel
            // was constructed before the Signum existed), adopt the started instance.
            consolePanel.setOnSignumStarted(this::adoptSignum);
            innerTabbedPane.addTab("Console", consolePanel);
            LOGGER.debug("Console tab added successfully");

            LOGGER.debug("Creating NodeConfigurationPanel for profile: {}", profile.getName());
            // Pass this node's own profile name explicitly so the panel does not rely
            // on the deprecated global "active node" lookup.
            configurationPanel = new NodeConfigurationPanel(
                    this::restartNode,
                    this.confFolder,
                    () -> {
                    },
                    null,
                    profile.getName()
            );
            if (signum != null) {
                configurationPanel.setSignum(signum);
            }
            innerTabbedPane.addTab("Configuration", configurationPanel);
            LOGGER.debug("Configuration tab added successfully");

            LOGGER.debug("Creating LoggerConfigurationPanel for profile: {}", profile.getName());
            loggingPanel = new NodeLoggingPanel(profile.getName(), this::restartNode);
            innerTabbedPane.addTab("Logging", loggingPanel);
            LOGGER.debug("Logging tab added successfully");

            LOGGER.debug("Adding innerTabbedPane to CENTER (total tabs: {})", innerTabbedPane.getTabCount());
            add(innerTabbedPane, BorderLayout.CENTER);
            
            LOGGER.debug("Wiring toolbar callbacks for profile: {}", profile.getName());
            wireToolbarCallbacks();
            
            LOGGER.debug("Wiring console visibility tracking for profile: {}", profile.getName());
            wireConsoleVisibilityTracking();

            // Adopt the Signum if it already exists at construction time:
            // registers the single state listener (PUSH) + performs the initial
            // refresh. No-op if the node does not exist yet — in that case it is
            // adopted later via the console panel's onSignumStarted callback.
            adoptSignum(this.signum);

            // v5 (multi-node): react IMMEDIATELY when this profile's start becomes
            // pending (queued) or the pending start completes — refresh the toolbar
            // (spinner + disabled Start) and info bar from the pending set, without
            // waiting for the (possibly still queued) STARTING state push.
            NodeModule.getInstance().addPendingListener(pendingListener);

            AppearanceModule.registerAppearanceListener(() -> {
                GuiFontManager.applyDefaultFont(innerTabbedPane);
            });

            LOGGER.debug("NodeProfilePanel constructor COMPLETED SUCCESSFULLY for profile: {} (tabs: {})", 
                    profile.getName(), innerTabbedPane.getTabCount());
        } catch (Exception e) {
            LOGGER.error("NodeProfilePanel constructor FAILED for profile: {}", profile.getName(), e);
            throw e;
        } finally {
            // Restore the thread-local log context — the EDT is shared, so it must never
            // leak past this panel's construction.
            if (previousContext != null) {
                application.utils.logging.NodeLogContext.set(previousContext);
            } else {
                application.utils.logging.NodeLogContext.clear();
            }
        }
    }

    /**
     * Single convergence point for binding this panel to a Signum instance.
     * <p>
     * Called with the Signum at construction time (if it already exists) and —
     * for late binding — when the console panel starts the node via
     * {@code NodeModule.startNode(name)} and hands the instance back. Idempotent for the
     * same instance; when a <i>different</i> instance is adopted (restart flow)
     * the previous state listener is unregistered first, so this panel always
     * has exactly one {@link Signum.StateListener} (PUSH trigger) and it only
     * invokes {@link #refreshFromSignum(Signum.State, Signum.State)}.
     * </p>
     *
     * @param newSignum the Signum instance to bind (null is ignored)
     */
    public void adoptSignum(Signum newSignum) {
        if (newSignum == null) {
            // The node has not been started yet in this session: there is no Signum to
            // observe, so no state pushes will ever arrive. Present the toolbar in a
            // startable state — the Start button is constructed disabled
            // (NodeToolbar.createButtons) and without this refresh it would stay gray
            // forever for a never-started profile (lazy-load flow: panel created with
            // a null Signum).
            if (toolbar != null) {
                toolbar.updateButtonStates(Signum.State.STOPPED, false);
            }
            return;
        }
        if (newSignum == this.signum) {
            return; // already bound to this instance
        }
        Signum previous = this.signum;
        if (previous != null && stateListener != null) {
            previous.removeStateListener(stateListener);
            stateListener = null;
        }
        this.signum = newSignum;
        // Give the toolbar its own reference to the Signum facade so it can
        // resolve the BlockchainProcessor and attach the DATABASE_CONSISTENCY_UPDATE
        // listener. Without this the toolbar's signum field stays null and the
        // DB-check icon never reflects the consistency state.
        if (toolbar != null) {
            toolbar.setSignum(newSignum);
        }
        stateListener = new Signum.StateListener() {
            @Override
            public void onStateChanged(Signum s, Signum.State oldState, Signum.State newState) {
                onNodeStateChanged(oldState, newState);
            }

            @Override
            public void onOperatingStateChanged(Signum s, Signum.OperatingState oldState, Signum.OperatingState newState) {
                // Unified: a pause/resume refreshes the SAME visual set as a lifecycle
                // change (info bar + toolbar + tab icon) via the single refreshVisuals()
                // point, so the tab header stays consistent. The console is intentionally
                // untouched — it reacts only to real lifecycle transitions.
                SwingUtilities.invokeLater(() -> refreshVisuals());
            }
        };
        newSignum.addStateListener(stateListener);
        newSignum.setGuiPanel(this);
        // Initial refresh so the UI immediately reflects the current node state.
        SwingUtilities.invokeLater(() -> refreshFromSignum(null, newSignum.getState()));
        LOGGER.info("Adopted Signum for profile: {} (state={})", profile.getName(), newSignum.getState());
    }

    /**
     * v5 (multi-node): pushed by {@code NodeModule} whenever THIS profile's
     * start-pending set membership changes — a start/restart was just queued
     * (the task may still be sitting in the lifecycle queue) or it completed
     * (success / failure / rejection).
     * <p>
     * Renders the toolbar (spinner + disabled buttons) and the info bar exactly
     * like the STARTING transition, so the user gets IMMEDIATE feedback on the
     * click — no waiting for the state push (which only arrives when the queued
     * task actually begins on a pool thread). Both components read the pending
     * set themselves; this method only triggers their refresh.
     * </p>
     */
    public void refreshPendingState() {
        SwingUtilities.invokeLater(() -> {
            if (toolbar != null) {
                Signum.State state = (signum != null) ? signum.getState() : Signum.State.STOPPED;
                toolbar.updateButtonStates(state, signum != null);
            }
            if (infoBar != null) {
                infoBar.refreshState();
            }
        });
    }

    /**
     * Unbinds this panel from the Signum instance it currently holds: the single
     * {@link Signum.StateListener} is removed so the (possibly still running) Signum
     * no longer pushes state to a dead panel. The Console tab cleans itself up via
     * {@code removeNotify()} (ProfileLogger subscriber disposal).
     * <p>
     * Safe to call multiple times; does not stop the node (lifecycle is owned by
     * {@code NodeModule} — v4 principle 2).
     * </p>
     */
    public void dispose() {
        // Unsubscribe from the start-pending broadcast so a disposed panel is no
        // longer refreshed (avoids a listener leak in the NodeModule).
        NodeModule.getInstance().removePendingListener(pendingListener);

        // Drop the SSOT tab-icon refresher so a disposed panel no longer triggers
        // tab updates (the toolbar's forwarded copy is cleared by setSignum(null)).
        this.tabIconRefresher = null;

        Signum current = this.signum;
        if (current != null && stateListener != null) {
            current.removeStateListener(stateListener);
            stateListener = null;
        }
        if (toolbar != null) {
            // Stop the Start/Stop spinner animation so its Timer cannot outlive
            // the toolbar, and detach all Signum-backed listeners.
            toolbar.setSignum(null);
            toolbar.setMaintenanceRefresher(null);
            toolbar.stopSpinnerAnimation();
        }
        LOGGER.info("NodeProfilePanel disposed for profile: {}", profile.getName());

        // Unregister the info bar from the cross-profile conflict broadcast so a disposed
        // panel is no longer refreshed by NodeInfoBar.refreshAllConflicts() (avoids a leak).
        if (infoBar != null) {
            infoBar.dispose();
        }
    }

    /**
     * Wires a ChangeListener to the inner tabbed pane to track when the Console tab
     * is selected/deselected. When visible, auto-scrolling is enabled; when hidden,
     * scrolling is suppressed to prevent layout jumping and reduce background CPU usage.
     */
    private void wireConsoleVisibilityTracking() {
        int consoleIndex = innerTabbedPane.indexOfTab("Console");
        if (consoleIndex < 0) {
            return;
        }

        ChangeListener listener = e -> {
            boolean consoleSelected = innerTabbedPane.getSelectedIndex() == consoleIndex;
            if (consolePanel != null && consolePanel.getUnifiedConsole() != null) {
                if (consoleSelected) {
                    consolePanel.getUnifiedConsole().onPanelActivated();
                } else {
                    consolePanel.getUnifiedConsole().onPanelDeactivated();
                }
            }
        };
        innerTabbedPane.addChangeListener(listener);
    }

    private void wireToolbarCallbacks() {
        toolbar.setOnRestart(this::restartNode);
        toolbar.setOnSyncToggle(this::toggleSync);
        toolbar.setOpenPhoenix(() -> openWebUi("/phoenix"));
        toolbar.setOpenClassic(() -> openWebUi("/classic"));
        toolbar.setOpenApi(() -> openWebUi("/api-doc"));
        toolbar.setOnEditConf(this::editConf);
        toolbar.setOnPopOff10(() -> popOff(10));
        toolbar.setOnPopOff100(() -> popOff(100));
        toolbar.setOnDbCheck(() -> dbCheckAction());
        // Wire hamburger menu button: delegate to console panel, passing toolbar's menuButton for popup positioning
        toolbar.setOnMenuToggle(() -> consolePanel.toggleMenu(toolbar.getMenuButton()));
    }

    /**
     * v4 (P1.3): pause/resume is owned by the Signum instance (PUSH).
     * The GUI no longer keeps a shadow syncPaused flag — the toolbar icon is
     * driven by onOperatingStateChanged (and by the explicit update below).
     */
    public void toggleSync() {
        Signum node = signum;
        if (node == null) {
            return;
        }
        Signum.OperatingState os = node.getOperatingState();
        if (os == Signum.OperatingState.PAUSED_USER || os == Signum.OperatingState.PAUSED_SYSTEM) {
            node.resumeByUser();
        } else {
            node.pauseByUser();
        }
        Signum.OperatingState after = node.getOperatingState();
        boolean paused = after == Signum.OperatingState.PAUSED_USER || after == Signum.OperatingState.PAUSED_SYSTEM;
        toolbar.updateSyncIcon(paused);
    }

    /**
     * Opens this profile's properties file in the default text editor — the same
     * file the node loads for this profile:
     * {@code <confFolder>/node/profiles/<profile>.properties}.
     */
    public void editConf() {
        String name = profile.getName();
        Path profileFile = PropertiesProfileLoader.resolveProfileFile(
                confFolder, ModuleIds.NODE, ModuleIds.CATEGORY_PROFILES, name);
        File file = profileFile.toFile();
        if (!file.exists()) {
            JOptionPane.showMessageDialog(this,
                    "Could not find properties file for profile '" + name + "':\n" + profileFile,
                    "File not found", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(file);
        } catch (java.io.IOException e) {
            LOGGER.error("Could not open conf file with default editor", e);
        }
    }

    /** Copy from NodeConsolePanel.openWebUi */
    public void openWebUi(String path) {
        try {
            Signum node = signum;
            PropertyService propertyService = node != null ? node.getPropertyService() : null;
            if (propertyService == null) {
                JOptionPane.showMessageDialog(this,
                        "PropertyService not available. Node may not be started.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            int port = propertyService.getInt(Props.API_PORT);
            String httpPrefix = propertyService.getBoolean(Props.API_SSL) ? "https://" : "http://";
            String address = httpPrefix + "localhost:" + port + path;
            try {
                Desktop.getDesktop().browse(new URI(address));
            } catch (Exception e) {
                LOGGER.error("Could not open browser", e);
            }
        } catch (Exception e) {
            LOGGER.error("Could not access PropertyService", e);
        }
    }

    /**
     * Requests a manual pop-off of the last {@code count} blocks.
     * <p>
     * Same behavior as the original SignumGUI: the <b>manual</b> pop-off path
     * ({@code BlockchainProcessor.popOff(int)}) on a dedicated background
     * thread — never on the EDT, since a pop-off can take a while. The work
     * runs inside this profile's {@code NodeLogContext} so all progress log
     * lines ("Request adds N blocks to pop off.", "Block processing threads
     * paused for pop-off.", "Pop-off height to X from Y", ...) are routed to
     * this profile's Node Console. Repeated clicks while a pop-off is already
     * queued add more blocks ("Request adds N blocks to pop off.").
     * </p>
     */
    public void popOff(int count) {
        Signum node = signum;
        BlockchainProcessor blockchainProcessor = node != null ? node.getBlockchainProcessor() : null;
        if (blockchainProcessor == null) {
            return;
        }
        new Thread(() -> application.utils.logging.NodeLogContext
                .runIn("node", profile.getName(), () -> blockchainProcessor.popOff(count))).start();
    }

    /** Copy from NodeConsolePanel.dbCheckAction */
    public void dbCheckAction() {
        Signum node = signum;
        BlockchainProcessor bp = node != null ? node.getBlockchainProcessor() : null;
        if (bp == null) {
            JOptionPane.showMessageDialog(this, "Blockchain processor not initialized.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        new Thread(() -> {
            try {
                int result = bp.checkDatabaseStateRequest();
                int height = bp.getLastCheckHeight();
                long totalMined = bp.getLastCheckTotalMined();
                long totalEffective = bp.getLastCheckTotalEffectiveBalance();
                long accountBalance = bp.getLastCheckAccountBalance();
                long escrowBalance = bp.getLastCheckEscrowBalance();
                SwingUtilities.invokeLater(() ->
                        showDbCheckResultDialog(result, height, totalMined, totalEffective, accountBalance, escrowBalance));
            } catch (IllegalStateException e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, e.getMessage(),
                                "Database Check Unavailable", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception ex) {
                LOGGER.error("Error during DB check", ex);
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "An error occurred during the database check.",
                                "Error", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    private void showDbCheckResultDialog(int result, int height, long totalMined, long totalEffective, long accountBalance, long escrowBalance) {
        double minedSigna = totalMined / 1_000_000_000.0;
        double effectiveSigna = totalEffective / 1_000_000_000.0;
        double accountSigna = accountBalance / 1_000_000_000.0;
        double escrowSigna = escrowBalance / 1_000_000_000.0;
        double diffSigna = (totalMined - totalEffective) / 1_000_000_000.0;
        boolean consistent = (result == 0);
        java.awt.Frame owner = (java.awt.Frame) javax.swing.SwingUtilities.getWindowAncestor(this);

        javax.swing.JDialog dialog = new javax.swing.JDialog(owner, "Database Consistency Check", true);
        // NOTE: global 'wrap' removed so that button + help share a row;
        // each logical row is terminated with an explicit 'wrap' constraint below.
        net.miginfocom.swing.MigLayout layout = new net.miginfocom.swing.MigLayout("insets 24, fillx");
        javax.swing.JPanel panel = new javax.swing.JPanel(layout);

        // Status header
        javax.swing.JLabel title = new javax.swing.JLabel(
                consistent ? "Database is CONSISTENT" : "Database is INCONSISTENT");
        title.setFont(getFont().deriveFont(java.awt.Font.BOLD, 16f));
        title.setForeground(consistent ? GuiColors.getStatusConsistent() : GuiColors.getContrastRed());

        // Detailed breakdown (matches log format)
        String detailHtml = "<html><body style='width:360px; font-family: monospace'>"
                + "Height: <b>" + height + "</b><br>"
                + "Total Mined (Supply): <b>" + String.format("%.8f", minedSigna) + "</b> SIGNA<br>"
                + "Total Effective Balance: <b>" + String.format("%.8f", effectiveSigna) + "</b> SIGNA<br>"
                + "Difference (Mined - Effective): <b>" + String.format("%.8f", diffSigna) + "</b> SIGNA<br>"
                + "--------------------------<br>"
                + "Account Balances: " + String.format("%.8f", accountSigna) + " SIGNA<br>"
                + "Escrow Balances: " + String.format("%.8f", escrowSigna) + " SIGNA<br>"
                + "Calculated Sum (Acc + Escrow): " + String.format("%.8f", effectiveSigna) + " SIGNA"
                + "</body></html>";
        javax.swing.JLabel detail = new javax.swing.JLabel(detailHtml, null, javax.swing.SwingConstants.CENTER);

        panel.add(title, "span, align center, wrap");
        panel.add(detail, "span, align center, gaptop 6, wrap");
        panel.add(new javax.swing.JSeparator(), "span, gaptop 12, gapbottom 12, wrap");

        // Action rows: [button] [help]
        javax.swing.JButton recheckBtn = new javax.swing.JButton("Run Database Check");
        recheckBtn.addActionListener(e -> { dialog.dispose(); dbCheckAction(); });
        application.utils.gui.HelpButton recheckHelp = new application.utils.gui.HelpButton();
        recheckHelp.setToolTipText("Re-run the database consistency check");
        recheckHelp.addActionListener(e -> showDbCheckHelpDialog(dialog, "Run Database Check",
                "Performs a full database consistency check comparing the total mined supply with the sum of "
                        + "all account and escrow balances.<br><br>"
                        + "Not available while a trim, prune, pop-off, or resolve operation is in progress."));
        panel.add(recheckBtn, "gaptop 8");
        panel.add(recheckHelp, "gapleft 2, wrap");

        javax.swing.JButton resolveBtn = new javax.swing.JButton("Start Auto Resolve");
        resolveBtn.setEnabled(!consistent);
        resolveBtn.addActionListener(e -> {
            BlockchainProcessor b = signum.getBlockchainProcessor();
            if (b != null) { dialog.dispose(); new Thread(b::manualResolveDatabaseConsistency).start(); }
        });
        application.utils.gui.HelpButton resolveHelp = new application.utils.gui.HelpButton();
        resolveHelp.setToolTipText("Resolve inconsistency by popping blocks");
        resolveHelp.addActionListener(e -> showDbCheckHelpDialog(dialog, "Start Auto Resolve",
                "Rolls back blocks one by one until the database becomes consistent or the safe rollback "
                        + "limit is reached. Only available when the database is inconsistent."));
        panel.add(resolveBtn, "gaptop 8");
        panel.add(resolveHelp, "gapleft 2, wrap");

        BlockchainProcessor bproc = signum.getBlockchainProcessor();
        boolean skipChecked = bproc != null && bproc.isSkipDbCheckOnManualPopOff();
        javax.swing.JCheckBox skipCb = new javax.swing.JCheckBox("Skip DB Check on Manual Pop-off", skipChecked);
        skipCb.addActionListener(e -> {
            BlockchainProcessor p = signum.getBlockchainProcessor();
            if (p != null) p.setSkipDbCheckOnManualPopOff(skipCb.isSelected());
        });
        application.utils.gui.HelpButton skipHelp = new application.utils.gui.HelpButton();
        skipHelp.setToolTipText("Toggle per-block check during manual pop-off");
        skipHelp.addActionListener(e -> showDbCheckHelpDialog(dialog, "Skip DB Check on Pop-off",
                "If enabled, skips the per-block consistency check during manual pop-off for faster operation.<br><br>"
                        + "<i>Session-only. Permanent: set <b>node.popOff.skipDatabaseCheck</b> in config.</i>"));
        panel.add(skipCb, "gaptop 8");
        panel.add(skipHelp, "gapleft 2, wrap");

        panel.add(new javax.swing.JSeparator(), "span, gaptop 16, gapbottom 12, wrap");

        javax.swing.JButton closeBtn = new javax.swing.JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        panel.add(closeBtn, "span, align right, wrap");

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(javax.swing.JDialog.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    private void showDbCheckHelpDialog(java.awt.Component parent, String title, String htmlBody) {
        String html = "<html><body style='width: 340px'><b>" + title + "</b><br><br>" + htmlBody + "</body></html>";
        JOptionPane.showMessageDialog(parent, html, title, JOptionPane.PLAIN_MESSAGE);
    }

    private String determineConfFolder() {
        // Single source of truth: the same conf root the node itself uses
        // (NodeModule.startNode -> Signum.CONF_FOLDER). Profiles live at
        // <confRoot>/node/profiles/<name>.properties — there is no per-network
        // subfolder in the profile architecture.
        return Signum.CONF_FOLDER;
    }

    private void restartNode() {
        // v4 (P1.6): the node lifecycle restart is owned by NodeModule
        // (restartNode(name) = stop + start on the same Signum instance).
        // The console panel provides the user-facing progress dialog and
        // delegates the actual restart to NodeModule.
        // Run within the profile's log context so the request logs (this panel +
        // NodeConsolePanel.restart + NodeModule.restartNode, all on the EDT) route
        // to the per-profile logger (<node.<profile>>) instead of the <system> context.
        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(), () -> {
            LOGGER.info("Restart requested for profile: {}", profile.getName());
            if (consolePanel != null) {
                consolePanel.restartNode();
            }
            if (infoBar != null) {
                infoBar.refreshData();
            }
        });
    }

    public NodeProfile getProfile() { return profile; }
    public NodeConsolePanel getConsolePanel() { return consolePanel; }
    public NodeConfigurationPanel getConfigurationPanel() { return configurationPanel; }
    public NodeLoggingPanel getLoggingPanel() { return loggingPanel; }
    public JTabbedPane getInnerTabbedPane() { return innerTabbedPane; }
    public NodeInfoBar getInfoBar() { return infoBar; }
    public NodeToolbar getToolbar() { return toolbar; }

    /** Returns the injected Signum facade (null if node not started). */
    public Signum getSignum() { return signum; }

    /**
     * Switches the inner tabbed pane to the Console tab.
     * Used by NodeConsolePanel to ensure command panel animation is visible.
     */
    public void switchToConsoleTab() {
        int consoleIndex = innerTabbedPane.indexOfTab("Console");
        if (consoleIndex >= 0 && innerTabbedPane.getSelectedIndex() != consoleIndex) {
            innerTabbedPane.setSelectedIndex(consoleIndex);
            LOGGER.debug("Switched to Console tab for panel visibility");
        }
    }

    public void stopNode() {
        // Single lifecycle entry point (v4): NodeModule stops this profile's node
        // asynchronously on the lifecycle thread (never blocks the EDT).
        // Run within the profile's log context so the request logs (this panel +
        // NodeModule.stopNode on the EDT) route to the per-profile logger
        // (<node.<profile>>) instead of the <system> context.
        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(), () -> {
            NodeModule.getInstance().stopNode(profile.getName());
            LOGGER.info("Stop requested for profile: {}", profile.getName());
        });
    }

    public void startNode() {
        // Single lifecycle entry point (v4): NodeModule creates (if missing) and
        // starts the node for this profile.
        // Run within the profile's log context so the request logs (this panel +
        // NodeModule.startNode/restartNode on the EDT) route to the per-profile
        // logger (<node.<profile>>) instead of the <system> context.
        Signum started = null;
        try {
            final Signum[] holder = new Signum[1];
            application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(), () -> {
                Signum existing = NodeModule.getInstance().get(profile.getName());
                if (existing != null && existing.getState() == Signum.State.ERROR) {
                    // ERROR is only recoverable through an explicit stop first
                    // (Signum.stop() accepts the ERROR state) — route Start through
                    // the restart path so a failed start never dead-ends the node.
                    holder[0] = NodeModule.getInstance().restartNode(profile.getName());
                } else {
                    holder[0] = NodeModule.getInstance().startNode(profile.getName());
                }
            });
            started = holder[0];
        } catch (Exception e) {
            application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(),
                    () -> LOGGER.error("Start failed for profile: {}", profile.getName(), e));
        }
        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(),
                () -> LOGGER.info("Start requested for profile: {}", profile.getName()));
        onNodeStarted(started);
    }

    /**
     * Called after this profile's node was started (toolbar Start, this panel's
     * {@link #startNode()}, or the console's Start). Binds the panel to the
     * returned instance — single {@link Signum.StateListener} (v4 D3, idempotent)
     * — and attaches the console subscriber so the profile console receives the
     * node's logs (replayed from the ProfileLogger buffer if attached late).
     */
    /**
     * Registers the SSOT tab-icon refresher (called by {@link NodePanel}).
     * <p>
     * The same callback is also forwarded to the toolbar so that an archival
     * maintenance phase (trim/prune) — which only the toolbar observes — triggers a
     * tab-icon refresh.
     *
     * @param refresher the callback that re-renders the tab icon from the SSOT
     */
    public void setTabIconRefresher(Runnable refresher) {
        this.tabIconRefresher = refresher;
        if (toolbar != null) {
            toolbar.setMaintenanceRefresher(refresher);
        }
    }

    /**
     * (EDT-safe) Re-renders the owning tab's status icon from the SSOT, if a
     * refresher has been registered by {@link NodePanel}.
     */
    private void refreshTabIcon() {
        Runnable refresher = tabIconRefresher;
        if (refresher != null) {
            SwingUtilities.invokeLater(refresher);
        }
    }

    private void onNodeStarted(Signum signum) {
        if (signum == null) {
            return;
        }
        adoptSignum(signum);
        if (consolePanel != null) {
            consolePanel.ensureProfileLoggerAttached();
        }
    }

    /**
     * Push trigger (PUSH, called by the single {@link Signum.StateListener} or by
     * {@link NodePanel}). Threads the update to the EDT and delegates to
     * {@link #refreshFromSignum(Signum.State, Signum.State)} — it never carries
     * data itself.
     */
    public void onNodeStateChanged(Signum.State oldState, Signum.State newState) {
        SwingUtilities.invokeLater(() -> refreshFromSignum(oldState, newState));
    }

    /**
     * Single refresh point (D2/D3): this panel owns <b>no</b> node state — it
     * re-reads everything from the Signum (single source of truth):
     * <pre>
     *   signum == null  → child panels render placeholders / inactive
     *   otherwise       → child panels render the real values from the Signum
     * </pre>
     * Must be called on the EDT (see {@link #onNodeStateChanged}).
     *
     * @param oldState  previous node state (null on initial refresh)
     * @param newState  current node state, as reported by the Signum
     */
    public void refreshFromSignum(Signum.State oldState, Signum.State newState) {
        String profileName = profile.getName();
        application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(),
                () -> LOGGER.info("[{}] State change: {} -> {}", profileName, oldState, newState));

        // Single visual refresh point (info bar + toolbar + tab icon), shared with the
        // operating-state path so the tab header always matches the panel.
        refreshVisuals();

        // Forward lifecycle events to the console panel so it can manage MetricsPanel
        // visibility in sync with the node state. When the node reaches READY/RUNNING,
        // Signum.getPropertyService() is available and the MetricsPanel can initialize.
        // (Lifecycle only — a pure pause/resume change refreshes the visuals directly
        // via refreshVisuals() and must not re-trigger lifecycle side effects.)
        if (consolePanel != null) {
            consolePanel.onNodeStateChanged(oldState, newState);
        }
    }

    /**
     * Single visual-refresh point (EDT): re-renders every state-visible widget of this
     * profile straight from the Signum (single source of truth) — the info bar, the
     * toolbar buttons, the operating (pause) sync icon and the tab header icon. It is
     * invoked on BOTH lifecycle changes ({@link #refreshFromSignum}) and operating
     * changes ({@link Signum.StateListener#onOperatingStateChanged}) so all four
     * surfaces always agree. It never touches the console.
     */
    private void refreshVisuals() {
        Signum s = this.signum;
        if (infoBar != null) {
            infoBar.refreshState();
        }
        if (toolbar != null) {
            Signum.State state = (s != null) ? s.getState() : Signum.State.CREATED;
            toolbar.updateButtonStates(state, s != null);
            toolbar.updateSyncIcon(isPaused());
        }
        refreshTabIcon();
    }

    /** True when the node is currently in a paused operating state (PAUSED_USER/PAUSED_SYSTEM). */
    private boolean isPaused() {
        Signum s = this.signum;
        if (s == null) {
            return false;
        }
        Signum.OperatingState op = s.getOperatingState();
        return op == Signum.OperatingState.PAUSED_USER || op == Signum.OperatingState.PAUSED_SYSTEM;
    }

    public void onStatusMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            LOGGER.debug("[{}] Status: {}", profile.getName(), message);
        });
    }

    public void onError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            LOGGER.error("[{}] Error: {}", profile.getName(), errorMessage);
            JOptionPane.showMessageDialog(
                    this,
                    "Node error: " + errorMessage,
                    "Error - " + profile.getName(),
                    JOptionPane.ERROR_MESSAGE
            );
        });
    }
}