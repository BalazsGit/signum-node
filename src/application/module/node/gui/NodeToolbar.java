package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.util.Listener;
import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawings;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;
import application.utils.gui.HoverScaleIcon;
import application.utils.gui.SpinnerIcon;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import application.module.node.NodeModule;

/**
 * Toolbar panel that sits below the NodeInfoBar in a NodeProfilePanel.
 * Mirrors NodeConsolePanel toolbar layout exactly:
 * - Icon-only buttons (no text labels)
 * - Start/Stop toggle (PLAY when stopped, POWER_OFF when running)
 * - Pop-off buttons with animated chevron toggle
 * - Hamburger menu button pinned to the right
 *
 * Button order matches ConsolePanel:
 * Start/Stop | Restart | Phoenix | Classic | Edit Conf | API | [PopOff Toggle] → Pop10 | Pop100 | DB Check | Sync | Hamburger Menu
 *
 * The toolbar uses callback Runnables so the NodeConsolePanel can still own
 * the actual command execution logic. Icons scale dynamically with font size.
 */
@SuppressWarnings("serial")
public class NodeToolbar extends JPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeToolbar.class);
    private static final int ANIMATION_DURATION_MS = 250;

    private final NodeProfile profile;

    /**
     * Signum facade for per-instance access to node services.
     * Set via {@link #setSignum(Signum)} by the parent NodeProfilePanel.
     * May be null in legacy mode or before the node starts.
     * @since 4.0 Phase G - Greenfield wiring
     */
    private Signum signum;

    // Lifecycle buttons - Start/Stop merged into single toggle + Restart
    private JButton startStopButton;
    private JButton restartButton;
    private JButton syncButton;

    // Web UI buttons (icon-only)
    private JButton openPhoenixButton;
    private JButton openClassicButton;
    private JButton openApiButton;
    private JButton editConfButton;

    // Operations (icon-only)
    private JButton popOff10Button;
    private JButton popOff100Button;
    private JButton dbCheckButton;

    // Pop-off toggle panel (animated show/hide like ConsolePanel)
    private CustomDrawingComponent popOffToggle;
    private JPanel popOffButtonsPanel;
    private Timer popOffAnimator;
    private boolean showPopOff = false;
    private int popOffPanelWidth = -1;

    // Hamburger menu button (right side)
    private JButton menuButton;

    // Sync state tracking
    private boolean isSyncStopped = false;
    private boolean consistencyListenerAttached = false;

    /**
     * Spinner animation for the Start/Stop button while the node is in the
     * STARTING/STOPPING state (v4 §9.4). Driven by an EDT Timer; stopped as soon
     * as the button leaves the transitioning state.
     */
    private Timer spinnerTimer;
    private SpinnerIcon spinnerIcon;

    /**
     * DB archival maintenance status label (v4 §9.6). Visible only while a
     * TRIMMING or PRUNING phase is active; driven by pushed trim/prune listener
     * events — no polling.
     */
    private JLabel maintenanceLabel;

    /**
     * SSOT tab-icon refresher (optional): invoked from {@link #updateMaintenanceStateLabel()}
     * whenever an archival maintenance phase starts/ends, so the owning profile's tab
     * icon reflects trim/prune. Forwarded here by the owning {@link NodeProfilePanel}.
     */
    private volatile Runnable maintenanceRefresher;

    private final Listener<BlockchainProcessor.TrimStats> trimStateListener =
            stats -> refreshMaintenanceState();
    private final Listener<BlockchainProcessor.PruneStats> pruneStateListener =
            stats -> refreshMaintenanceState();

    // Callbacks delegated to NodeConsolePanel or parent
    private Runnable onStartStop;
    private Runnable onRestart;
    /**
     * v4: invoked after the toolbar started the node via {@code NodeModule.startNode}.
     * The owning {@code NodeProfilePanel} uses it to adopt the returned instance
     * (single state listener + console attach) — the GUI never keeps its own copy.
     */
    private NodeStartedListener onNodeStarted;
    private Runnable onSyncToggle;
    private Runnable onOpenPhoenix;
    private Runnable onOpenClassic;
    private Runnable onOpenApi;
    private Runnable onEditConf;
    private Runnable onPopOff10;
    private Runnable onPopOff100;
    private Runnable onDbCheck;
    private Runnable onMenuToggle;

    /**
     * Creates a new NodeToolbar for the given profile.
     *
     * @param profile The NodeProfile this toolbar belongs to
     */
    public NodeToolbar(NodeProfile profile) {
        this.profile = profile;
        initialize();
    }

    /**
     * Sets the Signum facade for per-instance access to node services.
     * Called by NodeProfilePanel after construction so that all node component
     * access goes through the instance facade rather than static Signum calls.
     *
     * @param signum the Signum facade for this profile (may be null)
     * @since 4.0 Phase G - Greenfield wiring
     */
    public void setSignum(Signum signum) {
        Signum previous = this.signum;
        this.signum = signum;
        detachMaintenanceStateListeners(previous);
        attachMaintenanceStateListeners(signum);
        refreshMaintenanceState();
        updateDbCheckIconColor();
    }

    /**
     * Returns the injected Signum facade, or null if not set.
     * @return the Signum facade for this profile
     */
    Signum getSignum() {
        return signum;
    }

    private void initialize() {
        // Ensure FontAwesome is registered before creating icon buttons
        // (mirrors NodeConsolePanel.initConsoleUI() which calls IconFontSwing.register)
        try {
            IconFontSwing.register(FontAwesome.getIconFont());
        } catch (Exception e) {
            LOGGER.debug("FontAwesome already registered or registration failed", e);
        }

        // Outer layout matches ConsolePanel toolBar: MigLayout with left scroll + right icons
        setLayout(new MigLayout("insets 0, gap 0, fillx, hidemode 3", "[grow, shrink]0[pref!]", ""));
        setOpaque(false);

        createButtons();
        buildToolbar();

        // Register for appearance updates
        AppearanceModule.registerAppearanceListener(() -> updateStyles());
    }

    /**
     * Creates all toolbar buttons (icon-only, matching ConsolePanel style).
     */
    private void createButtons() {
        float iconSize = GuiConstants.getToolBarIconSize();
        Color iconColor = GuiColors.getButtonIcon();

        // --- Start/Stop toggle (PLAY when stopped, POWER_OFF when running) ---
        startStopButton = new JButton();
        setIconButton(startStopButton, FontAwesome.PLAY, GuiColors.getPeerActive(), iconSize);
        startStopButton.setToolTipText("Start the node");
        startStopButton.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        startStopButton.setOpaque(false);
        startStopButton.setContentAreaFilled(false);
        startStopButton.setEnabled(false);

        // --- Restart ---
        restartButton = createIconButton(FontAwesome.REFRESH, "Restart the node");
        restartButton.setEnabled(false);

        // --- Sync (Pause/Resume) ---
        syncButton = createIconButton(FontAwesome.PAUSE, "Pause/Resume blockchain sync");
        syncButton.setEnabled(false);

        // --- Web UI buttons ---
        // Phoenix/Classic/API docs are served by the node's own web server, so they
        // only work while the node is RUNNING — start disabled; updateButtonStates()
        // enables them once the node is running.
        openPhoenixButton = createIconButton(FontAwesome.FIRE, "Open Phoenix Wallet");
        openClassicButton = createIconButton(FontAwesome.WINDOW_RESTORE, "Open Classic Wallet");
        openApiButton = createIconButton(FontAwesome.BOOK, "Open API Documentation");
        openPhoenixButton.setEnabled(false);
        openClassicButton.setEnabled(false);
        openApiButton.setEnabled(false);
        editConfButton = createIconButton(FontAwesome.PENCIL, "Edit node configuration file");

        // --- Pop-off buttons ---
        popOff10Button = createIconButton(FontAwesome.STEP_BACKWARD, "Remove last 10 blocks");
        popOff100Button = createIconButton(FontAwesome.BACKWARD, "Remove last 100 blocks");
        popOff10Button.setEnabled(false);
        popOff100Button.setEnabled(false);

        // --- DB Check ---
        dbCheckButton = createIconButton(FontAwesome.DATABASE, "Run database consistency check");

        // --- DB maintenance state label (trim/prune) — hidden unless a phase is active ---
        maintenanceLabel = new JLabel(" ");
        maintenanceLabel.setForeground(GuiColors.getButtonIcon());
        maintenanceLabel.setVisible(false);

        // --- Hamburger menu button (right side) ---
        menuButton = new JButton();
        setIconButton(menuButton, FontAwesome.BARS, iconColor, iconSize);
        menuButton.setToolTipText("Menu");
        menuButton.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        menuButton.setOpaque(false);
        menuButton.setContentAreaFilled(false);
    }

    /**
     * Creates an icon-only toolbar button matching ConsolePanel style.
     */
    private JButton createIconButton(FontAwesome iconCode, String tooltip) {
        JButton button = new JButton();
        float iconSize = GuiConstants.getToolBarIconSize();
        setIconButton(button, iconCode, GuiColors.getButtonIcon(), iconSize);
        button.setToolTipText(tooltip);
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        return button;
    }

    /**
     * Sets both the normal and the hover (rollover) icons of an icon button.
     * <p>
     * The hover effect <b>grows</b> the glyph by {@link HoverScaleIcon#DEFAULT_SCALE}
     * (15%) rather than recoloring it, with no layout shift: the normal and the
     * rollover icon both expose the SAME (larger) bounding box, so the JButton is
     * sized once (to that box) and never resizes when the hover glyph is swapped
     * in — the glyph simply scales up inside a constant area, so nothing below the
     * button moves. The colour is unchanged on hover.
     * </p>
     */
    private static void setIconButton(JButton button, FontAwesome iconCode, Color color, float iconSize) {
        HoverScaleIcon.install(button,
                IconFontSwing.buildIcon(iconCode, iconSize, color),
                IconFontSwing.buildIcon(iconCode, iconSize * HoverScaleIcon.DEFAULT_SCALE, color));
    }

    /**
     * Builds the toolbar layout matching NodeConsolePanel:
     * - leftButtons panel with all action buttons
     * - ResponsiveToolbarScrollPane wrapper (horizontal overflow scrolling)
     * - rightIconsPanel with hamburger menu pinned to the right
     */
    private void buildToolbar() {
        // Left button row panel - all buttons in one horizontal row
        JPanel leftButtons = new JPanel(new MigLayout("insets 0, gap 5, hidemode 3, aligny top"));
        leftButtons.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        leftButtons.setOpaque(false);

        // Button order: Start/Stop | Restart | Sync | Phoenix | Classic | Edit Conf | API | PopOff Toggle | [PopOff buttons] | DB Check
        leftButtons.add(startStopButton);
        leftButtons.add(restartButton);
        leftButtons.add(syncButton);
        leftButtons.add(openPhoenixButton);
        leftButtons.add(openClassicButton);
        leftButtons.add(editConfButton);
        leftButtons.add(openApiButton);

        // Pop-off toggle (chevron) + collapsible pop-off buttons panel
        buildPopOffSection(leftButtons);

        leftButtons.add(dbCheckButton);
        leftButtons.add(maintenanceLabel, "gapleft 8");
        leftButtons.add(popOffToggle);

        // Wrap leftButtons in ResponsiveToolbarScrollPane for horizontal overflow scrolling
        application.utils.gui.ResponsiveToolbarScrollPane toolbarScroll =
                new application.utils.gui.ResponsiveToolbarScrollPane(leftButtons, GuiConstants.TOOLBAR_INSETS, true);
        toolbarScroll.setHorizontalScrollBarPolicy(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        toolbarScroll.setVerticalScrollBarPolicy(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        toolbarScroll.getHorizontalScrollBar().setUnitIncrement(16);

        add(toolbarScroll, "growx, pushx, aligny top");

        // Right icons panel with hamburger menu pinned to the right
        JPanel rightIconsPanel = new JPanel(new MigLayout("insets 5 5 5 10, gap 5, aligny top"));
        rightIconsPanel.setOpaque(false);
        rightIconsPanel.add(menuButton);
        add(rightIconsPanel, "shrink 0, aligny top");

        // Wire up button actions
        wireActions();
    }

    /**
     * Builds the collapsible pop-off button section with chevron toggle.
     * Matches ConsolePanel's popOffButtonsPanel behavior exactly.
     */
    private void buildPopOffSection(JPanel leftButtons) {
        // Pop-off buttons panel (collapsible, like ConsolePanel)
        popOffButtonsPanel = new JPanel() {
            @Override
            protected void paintChildren(Graphics g) {
                Graphics g2 = g.create();
                g2.setClip(0, 0, getWidth(), getHeight());
                super.paintChildren(g2);
                g2.dispose();
            }
        };
        popOffButtonsPanel.setLayout(new BoxLayout(popOffButtonsPanel, BoxLayout.X_AXIS));
        popOffButtonsPanel.setOpaque(false);
        popOffButtonsPanel.setBorder(null);
        popOffButtonsPanel.setMinimumSize(new Dimension(0, 0));
        popOffButtonsPanel.add(popOff10Button);
        popOffButtonsPanel.add(Box.createHorizontalStrut(5));
        popOffButtonsPanel.add(popOff100Button);
        leftButtons.add(popOffButtonsPanel);

        // Chevron toggle for pop-off buttons
        popOffToggle = new CustomDrawingComponent(
                showPopOff ? CustomDrawings.Chevron.LEFT : CustomDrawings.Chevron.RIGHT);
        popOffToggle.setToolTipText("Toggle Pop-off buttons");
        popOffToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        popOffToggle.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                togglePopOffButtons();
            }
        });

        // One-time setup: hide pop-off buttons initially
        popOffButtonsPanel.addHierarchyListener(new java.awt.event.HierarchyListener() {
            @Override
            public void hierarchyChanged(java.awt.event.HierarchyEvent e) {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                        && popOffButtonsPanel.isShowing()) {
                    if (popOffPanelWidth < 0) {
                        popOffPanelWidth = popOffButtonsPanel.getPreferredSize().width;
                        popOffButtonsPanel.setPreferredSize(new Dimension(0, Math.max(popOffButtonsPanel.getHeight(), 25)));
                        popOffButtonsPanel.setVisible(false);
                        revalidate();
                    }
                    popOffButtonsPanel.removeHierarchyListener(this);
                }
            }
        });

        popOffToggle.addPropertyChangeListener("UI", e -> SwingUtilities.invokeLater(this::updatePopOffToggleIcon));
    }

    /**
     * Recursively searches up the component hierarchy to find an enclosing JScrollPane.
     * Robust against intermediate wrapper panels (e.g., ResponsiveToolbarScrollPane contentWrapper).
     */
    private static javax.swing.JScrollPane findScrollPaneAncestor(Component start) {
        Component c = start.getParent();
        while (c != null) {
            if (c instanceof javax.swing.JScrollPane) {
                return (javax.swing.JScrollPane) c;
            }
            c = c.getParent();
        }
        return null;
    }

    /**
     * Toggles the pop-off buttons panel with animation.
     * Mirrors NodeConsolePanel.togglePopOffButtons() exactly.
     */
    private void togglePopOffButtons() {
        if (popOffAnimator != null && popOffAnimator.isRunning()) {
            return;
        }

        showPopOff = !showPopOff;
        updatePopOffToggleIcon();

        // Calculate target dimensions
        Dimension naturalSize = popOffButtonsPanel.getLayout().preferredLayoutSize(popOffButtonsPanel);
        final int targetWidth = naturalSize.width;
        final int targetHeight = editConfButton != null ? editConfButton.getPreferredSize().height
                : Math.max(naturalSize.height, 25);
        Container parent = popOffButtonsPanel.getParent();

        // Robust scroll pane search: traverse up until we find a JScrollPane ancestor.
        // This works regardless of intermediate wrapper panels (e.g., contentWrapper in ResponsiveToolbarScrollPane).
        final javax.swing.JScrollPane sp = findScrollPaneAncestor(popOffButtonsPanel);

        if (showPopOff) {
            // Opening
            popOffButtonsPanel.setVisible(true);
            popOffButtonsPanel.setPreferredSize(new Dimension(0, targetHeight));
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }

            final int finalTargetWidth = targetWidth;
            final javax.swing.JScrollPane finalSp = sp;
            popOffAnimator = new Timer(10, new java.awt.event.ActionListener() {
                private final long startTime = System.currentTimeMillis();

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - this.startTime;
                    float progress = Math.min(1.0f, (float) elapsed / ANIMATION_DURATION_MS);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3); // Ease out

                    int w = (int) (finalTargetWidth * progress);
                    popOffButtonsPanel.setPreferredSize(new Dimension(w, targetHeight));
                    if (parent != null) {
                        parent.revalidate();
                        parent.repaint();
                    }

                    // Scroll-to-max deferred via invokeLater (revalidate is async)
                    if (finalSp != null) {
                        SwingUtilities.invokeLater(() -> {
                            javax.swing.JScrollBar hBar = finalSp.getHorizontalScrollBar();
                            if (hBar != null) {
                                hBar.setValue(hBar.getMaximum());
                            }
                        });
                    }

                    if (progress >= 1.0f) {
                        ((javax.swing.Timer) e.getSource()).stop();
                        popOffButtonsPanel.setPreferredSize(null); // Reset to natural size
                        if (parent != null) parent.revalidate();
                        if (finalSp != null) {
                            SwingUtilities.invokeLater(() -> {
                                javax.swing.JScrollBar hBar = finalSp.getHorizontalScrollBar();
                                if (hBar != null) {
                                    hBar.setValue(hBar.getMaximum());
                                }
                            });
                        }
                    }
                }
            });
            popOffAnimator.start();
        } else {
            // Closing
            final int startWidth = popOffButtonsPanel.getWidth();
            final javax.swing.JScrollPane finalSp = sp;

            popOffAnimator = new Timer(10, new java.awt.event.ActionListener() {
                private final long startTime = System.currentTimeMillis();

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - this.startTime;
                    float progress = Math.min(1.0f, (float) elapsed / ANIMATION_DURATION_MS);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3); // Ease out

                    int w = (int) (startWidth * (1.0f - progress));
                    popOffButtonsPanel.setPreferredSize(new Dimension(w, targetHeight));
                    if (parent != null) {
                        parent.revalidate();
                        parent.repaint();
                    }

                    // Scroll-to-max deferred via invokeLater (revalidate is async)
                    if (finalSp != null) {
                        SwingUtilities.invokeLater(() -> {
                            javax.swing.JScrollBar hBar = finalSp.getHorizontalScrollBar();
                            if (hBar != null) {
                                hBar.setValue(hBar.getMaximum());
                            }
                        });
                    }

                    if (progress >= 1.0f) {
                        ((javax.swing.Timer) e.getSource()).stop();
                        popOffButtonsPanel.setPreferredSize(new Dimension(0, targetHeight));
                        popOffButtonsPanel.setVisible(false);
                        if (parent != null) parent.revalidate();
                        if (finalSp != null) {
                            SwingUtilities.invokeLater(() -> {
                                javax.swing.JScrollBar hBar = finalSp.getHorizontalScrollBar();
                                if (hBar != null) {
                                    hBar.setValue(hBar.getMaximum());
                                }
                            });
                        }
                    }
                }
            });
            popOffAnimator.start();
        }
    }

    private void updatePopOffToggleIcon() {
        if (popOffToggle != null) {
            popOffToggle.setDrawing(showPopOff ? CustomDrawings.Chevron.LEFT : CustomDrawings.Chevron.RIGHT);
        }
    }

    private void wireActions() {
        startStopButton.addActionListener(e -> {
            if (onStartStop != null) {
                onStartStop.run();
            } else {
                handleStartStopToggle();
            }
        });

        restartButton.addActionListener(e -> {
            if (onRestart != null) {
                onRestart.run();
            }
        });

        syncButton.addActionListener(e -> {
            if (onSyncToggle != null) {
                onSyncToggle.run();
            }
        });

        openPhoenixButton.addActionListener(e -> {
            if (onOpenPhoenix != null) onOpenPhoenix.run();
        });
        openClassicButton.addActionListener(e -> {
            if (onOpenClassic != null) onOpenClassic.run();
        });
        openApiButton.addActionListener(e -> {
            if (onOpenApi != null) onOpenApi.run();
        });
        editConfButton.addActionListener(e -> {
            if (onEditConf != null) onEditConf.run();
        });
        popOff10Button.addActionListener(e -> {
            if (onPopOff10 != null) onPopOff10.run();
        });
        popOff100Button.addActionListener(e -> {
            if (onPopOff100 != null) onPopOff100.run();
        });
        dbCheckButton.addActionListener(e -> {
            if (onDbCheck != null) onDbCheck.run();
        });

        // Wire hamburger menu button action
        menuButton.addActionListener(e -> {
            if (onMenuToggle != null) {
                onMenuToggle.run();
            }
        });
    }

    /**
     * Handles the Start/Stop toggle button click.
     * If node is running/paused, shows confirmation and stops; otherwise starts.
     */
    private void handleStartStopToggle() {
        // A start is already queued/running for this profile (start pending): the
        // button is disabled by updateButtonStates() anyway — this guard covers any
        // path that reaches here before the EDT processed the disable (fast
        // double-click), so a duplicate start can never be queued.
        if (NodeModule.getInstance().isStartPending(profile.getName())) {
            LOGGER.info("Start already pending for profile: {} — ignoring toggle click", profile.getName());
            return;
        }
        Signum signum = NodeModule.getInstance().get(profile.getName());
        Signum.State state = (signum != null) ? signum.getState() : Signum.State.CREATED;

        if (state == Signum.State.RUNNING || false) {
            // Currently running/paused -> stop
            int result = JOptionPane.showConfirmDialog(
                    this,
                    "This will stop the node for profile '" + profile.getName() + "'. Continue?",
                    "Stop Node",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (result == JOptionPane.YES_OPTION) {
                // Single lifecycle entry point (v4): NodeModule stops this profile's node.
                NodeModule.getInstance().stopNode(profile.getName());
                LOGGER.info("Stop requested for profile: {}", profile.getName());
            }
        } else {
            // Currently stopped/ready/error -> start (NodeModule creates the
            // Signum if it does not exist yet).
            try {
                Signum started;
                if (state == Signum.State.ERROR) {
                    // A failed start can only be recovered by an explicit stop
                    // first (Signum.stop() accepts the ERROR state); route the
                    // user's Start click through the restart path (stop + start)
                    // so it never dead-ends in the ERROR state.
                    started = NodeModule.getInstance().restartNode(profile.getName());
                } else {
                    started = NodeModule.getInstance().startNode(profile.getName());
                }
                // v4: hand the instance back to the owning panel (adopt + console attach).
                if (onNodeStarted != null) {
                    onNodeStarted.onNodeStarted(started);
                }
            } catch (Exception e) {
                application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(),
                        () -> LOGGER.error("Start failed for profile: {}", profile.getName(), e));
            }
            application.utils.logging.NodeLogContext.runIn(application.utils.config.ModuleIds.NODE, profile.getName(),
                    () -> LOGGER.info("Start requested for profile: {}", profile.getName()));
        }
    }

    /**
     * Updates the Start/Stop toggle button appearance and enabled states based on lifecycle state.
     * Call this method from NodeProfilePanel.onNodeStateChanged().
     *
     * @param state The current lifecycle state of the node
     * @param nodeStarted Whether the node has been started at least once (a Signum exists);
     *                    when false the Restart button stays disabled (nothing to restart yet)
     */
    public void updateButtonStates(Signum.State state, boolean nodeStarted) {
        boolean isRunning = (state == Signum.State.RUNNING || false);
        // v5: a queued/running start (start pending) renders EXACTLY like the
        // STARTING transition — spinner + disabled buttons — immediately on the
        // click, even before the STARTING state push arrives (with the parallel
        // lifecycle pool the task may still be sitting in the queue).
        boolean startPending = NodeModule.getInstance().isStartPending(profile.getName());
        boolean isTransitioning = (state == Signum.State.STARTING
                || state == Signum.State.STOPPING
                || startPending
                );

        // Web UI buttons (Phoenix wallet, Classic wallet, API docs): they open pages
        // served by the node's own web server, so they are only functional while the
        // node is RUNNING. Disabled in every other state — clicking them would only
        // yield a "connection refused" in the browser.
        boolean webUiAvailable = isRunning;
        String webUiHint = "Start the node first - then you can open ";
        openPhoenixButton.setEnabled(webUiAvailable);
        openPhoenixButton.setToolTipText(webUiAvailable ? "Open Phoenix Wallet" : webUiHint + "the Phoenix Wallet");
        openClassicButton.setEnabled(webUiAvailable);
        openClassicButton.setToolTipText(webUiAvailable ? "Open Classic Wallet" : webUiHint + "the Classic Wallet");
        boolean apiDocsAvailable = webUiAvailable && isApiDocsEnabled();
        openApiButton.setEnabled(apiDocsAvailable);
        openApiButton.setToolTipText(!webUiAvailable ? webUiHint + "the API Documentation"
                : apiDocsAvailable ? "Open API Documentation"
                : "API docs are disabled in the node configuration (API.DocMode=off)");

        float iconSize = GuiConstants.getToolBarIconSize();

        // Defense-in-depth: pause is only meaningful for a RUNNING node's blockchain
        // sync. Whenever the lifecycle is not RUNNING, the sync button is disabled and
        // its icon must show the neutral PAUSE state (not a stale RESUME) so a
        // (re)started node always presents the "Pause" action first. (v5: fix the
        // "Resume" button wrongly persisting across a stop→start cycle.)
        if (!isRunning) {
            isSyncStopped = false;
            setIconButton(syncButton, FontAwesome.PAUSE, GuiColors.getButtonIcon(), iconSize);
        }

        if (isRunning) {
            // Show POWER_OFF icon - node is running, click to stop
            stopSpinner();
            setIconButton(startStopButton, FontAwesome.POWER_OFF, GuiColors.getContrastRed(), iconSize);
            startStopButton.setToolTipText("Stop the node (shutdown)");
            startStopButton.setEnabled(true);
            restartButton.setEnabled(true);
            syncButton.setEnabled(true);
            popOff10Button.setEnabled(true);
            popOff100Button.setEnabled(true);
            dbCheckButton.setEnabled(true);

            // Now that the node is running, try to attach consistency listener
            // (setSignum may have been called before the node started)
            attachMaintenanceStateListeners(signum);
            updateDbCheckIconColor();

            // Update sync icon based on pause state
            setIconButton(syncButton,
                    isSyncStopped ? FontAwesome.PLAY : FontAwesome.PAUSE, GuiColors.getButtonIcon(), iconSize);
        } else if (state == Signum.State.ERROR) {
            // Show PLAY icon - can restart after error
            stopSpinner();
            setIconButton(startStopButton, FontAwesome.PLAY, GuiColors.getPeerActive(), iconSize);
            startStopButton.setToolTipText("Start the node");
            startStopButton.setEnabled(true);
            restartButton.setEnabled(true);
            syncButton.setEnabled(false);
            popOff10Button.setEnabled(false);
            popOff100Button.setEnabled(false);
            dbCheckButton.setEnabled(false);
        } else if (isTransitioning) {
            // Animated SPINNER while the node is starting/stopping (v4 §9.4):
            // the heavy work runs on the NodeModule lifecycle thread, so the EDT
            // stays free and the animation is visible.
            startSpinner();
            String tooltip = switch (state) {
                case STARTING -> "Starting...";
                case STOPPING -> "Stopping...";
                // A pending start (queued, not yet STARTING) shows the same state.
                default -> startPending ? "Starting..." : state.name().toLowerCase();
            };
            startStopButton.setToolTipText(tooltip);
            startStopButton.setEnabled(false);
            restartButton.setEnabled(false);
            syncButton.setEnabled(false);
            popOff10Button.setEnabled(false);
            popOff100Button.setEnabled(false);
            dbCheckButton.setEnabled(false);
        } else {
            // STOPPED / CREATED / INITIALIZED -> show PLAY icon (node is startable).
            // CREATED is a valid startable state: NodeModule.startNode() creates the
            // Signum and Signum.init() requires exactly the CREATED state, and
            // handleStartStopToggle() explicitly starts from any non-RUNNING state.
            // Restart is meaningful only once the node has actually started at least once.
            // CREATED / INITIALIZED mean "created but never started" — a restart here would
            // be a silent no-op (the previous complaint). STOPPED / ERROR mean "was started",
            // so restart (apply config) is valid. nodeStarted additionally guards the
            // "no Signum at all" placeholder (adoptSignum(null) -> STOPPED with nodeStarted=false).
            boolean canRestart = nodeStarted
                    && state != Signum.State.CREATED
                    && state != Signum.State.INITIALIZED;
            stopSpinner();
            setIconButton(startStopButton, FontAwesome.PLAY, GuiColors.getPeerActive(), iconSize);
            startStopButton.setToolTipText("Start the node");
            startStopButton.setEnabled(true);
            restartButton.setEnabled(canRestart);
            restartButton.setToolTipText(canRestart
                    ? "Restart the node (applies any saved configuration changes)"
                    : "Start the node first — then you can restart it");
            syncButton.setEnabled(false);
            popOff10Button.setEnabled(false);
            popOff100Button.setEnabled(false);
            dbCheckButton.setEnabled(false);
        }
    }

    /**
     * Returns whether the (potentially running) node serves the API documentation.
     * <p>
     * The docs are disabled when the {@code API.DocMode} property is set to "off"
     * (see {@code WebServerImpl}); any other value serves them.
     */
    private boolean isApiDocsEnabled() {
        Signum node = this.signum;
        if (node == null) {
            return false;
        }
        try {
            PropertyService propertyService = node.getPropertyService();
            return propertyService != null
                    && !"off".equalsIgnoreCase(propertyService.getString(Props.API_DOC_MODE));
        } catch (Exception e) {
            // PropertyService not accessible — keep the button available rather
            // than hiding a feature that might actually work.
            return true;
        }
    }

    /**
     * Starts the animated spinner on the Start/Stop button (EDT only).
     * <p>
     * The arc advances 30° every 50 ms; the animation keeps running until
     * {@link #stopSpinner()} is called (i.e. until {@link #updateButtonStates(Signum.State)}
     * receives a non-transitioning state). Repeated calls are idempotent.
     * </p>
     */
    private void startSpinner() {
        if (spinnerIcon == null) {
            int size = Math.max(12, (int) Math.ceil(GuiConstants.getToolBarIconSize()));
            spinnerIcon = new SpinnerIcon(size, GuiColors.getTransition());
        }
        if (spinnerTimer == null) {
            spinnerTimer = new Timer(50, e -> {
                spinnerIcon.advance(30f);
                startStopButton.repaint();
            });
        }
        spinnerTimer.start();
        // Keep the button's fixed (hover) bounding box so the transition spinner does
        // not resize the button and shift the layout.
        Icon spinner = HoverScaleIcon.box(
                GuiConstants.getToolBarIconSize() * HoverScaleIcon.DEFAULT_SCALE, spinnerIcon);
        // The Start/Stop button is DISABLED while the node is starting/stopping, so
        // Swing paints its disabledIcon (and its rolloverIcon on hover) — which still
        // holds the stale grayed PLAY/POWER_OFF left by the last setIconButton() call.
        // Set all three to the animated spinner so it stays visible (and rotating) in
        // every state, not only the enabled one.
        startStopButton.setIcon(spinner);
        startStopButton.setRolloverIcon(spinner);
        startStopButton.setDisabledIcon(spinner);
    }

    /** Stops the spinner animation without changing the current button icon. */
    private void stopSpinner() {
        if (spinnerTimer != null) {
            spinnerTimer.stop();
        }
    }

    /**
     * Stops the spinner animation. Called from the owning panel's dispose
     * (JComponent has no instance-level dispose hook), so the animation Timer
     * cannot outlive the toolbar.
     */
    public void stopSpinnerAnimation() {
        stopSpinner();
        detachMaintenanceStateListeners(signum);
    }

    // ====================================================================
    // DB archival maintenance state (trim/prune) — push-based (v4 §9.6)
    // ====================================================================

    /**
     * Resolves the BlockchainProcessor from the given facade, or null when the
     * facade is not present or not ready (node not started yet).
     */
    private BlockchainProcessor resolveProcessor(Signum s) {
        if (s == null) {
            return null;
        }
        try {
            return s.getBlockchainProcessor();
        } catch (Exception e) {
            LOGGER.debug("Signum facade not ready; maintenance state listeners not attached", e);
            return null;
        }
    }

    private void attachMaintenanceStateListeners(Signum s) {
        if (consistencyListenerAttached) {
            return;
        }
        BlockchainProcessor bp = resolveProcessor(s);
        if (bp == null) {
            return;
        }
        bp.addTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_START);
        bp.addTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_END);
        bp.addPruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_START);
        bp.addPruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_END);
        bp.addListener(block -> updateDbCheckIconColor(), BlockchainProcessor.Event.DATABASE_CONSISTENCY_UPDATE);
        consistencyListenerAttached = true;
        LOGGER.debug("Maintenance state listeners attached for profile: {}", profile.getName());
    }

    private void detachMaintenanceStateListeners(Signum s) {
        BlockchainProcessor bp = resolveProcessor(s);
        if (bp != null) {
            bp.removeTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_START);
            bp.removeTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_END);
            bp.removePruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_START);
            bp.removePruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_END);
        }
        consistencyListenerAttached = false;
    }

    /**
     * Pushed by trim/prune phase events (TRIM_START/END, PRUNE_START/END) on the
     * maintenance thread; marshals to the EDT and (re)renders the label from the
     * processor's current {@code ArchivalMaintenanceState}. Event-driven, no polling.
     */
    private void refreshMaintenanceState() {
        SwingUtilities.invokeLater(this::updateMaintenanceStateLabel);
    }

    /**
     * Registers the SSOT tab-icon refresher (called by the owning {@link NodeProfilePanel}).
     *
     * @param refresher callback invoked (EDT-safe) whenever a maintenance phase changes
     */
    public void setMaintenanceRefresher(Runnable refresher) {
        this.maintenanceRefresher = refresher;
    }

    private void updateMaintenanceStateLabel() {
        BlockchainProcessor.ArchivalMaintenanceState state = null;
        BlockchainProcessor bp = resolveProcessor(signum);
        if (bp != null) {
            state = bp.getArchivalMaintenanceState();
        }
        boolean active = state != null && state != BlockchainProcessor.ArchivalMaintenanceState.IDLE;
        if (active) {
            maintenanceLabel.setText(state == BlockchainProcessor.ArchivalMaintenanceState.TRIMMING
                    ? "DB maintenance: trimming..." : "DB maintenance: pruning...");
            dbCheckButton.setEnabled(false);
            dbCheckButton.setToolTipText(state == BlockchainProcessor.ArchivalMaintenanceState.TRIMMING
                    ? "Unavailable during trim" : "Unavailable during prune");
        } else {
            maintenanceLabel.setText(" ");
            if (signum != null && signum.getState() == Signum.State.RUNNING) {
                dbCheckButton.setEnabled(true);
                dbCheckButton.setToolTipText("Run database consistency check");
            }
        }
        maintenanceLabel.setVisible(active);
        JPanel row = (JPanel) maintenanceLabel.getParent();
        row.revalidate();
        row.repaint();

        // A maintenance phase is state-relevant for the tab icon → re-render from the SSOT.
        Runnable refresher = this.maintenanceRefresher;
        if (refresher != null) {
            SwingUtilities.invokeLater(refresher);
        }
    }

    private void updateDbCheckIconColor() {
        SwingUtilities.invokeLater(() -> {
            BlockchainProcessor bp = resolveProcessor(signum);
            if (bp == null || dbCheckButton == null) return;
            BlockchainProcessor.ConsistencyState state = bp.getConsistencyState();
            Color color;
            if (state == BlockchainProcessor.ConsistencyState.CONSISTENT) {
                color = GuiColors.getStatusConsistent();
            } else if (state == BlockchainProcessor.ConsistencyState.INCONSISTENT) {
                color = GuiColors.getContrastRed();
            } else {
                // UNDEFINED or null → default button-icon colour
                color = GuiColors.getButtonIcon();
            }
            setIconButton(dbCheckButton, FontAwesome.DATABASE, color, GuiConstants.getToolBarIconSize());
        });
    }

    /**
     * Updates the sync button icon after a sync toggle.
     */
    public void updateSyncIcon(boolean syncPaused) {
        this.isSyncStopped = syncPaused;
        float iconSize = GuiConstants.getToolBarIconSize();
        setIconButton(syncButton,
                syncPaused ? FontAwesome.PLAY : FontAwesome.PAUSE, GuiColors.getButtonIcon(), iconSize);
    }

    // ====================================================================
    // Callback setters
    // ====================================================================

    public void setOnStartStop(Runnable action) { this.onStartStop = action; }
    public void setOnRestart(Runnable action) { this.onRestart = action; }
    public void setOnNodeStarted(NodeStartedListener listener) { this.onNodeStarted = listener; }
    public void setOnSyncToggle(Runnable action) { this.onSyncToggle = action; }
    public void setOpenPhoenix(Runnable action) { this.onOpenPhoenix = action; }
    public void setOpenClassic(Runnable action) { this.onOpenClassic = action; }
    public void setOpenApi(Runnable action) { this.onOpenApi = action; }
    public void setOnEditConf(Runnable action) { this.onEditConf = action; }
    public void setOnPopOff10(Runnable action) { this.onPopOff10 = action; }
    public void setOnPopOff100(Runnable action) { this.onPopOff100 = action; }
    public void setOnDbCheck(Runnable action) { this.onDbCheck = action; }
    public void setOnMenuToggle(Runnable action) { this.onMenuToggle = action; }

    // ====================================================================
    // Button references for external enable/disable control
    // ====================================================================

    public JButton getStartStopButton() { return startStopButton; }
    public JButton getRestartButton() { return restartButton; }
    public JButton getSyncButton() { return syncButton; }
    public JButton getPopOff10Button() { return popOff10Button; }
    public JButton getPopOff100Button() { return popOff100Button; }
    public JButton getDbCheckButton() { return dbCheckButton; }
    public JButton getMenuButton() { return menuButton; }

    private void updateStyles() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            GuiFontManager.applyFontToTree(this, AppearanceModule.getActiveConsoleFont());

            float iconSize = GuiConstants.getToolBarIconSize();
            Color iconColor = GuiColors.getButtonIcon();

            // Update all icon-only buttons (via setIconButton so the hover scale-up and
            // the fixed bounding box are preserved after an appearance/font change).
            setIconButton(restartButton, FontAwesome.REFRESH, iconColor, iconSize);
            setIconButton(openPhoenixButton, FontAwesome.FIRE, iconColor, iconSize);
            setIconButton(openClassicButton, FontAwesome.WINDOW_RESTORE, iconColor, iconSize);
            setIconButton(openApiButton, FontAwesome.BOOK, iconColor, iconSize);
            setIconButton(editConfButton, FontAwesome.PENCIL, iconColor, iconSize);
            setIconButton(popOff10Button, FontAwesome.STEP_BACKWARD, iconColor, iconSize);
            setIconButton(popOff100Button, FontAwesome.BACKWARD, iconColor, iconSize);
            setIconButton(dbCheckButton, FontAwesome.DATABASE, iconColor, iconSize);

            // Update sync button (respect current pause state)
            setIconButton(syncButton,
                    isSyncStopped ? FontAwesome.PLAY : FontAwesome.PAUSE, iconColor, iconSize);

            // Update hamburger menu button icon
            setIconButton(menuButton, FontAwesome.BARS, iconColor, iconSize);

            // Restore DB check icon color based on consistency state.
            // Without this, appearance changes would overwrite the green/red icon
            // with the default button-icon color, losing the consistency indicator.
            updateDbCheckIconColor();

            for (Component comp : getComponents()) {
                updateFontsRecursively(comp);
            }
        });
    }

    private void updateFontsRecursively(Component comp) {
        if (comp instanceof JButton btn) {
            GuiFontManager.applyDefaultFont(btn);
        }
        if (comp instanceof JPanel panel) {
            for (Component child : panel.getComponents()) {
                updateFontsRecursively(child);
            }
        }
    }

    /**
     * Listener for the toolbar's Start action (v4 single lifecycle path).
     * Invoked after {@code NodeModule.startNode(profile)} succeeds, receiving
     * the started (or reused) {@link Signum} instance.
     */
    public interface NodeStartedListener {
        /**
         * @param signum the Signum instance NodeModule started for this profile
         *               (may be null if startup failed — implementations must be null-safe)
         */
        void onNodeStarted(Signum signum);
    }
}