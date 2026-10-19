package application.utils.logging;

import application.utils.logging.event.LevelFilter;
import application.utils.logging.event.LogEvent;
import application.utils.logging.event.LogFilter;
import application.utils.logging.event.LogFormatter;
import application.utils.logging.event.LogLevel;
import application.utils.logging.event.LogSubscriber;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Per-profile file appender — writes the log events of ONE profile into its own
 * file: {@code {logDir}/{profile}.log}.
 * <p>
 * This is the runtime half of the per-node file logging feature: node logs
 * converge into the per-profile {@link ProfileLogger} (direct module logging and
 * the JUL bridge via {@code NodeLogContext}), and this subscriber attaches to
 * that bus, so each node profile gets a physically separate log file without
 * any JUL-level reconfiguration.
 * </p>
 * <p>
 * <b>Level:</b> only events at or above the configured minimum level are
 * written (default INFO). The filter is published via {@link #getFilter()} so
 * the dispatcher pre-filters, with a defensive re-check in {@link #onLogEvent}.
 * </p>
 * <p>
 * <b>Rotation:</b> size-based. When the file reaches {@code limitBytes} (0 =
 * unlimited) it is moved to {@code {profile}.log.1} (older rotations are
 * shifted up to {@code count}, the oldest one dropped) and a fresh file is
 * started.
 * </p>
 * <p>
 * <b>Fail-soft:</b> an unopenable file never throws and never breaks the node —
 * the appender goes inactive (see {@link #isActive()}) and drops events. Errors
 * from this appender are deliberately NOT written back into the profile log
 * (circular logging guard); the owner (e.g. {@code Signum}) reports inactivity
 * once at attach time.
 * </p>
 * <p>
 * <b>Thread safety:</b> subscribers may be invoked from any thread — all file
 * access happens under a single lock.
 * </p>
 *
 * @see ProfileLogger
 * @see NodeLoggerRegistry
 */
public final class ProfileFileAppender implements LogSubscriber {

    /** The profile (node) name this appender writes for — used as the file name. */
    private final String profileName;

    /** The active log file: {@code {logDir}/{profile}.log}. */
    private final Path file;

    /** Rotation limit in bytes; 0 means unlimited (no rotation). */
    private final long limitBytes;

    /** Number of rotation files kept (1 = only {@code .1}). */
    private final int count;

    /** Minimum level to write (also published as the subscriber filter). */
    private final LogLevel minLevel;

    /**
     * A private formatter instance delegating to the shared
     * {@link TerminalFormatLogFormatter#INSTANCE} (thread-safe, stateless) —
     * a distinct identity keeps the per-appender render cache in
     * {@link LogEvent#getRenderedText(LogFormatter)} isolated from the GUI
     * consoles' cache.
     */
    private final LogFormatter formatter = TerminalFormatLogFormatter.INSTANCE::format;

    /** Guards the writer and the rotation sequence. */
    private final Object writeLock = new Object();

    /** The open writer, or null when the appender is inactive (open failed / disposed). */
    private BufferedWriter writer;

    /** Bytes written into the current file (approximation, for the rotation check). */
    private long writtenBytes;

    private volatile boolean disposed;

    /**
     * Creates the appender and opens the log file (creating the directory when
     * needed). A failure to open does NOT throw — {@link #isActive()} reports
     * the state and events are dropped until disposal.
     *
     * @param profileName  the profile name (used as the file name; unsafe file-name
     *                     characters are replaced), never null
     * @param logDir       the directory the file is written to (e.g. {@code logs/node}),
     *                     never null
     * @param minLevel     the minimum level to write (e.g. {@link LogLevel#INFO}), never null
     * @param limitBytes   rotation limit in bytes, 0 for unlimited
     * @param count        rotations to keep (at least 1)
     */
    public ProfileFileAppender(String profileName, Path logDir, LogLevel minLevel,
            long limitBytes, int count) {
        if (profileName == null || logDir == null || minLevel == null) {
            throw new NullPointerException("profileName, logDir and minLevel must not be null");
        }
        this.profileName = profileName;
        this.file = logDir.resolve(sanitizeFileName(profileName) + ".log");
        this.limitBytes = Math.max(0, limitBytes);
        this.count = Math.max(1, count);
        this.minLevel = minLevel;
        open();
    }

    /** @return true when the log file is open and events are being written. */
    public boolean isActive() {
        synchronized (writeLock) {
            return writer != null;
        }
    }

    /**
     * Parses a level name from the {@code node.fileLog.level} property into a
     * {@link LogLevel}. Accepted names: TRACE, DEBUG, INFO, WARN(ING), ERROR,
     * OFF (case-insensitive); unrecognized or missing values fall back to
     * {@link LogLevel#INFO} (the safe default).
     *
     * @param name the level name, may be null or blank
     * @return the parsed level, never null
     */
    public static LogLevel parseLevel(String name) {
        if (name == null || name.isBlank()) {
            return LogLevel.INFO;
        }
        String trimmed = name.trim();
        if (trimmed.equalsIgnoreCase("WARNING")) {
            return LogLevel.WARN;
        }
        return LogLevel.fromSlf4jName(trimmed);
    }

    /** @return the log file this appender writes to. */
    public Path getFile() {
        return file;
    }

    @Override
    public LogFilter getFilter() {
        return LevelFilter.atLeast(minLevel);
    }

    @Override
    public void onLogEvent(LogEvent event) {
        if (event == null || !event.getLevel().isAtLeast(minLevel)) {
            return;
        }
        synchronized (writeLock) {
            if (disposed || writer == null) {
                return;
            }
            try {
                if (limitBytes > 0 && writtenBytes >= limitBytes) {
                    rotate();
                    if (writer == null) {
                        return; // rotation failed — the appender is inactive now
                    }
                }
                String line = event.getRenderedText(formatter);
                writer.write(line);
                if (!line.endsWith("\n")) {
                    writer.newLine();
                }
                writer.flush();
                writtenBytes += line.length();
            } catch (IOException e) {
                closeWriterQuietly(); // go inactive — never throw from a log path
            }
        }
    }

    @Override
    public void dispose() {
        synchronized (writeLock) {
            if (disposed) {
                return;
            }
            disposed = true;
            closeWriterQuietly();
        }
    }

    /** Opens the log file in append mode (creating the parent directory); fail-soft. */
    private void open() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writtenBytes = Files.size(file);
        } catch (IOException e) {
            writer = null; // inactive — events will be dropped, the owner reports once
        }
    }

    /** Shifts the rotation chain ({@code .1} ← current, oldest dropped) and reopens. */
    private void rotate() throws IOException {
        closeWriterQuietly();
        if (count <= 1) {
            Files.deleteIfExists(rotated(1));
        } else {
            Files.deleteIfExists(rotated(count - 1));
            for (int i = count - 2; i >= 1; i--) {
                Path old = rotated(i);
                if (Files.exists(old)) {
                    Files.move(old, rotated(i + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (Files.exists(file)) {
            Files.move(file, rotated(1), StandardCopyOption.REPLACE_EXISTING);
        }
        open();
        if (writer != null) {
            writtenBytes = 0;
        }
    }

    private Path rotated(int index) {
        return file.resolveSibling(file.getFileName().toString() + "." + index);
    }

    private void closeWriterQuietly() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // closing best-effort — the appender goes inactive either way
        }
        writer = null;
    }

    /** Replaces file-name-unsafe characters so arbitrary profile names cannot escape logDir. */
    private static String sanitizeFileName(String profileName) {
        return profileName.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
}