package brs.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.util.SystemInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JDialog;
import brs.gui.animations.RotatingSvgIcon;
import javax.swing.JDialog;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
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
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.LookAndFeel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.JPopupMenu;
import javax.swing.UIDefaults;
import javax.swing.plaf.FontUIResource;
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

import brs.Signum;
import brs.BlockchainProcessor;
import brs.Constants;
import brs.gui.util.CustomDrawings;
import brs.gui.util.HelpButton;
import brs.gui.util.CustomDrawingComponent;
import brs.Block;
import brs.peer.Peer;
import brs.fluxcapacitor.FluxValues;
import brs.props.PropertyService;
import brs.props.Props;
import brs.util.DurationFormatter;
import brs.util.Listener;
import brs.util.Convert;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class SignumGUI extends JFrame {
    private static final String FAILED_TO_START_MESSAGE = "Signum caught exception while starting";
    private static SignumGUI instance;
    private static final String UNEXPECTED_EXIT_MESSAGE = "Signum Quit unexpectedly! Exit code ";

    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss yyyy-MM-dd");

    private static final int OUTPUT_MAX_LINES = 500;

    private static final int ANIMATION_DURATION_MS = 250;

    private static final Logger LOGGER = LoggerFactory.getLogger(SignumGUI.class);

    private static final ByteArrayOutputStream EARLY_LOG_BUFFER = new ByteArrayOutputStream();
    private static final ByteArrayOutputStream EARLY_ERR_BUFFER = new ByteArrayOutputStream();
    private static PrintStream ORIGINAL_OUT;
    private static PrintStream ORIGINAL_ERR;
    private static String[] args;

    private static Path getGuiSettingsPath(String[] args) {
        String confFolder = Signum.CONF_FOLDER;
        try {
            CommandLine cmd = new DefaultParser().parse(Signum.CLI_OPTIONS, args);
            if (cmd.hasOption(Signum.CONF_FOLDER_OPTION.getOpt())) {
                confFolder = cmd.getOptionValue(Signum.CONF_FOLDER_OPTION.getOpt());
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing command line arguments for config folder", e);
        }

        String settingsDir = Props.SETTINGS_DIR.getDefaultValue();
        Path nodePropsFile = brs.util.PathUtils.resolvePath(confFolder).resolve("node.properties");
        if (Files.exists(nodePropsFile)) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(nodePropsFile.toFile())) {
                Properties nodeProps = new Properties();
                nodeProps.load(in);
                settingsDir = nodeProps.getProperty(Props.SETTINGS_DIR.getName(), settingsDir);
            } catch (Exception e) {
                // ignore
            }
        }
        return brs.util.PathUtils.resolvePath(settingsDir).resolve("gui-settings.json");
    }

    private static void loadLookAndFeelSettings(String[] args) {
        Path settingsPath = getGuiSettingsPath(args);
        String themeClassName = FlatDarkLaf.class.getName(); // Default theme
        Map<String, Color> colorOverrides = null;

        if (Files.exists(settingsPath)) {
            try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    JsonObject settings = parsed.getAsJsonObject();
                    String lastProfileName = null;

                    if (settings.has("lastSelectedLafProfile")) {
                        lastProfileName = settings.get("lastSelectedLafProfile").getAsString();
                    }

                    if (settings.has("enableGPU") && settings.get("enableGPU").getAsBoolean()) {
                        System.setProperty("sun.java2d.opengl", "true");
                    }

                    if (lastProfileName != null && settings.has("lookAndFeelProfiles")) {
                        JsonObject profiles = settings.getAsJsonObject("lookAndFeelProfiles");
                        if (profiles.has(lastProfileName)) {
                            JsonObject profileSettings = profiles.getAsJsonObject(lastProfileName);
                            if (profileSettings.has("theme")) {
                                themeClassName = profileSettings.get("theme").getAsString();
                            }
                            if (profileSettings.has("font")) {
                                JsonObject fontSettings = profileSettings.getAsJsonObject("font");
                                String family = fontSettings.get("family").getAsString();
                                int style = fontSettings.get("style").getAsInt();
                                int size = fontSettings.get("size").getAsInt();
                                Font font = new Font(family, style, size);
                                UIManager.put("defaultFont", font);
                                updateCommonFontKeys(font);
                            }
                            if (profileSettings.has("colorOverrides")) {
                                colorOverrides = parseColorOverrides(profileSettings.getAsJsonObject("colorOverrides"));
                            }
                        }
                    } else if (settings.has("lookAndFeelSettings")) { // Fallback to old structure
                        JsonObject lafSettings = settings.getAsJsonObject("lookAndFeelSettings");
                        if (lafSettings.has("theme")) {
                            themeClassName = lafSettings.get("theme").getAsString();
                        }
                        if (lafSettings.has("font")) {
                            JsonObject fontSettings = lafSettings.getAsJsonObject("font");
                            String family = fontSettings.get("family").getAsString();
                            int style = fontSettings.get("style").getAsInt();
                            int size = fontSettings.get("size").getAsInt();
                            Font font = new Font(family, style, size);
                            UIManager.put("defaultFont", font);
                            updateCommonFontKeys(font);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not load Look and Feel settings, using default.", e);
            }
        }

        // Register custom defaults source for FlatLaf (accent colors, etc.)
        String packageName = GuiResources.FLATLAF_RESOURCE_PATH;
        if (packageName.endsWith("/")) {
            packageName = packageName.substring(0, packageName.length() - 1);
        }
        FlatLaf.registerCustomDefaultsSource(packageName);

        try {
            if (themeClassName.contains("NimbusLookAndFeel")) {
                setupLegacyNimbus();
            }
            UIManager.setLookAndFeel(themeClassName);
            ColorPaletteManager.updatePalette(colorOverrides);
        } catch (Exception e) {
            LOGGER.error("Failed to set Look and Feel, falling back to FlatDarkLaf.", e);
            FlatDarkLaf.setup();
            ColorPaletteManager.updatePalette(colorOverrides);
        }
    }

    private static void updateCommonFontKeys(Font font) {
        String[] fontKeys = {
                "ProgressBar.font", "Label.font", "Button.font", "CheckBox.font",
                "RadioButton.font", "ComboBox.font", "TextField.font",
                "TextArea.font", "PasswordField.font", "TextPane.font",
                "EditorPane.font", "TitledBorder.font", "Table.font", "TableHeader.font",
                "ToolTip.font", "Tree.font", "List.font", "Menu.font", "MenuItem.font",
                "CheckBoxMenuItem.font", "RadioButtonMenuItem.font" };

        if (font == null) {
            // If font is null, reset to Look and Feel defaults
            UIDefaults defaults = UIManager.getLookAndFeelDefaults();
            for (String key : fontKeys) {
                UIManager.put(key, defaults.getFont(key));
            }
            return;
        }

        FontUIResource uiFont = (font instanceof FontUIResource) ? (FontUIResource) font : new FontUIResource(font);
        for (String key : fontKeys) {
            UIManager.put(key, uiFont);
        }
    }

    private static Map<String, Color> parseColorOverrides(JsonObject overridesJson) {
        if (overridesJson == null) {
            return null;
        }
        Map<String, Color> overrides = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : overridesJson.entrySet()) {
            try {
                overrides.put(entry.getKey(), Color.decode(entry.getValue().getAsString()));
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid color override format for key '{}': {}", entry.getKey(),
                        entry.getValue().getAsString());
            }
        }
        return overrides;
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
        Map<String, Color> overrides = new HashMap<>();
        if (LookAndFeelPanel.getInstance() != null && LookAndFeelPanel.getInstance().getColorSettingsPanel() != null) {
            overrides = new HashMap<>(LookAndFeelPanel.getInstance().getColorSettingsPanel().getCurrentOverrides());
        }

        // 1. Update the color palette based on the current theme and overrides
        ColorPaletteManager.updatePalette(overrides);

        // 1b. Ensure all common UI fonts are linked to the active font
        updateCommonFontKeys(UIManager.getFont("defaultFont"));

        // Capture window sizes before update to prevent auto-resizing (packing)
        // behavior
        // of some Look and Feels or decoration switches.
        Map<Window, Dimension> windowSizes = new HashMap<>();
        for (Window w : Window.getWindows()) {
            if (w.isDisplayable()) {
                windowSizes.put(w, w.getSize());
            }
        }

        // 2. Animate Look and Feel change
        FlatAnimatedLafChange.showSnapshot();
        FlatLaf.updateUI();
        FlatAnimatedLafChange.hideSnapshotWithAnimation();

        // Restore window sizes
        for (Map.Entry<Window, Dimension> entry : windowSizes.entrySet()) {
            if (entry.getKey().isDisplayable()) {
                entry.getKey().setSize(entry.getValue());
            }
        }

        // 3. Manually trigger updates for components that need more than a standard
        // updateUI().
        if (instance != null) {
            // Manually update console font
            if (instance.textScrollPane != null) {
                JTextPane tp = (JTextPane) instance.textScrollPane.getViewport().getView();
                if (tp != null) {
                    tp.setFont(UIManager.getFont("TextPane.font"));
                }
            }
            instance.updateCustomComponents();
        }

        // 4. Also update any open dialogs that might have custom components
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing()) {
                SwingUtilities.updateComponentTreeUI(window);
            }
        }
    }

    private void updateCustomComponents() {
        updateToolBarIcons();
        updatePopOffToggleIcon();
        updateDbCheckButtonIcon();
        updateTimeLabelIcons();
        updateVolumeLabelIcons();

        if (menuPanel != null) {
            SwingUtilities.updateComponentTreeUI(menuPanel);
            menuPanel.setBackground(UIManager.getColor("PopupMenu.background"));
            menuPanel.setBorder(UIManager.getBorder("PopupMenu.border"));
        }
        if (menuPanelWrapper != null) {
            SwingUtilities.updateComponentTreeUI(menuPanelWrapper);
        }
        if (commandPanel != null) {
            SwingUtilities.updateComponentTreeUI(commandPanel);
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
    private final Color iconColor;

    private JLabel connectedPeersLabel;
    private JLabel peersCountLabel;
    private JLabel blacklistedPeersLabel;
    private JLabel uploadVolumeLabel;
    private JLabel downloadVolumeLabel;
    private JLabel trimHeightLabel;
    private JSeparator trimSeparator;
    private JLabel popOffBlockCountLabel;
    private JLabel popOffBlockHeightLabel;
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

    private CustomDrawingComponent popOffToggle;
    private JButton menuButton;
    private JButton globeButton;
    private JLabel measurementLabel;
    private JPanel commandPanel;
    private JPanel topPanel;
    private JPanel mainCardPanel;
    private CardLayout cardLayout;
    private static final String VIEW_CONSOLE = "CONSOLE";
    private static final String VIEW_NODE_PROPS = "NODE_PROPS";
    private static final String VIEW_LOGGER_PROPS = "LOGGER_PROPS";
    private static final String VIEW_LAF_PROPS = "LAF_PROPS";
    private boolean showCommandInput = false;
    private boolean showMetricsPanel = true;
    private JCheckBox showCommandItem;
    private JCheckBox enableGpuItem;
    private JCheckBox showMetricsItem;
    private JLabel experimentalLabel;
    private JPanel commandPanelWrapper;
    private Timer commandPanelAnimator;
    private JPanel menuPanelWrapper;
    private JPanel menuPanel;
    private Timer menuPanelAnimator;
    private boolean isMenuExpanded = false;

    private JLabel trimLabel;
    private JLabel autoResolveLabel;
    private JSeparator measurementSeparator;
    private JSeparator experimentalSeparator;
    private JSeparator trimIconSeparator;
    private JSeparator autoResolveSeparator;
    private boolean enableGPU = false;

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

    private void addInfoTooltip(JLabel label, String text, String titleOverride) {
        if (text != null) {
            String shortTooltip = text.split("\n")[0];
            label.setToolTipText(shortTooltip);
        }
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    String title;
                    String labelText = label.getText();
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
                    JOptionPane.showMessageDialog(SignumGUI.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
    }

    private void toggleMenu() {
        if (menuPanelAnimator != null && menuPanelAnimator.isRunning()) {
            return;
        }

        isMenuExpanded = !isMenuExpanded;

        if (isMenuExpanded) {
            // Calculate position relative to layered pane
            int menuWidth = Math.max(250, menuPanel.getPreferredSize().width);
            Point p = menuButton.getLocationOnScreen();
            SwingUtilities.convertPointFromScreen(p, getLayeredPane());
            int x = p.x + menuButton.getWidth() - menuWidth;
            int y = p.y + menuButton.getHeight();

            menuPanelWrapper.setBounds(x, y, menuWidth, 0);
            getLayeredPane().add(menuPanelWrapper, JLayeredPane.POPUP_LAYER);

            menuPanelWrapper.add(menuPanel, BorderLayout.CENTER);
            menuPanel.setVisible(true);

            int targetHeight = menuPanel.getPreferredSize().height;

            menuPanelAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3);

                    int h = (int) (targetHeight * progress);
                    menuPanelWrapper.setSize(menuWidth, h);
                    menuPanelWrapper.revalidate();
                    menuPanelWrapper.repaint();

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        menuPanelWrapper.setSize(menuWidth, targetHeight);
                        menuPanelWrapper.revalidate();
                    }
                }
            });
            menuPanelAnimator.start();
        } else {
            final int startHeight = menuPanelWrapper.getHeight();
            final int menuWidth = menuPanelWrapper.getWidth();

            menuPanelAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3);

                    int h = (int) (startHeight * (1.0f - progress));
                    menuPanelWrapper.setSize(menuWidth, h);
                    menuPanelWrapper.revalidate();
                    menuPanelWrapper.repaint();

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        menuPanelWrapper.removeAll();
                        getLayeredPane().remove(menuPanelWrapper);
                        getLayeredPane().repaint();
                    }
                }
            });
            menuPanelAnimator.start();
        }
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
        final int targetHeight = Math.max(naturalSize.height, 25);
        Container parent = popOffButtonsPanel.getParent();

        if (showPopOff) {
            // Opening
            popOffButtonsPanel.setVisible(true);
            popOffButtonsPanel.setPreferredSize(new Dimension(0, targetHeight));
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }

            popOffAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3); // Ease out

                    int w = (int) (targetWidth * progress);
                    popOffButtonsPanel.setPreferredSize(new Dimension(w, targetHeight));
                    if (parent != null) {
                        parent.revalidate();
                        parent.repaint();
                    }

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        popOffButtonsPanel.setPreferredSize(null); // Reset to natural size
                        if (parent != null)
                            parent.revalidate();
                    }
                }
            });
            popOffAnimator.start();
        } else {
            // Closing
            final int startWidth = popOffButtonsPanel.getWidth();

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

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        popOffButtonsPanel.setPreferredSize(new Dimension(0, targetHeight));
                        popOffButtonsPanel.setVisible(false);
                        if (parent != null)
                            parent.revalidate();
                    }
                }
            });
            popOffAnimator.start();
        }
    }

    private void toggleCommandPanel() {
        if (commandPanelAnimator != null && commandPanelAnimator.isRunning()) {
            return;
        }

        showCommandInput = !showCommandInput;
        showCommandItem.setSelected(showCommandInput);

        Runnable scrollToBottom = () -> {
            if (textScrollPane != null) {
                JScrollBar vertical = textScrollPane.getVerticalScrollBar();
                if (vertical != null) {
                    vertical.setValue(vertical.getMaximum());
                }
            }
        };

        if (showCommandInput) {
            commandPanelWrapper.add(commandPanel, BorderLayout.CENTER);
            commandPanel.setVisible(true);

            commandPanelWrapper.setPreferredSize(new Dimension(commandPanelWrapper.getWidth(), 0));
            if (commandPanelWrapper.getParent() instanceof JComponent) {
                ((JComponent) commandPanelWrapper.getParent()).revalidate();
                ((JComponent) commandPanelWrapper.getParent()).repaint();
            }

            int targetHeight = commandPanel.getPreferredSize().height;

            commandPanelAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3); // Ease out

                    int h = (int) (targetHeight * progress);
                    commandPanelWrapper.setPreferredSize(new Dimension(commandPanelWrapper.getWidth(), h));

                    if (commandPanelWrapper.getParent() instanceof JComponent) {
                        ((JComponent) commandPanelWrapper.getParent()).revalidate();
                        ((JComponent) commandPanelWrapper.getParent()).repaint();
                    }

                    scrollToBottom.run();

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        commandPanelWrapper.setPreferredSize(null);
                        if (commandPanelWrapper.getParent() instanceof JComponent) {
                            ((JComponent) commandPanelWrapper.getParent()).revalidate();
                        }
                        SwingUtilities.invokeLater(scrollToBottom);
                    }
                }
            });
            commandPanelAnimator.start();
        } else {
            final int startHeight = commandPanelWrapper.getHeight();

            commandPanelAnimator = new Timer(10, new ActionListener() {
                final long startTime = System.currentTimeMillis();
                final int duration = ANIMATION_DURATION_MS;

                @Override
                public void actionPerformed(ActionEvent e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float progress = Math.min(1.0f, (float) elapsed / duration);
                    progress = 1.0f - (float) Math.pow(1.0f - progress, 3); // Ease out

                    int h = (int) (startHeight * (1.0f - progress));
                    commandPanelWrapper.setPreferredSize(new Dimension(commandPanelWrapper.getWidth(), h));

                    if (commandPanelWrapper.getParent() instanceof JComponent) {
                        ((JComponent) commandPanelWrapper.getParent()).revalidate();
                        ((JComponent) commandPanelWrapper.getParent()).repaint();
                    }

                    scrollToBottom.run();

                    if (progress >= 1.0f) {
                        ((Timer) e.getSource()).stop();
                        commandPanelWrapper.removeAll();
                        commandPanelWrapper.setPreferredSize(new Dimension(0, 0));
                        if (commandPanelWrapper.getParent() instanceof JComponent) {
                            ((JComponent) commandPanelWrapper.getParent()).revalidate();
                        }
                        SwingUtilities.invokeLater(scrollToBottom);
                    }
                }
            });
            commandPanelAnimator.start();
        }
    }

    public static SignumGUI getInstance() {
        return instance;
    }

    public void showLookAndFeelSettings() {
        cardLayout.show(mainCardPanel, VIEW_LAF_PROPS);
    }

    public static void main(String[] args) {
        // Set default log format for early logs (before LoggerConfigurator takes over)
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%4$s] %1$tF %1$tT %3$s - %5$s%6$s%n");

        // Capture early logs before GUI is initialized
        ORIGINAL_OUT = System.out;
        ORIGINAL_ERR = System.err;

        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                ORIGINAL_OUT.write(b);
                EARLY_LOG_BUFFER.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ORIGINAL_OUT.write(b, off, len);
                EARLY_LOG_BUFFER.write(b, off, len);
            }
        }, true));

        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                ORIGINAL_ERR.write(b);
                EARLY_ERR_BUFFER.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ORIGINAL_ERR.write(b, off, len);
                EARLY_ERR_BUFFER.write(b, off, len);
            }
        }, true));

        SignumGUI.loadLookAndFeelSettings(args);
        new SignumGUI("Signum Node", Props.ICON_LOCATION.getDefaultValue(), Signum.VERSION.toString(), args);
    }

    public SignumGUI(String programName, String iconLocation, String version, String[] args) {
        try {
            // SecurityManager removed (Java 17+ deprecation).
            // Install a simple shutdown hook instead for cleanup if needed.
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        // TODO: add GUI cleanup here if required
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }));
            } catch (Throwable t) {
                // ignore
            }

        } catch (UnsupportedOperationException e) {
            // Java 17+ / 21+: Setting a SecurityManager is not supported anymore
            System.err.println("SecurityManager not supported, skipping setup");
        }
        SignumGUI.args = args;
        instance = this;
        this.programName = programName;
        this.version = version;
        setTitle(programName + " " + version);
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

        IconFontSwing.register(FontAwesome.getIconFont());
        JTextPane textPane = new JTextPane();
        Font consoleFont = UIManager.getFont("TextPane.font");
        if (consoleFont == null) {
            consoleFont = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        }
        textPane.setFont(consoleFont);
        iconColor = textPane.getForeground();
        this.dbConsistencyColor = GuiColors.getButtonIcon();
        DefaultCaret caret = (DefaultCaret) textPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        textPane.setEditable(false);
        flushEarlyLogs(textPane);
        sendJavaOutputToTextArea(textPane);
        textScrollPane = new JScrollPane(textPane);
        textScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        cardLayout = new CardLayout();
        mainCardPanel = new JPanel(cardLayout) {
            @Override
            public Dimension getPreferredSize() {
                // If the panel is visible, prefer the current size to prevent
                // auto-resizing/growth
                // during LookAndFeel changes (especially when switching to themes with larger
                // decorations like Nimbus).
                if (isShowing()) {
                    return getSize();
                }
                // Default initial size
                return new Dimension(900, 500);
            }
        };
        mainCardPanel.add(content, VIEW_CONSOLE);

        NodeConfigurationPanel nodeConfigPanel = new NodeConfigurationPanel(this::restart, this.confFolder,
                () -> cardLayout.show(mainCardPanel, VIEW_CONSOLE),
                () -> cardLayout.show(mainCardPanel, VIEW_LOGGER_PROPS));
        mainCardPanel.add(nodeConfigPanel, VIEW_NODE_PROPS);

        LoggerConfigurationPanel loggerConfigPanel = new LoggerConfigurationPanel(this::restart, this.confFolder,
                () -> cardLayout.show(mainCardPanel, VIEW_CONSOLE),
                () -> cardLayout.show(mainCardPanel, VIEW_NODE_PROPS));
        mainCardPanel.add(loggerConfigPanel, VIEW_LOGGER_PROPS);

        LookAndFeelPanel lafPanel = new LookAndFeelPanel(this::restart, confFolder,
                () -> cardLayout.show(mainCardPanel, VIEW_CONSOLE));
        mainCardPanel.add(lafPanel, VIEW_LAF_PROPS);

        setContentPane(mainCardPanel);

        toolBar = new JPanel(new BorderLayout());

        JPanel leftButtons = new JPanel(new MigLayout("insets 0, gap 5, hidemode 3, aligny top"));
        leftButtons.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

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
            if (JOptionPane.showConfirmDialog(SignumGUI.this,
                    "This will restart the node. Are you sure?", "Restart node",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                restart();
            }
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

        content.add(toolBar, BorderLayout.PAGE_START);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(bottomPanel, BorderLayout.PAGE_END);

        // Command Input Panel
        commandPanel = new JPanel(new BorderLayout(0, 0));
        commandPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        JComponent commandLabel = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                CustomDrawings.COMMAND_SYMBOL.draw((Graphics2D) g, getWidth(), getHeight(), GuiColors.getButtonIcon());
            }

            @Override
            public Dimension getPreferredSize() {
                int size = Math.round(GuiConstants.getToolBarIconSize());
                return new Dimension(size, size);
            }
        };
        commandLabel.setToolTipText("Command Input");
        JTextField commandField = new JTextField();
        commandField.setToolTipText("Enter node command (e.g. .help, .pause, .resume)");
        JButton sendCommandButton = new JButton("Send");

        ActionListener sendAction = e -> {
            String cmd = commandField.getText().trim();
            if (!cmd.isEmpty()) {
                LOGGER.info("Executing command: " + cmd);
                // Let the core handle all commands. The GUI will update via listeners.
                new Thread(() -> Signum.processCommand(cmd)).start();
                commandField.setText("");
            }
        };
        commandField.addActionListener(sendAction);
        sendCommandButton.addActionListener(sendAction);
        JButton helpButton = new HelpButton();
        helpButton.setToolTipText("Command Help");
        helpButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        String commandHelpText = "<html><b>Available Commands:</b><br>" +
                "<ul>" +
                "<li><b>.help</b> - Displays available commands in the log.</li>" +
                "<li><b>.pause</b> - Pauses blockchain synchronization.</li>" +
                "<li><b>.resume</b> - Resumes blockchain synchronization.</li>" +
                "<li><b>.restart</b> - Restarts the node application.</li>" +
                "<li><b>.shutdown</b> - Gracefully shuts down the node.</li>" +
                "<li><b>.autoresolve</b> - Triggers manual database consistency resolution.</li>" +
                "<li><b>.trim</b> - Schedules a database trim.</li>" +
                "<li><b>.dbcheck</b> - Performs a database consistency check.</li>" +
                "<li><b>.popoff &lt;n&gt;</b> - Pops off the last n blocks (e.g., .popoff 10).</li>" +
                "</ul>" +
                "Enter a command in the text field and click 'Send' or press Enter.</html>";

        helpButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(SignumGUI.this, commandHelpText, "Command Usage",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        buttonPanel.add(sendCommandButton);
        buttonPanel.add(helpButton);

        commandPanel.add(commandLabel, BorderLayout.WEST);
        commandPanel.add(commandField, BorderLayout.CENTER);
        commandPanel.add(buttonPanel, BorderLayout.EAST);

        commandPanelWrapper = new JPanel(new BorderLayout());
        bottomPanel.add(commandPanelWrapper, BorderLayout.NORTH);
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
                    JOptionPane.showMessageDialog(SignumGUI.this, htmlText, title, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });
        syncProgressBar.setPreferredSize(GuiConstants.PROGRESS_BAR_SIZE_SMALL);
        syncProgressBar.setMaximumSize(GuiConstants.PROGRESS_BAR_SIZE_SMALL);
        syncProgressBar.setMinimumSize(GuiConstants.PROGRESS_BAR_SIZE_SMALL);

        JPanel latestBlockInfoPanel = new JPanel(new MigLayout("insets 0, hidemode 3, gap 0"));
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
        metricsPanel = new MetricsPanel(this);
        metricsPanel.setVisible(false);
        metricsPanelWrapper = new JPanel(new BorderLayout());
        metricsPanelWrapper.add(metricsPanel, BorderLayout.CENTER);

        trimSeparator = new JSeparator(SwingConstants.VERTICAL);
        trimSeparator.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);
        String trimTooltip = "The minimum height to which the blockchain can be rolled back. Older data is pruned to save space.\n"
                + "Trimming occurs every " + brs.Constants.TRIM_PERIOD + " blocks.\n\n"
                + "If 'est.' (estimated) is shown, the actual trim height is unknown (e.g. after restart),\n"
                + "so it is calculated based on the trim period.";
        trimHeightLabel = createLabel("Trim height: -", null, trimTooltip);

        trimSeparator.setVisible(false);
        trimHeightLabel.setVisible(false);
        latestBlockInfoPanel.add(trimSeparator, "gapleft 5, gapright 5");
        latestBlockInfoPanel.add(trimHeightLabel);

        popOffSeparator1 = new JSeparator(SwingConstants.VERTICAL);
        popOffSeparator1.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);

        String popOffCountTooltip = "Shows the number of blocks remaining to be removed from the blockchain during a 'pop-off' operation.\n\nThis counter appears only when a pop-off is in progress and helps monitor its advancement.";
        popOffBlockCountLabel = createLabel("Pop off blocks: 0", null, popOffCountTooltip);

        popOffSeparator2 = new JSeparator(SwingConstants.VERTICAL);
        popOffSeparator2.setPreferredSize(GuiConstants.VERTICAL_SEPARATOR_SIZE);

        String popOffHeightTooltip = "Displays the target block height after the pop-off operation completes, along with the current block height before the pop-off.\n\nThis information is crucial for understanding the state of your blockchain during a pop-off, which is used to resolve forks or other issues by reverting to a previous state.";
        popOffBlockHeightLabel = createLabel("- 🡸 -", null, popOffHeightTooltip);

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
        menuButton.addActionListener(e -> toggleMenu());

        // Menu Panel setup
        menuPanel = new JPanel(new MigLayout("insets 10 15 10 15, fillx, wrap 1", "[grow]"));
        menuPanel.setBackground(UIManager.getColor("PopupMenu.background"));
        menuPanel.setBorder(UIManager.getBorder("PopupMenu.border"));

        showCommandItem = new JCheckBox("Show Command Input");
        showCommandItem.setSelected(showCommandInput);
        showCommandItem.addActionListener(e -> toggleCommandPanel());
        menuPanel.add(showCommandItem);

        showMetricsItem = new JCheckBox("Show Metrics Panel");
        showMetricsItem.setSelected(showMetricsPanel);
        showMetricsItem.addActionListener(e -> {
            updateMetricsPanelState(showMetricsItem.isSelected());
        });
        menuPanel.add(showMetricsItem);

        enableGpuItem = new JCheckBox("Enable GPU Acceleration");
        enableGpuItem.setToolTipText("Enables OpenGL pipeline for smoother rendering. Requires restart.");
        enableGpuItem.setSelected(enableGPU);
        enableGpuItem.addActionListener(e -> {
            enableGPU = enableGpuItem.isSelected();
            JOptionPane.showMessageDialog(SignumGUI.this,
                    "Changes to GPU acceleration will take effect after restart.", "Restart Required",
                    JOptionPane.INFORMATION_MESSAGE);
        });
        menuPanel.add(enableGpuItem);

        menuPanel.add(new JSeparator(), "growx, gapy 5");

        JButton nodePropsItem = new JButton("Node Configuration");
        nodePropsItem.setHorizontalAlignment(SwingConstants.LEFT);
        nodePropsItem.setBorderPainted(false);
        nodePropsItem.setContentAreaFilled(false);
        nodePropsItem.setFocusPainted(false);
        nodePropsItem.addActionListener(e -> {
            cardLayout.show(mainCardPanel, VIEW_NODE_PROPS);
            toggleMenu();
        });
        menuPanel.add(nodePropsItem, "growx");

        JButton loggerPropsItem = new JButton("Logger Configuration");
        loggerPropsItem.setHorizontalAlignment(SwingConstants.LEFT);
        loggerPropsItem.setBorderPainted(false);
        loggerPropsItem.setContentAreaFilled(false);
        loggerPropsItem.setFocusPainted(false);
        loggerPropsItem.addActionListener(e -> {
            cardLayout.show(mainCardPanel, VIEW_LOGGER_PROPS);
            toggleMenu();
        });
        menuPanel.add(loggerPropsItem, "growx");

        JButton lafPropsItem = new JButton("Look and Feel Settings");
        lafPropsItem.setHorizontalAlignment(SwingConstants.LEFT);
        lafPropsItem.setBorderPainted(false);
        lafPropsItem.setContentAreaFilled(false);
        lafPropsItem.setFocusPainted(false);
        lafPropsItem.addActionListener(e -> {
            cardLayout.show(mainCardPanel, VIEW_LAF_PROPS);
            toggleMenu();
        });
        menuPanel.add(lafPropsItem, "growx");

        menuPanelWrapper = new JPanel(new BorderLayout());

        JScrollPane scrollPane = new JScrollPane(leftButtons);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);

        // Listener to adjust bottom padding when scrollbar appears/disappears to
        // prevent overlay
        JScrollBar hBar = scrollPane.getHorizontalScrollBar();
        hBar.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                leftButtons.setBorder(BorderFactory.createEmptyBorder(5, 5, hBar.getHeight(), 5));
                toolBar.revalidate();
                SwingUtilities.invokeLater(() -> hBar.setValue(hBar.getMaximum()));
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                leftButtons.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
                toolBar.revalidate();
            }
        });

        scrollPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (hBar.isShowing()) {
                    SwingUtilities.invokeLater(() -> hBar.setValue(hBar.getMaximum()));
                }
            }
        });

        toolBar.add(scrollPane, BorderLayout.CENTER);

        JPanel rightIconsPanel = new JPanel(new MigLayout("insets 5 5 5 10, gap 5, aligny top"));
        globeButton = new JButton(IconFontSwing.buildIcon(FontAwesome.GLOBE, GuiConstants.getToolBarIconSize(),
                GuiColors.getButtonIcon()));
        rightIconsPanel.add(globeButton);
        rightIconsPanel.add(menuButton);
        toolBar.add(rightIconsPanel, BorderLayout.EAST);

        leftButtons.add(popOffToggle);

        // Use MigLayout for better dynamic resizing support
        topPanel = new JPanel(new MigLayout("insets 0, gap 0, fillx, wrap 1", "[grow]", "[]0[]"));
        topPanel.add(toolBar, "growx");
        topPanel.add(metricsPanelWrapper, "growx");

        // Use MigLayout for infoPanel to allow precise vertical alignment
        infoPanel = new JPanel(
                new MigLayout("insets 0, fillx, hidemode 3, gap 0", "[][][][][][][][][][][][][][grow]", "[]"));

        content.add(topPanel, BorderLayout.NORTH);
        content.add(textScrollPane, BorderLayout.CENTER);

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
                    PeersDialog.showPeersDialog(SignumGUI.this);
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
        tooltip = "Automatic table trimming is active (DB.trimDerivedTables = true).\n" +
                "Derived tables are being periodically pruned to save disk space.\n" +
                "This happens every " + (brs.Constants.MAX_ROLLBACK * 10) + " blocks."
                + "\n\nEnabled by property: DB.trimDerivedTables = true";
        trimLabel = new JLabel(IconFontSwing.buildIcon(FontAwesome.SCISSORS, GuiConstants.getToolBarIconSize(),
                GuiColors.getButtonIcon()));
        addInfoTooltip(trimLabel, tooltip, "TRIM");
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
            java.io.InputStream iconStream = getClass().getResourceAsStream(iconLocation);
            if (iconStream != null) {
                setIconImage(ImageIO.read(iconStream));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        initGlassPane();

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (trayIcon == null) {
                    if (JOptionPane.showConfirmDialog(SignumGUI.this,
                            "This will stop the node. Are you sure?", "Exit and stop node",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                        shutdown();
                    }
                } else {
                    trayIcon.displayMessage("Signum GUI closed", "Note that Signum is still running", MessageType.INFO);
                    setVisible(false);
                }
            }
        });

        pack();
        Insets insets = getInsets();
        int preferredContentWidth = Math.max(topPanel.getPreferredSize().width, metricsPanel.getPreferredSize().width);
        setSize(preferredContentWidth + insets.left + insets.right, 800);
        setLocationRelativeTo(null);
        showWindow();

        // Close menu when clicking outside
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (isMenuExpanded && event instanceof MouseEvent) {
                MouseEvent me = (MouseEvent) event;
                if (me.getID() == MouseEvent.MOUSE_PRESSED) {
                    if (menuPanelWrapper != null && menuPanelWrapper.isShowing() && menuButton != null
                            && menuButton.isShowing()) {
                        try {
                            Point p = me.getLocationOnScreen();
                            Point menuLoc = menuPanelWrapper.getLocationOnScreen();
                            Rectangle menuRect = new Rectangle(menuLoc, menuPanelWrapper.getSize());

                            Point btnLoc = menuButton.getLocationOnScreen();
                            Rectangle btnRect = new Rectangle(btnLoc, menuButton.getSize());

                            // If clicked outside menu and outside menu button, close it
                            if (!menuRect.contains(p) && !btnRect.contains(p)) {
                                toggleMenu();
                            }
                        } catch (IllegalComponentStateException e) {
                            // Component might be hidden during check
                        }
                    }
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK);

        // Start BRS
        new Thread(this::startSignumWithGUI).start();
    }

    private void initGlassPane() {
        JPanel glassPane = new GlassPane();
        setGlassPane(glassPane);
        glassPane.setVisible(true);
    }

    private void shutdown() {
        JDialog shutdownDialog = new JDialog(this, "Shutting down", true);
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
                    updateTitle();

                    if (elapsedTimeTimer != null) {
                        try {
                            elapsedTimeTimer.stop();
                        } catch (Throwable t) {
                            LOGGER.warn("Error stopping elapsed time timer", t);
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

        getContentPane().validate();

        try {
            String newIconLocation = iconLocation;
            if (Signum.getPropertyService() != null) {
                newIconLocation = Signum.getPropertyService().getString(Props.ICON_LOCATION);
            }
            if (!newIconLocation.equals(iconLocation)) {
                // update the icon
                iconLocation = newIconLocation;
                setIconImage(ImageIO.read(getClass().getResourceAsStream(iconLocation)));
            }
            TrayIcon newTrayIcon = new TrayIcon(
                    Toolkit.getDefaultToolkit().createImage(SignumGUI.class.getResource(iconLocation)), "Signum Node",
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
        Signum.getBlockchainProcessor().setSyncPaused(!isSyncStopped);
    }

    private void shutdownAction() {
        if (JOptionPane.showConfirmDialog(SignumGUI.this,
                "This will stop the node. Are you sure?", "Shutdown Node",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
            shutdown();
        }
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
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();

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

        waitDialog = new JDialog(SignumGUI.this, "Database Check", true);
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

                        final int finalLimitHeight = blockchainProcessor.getSafeRollbackHeight();
                        int lastTrimHeight = blockchainProcessor.getLastTrimHeight().get();
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
                            JOptionPane.showMessageDialog(SignumGUI.this, message,
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
        waitDialog.setLocationRelativeTo(SignumGUI.this);
        waitDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        if (isDbCheckRunning.get()) {
            waitDialog.setVisible(true);
            return;
        }

        waitDialog.setVisible(true);
    }

    private void showDbCheckResult(int result, int height, long totalMined, long totalEffectiveBalance,
            boolean wasResolutionActive, int limitHeight, int lastTrimHeight) {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
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
            JOptionPane.showMessageDialog(SignumGUI.this, message, "Database Consistency Check",
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

                JOptionPane.showMessageDialog(SignumGUI.this, messageContent, title,
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
            int n = JOptionPane.showOptionDialog(SignumGUI.this, messageContent, "Database Consistency Check",
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
        if (Signum.getBlockchainProcessor() == null) {
            showMessage("Blockchain processor not initialized.");
            return;
        }
        new Thread(() -> Signum.getBlockchainProcessor().popOff(count)).start();
    }

    void restart() {
        LOGGER.info("Restarting node...");

        JDialog restartDialog = new JDialog(this, "Restarting", true);
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
                    signum.Launcher.restart();
                }).start();
            }
        });

        restartDialog.setVisible(true);
    }

    private void editConf() {
        Path path = brs.util.PathUtils.resolvePath(confFolder).resolve(Signum.PROPERTIES_NAME);
        if (!Files.exists(path)) {
            path = brs.util.PathUtils.resolvePath(confFolder).resolve(Signum.DEFAULT_PROPERTIES_NAME);
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
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
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
            blockchainProcessor.addListener(block -> onTrimStart(),
                    BlockchainProcessor.Event.TRIM_START);
            blockchainProcessor.addListener(block -> onTrimHeightChanged(),
                    BlockchainProcessor.Event.TRIM_END);
            blockchainProcessor.addListener(block -> onConsistencyUpdate(),
                    BlockchainProcessor.Event.DATABASE_CONSISTENCY_UPDATE);
        }
    }

    public void onPeersUpdated() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        Collection<Peer> allPeers = blockchainProcessor.getAllPeers();
        long connectedCount = allPeers.stream().filter(p -> p.getState() == Peer.State.CONNECTED).count();
        long allKnownCount = allPeers.size();
        long blacklistedCount = allPeers.stream().filter(Peer::isBlacklisted).count();
        SwingUtilities.invokeLater(() -> updatePeerCount(connectedCount, allKnownCount, blacklistedCount));
    }

    public void onNetVolumeChanged() {
        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
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

    private void onTrimStart() {

        int currentTrimHeight = Signum.getBlockchainProcessor().getCurrentTrimHeight().get();
        int lastTrimHeight = Signum.getBlockchainProcessor().getLastTrimHeight().get();
        SwingUtilities.invokeLater(() -> {

            if (lastTrimHeight > currentTrimHeight) {
                if (currentTrimHeight < 0) {
                    trimHeightLabel.setText(String.format("Trim height: - 🡺 %d", lastTrimHeight));
                } else {
                    trimHeightLabel
                            .setText(String.format("Trim height: %d 🡺 %d", currentTrimHeight, lastTrimHeight));
                }
            }
            trimHeightLabel.setForeground(Color.GREEN);
        });
    }

    private void onConsistencyUpdate() {
        BlockchainProcessor.ConsistencyState state = Signum.getBlockchainProcessor().getConsistencyState();
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
        int remaining = Signum.getBlockchainProcessor().getManualPopOffBlocksCount();
        int blockHeight = Signum.getBlockchainProcessor().getBeforeRollbackHeight();
        int targetHeight = Signum.getBlockchainProcessor().getManualLastPopOffHeight();
        SwingUtilities.invokeLater(() -> {
            popOffBlockCountLabel.setText("Pop off blocks: " + remaining);
            popOffBlockHeightLabel.setText(targetHeight < 0 ? "-" : targetHeight + " 🡸 " + blockHeight);
            if (remaining > 0) {
                popOffBlockCountLabel.setForeground(Color.YELLOW);
                popOffBlockHeightLabel.setForeground(Color.YELLOW);
            } else {
                popOffBlockCountLabel.setForeground(iconColor);
                popOffBlockHeightLabel.setForeground(iconColor);
            }
            setPopOffLabelVisible(remaining > 0);
        });
    }

    private void onAutoPopOffProgress() {
        int remaining = Signum.getBlockchainProcessor().getAutoPopOffBlocksCount();
        int blockHeight = Signum.getBlockchainProcessor().getBeforeRollbackHeight();
        int targetHeight = Signum.getBlockchainProcessor().getAutoLastPopOffHeight();
        SwingUtilities.invokeLater(() -> {
            popOffBlockCountLabel.setText("Pop off blocks: " + remaining);
            popOffBlockHeightLabel.setText(targetHeight < 0 ? "-" : targetHeight + " 🡸 " + blockHeight);

            if (Signum.getBlockchainProcessor().getResolutionState() == BlockchainProcessor.ResolutionState.ACTIVE) {
                if (remaining > 0) {
                    popOffBlockCountLabel.setForeground(GuiColors.getContrastRed());
                    popOffBlockHeightLabel.setForeground(GuiColors.getContrastRed());
                } else {
                    popOffBlockCountLabel.setForeground(iconColor);
                    popOffBlockHeightLabel.setForeground(iconColor);
                }
            } else {
                if (remaining > 0) {
                    popOffBlockCountLabel.setForeground(Color.ORANGE);
                    popOffBlockHeightLabel.setForeground(Color.ORANGE);
                } else {
                    popOffBlockCountLabel.setForeground(iconColor);
                    popOffBlockHeightLabel.setForeground(iconColor);
                }
            }
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
            updateTitle();
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
            trimEnabled = Signum.getPropertyService().getBoolean(Props.DB_TRIM_DERIVED_TABLES);
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
                    if (enableGpuItem != null) {
                        enableGpuItem.setSelected(enableGPU);
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

                    if (trimEnabled) {
                        trimLabel.setVisible(true);
                        trimIconSeparator.setVisible(true);
                        trimHeightLabel.setVisible(true);
                        trimSeparator.setVisible(true);
                    } else {
                        trimLabel.setVisible(false);
                        trimIconSeparator.setVisible(false);
                        trimHeightLabel.setVisible(false);
                        trimSeparator.setVisible(false);
                    }

                    if (autoResolveEnabled) {
                        autoResolveLabel.setVisible(true);
                        autoResolveSeparator.setVisible(true);
                    } else {
                        autoResolveLabel.setVisible(false);
                        autoResolveSeparator.setVisible(false);
                    }

                    onTrimHeightChanged();
                    onConsistencyUpdate();
                    onManualPopOffProgress();
                    onAutoPopOffProgress();

                    updateLatestBlock(lastBlock, maxPeerHeight, blockTime);
                    updatePeerCount(connectedCount, allKnownCount, blacklistedCount);
                });

                updateTitle();

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
            LOGGER.error(FAILED_TO_START_MESSAGE, t);
            showMessage(FAILED_TO_START_MESSAGE);
            onBrsStopped();
            SwingUtilities.invokeLater(this::showTrayIcon);
        }

    }

    private void updateMetricsPanelState(boolean show) {
        if (metricsPanelAnimator != null && metricsPanelAnimator.isRunning()) {
            return;
        }

        showMetricsPanel = show;
        if (show) {
            if (metricsPanel == null) {
                metricsPanel = new MetricsPanel(this);
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

    void updateTitle() {
        String networkName = Signum.getPropertyService().getString(Props.NETWORK_NAME);

        StringBuilder titleBuilder = new StringBuilder();
        titleBuilder.append(this.programName + " [" + networkName + "] " + this.version);

        BlockchainProcessor blockchainProcessor = Signum.getBlockchainProcessor();
        if (blockchainProcessor != null) {
            String dbType = blockchainProcessor.getDbType();
            String dbVersion = blockchainProcessor.getDbVersion();
            titleBuilder.append(" - [").append(dbType);
            if (dbVersion != null && !dbVersion.isEmpty() && !"N/A".equals(dbVersion)) {
                titleBuilder.append(" ").append(dbVersion);
            }
            titleBuilder.append("]");
        }

        String title = titleBuilder.toString();
        if (isSyncStopped) {
            title += " (Sync paused)";
        } else if (isShuttingDown) {
            title += " (Shutting Down...)";
        }
        final String finalTitle = title;
        SwingUtilities.invokeLater(() -> setTitle(finalTitle));
        if (trayIcon != null) {
            trayIcon.setToolTip(finalTitle);
        }
    }

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
            return Signum.getBlockchainProcessor().getAllPeers().stream()
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

    private void onTrimHeightChanged() {
        int currentTrimHeight = Signum.getBlockchainProcessor().getCurrentTrimHeight().get();
        int estimatedTrimHeight = (currentTrimHeight == -1) ? Signum.getBlockchainProcessor().getEstimatedTrimHeight()
                : 0;
        SwingUtilities.invokeLater(() -> {

            if (currentTrimHeight != -1) {
                trimHeightLabel.setText("Trim height: " + currentTrimHeight);
            } else {
                trimHeightLabel.setText("Trim height: est. " + estimatedTrimHeight);
            }
            trimHeightLabel.setForeground(iconColor);
        });
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

    private void loadGuiSettings() {
        try {
            String settingsDir = Signum.getPropertyService().getString(Props.SETTINGS_DIR);
            Path settingsPath = brs.util.PathUtils.resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
            if (Files.exists(settingsPath)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(settingsPath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        JsonObject settings = parsed.getAsJsonObject();
                        if (settings.has("showCommandInput")) {
                            showCommandInput = settings.get("showCommandInput").getAsBoolean();
                        }
                        if (settings.has("showMetricsPanel")) {
                            showMetricsPanel = settings.get("showMetricsPanel").getAsBoolean();
                        }
                        if (settings.has("enableGPU")) {
                            enableGPU = settings.get("enableGPU").getAsBoolean();
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load GUI settings", e);
        }
    }

    private void saveGuiSettings() {
        try {
            String settingsDir = Signum.getPropertyService().getString(Props.SETTINGS_DIR);
            Path settingsPath = brs.util.PathUtils.resolvePath(Paths.get(settingsDir, "gui-settings.json").toString());
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
                }
            }
            settings.addProperty("showCommandInput", showCommandInput);
            settings.addProperty("showMetricsPanel", showMetricsPanel);
            settings.addProperty("enableGPU", enableGPU);
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(settingsPath)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                writer.write(gson.toJson(settings));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save GUI settings", e);
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
        SwingUtilities.invokeLater(() -> setTitle(getTitle() + " (STOPPED)"));
        if (trayIcon != null)
            trayIcon.setToolTip(trayIcon.getToolTip() + " (STOPPED)");
    }

    private void flushEarlyLogs(JTextPane textPane) {
        // Use a dummy stream for actualOutput to prevent duplication to console,
        // as these logs were already printed to ORIGINAL_OUT/ERR during capture.
        PrintStream dummyStream = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
            }
        });

        if (EARLY_LOG_BUFFER.size() > 0) {
            // Pass false to disable timer, we flush immediately
            TextAreaOutputStream taos = new TextAreaOutputStream(textPane, dummyStream, false, false);
            taos.write(EARLY_LOG_BUFFER.toByteArray());
            taos.flush();
        }
        if (EARLY_ERR_BUFFER.size() > 0) {
            TextAreaOutputStream taos = new TextAreaOutputStream(textPane, dummyStream, true, false);
            taos.write(EARLY_ERR_BUFFER.toByteArray());
            taos.flush();
        }
        // Release memory
        EARLY_LOG_BUFFER.reset();
        EARLY_ERR_BUFFER.reset();
    }

    private void sendJavaOutputToTextArea(JTextPane textPane) {
        // Revert to original streams + GUI capture (removes the buffering layer)
        PrintStream out = ORIGINAL_OUT != null ? ORIGINAL_OUT : System.out;
        PrintStream err = ORIGINAL_ERR != null ? ORIGINAL_ERR : System.err;
        System.setOut(new PrintStream(new TextAreaOutputStream(textPane, out, false)));
        System.setErr(new PrintStream(new TextAreaOutputStream(textPane, err, true)));
    }

    private void showMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            System.err.println("Showing message: " + message);
            JOptionPane.showMessageDialog(this, message, "Signum Message", JOptionPane.ERROR_MESSAGE);
        });
    }

    private static class TextAreaOutputStream extends OutputStream {
        private final JTextPane textPane;
        private final PrintStream actualOutput;
        private final StringBuilder buffer = new StringBuilder();
        private final Timer timer;
        private final boolean isError;

        private TextAreaOutputStream(JTextPane textPane, PrintStream actualOutput, boolean isError) {
            this(textPane, actualOutput, isError, true);
        }

        private TextAreaOutputStream(JTextPane textPane, PrintStream actualOutput, boolean isError, boolean useTimer) {
            this.textPane = textPane;
            this.actualOutput = actualOutput;
            this.isError = isError;
            if (useTimer) {
                this.timer = new Timer(500, e -> flush());
                this.timer.setRepeats(true);
                this.timer.start();
            } else {
                this.timer = null;
            }
        }

        @Override
        public void write(int b) {
            writeString(new String(new byte[] { (byte) b }));
        }

        @Override
        public void write(byte[] b) {
            writeString(new String(b));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writeString(new String(b, off, len));
        }

        private synchronized void writeString(String string) {
            actualOutput.print(string);
            buffer.append(string);
        }

        @Override
        public void flush() {
            String text;
            synchronized (this) {
                if (buffer.length() == 0)
                    return;
                text = buffer.toString();
                buffer.setLength(0);
            }
            append(text);
        }

        private void append(String text) {
            StyledDocument doc = textPane.getStyledDocument();
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

                if (color != null) {
                    StyleConstants.setForeground(attrs, color);
                }

                try {
                    doc.insertString(doc.getLength(), line, attrs);
                } catch (BadLocationException e) {
                    // ignore
                }
            }

            Element root = doc.getDefaultRootElement();
            while (root.getElementCount() > OUTPUT_MAX_LINES) {
                try {
                    Element firstLine = root.getElement(0);
                    doc.remove(0, firstLine.getEndOffset());
                } catch (BadLocationException e) {
                    break;
                }
            }
            textPane.setCaretPosition(doc.getLength());
        }
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
}
