package application.module.logging;

import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LoggingProfileRepository}.
 * <p>
 * Exercises CRUD against a temporary conf root, reserved-name protection, and the
 * applied-profile metadata round-trip. No node is started.
 */
@DisplayName("LoggingProfileRepository Tests")
class LoggingProfileRepositoryTest {

    private static final String MODULE = "node";

    @TempDir
    Path tempDir;

    private LoggingProfileRepository repo;

    @BeforeEach
    void setUp() {
        repo = new LoggingProfileRepository(tempDir.toString());
        LoggingModuleRegistry.getInstance().clear();
    }

    @AfterEach
    void tearDown() {
        LoggingModuleRegistry.getInstance().clear();
    }

    @Test
    @DisplayName("create writes a new profile file and it becomes discoverable")
    void create_writesDiscoverableProfile() throws IOException {
        repo.create(MODULE, "myprofile", (java.util.Properties) null);

        assertTrue(repo.getProfileFile(MODULE, "myprofile").endsWith("node/logging/myprofile.properties"));
        assertTrue(Files.exists(repo.getProfileFile(MODULE, "myprofile")));
        assertEquals(List.of("myprofile"), repo.listProfiles(MODULE));
    }

    @Test
    @DisplayName("create seeds content from explicit properties (the editor-state path)")
    void create_withPropertiesSeed_writesSeedContent() throws IOException {
        Properties seed = new Properties();
        seed.setProperty("test.level", "FINE");
        seed.setProperty("test.handler", "custom.handler");

        repo.create(MODULE, "from-seed", seed);
        Properties loaded = repo.loadProps(MODULE, "from-seed");

        assertEquals("FINE", loaded.getProperty("test.level"));
        assertEquals("custom.handler", loaded.getProperty("test.handler"));
        assertEquals(2, loaded.size(), "exactly the seeded entries are written");
    }

    @DisplayName("create refuses the reserved name")
    void create_reservedName_rejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.create(MODULE, "logging-default", (java.util.Properties) null));
    }

    @Test
    @DisplayName("create refuses a duplicate name")
    void create_duplicateName_rejected() throws IOException {
        repo.create(MODULE, "dup", (java.util.Properties) null);
        assertThrows(IllegalArgumentException.class, () -> repo.create(MODULE, "dup", (java.util.Properties) null));
    }

    @Test
    @DisplayName("listProfiles excludes the reserved default even if present on disk")
    void listProfiles_excludesReserved() throws IOException {
        Path dir = repo.getLoggingDir(MODULE);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("logging-default.properties"), "node.level=INFO\n");
        Files.writeString(dir.resolve("alpha.properties"), "node.level=INFO\n");

        List<String> names = repo.listProfiles(MODULE);
        assertTrue(names.contains("alpha"));
        assertFalse(names.contains("logging-default"));
    }

    @Test
    @DisplayName("rename moves the file and updates the applied metadata")
    void rename_movesFileAndUpdatesApplied() throws IOException {
        repo.create(MODULE, "old", (java.util.Properties) null);
        repo.setApplied(MODULE, "old");

        repo.rename(MODULE, "old", "new");

        assertFalse(Files.exists(repo.getProfileFile(MODULE, "old")));
        assertTrue(Files.exists(repo.getProfileFile(MODULE, "new")));
        assertEquals("new", repo.getApplied(MODULE));
    }

    @Test
    @DisplayName("rename refuses to move the reserved profile")
    void rename_reservedSource_rejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.rename(MODULE, "logging-default", "other"));
    }

    @Test
    @DisplayName("delete removes the file and clears applied metadata when it was applied")
    void delete_removesAndClearsApplied() throws IOException {
        repo.create(MODULE, "doomed", (java.util.Properties) null);
        repo.setApplied(MODULE, "doomed");

        repo.delete(MODULE, "doomed");

        assertFalse(Files.exists(repo.getProfileFile(MODULE, "doomed")));
        assertNull(repo.getApplied(MODULE));
    }

    @Test
    @DisplayName("delete refuses the reserved profile")
    void delete_reserved_rejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.delete(MODULE, "logging-default"));
    }

    @Test
    @DisplayName("saveProps round-trips properties; reserved is protected")
    void saveProps_roundTrips_andReservedProtected() throws IOException {
        Properties props = new Properties();
        props.setProperty("node.level", "FINE");
        props.setProperty("node.peer", "WARNING");

        repo.saveProps(MODULE, "saved", props);

        Properties loaded = repo.loadProps(MODULE, "saved");
        assertEquals("FINE", loaded.getProperty("node.level"));
        assertEquals("WARNING", loaded.getProperty("node.peer"));

        assertThrows(IllegalArgumentException.class,
                () -> repo.saveProps(MODULE, "logging-default", props));
    }

    @Test
    @DisplayName("applied metadata round-trips and can be cleared")
    void appliedMetadata_roundTrips() throws IOException {
        assertNull(repo.getApplied(MODULE));

        repo.setApplied(MODULE, "standard");
        assertEquals("standard", repo.getApplied(MODULE));

        repo.setApplied(MODULE, null);
        assertNull(repo.getApplied(MODULE));
    }

    @Test
    @DisplayName("hasProfile is true for an on-disk profile file")
    void hasProfile_onDisk() throws IOException {
        repo.create(MODULE, "exists", (java.util.Properties) null);
        assertTrue(repo.hasProfile(MODULE, "exists"));
        assertFalse(repo.hasProfile(MODULE, "missing"));
        assertFalse(repo.hasProfile(MODULE, null));
    }

    @Test
    @DisplayName("resolveEffective returns the requested name when it is resolvable")
    void resolveEffective_valid() throws IOException {
        repo.create(MODULE, "myprofile", (java.util.Properties) null);
        assertEquals("myprofile", repo.resolveEffective(MODULE, "myprofile"));
    }

    @Test
    @DisplayName("resolveEffective returns the reserved default when the profile is missing/invalid")
    void resolveEffective_invalidFallsBackToDefault() {
        assertEquals(LoggingProfileRepository.RESERVED_PROFILE_NAME,
                repo.resolveEffective(MODULE, "deleted-profile"));
        assertEquals(LoggingProfileRepository.RESERVED_PROFILE_NAME,
                repo.resolveEffective(MODULE, null));
        assertEquals(LoggingProfileRepository.RESERVED_PROFILE_NAME,
                repo.resolveEffective(MODULE, "  "));
    }

    // â”€â”€ Test fixtures â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    static final class TestProfile extends ModuleLoggingProfile {
        @Override
        public String getModuleId() {
            return "node";
        }

        @Override
        public String getDisplayName() {
            return "Test Node";
        }

        @Override
        public String getDescription() {
            return "Test";
        }

        @Override
        public Map<String, String> getDefaults() {
            return Map.of("testmod.level", "INFO");
        }
    }

    static final class TestProvider extends ModuleLoggingProvider {
        private final ModuleLoggingProfile profile = new TestProfile();

        @Override
        public ModuleLoggingProfile getProfile() {
            return profile;
        }
    }
}
