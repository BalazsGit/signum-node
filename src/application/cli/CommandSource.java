package application.cli;

/**
 * The surface a command was entered from.
 * <p>
 * Informational for v1: it documents the execution context (and will distinguish
 * the headless CLI from the console front-ends). Handlers do not branch on it, so
 * the exact same command behaves identically everywhere — only the outer shell
 * (exit-code vs. log line) differs.
 * </p>
 */
public enum CommandSource {
    /** The System Console tab (no implicit owner; commands must address an owner explicitly). */
    CONSOLE_SYSTEM,
    /** A per-profile console tab (the profile is the implicit default owner). */
    CONSOLE_PROFILE,
    /** The headless command-line front-end ({@code java -jar signum.jar <cmd...>}). */
    CLI
}
