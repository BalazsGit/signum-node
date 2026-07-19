package application.utils.gui.console;

import java.util.function.Consumer;

import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.event.LogFilter;

/**
 * Centralized configuration for UnifiedConsolePanel instances.
 * <p>
 * All properties are mutable via fluent setters for runtime reconfiguration.
 * Use factory methods {@link #systemConsole()} or {@link #profileConsole(String)}
 * to create pre-configured instances, then chain {@code with...} setters.
 * </p>
 * <p>
 * <h3>Usage Example</h3>
 * <pre>
 * ConsolePanelConfiguration config = ConsolePanelConfiguration.systemConsole()
 *     .withShowFilterHeader(true)
 *     .withShowCommandInput(true)
 *     .withMaxLines(1000)
 *     .withCommandHandler(cmd -> processCommand(cmd));
 * </pre>
 *
 * @see application.utils.gui.console.UnifiedConsolePanel
 */
public final class ConsolePanelConfiguration {

    // ── Properties ───────────────────────────────────────────────────────

    private boolean showFilterHeader = true;
    private boolean showCommandInput = false;
    private ConsoleInputPosition commandPosition = ConsoleInputPosition.BOTTOM;
    private String profileName;                // null = system console
    private String title;
    private int maxLines = 500;
    private LogFilter initialFilter;
    private ConsoleColorScheme colorScheme;
    private Consumer<String> commandHandler;
    private boolean enableSmartScroll = true;
    
    // ── Command Panel Animation & Visibility Control ─────────────────────
    private boolean animateCommandInput = true;     // Smooth expand/collapse animation
    private boolean commandInputVisible = false;    // Initial visibility state
    private boolean enableCommandToggle = true;     // Show toggle in hamburger menu

    // ── Private Constructor ──────────────────────────────────────────────

    private ConsolePanelConfiguration() {
        // Instances created via factory methods only
    }

    // ── Factory Methods (personalized initialization) ────────────────────

    /**
     * Creates a configuration preset for a system-wide console that aggregates
     * logs from all profiles. Filter header enabled by default.
     */
    public static ConsolePanelConfiguration systemConsole() {
        ConsolePanelConfiguration config = new ConsolePanelConfiguration();
        config.showFilterHeader = true;
        config.title = "System Console";
        config.maxLines = 1000;
        return config;
    }

    /**
     * Creates a configuration preset for a single-profile console.
     *
     * @param name the profile name (never null)
     */
    public static ConsolePanelConfiguration profileConsole(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Profile name must not be null or empty");
        }
        ConsolePanelConfiguration config = new ConsolePanelConfiguration();
        config.profileName = name;
        config.title = "Console: " + name;
        config.showFilterHeader = true;
        config.maxLines = 500;
        return config;
    }

    // ── Fluent Setters (chainable, runtime reconfiguration) ──────────────

    /** Shows or hides the filter header toolbar. */
    public ConsolePanelConfiguration withShowFilterHeader(boolean value) {
        this.showFilterHeader = value;
        return this;
    }

    /** Shows or hides the command input panel. */
    public ConsolePanelConfiguration withShowCommandInput(boolean value) {
        this.showCommandInput = value;
        return this;
    }

    /** Sets the vertical position of the command input panel. */
    public ConsolePanelConfiguration withCommandPosition(ConsoleInputPosition position) {
        if (position == null) {
            throw new NullPointerException("Command position must not be null");
        }
        this.commandPosition = position;
        return this;
    }

    /** Sets the maximum line count before oldest lines are trimmed. */
    public ConsolePanelConfiguration withMaxLines(int lines) {
        if (lines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive, got: " + lines);
        }
        this.maxLines = lines;
        return this;
    }

    /** Sets the initial log event filter applied at startup. */
    public ConsolePanelConfiguration withInitialFilter(LogFilter filter) {
        this.initialFilter = filter;
        return this;
    }

    /** Sets the color scheme used for profile-based coloring. */
    public ConsolePanelConfiguration withColorScheme(ConsoleColorScheme scheme) {
        this.colorScheme = scheme;
        return this;
    }

    /** Sets the handler invoked when the user submits a command. */
    public ConsolePanelConfiguration withCommandHandler(Consumer<String> handler) {
        this.commandHandler = handler;
        return this;
    }

    /** Enables or disables smart auto-scroll behavior. */
    public ConsolePanelConfiguration withEnableSmartScroll(boolean enabled) {
        this.enableSmartScroll = enabled;
        return this;
    }

    /** Sets the console panel title. */
    public ConsolePanelConfiguration withTitle(String title) {
        this.title = title;
        return this;
    }

    /** Enables or disables smooth expand/collapse animation for command input. */
    public ConsolePanelConfiguration withAnimateCommandInput(boolean enabled) {
        this.animateCommandInput = enabled;
        return this;
    }

    /** Sets the initial visibility state of the command input panel. */
    public ConsolePanelConfiguration withCommandInputVisible(boolean visible) {
        this.commandInputVisible = visible;
        return this;
    }

    /** Controls whether the command toggle checkbox appears in the hamburger menu. */
    public ConsolePanelConfiguration withEnableCommandToggle(boolean enabled) {
        this.enableCommandToggle = enabled;
        return this;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    /** @return true if the filter header toolbar should be shown */
    public boolean isShowFilterHeader() {
        return showFilterHeader;
    }

    /** @return true if the command input panel should be shown */
    public boolean isShowCommandInput() {
        return showCommandInput;
    }

    /** @return the vertical position of the command input panel */
    public ConsoleInputPosition getCommandPosition() {
        return commandPosition;
    }

    /** @return the profile name (null for system console) */
    public String getProfileName() {
        return profileName;
    }

    /** @return the console panel title */
    public String getTitle() {
        return title;
    }

    /** @return the maximum line count before trimming */
    public int getMaxLines() {
        return maxLines;
    }

    /** @return the initial log filter (null = accept all) */
    public LogFilter getInitialFilter() {
        return initialFilter;
    }

    /** @return the color scheme for profile coloring */
    public ConsoleColorScheme getColorScheme() {
        return colorScheme;
    }

    /** @return the command handler (null if no input configured) */
    public Consumer<String> getCommandHandler() {
        return commandHandler;
    }

    /** @return true if smart auto-scroll is enabled */
    public boolean isEnableSmartScroll() {
        return enableSmartScroll;
    }

    /** @return true if command input animation is enabled */
    public boolean isAnimateCommandInput() {
        return animateCommandInput;
    }

    /** @return true if command input should be visible at startup */
    public boolean isCommandInputVisible() {
        return commandInputVisible;
    }

    /** @return true if command toggle checkbox should appear in menu */
    public boolean isEnableCommandToggle() {
        return enableCommandToggle;
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "ConsolePanelConfiguration{" +
                "title='" + title + '\'' +
                ", profileName='" + profileName + '\'' +
                ", showFilterHeader=" + showFilterHeader +
                ", showCommandInput=" + showCommandInput +
                ", commandPosition=" + commandPosition +
                ", animateCommandInput=" + animateCommandInput +
                ", commandInputVisible=" + commandInputVisible +
                ", enableCommandToggle=" + enableCommandToggle +
                ", maxLines=" + maxLines +
                ", enableSmartScroll=" + enableSmartScroll +
                '}';
    }
}