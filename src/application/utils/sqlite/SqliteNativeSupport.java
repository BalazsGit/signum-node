package application.utils.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteJDBCLoader;

/**
 * Reliability layer for the SQLite JDBC (org.xerial) native library.
 *
 * <h2>Why this class exists</h2>
 * <p>sqlite-jdbc loads its native library ({@code sqlitejdbc.dll} on Windows)
 * effectively <b>once per JVM</b>: {@code NativeDB.load()} latches its outcome
 * in a static {@code isLoaded} flag, <i>whether the load succeeded or
 * failed</i>. The loader extracts a fresh copy of the DLL to the temp
 * directory and {@code System.load}s it; if that single attempt fails
 * transiently (e.g. antivirus / Windows Defender briefly locking a just
 * written file in {@code %TEMP%}), the failure is permanent for the whole
 * JVM. The <i>first</i> consumer only sees a wrapped
 * {@code SQLException("Error opening connection")} with a
 * {@code NativeLibraryNotFoundException} cause — which a component that is
 * allowed to degrade (the browser history store, D17) swallows by design —
 * while <i>every later</i> consumer skips the (latched) load and dies with a
 * raw, uninformative
 * {@code java.lang.UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'}
 * at the first native call (an {@link Error}, which the driver's
 * {@code catch (Exception)} does not intercept).
 *
 * <p>{@link SQLiteJDBCLoader#initialize()} — unlike the latched
 * {@code NativeDB.load()} — is retryable: after a failure it re-extracts a
 * fresh DLL and retries {@code System.load}. So the remedy is to make the
 * first load attempt happen <b>here</b>, early, in a controlled context with
 * retries — before any sqlite-jdbc consumer (browser history DB, node
 * database) gets to trigger the one-shot load.
 *
 * <p>If this class is called <i>after</i> a previous consumer already
 * triggered a failed one-shot load, a successful retry still repairs the
 * session: the driver's connection path ignores the latched boolean and the
 * native symbols become resolvable at the first native call.
 */
public final class SqliteNativeSupport {

    private static final Logger logger = LoggerFactory.getLogger(SqliteNativeSupport.class);

    /** Number of native load attempts (each one re-extracts a fresh DLL). */
    static final int MAX_LOAD_ATTEMPTS = 5;

    /** Delay between load attempts, in milliseconds. */
    static final long RETRY_DELAY_MS = 500;

    private SqliteNativeSupport() {
        // utility class
    }

    /**
     * Startup hook: loads the SQLite JDBC native library now — before the
     * first sqlite-jdbc usage in this JVM — and logs the outcome.
     * <p>
     * Never throws: a failure is logged (with guidance) and surfaced later as
     * a clear {@code NodeStartupException} by the node database layer.
     */
    public static void prewarm() {
        if (ensureLoaded()) {
            logger.info("SQLite JDBC native library ready (driver v{}) — SQLite features available",
                    SQLiteJDBCLoader.getVersion());
        }
        // On failure ensureLoaded() already logged the ERROR with guidance.
    }

    /**
     * Ensures the SQLite JDBC native library is loaded, retrying the first
     * load up to {@value #MAX_LOAD_ATTEMPTS} times.
     * <p>
     * Idempotent and cheap once the library is loaded ({@code initialize()}
     * becomes a no-op).
     *
     * @return {@code true} if the native library is loaded (or was already
     *         loaded); {@code false} if every attempt failed — in that case
     *         <b>all</b> SQLite usage in this JVM is broken until the
     *         application is restarted
     */
    public static boolean ensureLoaded() {
        for (int attempt = 1; attempt <= MAX_LOAD_ATTEMPTS; attempt++) {
            try {
                SQLiteJDBCLoader.initialize();
                if (attempt > 1) {
                    logger.info("SQLite JDBC native library loaded on attempt {}/{}",
                            attempt, MAX_LOAD_ATTEMPTS);
                } else {
                    logger.debug("SQLite JDBC native library loaded (driver v{})",
                            SQLiteJDBCLoader.getVersion());
                }
                return true;
            } catch (Exception e) {
                logger.warn("SQLite JDBC native library load attempt {}/{} failed: {}",
                        attempt, MAX_LOAD_ATTEMPTS, e.getMessage());
                if (attempt < MAX_LOAD_ATTEMPTS && !sleep(RETRY_DELAY_MS)) {
                    return false;
                }
            }
        }
        logger.error(
                "SQLite JDBC native library could not be loaded after {} attempts. All "
                        + "SQLite-based features (node database, browser history) are unavailable "
                        + "for this session — restart the application to retry. "
                        + "sqlite-jdbc latches a failed first load for the JVM's lifetime, so without "
                        + "this pre-warm the failure would surface later as a raw "
                        + "java.lang.UnsatisfiedLinkError. Possible causes: antivirus (e.g. Windows "
                        + "Defender) temporarily locking the freshly extracted DLL in the temp "
                        + "directory, or a corrupted sqlite-jdbc jar.",
                MAX_LOAD_ATTEMPTS);
        return false;
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("SQLite JDBC native library pre-warm interrupted");
            return false;
        }
    }
}
