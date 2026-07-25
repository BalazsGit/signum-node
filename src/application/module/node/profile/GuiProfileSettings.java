package application.module.node.profile;

import java.util.Objects;

/**
 * Immutable value object that encapsulates all GUI-specific preferences for a
 * single node profile.
 * <p>
 * Instances are loaded lazily from {@code settings/gui-settings.json} under the
 * path {@code modules.node.profiles.{profileName}}. In headless mode this class
 * is never instantiated.
 * </p>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li><b>Immutable (Value Object):</b> All fields are {@code final} and set
 *       once through the constructor. No mutators exist, which guarantees
 *       thread-safety without any synchronization overhead.</li>
 *   <li><b>Single Responsibility:</b> This class ONLY carries GUI preference
 *       data. It does not perform I/O, parsing, or persistence — that logic
 *       lives in {@link application.module.node.profile.GuiSettingsLoader}.</li>
 *   <li><b>Null-safe convention:</b> A {@code null} child object means the
 *       particular sub-section was not configured and the GUI should fall back
 *       to its built-in defaults.</li>
 * </ul>
 *
 * @see application.module.node.profile.GuiSettingsLoader
 * @since 4.0
 */
public final class GuiProfileSettings {

    /** Console filter preferences for this profile. {@code null} means defaults. */
    private final ConsoleFilterConfig consoleFilters;

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Creates a new {@code GuiProfileSettings} instance.
     *
     * @param consoleFilters console filter configuration, or {@code null} to use defaults
     */
    public GuiProfileSettings(ConsoleFilterConfig consoleFilters) {
        this.consoleFilters = consoleFilters;
    }

    // ── Accessors ───────────────────────────────────────────────────────

    /**
     * Returns the console filter configuration for this profile.
     *
     * @return the {@link ConsoleFilterConfig}, or {@code null} if not configured
     */
    public ConsoleFilterConfig getConsoleFilters() {
        return consoleFilters;
    }

    /**
     * Returns {@code true} if console filter preferences are explicitly set.
     */
    public boolean hasConsoleFilters() {
        return consoleFilters != null;
    }

    // ── Object Contract ────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GuiProfileSettings that = (GuiProfileSettings) o;
        return Objects.equals(consoleFilters, that.consoleFilters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consoleFilters);
    }

    @Override
    public String toString() {
        return "GuiProfileSettings{" +
                "consoleFilters=" + consoleFilters +
                '}';
    }
}