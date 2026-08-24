package application.module.node.gui;

import java.awt.Color;
import javax.swing.text.StyledDocument;

import application.utils.logging.ConsoleColorScheme;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.gui.BaseConsoleSubscriber;

/**
 * Aggregating log subscriber that collects events from ALL running profiles
 * and displays them in a single shared console with profile-based color coding.
 * <p>
 * Architecture:
 * <pre>
 *   SystemLogger ─→ SystemConsoleSubscriber ─→ LogEventBatcher ─→ EDT ─→ StyledDocument
 *   ProfileLogger("node.mainnet") ──┐
 *   ProfileLogger("node.testnet")  ──┤──→ (forwardToSystem)
 *   ProfileLogger("database.x")    ──┘
 * </pre>
 * <p>
 * <h3>Key Differences from {@link ProfileConsoleSubscriber}</h3>
 * <ul>
 *   <li><b>Multi-profile:</b> Receives events from all registered profiles, not just one.</li>
 *   <li><b>Profile tag:</b> Each line is prefixed with a colored profile identifier.</li>
 *   <li><b>Dynamic filter:</b> Supports runtime filter changes via {@link BaseConsoleSubscriber#setFilter(LogFilter)}.</li>
 *   <li><b>Color scheme:</b> Uses {@link ConsoleColorScheme} for per-profile color coding.</li>
 * </ul>
 * </p>
 * <p>
 * <h3>Thread Safety</h3>
 * Events arrive from arbitrary threads. All StyledDocument mutations are dispatched
 * to the EDT via {@link application.utils.logging.event.LogEventBatcher} with time/count thresholds.
 * </p>
 * <p>
 * <h3>Lifecycle</h3>
 * Created once for the global SystemConsole panel. When disposed,
 * {@link BaseConsoleSubscriber#dispose()} flushes remaining buffered events and stops the batcher.
 * </p>
 * <p>
 * <h3>Log Format</h3>
 * Uses {@link application.utils.logging.TerminalFormatLogFormatter} to produce terminal-matching output with profile tag:
 * <pre>
 *   [INFO] 2026-07-12 13:52:20 <hdhdh>: application.module.node.Signum - Initializing...
 * </pre>
 * </p>
 *
 * @see ConsoleColorScheme
 * @see ProfileConsoleSubscriber
 * @see BaseConsoleSubscriber
 */
public final class SystemConsoleSubscriber extends BaseConsoleSubscriber {

    /** Default maximum console line count to prevent unbounded memory growth */
    public static final int DEFAULT_MAX_LINES = 1000;

    // ── Level-based color scheme (same base as ProfileConsoleSubscriber) ──

    private static final Color COLOR_ERROR = new Color(244, 86, 93);
    private static final Color COLOR_WARN  = new Color(255, 187, 0);
    private static final Color COLOR_INFO  = null;
    private static final Color COLOR_DEBUG = new Color(160, 160, 160);

    // ── Fields ───────────────────────────────────────────────────────────

    /** Per-profile color scheme for blended coloring */
    private final ConsoleColorScheme colorScheme;

    // ── Constructors ─────────────────────────────────────────────────────

    /**
     * Creates a subscriber using the default ConsoleColorScheme.
     *
     * @param document the target StyledDocument (never null)
     */
    public SystemConsoleSubscriber(StyledDocument document) {
        this(document, DEFAULT_MAX_LINES, null);
    }

    /**
     * Creates a subscriber with custom settings.
     *
     * @param document the target StyledDocument (never null)
     * @param maxLines maximum line count before trimming oldest lines
     * @param filter   optional initial event filter (null = accept all)
     */
    public SystemConsoleSubscriber(StyledDocument document, int maxLines, LogFilter filter) {
        this(ConsoleColorScheme.getDefault(), document, maxLines, filter);
    }

    /**
     * Creates a subscriber with a custom ConsoleColorScheme.
     *
     * @param colorScheme the color scheme for profile coloring (never null)
     * @param document    the target StyledDocument (never null)
     * @param maxLines    maximum line count before trimming
     * @param filter      optional initial event filter (null = accept all)
     */
    public SystemConsoleSubscriber(ConsoleColorScheme colorScheme, StyledDocument document,
                                   int maxLines, LogFilter filter) {
        super(document, maxLines, filter);

        if (colorScheme == null) {
            throw new NullPointerException("ConsoleColorScheme must not be null");
        }
        this.colorScheme = colorScheme;
    }

    // ── Template Method Hooks ────────────────────────────────────────────

    @Override
    protected String formatLine(LogEvent event) {
        return formatAggregatedLine(event);
    }

    @Override
    protected Color resolveLineColor(LogEvent event) {
        // Composite color: blend profile color with level color.
        // Key the color by the qualified (module.profile) name, falling back to the
        // bare profile name for legacy custom colors keyed by the bare name.
        String profile = event.getProfileName();
        String module = event.getModule();
        String qualified = (module != null && profile != null && !profile.isEmpty())
                ? module + "." + profile
                : profile;
        Color profileColor = colorScheme.resolveEventColor(qualified, profile);
        Color levelColor = resolveLevelColor(event.getLevel());
        return blendColors(profileColor, levelColor);
    }

    // ── Log Line Formatting (Aggregated View) ───────────────────────────

    /**
     * Formats a log event for the aggregated system console.
     * Uses {@link application.utils.logging.TerminalFormatLogFormatter} with profile tag to match terminal output:
     * <pre>
     *   [LEVEL] yyyy-MM-dd HH:mm:ss <profile>: loggerName - message
     * </pre>
     * For events without a profile, uses "<system>" as the tag.
     */
    private String formatAggregatedLine(LogEvent event) {
        String profile = event.getProfileName();
        String module = event.getModule();
        // Prefer the qualified (module.profile) tag; fall back to the bare profile
        // name, then to "system" for unscoped/bootstrap events.
        String profileTag;
        if (module != null && profile != null && !profile.isEmpty()) {
            profileTag = module + "." + profile;
        } else if (profile != null && !profile.isEmpty()) {
            profileTag = profile;
        } else {
            profileTag = "system";
        }
        return formatter.formatWithProfile(event, profileTag);
    }

    // ── Color Resolution & Blending ─────────────────────────────────────

    /**
     * Resolves the base color for a given log level.
     */
    private static Color resolveLevelColor(LogLevel level) {
        switch (level) {
            case ERROR: return COLOR_ERROR;
            case WARN:  return COLOR_WARN;
            case DEBUG:
            case TRACE: return COLOR_DEBUG;
            case INFO:
            default:    return COLOR_INFO; // null = use profile color only
        }
    }

    /**
     * Blends a profile color with a level color.
     * If levelColor is null (INFO/default), returns the profile color unchanged.
     * Otherwise, averages the RGB components for a composite hint.
     */
    private static Color blendColors(Color profileColor, Color levelColor) {
        if (levelColor == null) {
            return profileColor;
        }
        if (profileColor == null) {
            return levelColor;
        }

        int r = (profileColor.getRed() + levelColor.getRed()) >> 1;
        int g = (profileColor.getGreen() + levelColor.getGreen()) >> 1;
        int b = (profileColor.getBlue() + levelColor.getBlue()) >> 1;
        return new Color(r, g, b);
    }

    // ── Convenience Methods ─────────────────────────────────────────────

    /** @return the ConsoleColorScheme used by this subscriber */
    public ConsoleColorScheme getColorScheme() {
        return colorScheme;
    }

    @Override
    public String toString() {
        return "SystemConsoleSubscriber{disposed=" + disposed + ", filter=" + filter + '}';
    }
}