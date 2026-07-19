package application.utils.gui.console;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.text.StyledDocument;

import application.module.node.gui.ConsoleFilterHeader;
import application.module.node.gui.ProfileConsoleSubscriber;
import application.module.node.gui.SystemConsoleSubscriber;
import application.utils.gui.GuiColors;
import application.utils.gui.SmartScrollController;
import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.event.LogFilter;
import application.utils.logging.gui.BaseConsoleSubscriber;

/**
 * Centralized console panel that composes reusable components:
 * filter header, text display area, command input, and smart scroll.
 * <p>
 * This is the main composition class for the unified console architecture.
 * It assembles sub-components based on {@link ConsolePanelConfiguration}
 * and wires them together with a {@link BaseConsoleSubscriber}.
 * </p>
 * <p>
 * <h3>Personalized Initialization</h3>
 * <pre>
 * UnifiedConsolePanel panel = new UnifiedConsolePanel(
 *     ConsolePanelConfiguration.systemConsole()
 *         .withShowFilterHeader(true)
 *         .withShowCommandInput(true)
 *         .withMaxLines(1000)
 *         .withCommandHandler(cmd -> process(cmd)),
 *     SystemConsoleSubscriber.class
 * );
 * </pre>
 * <p>
 * <h3>Runtime Reconfiguration</h3>
 * All setter methods are EDT-safe and can be called at runtime to change
 * panel behavior without recreation.
 * </p>
 * <p>
 * <h3>Smart Auto-Scroll</h3>
 * When smart scroll is enabled, the console auto-follows new log lines only when
 * the scrollbar is near the bottom (threshold ~92%). If the user scrolls up to read
 * older logs, a floating "scroll to bottom" button appears. Clicking it resumes
 * auto-follow mode.
 * </p>
 *
 * @see ConsolePanelConfiguration
 * @see BaseConsoleSubscriber
 * @see ConsoleInputPanel
 * @see SmartScrollController
 */
public final class UnifiedConsolePanel extends JPanel {

    // ── Configuration (immutable reference) ──────────────────────────────

    private final ConsolePanelConfiguration config;

    // ── Composed Components ──────────────────────────────────────────────

    private JTextPane textPane;
    private JScrollPane scrollPane;
    private BaseConsoleSubscriber subscriber;
    private ConsoleFilterHeader filterHeader;
    private ConsoleInputPanel inputPanel;

    /** Wrapper panel for header region (filterHeader + optional top command input) */
    private JPanel headerRegion;

    /** Tracks runtime command position changes (overrides config default when non-null) */
    private ConsoleInputPosition runtimeCommandPosition;

    /** Floating scroll-to-bottom button overlay (appears when user scrolls up) */
    private ScrollToBottomButton scrollToBottomButton;

    // ── Constructor (personalized initialization) ────────────────────────

    /**
     * Creates a unified console panel configured according to the given spec.
     *
     * @param config        the panel configuration (never null)
     * @param subscriberType the concrete subscriber class to instantiate
     */
    public UnifiedConsolePanel(
            ConsolePanelConfiguration config,
            Class<? extends BaseConsoleSubscriber> subscriberType) {

        if (config == null) {
            throw new NullPointerException("Configuration must not be null");
        }
        if (subscriberType == null) {
            throw new NullPointerException("Subscriber type must not be null");
        }

        this.config = config;
        initUI();
        createSubscriber(subscriberType);
    }

    // ── UI Composition ──────────────────────────────────────────────────

    /**
     * Builds the complete panel layout:
     * <pre>
     *   NORTH (headerRegion):
     *     - ConsoleFilterHeader (optional)
     *     - ConsoleInputPanel if TOP position (below filter, inside headerRegion)
     *   CENTER: JLayeredPane → JScrollPane → JTextPane (StyledDocument)
     *           + ScrollToBottomButton overlay
     *   SOUTH:  ConsoleInputPanel if BOTTOM position
     * </pre>
     */
    private void initUI() {
        setLayout(new BorderLayout(5, 5));
        setBorder(new EmptyBorder(5, 5, 5, 5));

        // Header region: contains filterHeader + optional TOP-position command input
        if (config.isShowFilterHeader() ||
            (config.isShowCommandInput() && config.getCommandPosition() == ConsoleInputPosition.TOP)) {
            headerRegion = new JPanel(new BorderLayout(0, 2));

            // Filter header at NORTH of header region
            if (config.isShowFilterHeader()) {
                filterHeader = new ConsoleFilterHeader(this::onFilterChanged);
                headerRegion.add(filterHeader, BorderLayout.NORTH);
            }

            // Command input at TOP position: placed below filter inside headerRegion
            if (config.isShowCommandInput() && config.getCommandPosition() == ConsoleInputPosition.TOP) {
                inputPanel = createAndWireInputPanel();
                headerRegion.add(inputPanel, BorderLayout.CENTER);
            }

            add(headerRegion, BorderLayout.NORTH);
        } else if (config.isShowFilterHeader()) {
            // Only filter header, no wrapper needed
            filterHeader = new ConsoleFilterHeader(this::onFilterChanged);
            add(filterHeader, BorderLayout.NORTH);
        }

        // Console text area: JTextPane with monospace font inside JScrollPane
        textPane = new JTextPane();
        applyConsoleFont(textPane);
        textPane.setEditable(false);
        textPane.setCaretPosition(0);

        scrollPane = new JScrollPane(textPane);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        add(scrollPane, BorderLayout.CENTER);

        // Command input at BOTTOM position (after console output)
        if (config.isShowCommandInput() && config.getCommandPosition() == ConsoleInputPosition.BOTTOM) {
            inputPanel = createAndWireInputPanel();
            add(inputPanel, BorderLayout.SOUTH);
        }
    }

    /**
     * Creates a ConsoleInputPanel and wires it to the configured command handler.
     */
    private ConsoleInputPanel createAndWireInputPanel() {
        ConsoleInputPanel panel = new ConsoleInputPanel();
        if (config.getCommandHandler() != null) {
            panel.setCommandHandler(config.getCommandHandler());
        }
        return panel;
    }

    /**
     * Applies a monospace font to the text pane for consistent log display.
     */
    private static void applyConsoleFont(JTextPane pane) {
        Font uiFont = UIManager.getFont("TextPane.font");
        if (uiFont != null && uiFont.getFontName() != null && uiFont.getFontName().toLowerCase().contains("mono")) {
            // Already monospace from LAF
            return;
        }
        // Fallback to a standard monospace font
        Font consoleFont = new Font("Monospaced", Font.PLAIN, 12);
        pane.setFont(consoleFont);
    }

    // ── Subscriber Creation (Strategy Pattern) ──────────────────────────

    /**
     * Instantiates the appropriate concrete subscriber based on type.
     * Uses Strategy pattern: SystemConsoleSubscriber for multi-profile,
     * ProfileConsoleSubscriber for single-profile.
     */
    private void createSubscriber(Class<? extends BaseConsoleSubscriber> type) {
        StyledDocument doc = (StyledDocument) textPane.getDocument();

        if (type == SystemConsoleSubscriber.class) {
            subscriber = new SystemConsoleSubscriber(
                    ConsoleColorScheme.getDefault(),
                    doc,
                    config.getMaxLines(),
                    config.getInitialFilter());
        } else if (type == ProfileConsoleSubscriber.class) {
            subscriber = new ProfileConsoleSubscriber(
                    config.getProfileName(),
                    doc,
                    config.getMaxLines(),
                    config.getInitialFilter());
        } else {
            throw new IllegalArgumentException("Unsupported subscriber type: " + type.getName());
        }

        // Wire filter header to subscriber (filter changes propagate)
        if (filterHeader != null && subscriber != null) {
            // Already wired via ConsoleFilterHeader constructor callback
        }

        // Attach smart scroll controller if enabled
        if (config.isEnableSmartScroll() && subscriber.getScrollController() != null) {
            SmartScrollController controller = subscriber.getScrollController();
            controller.attach(scrollPane);
            // Wire the floating button visibility to scroll state
            wireScrollButtonVisibility(controller);
        }
    }

    /**
     * Wires the floating scroll-to-bottom button visibility to the SmartScrollController
     * using a push-based event listener (no polling). The button appears when the controller
     * signals PAUSED + new content below, and disappears on FOLLOWING or no unread content.
     */
    private void wireScrollButtonVisibility(SmartScrollController controller) {
        // Push-based: controller calls back on state change, no timer needed
        controller.onStateChanged(show -> {
            if (scrollToBottomButton != null) {
                scrollToBottomButton.setVisible(show);
            }
        });
    }

    // ── Filter Callback ─────────────────────────────────────────────────

    /**
     * Called by ConsoleFilterHeader when filter controls change.
     * Propagates the combined filter to the active subscriber.
     */
    private void onFilterChanged(LogFilter combinedFilter) {
        if (subscriber != null) {
            subscriber.setFilter(combinedFilter);
        }
    }

    // ── Public API (getter accessors for composed components) ────────────

    /** @return the JTextPane used for log output */
    public JTextPane getTextPane() {
        return textPane;
    }

    /** @return the JScrollPane wrapping the text pane */
    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    /** @return the active log subscriber */
    public BaseConsoleSubscriber getSubscriber() {
        return subscriber;
    }

    /** @return the filter header (null if disabled in config) */
    public ConsoleFilterHeader getFilterHeader() {
        return filterHeader;
    }

    /** @return the command input panel (null if disabled in config) */
    public ConsoleInputPanel getInputPanel() {
        return inputPanel;
    }

    /** @return the current configuration */
    public ConsolePanelConfiguration getConfig() {
        return config;
    }

    // ── Public API (setter-based runtime configuration) ─────────────────

    /**
     * Shows or hides the command input panel at runtime with animation.
     * Uses the animation setting from {@link ConsolePanelConfiguration}.
     * <p>
     * Supports lazy initialization: if the panel was not created during
     * construction (because {@code showCommandInput} was false in config),
     * it is created and added to the layout on first call with {@code show=true}.
     * </p>
     *
     * @param show true to show the input panel
     */
    public void setShowCommandInput(boolean show) {
        // Lazy initialization: create inputPanel if it doesn't exist yet
        if (show && inputPanel == null) {
            inputPanel = createAndWireInputPanel();
            // Use runtime position if set, otherwise fall back to config default
            ConsoleInputPosition effectivePosition = getEffectiveCommandPosition();
            if (effectivePosition == ConsoleInputPosition.BOTTOM) {
                add(inputPanel, BorderLayout.SOUTH);
            } else {
                // TOP position: place inside headerRegion below filterHeader
                ensureHeaderRegion();
                headerRegion.add(inputPanel, BorderLayout.CENTER);
            }
            revalidate();
            repaint();
            // Defer show animation until after layout pass computes preferredSize
            java.awt.EventQueue.invokeLater(() -> inputPanel.show(config.isAnimateCommandInput()));
            return;
        }
        if (inputPanel != null) {
            boolean animate = config.isAnimateCommandInput();
            if (show) {
                inputPanel.show(animate);
            } else {
                inputPanel.hide(animate);
            }
        }
    }

    /**
     * Ensures the headerRegion wrapper exists and is added to this panel.
     * Creates it lazily if filterHeader was already placed directly at NORTH.
     */
    private void ensureHeaderRegion() {
        if (headerRegion != null) {
            return;
        }
        // Create new header region
        headerRegion = new JPanel(new BorderLayout(0, 2));

        // If filterHeader exists and is a direct child of this panel, move it into headerRegion
        if (filterHeader != null && filterHeader.getParent() == this) {
            remove(filterHeader);
            headerRegion.add(filterHeader, BorderLayout.NORTH);
        }

        add(headerRegion, BorderLayout.NORTH);
    }

    /**
     * Toggles command input visibility with animation.
     * Convenience method for hamburger menu checkbox wiring.
     */
    public void toggleCommandInput() {
        setShowCommandInput(!isCommandInputVisible());
    }

    /**
     * Returns whether the command input panel is currently visible (expanded).
     */
    public boolean isCommandInputVisible() {
        return inputPanel != null && inputPanel.isExpanded();
    }

    /**
     * Returns whether command input is enabled in configuration.
     */
    public boolean isCommandInputEnabled() {
        return config.isShowCommandInput();
    }

    /**
     * Returns the current command position (TOP or BOTTOM).
     */
    public ConsoleInputPosition getCommandPosition() {
        return config.getCommandPosition();
    }

    /**
     * Changes the command input panel's vertical position at runtime.
     * Moves the panel between TOP (above console output)
     * and BOTTOM (below console output).
     * <p>
     * When animation is enabled ({@code config.isAnimateCommandInput()}), the panel
     * first collapses at its current location, then repositions, then expands at the new location.
     * When animation is disabled, the position change is instant.
     * </p>
     * <p>
     * This is EDT-safe: calls from background threads are delegated to the event dispatch thread.
     * </p>
     *
     * @param position the new position (never null)
     */
    public void setCommandPosition(ConsoleInputPosition position) {
        if (position == null) {
            throw new NullPointerException("Position must not be null");
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setCommandPosition(position));
            return;
        }
        if (inputPanel == null) {
            // No panel to move; just update config so lazy init uses the new position
            this.runtimeCommandPosition = position;
            return;
        }

        ConsoleInputPosition current = getEffectiveCommandPosition();
        if (current == position) {
            // No actual change
            return;
        }

        this.runtimeCommandPosition = position;

        if (config.isAnimateCommandInput()) {
            animatePositionChange(position);
        } else {
            instantPositionChange(position);
        }
    }

    /**
     * Instantly moves the input panel to the new position without animation.
     */
    private void instantPositionChange(ConsoleInputPosition position) {
        remove(inputPanel);
        if (position == ConsoleInputPosition.BOTTOM) {
            add(inputPanel, BorderLayout.SOUTH);
        } else {
            ensureHeaderRegion();
            headerRegion.add(inputPanel, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /**
     * Animates a position change: collapse at current position → reposition → expand at new position.
     * The collapse-to-reposition transition is triggered via the {@code onCollapsedListener} callback.
     */
    private void animatePositionChange(final ConsoleInputPosition newPosition) {
        // Wire callback: after collapse finishes, reposition then expand
        inputPanel.setOnCollapsedListener(() -> {
            // Remove from old position and add to new position while collapsed (zero height)
            remove(inputPanel);
            if (newPosition == ConsoleInputPosition.BOTTOM) {
                add(inputPanel, BorderLayout.SOUTH);
            } else {
                ensureHeaderRegion();
                headerRegion.add(inputPanel, BorderLayout.CENTER);
            }
            revalidate();
            repaint();
            // Expand at new position
            inputPanel.show(true);
            // Clear callback after use
            inputPanel.setOnCollapsedListener(null);
        });
        // Start collapse animation at current position
        inputPanel.hide(true);
    }

    /**
     * Returns the effective command position (runtime value if set, otherwise config default).
     */
    public ConsoleInputPosition getEffectiveCommandPosition() {
        return runtimeCommandPosition != null ? runtimeCommandPosition : config.getCommandPosition();
    }

    /**
     * Activates a new filter on the subscriber.
     *
     * @param filter the filter to apply (null = accept all)
     */
    public void setActiveFilter(LogFilter filter) {
        if (subscriber != null) {
            subscriber.setFilter(filter);
        }
    }

    /**
     * Scrolls the console to the bottom.
     */
    public void scrollToBottom() {
        if (subscriber != null && subscriber.getScrollController() != null) {
            subscriber.getScrollController().scrollToBottom();
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Re-applies appearance settings to all child components.
     * Called by AppearanceModule when theme changes.
     */
    public void applyAppearanceUpdate() {
        applyConsoleFont(textPane);
        if (filterHeader != null) {
            filterHeader.applyComponentOrientation(getComponentOrientation());
        }
    }

    /**
     * Releases all resources held by this panel.
     * Disposes the subscriber, detaches scroll controller, and clears references.
     */
    public void dispose() {
        if (subscriber != null) {
            subscriber.dispose();
            subscriber = null;
        }
    }

    // ── Floating Scroll-to-Bottom Button ─────────────────────────────────

    /**
     * Custom-drawn floating button that displays a Chevron.DOWN icon inside a circle.
     * Positioned at the bottom-right of the console viewport. Appears when the user
     * has scrolled up and there is unread content below.
     * <p>
     * Dynamically sized based on UI font size and colored via GuiColors for theme support.
     * </p>
     */
    private class ScrollToBottomButton extends JPanel {

        private static final int BUTTON_SIZE = 36;
        private static final int MARGIN = 12;

        ScrollToBottomButton() {
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
            setToolTipText("Scroll to latest logs");

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    // Scroll to bottom and resume following
                    if (subscriber != null && subscriber.getScrollController() != null) {
                        subscriber.getScrollController().scrollToBottom();
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!isVisible()) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                g2.dispose();
                return;
            }

            // Position at bottom-right with margin
            int x = scrollPane.getWidth() - w - MARGIN;
            int y = scrollPane.getHeight() - h - MARGIN;

            // Move graphics origin to button position
            g2.translate(x, y);

            // Draw circle background
            Color bgColor = new Color(
                    UIManager.getColor("Panel.background").getRed(),
                    UIManager.getColor("Panel.background").getGreen(),
                    UIManager.getColor("Panel.background").getBlue(),
                    200); // Semi-transparent
            Color borderColor = GuiColors.getButtonIcon();

            g2.setColor(bgColor);
            g2.fill(new Ellipse2D.Double(2, 2, w - 4, h - 4));
            g2.setColor(borderColor);
            g2.setStroke(new java.awt.BasicStroke(1.5f));
            g2.draw(new Ellipse2D.Double(2, 2, w - 4, h - 4));

            // Draw Chevron.DOWN icon inside the circle
            int iconSize = Math.min(w, h) - 12; // Padding inside circle
            int iconX = (w - iconSize) / 2;
            int iconY = (h - iconSize) / 2;

            // Save and translate for icon drawing
            java.awt.geom.AffineTransform oldTx = g2.getTransform();
            g2.translate(iconX, iconY);
            g2.setColor(borderColor);

            // Draw chevron using CustomDrawings symbol with custom color
            drawChevronDown(g2, iconSize, iconSize, borderColor);

            g2.setTransform(oldTx);
            g2.dispose();
        }

        /**
         * Draws a chevron-down arrow at the current graphics origin.
         */
        private void drawChevronDown(Graphics2D g2, int w, int h, Color color) {
            g2.setColor(color);
            g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));

            int cy = h / 2;
            int cx = w / 2;
            int span = Math.min(w, h) / 3;

            java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
            path.moveTo(cx - span, cy - span / 2);
            path.lineTo(cx, cy + span / 2);
            path.lineTo(cx + span, cy - span / 2);
            g2.draw(path);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            repaint();
        }
    }
}