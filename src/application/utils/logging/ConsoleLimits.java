package application.utils.logging;

import java.util.Properties;

/**
 * The single source of truth (SSOT) for GUI console sizing (F4 / decision D5).
 * <p>
 * Historically the GUI console had several independent, drifting limits — a char
 * buffer in the logging profile ({@code *.gui.consoleLogSize}), a line count in the
 * user JSON ({@code ConsoleSettings.maxConsoleLines}), and hard-coded
 * {@code DEFAULT_MAX_LINES} constants in each console subscriber. This class is the
 * one place that defines "how many lines a console keeps" and derives the other
 * sizes from it, so they can no longer diverge:
 * <ul>
 *   <li>{@code console.maxLines} — the system (JVM-global) console line buffer (SSOT);</li>
 *   <li>{@code node.console.maxLines} — the per-node console line buffer (SSOT);</li>
 *   <li>the document <b>char cap</b> ({@code *.gui.consoleLogSize}) is
 *       {@link #deriveCharCap(int) derived} from the line count;</li>
 *   <li>the <b>replay buffer</b> (bootstrap lines retained for late subscribers) is
 *       {@link #deriveReplayCapacity(int) derived} from the line count.</li>
 * </ul>
 * </p>
 * <p>
 * These are pure helpers over an already-resolved effective profile
 * ({@code Properties}); the caller is responsible for supplying the L0+L1(+L2) merged
 * properties for the right context (system vs. node).
 * </p>
 *
 * @see SystemLoggingProfile
 */
public final class ConsoleLimits {

    /** System (JVM-global) console line-buffer key (SSOT). */
    public static final String SYSTEM_MAX_LINES_KEY = "console.maxLines";
    /** Per-node console line-buffer key (SSOT). */
    public static final String NODE_MAX_LINES_KEY = "node.console.maxLines";
    /** L0 fallback for the system console line buffer. */
    public static final int DEFAULT_SYSTEM_MAX_LINES = 1500;
    /** L0 fallback for the node console line buffer. */
    public static final int DEFAULT_NODE_MAX_LINES = 3000;

    /** Approximate characters per console line, used to derive the char cap. */
    static final int CHARS_PER_LINE = 80;
    /** Floor for the derived char cap (never smaller than this). */
    static final int MIN_CHAR_CAP = 10_000;

    private ConsoleLimits() {
        // no instances — pure static helpers
    }

    /**
     * Resolves a console line-buffer size from an effective logging profile.
     *
     * @param profile  the effective (L0+L1+L2 merged) profile properties (may be null/empty)
     * @param key      the SSOT key ({@link #SYSTEM_MAX_LINES_KEY} or {@link #NODE_MAX_LINES_KEY})
     * @param fallback the value used when the key is absent or not a positive integer (the L0 default)
     * @return a positive line count, or {@code fallback}
     */
    public static int resolveMaxLines(Properties profile, String key, int fallback) {
        if (profile != null) {
            String raw = profile.getProperty(key);
            if (raw != null) {
                try {
                    int value = Integer.parseInt(raw.trim());
                    if (value > 0) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to the fallback
                }
            }
        }
        return fallback;
    }

    /** Resolves the SYSTEM console line buffer (falls back to {@link #DEFAULT_SYSTEM_MAX_LINES}). */
    public static int systemMaxLines(Properties profile) {
        return resolveMaxLines(profile, SYSTEM_MAX_LINES_KEY, DEFAULT_SYSTEM_MAX_LINES);
    }

    /** Resolves the NODE console line buffer (falls back to {@link #DEFAULT_NODE_MAX_LINES}). */
    public static int nodeMaxLines(Properties profile) {
        return resolveMaxLines(profile, NODE_MAX_LINES_KEY, DEFAULT_NODE_MAX_LINES);
    }

    /**
     * Derives the document char cap (the {@code *.gui.consoleLogSize} value) from the
     * line-buffer size, so the char cap and the line count stay consistent (F4 / D5).
     *
     * @param maxLines the console line-buffer size (the SSOT value)
     * @return the char cap (never below {@link #MIN_CHAR_CAP})
     */
    public static int deriveCharCap(int maxLines) {
        if (maxLines <= 0) {
            return MIN_CHAR_CAP;
        }
        return Math.max(MIN_CHAR_CAP, maxLines * CHARS_PER_LINE);
    }

    /**
     * Derives the replay-buffer capacity (how many bootstrap lines are retained for
     * late subscribers) from the line-buffer size (F4 / D5).
     *
     * @param maxLines the console line-buffer size (the SSOT value)
     * @return the replay capacity (the line-buffer size, floored at 0)
     */
    public static int deriveReplayCapacity(int maxLines) {
        return Math.max(0, maxLines);
    }
}
