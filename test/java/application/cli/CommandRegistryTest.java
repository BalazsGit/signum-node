package application.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the data-driven {@link CommandRegistry} and the value objects
 * ({@link CommandSpec}, {@link OwnerContext}, {@link CommandResult}).
 */
@DisplayName("CommandRegistry Tests")
class CommandRegistryTest {

    private final CommandRegistry registry = CommandRegistry.defaultRegistry();

    @Test
    @DisplayName("defaultRegistry contains every v1 command")
    void containsAllV1Commands() {
        List<String> expected = List.of(
                "node.pause", "node.resume", "node.shutdown", "node.restart",
                "node.autoresolve", "node.trim", "node.dbcheck", "node.popoff",
                "logging.link", "logging.unlink", "logging.show", "logging.list",
                "sys.profiles", "sys.help");
        for (String key : expected) {
            assertTrue(registry.lookup(key).isPresent(), "missing command: " + key);
        }
    }

    @Test
    @DisplayName("lookup of an unknown command is empty")
    void lookupUnknownIsEmpty() {
        assertFalse(registry.lookup("node.frobnicate").isPresent());
        assertFalse(registry.lookup(null).isPresent());
    }

    @Test
    @DisplayName("all() returns the full set in registration order")
    void allReturnsOrdered() {
        List<CommandSpec> all = registry.all();
        assertEquals(14, all.size());
        assertEquals("node.pause", all.get(0).key());
        assertEquals("sys.help", all.get(all.size() - 1).key());
    }

    @Test
    @DisplayName("CommandSpec exposes domain/action/key")
    void specParts() {
        CommandSpec pause = registry.lookup("node.pause").orElseThrow();
        assertEquals("node", pause.domain());
        assertEquals("pause", pause.action());
        assertEquals("node.pause", pause.key());
    }

    @Test
    @DisplayName("node.* commands require a node owner")
    void nodeCommandsConstrainedToNode() {
        CommandSpec pause = registry.lookup("node.pause").orElseThrow();
        assertTrue(pause.requiresOwner());
        assertEquals("node", pause.ownerModuleConstraint());
    }

    @Test
    @DisplayName("node.popoff takes exactly one argument")
    void popoffArgBounds() {
        CommandSpec popoff = registry.lookup("node.popoff").orElseThrow();
        assertEquals(1, popoff.minArgs());
        assertEquals(1, popoff.maxArgs());
    }

    @Test
    @DisplayName("sys.help does not require an owner")
    void sysHelpOwnerFree() {
        CommandSpec help = registry.lookup("sys.help").orElseThrow();
        assertFalse(help.requiresOwner());
    }

    @Test
    @DisplayName("reservedProfileNames contains the action names")
    void reservedNames() {
        var reserved = registry.reservedProfileNames();
        assertTrue(reserved.contains("pause"));
        assertTrue(reserved.contains("link"));
        assertTrue(reserved.contains("help"));
    }

    @Test
    @DisplayName("OwnerContext parses module.profile and exposes parts")
    void ownerContextParse() {
        OwnerContext owner = OwnerContext.parse("node.mainnet");
        assertEquals("node", owner.module());
        assertEquals("mainnet", owner.profile());
        assertEquals("node.mainnet", owner.toString());
        assertEquals(OwnerContext.parse("node.mainnet"), OwnerContext.of("node", "mainnet"));
    }

    @Test
    @DisplayName("OwnerContext rejects malformed tokens")
    void ownerContextMalformed() {
        assertThrows(IllegalArgumentException.class, () -> OwnerContext.parse("mainnet"));
        assertThrows(IllegalArgumentException.class, () -> OwnerContext.parse(".mainnet"));
        assertThrows(IllegalArgumentException.class, () -> OwnerContext.parse("node."));
    }

    @Test
    @DisplayName("CommandResult ok/fail carry out, ok-flag and exit code")
    void commandResultShapes() {
        CommandResult ok = CommandResult.ok(List.of("a", "b"));
        assertTrue(ok.ok());
        assertEquals(0, ok.exitCode());
        assertEquals(2, ok.out().size());

        CommandResult fail = CommandResult.fail("boom");
        assertFalse(fail.ok());
        assertEquals(1, fail.exitCode());
        assertEquals("boom", fail.out().get(0));
    }
}
