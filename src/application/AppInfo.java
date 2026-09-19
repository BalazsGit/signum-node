package application;

/**
 * Single source of truth (SSOT) for the application's display name.
 * <p>
 * Every user-visible label (window titles, tray tooltips, notifications)
 * must be derived from {@link #NAME} / {@link #PLATFORM_NAME}, so renaming
 * the product means editing exactly one place.
 * </p>
 * <p>
 * Scope: <em>display</em> names only. Functional identifiers (database names
 * and users, the {@code signum://} deep-link protocol, network names, thread
 * names, logger prefixes) are intentionally NOT derived from here — changing
 * them would alter behaviour, not just wording.
 * </p>
 */
public final class AppInfo {

    /** The product brand name (SSOT for every user-visible label). */
    public static final String NAME = "Signum";

    /** The full platform name used in window titles and the tray tooltip. */
    public static final String PLATFORM_NAME = NAME + " Platform";

    private AppInfo() {
        // no instances
    }
}
