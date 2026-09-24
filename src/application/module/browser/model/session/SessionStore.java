package application.module.browser.model.session;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Persists the open-tab session (T8) as JSON under {@code conf/browser/}
 * (plan D8). Gson is already a project dependency; writes are atomic
 * (temp file + move) so a crash can never leave a truncated session file.
 * <p>
 * A missing or corrupt file is never fatal: {@link #load()} returns
 * {@link Optional#empty()} and the app starts with a New Tab.
 */
public final class SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionStore.class);

    private final Path file;
    private final Gson gson = new Gson();

    public SessionStore(Path sessionFile) {
        this.file = sessionFile;
    }

    /**
     * @return the saved session, or empty when the file is missing/corrupt/empty.
     *         A loaded snapshot always has a valid {@code activeIndex} (clamped).
     */
    public synchronized Optional<SessionSnapshot> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SessionSnapshot snapshot = gson.fromJson(reader, SessionSnapshot.class);
            if (snapshot == null || snapshot.getTabs().isEmpty()) {
                return Optional.empty();
            }
            snapshot.setActiveIndex(Math.max(0, Math.min(snapshot.getActiveIndex(), snapshot.getTabs().size() - 1)));
            return Optional.of(snapshot);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read the browser session ({}); starting fresh: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Saves the session atomically (never throws; failures are logged). */
    public synchronized void save(SessionSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, ".session-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(snapshot, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Could not save the browser session to {}", file, e);
        }
    }
}
