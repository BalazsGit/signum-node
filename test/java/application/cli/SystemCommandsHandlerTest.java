package application.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SystemCommandsHandler} ({@code sys.profiles} / {@code sys.help})
 * against a temporary {@code conf/} tree.
 */
@DisplayName("SystemCommandsHandler Tests")
class SystemCommandsHandlerTest {

    @TempDir
    Path tempDir;

    private final CommandRegistry registry = CommandRegistry.defaultRegistry();

    private CommandContext ctx(List<String> args) {
        return new CommandContext(null, args, CommandSource.CONSOLE_SYSTEM, tempDir.toString());
    }

    private void createNodeProfile(String name) throws Exception {
        Path f = tempDir.resolve("node").resolve("profiles").resolve(name + ".properties");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "some.prop=1\n");
    }

    @Test
    @DisplayName("sys.profiles lists the node module's profiles (default module = node)")
    void profilesListsNode() throws Exception {
        createNodeProfile("mainnet");
        createNodeProfile("testnet");

        CommandResult r = new SystemCommandsHandler("profiles", registry).handle(ctx(List.of()));

        assertTrue(r.ok());
        String joined = String.join("\n", r.out());
        assertTrue(joined.contains("mainnet"));
        assertTrue(joined.contains("testnet"));
    }

    @Test
    @DisplayName("sys.profiles excludes reserved names")
    void profilesExcludesReserved() throws Exception {
        createNodeProfile("node-default");
        createNodeProfile("custom");

        String joined = String.join("\n",
                new SystemCommandsHandler("profiles", registry).handle(ctx(List.of())).out());

        assertFalse(joined.contains("node-default"));
        assertTrue(joined.contains("custom"));
    }

    @Test
    @DisplayName("sys.help lists all registered commands (data-driven)")
    void helpListsAll() {
        String joined = String.join("\n",
                new SystemCommandsHandler("help", registry).handle(ctx(List.of())).out());

        assertTrue(joined.contains("node.pause"));
        assertTrue(joined.contains("logging.link"));
        assertTrue(joined.contains("sys.profiles"));
        assertTrue(joined.contains("sys.help"));
    }
}
