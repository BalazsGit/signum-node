package application.module.node.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.SystemInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.io.InputStream;
import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.AWTEventListener;
import java.awt.AWTEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.beans.PropertyChangeListener;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import application.module.node.gui.animations.RotatingSvgIcon;
import application.module.node.gui.configuration.ConfigurationPanel;
import application.gui.glassPanel.GlassPanelManager;
import application.module.node.gui.dialog.PeersDialog;
import application.module.node.gui.metrics.MetricsPanel;
import application.gui.glassPanel.GlassPanel;
import application.module.appearance.AppearanceModule;
import application.module.appearance.gui.AppearancePanel;
import javax.swing.JDialog;
import java.awt.geom.Rectangle2D;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.LookAndFeel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.JPopupMenu;
import javax.swing.plaf.FontUIResource;
import java.util.Set;
import java.util.HashSet;
import javax.swing.text.DefaultCaret;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;

import application.module.node.Signum;
import application.module.node.BlockchainProcessor;
import application.module.node.instance.NodeCoreContext;
import application.module.node.Constants;
import application.module.node.Block;
import application.module.node.peer.Peer;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.util.DurationFormatter;
import application.module.node.util.Listener;
import application.utils.gui.CustomDrawingComponent;
import application.utils.gui.CustomDrawingIcon;
import application.utils.gui.CustomDrawings;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.GuiFontManager;
import application.utils.gui.GuiUtils;
import application.utils.gui.HelpButton;
import application.module.node.util.Convert;
import application.module.node.lifecycle.NodeLifecycleState;
import application.module.node.profile.NodeProfile;
import application.utils.logging.ProfileLogger;
import application.utils.gui.console.ConsoleInputPosition;
import application.utils.gui.console.ConsolePanelConfiguration;
import application.utils.gui.console.UnifiedConsolePanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class NodeConsolePanel extends JPanel {
    private static final String FAILED_TO_START_MESSAGE = "Signum caught exception while starting";
    private static NodeConsolePanel instance;
    /** Parent frame reference (used for dialogs and window ancestor lookup) */
    private final JFrame parentFrame;
    /** Current node profile name for hierarchical GUI settings storage */
    private final String profileName;

    /** Unified Console Panel - centralized composition of console output + command input */
    private UnifiedConsolePanel unifiedConsole;

    /** @deprecated Use unifiedConsole.getSubscriber() instead */
    @Deprecated
    private ProfileConsoleSubscriber consoleSubscriber;

    /** Reference to the ProfileLogger for cleanup on panel disposal */
    private ProfileLogger profileLogger;

    private static final String UNEXPECTED_EXIT_MESSAGE = "Signum Quit unexpectedly! Exit code ";

    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss yyyy-MM-dd");

    private static final int OUTPUT_MAX_LINES = 500;

    private static final int ANIMATION_DURATION_MS = 250;

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeConsolePanel.class);

    private static String[] args;

    /**
     * Per-instance NodeCoreContext reference injected by NodeProfilePanel.
     * Provides access to BlockchainProcessor, PropertyService, DbContext, etc.
     * for this specific node profile, replacing static Signum.getXxx() calls.
     * May be null in legacy single-instance mode or before the node starts.
     */
    private NodeCoreContext nodeContext;

    /**
     * Injects the per-instance NodeCoreContext into this console panel.
     * Called by NodeProfilePanel after construction so that all node component
     * access goes through the instance context rather than static Signum calls.
     *
     * @param context the node core context for this profile (may be null)
     */
    public void setNodeContext(NodeCoreContext context) {
        this.nodeContext = context;
    }

    /**
     * Returns the injected NodeCoreContext, or falls back to the legacy
     * static bridge via NodeCoreContextManager.getActive() if not set.
     */
    NodeCoreContext getNodeContext() {
        if (nodeContext != null) {
            return nodeContext;
        }
        // Fallback: try to get from manager for backwards compatibility
        return application.module.node.instance.NodeCoreContextManager.getInstance().getActive();
    }

    private static void applyFontRecursively(Component comp, Font font) {
        GuiFontManager.applyFontToTree(comp, font);
    }

    /**
     * Log the layout/component tree state for diagnostics.
     */
    private void logLayoutDiagnostics(String tag) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[LayoutDiag] ").append(tag).append(" - ");
            JFrame f = (JFrame) SwingUtilities.getWindowAncestor(this);
            if (f != null) {
                sb.append("Frame=size=").append(f.getSize()).append(", rootPaneSize=")
                        .append(f.getRootPane() != null ? f.getRootPane().getSize() : "null").append("; ");
            }
            sb.append("commandPanelWrapper: isShowing=").append(commandPanelWrapper.isShowing())
                    .append(", isVisible=").append(commandPanelWrapper.isVisible())
                    .append(", height=").append(commandPanelWrapper.getHeight())
                    .append(", pref=").append(commandPanelWrapper.getPreferredSize())
                    .append(", max=").append(commandPanelWrapper.getMaximumSize()).append("; ");

            Component p = commandPanelWrapper.getParent();
            int depth = 0;
            while (p != null && depth < 10) {
                sb.append("[parent-").append(depth).append(":")
                        .append(p.getClass().getSimpleName()).append(" size=").append(p.getSize())
                        .append(", pref=")
                        .append(p instanceof JComponent ? ((JComponent) p).getPreferredSize() : "n/a")
                        .append(", isShowing=").append(p.isShowing())
                        .append(", isVisible=").append(p.isVisible());
                if (p instanceof JComponent) {
                    sb.append(", isValidateRoot=").append(((JComponent) p).isValidateRoot());
                }
                // If this is the immediate parent (likely bottomPanel), dump its children and layout info
                if (depth == 0 && p instanceof Container) {
                    Container c = (Container) p;
                    LayoutManager lm = c.getLayout();
                    sb.append(", layout=").append(lm != null ? lm.getClass().getSimpleName() : "null");
                    Component[] kids = c.getComponents();
                    sb.append(", childrenCount=").append(kids.length).append("[");
                    for (int i = 0; i < kids.length; i++) {
                        Component k = kids[i];
                        sb.append("#").append(i).append(":" + k.getClass().getSimpleName())
                                .append(" bounds=").append(k.getBounds())
                                .append(" pref=")
                                .append(k instanceof JComponent ? ((JComponent) k).getPreferredSize() : "n/a")
                                .append(" visible=").append(k.isVisible());
                        if (i < kids.length - 1) sb.append(", ");
                    }
                    sb.append("]");
                    // If BorderLayout, attempt to resolve which component is in NORTH/CENTER/SOUTH
                    if (lm instanceof BorderLayout) {
                        BorderLayout bl = (BorderLayout) lm;
                        Component north = bl.getLayoutComponent(c, BorderLayout.NORTH);
                        Component center = bl.getLayoutComponent(c, BorderLayout.CENTER);
                        Component south = bl.getLayoutComponent(c, BorderLayout.SOUTH);
                        sb.append(" northComp=")
                                .append(north != null ? north.getClass().getSimpleName() + north.getBounds() : "null");
                        sb.append(" centerComp=")
                                .append(center != null ? center.getClass().getSimpleName() + center.getBounds() : "null");
                        sb.append(" southComp=")
                                .append(south != null ? south.getClass().getSimpleName() + south.getBounds() : "null");
                    }
                }
                sb.append("] ");
                p = p.getParent();
                depth++;
            }
            LOGGER.info(sb.toString());
        } catch (Exception e) {
            LOGGER.warn("Error while logging layout diagnostics", e);
        }
    }

    /**
     * A general method to update the entire GUI after a Look and Feel or theme
     * change.
     * This method handles:
     * 1. Reloading the color palette based on the current theme and user overrides.
     * 2. Calling FlatLaf.updateUI() to update all standard Swing components.
     * 3. Triggering custom update logic for components with derived properties
     * (e.g., font-sized icons).
     */
    public static void updateAllUIs() {
        AppearanceModule.updateAllUIs();
    }

    private void updateConsoleStyle() {
        if (textScrollPane != null) {
            JTextPane tp = (JTextPane) textScrollPane.getViewport().getView();
            Font consoleFont = AppearanceModule.getActiveConsoleFont();
            if (tp != null && consoleFont != null) {
                tp.setFont(consoleFont);
                // Update existing text in the StyledDocument
                StyledDocument doc = tp.getStyledDocument();
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setFontFamily(attrs, consoleFont.getFamily());
                StyleConstants.setFontSize(attrs, consoleFont.getSize());
                doc.setCharacterAttributes(0, doc.getLength(), attrs, false);
            }
        }
    }

    /**
     * Logs the current state of key UI fonts for debugging purposes.
     */
    private static void logCurrentFonts(String title) {
        String[] keys = { "Label.font", "Button.font", "TextField.font", "TextPane.font",
                "Table.font", "TableHeader.font", "TitledBorder.font", "defaultFont" };
        StringBuilder sb = new StringBuilder(title).append(":");
        for (String key : keys) {
            Font f = UIManager.getFont(key);
            if (f != null) {
                sb.append("\n  ").append(key).append(": ").append(f.getFamily()).append(" ").append(f.getSize())
                        .append(" (").append(f.getClass().getSimpleName()).append(")");
            } else {
                sb.append("\n  ").append(key).append(": null");
            }
        }
        LOGGER.info(sb.toString());
    }

    private void updateCustomComponents() {
        updateToolBarIcons();
        updatePopOffToggleIcon();
        updateDbCheckButtonIcon();
        updateTimeLabelIcons();
        updateVolumeLabelIcons();

        LookAndFeel laf = UIManager.getLookAndFeel();
        boolean isFlatLaf = laf instanceof FlatLaf;

        if (menuPanel != null) {
            SwingUtilities.updateComponentTreeUI(menuPanel);
            menuPanel.setBackground(UIManager.getColor("PopupMenu.background"));
            menuPanel.setBorder(UIManager.getBorder("PopupMenu.border"));
        }
        // popup wrapper is managed by MenuPopupController
        if (commandPanel != null) {
            SwingUtilities.updateComponentTreeUI(commandPanel);
        }
    }

    /**
     * Updates the command position checkbox text and icon to reflect current position.
     * BOTTOM → "Command Panel Down"
     * TOP    → "Command Panel Up"
     */
    private void updateCommandPositionText() {
        if (commandPositionBottomItem == null) {
            return;
        }
        // Determine current effective position
        boolean isBottom;
        if (unifiedConsole != null) {
            isBottom = unifiedConsole.getEffectiveCommandPosition() == ConsoleInputPosition.BOTTOM;
        } else {
            isBottom = commandPanelAtBottom;
        }
        if (isBottom) {
            commandPositionBottomItem.setIcon(new CustomDrawingIcon(CustomDrawings.Chevron.DOWN,
                    UIManager.getColor("Label.foreground")));
            commandPositionBottomItem.setText(" Command Panel Down");
            commandPositionBottomItem.setToolTipText("Move command input panel to bottom of console");
        } else {
            commandPositionBottomItem.setIcon(new CustomDrawingIcon(CustomDrawings.Chevron.UP,
                    UIManager.getColor("Label.foreground")));
            commandPositionBottomItem.setText(" Command Panel Up");
            commandPositionBottomItem.setToolTipText("Move command input panel to top of console");
        }
    }

    private void updateVolumeLabelIcons() {
        if (uploadVolumeLabel != null) {
            uploadVolumeLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.ARROW_UP, GuiConstants.getHelpIconSize(),
                    GuiColors.getButtonIcon()));
        }
        if (downloadVolumeLabel != null) {
            downloadVolumeLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.ARROW_DOWN, GuiConstants.getHelpIconSize(),
                    GuiColors.getButtonIcon()));
        }
    }

    public static void setupLegacyNimbus() {
        UIManager.put("control", new Color(128, 128, 128));
        UIManager.put("info", new Color(128, 128, 128));
        UIManager.put("nimbusBase", new Color(18, 30, 49));
        UIManager.put("nimbusAlertYellow", new Color(248, 187, 0));
        UIManager.put("nimbusDisabledText", new Color(90, 90, 90));
        UIManager.put("nimbusFocus", new Color(115, 164, 209));
        UIManager.put("nimbusGreen", new Color(176, 179, 50));
        UIManager.put("nimbusInfoBlue", new Color(66, 139, 221));
        UIManager.put("nimbusLightBackground", new Color(18, 30, 49));
        UIManager.put("nimbusOrange", new Color(191, 98, 4));
        UIManager.put("nimbusRed", new Color(169, 46, 34));
        UIManager.put("nimbusSelectedText", new Color(255, 255, 255));
        UIManager.put("nimbusSelectionBackground", new Color(104, 93, 156));
        UIManager.put("text", new Color(230, 230, 230));
    }

    private String iconLocation;
    private TrayIcon trayIcon = null;
    private JPanel toolBar = null;
    private JLabel latestBlockHeightLabel = null;
    private JLabel latestBlockTimestampLabel = null;
    private JLabel elapsedTimeLabel = null;
    private JSeparator elapsedTimeSeparator = null;
    private Timer elapsedTimeTimer = null;
    private long elapsedTimeCounter = 0;
    private JPanel infoPanel;
    private JProgressBar syncProgressBar = null;
    private JScrollPane textScrollPane = null;
    private String programName = null;
    private String version = null;
    private final String confFolder;
    private Color iconColor;
    // private ConfigurationPanel configurationPanel;

    private JLabel connectedPeersLabel;
    private JLabel peersCountLabel;
    private JLabel blacklistedPeersLabel;
    private JLabel uploadVolumeLabel;
    private JLabel downloadVolumeLabel;
    private DynamicArrowPanel trimHeightLabel;
    private JSeparator trimSeparator;
    private DynamicArrowPanel pruneHeightLabel;
    private JSeparator pruneSeparator;
    private JLabel popOffBlockCountLabel;
    private DynamicArrowPanel popOffBlockHeightLabel;
    private JSeparator popOffSeparator1;
    private JSeparator popOffSeparator2;
    private boolean showPopOff = false;
    private JPanel popOffButtonsPanel;
    private Timer popOffAnimator;
    private int popOffPanelWidth = -1;

    private JButton popOff10Button;
    private JButton popOff100Button;
    private JButton dbCheckButton;
    private JButton syncButton;
    private JButton shutdownButton;
    private JButton restartButton;
    private Color dbConsistencyColor;

    private boolean isSyncStopped = false;
    private boolean isShuttingDown = false;

    private boolean measurementActive = false;
    private boolean experimentalActive = false;
    private boolean trimEnabled = false;
    private boolean autoResolveEnabled = false;

    private JButton openPhoenixButton;
    private JButton openClassicButton;
    private JButton openApiButton;
    private JButton editConfButton;

    private MetricsPanel metricsPanel;
    private JPanel metricsPanelWrapper;
    private Timer metricsPanelAnimator;
    private static final int COMMAND_PANEL_ANIMATION_DURATION = 250;

    private CustomDrawingComponent popOffToggle;
    private JButton menuButton;
    private JButton globeButton;
    private JLabel measurementLabel;
    private JPanel commandPanel;
    private JTextField commandField;
    private JPanel contentPanel;   // Content BorderLayout inside CardLayout (holds toolbar, console, bottomPanel)
    private JPanel consoleWrapper;
    private JPanel bottomPanel;
    private JPanel topPanel;
    private JPanel mainCardPanel;
    private CardLayout cardLayout;
    private static final String VIEW_CONSOLE = "CONSOLE";
    private static final String VIEW_CONFIGURATION = "CONFIGURATION";
    private boolean showCommandInput = false;    // Default: hidden
    private boolean showMetricsPanel = false;    // Default: hidden
    private boolean metricsExpanded = false;     // Default: collapsed (chevron DOWN)
    private boolean commandPanelAtBottom = true; // Default: at bottom position
    private JCheckBox showCommandItem;
    private JCheckBox showMetricsItem;
    private JCheckBox commandPositionBottomItem;
    private JCheckBox autostartItem;
    private JCheckBox skipDbCheckItem;
    private JLabel experimentalLabel;
    private JPanel commandPanelWrapper;
    private Timer commandPanelAnimator;
    private JPanel menuPanel;
    /** Controller that handles popup display, animation and auto-close behavior */
    private application.utils.gui.MenuPopupController menuPopupController;
    /** The button that triggered the currently open menu (may be toolbar's button)
     *  Used to exclude the trigger from click-outside checks. */
    private AbstractButton menuTriggerButton;
    /** Callback to switch parent tabbed pane to Console tab (set by NodeProfilePanel) */
    private Runnable switchToConsoleAction;

    /**
     * Sets the callback to switch the containing tabbed pane to the Console tab.
     * Called by NodeProfilePanel after construction.
     * @param action Runnable that switches to the Console tab
     */
    public void setSwitchToConsoleAction(Runnable action) {
        this.switchToConsoleAction = action;
    }

    private JLabel trimLabel;
    private JLabel autoResolveLabel;
    private JSeparator measurementSeparator;
    private JSeparator experimentalSeparator;
    private JSeparator trimIconSeparator;
    private JSeparator autoResolveSeparator;

    private final AtomicBoolean isDbCheckRunning = new AtomicBoolean(false);

    /**
     * Label to display the total elapsed time since the GUI was started.
     */
    private JLabel totalTimeLabel;
    /**
     * Label to display the accumulated time spent syncing the blockchain.
     */
    private JLabel syncInProgressTimeLabel;

    private JSeparator timeSeparator;

    /**
     * Stores the total elapsed time in milliseconds, updated by the GUI timer.
     */
    private long guiAccumulatedSyncTimeMs = 0;
    /**
     * Stores the accumulated time in milliseconds spent actively syncing (when more
     * than 10 blocks behind).
     */
    private long guiAccumulatedSyncInProgressTimeMs = 0;
    /**
     * Flag for the hysteresis logic, indicating if the node is currently considered
     * to be syncing.
     */
    private boolean isSyncing = false; // For hysteresis
    /**
     * Label for the separator between time labels.
     */
    private JSeparator innerTimeSeparator;
    /**
     * Timer to update the GUI time labels every second.
     */
    private Timer guiTimer;
    private final AtomicBoolean guiTimerStarted = new AtomicBoolean(false);

    private JDialog waitDialog;

    private JLabel createLabel(String text, Color color, String tooltip) {
        return createLabel(text, color, tooltip, null);
    }

    private JLabel createLabel(String text, Color color, String tooltip, String title) {
        JLabel label = new JLabel(text) { // Anonymous inner class to handle UI updates
            @Override
            public void updateUI() {
                super.updateUI();
                GuiFontManager.applyDefaultFont(this);
                // Re-apply custom color after Look and Feel change
                if (color != null) {
                    setForeground(color);
                }
            }
        };
        if (tooltip != null) {
            String shortTooltip = tooltip.split("\n")[0];
            label.setToolTipText(shortTooltip);
            addInfoTooltip(label, tooltip, title);
        }
        return label;
    }

    private void addInfoTooltip(JComponent component, String text, String titleOverride) {
        if (text != null) {
            String shortTooltip = text.split("\n")[0];
            component.setToolTipText(shortTooltip);
        }
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title;
                    String labelText = "";
                    if (component instanceof JLabel) {
                        labelText = ((JLabel) component).getText();
                    } else if (component instanceof DynamicArrowPanel) {
                        labelText = ((DynamicArrowPanel) component).leftTextLabel.getText();
                    }
                    if (titleOverride != null) {
                        title = (labelText != null && !labelText.isEmpty()) ? labelText + " " + titleOverride
                                : titleOverride;
                    } else {
                        title = labelText != null ? labelText : "";
                        // Remove trailing colon for a cleaner title
                        if (title != null && title.endsWith(":")) {
                            title = title.substring(0, title.length() - 1);
                        }
                    }
                    // Wrap the text in HTML to control the width of the dialog.
                    String htmlText = "<html><body><p style='width: 300px;'>" + text.replace("\n", "<br>")
                            + "</p></body></html>";
                    JOptionPane.showMessageDialog(NodeConsolePanel.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
    }

    /**
     * Toggles the hamburger menu popup open/closed.
     * Made public so NodeToolbar (via NodeProfilePanel) can trigger it.
     * 
     * @param triggerButton The button that triggered this toggle (used for popup positioning).
     *                      If null, falls back to internal menuButton.
     */
    public void toggleMenu(JButton triggerButton) {
        // Determine trigger and owning window
        JButton sourceButton = (triggerButton != null) ? triggerButton : menuButton;
        Window ownerWindow = SwingUtilities.getWindowAncestor(sourceButton);
        if (ownerWindow == null) {
            LOGGER.warn("Could not determine owner window for menu popup");
            return;
        }

        if (menuPopupController == null || menuPopupController.getOwner() != ownerWindow) {
            menuPopupController = new application.utils.gui.MenuPopupController(ownerWindow);
        }

        menuPopupController.toggle(menuPanel, sourceButton);
    }

    /**
     * Recursively searches up the component hierarchy to find an enclosing JScrollPane.
     * Robust against intermediate wrapper panels (e.g., ResponsiveToolbarScrollPane contentWrapper).
     */
    private static JScrollPane findScrollPaneAncestor(Component start) {
        Component c = start.getParent();
        while (c != null) {
            if (c instanceof JScrollPane) {
                return (JScrollPane) c;
            }
            c = c.getParent();
        }
        return null;
    }

    private void togglePopOffButtons() {
        if (popOffAnimator != null && popOffAnimator.isRunning()) {
            return; // Don't start a new animation if one is running
        }

        showPopOff = !showPopOff;
        updatePopOffToggleIcon();

        // Calculate target dimensions
        Dimension naturalSize = popOffButtonsPanel.getLayout().preferredLayoutSize(popOffButtonsPanel);
        final int targetWidth = naturalSize.width;
        // Use a stable height from existing toolbar buttons to prevent vertical jumping
        final int targetHeight = editConfButton != null ? editConfButton.getPreferredSize().height
                : Math.max(naturalSize.height, 25);
        Container parent = popOffButtonsPanel.getParent();

        // Robust scroll pane search: traverse up until we find a JScrollPane ancestor.
        // This works regardless of intermediate wrapper panels (e.g., contentWrapper in ResponsiveToolbarScrollPane).
        final JScrollPane sp = findScrollPaneAncestor(popOffButtonsPanel);

        if (showPopOff) {
            // Opening
            popOffButtonsPanel.setVisible(true);
            popOffButtonsPanel.setPreferredSize(new Dimension(0, targetHeight));
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }

            final int finalTargetWidth = targetWidth;
            final JScrollPane finalSp = sp;
            popOffAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
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
                            JScrollBar hBar = finalSp.getHorizontalScrollBar();
                            if (hBar != null) {
                                hBar.setValue(hBar.getMaximum());
                            }
                        });
                    }

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        popOffButtonsPanel.setPreferredSize(null); // Reset to natural size
                        if (parent != null)
                            parent.revalidate();
                        if (finalSp != null) {
                            SwingUtilities.invokeLater(() -> {
                                JScrollBar hBar = finalSp.getHorizontalScrollBar();
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
            final JScrollPane finalSp = sp;

            popOffAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
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
                            JScrollBar hBar = finalSp.getHorizontalScrollBar();
                            if (hBar != null) {
                                hBar.setValue(hBar.getMaximum());
                            }
                        });
                    }

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        popOffButtonsPanel.setPreferredSize(new Dimension(0, targetHeight));
                        popOffButtonsPanel.setVisible(false);
                        if (parent != null)
                            parent.revalidate();
                        if (finalSp != null) {
                            SwingUtilities.invokeLater(() -> {
                                JScrollBar hBar = finalSp.getHorizontalScrollBar();
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

    /**
     * @deprecated Replaced by UnifiedConsolePanel.setShowCommandInput(boolean).
     * Retained for backward compatibility (null-safe when commandPanel is null).
     */
    @Deprecated
    private void toggleCommandPanel() {
        showCommandInput = !showCommandInput;
        showCommandItem.setSelected(showCommandInput);

        LOGGER.info("[CommandPanel] Toggling Command Input panel: {}", showCommandInput ? "OPEN" : "CLOSE");

        if (showCommandInput) {
            if (switchToConsoleAction != null) {
                LOGGER.info("[CommandPanel] Switching to Console tab before showing command panel");
                switchToConsoleAction.run();
            }
            showCommandPanelInline();
        } else {
            hideCommandPanelInline();
        }

        LOGGER.info("[ScrollDebug] toggleCommandPanel: about to invokeLater scroll to bottom");
        SwingUtilities.invokeLater(() -> {
            if (textScrollPane != null) {
                JScrollBar vertical = textScrollPane.getVerticalScrollBar();
                if (vertical != null) {
                    int maxVal = vertical.getMaximum();
                    int currentBefore = vertical.getValue();
                    LOGGER.info("[ScrollDebug] toggleCommandPanel: setting scrollbar from {} to {}", currentBefore, maxVal);
                    vertical.setValue(maxVal);
                } else {
                    LOGGER.info("[ScrollDebug] toggleCommandPanel: vertical scrollbar is null");
                }
            } else {
                LOGGER.info("[ScrollDebug] toggleCommandPanel: textScrollPane is null");
            }
        });
    }

    /**
     * @deprecated Replaced by UnifiedConsolePanel command input panel.
     * Null-safe: returns immediately when commandPanel/commandPanelWrapper are null.
     */
    @Deprecated
    private void showCommandPanelInline() {
        showCommandPanelInline(commandPanelAtBottom ? BottomPanelPosition.BOTTOM : BottomPanelPosition.TOP);
    }

    /**
     * @deprecated Replaced by UnifiedConsolePanel command input panel.
     * Null-safe: returns immediately when components are null.
     */
    @Deprecated
    private void showCommandPanelInline(BottomPanelPosition position) {
        if (commandPanel == null || commandPanelWrapper == null || contentPanel == null || bottomPanel == null) {
            LOGGER.warn("Command panel or wrapper is not initialized");
            return;
        }

        if (commandPanel.getParent() != commandPanelWrapper) {
            if (commandPanel.getParent() instanceof java.awt.Container) {
                ((java.awt.Container) commandPanel.getParent()).remove(commandPanel);
            }
            commandPanelWrapper.removeAll();
            commandPanelWrapper.add(commandPanel, BorderLayout.CENTER);
        }

        if (position == BottomPanelPosition.BOTTOM) {
            if (commandPanelWrapper.getParent() != bottomPanel) {
                if (commandPanelWrapper.getParent() instanceof java.awt.Container) {
                    ((java.awt.Container) commandPanelWrapper.getParent()).remove(commandPanelWrapper);
                }
                bottomPanel.add(commandPanelWrapper, BorderLayout.NORTH);
            }
        } else {
            if (commandPanelWrapper.getParent() != topPanel) {
                if (commandPanelWrapper.getParent() instanceof java.awt.Container) {
                    ((java.awt.Container) commandPanelWrapper.getParent()).remove(commandPanelWrapper);
                }
                // "h 0:" allows MigLayout to resize this component's row from height 0 upward,
                // enabling smooth expand/collapse animations (without it the row locks at natural size)
                topPanel.add(commandPanelWrapper, "growx, h 0:");
            }
        }

        commandPanel.setVisible(true);
        commandPanelWrapper.setVisible(true);
        if (position == BottomPanelPosition.BOTTOM) {
            bottomPanel.revalidate();
            bottomPanel.repaint();
        } else {
            topPanel.revalidate();
            topPanel.repaint();
        }
        contentPanel.revalidate();
        contentPanel.repaint();
        // Use invokeLater to ensure layout is fully computed before animation starts
        // This is especially important for MigLayout (topPanel) where the initial
        // preferredSize calculation may not be ready immediately after revalidate()
        SwingUtilities.invokeLater(() -> animateCommandPanelOpen(position));
    }

    /**
     * @deprecated Replaced by UnifiedConsolePanel command input panel.
     * Null-safe: returns immediately when commandPanelWrapper is null.
     */
    @Deprecated
    private void hideCommandPanelInline() {
        if (commandPanelWrapper == null) {
            return;
        }
        animateCommandPanelClose();
    }

    /**
     * @deprecated Replaced by UnifiedConsolePanel ConsoleInputPanel animation.
     * Null-safe: no-op when commandPanel is null.
     */
    @Deprecated
    private void animateCommandPanelOpen(BottomPanelPosition position) {
        if (commandPanelAnimator != null && commandPanelAnimator.isRunning()) {
            commandPanelAnimator.stop();
        }

        int targetHeight = commandPanel.getPreferredSize().height;
        commandPanelWrapper.setPreferredSize(new Dimension(commandPanelWrapper.getWidth(), 0));
        commandPanelWrapper.revalidate();

        commandPanelAnimator = new Timer(10, null);
        long start = System.currentTimeMillis();
        commandPanelAnimator.addActionListener(e -> {
            float progress = Math.min(1.0f, (System.currentTimeMillis() - start) / (float) COMMAND_PANEL_ANIMATION_DURATION);
            progress = 1 - (float) Math.pow(1 - progress, 3);
            int height = (int) (targetHeight * progress);
            commandPanelWrapper.setPreferredSize(new Dimension(commandPanelWrapper.getWidth(), height));
            // Update maximumSize as well so MigLayout knows the row can grow during animation
            commandPanelWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            // Also revalidate parent container for MigLayout compatibility
            // MigLayout does not automatically respond to preferredSize changes of children
            Component parent = commandPanelWrapper.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
            commandPanelWrapper.revalidate();
            commandPanelWrapper.repaint();
            if (progress >= 1f) {
                commandPanelAnimator.stop();
                commandPanelWrapper.setPreferredSize(new Dimension(commandPanelWrapper.getWidth(), targetHeight));
                commandPanelWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, targetHeight));
                if (parent != null) {
                    parent.revalidate();
                    parent.repaint();
                }
                commandPanelWrapper.revalidate();
                commandPanelWrapper.repaint();
            }
        });
        commandPanelAnimator.start();
    }

    /**
     * @deprecated Replaced by UnifiedConsolePanel ConsoleInputPanel animation.
     * Null-safe: no-op when commandPanel is null.
     */
    @Deprecated
    private void animateCommandPanelClose() {
        if (commandPanelAnimator != null && commandPanelAnimator.isRunning()) {
            commandPanelAnimator.stop();
        }

        int startHeight = commandPanelWrapper.getHeight();
        // Capture parent reference for MigLayout compatibility
        Component parent = commandPanelWrapper.getParent();
        commandPanelAnimator = new Timer(10, null);
        long start = System.currentTimeMillis();
        commandPanelAnimator.addActionListener(e -> {
            float progress = Math.min(1.0f, (System.currentTimeMillis() - start) / (float) COMMAND_PANEL_ANIMATION_DURATION);
            progress = 1 - (float) Math.pow(1 - progress, 3);
            int height = (int) (startHeight * (1 - progress));
            commandPanelWrapper.setPreferredSize(new Dimension(commandPanelWrapper.getWidth(), Math.max(0, height)));
            // Also revalidate parent container for MigLayout compatibility
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
            commandPanelWrapper.revalidate();
            commandPanelWrapper.repaint();
            if (progress >= 1f) {
                commandPanelAnimator.stop();
                commandPanelWrapper.removeAll();
                commandPanelWrapper.setPreferredSize(new Dimension(0, 0));
                commandPanelWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 0));
                commandPanelWrapper.setVisible(false);
                // Final revalidate of parent for MigLayout compatibility
                if (parent != null) {
                    parent.revalidate();
                    parent.repaint();
                }
                commandPanelWrapper.revalidate();
                commandPanelWrapper.repaint();
            }
        });
        commandPanelAnimator.start();
    }

    /**
     * @deprecated Replaced by UnifiedConsolePanel configuration.
     * Kept for backward compatibility with legacy menu checkbox.
     */
    @Deprecated
    private void setCommandPanelAtBottom(boolean bottom) {
        this.commandPanelAtBottom = bottom;
        if (commandPositionBottomItem != null) {
            commandPositionBottomItem.setSelected(bottom);
        }
        if (showCommandInput) {
            hideCommandPanelInline();
            showCommandPanelInline(bottom ? BottomPanelPosition.BOTTOM : BottomPanelPosition.TOP);
        }
    }

    private enum BottomPanelPosition {
        TOP,
        BOTTOM
    }

    public static NodeConsolePanel getInstance() {
        return instance;
    }

    /**
     * Returns the underlying UnifiedConsolePanel instance.
     * Used by NodeProfilePanel to control scroll visibility on tab switching.
     * @return the unified console panel, or null if not initialized
     */
    public UnifiedConsolePanel getUnifiedConsole() {
        return unifiedConsole;
    }

    /**
     * @deprecated Use unifiedConsole.getTextPane() directly.
     * Delegating getter for backward compatibility.
     */
    @Deprecated
    public JTextPane getConsoleTextPane() {
        if (unifiedConsole != null) {
            return unifiedConsole.getTextPane();
        }
        // Fallback to legacy textScrollPane if unifiedConsole not yet initialized
        if (textScrollPane != null) {
            return (JTextPane) textScrollPane.getViewport().getView();
        }
        return null;
    }

    public void showLookAndFeelSettings() {
        cardLayout.show(mainCardPanel, VIEW_CONFIGURATION);
    }

    public static void main(String[] args) {
        // We no longer manually add a JUL handler or redirect here.
        // Launcher.main has already set up the Proxy Streams.
        try {
            application.module.node.util.BriefLogFormatter.init(); // Apply consistent formatting
        } catch (Exception e) {
            // ignore
        }

        AppearanceModule.setupInitialLookAndFeel(args);
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame();
            NodePanel gui = new NodePanel(frame);
            frame.setContentPane(gui);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    /**
     * Constructs a NodeConsolePanel for a specific NodeProfile instance.
     * Used by NodeProfilePanel for multi-instance support.
     * 
     * @param parentFrame The parent JFrame for dialogs
     * @param profile     The NodeProfile containing configuration
     */
    public NodeConsolePanel(JFrame parentFrame, NodeProfile profile) {
        this.parentFrame = parentFrame;
        this.profileName = profile.getName();
        this.programName = "Signum Node [" + profile.getName() + "]";
        this.version = Signum.VERSION.toString();
        this.iconLocation = Props.ICON_LOCATION.getDefaultValue();
        this.confFolder = Signum.CONF_FOLDER;

        IconFontSwing.register(FontAwesome.getIconFont());

        // Initialize shared console UI
        initConsoleUI();
    }

    /**
     * Legacy constructor - stands alone (static single-instance mode).
     * Uses "default" as profile name for gui-settings.json hierarchy.
     */
    public NodeConsolePanel(JFrame parentFrame, String programName, String iconLocation, String version,
            String[] args) {
        this.parentFrame = parentFrame;
        this.profileName = "default"; // Legacy single-instance mode uses default key

        NodeConsolePanel.args = args;
        instance = this;
        this.programName = programName;
        this.version = version;
        this.iconLocation = iconLocation;

        IconFontSwing.register(FontAwesome.getIconFont());

        String localConfFolder = Signum.CONF_FOLDER;
        try {
            CommandLine cmd = new DefaultParser().parse(Signum.CLI_OPTIONS, args);
            if (cmd.hasOption(Signum.CONF_FOLDER_OPTION.getOpt())) {
                localConfFolder = cmd.getOptionValue(Signum.CONF_FOLDER_OPTION.getOpt());
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing command line arguments for config folder", e);
        }
        this.confFolder = localConfFolder;

        // Initialize shared console UI
        initConsoleUI();
    }

    /**
     * Shared UI initialization for both constructors.
     * Sets up console text pane, toolbar, buttons, hamburger menu, info panel, and starts the Signum node.
     * 
     * IMPORTANT: loadGuiSettings() is called FIRST so that field defaults (showMetricsPanel, etc.)
     * reflect persisted values BEFORE any UI elements are created. This prevents the hamburger
     * menu checkbox from showing a stale default while the actual panel remains hidden.
     */
    private void initConsoleUI() {
        // Load persisted GUI settings BEFORE creating any UI components so that
        // field defaults match what the user previously saved (profile-specific keys first).
        loadGuiSettings();

        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            InputStream fontStream = FontAwesome.class
                    .getResourceAsStream("/jiconfont/icons/font_awesome/fontawesome-webfont.ttf");
            if (fontStream != null) {
                ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, fontStream));
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register FontAwesome for HTML rendering", e);
        }

        // ── UnifiedConsolePanel creation (replaces direct JTextPane + ProfileConsoleSubscriber) ──
        unifiedConsole = new UnifiedConsolePanel(
            ConsolePanelConfiguration.profileConsole(profileName)
                .withShowFilterHeader(true)
                .withShowCommandInput(false)  // Command input is managed via hamburger menu toggle
                .withCommandPosition(ConsoleInputPosition.BOTTOM)
                .withAnimateCommandInput(true)
                .withCommandInputVisible(false)
                .withEnableCommandToggle(true)
                .withMaxLines(OUTPUT_MAX_LINES)
                .withCommandHandler(cmd -> {
                    new Thread(() -> Signum.processCommand(cmd)).start();
                })
                .withEnableSmartScroll(true),
            ProfileConsoleSubscriber.class
        );

        // Backward-compatible aliases for code that still references textScrollPane/textPane directly
        textScrollPane = unifiedConsole.getScrollPane();
        textScrollPane.setBorder(BorderFactory.createEmptyBorder());
        textScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        
        JTextPane textPane = unifiedConsole.getTextPane();
        Font consoleFont = AppearanceModule.getActiveConsoleFont();
        if (consoleFont == null) {
            consoleFont = new Font(Font.MONOSPACED, Font.PLAIN, GuiConstants.CONSOLE_FONT_SIZE_DEFAULT);
        }
        iconColor = textPane.getForeground();
        this.dbConsistencyColor = GuiColors.getButtonIcon();

        // Flush legacy static bootstrap logs directly to the console for backward compatibility
        flushLegacyBootstrapLogs(textPane);

        // Scroll to bottom after bootstrap logs are rendered so the console
        // shows the latest content when the user first opens the tab.
        GuiUtils.scrollToBottom(textScrollPane);
        textScrollPane.setBorder(BorderFactory.createEmptyBorder());
        textScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        /* ── LEGACY CODE COMMENTED OUT (preserved for reference) ──
        JTextPane legacyTextPane = new JTextPane();
        Font legacyConsoleFont = AppearanceModule.getActiveConsoleFont();
        if (legacyConsoleFont == null) {
            legacyConsoleFont = new Font(Font.MONOSPACED, Font.PLAIN, GuiConstants.CONSOLE_FONT_SIZE_DEFAULT);
        }
        legacyTextPane.setFont(legacyConsoleFont);
        iconColor = legacyTextPane.getForeground();
        this.dbConsistencyColor = GuiColors.getButtonIcon();
        DefaultCaret legacyCaret = (DefaultCaret) legacyTextPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        legacyTextPane.setEditable(false);

        // No global debug logger in production; Popup auto-close logic is instantiated
        // when a popup is opened by the owning component.

        textScrollPane = new JScrollPane(legacyTextPane);
        textScrollPane.setBorder(BorderFactory.createEmptyBorder());
        textScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Setup per-profile log routing with smart auto-scroll support
        initProfileLogging(legacyTextPane, textScrollPane);
        ── LEGACY CODE END ── */

        contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        cardLayout = new CardLayout();
        mainCardPanel = new JPanel(cardLayout);
        mainCardPanel.add(contentPanel, VIEW_CONSOLE);
        /*
         * this.configurationPanel = new ConfigurationPanel(this::restart,
         * this.confFolder,
         * () -> cardLayout.show(mainCardPanel, VIEW_CONSOLE));
         * mainCardPanel.add(configurationPanel, VIEW_CONFIGURATION);
         */
        // Regisztrálunk az AppearanceModule-nál az egyedi UI frissítésekre
        AppearanceModule.registerAppearanceListener(() -> { // Register for custom UI updates with the AppearanceModule
            updateCustomComponents();
            updateConsoleStyle();
        });

        setLayout(new BorderLayout());
        add(mainCardPanel, BorderLayout.CENTER);

        // Toolbar with horizontal-only overflow scrolling when window is narrow.
        // MigLayout column constraint [pref!] allows the left section to shrink below its preferred size.
        toolBar = new JPanel(new MigLayout("insets 0, gap 0, fillx, hidemode 3", "[grow, shrink]0[pref!]", ""));

        JPanel leftButtons = new JPanel(new MigLayout("insets 0, gap 5, hidemode 3, aligny top"));
        leftButtons.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        leftButtons.setOpaque(false);

        openPhoenixButton = new JButton("Phoenix Wallet");
        openClassicButton = new JButton("Classic Wallet");
        openApiButton = new JButton("API doc");
        editConfButton = new JButton("Edit conf file");
        popOff10Button = new JButton("Pop off 10 blocks");
        popOff100Button = new JButton("Pop off 100 blocks");
        dbCheckButton = new JButton("Database check");
        syncButton = new JButton("Pause Sync");
        restartButton = new JButton("Restart");
        shutdownButton = new JButton("Shutdown");

        popOff10Button.setEnabled(false);
        popOff100Button.setEnabled(false);
        dbCheckButton.setEnabled(false);
        syncButton.setEnabled(false);

        updateToolBarIcons();
        updateDbCheckButtonIcon();

        addInfoTooltip(openPhoenixButton, "Opens the modern Phoenix Wallet in your default web browser.");
        addInfoTooltip(openClassicButton, "Opens the Classic Wallet in your default web browser.");
        addInfoTooltip(openApiButton, "Opens the interactive API documentation in your default web browser.");
        addInfoTooltip(editConfButton,
                "Opens the node's configuration file (node.properties or node-default.properties) in your default text editor for easy modification.");
        addInfoTooltip(popOff10Button,
                "Removes the last 10 blocks from your local blockchain. This can help resolve a local fork if your node is stuck.");
        addInfoTooltip(popOff100Button,
                "Removes the last 100 blocks from your local blockchain. Use this if a smaller pop-off does not resolve a fork.");
        addInfoTooltip(dbCheckButton,
                "Performs a manual consistency check on the database to ensure data integrity.");
        addInfoTooltip(syncButton,
                "Toggles the synchronization process. 'Pause Sync' pauses the downloading and processing of new blocks. 'Resume Sync' continues the process.");
        addInfoTooltip(restartButton,
                "Restarts the Signum node application. This is useful for applying configuration changes or reloading the application. A confirmation dialog will be shown before restarting.");
        addInfoTooltip(shutdownButton,
                "Safely stops the Signum node application. This ensures all data is saved correctly and prevents potential database corruption. A confirmation dialog will be shown before shutting down.");

        openPhoenixButton.addActionListener(e -> openWebUi("/phoenix"));
        openClassicButton.addActionListener(e -> openWebUi("/classic"));
        openApiButton.addActionListener(e -> openWebUi("/api-doc"));
        editConfButton.addActionListener(e -> editConf());
        popOff10Button.addActionListener(e -> popOff(10));
        popOff100Button.addActionListener(e -> popOff(100));

        File phoenixIndex = new File("html/ui/phoenix/index.html");
        File classicIndex = new File("html/ui/classic/index.html");

        dbCheckButton.addActionListener(e -> dbCheckAction());

        syncButton.addActionListener(e -> syncButtonAction());
        shutdownButton.addActionListener(e -> shutdownAction());
        restartButton.addActionListener(e -> {
            Runnable restartTask = () -> {
                if (JOptionPane.showConfirmDialog(NodeConsolePanel.this,
                        "This will restart the node. Are you sure?", "Restart node",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                    restart();
                }
            };
            /*
             * if (checkAllUnsavedChanges()) {
             * restartTask.run();
             * }
             */
        });

        if (phoenixIndex.isFile() && phoenixIndex.exists()) {
            leftButtons.add(openPhoenixButton);
        }
        if (classicIndex.isFile() && classicIndex.exists()) {
            leftButtons.add(openClassicButton);
        }
        leftButtons.add(editConfButton);
        leftButtons.add(openApiButton);

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
        popOffButtonsPanel.setMinimumSize(new Dimension(0, 0));
        popOffButtonsPanel.add(popOff10Button);
        popOffButtonsPanel.add(Box.createHorizontalStrut(5));
        popOffButtonsPanel.add(popOff100Button);
        leftButtons.add(popOffButtonsPanel);

        leftButtons.add(dbCheckButton);
        leftButtons.add(syncButton);

        leftButtons.add(restartButton);
        leftButtons.add(shutdownButton);

        contentPanel.add(toolBar, BorderLayout.PAGE_START);

        bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        contentPanel.add(bottomPanel, BorderLayout.PAGE_END);

        /* ── LEGACY: Command Input Panel replaced by UnifiedConsolePanel.ConsoleInputPanel ──
         * The unified console now handles command input internally via its built-in ConsoleInputPanel.
         * Hamburger menu toggle calls unifiedConsole.setShowCommandInput(true/false) with animation.
         * These fields remain declared (but null) for backward compatibility with legacy methods.
         */

        /* ── LEGACY CODE COMMENTED OUT (preserved for reference) ──
        commandPanel = new JPanel(new BorderLayout(0, 0));
        commandPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        JComponent legacyCommandLabel = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                CustomDrawings.COMMAND_SYMBOL.draw((Graphics2D) g, getWidth(), getHeight(), GuiColors.getButtonIcon());
            }
            @Override public Dimension getPreferredSize() {
                int size = Math.round(GuiConstants.getToolBarIconSize());
                return new Dimension(size, size);
            }
        };
        legacyCommandLabel.setToolTipText("Command Input");
        commandField = new JTextField();
        commandField.setToolTipText("Enter node command (e.g. .help, .pause, .resume)");
        JButton legacySendButton = new JButton("Send");
        ActionListener legacySendAction = e -> {
            String cmd = commandField.getText().trim();
            if (!cmd.isEmpty()) {
                LOGGER.info("Executing command: " + cmd);
                new Thread(() -> Signum.processCommand(cmd)).start();
                commandField.setText("");
            }
        };
        commandField.addActionListener(legacySendAction);
        legacySendButton.addActionListener(legacySendAction);
        JButton legacyHelpButton = new HelpButton();
        legacyHelpButton.setToolTipText("Command Help");
        legacyHelpButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String legacyCommandHelpText = "<html><b>Available Commands:</b><br>" +
                "<ul>" +
                "<li><b>.help</b> - Displays available commands in the log.</li>" +
                "<li><b>.pause</b> - Pauses blockchain synchronization.</li>" +
                "<li><b>.resume</b> - Resumes blockchain synchronization.</li>" +
                "<li><b>.restart</b> - Restarts the node application.</li>" +
                "<li><b>.shutdown</b> - Gracefully shuts down the node.</li>" +
                "<li><b>.autoresolve</b> - Triggers manual database consistency resolution.</li>" +
                "<li><b>.trim</b> - Schedules a database trim.</li>" +
                "<li><b>.dbcheck</b> - Performs a database consistency check.</li>" +
                "<li><b>.popoff <n></b> - Pops off the last n blocks (e.g., .popoff 10).</li>" +
                "</ul>" +
                "Enter a command in the text field and click 'Send' or press Enter.</html>";
        legacyHelpButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(NodeConsolePanel.this, legacyCommandHelpText, "Command Usage",
                    JOptionPane.INFORMATION_MESSAGE);
        });
        JPanel legacyButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        legacyButtonPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        legacyButtonPanel.add(legacySendButton);
        legacyButtonPanel.add(legacyHelpButton);
        commandPanel.add(legacyCommandLabel, BorderLayout.WEST);
        commandPanel.add(commandField, BorderLayout.CENTER);
        commandPanel.add(legacyButtonPanel, BorderLayout.EAST);
        commandPanelWrapper = new JPanel(new BorderLayout());
        commandPanelWrapper.setVisible(false);
        commandPanelWrapper.setPreferredSize(new Dimension(0, 0));
        commandPanelWrapper.setMinimumSize(new Dimension(0, 0));
        ── LEGACY CODE END ── */
        syncProgressBar = new JProgressBar(0, 100);
        syncProgressBar.setStringPainted(true);
        syncProgressBar.setFont(UIManager.getFont("Label.font"));
        String syncTooltipText = "Indicates the synchronization progress of the blockchain, displayed as a percentage. This value is calculated by comparing your node's current block height to the estimated highest block height known in the network.\n\nA value of 100% means your node is fully synchronized and has the complete, up-to-date ledger. During synchronization, this bar will gradually fill as the node downloads and processes blocks.";
        syncProgressBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title = "Synchronization Progress";
                    String htmlText = "<html><body><p style='width: 300px;'>" + syncTooltipText.replace("\n", "<br>")
                            + "</p></body></html>";
                    JOptionPane.showMessageDialog(NodeConsolePanel.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
        syncProgressBar.setPreferredSize(GuiConstants.PROGRESS_BAR_SIZE_SMALL);
        syncProgressBar.setMaximumSize(GuiConstants.PROGRESS_BAR_SIZE_SMALL);
        syncProgressBar.setMinimumSize(GuiConstants.PROGRESS_BAR_SIZE_SMALL);

        JPanel latestBlockInfoPanel = new JPanel(new MigLayout("insets 0, hidemode 3, gap 0")) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };
        latestBlockHeightLabel = new JLabel("Latest block: -");
        latestBlockTimestampLabel = new JLabel("Timestamp: -");
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);

        latestBlockInfoPanel.add(latestBlockHeightLabel);
        latestBlockInfoPanel.add(separator, "gapleft 5, gapright 5");
        latestBlockInfoPanel.add(latestBlockTimestampLabel);

        elapsedTimeSeparator = new JSeparator(SwingConstants.VERTICAL);
        elapsedTimeSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        elapsedTimeLabel = new JLabel("Elapsed Time: -");
        String elapsedTooltip = "Displays the time elapsed in seconds since the last block was generated.\n\n"
                + "This counter resets every time a new block is received. Since the target block time is 240 seconds (4 minutes), this helps visualize how long it has been since the last network update.";
        addInfoTooltip(elapsedTimeLabel, elapsedTooltip);
        elapsedTimeSeparator.setVisible(false);
        elapsedTimeLabel.setVisible(false);
        latestBlockInfoPanel.add(elapsedTimeSeparator, "gapleft 5, gapright 5");
        latestBlockInfoPanel.add(elapsedTimeLabel);

        String blockInfoTooltip = "Displays critical information about the most recent block processed by your node. This includes:\n\n"
                + "- Latest block: The sequential number of the latest block synchronized by your node.\n"
                + "- Timestamp: The date and time when the block was generated by a miner.\n\n"
                + "This information is essential for confirming that your node is connected to the network and processing new blocks as they are created.";
        addInfoTooltip(latestBlockHeightLabel, blockInfoTooltip);
        addInfoTooltip(latestBlockTimestampLabel, blockInfoTooltip);
        metricsPanel = new MetricsPanel(parentFrame);
        // Wire ExpansionListener so that chevron toggle updates our tracked state
        metricsPanel.setExpansionListener(expanded -> this.metricsExpanded = expanded);
        metricsPanel.setVisible(false);
        metricsPanelWrapper = new JPanel(new BorderLayout());
        metricsPanelWrapper.add(metricsPanel, BorderLayout.CENTER);

        trimSeparator = new JSeparator(SwingConstants.VERTICAL);
        trimSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        String trimTooltip = "The block height to which the derived tables are trimmed. This removes temporary historical data to maintain performance.\n"
                + "Trimming occurs every " + application.module.node.Constants.TRIM_PERIOD + " blocks.\n\n"
                + "If '-' is shown, the actual trim height is unknown.";
        trimHeightLabel = new DynamicArrowPanel();
        addInfoTooltip(trimHeightLabel, trimTooltip, null);

        trimSeparator.setVisible(false);
        trimHeightLabel.setVisible(false);
        latestBlockInfoPanel.add(trimSeparator, "gapleft 5, gapright 5");
        latestBlockInfoPanel.add(trimHeightLabel);

        pruneSeparator = new JSeparator(SwingConstants.VERTICAL);
        pruneSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        String pruneTooltip = "The current block height of the physical pruning process. Blocks older than the rollback limit are deleted from the disk to save space.\nPruning occurs every "
                + application.module.node.Constants.TRIM_PERIOD + " blocks.";
        pruneHeightLabel = new DynamicArrowPanel();
        addInfoTooltip(pruneHeightLabel, pruneTooltip, "PRUNE");
        pruneSeparator.setVisible(false);
        pruneHeightLabel.setVisible(false);
        latestBlockInfoPanel.add(pruneSeparator, "gapleft 5, gapright 5");
        latestBlockInfoPanel.add(pruneHeightLabel);

        popOffSeparator1 = new JSeparator(SwingConstants.VERTICAL);
        popOffSeparator1.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);

        String popOffCountTooltip = "Shows the number of blocks remaining to be removed from the blockchain during a 'pop-off' operation.\n\nThis counter appears only when a pop-off is in progress and helps monitor its advancement.";
        popOffBlockCountLabel = createLabel("Pop off blocks: 0", null, popOffCountTooltip);

        popOffSeparator2 = new JSeparator(SwingConstants.VERTICAL);
        popOffSeparator2.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);

        String popOffHeightTooltip = "Displays the target block height after the pop-off operation completes, along with the current block height before the pop-off.\n\nThis information is crucial for understanding the state of your blockchain during a pop-off, which is used to resolve forks or other issues by reverting to a previous state.";
        popOffBlockHeightLabel = new DynamicArrowPanel();
        addInfoTooltip(popOffBlockHeightLabel, popOffHeightTooltip, "POP OFF");

        latestBlockInfoPanel.add(popOffSeparator1, "gapleft 5, gapright 5");
        latestBlockInfoPanel.add(popOffBlockCountLabel);
        latestBlockInfoPanel.add(popOffSeparator2, "gapleft 5, gapright 5");
        latestBlockInfoPanel.add(popOffBlockHeightLabel);
        setPopOffLabelVisible(false);

        // === Add toggle to toolBar ===
        popOffToggle = new CustomDrawingComponent(
                showPopOff ? CustomDrawings.Chevron.LEFT : CustomDrawings.Chevron.RIGHT);
        updatePopOffToggleIcon();

        popOffToggle.setToolTipText("Toggle Pop-off buttons");
        popOffToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        popOffToggle.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                togglePopOffButtons();
            }
        });

        popOffButtonsPanel.addHierarchyListener(new java.awt.event.HierarchyListener() {
            @Override
            public void hierarchyChanged(java.awt.event.HierarchyEvent e) {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                        && popOffButtonsPanel.isShowing()) {
                    // One-time setup when the panel is first shown
                    if (popOffPanelWidth < 0) {
                        popOffPanelWidth = popOffButtonsPanel.getPreferredSize().width;
                        if (!showPopOff) {
                            popOffButtonsPanel
                                    .setPreferredSize(new Dimension(0, Math.max(popOffButtonsPanel.getHeight(), 25)));
                            popOffButtonsPanel.setVisible(false);
                            toolBar.revalidate();
                        }
                    }
                    // Remove listener to avoid re-running
                    popOffButtonsPanel.removeHierarchyListener(this);
                }
            }
        });

        popOffToggle.addPropertyChangeListener("UI", e -> SwingUtilities.invokeLater(this::updatePopOffToggleIcon));

        // Hamburger Menu
        menuButton = new JButton(IconFontSwing.buildIcon(FontAwesome.BARS, GuiConstants.getToolBarIconSize(),
                GuiColors.getButtonIcon()));
        menuButton.setToolTipText("Menu");
        menuButton.addActionListener(e -> toggleMenu(null));

        // Menu Panel setup
        menuPanel = new JPanel(new MigLayout("insets 0, fillx, wrap 1, gapy 0", "[grow]"));
        menuPanel.setBackground(UIManager.getColor("PopupMenu.background"));
        menuPanel.setBorder(BorderFactory.createEmptyBorder());

        showCommandItem = new JCheckBox("Show Command Input");
        showCommandItem.setSelected(showCommandInput);
        // Delegate to UnifiedConsolePanel for animated command input toggle
        showCommandItem.addActionListener(e -> {
            boolean newState = showCommandItem.isSelected();
            if (unifiedConsole != null) {
                unifiedConsole.setShowCommandInput(newState);
            }
            showCommandInput = newState;
        });
        styleMenuComponent(showCommandItem);
        menuPanel.add(showCommandItem, "growx");

        commandPositionBottomItem = new JCheckBox();
        updateCommandPositionText();
        commandPositionBottomItem.setSelected(commandPanelAtBottom);
        commandPositionBottomItem.addActionListener(e -> {
            // Toggle: if currently BOTTOM (checked), switch to TOP; otherwise switch to BOTTOM
            boolean goingToBottom = commandPositionBottomItem.isSelected();
            ConsoleInputPosition newPosition = goingToBottom ? ConsoleInputPosition.BOTTOM : ConsoleInputPosition.TOP;
            if (unifiedConsole != null) {
                unifiedConsole.setCommandPosition(newPosition);
            }
            this.commandPanelAtBottom = goingToBottom;
            updateCommandPositionText();
        });
        styleMenuComponent(commandPositionBottomItem);
        menuPanel.add(commandPositionBottomItem, "growx");

        showMetricsItem = new JCheckBox("Show Metrics Panel");
        showMetricsItem.setSelected(showMetricsPanel);
        LOGGER.info("[DEBUG-MetricsPanel] ==== hamburger checkbox created ===== initial selected={}", showMetricsPanel);
        showMetricsItem.addActionListener(e -> {
            LOGGER.info("[DEBUG-MetricsPanel] ==== HAMBURGER CHECKBOX CLICKED ===== new checked={}", showMetricsItem.isSelected());
            updateMetricsPanelState(showMetricsItem.isSelected());
        });
        styleMenuComponent(showMetricsItem);
        menuPanel.add(showMetricsItem, "growx");

        // Auto-start checkbox — controls whether this profile node starts on app launch
        autostartItem = new JCheckBox("Auto-start on App Launch");
        // Load autostart from hierarchical section (same structure as other GUI settings)
        boolean autostartValue = false; // default: disabled
        try {
            String settingsDir = resolveSettingsDir();
            Path settingsPath = application.utils.io.PathUtils
                    .resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
            if (Files.exists(settingsPath)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        JsonObject profileSection = getNodeProfileSectionIfExists(parsed.getAsJsonObject());
                        if (profileSection != null) {
                            autostartValue = getBool(profileSection, "autostart", false);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("Could not load autostart setting for profile '{}'", profileName, ex);
        }
        autostartItem.setSelected(autostartValue);
        autostartItem.addActionListener(e -> {
            boolean newValue = autostartItem.isSelected();
            // Save to hierarchical section: node.{profileName}.autostart
            try {
                String settingsDir = resolveSettingsDir();
                Path settingsPath = application.utils.io.PathUtils
                        .resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
                if (settingsPath.getParent() != null) {
                    Files.createDirectories(settingsPath.getParent());
                }
                JsonObject settings = new JsonObject();
                if (Files.exists(settingsPath)) {
                    try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                        JsonElement parsed = JsonParser.parseReader(reader);
                        if (parsed.isJsonObject()) {
                            settings = parsed.getAsJsonObject();
                        }
                    }
                }
                JsonObject profileSection = getOrCreateNodeProfileSection(settings);
                profileSection.addProperty("autostart", newValue);
                try (java.io.BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    writer.write(gson.toJson(settings));
                }
            } catch (Exception ex) {
                LOGGER.warn("Could not save autostart setting for profile '{}'", profileName, ex);
            }
            LOGGER.info("Auto-start setting for profile '{}' changed to {}", profileName, newValue);
        });
        autostartItem.setToolTipText("When checked, this node profile will start automatically when the application launches");
        styleMenuComponent(autostartItem);
        menuPanel.add(autostartItem, "growx");

        // Skip DB Check on Manual Pop-off — console-specific setting for pop-off decisions
        skipDbCheckItem = new JCheckBox("Skip DB Check on Manual Pop-off");
        skipDbCheckItem.addActionListener(e -> {
            NodeCoreContext ctx = getNodeContext();
            if (ctx != null) {
                BlockchainProcessor bp = ctx.getBlockchainProcessor();
                if (bp != null) {
                    bp.setSkipDbCheckOnManualPopOff(skipDbCheckItem.isSelected());
                }
            }
        });

        JPanel skipDbRow = new JPanel(new MigLayout("insets 0, fillx", "[grow][]")) {
            @Override
            public void setForeground(Color fg) {
                super.setForeground(fg);
                if (skipDbCheckItem != null)
                    skipDbCheckItem.setForeground(fg);
            }
        };
        skipDbRow.setOpaque(false);
        styleMenuComponent(skipDbRow);
        skipDbCheckItem.setOpaque(false);
        skipDbCheckItem.setBorder(null);

        JButton skipDbHelp = new HelpButton();
        skipDbHelp.addActionListener(e -> {
            String msg = "<html><body style='width: 300px'><b>Skip Database Check</b><br><br>" +
                    "If enabled, the node will skip the expensive consistency check after each block removed during a manual pop-off.<br><br>"
                    +
                    "<i>Note: This setting is temporary for the current session. To make it permanent, modify <b>node.popOff.skipDatabaseCheck</b> in your node configuration and restart the node.</i></body></html>";
            showInfoDialog("Manual Pop-off Setting", msg, 350);
        });
        skipDbRow.add(skipDbCheckItem, "growx");
        skipDbRow.add(skipDbHelp);
        menuPanel.add(skipDbRow, "growx");

        menuPanel.add(new JSeparator(), "growx");

        // Configuration item removed from hamburger menu - configuration is now accessible
        // through the NodeProfilePanel's dedicated Configuration tab

        // menuPanelWrapper is managed by MenuPopupController now

        // Wrap the leftButtons (all buttons) in a JScrollPane for horizontal overflow.
        // This ensures that when the window is narrow, a horizontal scrollbar appears below
        // the button row - identical behavior to all configuration panels.
        application.utils.gui.ResponsiveToolbarScrollPane toolbarScroll =
                new application.utils.gui.ResponsiveToolbarScrollPane(leftButtons, GuiConstants.TOOLBAR_INSETS, true);
        toolbarScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        toolbarScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        toolbarScroll.getHorizontalScrollBar().setUnitIncrement(16);

        toolBar.add(toolbarScroll, "growx, pushx, aligny top");

        JPanel rightIconsPanel = new JPanel(new MigLayout("insets 5 5 5 10, gap 5, aligny top"));
        rightIconsPanel.setOpaque(false);
        rightIconsPanel.add(menuButton);
        toolBar.add(rightIconsPanel, "shrink 0, aligny top");

        leftButtons.add(popOffToggle);

        // Use MigLayout for better dynamic resizing support
        topPanel = new JPanel(new MigLayout("insets 0, gap 0, fillx, wrap 1", "[grow]", "[]0[]0[]"));
        topPanel.add(toolBar, "growx");
        topPanel.add(metricsPanelWrapper, "growx");
        /* ── LEGACY: commandPanelWrapper replaced by UnifiedConsolePanel.ConsoleInputPanel ──
         * Command input is now handled internally by UnifiedConsolePanel.
         * This line commented out because commandPanelWrapper is null after legacy code removal.
         */
        // topPanel.add(commandPanelWrapper, "growx");

        // Use MigLayout for infoPanel to allow precise vertical alignment
        infoPanel = new JPanel(
                new MigLayout("insets 0, fillx, hidemode 3, gap 0", "[][][][][][][][][][][][][][grow]", "[]")) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };

        contentPanel.add(topPanel, BorderLayout.NORTH);
        // Use unifiedConsole (contains filter header + console output + command input) as CENTER
        contentPanel.add(unifiedConsole, BorderLayout.CENTER);

        // --- Time Labels ---
        String tooltip;
        String timeTooltip = "Displays the total elapsed time since the node application was started.";
        totalTimeLabel = createLabel("0s", null, timeTooltip);
        String syncTimeTooltip = "Displays the total time the node has spent in synchronization mode. The timer is active only when the blockchain is more than 10 blocks behind the network.";
        syncInProgressTimeLabel = createLabel("0s", null, syncTimeTooltip);

        updateTimeLabelIcons();

        timeSeparator = new JSeparator(SwingConstants.VERTICAL);
        timeSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        innerTimeSeparator = new JSeparator(SwingConstants.VERTICAL);
        innerTimeSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);

        // Visibility initially false, controlled by experimental features
        totalTimeLabel.setVisible(false);
        innerTimeSeparator.setVisible(false);
        syncInProgressTimeLabel.setVisible(false);
        timeSeparator.setVisible(false);

        infoPanel.add(totalTimeLabel);
        infoPanel.add(innerTimeSeparator, "gapleft 5, gapright 5");
        infoPanel.add(syncInProgressTimeLabel);
        infoPanel.add(timeSeparator, "gapleft 5, gapright 5");

        // --- Peers ---
        JPanel peersPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        MouseAdapter peersMouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isRightMouseButton(e)) {
                    PeersDialog.showPeersDialog(parentFrame);
                }
            }
        };

        tooltip = "Connected Peers: The number of peers with a stable, established connection to your node.";
        connectedPeersLabel = new JLabel("0");
        connectedPeersLabel.setToolTipText(tooltip);
        tooltip = "Total Discovered Peers: The total number of peers your node has ever discovered, including active, disconnected, and blacklisted ones.";
        peersCountLabel = new JLabel("0"); // Represents 'All Known' peers
        peersCountLabel.setToolTipText(tooltip);
        tooltip = "Blacklisted Peers: The number of peers that have been temporarily banned for sending invalid data or other network violations.";
        blacklistedPeersLabel = new JLabel("0");
        blacklistedPeersLabel.setToolTipText(tooltip);

        peersPanel.add(new JLabel("Peers: "));
        peersPanel.add(connectedPeersLabel);
        peersPanel.add(new JLabel(" / "));
        peersPanel.add(peersCountLabel);
        peersPanel.add(new JLabel(" (BL: "));
        peersPanel.add(blacklistedPeersLabel);
        peersPanel.add(new JLabel(")"));

        // Add peersPanel
        peersPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        peersPanel.addMouseListener(peersMouseAdapter);
        peersPanel.setToolTipText("Click to see detailed peer information.");

        for (Component comp : peersPanel.getComponents()) {
            comp.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            comp.addMouseListener(peersMouseAdapter);
        }

        infoPanel.add(peersPanel);

        // Add separator after peersPanel
        JSeparator peersSeparator = new JSeparator(SwingConstants.VERTICAL);
        peersSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        infoPanel.add(peersSeparator, "gapleft 5, gapright 5");

        // --- Volume ---
        JPanel volumePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        // Upload
        tooltip = "The total amount of data your node has uploaded to other peers since the application started. This data primarily consists of blocks and transactions that you are sharing with the rest of the network, contributing to its health and decentralization.";
        uploadVolumeLabel = createLabel("0 MB", null, tooltip);

        // Download
        tooltip = "The total amount of data your node has downloaded from other peers since the application started. This data includes blocks and transactions required to synchronize your local copy of the blockchain with the network.";
        downloadVolumeLabel = createLabel("0 MB", null, tooltip);

        updateVolumeLabelIcons();

        volumePanel.add(uploadVolumeLabel);
        volumePanel.add(new JLabel(" / "));
        volumePanel.add(downloadVolumeLabel);

        // Add volumePanel
        infoPanel.add(volumePanel);

        // Add separator after volumePanel
        JSeparator volumeSeparator = new JSeparator(SwingConstants.VERTICAL);
        volumeSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        infoPanel.add(volumeSeparator, "gapleft 5, gapright 5");

        // --- Measurement ---
        measurementSeparator = new JSeparator(SwingConstants.VERTICAL);
        measurementSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        measurementSeparator.setMaximumSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        tooltip = "Performance measurement is active.\n"
                + "Detailed synchronization data is being collected for each block and saved to:\n"
                + "- measurement/sync_measurement.csv\n"
                + "- measurement/sync_progress.csv\n" + "for analysis."
                + "\n\nEnabled by property: node.measurementActive = true";
        measurementLabel = new JLabel(IconFontSwing.buildIcon(FontAwesome.FLASK, GuiConstants.getToolBarIconSize(),
                GuiColors.getButtonIcon()));
        addInfoTooltip(measurementLabel, tooltip, "MEASUREMENT");
        measurementLabel.setVisible(false);
        measurementSeparator.setVisible(false);

        infoPanel.add(measurementLabel);
        infoPanel.add(measurementSeparator, "gapleft 5, gapright 5");

        // --- Experimental ---
        experimentalSeparator = new JSeparator(SwingConstants.VERTICAL);
        experimentalSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        experimentalSeparator.setMaximumSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        tooltip = "Experimental feature is enabled.\n" + "Simplified data is being collected and saved to:\n"
                + "- measurement/sync_progress.csv\n" + "for analysis."
                + "\n\nEnabled by property: node.experimental = true";
        experimentalLabel = new JLabel(
                IconFontSwing.buildIcon(FontAwesome.COG, GuiConstants.getToolBarIconSize(), GuiColors.getButtonIcon()));
        addInfoTooltip(experimentalLabel, tooltip, "EXPERIMENTAL");
        experimentalLabel.setVisible(false);
        experimentalSeparator.setVisible(false);

        infoPanel.add(experimentalLabel);
        infoPanel.add(experimentalSeparator, "gapleft 5, gapright 5");

        // --- Trim ---
        trimIconSeparator = new JSeparator(SwingConstants.VERTICAL);
        trimIconSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        trimIconSeparator.setMaximumSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        trimLabel = new JLabel(IconFontSwing.buildIcon(FontAwesome.SCISSORS, GuiConstants.getToolBarIconSize(),
                GuiColors.getButtonIcon()));
        trimLabel.setVisible(false);
        trimIconSeparator.setVisible(false);

        infoPanel.add(trimLabel);
        infoPanel.add(trimIconSeparator, "gapleft 5, gapright 5");

        // --- Auto Resolve ---
        autoResolveSeparator = new JSeparator(SwingConstants.VERTICAL);
        autoResolveSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        autoResolveSeparator.setMaximumSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        tooltip = "Auto-Resolve is enabled.\n" +
                "If database inconsistency is detected at startup, the node will automatically attempt to resolve it by rolling back blocks."
                + "\n\nEnabled by property: node.autoConsistencyResolve = true";
        autoResolveLabel = new JLabel(IconFontSwing.buildIcon(FontAwesome.WRENCH, GuiConstants.getToolBarIconSize(),
                GuiColors.getButtonIcon()));
        addInfoTooltip(autoResolveLabel, tooltip, "AUTO RESOLVE");
        autoResolveLabel.setVisible(false);
        autoResolveSeparator.setVisible(false);

        infoPanel.add(autoResolveLabel);
        infoPanel.add(autoResolveSeparator, "gapleft 5, gapright 5");

        infoPanel.add(syncProgressBar, "growx");

        bottomPanel.add(latestBlockInfoPanel, BorderLayout.CENTER);
        bottomPanel.add(infoPanel, BorderLayout.LINE_END);

        try {
            if (parentFrame != null && iconLocation != null) {
                java.io.InputStream iconStream = getClass().getResourceAsStream(iconLocation);
                if (iconStream != null) {
                    parentFrame.setIconImage(ImageIO.read(iconStream));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (parentFrame != null) {
            parentFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            parentFrame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (trayIcon == null) {
                        Runnable exitTask = () -> {
                            if (JOptionPane.showConfirmDialog(NodeConsolePanel.this,
                                    "This will stop the node. Are you sure?", "Exit and stop node",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                                shutdown();
                            }
                        };
                        /*
                         * if (checkAllUnsavedChanges()) {
                         * exitTask.run();
                         * }
                         */
                    } else {
                        trayIcon.displayMessage("Signum GUI closed", "Note that Signum is still running",
                                MessageType.INFO);
                        setVisible(false);
                    }
                }
            });

            // Force a re-layout and repaint of the toolbar during resize to prevent visual
            // artifacts
            this.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (toolBar != null) {
                        toolBar.revalidate();
                        toolBar.repaint();
                    }
                }
            });

            parentFrame.pack();
            Insets insets = parentFrame.getInsets();
            int preferredContentWidth = Math.max(topPanel.getPreferredSize().width,
                    metricsPanel.getPreferredSize().width);
            parentFrame.setSize(preferredContentWidth + insets.left + insets.right, 800);
            parentFrame.setLocationRelativeTo(null);
            showWindow();
        }

        // Popups use PopupAutoCloser to handle outside clicks / window deactivation / resize.

        // Finalize custom component states (icons, fonts for Nimbus, etc.)
        updateCustomComponents();

        // Apply Metrics Panel visibility NOW so the hamburger menu checkbox state matches
        // the actual panel on first render. This is critical in multi-profile mode where
        // startSignumWithGUI() runs in a background thread and may not apply settings in time.
        applyPanelVisibilityState();

        // Start BRS
        new Thread(this::startSignumWithGUI).start();
    }
    /*
     * private boolean checkAllUnsavedChanges() {
     * return configurationPanel == null ||
     * configurationPanel.checkUnsavedChanges();
     * }
     */

    private void shutdown() {
        JDialog shutdownDialog = new JDialog(parentFrame, "Shutting down", true);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel messageLabel = new JLabel("Please wait, Signum is shutting down...");
        messageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(messageLabel);

        panel.add(Box.createRigidArea(new Dimension(0, 15)));

        final RotatingSvgIcon rotatingIcon = new RotatingSvgIcon(0.5);
        rotatingIcon.setPreferredSize(new Dimension(64, 64));
        rotatingIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(rotatingIcon);
        rotatingIcon.start();

        shutdownDialog.setContentPane(panel);
        shutdownDialog.pack();
        shutdownDialog.setLocationRelativeTo(this);
        shutdownDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        shutdownDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                new Thread(() -> {
                    saveGuiSettings();
                    isShuttingDown = true;

                    if (elapsedTimeTimer != null) {
                        try {
                            elapsedTimeTimer.stop();
                        } catch (Throwable t) {
                            LOGGER.warn("Error stopping elapsed time timer", t);
                        }
                    }
                    if (guiTimer != null) {
                        try {
                            guiTimer.stop();
                        } catch (Throwable t) {
                            LOGGER.warn("Error stopping GUI timer", t);
                        }
                    }
                    if (popOffAnimator != null) {
                        try {
                            popOffAnimator.stop();
                        } catch (Throwable t) {
                            LOGGER.warn("Error stopping popOff animator", t);
                        }
                    }
                    if (metricsPanelAnimator != null) {
                        try {
                            metricsPanelAnimator.stop();
                        } catch (Throwable t) {
                            LOGGER.warn("Error stopping metricsPanel animator", t);
                        }
                    }
                    hideCommandPanelInline();
                    if (menuPopupController != null && menuPopupController.isOpen()) {
                        try {
                            menuPopupController.hide();
                        } catch (Throwable t) {
                            LOGGER.warn("Error hiding menu popup", t);
                        }
                    }

                    if (metricsPanel != null) {
                        try {
                            metricsPanel.shutdown();
                        } catch (Throwable t) {
                            LOGGER.warn("Error shutting down metrics panel", t);
                        }
                    }

                    try {
                        Signum.shutdown(false);
                    } catch (Throwable t) {
                        LOGGER.error("Unexpected error during Signum core shutdown", t);
                    }

                    if (trayIcon != null && SystemTray.isSupported()) {
                        try {
                            SystemTray.getSystemTray().remove(trayIcon);
                        } catch (Throwable t) {
                            LOGGER.warn("Error removing tray icon", t);
                        }
                    }

                    System.exit(0);
                }).start();
            }
        });

        shutdownDialog.setVisible(true);
    }

    private void updateToolBarIcons() {
        Color currentIconColor = GuiColors.getButtonIcon();
        float iconSize = GuiConstants.getToolBarIconSize();

        if (openPhoenixButton != null)
            openPhoenixButton.setIcon(IconFontSwing.buildIcon(FontAwesome.FIRE, iconSize, currentIconColor));
        if (openClassicButton != null)
            openClassicButton.setIcon(IconFontSwing.buildIcon(FontAwesome.WINDOW_RESTORE, iconSize, currentIconColor));
        if (openApiButton != null)
            openApiButton.setIcon(IconFontSwing.buildIcon(FontAwesome.BOOK, iconSize, currentIconColor));
        if (editConfButton != null)
            editConfButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PENCIL, iconSize, currentIconColor));
        if (popOff10Button != null)
            popOff10Button.setIcon(IconFontSwing.buildIcon(FontAwesome.STEP_BACKWARD, iconSize, currentIconColor));
        if (popOff100Button != null)
            popOff100Button.setIcon(IconFontSwing.buildIcon(FontAwesome.BACKWARD, iconSize, currentIconColor));
        if (syncButton != null) {
            syncButton.setIcon(IconFontSwing.buildIcon(isSyncStopped ? FontAwesome.PLAY : FontAwesome.PAUSE, iconSize,
                    currentIconColor));
        }
        if (restartButton != null)
            restartButton.setIcon(IconFontSwing.buildIcon(FontAwesome.REFRESH, iconSize, currentIconColor));
        if (shutdownButton != null)
            shutdownButton.setIcon(IconFontSwing.buildIcon(FontAwesome.POWER_OFF, iconSize, currentIconColor));

        if (measurementLabel != null)
            measurementLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.FLASK, GuiConstants.getToolBarIconSize(),
                    GuiColors.getButtonIcon()));
        if (experimentalLabel != null)
            experimentalLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.COG, GuiConstants.getToolBarIconSize(),
                    GuiColors.getButtonIcon()));
        if (trimLabel != null)
            trimLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.SCISSORS, GuiConstants.getToolBarIconSize(),
                    GuiColors.getButtonIcon()));
        if (autoResolveLabel != null)
            autoResolveLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.WRENCH, GuiConstants.getToolBarIconSize(),
                    GuiColors.getButtonIcon()));

        if (menuButton != null)
            menuButton.setIcon(IconFontSwing.buildIcon(FontAwesome.BARS, iconSize, currentIconColor));

        if (globeButton != null)
            globeButton.setIcon(
                    IconFontSwing.buildIcon(FontAwesome.GLOBE, GuiConstants.getToolBarIconSize(), currentIconColor));
    }

    private void updatePopOffToggleIcon() {
        if (popOffToggle != null)
            popOffToggle.setDrawing(showPopOff ? CustomDrawings.Chevron.LEFT : CustomDrawings.Chevron.RIGHT);
    }

    private void updateDbCheckButtonIcon() {
        if (dbCheckButton != null) {
            dbCheckButton.setIcon(IconFontSwing.buildIcon(FontAwesome.DATABASE, GuiConstants.getToolBarIconSize(),
                    dbConsistencyColor));
        }
    }

    private void updateTimeLabelIcons() {
        if (totalTimeLabel != null) {
            totalTimeLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.CLOCK_O, GuiConstants.getHelpIconSize(),
                    GuiColors.getButtonIcon()));
        }
        if (syncInProgressTimeLabel != null) {
            syncInProgressTimeLabel.setIcon(IconFontSwing.buildIcon(FontAwesome.REFRESH, GuiConstants.getHelpIconSize(),
                    GuiColors.getButtonIcon()));
        }
    }

    private void showTrayIcon() {
        if (trayIcon == null) { // Don't start running in tray twice
            trayIcon = createTrayIcon();
        }
    }

    private TrayIcon createTrayIcon() {
        // Guard against null parentFrame (can happen in multi-profile mode when NodeConsolePanel
        // is embedded in a NodeProfilePanel tab rather than a standalone JFrame).
        if (parentFrame == null) {
            LOGGER.warn("parentFrame is null - skipping tray icon creation");
            return null;
        }

        PopupMenu popupMenu = new PopupMenu();

        MenuItem openPheonixWalletItem = new MenuItem("Phoenix Wallet");
        MenuItem openClassicWalletItem = new MenuItem("Classic Wallet");
        MenuItem showItem = new MenuItem("Show the node window");
        MenuItem shutdownItem = new MenuItem("Shutdown the node");

        File phoenixIndex = new File("html/ui/phoenix/index.html");

        openPheonixWalletItem.addActionListener(e -> openWebUi("/phoenix"));
        openClassicWalletItem.addActionListener(e -> openWebUi("/classic"));
        showItem.addActionListener(e -> showWindow());
        shutdownItem.addActionListener(e -> shutdown());

        popupMenu.add(openClassicWalletItem);
        popupMenu.add(showItem);
        popupMenu.add(shutdownItem);

        parentFrame.validate();

        try {
            String newIconLocation = iconLocation;
            if (Signum.getPropertyService() != null) {
                newIconLocation = Signum.getPropertyService().getString(Props.ICON_LOCATION);
            }
            if (!newIconLocation.equals(iconLocation)) {
                // update the icon
                iconLocation = newIconLocation;
                parentFrame.setIconImage(ImageIO.read(getClass().getResourceAsStream(iconLocation)));
            }
            TrayIcon newTrayIcon = new TrayIcon(
                    Toolkit.getDefaultToolkit().createImage(NodePanel.class.getResource(iconLocation)), "Signum Node",
                    popupMenu);
            newTrayIcon.setImage(
                    newTrayIcon.getImage().getScaledInstance(newTrayIcon.getSize().width, -1, Image.SCALE_SMOOTH));
            if (phoenixIndex.isFile() && phoenixIndex.exists()) {
                newTrayIcon.addActionListener(e -> openWebUi("/phoenix"));
            }

            SystemTray systemTray = SystemTray.getSystemTray();
            systemTray.add(newTrayIcon);

            newTrayIcon.displayMessage("Signum Running",
                    "Signum is running on background, use this icon to interact with it.", MessageType.INFO);

            return newTrayIcon;
        } catch (Exception e) {
            LOGGER.info("Could not create tray icon");
            return null;
        }
    }

    private void syncButtonAction() {
        // The UI will update via the onSyncStateChanged listener when the core
        // processes the change.
        BlockchainProcessor blockchainProcessor = getNodeContext().getBlockchainProcessor();
        if (blockchainProcessor != null) {
            blockchainProcessor.setSyncPaused(!isSyncStopped);
        }
    }

    private void shutdownAction() {
        Runnable shutdownTask = () -> {
            if (JOptionPane.showConfirmDialog(NodeConsolePanel.this,
                    "This will stop the node. Are you sure?", "Shutdown Node",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                shutdown();
            }
        };
        /*
         * if (checkAllUnsavedChanges()) {
         * shutdownTask.run();
         * }
         */
    }

    /**
     * Performs a database consistency check and handles the UI response based on
     * the result and configuration.
     * <p>
     * This method runs on a background thread to avoid blocking the EDT during the
     * check.
     * The UI feedback logic handles the following scenarios:
     * <ul>
     * <li><b>Consistent:</b> Displays a success message with database
     * statistics.</li>
     * <li><b>Inconsistent:</b>
     * <ul>
     * <li><b>Auto-Resolve Triggered:</b> If auto-resolve is enabled and this check
     * triggered it
     * (state transition to INCONSISTENT), displays an information message that
     * automatic resolution has started.</li>
     * <li><b>Already Active:</b> If a resolution process was already running before
     * this check,
     * displays a warning that resolution is in progress.</li>
     * <li><b>Manual Action Required:</b> If auto-resolve is disabled or did not
     * trigger (e.g., persistent inconsistency),
     * displays an error dialog offering the user to manually start the resolution
     * process.</li>
     * </ul>
     * </li>
     * </ul>
     */
    private void dbCheckAction() {
        BlockchainProcessor blockchainProcessor = getNodeContext().getBlockchainProcessor();
        if (blockchainProcessor == null) {
            showMessage("Blockchain processor not initialized.");
            return;
        }

        String statusMessage;
        if (blockchainProcessor.getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE) {
            statusMessage = "Auto database resolve ongoing. Database check will run after resolution is finished...";
        } else if (blockchainProcessor.isTrimming()) {
            statusMessage = "Trim ongoing. Database check will run after trim is finished...";
        } else if (blockchainProcessor.getManualPopOffBlocksCount() > 0
                || blockchainProcessor.getAutoPopOffBlocksCount() > 0) {
            statusMessage = "Pop-off ongoing. Database check will run after pop-off is finished...";
        } else {
            statusMessage = "Database consistency check in progress...";
        }

        waitDialog = new JDialog(parentFrame, "Database Check", true);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel messageLabel = new JLabel(statusMessage);
        messageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(messageLabel);

        panel.add(Box.createRigidArea(new Dimension(0, 15)));

        final RotatingSvgIcon rotatingIcon = new RotatingSvgIcon(0.5);
        rotatingIcon.setPreferredSize(new Dimension(64, 64));
        rotatingIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(rotatingIcon);
        rotatingIcon.start();

        waitDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                rotatingIcon.stop();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                rotatingIcon.stop();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                if (isDbCheckRunning.get()) {
                    return;
                }
                isDbCheckRunning.set(true);

                new Thread(() -> {
                    try {
                        // Check if resolution was already active before we requested the check
                        boolean wasResolutionActive = blockchainProcessor
                                .getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE;

                        final int result = blockchainProcessor.checkDatabaseStateRequest();
                        final int height = blockchainProcessor.getLastCheckHeight();
                        final long totalMined = blockchainProcessor.getLastCheckTotalMined();
                        final long totalEffectiveBalance = blockchainProcessor.getLastCheckTotalEffectiveBalance();

                        final int finalLimitHeight = blockchainProcessor.getMinRollbackHeight();
                        int lastTrimHeight = blockchainProcessor.getCurrentTrimHeight().get();
                        final int finalLastTrimHeight = lastTrimHeight;

                        SwingUtilities.invokeLater(() -> {
                            if (waitDialog.isDisplayable()) {
                                rotatingIcon.stop();
                                waitDialog.dispose();
                            }
                            showDbCheckResult(result, height, totalMined, totalEffectiveBalance, wasResolutionActive,
                                    finalLimitHeight, finalLastTrimHeight);
                        });
                    } catch (Exception ex) {
                        LOGGER.error("Error during DB check", ex);
                        SwingUtilities.invokeLater(() -> {
                            if (waitDialog.isDisplayable()) {
                                rotatingIcon.stop();
                                waitDialog.dispose();
                            }
                            String message = "An error occurred during the database check.";
                            if (ex instanceof IllegalStateException
                                    && ex.getMessage().contains("already in progress")) {
                                message = "A database check is already running in the background.";
                            }
                            JOptionPane.showMessageDialog(NodeConsolePanel.this, message,
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    } finally {
                        isDbCheckRunning.set(false);
                    }
                }).start();
            }
        });

        waitDialog.setContentPane(panel);
        waitDialog.pack();
        waitDialog.setLocationRelativeTo(NodeConsolePanel.this);
        waitDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        if (isDbCheckRunning.get()) {
            waitDialog.setVisible(true);
            return;
        }

        waitDialog.setVisible(true);
    }

    private void showDbCheckResult(int result, int height, long totalMined, long totalEffectiveBalance,
            boolean wasResolutionActive, int limitHeight, int lastTrimHeight) {
        BlockchainProcessor blockchainProcessor = getNodeContext().getBlockchainProcessor();
        final double totalMinedSigna = (double) totalMined / Constants.ONE_SIGNA;
        final double totalEffectiveBalanceSigna = (double) totalEffectiveBalance / Constants.ONE_SIGNA;
        final long difference = totalMined - totalEffectiveBalance;

        String message;
        Icon icon;
        if (result == 0) {
            message = String.format("Database is consistent at block height %d.\n\n" +
                    "Total Mined: %,.2f SIGNA (%,d NQT)\n" +
                    "Total Effective Balance: %,.2f SIGNA (%,d NQT)",
                    height,
                    totalMinedSigna, totalMined,
                    totalEffectiveBalanceSigna, totalEffectiveBalance);
            icon = IconFontSwing.buildIcon(FontAwesome.CHECK_CIRCLE, GuiConstants.ICON_SIZE_DIALOG,
                    new Color(0, 128, 0));
            JOptionPane.showMessageDialog(NodeConsolePanel.this, message, "Database Consistency Check",
                    JOptionPane.INFORMATION_MESSAGE, icon);
        } else {
            String inconsistencyType;
            if (result > 0) {
                inconsistencyType = "Total mined is greater than total effective balance.";
            } else {
                inconsistencyType = "Total mined is less than total effective balance.";
            }
            String infoMessage = String.format("Database is INCONSISTENT!\n\n%s\n\n" +
                    "Total Mined: %,.2f SIGNA (%,d NQT)\n" +
                    "Total Effective Balance: %,.2f SIGNA (%,d NQT)\n\n" +
                    "Difference: %,d NQT\n\nCheck logs for more details at block height %d.",
                    inconsistencyType,
                    totalMinedSigna, totalMined,
                    totalEffectiveBalanceSigna, totalEffectiveBalance,
                    difference, height);

            String resolveMessage = "This tool can try to automatically resolve the inconsistency by popping off blocks.\n"
                    + "It will rollback blocks until the database becomes consistent or the safe rollback limit is reached.\n\n"
                    + "The safe rollback limit is calculated as follows:\n";

            if (trimEnabled) {
                resolveMessage += "- Trimming enabled. Limit is the last trim height: " + limitHeight + ".\n";
                if (lastTrimHeight <= 0) {
                    resolveMessage += "  (Estimated using modulo of current height and trim period "
                            + Constants.TRIM_PERIOD + ")\n";
                }
            } else {
                resolveMessage += "- Trimming disabled. Limit is " + Constants.MAX_ROLLBACK + " blocks back: "
                        + limitHeight + ".\n";
            }
            resolveMessage += "The process stops if consistency is restored before reaching this limit.";

            icon = IconFontSwing.buildIcon(FontAwesome.EXCLAMATION_TRIANGLE, GuiConstants.ICON_SIZE_DIALOG,
                    GuiColors.getContrastRed());

            if (blockchainProcessor.getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE) {
                String activeMessage;
                String title;
                int messageType;

                if (!wasResolutionActive
                        && Signum.getPropertyService().getBoolean(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED)) {
                    activeMessage = "The database is INCONSISTENT.\n\n" +
                            "An automatic consistency resolution has been started.\n" +
                            "Please check the logs for progress.";
                    title = "Automatic Resolution Started";
                    messageType = JOptionPane.INFORMATION_MESSAGE;
                } else {
                    activeMessage = "Consistency resolution is currently IN PROGRESS.\n" +
                            "Please check the logs for progress.";
                    title = "Database Consistency Check";
                    messageType = JOptionPane.WARNING_MESSAGE;
                }

                Object[] messageContent = { infoMessage, Box.createVerticalStrut(10), new JSeparator(),
                        Box.createVerticalStrut(10), activeMessage };

                JOptionPane.showMessageDialog(NodeConsolePanel.this, messageContent, title,
                        messageType, icon);
                return;
            }

            Object[] messageContent = {
                    infoMessage,
                    Box.createVerticalStrut(10),
                    new JSeparator(),
                    Box.createVerticalStrut(10),
                    resolveMessage
            };

            Object[] options = { "Start Auto Resolve Database Consistency", "Cancel" };
            int n = JOptionPane.showOptionDialog(NodeConsolePanel.this, messageContent, "Database Consistency Check",
                    JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE, icon, options, options[1]);

            if (n == 0) {
                blockchainProcessor.manualResolveDatabaseConsistency();
            }
        }
    }

    private void showWindow() {
        setVisible(true);
    }
    /*
     * private void popOff(int blocks) {
     * LOGGER.info("Pop off requested, this can take a while...");
     * int height = blocks > 0 ? Signum.getBlockchain().getLastBlock().getHeight() -
     * blocks
     * : Signum.getBlockchainProcessor().getMinRollbackHeight();
     * new Thread(() -> Signum.getBlockchainProcessor().popOffTo(height)).start();
     * }
     */

    private void popOff(int count) {
        // LOGGER.info("Pop off requested, this can take a while...");
        if (getNodeContext().getBlockchainProcessor() == null) {
            showMessage("Blockchain processor not initialized.");
            return;
        }
        new Thread(() -> getNodeContext().getBlockchainProcessor().popOff(count)).start();
    }

    /**
     * Public wrapper for restart - called from NodeProfilePanel
     */
    public void restartNode() {
        restart();
    }

    /**
     * Public wrapper for shutdown - called from NodeProfilePanel
     */
    public void stopNode() {
        shutdown();
    }

    /**
     * Public wrapper for starting the node - called from NodeProfilePanel.
     * Delegates to startSignumWithGUI.
     */
    public void startNode() {
        new Thread(this::startSignumWithGUI).start();
    }

    // Package-private for internal use
    void restart() {
        LOGGER.info("Restarting node...");

        JDialog restartDialog = new JDialog(parentFrame, "Restarting", true);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel messageLabel = new JLabel("Please wait, Signum is restarting...");
        messageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(messageLabel);

        panel.add(Box.createRigidArea(new Dimension(0, 15)));

        final RotatingSvgIcon rotatingIcon = new RotatingSvgIcon(0.5);
        rotatingIcon.setPreferredSize(new Dimension(64, 64));
        rotatingIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(rotatingIcon);
        rotatingIcon.start();

        restartDialog.setContentPane(panel);
        restartDialog.pack();
        restartDialog.setLocationRelativeTo(this);
        restartDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        restartDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                new Thread(() -> {
                    saveGuiSettings();
                    application.launcher.Launcher.restart();
                }).start();
            }
        });

        restartDialog.setVisible(true);
    }

    private void editConf() {
        Path nodeFolder = application.utils.io.PathUtils.resolvePath(confFolder).resolve("node");
        Path path = nodeFolder.resolve(Signum.PROPERTIES_NAME);
        if (!Files.exists(path)) {
            path = nodeFolder.resolve(Signum.DEFAULT_PROPERTIES_NAME);
        }

        File file = path.toFile();

        if (!file.exists()) {
            JOptionPane.showMessageDialog(this,
                    "Could not find conf file: " + Signum.PROPERTIES_NAME + " or " + Signum.DEFAULT_PROPERTIES_NAME,
                    "File not found", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            LOGGER.error("Could not open conf file with default editor", e);
        }
    }

    private void openWebUi(String path) {
        try {
            PropertyService propertyService = Signum.getPropertyService();
            int port = propertyService.getInt(Props.API_PORT);
            String httpPrefix = propertyService.getBoolean(Props.API_SSL) ? "https://" : "http://";
            String address = httpPrefix + "localhost:" + port + path;
            try {
                Desktop.getDesktop().browse(new URI(address));
            } catch (Exception e) { // Catches parse exception or exception when opening browser
                LOGGER.error("Could not open browser", e);
                showMessage("Error opening web UI. Please open your browser and navigate to " + address);
            }
        } catch (Exception e) { // Catches error accessing PropertyService
            LOGGER.error("Could not access PropertyService", e);
            showMessage("Could not open web UI as could not read the configuration file.");
        }
    }

    private void initListeners() {
        BlockchainProcessor blockchainProcessor = getNodeContext().getBlockchainProcessor();
        blockchainProcessor.addListener(block -> onPeersUpdated(), BlockchainProcessor.Event.PEERS_UPDATED);
        blockchainProcessor.addListener(block -> onNetVolumeChanged(), BlockchainProcessor.Event.NET_VOLUME_CHANGED);
        blockchainProcessor.addListener(this::onBlockPushed, BlockchainProcessor.Event.BLOCK_PUSHED);
        blockchainProcessor.addListener(block -> onBlockPopped(), BlockchainProcessor.Event.BLOCK_MANUAL_POPPED);
        blockchainProcessor.addListener(block -> onBlockPopped(), BlockchainProcessor.Event.BLOCK_AUTO_POPPED);
        blockchainProcessor.addListener(block -> onManualPopOffProgress(),
                BlockchainProcessor.Event.BLOCK_MANUAL_POPPED);
        blockchainProcessor.addListener(block -> onAutoPopOffProgress(), BlockchainProcessor.Event.BLOCK_AUTO_POPPED);
        blockchainProcessor.addSyncStateListener(this::onSyncStateChanged);

        if (trimEnabled) {
            blockchainProcessor.addTrimListener(
                    stats -> onTrimStart(stats.currentHeight, stats.targetHeight),
                    BlockchainProcessor.Event.TRIM_START);
            blockchainProcessor.addTrimListener(
                    stats -> onTrimEnd(stats.currentHeight),
                    BlockchainProcessor.Event.TRIM_END);
            blockchainProcessor.addPruneListener(
                    stats -> onPruneStart(stats.currentHeight, stats.targetHeight),
                    BlockchainProcessor.Event.PRUNE_START);
            blockchainProcessor.addPruneListener(
                    stats -> onPruneEnd(stats.currentHeight),
                    BlockchainProcessor.Event.PRUNE_END);
            blockchainProcessor.addListener(block -> onConsistencyUpdate(),
                    BlockchainProcessor.Event.DATABASE_CONSISTENCY_UPDATE);
        }
    }

    public void onPeersUpdated() {
        BlockchainProcessor blockchainProcessor = getNodeContext().getBlockchainProcessor();
        Collection<Peer> allPeers = blockchainProcessor.getAllPeers();
        long connectedCount = allPeers.stream().filter(p -> p.getState() == Peer.State.CONNECTED).count();
        long allKnownCount = allPeers.size();
        long blacklistedCount = allPeers.stream().filter(Peer::isBlacklisted).count();
        SwingUtilities.invokeLater(() -> updatePeerCount(connectedCount, allKnownCount, blacklistedCount));
    }

    public void onNetVolumeChanged() {
        BlockchainProcessor blockchainProcessor = getNodeContext().getBlockchainProcessor();
        long uploaded = blockchainProcessor.getUploadedVolume();
        long downloaded = blockchainProcessor.getDownloadedVolume();
        SwingUtilities.invokeLater(() -> {
            uploadVolumeLabel.setText(formatDataSize(uploaded));
            downloadVolumeLabel.setText(formatDataSize(downloaded));

            // Start the GUI timer only once, when the first download volume is received,
            // and if experimental features are enabled in the config.
            if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL)
                    && downloaded > 0
                    && !guiTimerStarted.getAndSet(true)) {
                startGuiTimer();
            }
        });
    }

    private void startGuiTimer() {
        guiTimer = new Timer(1000, e -> {
            if (Signum.getBlockchain() != null && Signum.getBlockchainProcessor() != null) {
                guiAccumulatedSyncTimeMs += 1000;
                totalTimeLabel.setText(DurationFormatter.format(guiAccumulatedSyncTimeMs,
                        DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));

                if (isSyncing) {
                    guiAccumulatedSyncInProgressTimeMs += 1000;
                }
                syncInProgressTimeLabel
                        .setText(DurationFormatter.format(guiAccumulatedSyncInProgressTimeMs,
                                DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));
                updateTimeLabelVisibility();
            }
        });
        guiTimer.start();
    }

    private void onTrimStart(int currentHeight, int targetHeight) {
        SwingUtilities.invokeLater(() -> {

            trimHeightLabel.setVisible(true);
            trimSeparator.setVisible(true);
            pruneHeightLabel.setVisible(false);
            pruneSeparator.setVisible(false);

            trimHeightLabel.updateValues("Trim height: " + currentHeight,
                    FontAwesome.ARROW_RIGHT, String.valueOf(targetHeight),
                    GuiColors.getSaved(), GuiColors.getSaved());
        });
    }

    private void onTrimEnd(int currentHeight) {
        SwingUtilities.invokeLater(() -> {
            trimHeightLabel.updateValues("Trim height: " + currentHeight,
                    null, "", GuiColors.getApplied(), null);
            trimHeightLabel.setAllColors(GuiColors.getApplied());
        });
    }

    private void onPruneStart(int currentHeight, int targetHeight) {
        SwingUtilities.invokeLater(() -> {

            trimHeightLabel.setVisible(false);
            trimSeparator.setVisible(false);
            pruneHeightLabel.setVisible(true);
            pruneSeparator.setVisible(true);

            pruneHeightLabel.updateValues("Prune height: " + currentHeight,
                    FontAwesome.ARROW_RIGHT, String.valueOf(targetHeight),
                    GuiColors.getSaved(), GuiColors.getSaved());
        });
    }

    private void onPruneEnd(int currentHeight) {
        SwingUtilities.invokeLater(() -> {
            pruneHeightLabel.updateValues("Prune height: " + (currentHeight < 0 ? "-" : currentHeight),
                    null, "", GuiColors.getApplied(), null);
            pruneHeightLabel.setAllColors(GuiColors.getApplied());
        });
    }

    private void onConsistencyUpdate() {
        BlockchainProcessor.ConsistencyState state = getNodeContext().getBlockchainProcessor().getConsistencyState();
        SwingUtilities.invokeLater(() -> {
            switch (state) {
                case CONSISTENT:
                    dbConsistencyColor = GuiColors.getStatusConsistent();
                    break;
                case INCONSISTENT:
                    dbConsistencyColor = GuiColors.getContrastRed();
                    break;
                default: // UNDEFINED
                    dbConsistencyColor = GuiColors.getButtonIcon();
            }
            updateDbCheckButtonIcon();
        });
    }

    private void onBlockPushed(Block block) {
        if (block == null)
            return;
        int maxPeerHeight = calculateMaxPeerHeight();
        long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);
        SwingUtilities.invokeLater(() -> {
            updateLatestBlock(block, maxPeerHeight, blockTime);

            // Start the GUI timer only once, when the first block is pushed,
            // and if experimental features are enabled in the config.
            if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL) && !guiTimerStarted.getAndSet(true)) {
                startGuiTimer();
            }
        });
    }

    private void onBlockPopped() {
        Block lastBlock = Signum.getBlockchain().getLastBlock();
        int maxPeerHeight = calculateMaxPeerHeight();
        long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);
        SwingUtilities.invokeLater(() -> {
            updateLatestBlock(lastBlock, maxPeerHeight, blockTime);
        });
    }

    private void onManualPopOffProgress() {
        int remaining = getNodeContext().getBlockchainProcessor().getManualPopOffBlocksCount();
        int blockHeight = getNodeContext().getBlockchainProcessor().getBeforeRollbackHeight();
        int targetHeight = getNodeContext().getBlockchainProcessor().getManualLastPopOffHeight();
        SwingUtilities.invokeLater(() -> {
            Color textColor = remaining > 0 ? GuiColors.getSaved() : GuiColors.getApplied();
            popOffBlockCountLabel.setText("Pop off blocks: " + remaining);
            popOffBlockHeightLabel.updateValues(String.valueOf(targetHeight < 0 ? "-" : targetHeight),
                    FontAwesome.ARROW_LEFT, String.valueOf(blockHeight), textColor, textColor);
            if (remaining > 0) {
                popOffBlockCountLabel.setForeground(textColor);
                popOffBlockHeightLabel.setAllColors(textColor);
            } else {
                popOffBlockCountLabel.setForeground(GuiColors.getApplied());
                popOffBlockHeightLabel.setAllColors(GuiColors.getApplied());
            }
            setPopOffLabelVisible(remaining > 0);
        });
    }

    private void onAutoPopOffProgress() {
        int remaining = getNodeContext().getBlockchainProcessor().getAutoPopOffBlocksCount();
        int blockHeight = getNodeContext().getBlockchainProcessor().getBeforeRollbackHeight();
        int targetHeight = getNodeContext().getBlockchainProcessor().getAutoLastPopOffHeight();
        SwingUtilities.invokeLater(() -> {
            Color textColor;
            if (getNodeContext().getBlockchainProcessor().getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE) {
                textColor = GuiColors.getContrastRed();
            } else {
                textColor = GuiColors.getSaved();
            }
            if (remaining == 0) {
                textColor = GuiColors.getApplied();
            }

            popOffBlockCountLabel.setText("Pop off blocks: " + remaining);
            popOffBlockCountLabel.setForeground(textColor);

            popOffBlockHeightLabel.updateValues(String.valueOf(targetHeight < 0 ? "-" : targetHeight),
                    FontAwesome.ARROW_LEFT, String.valueOf(blockHeight), textColor, textColor);
            popOffBlockHeightLabel.setAllColors(textColor);
            setPopOffLabelVisible(remaining > 0);
        });
    }

    private void setPopOffLabelVisible(boolean isVisible) {
        popOffSeparator1.setVisible(isVisible);
        popOffBlockCountLabel.setVisible(isVisible);
        popOffSeparator2.setVisible(isVisible);
        popOffBlockHeightLabel.setVisible(isVisible);
    }

    private void onSyncStateChanged(Boolean isPaused) {
        SwingUtilities.invokeLater(() -> {
            if (isSyncStopped == isPaused) {
                return; // No change
            }
            isSyncStopped = isPaused;
            if (isSyncStopped) {
                syncButton.setText("Resume Sync");
                syncButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY, GuiConstants.getToolBarIconSize(),
                        GuiColors.getButtonIcon()));
                if (guiTimer != null) {
                    guiTimer.stop();
                }
            } else {
                syncButton.setText("Pause Sync");
                syncButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PAUSE, GuiConstants.getToolBarIconSize(),
                        GuiColors.getButtonIcon()));
                if (guiTimer != null) {
                    guiTimer.start();
                }
            }
            // updateTitle() removed - title management moved to NodeInfoBar
        });
    }

    public void startSignumWithGUI() {
        try {
            // signum.init();
            Signum.main(args);
            loadGuiSettings();
            
            // Now that properties are loaded, set the correct values for the GUI
            showPopOff = Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL);
            measurementActive = Signum.getPropertyService().getBoolean(Props.MEASUREMENT_ACTIVE);
            experimentalActive = Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL);
            String archivalMode = Signum.getPropertyService().getString(Props.DB_ARCHIVAL_MODE).toUpperCase();
            boolean isPruneMode = "PRUNE".equals(archivalMode);
            boolean isTrimMode = "TRIM".equals(archivalMode);
            trimEnabled = isTrimMode || isPruneMode;

            String shortTooltip = "Database Mode: " + archivalMode;
            String detailedTooltip = "Database Archival Mode: " + archivalMode + "\n\n";

            if (isPruneMode) {
                shortTooltip += " - Physical pruning is active (blocks deleted).";
                detailedTooltip += "Physical pruning is active. Blocks and associated data older than the rollback limit ("
                        + application.module.node.Constants.MAX_ROLLBACK
                        + " blocks) are permanently deleted from the database to save disk space.\n\n"
                        + "Maintenance occurs automatically every " + application.module.node.Constants.TRIM_PERIOD
                        + " blocks.";
            } else if (isTrimMode) {
                shortTooltip += " - Table trimming is active (history cleaned).";
                detailedTooltip += "Table trimming is active. Only temporary derived table history is periodically cleaned to maintain performance. All blocks and transactions are kept.\n\n"
                        + "Trimming occurs automatically every " + application.module.node.Constants.TRIM_PERIOD
                        + " blocks.";
            } else {
                shortTooltip += " - Full history preserved.";
                detailedTooltip += "Automatic database maintenance is disabled. No trimming or pruning is performed.\n\n"
                        + "The entire blockchain history is stored locally. This node acts as an archival node.";
            }
            detailedTooltip += "\n\nControlled by property: DB.ArchivalMode";

            trimLabel.setToolTipText(shortTooltip);
            addInfoTooltip(trimLabel, detailedTooltip, archivalMode);

            autoResolveEnabled = Signum.getPropertyService().getBoolean(Props.AUTO_CONSISTENCY_RESOLVE_ENABLED);

            Block lastBlock = Signum.getBlockchain().getLastBlock();
            int maxPeerHeight = calculateMaxPeerHeight();
            BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
            Collection<Peer> allPeers = blockchainProcessor.getAllPeers();
            long connectedCount = allPeers.stream().filter(p -> p.getState() == Peer.State.CONNECTED).count();
            long allKnownCount = allPeers.size();
            long blacklistedCount = allPeers.stream().filter(Peer::isBlacklisted).count();
            long blockTime = Signum.getFluxCapacitor().getValue(FluxValues.BLOCK_TIME);

            try {
                SwingUtilities.invokeLater(() -> {
                    if (showCommandItem != null) {
                        showCommandItem.setSelected(showCommandInput);
                    }
                    if (showCommandInput) {
                        commandPanelWrapper.add(commandPanel, BorderLayout.CENTER);
                    } else {
                        commandPanelWrapper.setPreferredSize(new Dimension(0, 0));
                    }

                    if (showMetricsItem != null) {
                        showMetricsItem.setSelected(showMetricsPanel);
                    }

                    if (skipDbCheckItem != null && blockchainProcessor != null) {
                        skipDbCheckItem.setSelected(blockchainProcessor.isSkipDbCheckOnManualPopOff());
                    }

                    if (showMetricsPanel) {
                        metricsPanel.init();
                        metricsPanel.setVisible(true);
                    } else {
                        metricsPanel.shutdown();
                        metricsPanelWrapper.removeAll();
                        metricsPanel = null;
                        metricsPanelWrapper.setPreferredSize(new Dimension(0, 0));
                        metricsPanelWrapper.revalidate();
                    }

                    showTrayIcon();
                    // Sync checkbox states with loaded properties
                    updatePopOffToggleIcon();

                    if (showPopOff) {
                        popOffButtonsPanel.setVisible(true);
                        popOffButtonsPanel.setPreferredSize(null);
                    } else {
                        Dimension natural = popOffButtonsPanel.getLayout().preferredLayoutSize(popOffButtonsPanel);
                        popOffButtonsPanel.setPreferredSize(new Dimension(0, Math.max(natural.height, 25)));
                        popOffButtonsPanel.setVisible(false);
                    }
                    metricsPanelWrapper.setMinimumSize(new Dimension(0, 0)); // Initial state shrinkable
                    toolBar.revalidate();

                    if (measurementActive) {
                        measurementLabel.setVisible(true);
                        measurementSeparator.setVisible(true);
                    }

                    if (experimentalActive) {
                        experimentalLabel.setVisible(true);
                        experimentalSeparator.setVisible(true);
                        // Initial time label visibility is handled by updateTimeLabelVisibility later
                        syncInProgressTimeLabel.setVisible(true);
                        timeSeparator.setVisible(true);
                    }

                    // Scissors icon is always visible to show database mode status
                    trimLabel.setVisible(trimEnabled);
                    trimIconSeparator.setVisible(trimEnabled);

                    // Maintenance labels are only visible if TRIM or PRUNE is active
                    trimHeightLabel.setVisible(isTrimMode);
                    trimSeparator.setVisible(isTrimMode);
                    pruneHeightLabel.setVisible(isPruneMode);
                    pruneSeparator.setVisible(isPruneMode);

                    if (autoResolveEnabled) {
                        autoResolveLabel.setVisible(true);
                        autoResolveSeparator.setVisible(true);
                    } else {
                        autoResolveLabel.setVisible(false);
                        autoResolveSeparator.setVisible(false);
                    }

                    onTrimEnd(blockchainProcessor.getCurrentTrimHeight().get());
                    onPruneEnd(blockchainProcessor.getCurrentPruneHeight().get());
                    onConsistencyUpdate();
                    onManualPopOffProgress();
                    onAutoPopOffProgress();

                    // configurationPanel.loadAppliedProperties();

                    updateLatestBlock(lastBlock, maxPeerHeight, blockTime);
                    updatePeerCount(connectedCount, allKnownCount, blacklistedCount);

                    syncButton.setEnabled(true);
                    dbCheckButton.setEnabled(true);
                    popOff10Button.setEnabled(true);
                    popOff100Button.setEnabled(true);
                });

                // updateTitle() removed - title management moved to NodeInfoBar

                initListeners();
                if (Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL)) {
                    // Initialize timers from the log file.
                    if (blockchainProcessor != null) {
                        this.guiAccumulatedSyncTimeMs = blockchainProcessor.getAccumulatedSyncTimeMs();
                        this.guiAccumulatedSyncInProgressTimeMs = blockchainProcessor
                                .getAccumulatedSyncInProgressTimeMs();
                    }
                    // Update labels with initial values from log file
                    SwingUtilities.invokeLater(() -> {
                        totalTimeLabel.setText(DurationFormatter.format(guiAccumulatedSyncTimeMs,
                                DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));
                        syncInProgressTimeLabel
                                .setText(DurationFormatter.format(guiAccumulatedSyncInProgressTimeMs,
                                        DurationFormatter.Unit.YEAR, DurationFormatter.Unit.SECOND));
                        updateTimeLabelVisibility(); // Initial visibility check
                    });
                }
                if (Signum.getBlockchain() == null) {
                    onBrsStopped();
                }
            } catch (Exception t) {
                LOGGER.error("Could not determine if running in testnet mode", t);
            }
            } catch (Exception t) {
                showMessage(FAILED_TO_START_MESSAGE);
                onBrsStopped();
                // Even if node startup failed, still load GUI settings and apply panel visibility state
                // so the hamburger menu checkbox state is consistent with actual UI behavior.
                try {
                    loadGuiSettings();
                    } catch (Exception loadEx) {
                    LOGGER.warn("Could not load GUI settings during fallback", loadEx);
                }
                SwingUtilities.invokeLater(() -> {
                    // Apply panel visibility state first (before tray icon) to ensure MetricsPanel shows correctly
                    // even if tray icon creation fails (e.g., parentFrame is null in multi-profile mode).
                    applyPanelVisibilityState();
                    try {
                        showTrayIcon();
                    } catch (Exception e) {
                        LOGGER.warn("Failed to show tray icon during fallback (non-fatal)", e);
                    }
                });
            }

    }

    /**
     * Applies panel visibility state based on loaded GUI settings.
     * 
     * IMPORTANT: This method is called during initConsoleUI() BEFORE the node starts,
     * so PropertyService is not yet available. Therefore it ONLY syncs checkbox states
     * with loaded preferences — it does NOT call metricsPanel.init() which requires
     * PropertyService. The actual MetricsPanel initialization/destruction is deferred
     * to startSignumWithGUI() where PropertyService is guaranteed to be available.
     */
    private void applyPanelVisibilityState() {
        
        // Sync checkbox states with loaded settings (does NOT require PropertyService)
        if (showCommandItem != null) {
            showCommandItem.setSelected(showCommandInput);
        }
        if (showMetricsItem != null) {
            showMetricsItem.setSelected(showMetricsPanel);
        }

        // Do NOT init/shutdown MetricsPanel here — it needs PropertyService which is not
        // available during init. The actual panel visibility is handled by startSignumWithGUI().
        // Instead, just ensure the wrapper has a stable size so toolbar layout is correct.
        if (!showMetricsPanel) {
            metricsPanelWrapper.setPreferredSize(new Dimension(0, 0));
            metricsPanelWrapper.setMinimumSize(new Dimension(0, 0));
        }

        // Apply Command Panel visibility (legacy code paths are null-safe / deprecated)
        if (showCommandInput) {
            showCommandPanelInline();
        } else {
            hideCommandPanelInline();
        }

        // Revalidate the full content hierarchy (toolbar -> metricsPanelWrapper -> bottomPanel/commandPanelWrapper)
        // toolBar.revalidate() alone is insufficient because isValidateRoot barriers prevent layout propagation
        // to bottomPanel. We must explicitly revalidate contentPanel which contains all three regions.
        toolBar.revalidate();
        if (contentPanel != null) {
            contentPanel.revalidate();
            contentPanel.repaint();
        }
        if (mainCardPanel != null) {
            mainCardPanel.validate();
            mainCardPanel.repaint();
        }
    }

    private void updateMetricsPanelState(boolean show) {
        LOGGER.info("[DEBUG-MetricsPanel] ==== updateMetricsPanelState(show={}) CALLED ===== thread={}, showMetricsPanel={}, metricsPanel={}, animatorRunning={}",
            show, Thread.currentThread().getName(), showMetricsPanel, metricsPanel != null ? "exists" : "null",
            metricsPanelAnimator != null && metricsPanelAnimator.isRunning());

        if (metricsPanelAnimator != null && metricsPanelAnimator.isRunning()) {
            LOGGER.warn("[DEBUG-MetricsPanel] updateMetricsPanelState: ABORTING — animator already running!");
            return;
        }

        showMetricsPanel = show;
        LOGGER.info("[DEBUG-MetricsPanel] updateMetricsPanelState: showMetricsPanel={}", showMetricsPanel);
        if (show) {
            if (metricsPanel == null) {
                metricsPanel = new MetricsPanel(parentFrame);
                metricsPanel.init();
                metricsPanel.setVisible(true);
                metricsPanelWrapper.add(metricsPanel, BorderLayout.CENTER);
            }

            // Prepare for animation: Start from 0 height
            metricsPanelWrapper.setPreferredSize(new Dimension(metricsPanelWrapper.getWidth(), 0));
            metricsPanelWrapper.revalidate();

            // Calculate target height
            int targetHeight = metricsPanel.getPreferredSize().height;

            metricsPanelAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    // Ease out: 1 - (1 - t)^3
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3);

                    int h = (int) (targetHeight * progress);
                    metricsPanelWrapper.setPreferredSize(new Dimension(metricsPanelWrapper.getWidth(), h));
                    metricsPanelWrapper.revalidate();

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        metricsPanelWrapper.setPreferredSize(null); // Reset to allow dynamic resizing
                        metricsPanelWrapper.revalidate();
                    }
                }
            });
            metricsPanelAnimator.start();
        } else {
            if (metricsPanel != null) {
                final int startHeight = metricsPanelWrapper.getHeight();

                metricsPanelAnimator = new Timer(10, new ActionListener() {
                    final long startTime = System.currentTimeMillis();
                    final int duration = ANIMATION_DURATION_MS;

                    @Override
                    public void actionPerformed(ActionEvent e) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        float progress = Math.min(1.0f, (float) elapsed / duration);
                        // Ease out
                        progress = 1.0f - (float) Math.pow(1.0f - progress, 3);

                        int h = (int) (startHeight * (1.0f - progress));
                        metricsPanelWrapper.setPreferredSize(new Dimension(metricsPanelWrapper.getWidth(), h));
                        metricsPanelWrapper.revalidate();

                        if (progress >= 1.0f) {
                            ((Timer) e.getSource()).stop();
                            metricsPanel.shutdown();
                            metricsPanelWrapper.removeAll();
                            metricsPanel = null;
                            metricsPanelWrapper.setPreferredSize(new Dimension(0, 0));
                            metricsPanelWrapper.setMinimumSize(new Dimension(0, 0));
                            metricsPanelWrapper.revalidate();
                        }
                    }
                });
                metricsPanelAnimator.start();
            }
        }
    }

    private void updateTimeLabelVisibility() {
        if (!Signum.getPropertyService().getBoolean(Props.EXPERIMENTAL)) {
            totalTimeLabel.setVisible(false);
            innerTimeSeparator.setVisible(false);
            syncInProgressTimeLabel.setVisible(false);
            timeSeparator.setVisible(false);
            return;
        }
        boolean showTotalTime = guiAccumulatedSyncTimeMs != guiAccumulatedSyncInProgressTimeMs;
        totalTimeLabel.setVisible(showTotalTime);
        innerTimeSeparator.setVisible(showTotalTime);
    }

    /**
     * This method is called when the Signum service is restarted.
     * It re-initializes the GUI components and updates the state to reflect the new
     * service instances.
     */
    /*
     * public void reinitOnRestart() {
     * // Re-register listeners to the new service instances
     * initListeners();
     * 
     * // Manually update the UI with the current state after restart
     * updateTitle();
     * if (Signum.getBlockchain() != null) {
     * updateLatestBlock(Signum.getBlockchain().getLastBlock());
     * }
     * updatePeerCount(Peers.getAllPeers().size(), Peers.getActivePeers().size());
     * }
     */


    private void updateLatestBlock(Block block, int maxPeerHeight, long blockTime) {
        if (block == null) {
            return;
        }
        Date blockDate = Convert.fromEpochTime(block.getTimestamp());

        int missingBlocks;
        if (maxPeerHeight > 0) {
            // We have peers, use their height as the source of truth.
            missingBlocks = Math.max(0, maxPeerHeight - block.getHeight());
        } else {
            // No peers, fall back to time-based estimation.
            Date now = new Date();
            long secondsSinceLastBlock = (now.getTime() - blockDate.getTime()) / 1000;
            missingBlocks = secondsSinceLastBlock > 0 ? (int) (secondsSinceLastBlock / blockTime) : 0;
        }

        boolean isEffectivelySynced = missingBlocks == 0;

        elapsedTimeLabel.setVisible(isEffectivelySynced);
        elapsedTimeSeparator.setVisible(isEffectivelySynced);

        if (isEffectivelySynced) {
            elapsedTimeCounter = (System.currentTimeMillis() - blockDate.getTime()) / 1000;
            if (elapsedTimeCounter < 0) {
                elapsedTimeCounter = 0;
            }
            elapsedTimeLabel.setText("Elapsed Time: " + elapsedTimeCounter + "s");
        } else {
            elapsedTimeCounter = 0;
        }

        if (elapsedTimeTimer == null) {
            elapsedTimeTimer = new Timer(1000, e -> updateElapsedTime());
            elapsedTimeTimer.start();
        }
        latestBlockHeightLabel.setText("Latest block: " + block.getHeight());
        latestBlockTimestampLabel.setText("Timestamp: " + DATE_FORMAT.format(blockDate));

        // Start syncing if more than 10 block times behind, stop if 1 or less.
        // This is more reliable than peer height difference, especially at startup.
        if (!isSyncing && missingBlocks > 10) {
            isSyncing = true;
        } else if (isSyncing && missingBlocks <= 1) {
            isSyncing = false;
        }

        String tooltipText = "Synchronized";
        if (missingBlocks > 0) {
            tooltipText = "Estimated blocks behind: " + missingBlocks;
        }

        if (maxPeerHeight > block.getHeight()) {
            tooltipText = "Network Height: " + maxPeerHeight + " (Behind: " + (maxPeerHeight - block.getHeight()) + ")";
        } else if (maxPeerHeight > 0 && missingBlocks == 0) {
            tooltipText = "Synchronized (Network Height: " + maxPeerHeight + ")";
        }
        syncProgressBar.setToolTipText(tooltipText);

        float prog = 0;
        int totalBlocks = block.getHeight() + missingBlocks;
        if (totalBlocks > 0) {
            // Use 100.0f to force floating-point division, preserving decimal places
            prog = (float) block.getHeight() * 100.0f / totalBlocks;
        }

        if (prog > 100.0f) {
            prog = 100.0f;
        }
        syncProgressBar.setValue((int) prog);
        syncProgressBar.setString(String.format("%.2f %%", prog));
    }

    private int calculateMaxPeerHeight() {
        try {
            return getNodeContext().getBlockchainProcessor().getAllPeers().stream()
                    .filter(p -> p.getState() == Peer.State.CONNECTED)
                    .mapToInt(p -> (int) p.getHeight())
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateElapsedTime() {
        if (!elapsedTimeLabel.isVisible()) {
            return;
        }
        elapsedTimeCounter++;
        elapsedTimeLabel.setText("Elapsed Time: " + elapsedTimeCounter + "s");
    }

    private void addInfoTooltip(JComponent component, String text) {
        component.setToolTipText("Right-click for more info");
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title = "";
                    if (component instanceof JLabel) {
                        title = ((JLabel) component).getText();
                    } else if (component instanceof JButton) {
                        title = ((JButton) component).getText();
                    } else if (component instanceof DynamicArrowPanel) {
                        title = ((DynamicArrowPanel) component).leftTextLabel.getText();
                    }
                    showInfoDialog(title, text, 300);
                }
            }
        });
    }

    private void showInfoDialog(String title, String text, int width) {
        if (title.endsWith(":")) {
            title = title.substring(0, title.length() - 1);
        }
        String htmlText = "<html><body><p style='width: " + width + "px;'>" + text.replace("\n", "<br>")
                + "</p></body></html>";
        JOptionPane.showMessageDialog(this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Loads the autostart preference for this profile from gui-settings.json.
     * Returns false if no setting exists or on error.
     */
    private boolean loadAutostartSetting() {
        try {
            String settingsDir = Signum.getPropertyService().getString(Props.SETTINGS_DIR);
            Path settingsPath = application.utils.io.PathUtils
                    .resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
            if (Files.exists(settingsPath)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        JsonObject settings = parsed.getAsJsonObject();
                        // Profile-specific autostart key: "autostart.{profileName}"
                        String key = "autostart." + profileName;
                        if (settings.has(key)) {
                            return settings.get(key).getAsBoolean();
                        }
                        // Fallback to global autostart setting
                        if (settings.has("autostart")) {
                            return settings.get("autostart").getAsBoolean();
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load autostart setting for profile '{}'", profileName, e);
        }
        return false;
    }

    /**
     * Saves the autostart preference for this profile to gui-settings.json.
     */
    private void saveAutostartSetting(boolean value) {
        try {
            String settingsDir = Signum.getPropertyService().getString(Props.SETTINGS_DIR);
            Path settingsPath = application.utils.io.PathUtils
                    .resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
            if (settingsPath.getParent() != null) {
                Files.createDirectories(settingsPath.getParent());
            }
            JsonObject settings = new JsonObject();
            if (Files.exists(settingsPath)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        settings = parsed.getAsJsonObject();
                    }
                } catch (Exception e) {
                    // Use empty object on parse error
                }
            }
            // Profile-specific autostart key
            String key = "autostart." + profileName;
            settings.addProperty(key, value);
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save autostart setting for profile '{}'", profileName, e);
        }
    }

    /**
     * Returns the profile-specific JSON key for a given setting name,
     * or null if no profile name is configured.
     */
    private String profileKey(String baseKey) {
        return (profileName != null && !profileName.isEmpty()) ? baseKey + "." + profileName : null;
    }

    /**
     * Resolves the settings directory for gui-settings.json.
     * Tries PropertyService first (node running), falls back to default "settings" directory.
     * This allows GUI settings to load even before the node starts (multi-profile mode).
     */
    private String resolveSettingsDir() {
        try {
            PropertyService ps = Signum.getPropertyService();
            if (ps != null) {
                return ps.getString(Props.SETTINGS_DIR);
            }
        } catch (Exception e) {
            // PropertyService not available yet
        }
        // Default fallback: settings directory next to working directory
        return "settings";
    }

    // ── Hierarchical GUI Settings Helpers ──────────────────────────────────────
    // Format: { "node": { "{profileName}": { "showMetricsPanel": bool, ... } } }

    private static final String GUI_MODULE_KEY = "node";

    /**
     * Safely reads a boolean from a JsonObject, returning defaultValue if missing or invalid.
     */
    private static boolean getBool(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key)) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Gets or creates the nested JsonObject for this module+profile: root["node"][profileName].
     */
    private JsonObject getOrCreateNodeProfileSection(JsonObject root) {
        if (!root.has(GUI_MODULE_KEY)) {
            root.add(GUI_MODULE_KEY, new JsonObject());
        }
        JsonObject moduleObj = root.getAsJsonObject(GUI_MODULE_KEY);
        if (!moduleObj.has(profileName)) {
            moduleObj.add(profileName, new JsonObject());
        }
        return moduleObj.getAsJsonObject(profileName);
    }

    /**
     * Returns the nested JsonObject for this module+profile if it exists, null otherwise.
     */
    private JsonObject getNodeProfileSectionIfExists(JsonObject root) {
        if (root == null || !root.has(GUI_MODULE_KEY)) {
            return null;
        }
        JsonObject moduleObj = root.getAsJsonObject(GUI_MODULE_KEY);
        if (!moduleObj.has(profileName)) {
            return null;
        }
        return moduleObj.getAsJsonObject(profileName);
    }

    /**
     * Loads all GUI panel states from the hierarchical section: node.{profileName}.
     * If the settings file does not exist yet, creates it with default values
     * (including autostart=false for this profile) so subsequent reads find it.
     */
    private void loadGuiSettings() {
        boolean prevShowCommandInput = showCommandInput;
        boolean prevShowMetricsPanel = showMetricsPanel;
        boolean prevMetricsExpanded = metricsExpanded;
        boolean prevCommandAtBottom = commandPanelAtBottom;
        boolean fileExisted = false;

        try {
            String settingsDir = resolveSettingsDir();
            Path settingsPath = application.utils.io.PathUtils
                    .resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
            if (Files.exists(settingsPath)) {
                fileExisted = true;
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        JsonObject profileSection = getNodeProfileSectionIfExists(parsed.getAsJsonObject());
                        if (profileSection != null) {
                            showMetricsPanel = getBool(profileSection, "showMetricsPanel", showMetricsPanel);
                            metricsExpanded = getBool(profileSection, "metricsExpanded", metricsExpanded);
                            showCommandInput = getBool(profileSection, "showCommandInput", showCommandInput);
                            commandPanelAtBottom = getBool(profileSection, "commandPanelAtBottom", commandPanelAtBottom);
                        }
                    }
                }
            }

            // If file did NOT exist, create it with default values for this profile.
            // This ensures gui-settings.json is always present after first load,
            // and includes autostart=false so nodes don't start automatically by default.
            if (!fileExisted) {
                createDefaultGuiSettings(settingsDir);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load GUI settings for profile '{}'", profileName, e);
        }

        LOGGER.info("[GUI-Settings] profile={}, showMetricsPanel: {}->{}, metricsExpanded: {}->{}, showCommandInput: {}->{}, commandPanelAtBottom: {}->{}",
                profileName, prevShowMetricsPanel, showMetricsPanel,
                prevMetricsExpanded, metricsExpanded,
                prevShowCommandInput, showCommandInput,
                prevCommandAtBottom, commandPanelAtBottom);
    }

    /**
     * Creates the gui-settings.json file with default values for this profile.
     * Includes autostart=false so nodes do NOT start automatically by default.
     */
    private void createDefaultGuiSettings(String settingsDir) {
        try {
            Path settingsPath = application.utils.io.PathUtils
                    .resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
            if (settingsPath.getParent() != null) {
                Files.createDirectories(settingsPath.getParent());
            }

            JsonObject settings = new JsonObject();

            // Profile-specific autostart: disabled by default
            String autoKey = "autostart." + profileName;
            settings.addProperty(autoKey, false);

            // Hierarchical node section with defaults
            JsonObject profileSection = getOrCreateNodeProfileSection(settings);
            profileSection.addProperty("showMetricsPanel", false);
            profileSection.addProperty("metricsExpanded", false);
            profileSection.addProperty("showCommandInput", false);
            profileSection.addProperty("commandPanelAtBottom", true);

            try (java.io.BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }

            LOGGER.info("[GUI-Settings] Created default gui-settings.json for profile '{}'", profileName);
        } catch (Exception e) {
            LOGGER.warn("Failed to create default GUI settings for profile '{}'", profileName, e);
        }
    }

    /**
     * Saves all GUI panel states to the hierarchical section: node.{profileName}.
     * Reads the actual MetricsPanel expansion state at save time (shutdown/restart).
     * Called by LifecycleListener.onShutdownRequested() during centralized shutdown.
     */
    public void saveGuiSettings() {
        LOGGER.info("[GUI-Settings] ==== saveGuiSettings CALLED ===== profile={}, showMetricsPanel={}, metricsExpanded={}",
                profileName, showMetricsPanel, metricsExpanded);
        try {
            String settingsDir = resolveSettingsDir();
            Path settingsPath = application.utils.io.PathUtils
                    .resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
            if (settingsPath.getParent() != null) {
                Files.createDirectories(settingsPath.getParent());
            }
            JsonObject settings = new JsonObject();
            if (Files.exists(settingsPath)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        settings = parsed.getAsJsonObject();
                    }
                } catch (Exception e) {
                    // Use empty object on parse error
                }
            }

            // Write to hierarchical section: node.{profileName}
            JsonObject profileSection = getOrCreateNodeProfileSection(settings);
            // Read actual expansion state from MetricsPanel at save time
            boolean expandedState = metricsPanel != null ? metricsPanel.isExpanded() : metricsExpanded;
            profileSection.addProperty("showMetricsPanel", showMetricsPanel);
            profileSection.addProperty("metricsExpanded", expandedState);
            profileSection.addProperty("showCommandInput", showCommandInput);
            profileSection.addProperty("commandPanelAtBottom", commandPanelAtBottom);

            try (java.io.BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }
            LOGGER.info("[GUI-Settings] Saved to gui-settings.json for profile '{}'", profileName);
        } catch (Exception e) {
            LOGGER.error("Failed to save GUI settings for profile '{}'", profileName, e);
        }
    }

    private void updatePeerCount(long connectedCount, long allKnownCount, long blacklistedCount) {
        // The label previously for 'connected' now shows 'active' peers.
        connectedPeersLabel.setText(String.valueOf(connectedCount));
        peersCountLabel.setText(String.valueOf(allKnownCount));
        blacklistedPeersLabel.setText(blacklistedCount + "");
    }

    private String formatDataSize(double bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = { "B", "KB", "MB", "GB", "TB", "PB", "EB" };
        int unitIndex = 0;
        while (bytes >= 1024 && unitIndex < units.length - 1) {
            bytes /= 1024;
            unitIndex++;
        }
        return String.format("%.2f %s", bytes, units[unitIndex]);
    }

    private void onBrsStopped() {
        // Title management has been moved to NodeInfoBar in multi-profile mode.
        // Only update tray icon tooltip if it exists.
        if (trayIcon != null) {
            String currentTip = trayIcon.getToolTip();
            if (currentTip != null && !currentTip.endsWith(" (STOPPED)")) {
                trayIcon.setToolTip(currentTip + " (STOPPED)");
            }
        }
    }

    private void showMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            System.err.println("Showing message: " + message);
            JOptionPane.showMessageDialog(this, message, "Signum Message", JOptionPane.ERROR_MESSAGE);
        });
    }

    /**
     * Push-based OutputStream that renders stdout/stderr to a Swing JTextPane.
     * <p>
     * Replaced the old polling Timer (500ms interval) with a coalesced push model:
     * each {@code write()} schedules at most one EDT flush via {@link SwingUtilities#invokeLater}.
     * The {@link AtomicBoolean} flag ensures multiple rapid writes produce a single UI update,
     * eliminating unnecessary document mutations and spurious scroll-jumps.
     * </p>
     */
    private static class TextAreaOutputStream extends OutputStream {
        /** Smart scroll threshold: only auto-scroll when user is at or above 90% of scroll range */
        private static final double SMART_SCROLL_THRESHOLD = 0.9;

        private final JTextPane textPane;
        private final JScrollPane scrollPane;
        private final PrintStream actualOutput;
        private final StringBuilder buffer = new StringBuilder();
        /** Coalescing guard: ensures only one pending flush is scheduled per write burst */
        private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
        private final boolean isError;

        private TextAreaOutputStream(JTextPane textPane, PrintStream actualOutput, boolean isError) {
            this(textPane, null, actualOutput, isError);
        }

        private TextAreaOutputStream(JTextPane textPane, JScrollPane scrollPane, PrintStream actualOutput, boolean isError) {
            this.textPane = textPane;
            this.scrollPane = scrollPane;
            this.actualOutput = actualOutput;
            this.isError = isError;
        }

        @Override
        public void write(int b) {
            writeString(new String(new byte[] { (byte) b }, StandardCharsets.UTF_8));
        }

        @Override
        public void write(byte[] b) {
            writeString(new String(b, StandardCharsets.UTF_8));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writeString(new String(b, off, len, StandardCharsets.UTF_8));
        }

        private synchronized void writeString(String string) {
            if (actualOutput != null) {
                actualOutput.print(string);
            }
            buffer.append(string);
            // Push-based: schedule flush on EDT (coalesced – only one pending at a time)
            scheduleFlush();
        }

        /**
         * Schedules a single buffered flush on the EDT.
         * Uses CAS to coalesce multiple calls into one pending update.
         */
        private void scheduleFlush() {
            if (flushScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::doFlush);
            }
        }

        /** Drains the buffer and updates the UI (runs on EDT). Resets the coalescing flag. */
        private synchronized void doFlush() {
            try {
                if (buffer.length() == 0) {
                    return; // Nothing to render
                }
                String text = buffer.toString();
                buffer.setLength(0);
                processAppend(text);
            } finally {
                flushScheduled.set(false); // Allow next write burst to schedule again
            }
        }

        @Override
        public void flush() {
            // Synchronous flush: ensure all pending buffered data is rendered.
            // If a flush is already pending on EDT, it will drain; otherwise schedule one.
            scheduleFlush();
        }

        /** Directly appends text to the internal buffer (used by legacy bootstrap flush). */
        private synchronized void append(String text) {
            buffer.append(text);
        }

        private void processAppend(String text) {
            // Already running on EDT via invokeLater, but double-check for safety
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> processAppend(text));
                return;
            }

            StyledDocument doc = textPane.getStyledDocument();
            int docLengthBefore = doc.getLength();
            String[] lines = text.split("(?<=\\n)");

            for (String line : lines) {
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                Color color = null;

                if (line.contains("ERROR") || line.contains("SEVERE")) {
                    color = new Color(255, 100, 100);
                } else if (line.contains("WARN") || line.contains("WARNING")) {
                    color = new Color(255, 200, 100);
                } else if (line.contains("TRACE") || line.contains("FINER") || line.contains("FINEST")) {
                    color = new Color(150, 150, 150);
                } else if (line.contains("DEBUG") || line.contains("FINE")) {
                    color = new Color(180, 180, 180);
                } else if (line.contains("CONFIG")) {
                    color = new Color(100, 200, 200);
                } else if (isError && !line.contains("INFO")) {
                    color = new Color(255, 100, 100);
                }

                // Apply the active custom font to new lines being appended to the console
                Font activeFont = AppearanceModule.getActiveConsoleFont();
                if (activeFont != null) {
                    StyleConstants.setFontFamily(attrs, activeFont.getFamily());
                    StyleConstants.setFontSize(attrs, activeFont.getSize());
                }

                if (color != null) {
                    StyleConstants.setForeground(attrs, color);
                }

                try {
                    doc.insertString(doc.getLength(), line, attrs);
                } catch (BadLocationException e) {
                    // ignore
                }
            }

            // Enforce max line count by trimming oldest lines
            Element root = doc.getDefaultRootElement();
            while (root.getElementCount() > OUTPUT_MAX_LINES) {
                try {
                    Element firstLine = root.getElement(0);
                    doc.remove(0, firstLine.getEndOffset());
                } catch (BadLocationException e) {
                    break;
                }
            }

            // Smart scroll: only adjust scrollbar if content actually changed.
            // This prevents false scroll jumps when the buffer was empty.
            if (doc.getLength() > docLengthBefore) {
                maybeScrollToEnd(textPane, scrollPane);
            }
        }

        /**
         * Scrolls the console to the end only if the user is already viewing near the bottom.
         * Similar to VS Code terminal: new content auto-scrolls when at the bottom,
         * but does not jump when the user has scrolled up to read older content.
         */
        private static void maybeScrollToEnd(JTextPane tp, JScrollPane pane) {
            if (pane == null) {
                LOGGER.info("[ScrollDebug] TextAreaOutputStream.maybeScrollToEnd: pane=null, returning early");
                return;
            }
            javax.swing.JScrollBar verticalBar = pane.getVerticalScrollBar();
            if (verticalBar == null) {
                LOGGER.info("[ScrollDebug] TextAreaOutputStream.maybeScrollToEnd: verticalBar=null, returning early");
                return;
            }
            int max = verticalBar.getMaximum();
            int extent = verticalBar.getVisibleAmount();
            int current = verticalBar.getValue();
            int scrollableRange = max - extent;
            
            double ratioForLog = scrollableRange > 0 ? (double) current / scrollableRange : Double.NaN;
            LOGGER.info("[ScrollDebug] TextAreaOutputStream.maybeScrollToEnd: max={}, extent={}, current={}, scrollableRange={}, ratio={}, threshold={}", 
                max, extent, current, scrollableRange, ratioForLog, SMART_SCROLL_THRESHOLD);
            
            if (scrollableRange <= 0) {
                LOGGER.info("[ScrollDebug] TextAreaOutputStream.maybeScrollToEnd: scrollableRange<=0, no scroll needed");
                return;
            }
            double positionRatio = (double) current / scrollableRange;
            if (positionRatio >= SMART_SCROLL_THRESHOLD) {
                LOGGER.info("[ScrollDebug] TextAreaOutputStream.maybeScrollToEnd: WILL SCROLL - ratio={} >= threshold={}", positionRatio, SMART_SCROLL_THRESHOLD);
                verticalBar.setValue(max);
            } else {
                LOGGER.info("[ScrollDebug] TextAreaOutputStream.maybeScrollToEnd: NO SCROLL - ratio={} < threshold={} (user reading old logs)", positionRatio, SMART_SCROLL_THRESHOLD);
            }
        }
    }

    private void styleMenuComponent(JComponent comp) {
        comp.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // Add internal padding for a "row" feel and proper height
        comp.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));

        if (comp instanceof AbstractButton) {
            AbstractButton button = (AbstractButton) comp;
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.setOpaque(false);
            button.setHorizontalAlignment(SwingConstants.LEFT);
        }
        comp.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                comp.setOpaque(true);
                if (comp instanceof AbstractButton) {
                    ((AbstractButton) comp).setContentAreaFilled(true);
                }
                comp.setBackground(UIManager.getColor("List.selectionBackground"));
                comp.setForeground(UIManager.getColor("List.selectionForeground"));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // Only reset if we're actually outside the component, not just moving between
                // child elements
                if (comp.contains(e.getPoint())) {
                    return;
                }
                comp.setOpaque(false);
                if (comp instanceof AbstractButton) {
                    ((AbstractButton) comp).setContentAreaFilled(false);
                }
                // Restore default colors
                comp.setBackground(null);
                comp.setForeground(UIManager.getColor("Label.foreground"));
            }
        });
    }

    // Removed deprecated SignaGUISecurityManager (Java 17+)

    /**
     * Unified exit method replacing SecurityManager-based exit interception.
     * Use this instead of System.exit() for graceful shutdown from the GUI.
     */
    private void safeExit(int status) {
        try {
            // Place any confirmation dialogs or cleanup here if needed.
        } finally {
            System.exit(status);
        }
    }

    /**
     * A custom JPanel that displays a left text, a FontAwesome icon, and a right
     * text.
     * Used for consistent "number icon number" formatting in the bottom panel.
     */
    private static class DynamicArrowPanel extends JPanel {
        private final JLabel leftTextLabel = new JLabel("-");
        private final JLabel iconLabel = new JLabel();
        private final JLabel rightTextLabel = new JLabel("-");
        private FontAwesome currentIcon = null;
        private Color currentIconColor = null;

        public DynamicArrowPanel() {
            super(new FlowLayout(FlowLayout.LEFT, 2, 0));
            setOpaque(false);
            add(leftTextLabel);
            add(iconLabel);
            add(rightTextLabel);
            updateUI();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (leftTextLabel != null) {
                GuiFontManager.applyDefaultFont(leftTextLabel);
                GuiFontManager.applyDefaultFont(rightTextLabel);
                if (currentIcon != null) {
                    iconLabel.setIcon(IconFontSwing.buildIcon(currentIcon, GuiConstants.getHelpIconSize(),
                            currentIconColor != null ? currentIconColor : UIManager.getColor("Label.foreground")));
                }
            }
        }

        public void updateValues(String leftText, FontAwesome icon, String rightText, Color textColor,
                Color iconColor) {
            this.currentIcon = icon;
            this.currentIconColor = iconColor;

            leftTextLabel.setText(leftText + (icon != null ? " " : ""));
            rightTextLabel.setText((icon != null ? " " : "") + rightText);

            Color actualTextColor = (textColor != null) ? textColor : UIManager.getColor("Label.foreground");
            Color actualIconColor = (iconColor != null) ? iconColor : GuiColors.getButtonIcon();

            leftTextLabel.setForeground(actualTextColor);
            rightTextLabel.setForeground(actualTextColor);

            if (icon != null) {
                iconLabel.setIcon(IconFontSwing.buildIcon(icon, GuiConstants.getHelpIconSize(), actualIconColor));
                iconLabel.setVisible(true);
            } else {
                iconLabel.setIcon(null);
                iconLabel.setVisible(false);
            }
        }

        public void setAllColors(Color color) {
            leftTextLabel.setForeground(color);
            rightTextLabel.setForeground(color);
            if (currentIcon != null) {
                iconLabel.setIcon(IconFontSwing.buildIcon(currentIcon, GuiConstants.getHelpIconSize(), color));
                currentIconColor = color;
            }
        }
    }

    // ── Per-Profile Logging Integration ────────────────────────────────────────

    /**
     * Initializes per-profile log routing for this console panel.
     * Uses ProfileLogger from NodeProfile architecture (new logger system).
     * The ProfileLogger forwards events to SystemLogger automatically,
     * and the ProfileConsoleSubscriber renders them in the given JTextPane.
     *
     * @param textPane   the target JTextPane for log display
     * @param scrollPane the parent JScrollPane for smart auto-scroll behavior (null to disable)
     */
    private void initProfileLogging(JTextPane textPane, JScrollPane scrollPane) {
        // Load or create NodeProfile for this profile name and get its ProfileLogger.
        // The ProfileLogger is part of the new centralized logger architecture:
        //   SLF4J/Logback → ProfileLogger → (forward to SystemLogger) + GUI subscribers
        NodeProfile nodeProfile = NodeProfile.loadByName(profileName);
        if (nodeProfile == null) {
            // Fallback: create a new profile if file doesn't exist yet
            nodeProfile = new NodeProfile(profileName);
        }

        profileLogger = nodeProfile.getLogger();

        // Create subscriber that renders log events to the console JTextPane
        consoleSubscriber = new ProfileConsoleSubscriber(profileName, textPane.getStyledDocument());
        // Enable smart auto-scroll: only scroll when user is near the bottom
        if (scrollPane != null) {
            consoleSubscriber.setScrollPane(scrollPane);
        }
        // Subscribe directly to ProfileLogger instead of legacy ProfileLogContext
        profileLogger.addSubscriber(consoleSubscriber);
    }

    /**
     * Flushes legacy static bootstrap logs to the console for backward compatibility.
     * <p>
     * Old code may have written directly to {@code Signum.BOOTSTRAP_LOGS} before the
     * new ProfileLogRouter was installed. This method renders those leftover entries
     * so the user does not lose early startup output.
     * </p>
     * <p>
     * Note: The modern buffering mechanism ({@link application.utils.logging.BootstrapLogBuffer})
     * is managed at the {@link application.utils.logging.ProfileLogRouter} level and flushes
     * automatically; this method only handles the deprecated static list.
     * </p>
     *
     * @param textPane the target JTextPane, never null
     */
    private void flushLegacyBootstrapLogs(JTextPane textPane) {
        // Flush legacy static bootstrap logs for backward compatibility.
        // BootstrapLogBuffer is managed at the ProfileLogRouter level and will be
        // flushed automatically when the router redistributes buffered entries.
        if (Signum.BOOTSTRAP_LOGS.isEmpty()) {
            return;
        }
        // Create output stream without timer (push-based model schedules flush on write)
        TextAreaOutputStream taos = new TextAreaOutputStream(textPane, textScrollPane, null, false);
        for (String line : Signum.BOOTSTRAP_LOGS) {
            taos.append(line + "\n");
        }
        // Trigger a final flush to render all buffered bootstrap lines
        taos.flush();
        Signum.BOOTSTRAP_LOGS.clear();
    }

    /**
     * Cleans up resources when this panel is removed from its parent container.
     * Delegates to UnifiedConsolePanel for proper disposal of subscriber + smart scroll controller.
     */
    @Override
    public void removeNotify() {
        // Delegate cleanup to unifiedConsole (disposes subscriber, detaches scroll controller)
        if (unifiedConsole != null) {
            unifiedConsole.dispose();
        }
        /* ── LEGACY CODE COMMENTED OUT ──
        if (consoleSubscriber != null && profileLogger != null) {
            profileLogger.removeSubscriber(consoleSubscriber);
        }
        if (consoleSubscriber != null) {
            consoleSubscriber.dispose();
            consoleSubscriber = null;
        }
        profileLogger = null;
        ── LEGACY CODE END ── */
        super.removeNotify();
    }

    /**
     * Handles node lifecycle state changes forwarded from NodeProfilePanel.
     * <p>
     * This is the single point of truth for synchronizing MetricsPanel visibility
     * with the node lifecycle. When the node reaches READY or RUNNING state, this
     * method ensures the MetricsPanel reflects the user's persisted preference
     * ({@link #showMetricsPanel}), guaranteeing that the hamburger menu checkbox
     * and the actual panel visibility are always consistent.
     * </p>
     *
     * @param oldState the previous lifecycle state
     * @param newState the current lifecycle state
     */
    public void onNodeStateChanged(NodeLifecycleState oldState, NodeLifecycleState newState) {
        LOGGER.debug("[MetricsPanel] Lifecycle state change: {} -> {}", oldState, newState);

        SwingUtilities.invokeLater(() -> {
            // When node becomes READY/RUNNING, ensure MetricsPanel visibility matches user preference
            if (newState == NodeLifecycleState.RUNNING || newState == NodeLifecycleState.READY) {
                LOGGER.info("[MetricsPanel] Node reached {} — applying visibility preference: showMetricsPanel={}", 
                    newState, showMetricsPanel);
                
                if (showMetricsPanel) {
                    // Ensure the MetricsPanel is initialized and visible
                    if (metricsPanel == null || !metricsPanel.isVisible()) {
                        LOGGER.info("[MetricsPanel] Initializing MetricsPanel for node state {}", newState);
                        updateMetricsPanelState(true);
                    } else {
                        LOGGER.debug("[MetricsPanel] MetricsPanel already active, no action needed");
                    }
                } else {
                    LOGGER.debug("[MetricsPanel] User preference is to hide MetricsPanel, keeping hidden");
                }

                // Sync checkbox state to ensure consistency — this is the authoritative source of truth
                if (showMetricsItem != null) {
                    boolean current = showMetricsItem.isSelected();
                    if (current != showMetricsPanel) {
                        LOGGER.warn("[MetricsPanel] Checkbox was inconsistent! Was={}, expected={} — fixing", 
                            current, showMetricsPanel);
                    }
                    showMetricsItem.setSelected(showMetricsPanel);
                }
            }

            // When node stops, hide and shutdown MetricsPanel to release resources
            if (newState == NodeLifecycleState.STOPPED || newState == NodeLifecycleState.IDLE) {
                LOGGER.debug("[MetricsPanel] Node stopped — shutting down MetricsPanel");
                if (metricsPanel != null) {
                    updateMetricsPanelState(false);
                }
            }
        });
    }
}
