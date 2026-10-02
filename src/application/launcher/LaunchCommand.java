package application.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Remembers how the current JVM was launched so the application can relaunch
 * itself (Restart button, {@code .restart} console command,
 * {@code ModuleContext.requestRestart()}) with a <em>verified</em> command line.
 * <p>
 * Why this exists: reconstructing the launch command line at restart time from
 * {@link ProcessHandle#info()} alone is fragile — on several Windows launch
 * paths (IDE agents, wrapper scripts, elevated launches) the OS reports an
 * empty or partial command line, and the old reconstruction refused to work at
 * all when the app ran from a class directory (IDE/Gradle). A failed relaunch
 * was then silently swallowed and the "Restart" button merely shut the
 * application down.
 * <p>
 * Two strategies, tried in order:
 * <ol>
 *   <li><b>Exact</b> — re-run the process's own command line as reported by
 *       {@link ProcessHandle#info()} (keeps JVM options, IDE agents,
 *       {@code -jar}, ...), when its executable still exists.</li>
 *   <li><b>Reconstructed</b> —
 *       {@code <java.home>/bin/java (-jar <app jar> | -cp <classpath>)
 *       application.launcher.Launcher <original args>} — always works when the
 *       main class is known, independent of what the OS reports.</li>
 * </ol>
 * Both strategies are resolved from information captured once at startup (main
 * process only — a JCEF subprocess re-runs {@code main()} but never passes the
 * JCEF bootstrap, which terminates the process), so a restart never depends on
 * the OS cooperating at that late moment.
 */
public final class LaunchCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(LaunchCommand.class);

    private static volatile boolean captured;
    private static volatile List<String> exactCommandLine = Collections.emptyList();
    private static volatile String[] appArgs = new String[0];
    private static volatile File workingDirectory = new File("").getAbsoluteFile();

    private LaunchCommand() {
    }

    /**
     * Captures the launch information. Must be called once, early, from the
     * main process's {@code main()}; idempotent (the first capture wins).
     *
     * @param mainArgs the original main() arguments of this process
     */
    public static synchronized void capture(String[] mainArgs) {
        if (captured) {
            return;
        }
        appArgs = mainArgs != null ? mainArgs.clone() : new String[0];
        workingDirectory = new File("").getAbsoluteFile();

        List<String> exact = new ArrayList<>();
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            String command = info.command().orElse(null);
            if (command != null && !command.isBlank()) {
                exact.add(command);
                for (String arg : info.arguments().orElse(new String[0])) {
                    exact.add(arg);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read the launch command line from ProcessHandle: {}", t.toString());
        }
        exactCommandLine = Collections.unmodifiableList(exact);
        captured = true;
        if (exactCommandLine.size() > 1) {
            LOGGER.info("Launch command captured (strategy: exact): {}", String.join(" ", exact));
        } else {
            LOGGER.warn("Launch command captured WITHOUT arguments (the OS reported only the executable: {}) — "
                            + "restart will use the reconstructed command (java -jar/-cp + main class + original args)",
                    exact.isEmpty() ? "<none>" : exact.get(0));
        }
    }

    /**
     * @return whether the launch information was captured
     */
    public static synchronized boolean isCaptured() {
        return captured;
    }

    /**
     * Resolves the command line to launch the application again: the exact
     * original one when its executable exists <em>and it carries arguments</em>,
     * otherwise the reconstructed {@code java + (-jar | -cp) + main class + args}
     * one.
     * <p>
     * The "must carry arguments" rule matters: on some Windows launch paths the
     * OS reports the process's command but an <em>empty</em> argument list — a
     * bare {@code java.exe} line would then "succeed" at starting a child that
     * immediately prints its usage and exits, making the Restart button look
     * like a plain shutdown.
     *
     * @return the full command line (executable first)
     * @throws IllegalStateException if no usable command line can be built
     */
    public static synchronized List<String> resolveCommandLine() {
        if (exactCommandLine.size() > 1 && executableExists(exactCommandLine.get(0))) {
            return exactCommandLine;
        }
        return fallbackCommandLine();
    }

    /**
     * Starts a new, independent instance of the application (detached from the
     * current process: the child keeps running after the parent exits).
     *
     * @return the started child process (already running)
     * @throws IllegalStateException if no launch information was captured or
     *                               no usable command line can be built
     * @throws IOException           if the new process cannot be started
     */
    public static synchronized Process startNewInstance() throws IOException {
        if (!captured) {
            throw new IllegalStateException(
                    "launch information was not captured (LaunchCommand.capture() must run in main)");
        }
        List<String> commandLine = resolveCommandLine();
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        Process child = builder.start();
        LOGGER.info("Launched a new application instance (pid {}): {}",
                child.pid(), String.join(" ", commandLine));
        return child;
    }

    /**
     * Builds the {@code <java> (-jar <app jar> | -cp <classpath>)
     * application.launcher.Launcher <original args>} command line.
     * Package-private so unit tests can verify it independently of the
     * ProcessHandle-reported exact command line.
     *
     * @return the reconstructed command line (executable first)
     * @throws IllegalStateException if the JVM executable or classpath is missing
     */
    static List<String> fallbackCommandLine() {
        String osName = System.getProperty("os.name", "");
        File javaExe = new File(new File(System.getProperty("java.home", ""), "bin"),
                osName.toLowerCase().contains("win") ? "java.exe" : "java");
        if (!javaExe.isFile()) {
            throw new IllegalStateException("JVM executable not found at " + javaExe);
        }
        String classPath = System.getProperty("java.class.path", "");
        String[] entries = classPath.split(File.pathSeparator);
        if (entries.length == 0 || entries[0].isBlank()) {
            throw new IllegalStateException("java.class.path is empty — cannot reconstruct the launch command");
        }

        List<String> command = new ArrayList<>();
        command.add(javaExe.getPath());
        if (entries.length == 1 && entries[0].toLowerCase().endsWith(".jar")) {
            // Launched with "java -jar <app>.jar" (java.class.path == the jar).
            command.add("-jar");
            command.add(resolveAgainstWorkingDirectory(entries[0]).getPath());
        } else {
            // Launched with a classpath (IDE / Gradle run).
            command.add("-cp");
            command.add(classPath);
            command.add(Launcher.class.getName());
        }
        command.addAll(Arrays.asList(appArgs));
        return command;
    }

    private static boolean executableExists(String command) {
        return new File(command).isFile()
                || new File(workingDirectory, command).isFile();
    }

    private static File resolveAgainstWorkingDirectory(String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(workingDirectory, path);
    }

    /** Test hook: forgets the captured launch information. */
    static synchronized void resetForTesting() {
        captured = false;
        exactCommandLine = Collections.emptyList();
        appArgs = new String[0];
        workingDirectory = new File("").getAbsoluteFile();
    }
}