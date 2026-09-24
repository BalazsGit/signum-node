package application.module.browser.model.tab;

/**
 * Where a tab-open request came from (diagnostics today; behavior hooks later).
 */
public enum TabSource {
    /** User action: Ctrl+T, the "+" button, middle-click. */
    USER,
    /** A page opened it: {@code window.open} / {@code target=_blank} / Ctrl+Click (plan 4.3/3, N7). */
    POPUP,
    /** Restored from the closed-tab LRU stack (T7) or from a saved session (T8). */
    RESTORE,
    /** Internal: e.g. the automatic New-Tab page after the last tab is closed (T2). */
    AUTO
}
