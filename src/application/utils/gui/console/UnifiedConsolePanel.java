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

import javax.swing.JLayeredPane;
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

        // Wrap console area in a JLayeredPane so the floating scroll-to-bottom button
        // can overlay the text output without interfering with scrolling or selection.
        final JLayeredPane layeredPane = new JLayeredPane();

        // Add JScrollPane at the default (bottom) layer. In a JLayeredPane (null layout),
        // we must set explicit bounds so the scroll pane fills the entire layered area.
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        layeredPane.add(scrollPane, JLayeredPane.DEFAULT_LAYER);

        // Create and add the floating scroll-to-bottom button overlay.
        scrollToBottomButton = new ScrollToBottomButton();
        scrollToBottomButton.setVisible(false); // Hidden by default, shown by SmartScrollController
        layeredPane.add(scrollToBottomButton, JLayeredPane.PALETTE_LAYER);

        // Layout listener: whenever the layeredPane is resized by BorderLayout, update bounds
        // of all null-layout children (scrollPane fills entire area, button stays bottom-right).
        layeredPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                Dimension size = layeredPane.getSize();
                scrollPane.setBounds(0, 0, size.width, size.height);
                positionScrollToBottomButton();
            }
        });

        add(layeredPane, BorderLayout.CENTER);

        // Trigger initial layout after the first paint (BorderLayout has sized the layeredPane)
        java.awt.EventQueue.invokeLater(() -> {
            Dimension size = layeredPane.getSize();
            if (size.width > 0 && size.height > 0) {
                scrollPane.setBounds(0, 0, size.width, size.height);
                positionScrollToBottomButton();
            }
        });

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
     * using a push-based event listener (no polling). The button fades in when the controller
     * signals PAUSED + new content below, and fades out on FOLLOWING or no unread content.
     */
    private void wireScrollButtonVisibility(SmartScrollController controller) {
        // Push-based: controller calls back on state change, no timer needed
        controller.onStateChanged(show -> {
            if (scrollToBottomButton != null) {
                if (show) {
                    scrollToBottomButton.fadeIn();
                } else {
                    scrollToBottomButton.fadeOut();
                }
            }
        });
    }

    /**
     * Positions the floating scroll-to-bottom button at the bottom-right of the
     * JScrollPane viewport, accounting for the scrollbar width.
     */
    private void positionScrollToBottomButton() {
        if (scrollToBottomButton == null) {
            return;
        }
        int btnW = ScrollToBottomButton.BUTTON_SIZE;
        int btnH = ScrollToBottomButton.BUTTON_SIZE;
        int x = scrollPane.getWidth() - btnW - ScrollToBottomButton.MARGIN;
        int y = scrollPane.getHeight() - btnH - ScrollToBottomButton.MARGIN;
        // Clamp to ensure the button stays inside the pane
        x = Math.max(0, x);
        y = Math.max(0, y);
        scrollToBottomButton.setBounds(x, y, btnW, btnH);
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

    // ── Visibility Management ───────────────────────────────────────────

    /**
     * Called when this panel becomes visible (tab selected, window activated).
     * Enables smart scroll auto-follow and performs a consolidated scroll-to-bottom.
     * <p>
     * This prevents multiple competing scroll-to-bottom calls during initialization
     * by deferring the first scroll until the panel is actually shown to the user.
     * </p>
     */
    public void onPanelActivated() {
        if (subscriber != null && subscriber.getScrollController() != null) {
            subscriber.getScrollController().setPanelActive(true);
            // Single consolidated scroll after all pending EDT tasks are processed
            SwingUtilities.invokeLater(() -> scrollToBottom());
        }
    }

    /**
     * Called when this panel becomes invisible (tab deselected, window iconified).
     * Disables smart scroll auto-follow to prevent unnecessary scrolling and layout passes.
     */
    public void onPanelDeactivated() {
        if (subscriber != null && subscriber.getScrollController() != null) {
            subscriber.getScrollController().setPanelActive(false);
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
     * Disposes the subscriber, detaches scroll controller, stops fade animations, and clears references.
     */
    public void dispose() {
        if (scrollToBottomButton != null) {
            scrollToBottomButton.stopFadeAnimation();
        }
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

        /** Fade duration in milliseconds (matches ConsoleInputPanel standard) */
        private static final int FADE_DURATION_MS = 250;
        /** Fade timer tick interval in milliseconds */
        private static final int FADE_INTERVAL_MS = 10;

        /** Current alpha value (0.0 = invisible, 1.0 = fully visible) */
        private float buttonAlpha = 0f;
        /** Target alpha the animation is converging toward */
        private float targetAlpha = 0f;
        /** Swing Timer driving the fade animation on EDT */
        private javax.swing.Timer fadeTimer;

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

        // ── Fade Animation ───────────────────────────────────────────────

        /**
         * Starts fade-in animation: makes the button visible and animates alpha from 0 to 1.
         */
        void fadeIn() {
            setVisible(true);
            targetAlpha = 1.0f;
            ensureFadeTimerRunning();
        }

        /**
         * Starts fade-out animation: animates alpha from current value to 0,
         * then hides the button when animation completes.
         */
        void fadeOut() {
            targetAlpha = 0.0f;
            ensureFadeTimerRunning();
        }

        /** Starts the fade timer if not already running. */
        private void ensureFadeTimerRunning() {
            if (fadeTimer != null && fadeTimer.isRunning()) {
                return;
            }
            fadeTimer = new javax.swing.Timer(FADE_INTERVAL_MS, e -> tickFade());
            fadeTimer.setRepeats(true);
            fadeTimer.start();
        }

        /**
         * Single animation tick: advances buttonAlpha toward targetAlpha using linear steps.
         * When the target is reached (within epsilon), stops the timer and applies final state.
         */
        private void tickFade() {
            float diff = targetAlpha - buttonAlpha;
            if (Math.abs(diff) < 0.01f) {
                // Reached target — snap to exact value, stop timer, apply final visibility
                buttonAlpha = targetAlpha;
                fadeTimer.stop();
                fadeTimer = null;
                if (targetAlpha == 0f) {
                    setVisible(false);
                }
                repaint();
                return;
            }

            // Linear step per tick (FADE_INTERVAL_MS / FADE_DURATION_MS = 10/250 = 0.04)
            float step = FADE_INTERVAL_MS / (float) FADE_DURATION_MS;
            buttonAlpha += (diff > 0 ? step : -step);
            // Clamp to [0, 1]
            if (buttonAlpha < 0f) buttonAlpha = 0f;
            if (buttonAlpha > 1f) buttonAlpha = 1f;
            repaint();
        }

        /** Stops any running fade animation. Called during disposal. */
        void stopFadeAnimation() {
            if (fadeTimer != null && fadeTimer.isRunning()) {
                fadeTimer.stop();
                fadeTimer = null;
            }
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

            // The button is already positioned via setBounds in the JLayeredPane,
            // so we draw directly at (0, 0) relative to this panel's origin.

            // Base colors from theme
            Color panelBg = UIManager.getColor("Panel.background");
            Color baseIconColor = GuiColors.getButtonIcon();

            // Apply fade alpha: background max alpha is 200, icon max alpha is 255
            int bgAlpha = (int) (200 * buttonAlpha);
            int iconAlpha = (int) (255 * buttonAlpha);
            bgAlpha = Math.max(0, Math.min(255, bgAlpha));
            iconAlpha = Math.max(0, Math.min(255, iconAlpha));

            Color bgColor = new Color(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), bgAlpha);
            Color iconColor = new Color(
                    baseIconColor.getRed(), baseIconColor.getGreen(), baseIconColor.getBlue(), iconAlpha);

            g2.setColor(bgColor);
            g2.fill(new Ellipse2D.Double(2, 2, w - 4, h - 4));
            g2.setColor(iconColor);
            g2.setStroke(new java.awt.BasicStroke(1.5f));
            g2.draw(new Ellipse2D.Double(2, 2, w - 4, h - 4));

            // Draw Chevron.DOWN icon centered inside the circle
            int iconSize = Math.min(w, h) - 12; // Padding inside circle
            int iconX = (w - iconSize) / 2;
            int iconY = (h - iconSize) / 2;

            java.awt.geom.AffineTransform oldTx = g2.getTransform();
            g2.translate(iconX, iconY);
            drawChevronDown(g2, iconSize, iconSize, iconColor);
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