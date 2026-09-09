package application.cli;

import java.util.List;

/**
 * The outcome of executing a command.
 * <p>
 * {@code out} holds the user-facing lines (printed to the console or emitted to the
 * CLI); {@code ok} indicates success; {@code exitCode} is meaningful for the headless
 * CLI ({@code 0} on success, {@code 1} on failure) and ignored by the console
 * front-ends (which keep the session alive regardless).
 * </p>
 */
public final class CommandResult {

    private final List<String> out;
    private final boolean ok;
    private final int exitCode;

    private CommandResult(List<String> out, boolean ok, int exitCode) {
        this.out = out == null ? List.of() : List.copyOf(out);
        this.ok = ok;
        this.exitCode = exitCode;
    }

    public static CommandResult ok(List<String> out) {
        return new CommandResult(out, true, 0);
    }

    public static CommandResult fail(String message) {
        return new CommandResult(List.of(message), false, 1);
    }

    public List<String> out() {
        return out;
    }

    public boolean ok() {
        return ok;
    }

    public int exitCode() {
        return exitCode;
    }
}
