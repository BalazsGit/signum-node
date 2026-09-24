package application.module.browser.config;

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

/**
 * Loads and saves {@link BrowserSettings} as JSON (gson — already a project
 * dependency; atomic writes, same pattern as the session store).
 * <p>
 * D17: a missing, corrupt or partial file is never fatal — {@link #load()}
 * falls back to the safe defaults, and {@link #save(BrowserSettings)} never
 * throws.
 */
public final class BrowserSettingsRepository {

    private static final Logger logger = LoggerFactory.getLogger(BrowserSettingsRepository.class);

    private final Path file;
    private final Gson gson = new Gson();

    public BrowserSettingsRepository(Path settingsFile) {
        this.file = settingsFile;
    }

    /**
     * @return the saved settings, or the safe defaults when the file is
     *         missing, corrupt or only partially populated.
     */
    public synchronized BrowserSettings load() {
        if (!Files.isRegularFile(file)) {
            return new BrowserSettings();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            BrowserSettings settings = gson.fromJson(reader, BrowserSettings.class);
            if (settings == null) {
                return new BrowserSettings();
            }
            settings.sanitize();
            return settings;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read the browser settings ({}); using defaults", file, e.toString());
            return new BrowserSettings();
        }
    }

    /** Saves atomically (temp file + move); failures are logged, never thrown. */
    public synchronized void save(BrowserSettings settings) {
        if (settings == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, ".settings-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(settings, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Could not save the browser settings to {}", file, e);
        }
    }
}
