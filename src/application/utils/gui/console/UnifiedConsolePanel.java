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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
    /** Concrete subscriber class, retained so {@link #recreateSubscriber()} can rebuild after dispose() */
    private final Class<? extends BaseConsoleSubscriber> subscriberType;
    private ConsoleFilterHeader filterHeader;
    private ConsoleInputPanel inputPanel;

    /** Live "find in console" highlighter (search field + chevron navigation) */
    private SearchHighlighter searchHighlighter;

    /** Debounced timer that re-runs the active search after new content is appended */
    private javax.swing.Timer searchRehighlightTimer;

    /** Document length when the highlight was last (re-)applied; distinguishes
     *  content changes from attribute-only (highlight) changes */
    private int lastRehighlightedLength = 0;

    /**
     * Whether the active match of the current query has already been the
     * target of a navigation request (Enter or chevron). Reset on every
     * search text change, so the first Enter after a change scrolls to the
     * first (already active) match while later Enters advance.
     */
    private boolean searchAnchorConsumed = false;

    /** Timer driving the animated scroll to the active search match (EDT) */
    private javax.swing.Timer searchScrollTimer;

    /** Scrollbar value expected from the running search scroll animation;
     *  any other adjustment event means the user scrolled (cancel animation) */
    private int searchScrollExpectedValue = -1;

    /** Animation tick interval (ms) for the scroll-to-match animation */
    private static final int SEARCH_SCROLL_TICK_MS = 16;

    /** Animated scroll speed (px/s) for the scroll-to-match animation */
    private static final int SEARCH_SCROLL_SPEED_PX_PER_SEC = 3000;

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
        this.subscriberType = subscriberType;
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
        setLayout(new BorderLayout(5, 0));
        // Same EmptyBorder as the search panels (ConsoleFilterHeader / NodeConfigurationPanel):
        // thin 4px above and below, no side insets, so the distances are identical everywhere.
        // The layout's vertical gap is 0 on purpose: only the search panel's own
        // EmptyBorder(4, 0, 4, 0) creates the spacing between the filter header and the
        // console body; the horizontal gap keeps the body's left/right margins.
        setBorder(new EmptyBorder(4, 0, 4, 0));

        // Header region: contains filterHeader + optional TOP-position command input
        if (config.isShowFilterHeader() ||
            (config.isShowCommandInput() && config.getCommandPosition() == ConsoleInputPosition.TOP)) {
            headerRegion = new JPanel(new BorderLayout(0, 0));

            // Filter header at NORTH of header region
            if (config.isShowFilterHeader()) {
                filterHeader = createFilterHeader();
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
            filterHeader = createFilterHeader();
            add(filterHeader, BorderLayout.NORTH);
        }

        // Console text area: JTextPane with monospace font inside JScrollPane
        textPane = new JTextPane();
        applyConsoleFont(textPane);
        textPane.setEditable(false);
        textPane.setCaretPosition(0);

        // Live "find in console" highlighting (search header chevrons + field)
        searchHighlighter = new SearchHighlighter(textPane);
        if (filterHeader != null) {
            filterHeader.setSearchTextListener(this::onSearchTextChanged);
            filterHeader.setSearchNavigationListener(this::onSearchNavigated);
            filterHeader.setSearchEnterListener(this::onSearchEnter);
        }
        installSearchRehighlightListener();

        scrollPane = new JScrollPane(textPane);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // User scroll cancels a running scroll-to-match animation (same
        // cancellation rule as SmartScrollController's animated bottom scroll).
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (searchScrollTimer != null && searchScrollTimer.isRunning()
                    && e.getValue() != searchScrollExpectedValue) {
                stopSearchScrollAnimation();
            }
        });

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
        if (config.getDefaultCommandPrefix() != null) {
            panel.prefill(config.getDefaultCommandPrefix());
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
     * Creates the filter header honouring the per-section visibility flags
     * (Profile / Module can be disabled, e.g. in the single-profile node console).
     */
    private ConsoleFilterHeader createFilterHeader() {
        return new ConsoleFilterHeader(
                this::onFilterChanged,
                config.isShowProfileFilter(),
                config.isShowModuleFilter());
    }

    /**
     * Called by ConsoleFilterHeader when filter controls change.
     * Propagates the combined filter to the active subscriber.
     * <p>
     * The subscriber rebuilds the already-rendered lines from its retained
     * event history, so unchecking a level removes those lines and re-checking
     * restores them. Each rendered line carries its level as a document
     * attribute ({@link BaseConsoleSubscriber#LEVEL_ATTRIBUTE}).
     * </p>
     */
    private void onFilterChanged(LogFilter combinedFilter) {
        if (subscriber != null) {
            subscriber.setFilter(combinedFilter);
        }
        // setFilter() rebuilt (or scheduled a rebuild of) the document — re-run
        // the live search so the highlights and the "current/total" indicator
        // land on the rebuilt content. The invokeLater ordering guarantees it
        // runs after the EDT rebuild. NOTE: this must NOT be guarded by
        // matchCount() > 0 — a previous rebuild may have reduced the count to
        // zero while the query is still active, and the search must be
        // re-executed when the matching lines come back. reapply() is a
        // no-op when there is no active (non-blank) query, and
        // refreshMatchIndicator() keeps the label hidden for an empty field.
        if (searchHighlighter != null) {
            SwingUtilities.invokeLater(() -> {
                searchHighlighter.reapply();
                lastRehighlightedLength = textPane.getDocument().getLength();
                refreshMatchIndicator();
            });
        }
    }

    // ── Live Search (highlight + chevron navigation) ─────────────────────

    /**
     * Called by the filter header after every search-field change.
     * Highlights all matches in the console (or clears the highlight).
     */
    private void onSearchTextChanged(String text) {
        if (searchHighlighter == null) {
            return;
        }
        searchAnchorConsumed = false;
        searchHighlighter.applySearch(text);
        lastRehighlightedLength = textPane.getDocument().getLength();
        refreshMatchIndicator();
    }

    /**
     * Called by the filter header chevron buttons:
     * {@code true} = next match, {@code false} = previous match.
     */
    private void onSearchNavigated(Boolean next) {
        if (searchHighlighter == null) {
            return;
        }
        if (Boolean.TRUE.equals(next)) {
            searchHighlighter.navigateNext();
        } else {
            searchHighlighter.navigatePrevious();
        }
        searchAnchorConsumed = true;
        refreshMatchIndicator();
        animateScrollToActiveMatch();
    }

    /**
     * Called by the filter header when the user presses Enter in the search
     * field. The first Enter after a query change scrolls to the first
     * (already active) match; each further Enter advances to the next match.
     * <p>
     * Package-private so the (same-package) tests can exercise the
     * Enter-handling semantics without dispatching synthetic key events
     * (the reduced JDK does not deliver dispatched {@code KeyEvent}s).
     * </p>
     */
    void onSearchEnter() {
        if (searchHighlighter == null || searchHighlighter.matchCount() == 0) {
            return;
        }
        if (searchAnchorConsumed) {
            searchHighlighter.navigateNext();
            refreshMatchIndicator();
        } else {
            searchAnchorConsumed = true;
        }
        animateScrollToActiveMatch();
    }

    /**
     * Installs a document listener that re-runs the active search (debounced)
     * when the console content actually changes (new lines appended, trimmed).
     * Attribute-only changes (our own highlighting) do NOT trigger a re-run.
     */
    private void installSearchRehighlightListener() {
        searchRehighlightTimer = new javax.swing.Timer(250, e -> rehighlightIfActive());
        searchRehighlightTimer.setRepeats(false);
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                checkSearchRehighlight();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkSearchRehighlight();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                checkSearchRehighlight();
            }
        };
        textPane.getDocument().addDocumentListener(listener);
    }

    /** Schedules a debounced re-highlight when the document length changed. */
    private void checkSearchRehighlight() {
        if (searchHighlighter == null || searchHighlighter.matchCount() == 0) {
            return;
        }
        int length = textPane.getDocument().getLength();
        if (length == lastRehighlightedLength) {
            return; // attribute-only change (our own highlighting) — skip
        }
        if (searchRehighlightTimer != null) {
            searchRehighlightTimer.restart();
        }
    }

    /** Re-applies the active search after the debounce delay. */
    private void rehighlightIfActive() {
        if (searchHighlighter == null || searchHighlighter.matchCount() == 0) {
            return;
        }
        searchHighlighter.reapply();
        lastRehighlightedLength = textPane.getDocument().getLength();
        refreshMatchIndicator();
    }

    /**
     * Refreshes the "current/total" match counter in the filter header from
     * the live highlighter state (e.g. {@code "1/23"}). Hidden while the
     * search field is empty.
     */
    private void refreshMatchIndicator() {
        if (filterHeader == null || searchHighlighter == null) {
            return;
        }
        String query = filterHeader.getSearchText();
        if (query == null || query.isEmpty()) {
            filterHeader.setSearchMatchIndicatorText("");
            return;
        }
        int total = searchHighlighter.matchCount();
        int active = searchHighlighter.matchCount() == 0 ? 0 : searchHighlighter.currentIndex() + 1;
        filterHeader.setSearchMatchIndicatorText(active + "/" + total);
    }

    /**
     * Smoothly scrolls the console so the active (strong) search match is
     * visible (placed in the upper third of the viewport). Driven by an EDT
     * timer at a fixed pixel speed — the same feel as
     * {@code SmartScrollController#scrollToBottomAnimated()}.
     * <p>
     * No-op when there is no active match or it is already visible.
     * The animation is cancelled by a user scroll (see the scrollbar
     * adjustment listener installed in {@link #initUI()}).
     * </p>
     */
    private void animateScrollToActiveMatch() {
        if (searchHighlighter == null) {
            return;
        }
        int[] range = searchHighlighter.currentMatchRange();
        if (range == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::animateScrollToActiveMatch);
            return;
        }
        // NOTE: reduced JDK has no JTextComponent#viewForPosition — modelToView
        // returns the same on-screen bounds directly.
        java.awt.Rectangle bounds;
        try {
            bounds = textPane.modelToView(range[0]);
        } catch (javax.swing.text.BadLocationException e) {
            return;
        }
        if (bounds == null) {
            return;
        }
        int matchY = bounds.y;
        int matchHeight = Math.max(1, bounds.height);
        javax.swing.JScrollBar bar = scrollPane.getVerticalScrollBar();
        int visibleBottom = bar.getValue() + bar.getVisibleAmount();
        if (matchY + matchHeight >= bar.getValue() && matchY <= visibleBottom) {
            return; // already visible — no scroll needed
        }
        final int targetValue = Math.max(bar.getMinimum(), Math.min(
                matchY - bar.getVisibleAmount() / 3,
                bar.getMaximum() - bar.getVisibleAmount()));
        final int step = Math.max(1, SEARCH_SCROLL_SPEED_PX_PER_SEC * SEARCH_SCROLL_TICK_MS / 1000);
        final boolean down = targetValue > bar.getValue();
        stopSearchScrollAnimation();
        searchScrollTimer = new javax.swing.Timer(SEARCH_SCROLL_TICK_MS, e -> {
            javax.swing.JScrollBar b = scrollPane.getVerticalScrollBar();
            int current = b.getValue();
            int next = down ? Math.min(current + step, targetValue) : Math.max(current - step, targetValue);
            searchScrollExpectedValue = next;
            b.setValue(next);
            if (next == targetValue) {
                stopSearchScrollAnimation();
            }
        });
        searchScrollTimer.setRepeats(true);
        searchScrollTimer.start();
    }

    /** Stops a running scroll-to-match animation (safe when none is running). */
    private void stopSearchScrollAnimation() {
        if (searchScrollTimer != null && searchScrollTimer.isRunning()) {
            searchScrollTimer.stop();
        }
        searchScrollExpectedValue = -1;
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
        headerRegion = new JPanel(new BorderLayout(0, 0));

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
     * Returns the command input panel, or {@code null} if it has not been
     * created yet (lazy initialization on first show / TOP position config).
     */
    public ConsoleInputPanel getCommandInputPanel() {
        return inputPanel;
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

        // An interrupted or earlier position change may have left a pending collapse
        // callback behind — cancel it so it cannot reposition (or force-show) the
        // panel after a superseding state change (e.g. a quick double toggle).
        inputPanel.setOnCollapsedListener(null);

        // The show/hide state is the single source of truth here: a position change
        // must PRESERVE the current visibility, never force the panel open.
        if (isCommandInputVisible() && config.isAnimateCommandInput()) {
            // Visible: animated collapse at the old slot, reposition, expand at the new slot
            animatePositionChange(position);
        } else {
            // Hidden (or animation disabled): move the zero-height panel to the new
            // slot silently — no animation, no forced show. A collapse animation that
            // is still in flight simply continues to zero height at the new slot.
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
        // Preserve the visibility that was in effect when the change started:
        // the panel must end up exactly as visible as it was before the move.
        final boolean restoreVisibility = inputPanel.isExpanded();
        // Wire callback: after collapse finishes, reposition then restore visibility
        inputPanel.setOnCollapsedListener(() -> {
            try {
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
                // Restore visibility at the new position
                if (restoreVisibility) {
                    inputPanel.show(true);
                }
            } finally {
                // Clear callback after use (defensive: must never leak, even on error)
                inputPanel.setOnCollapsedListener(null);
            }
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
        // Re-color active search highlights for the new (light/dark) palette
        if (searchHighlighter != null && searchHighlighter.matchCount() > 0) {
            searchHighlighter.reapply();
            lastRehighlightedLength = textPane.getDocument().getLength();
            refreshMatchIndicator();
        }
    }

    /**
     * Releases all resources held by this panel.
     * Disposes the subscriber, detaches scroll controller, stops fade animations, and clears references.
     */
    public void dispose() {
        if (searchRehighlightTimer != null && searchRehighlightTimer.isRunning()) {
            searchRehighlightTimer.stop();
        }
        stopSearchScrollAnimation();
        if (searchHighlighter != null) {
            searchHighlighter.clear();
        }
        if (scrollToBottomButton != null) {
            scrollToBottomButton.stopFadeAnimation();
        }
        if (subscriber != null) {
            subscriber.dispose();
            subscriber = null;
        }
    }

    /**
     * Recreates the console subscriber after {@link #dispose()}.
     * <p>
     * Binds a fresh subscriber to the same text document and smart-scroll wiring.
     * The caller is responsible for re-attaching the new subscriber to its log
     * source (e.g. {@code ProfileLogger.addSubscriber(...)}) — {@link #dispose()}
     * only releases this panel's own references.
     * </p>
     * <p>
     * Used to self-heal the console when Swing re-attaches the panel to the
     * hierarchy (addNotify) after a removeNotify() that disposed the subscriber.
     * </p>
     */
    public void recreateSubscriber() {
        if (subscriber != null) {
            subscriber.dispose();
            subscriber = null;
        }
        createSubscriber(subscriberType);
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
                    // Scroll to bottom at a fixed speed and resume following
                    if (subscriber != null && subscriber.getScrollController() != null) {
                        subscriber.getScrollController().scrollToBottomAnimated();
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