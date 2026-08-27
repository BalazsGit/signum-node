package application.module.node.gui;

import application.module.appearance.AppearanceModule;
import application.module.node.BlockchainProcessor;
import application.module.node.Signum;
import application.module.node.profile.NodeProfile;
import application.module.node.util.Listener;
import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawings;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiIcons;
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
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
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
        startStopButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY, iconSize, GuiColors.getPeerActive()));
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
        openPhoenixButton = createIconButton(FontAwesome.FIRE, "Open Phoenix Wallet");
        openClassicButton = createIconButton(FontAwesome.WINDOW_RESTORE, "Open Classic Wallet");
        openApiButton = createIconButton(FontAwesome.BOOK, "Open API Documentation");
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
        menuButton.setIcon(IconFontSwing.buildIcon(FontAwesome.BARS, iconSize, iconColor));
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
        button.setIcon(IconFontSwing.buildIcon(iconCode, iconSize, GuiColors.getButtonIcon()));
        button.setToolTipText(tooltip);
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        return button;
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
                Signum started = NodeModule.getInstance().startNode(profile.getName());
                // v4: hand the instance back to the owning panel (adopt + console attach).
                if (onNodeStarted != null) {
                    onNodeStarted.onNodeStarted(started);
                }
            } catch (Exception e) {
                LOGGER.error("Start failed for profile: {}", profile.getName(), e);
            }
            LOGGER.info("Start requested for profile: {}", profile.getName());
        }
    }

    /**
     * Updates the Start/Stop toggle button appearance and enabled states based on lifecycle state.
     * Call this method from NodeProfilePanel.onNodeStateChanged().
     *
     * @param state The current lifecycle state of the node
     */
    public void updateButtonStates(Signum.State state) {
        boolean isRunning = (state == Signum.State.RUNNING || false);
        boolean isTransitioning = (state == Signum.State.STARTING
                || state == Signum.State.STOPPING
                );

        float iconSize = GuiConstants.getToolBarIconSize();

        if (isRunning) {
            // Show POWER_OFF icon - node is running, click to stop
            stopSpinner();
            startStopButton.setIcon(IconFontSwing.buildIcon(FontAwesome.POWER_OFF, iconSize, GuiColors.getContrastRed()));
            startStopButton.setToolTipText("Stop the node (shutdown)");
            startStopButton.setEnabled(true);
            restartButton.setEnabled(true);
            syncButton.setEnabled(true);
            popOff10Button.setEnabled(true);
            popOff100Button.setEnabled(true);
            dbCheckButton.setEnabled(true);

            // Update sync icon based on pause state
            syncButton.setIcon(IconFontSwing.buildIcon(
                    isSyncStopped ? FontAwesome.PLAY : FontAwesome.PAUSE, iconSize, GuiColors.getButtonIcon()));
        } else if (state == Signum.State.ERROR) {
            // Show PLAY icon - can restart after error
            stopSpinner();
            startStopButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY, iconSize, GuiColors.getPeerActive()));
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
                default -> state.name().toLowerCase();
            };
            startStopButton.setToolTipText(tooltip);
            startStopButton.setEnabled(false);
            restartButton.setEnabled(false);
            syncButton.setEnabled(false);
            popOff10Button.setEnabled(false);
            popOff100Button.setEnabled(false);
            dbCheckButton.setEnabled(false);
        } else {
            // STOPPED / READY / IDLE / CREATED -> show PLAY icon.
            // CREATED is a valid startable state: NodeModule.startNode() creates the
            // Signum and Signum.init() requires exactly the CREATED state, and
            // handleStartStopToggle() explicitly starts from any non-RUNNING state.
            stopSpinner();
            startStopButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY, iconSize, GuiColors.getPeerActive()));
            startStopButton.setToolTipText("Start the node");
            startStopButton.setEnabled(true);
            restartButton.setEnabled(false);
            syncButton.setEnabled(false);
            popOff10Button.setEnabled(false);
            popOff100Button.setEnabled(false);
            dbCheckButton.setEnabled(false);
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
            spinnerIcon = new SpinnerIcon(size, new Color(255, 193, 7));
        }
        if (spinnerTimer == null) {
            spinnerTimer = new Timer(50, e -> {
                spinnerIcon.advance(30f);
                startStopButton.repaint();
            });
        }
        spinnerTimer.start();
        startStopButton.setIcon(spinnerIcon);
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
        BlockchainProcessor bp = resolveProcessor(s);
        if (bp == null) {
            return;
        }
        bp.addTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_START);
        bp.addTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_END);
        bp.addPruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_START);
        bp.addPruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_END);
        LOGGER.debug("Maintenance state listeners attached for profile: {}", profile.getName());
    }

    private void detachMaintenanceStateListeners(Signum s) {
        BlockchainProcessor bp = resolveProcessor(s);
        if (bp == null) {
            return;
        }
        bp.removeTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_START);
        bp.removeTrimListener(trimStateListener, BlockchainProcessor.Event.TRIM_END);
        bp.removePruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_START);
        bp.removePruneListener(pruneStateListener, BlockchainProcessor.Event.PRUNE_END);
    }

    /**
     * Pushed by trim/prune phase events (TRIM_START/END, PRUNE_START/END) on the
     * maintenance thread; marshals to the EDT and (re)renders the label from the
     * processor's current {@code ArchivalMaintenanceState}. Event-driven, no polling.
     */
    private void refreshMaintenanceState() {
        SwingUtilities.invokeLater(this::updateMaintenanceStateLabel);
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
        } else {
            maintenanceLabel.setText(" ");
        }
        JPanel row = (JPanel) maintenanceLabel.getParent();
        maintenanceLabel.setVisible(active);
        row.revalidate();
        row.repaint();
    }

    /**
     * Updates the sync button icon after a sync toggle.
     */
    public void updateSyncIcon(boolean syncPaused) {
        this.isSyncStopped = syncPaused;
        float iconSize = GuiConstants.getToolBarIconSize();
        syncButton.setIcon(IconFontSwing.buildIcon(
                syncPaused ? FontAwesome.PLAY : FontAwesome.PAUSE, iconSize, GuiColors.getButtonIcon()));
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

            // Update all icon-only buttons
            restartButton.setIcon(IconFontSwing.buildIcon(FontAwesome.REFRESH, iconSize, iconColor));
            openPhoenixButton.setIcon(IconFontSwing.buildIcon(FontAwesome.FIRE, iconSize, iconColor));
            openClassicButton.setIcon(IconFontSwing.buildIcon(FontAwesome.WINDOW_RESTORE, iconSize, iconColor));
            openApiButton.setIcon(IconFontSwing.buildIcon(FontAwesome.BOOK, iconSize, iconColor));
            editConfButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PENCIL, iconSize, iconColor));
            popOff10Button.setIcon(IconFontSwing.buildIcon(FontAwesome.STEP_BACKWARD, iconSize, iconColor));
            popOff100Button.setIcon(IconFontSwing.buildIcon(FontAwesome.BACKWARD, iconSize, iconColor));
            dbCheckButton.setIcon(IconFontSwing.buildIcon(FontAwesome.DATABASE, iconSize, iconColor));

            // Update sync button (respect current pause state)
            syncButton.setIcon(IconFontSwing.buildIcon(
                    isSyncStopped ? FontAwesome.PLAY : FontAwesome.PAUSE, iconSize, iconColor));

            // Update hamburger menu button icon
            menuButton.setIcon(IconFontSwing.buildIcon(FontAwesome.BARS, iconSize, iconColor));

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