package application.module.node.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link NodeProfileRepository} CRUD operations
 * (create / create-default / rename / delete / discover).
 * <p>
 * Uses an explicit {@code confRoot} sandbox (a JUnit {@link TempDir}) so the tests never
 * touch the real {@code conf/} tree. Follows AAA + JUnit 5 conventions.
 */
@DisplayName("NodeProfileRepository CRUD Tests")
class NodeProfileRepositoryCrudTest {

    @TempDir
    Path tempDir;

    private String confRoot() {
        return tempDir.toString();
    }

    private Path profilesDir() {
        return tempDir.resolve("node").resolve("profiles");
    }

    private Path profileFile(String name) {
        return profilesDir().resolve(name + ".properties");
    }

    @Nested
    @DisplayName("createProfile")
    class CreateTests {

        @Test
        @DisplayName("creates the .properties file and returns the profile")
        void create_WritesFileAndReturnsProfile() throws IOException {
            Properties props = new Properties();
            props.setProperty("API.Port", "9999");

            NodeProfile result = NodeProfileRepository.createProfile(confRoot(), "mainnet", props);

            assertNotNull(result);
            assertEquals("mainnet", result.getName());
            assertTrue(Files.exists(profileFile("mainnet")));
            assertEquals("9999", result.getProperties().getProperty("API.Port"));
        }

        @Test
        @DisplayName("rejects a duplicate name")
        void create_DuplicateName_Throws() throws IOException {
            NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties());
            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.createProfile(confRoot(), "mainnet", new Properties()));
        }

        @Test
        @DisplayName("rejects a reserved name")
        void create_ReservedName_Throws() throws IOException {
            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.createProfile(confRoot(), "node-default", new Properties()));
        }

        @Test
        @DisplayName("rejects a blank name")
        void create_BlankName_Throws() throws IOException {
            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.createProfile(confRoot(), "   ", new Properties()));
        }

        @Test
        @DisplayName("rejects invalid characters")
        void create_InvalidCharacters_Throws() throws IOException {
            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.createProfile(confRoot(), "bad name!", new Properties()));
        }
    }

    @Nested
    @DisplayName("createDefaultProfile")
    class CreateDefaultTests {

        @Test
        @DisplayName("merges the node-default template with overrides")
        void createDefault_MergesTemplateAndOverrides() throws IOException {
            Files.createDirectories(profilesDir());
            Files.writeString(profileFile("node-default"), "node.network=mainnet\nAPI.Port=8125\n");
            Properties overrides = new Properties();
            overrides.setProperty("API.Port", "7777");

            NodeProfile result = NodeProfileRepository.createDefaultProfile(confRoot(), "mynet", overrides);

            assertNotNull(result);
            assertEquals("7777", result.getProperties().getProperty("API.Port"));
            assertEquals("mainnet", result.getProperties().getProperty("node.network"));
        }

        @Test
        @DisplayName("creates with only overrides when template is missing")
        void createDefault_NoTemplate_UsesOnlyOverrides() throws IOException {
            Properties overrides = new Properties();
            overrides.setProperty("P2P.Port", "18123");

            NodeProfile result = NodeProfileRepository.createDefaultProfile(confRoot(), "solo", overrides);

            assertNotNull(result);
            assertEquals("18123", result.getProperties().getProperty("P2P.Port"));
            assertTrue(Files.exists(profileFile("solo")));
        }
    }

    @Nested
    @DisplayName("renameProfile")
    class RenameTests {

        @Test
        @DisplayName("moves the profile file to the new name")
        void rename_MovesFile() throws IOException {
            NodeProfileRepository.createProfile(confRoot(), "old", new Properties());

            NodeProfileRepository.renameProfile(confRoot(), "old", "new");

            assertFalse(Files.exists(profileFile("old")));
            assertTrue(Files.exists(profileFile("new")));
        }

        @Test
        @DisplayName("rejects an unknown source name")
        void rename_UnknownSource_Throws() throws IOException {
            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.renameProfile(confRoot(), "ghost", "new"));
        }

        @Test
        @DisplayName("rejects an already-existing target name")
        void rename_ExistingTarget_Throws() throws IOException {
            NodeProfileRepository.createProfile(confRoot(), "a", new Properties());
            NodeProfileRepository.createProfile(confRoot(), "b", new Properties());

            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.renameProfile(confRoot(), "a", "b"));
        }
    }

    @Nested
    @DisplayName("deleteProfile")
    class DeleteTests {

        @Test
        @DisplayName("removes the profile file")
        void delete_RemovesFile() throws IOException {
            NodeProfileRepository.createProfile(confRoot(), "x", new Properties());

            NodeProfileRepository.deleteProfile(confRoot(), "x");

            assertFalse(Files.exists(profileFile("x")));
        }

        @Test
        @DisplayName("rejects a reserved name")
        void delete_ReservedName_Throws() throws IOException {
            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.deleteProfile(confRoot(), "node-default"));
        }

        @Test
        @DisplayName("rejects an unknown name")
        void delete_UnknownName_Throws() throws IOException {
            assertThrows(IllegalArgumentException.class,
                    () -> NodeProfileRepository.deleteProfile(confRoot(), "ghost"));
        }
    }

    @Nested
    @DisplayName("discoverProfileNames")
    class DiscoverTests {

        @Test
        @DisplayName("lists created profiles and excludes reserved names")
        void discover_ListsCreated_ExcludesReserved() throws IOException {
            NodeProfileRepository.createProfile(confRoot(), "aaa", new Properties());
            NodeProfileRepository.createProfile(confRoot(), "bbb", new Properties());
            Files.createDirectories(profilesDir());
            Files.writeString(profileFile("node-default"), "x=y\n");

            List<String> names = NodeProfileRepository.discoverProfileNames(confRoot());

            assertTrue(names.contains("aaa"));
            assertTrue(names.contains("bbb"));
            assertFalse(names.contains("node-default"));
        }
    }

    @Nested
    @DisplayName("checkProfileName")
    class CheckProfileNameTests {

        @Test
        @DisplayName("returns null for a valid, available name")
        void check_ValidName_ReturnsNull() {
            assertNull(NodeProfileRepository.checkProfileName("mainnet", List.of("other")));
            assertNull(NodeProfileRepository.checkProfileName("mainnet", null));
        }

        @Test
        @DisplayName("returns an error for a taken name")
        void check_TakenName_ReturnsError() {
            String error = NodeProfileRepository.checkProfileName("mainnet", List.of("mainnet"));
            assertNotNull(error);
            assertTrue(error.contains("already exists"));
        }

        @Test
        @DisplayName("returns an error for a reserved name regardless of the taken set")
        void check_ReservedName_ReturnsError() {
            assertNotNull(NodeProfileRepository.checkProfileName("node-default", null));
            assertNotNull(NodeProfileRepository.checkProfileName("logging-default", List.of()));
        }

        @Test
        @DisplayName("returns an error for a blank, invalid, or null name")
        void check_BlankOrInvalid_ReturnsError() {
            assertNotNull(NodeProfileRepository.checkProfileName("  ", null));
            assertNotNull(NodeProfileRepository.checkProfileName("bad name!", null));
            assertNotNull(NodeProfileRepository.checkProfileName(null, null));
        }
    }
}