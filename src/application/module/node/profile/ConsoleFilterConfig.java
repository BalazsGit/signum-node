package application.module.node.profile;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value object that captures console filter preferences for a single
 * node profile.
 * <p>
 * This DTO is part of the {@link GuiProfileSettings} hierarchy and represents
 * the user-defined filtering rules applied to the profile-specific console
 * output (log level, module whitelist, free-text search).
 * </p>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li><b>Immutable:</b> All fields are {@code final} and populated through
 *       the constructor. This guarantees thread-safety without synchronization.</li>
 *   <li><b>Defensive copying:</b> The {@code modules} list is defensively copied
 *       both on construction and on access to prevent external mutation.</li>
 *   <li><b>Null-defaults:</b> A {@code null} value for any field means "use the
 *       system default" rather than an explicit empty setting.</li>
 * </ul>
 *
 * @see GuiProfileSettings
 * @since 4.0
 */
public final class ConsoleFilterConfig {

    /**
     * Log level filter (e.g., "info", "debug", "warn").
     * {@code null} means no explicit level override — use system default.
     */
    private final String logLevel;

    /**
     * Whitelist of module identifiers to display (e.g., ["node", "database"]).
     * An empty list means all modules are shown.
     * {@code null} means no explicit module filter — use system default.
     * Never {@code null} when returned from {@link #getModules()}.
     */
    private final List<String> modules;

    /**
     * Free-text search pattern applied to log messages.
     * An empty string means no text filter active.
     * {@code null} means no explicit search — use system default.
     */
    private final String textSearch;

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Creates a new {@code ConsoleFilterConfig} with the specified values.
     *
     * @param logLevel   the log level filter, or {@code null} for default
     * @param modules    the module whitelist, or {@code null} for default
     * @param textSearch the free-text search pattern, or {@code null} for default
     */
    public ConsoleFilterConfig(String logLevel, List<String> modules, String textSearch) {
        this.logLevel = logLevel;
        this.modules = (modules != null)
                ? Collections.unmodifiableList(List.copyOf(modules))
                : Collections.emptyList();
        this.textSearch = textSearch;
    }

    // ── Accessors ───────────────────────────────────────────────────────

    /**
     * Returns the log level filter.
     *
     * @return the configured log level, or {@code null} if not explicitly set
     */
    public String getLogLevel() {
        return logLevel;
    }

    /**
     * Returns the module whitelist.
     * <p>
     * The returned list is unmodifiable. An empty list indicates no explicit
     * filter (all modules shown).
     * </p>
     *
     * @return an unmodifiable list of module identifiers (never {@code null})
     */
    public List<String> getModules() {
        return modules;
    }

    /**
     * Returns the free-text search pattern.
     *
     * @return the configured text search, or {@code null} if not explicitly set
     */
    public String getTextSearch() {
        return textSearch;
    }

    /**
     * Returns {@code true} if a log level override is configured.
     */
    public boolean hasLogLevel() {
        return logLevel != null && !logLevel.isBlank();
    }

    /**
     * Returns {@code true} if an explicit module whitelist is configured.
     */
    public boolean hasModulesFilter() {
        return !modules.isEmpty();
    }

    /**
     * Returns {@code true} if a free-text search pattern is configured.
     */
    public boolean hasTextSearch() {
        return textSearch != null && !textSearch.isBlank();
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConsoleFilterConfig that = (ConsoleFilterConfig) o;
        return Objects.equals(logLevel, that.logLevel)
                && Objects.equals(modules, that.modules)
                && Objects.equals(textSearch, that.textSearch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logLevel, modules, textSearch);
    }

    @Override
    public String toString() {
        return "ConsoleFilterConfig{" +
                "logLevel='" + logLevel + '\'' +
                ", modules=" + modules +
                ", textSearch='" + textSearch + '\'' +
                '}';
    }
}