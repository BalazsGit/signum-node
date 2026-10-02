package application.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LaunchCommand} — the launch-command memory that makes
 * the Restart button able to relaunch the application (instead of merely
 * shutting it down) from a fat jar <em>and</em> from a class directory
 * (IDE/Gradle).
 */
@DisplayName("LaunchCommand Tests")
class LaunchCommandTest {

    @BeforeEach
    void setUp() {
        LaunchCommand.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        LaunchCommand.resetForTesting();
    }

    @Test
    @DisplayName("startNewInstance before capture throws (a restart must never be a silent no-op)")
    void start_beforeCapture_throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, LaunchCommand::startNewInstance);
        assertTrue(ex.getMessage().contains("not captured"),
                "the error must say the launch information is missing");
    }

    @Test
    @DisplayName("capture is idempotent — the first capture wins")
    void capture_isIdempotent() {
        LaunchCommand.capture(new String[]{"-c", "one"});
        LaunchCommand.capture(new String[]{"-c", "two"});

        List<String> fallback = LaunchCommand.fallbackCommandLine();
        assertEquals("one", fallback.get(fallback.size() - 1),
                "the original (first-captured) arguments must be the ones relaunched");
    }

    @Test
    @DisplayName("the resolved command line is never empty and its executable exists")
    void resolve_returnsUsableCommandLine() {
        LaunchCommand.capture(new String[]{"-c", "conf"});

        List<String> commandLine = LaunchCommand.resolveCommandLine();
        assertFalse(commandLine.isEmpty(), "the command line must not be empty");
        assertTrue(commandLine.size() >= 2,
                "a bare JVM executable (no -jar/-cp/main class) can never start the application — "
                        + "an argument-less 'exact' line must fall back to the reconstructed one: "
                        + commandLine);
        File executable = new File(commandLine.get(0));
        if (!executable.isAbsolute()) {
            executable = new File(executable.getPath());
        }
        assertTrue(executable.isFile(),
                "the command's executable must exist: " + commandLine.get(0));
    }

    @Test
    @DisplayName("the fallback command line names the JVM, the entry point and the original arguments")
    void fallback_reconstructsEntryPointAndOriginalArgs() {
        String[] appArgs = {"-c", "conf"};
        LaunchCommand.capture(appArgs);

        List<String> fallback = LaunchCommand.fallbackCommandLine();
        assertTrue(new File(fallback.get(0)).isFile(),
                "the JVM executable must exist: " + fallback.get(0));

        String joined = String.join(" ", fallback);
        assertTrue(joined.contains(Launcher.class.getName()) || joined.contains("-jar"),
                "the fallback must name the main class or an executable jar: " + joined);

        for (int i = 0; i < appArgs.length; i++) {
            assertEquals(appArgs[i], fallback.get(fallback.size() - appArgs.length + i),
                    "the original application arguments must be appended verbatim");
        }
    }

    @Test
    @DisplayName("the fallback survives null arguments")
    void fallback_nullArgs_areTreatedAsEmpty() {
        LaunchCommand.capture(null);

        List<String> fallback = LaunchCommand.fallbackCommandLine();
        assertFalse(fallback.isEmpty());
        assertTrue(new File(fallback.get(0)).isFile());
    }
}