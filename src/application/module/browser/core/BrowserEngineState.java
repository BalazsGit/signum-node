package application.module.browser.core;

/**
 * State machine of the browser engine (plan D4).
 * <p>
 * Normal lifecycle: {@code IDLE → INITIALIZING → READY → SHUTTING_DOWN → SHUT_DOWN}.
 * {@code INITIALIZING} may end in {@link #FAILED} (e.g. the JCEF native
 * distribution is missing); a failed engine may be started again, which returns
 * it to {@code INITIALIZING}.
 */
public enum BrowserEngineState {

    /** Engine has never been started (or was reset). */
    IDLE,

    /** CEF initialization is running on the engine's init thread (blocking, 2–10 s). */
    INITIALIZING,

    /** CEF is up; browsers can be created. */
    READY,

    /** Initialization failed; {@link BrowserEngine#getFailureReason()} has the cause. */
    FAILED,

    /** Shutdown is in progress (CEF clients are disposed, the message loop terminates). */
    SHUTTING_DOWN,

    /** Engine is terminated; no CEF object is alive any more. */
    SHUT_DOWN
}
