package application.module.node.profile;

import application.module.node.props.Props;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProfileCli} command dispatch and CRUD behaviour.
 * <p>
 * Runs the CLI against a {@code confRoot} sandbox (JUnit {@link TempDir}) via the
 * package-private {@code tryHandleProfileCommand(String[], String)} overload so the real
 * {@code conf/} tree is never touched.
 */
@DisplayName("ProfileCli Tests")
class ProfileCliTest {

    @TempDir
    Path tempDir;

    private String confRoot() {
        return tempDir.toString();
    }

    private Path profileFile(String name) {
        return tempDir.resolve("node").resolve("profiles").resolve(name + ".properties");
    }

    @Nested
    @DisplayName("command detection")
    class DetectionTests {

        @Test
        @DisplayName("returns NOT_A_PROFILE_COMMAND when there is no 'profile' keyword")
        void detection_NoKeyword() {
            assertEquals(ProfileCli.NOT_A_PROFILE_COMMAND,
                    ProfileCli.tryHandleProfileCommand(new String[]{"--flag", "value"}, confRoot()));
        }

        @Test
        @DisplayName("returns NOT_A_PROFILE_COMMAND for a bare 'profile' keyword")
        void detection_BareKeyword() {
            assertEquals(ProfileCli.NOT_A_PROFILE_COMMAND,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile"}, confRoot()));
        }

        @Test
        @DisplayName("an unknown subcommand returns ERROR")
        void detection_UnknownCommand() {
            assertEquals(ProfileCli.ERROR,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "bogus"}, confRoot()));
        }
    }

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("maps CLI options to the correct Props keys")
        void create_MapsOptionsToProps() throws IOException {
            int code = ProfileCli.tryHandleProfileCommand(
                    new String[]{"profile", "create", "mynet",
                            "--network", "testnet", "--api-port", "9999"},
                    confRoot());

            assertEquals(ProfileCli.SUCCESS, code);
            assertTrue(Files.exists(profileFile("mynet")));
            NodeProfile p = NodeProfileRepository.loadProfile(confRoot(), "mynet");
            assertEquals("testnet", p.getProperties().getProperty("node.network"));
            assertEquals("9999", p.getProperties().getProperty("API.Port"));
        }

        @Test
        @DisplayName("create without explicit DB/ports assigns per-profile unique resources (A1)")
        void create_AutoAssignsUniqueDbAndPorts() throws IOException {
            assertEquals(ProfileCli.SUCCESS,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "create", "alpha"}, confRoot()));
            NodeProfile a = NodeProfileRepository.loadProfile(confRoot(), "alpha");
            // per-profile SQLite DB (not the shared default)
            assertTrue(a.getProperties().getProperty(Props.DB_URL.getName()).contains("alpha"));
            int apiA = Integer.parseInt(a.getProperties().getProperty(Props.API_PORT.getName()));
            assertTrue(apiA > 1024 && apiA < 65535, "API port must be a valid TCP port");

            assertEquals(ProfileCli.SUCCESS,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "create", "beta"}, confRoot()));
            NodeProfile b = NodeProfileRepository.loadProfile(confRoot(), "beta");
            int apiB = Integer.parseInt(b.getProperties().getProperty(Props.API_PORT.getName()));
            assertNotEquals(apiA, apiB, "two profiles must not share the API port");
        }

        @Test
        @DisplayName("create without a name returns ERROR")
        void create_MissingName() {
            assertEquals(ProfileCli.ERROR,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "create"}, confRoot()));
        }
    }

    @Nested
    @DisplayName("info / rename / delete")
    class CommandTests {

        @Test
        @DisplayName("info on a missing profile returns ERROR")
        void info_MissingProfile() {
            assertEquals(ProfileCli.ERROR,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "info", "ghost"}, confRoot()));
        }

        @Test
        @DisplayName("rename then info works end-to-end")
        void rename_ThenInfo() throws IOException {
            ProfileCli.tryHandleProfileCommand(new String[]{"profile", "create", "a"}, confRoot());

            int rc = ProfileCli.tryHandleProfileCommand(
                    new String[]{"profile", "rename", "a", "b"}, confRoot());

            assertEquals(ProfileCli.SUCCESS, rc);
            assertFalse(Files.exists(profileFile("a")));
            assertTrue(Files.exists(profileFile("b")));
            assertEquals(ProfileCli.SUCCESS,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "info", "b"}, confRoot()));
        }

        @Test
        @DisplayName("delete requires --force; with it, the profile is removed")
        void delete_ForceRequired() throws IOException {
            ProfileCli.tryHandleProfileCommand(new String[]{"profile", "create", "x"}, confRoot());

            assertEquals(ProfileCli.ERROR,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "delete", "x"}, confRoot()));
            assertTrue(Files.exists(profileFile("x")));

            assertEquals(ProfileCli.SUCCESS,
                    ProfileCli.tryHandleProfileCommand(
                            new String[]{"profile", "delete", "x", "--force"}, confRoot()));
            assertFalse(Files.exists(profileFile("x")));
        }
    }

    @Nested
    @DisplayName("run")
    class RunTests {

        @Test
        @DisplayName("run on an existing profile returns RUN_REQUESTED; on a missing one ERROR")
        void run_ExistingAndMissing() throws IOException {
            ProfileCli.tryHandleProfileCommand(new String[]{"profile", "create", "runner"}, confRoot());

            assertEquals(ProfileCli.RUN_REQUESTED,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "run", "runner"}, confRoot()));
            assertEquals(ProfileCli.ERROR,
                    ProfileCli.tryHandleProfileCommand(new String[]{"profile", "run", "ghost"}, confRoot()));
        }

        @Test
        @DisplayName("getRunTarget extracts the profile name for 'profile run <name>' only")
        void getRunTarget_ExtractsName() {
            assertEquals("runner", ProfileCli.getRunTarget(new String[]{"profile", "run", "runner"}));
            assertNull(ProfileCli.getRunTarget(new String[]{"profile", "create", "runner"}));
            assertNull(ProfileCli.getRunTarget(new String[]{"profile", "run"}));
        }
    }
}