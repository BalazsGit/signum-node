package application.cli;

import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LoggingCommandsHandler} (link / unlink / show / list) against a
 * temporary {@code conf/} tree — no running node required (the applier is a no-op then).
 */
@DisplayName("LoggingCommandsHandler Tests")
class LoggingCommandsHandlerTest {

    @TempDir
    Path tempDir;

    private static final OwnerContext OWNER = OwnerContext.of("node", "mainnet");

    private CommandContext ctx(OwnerContext owner, List<String> args) {
        return new CommandContext(owner, args, CommandSource.CONSOLE_SYSTEM, tempDir.toString());
    }

    private void createProfile(String moduleId, String name) throws Exception {
        new LoggingProfileRepository(tempDir.toString()).saveProps(moduleId, name, new Properties());
    }

    private LoggingAssignmentStore store() {
        return new LoggingAssignmentStore(tempDir.toString());
    }

    @Test
    @DisplayName("link sets the assignment for the owner's module")
    void linkSetsAssignment() throws Exception {
        createProfile("node", "standard");

        CommandResult r = new LoggingCommandsHandler("link").handle(ctx(OWNER, List.of("standard")));

        assertTrue(r.ok());
        assertEquals("standard", store().getAssignment("mainnet").get("node"));
    }

    @Test
    @DisplayName("link with an unknown profile fails")
    void linkUnknownProfileFails() {
        CommandResult r = new LoggingCommandsHandler("link").handle(ctx(OWNER, List.of("ghost")));
        assertFalse(r.ok());
        assertTrue(r.out().get(0).contains("Unknown logging profile"));
    }

    @Test
    @DisplayName("link without an arg fails")
    void linkMissingArgFails() {
        assertFalse(new LoggingCommandsHandler("link").handle(ctx(OWNER, List.of())).ok());
    }

    @Test
    @DisplayName("link without an owner fails")
    void linkNoOwnerFails() {
        assertFalse(new LoggingCommandsHandler("link").handle(ctx(null, List.of("standard"))).ok());
    }

    @Test
    @DisplayName("unlink clears the owner's module assignment")
    void unlinkClears() throws Exception {
        createProfile("node", "standard");
        store().setAssignmentForModule("mainnet", "node", "standard");

        CommandResult r = new LoggingCommandsHandler("unlink").handle(ctx(OWNER, List.of()));

        assertTrue(r.ok());
        assertFalse(store().getAssignment("mainnet").containsKey("node"));
    }

    @Test
    @DisplayName("show reports the assigned profile")
    void showReports() throws Exception {
        createProfile("node", "standard");
        store().setAssignmentForModule("mainnet", "node", "standard");

        CommandResult r = new LoggingCommandsHandler("show").handle(ctx(OWNER, List.of()));

        assertTrue(r.ok());
        String joined = String.join("\n", r.out());
        assertTrue(joined.contains("standard"));
        assertTrue(joined.contains("mainnet"));
    }

    @Test
    @DisplayName("show without an owner fails")
    void showNoOwnerFails() {
        assertFalse(new LoggingCommandsHandler("show").handle(ctx(null, List.of())).ok());
    }

    @Test
    @DisplayName("list lists the module's profiles and marks the assigned one")
    void listMarksAssigned() throws Exception {
        createProfile("node", "standard");
        createProfile("node", "verbose");
        store().setAssignmentForModule("mainnet", "node", "verbose");

        CommandResult r = new LoggingCommandsHandler("list").handle(ctx(OWNER, List.of()));

        assertTrue(r.ok());
        String joined = String.join("\n", r.out());
        assertTrue(joined.contains("standard"));
        assertTrue(joined.contains("* verbose"));
    }

    @Test
    @DisplayName("list with an explicit module arg works without an owner")
    void listModuleArg() throws Exception {
        createProfile("database", "quiet");

        CommandResult r = new LoggingCommandsHandler("list").handle(ctx(null, List.of("database")));

        assertTrue(r.ok());
        assertTrue(String.join("\n", r.out()).contains("quiet"));
    }

    @Test
    @DisplayName("list without a module and without an owner fails")
    void listNoModuleNoOwnerFails() {
        assertFalse(new LoggingCommandsHandler("list").handle(ctx(null, List.of())).ok());
    }
}
