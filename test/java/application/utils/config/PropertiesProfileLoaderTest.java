package application.utils.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PropertiesProfileLoader}.
 * <p>
 * Tests cover path resolution, discovery, loading, hash-based sync,
 * placeholder creation, and directory management using temporary directories.
 *
 * @since 4.0
 */
@DisplayName("PropertiesProfileLoader Tests")
class PropertiesProfileLoaderTest {

    @TempDir
    Path tempDir;

    private static final String CONF_ROOT = "."; // tempDir root for tests
    private static final Set<String> RESERVED = Set.of("profile-default", "logging-default");

    // =====================================================================
    // resolveProfileDir()
    // =====================================================================

    @Nested
    @DisplayName("resolveProfileDir()")
    class ResolveProfileDirTests {

        @Test
        @DisplayName("resolves correct path for given module and category")
        void resolveProfileDir_GivenValidInputs_ReturnsCorrectPath() {
            // Act
            Path result = PropertiesProfileLoader.resolveProfileDir(
                    CONF_ROOT, "node", "profiles");

            // Assert
            assertTrue(result.endsWith("node/profiles"));
        }

        @Test
        @DisplayName("resolves different paths for different categories")
        void resolveProfileDir_GivenDifferentCategories_ReturnsDifferentPaths() {
            // Act
            Path profiles = PropertiesProfileLoader.resolveProfileDir(
                    CONF_ROOT, "node", "profiles");
            Path logging = PropertiesProfileLoader.resolveProfileDir(
                    CONF_ROOT, "node", "logging");

            // Assert
            assertNotEquals(profiles, logging);
        }

        @Test
        @DisplayName("resolves different paths for different modules")
        void resolveProfileDir_GivenDifferentModules_ReturnsDifferentPaths() {
            // Act
            Path node = PropertiesProfileLoader.resolveProfileDir(
                    CONF_ROOT, "node", "profiles");
            Path database = PropertiesProfileLoader.resolveProfileDir(
                    CONF_ROOT, "database", "profiles");

            // Assert
            assertNotEquals(node, database);
        }
    }

    // =====================================================================
    // resolveProfileFile()
    // =====================================================================

    @Nested
    @DisplayName("resolveProfileFile()")
    class ResolveProfileFileTests {

        @Test
        @DisplayName("resolves correct path for profile file")
        void resolveProfileFile_GivenValidInputs_ReturnsCorrectPath() {
            // Act
            Path result = PropertiesProfileLoader.resolveProfileFile(
                    CONF_ROOT, "node", "profiles", "mainnet");

            // Assert
            assertTrue(result.endsWith("node/profiles/mainnet.properties"));
        }

        @Test
        @DisplayName("handles profile name already ending with .properties")
        void resolveProfileFile_GivenNameWithExtension_DoesNotDuplicate() {
            // Act
            Path result = PropertiesProfileLoader.resolveProfileFile(
                    CONF_ROOT, "node", "profiles", "mainnet.properties");

            // Assert
            assertTrue(result.endsWith("node/profiles/mainnet.properties"));
            assertFalse(result.toString().endsWith(".properties.properties"));
        }
    }

    // =====================================================================
    // discoverProfiles()
    // =====================================================================

    @Nested
    @DisplayName("discoverProfiles()")
    class DiscoverProfilesTests {

        @Test
        @DisplayName("returns empty list when directory does not exist")
        void discoverProfiles_GivenMissingDir_ReturnsEmptyList() {
            // Act
            List<String> result = PropertiesProfileLoader.discoverProfiles(
                    CONF_ROOT, "nonexistent", "profiles", RESERVED);

            // Assert
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty list when directory exists but has no profiles")
        void discoverProfiles_GivenEmptyDir_ReturnsEmptyList() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("empty/module/profiles");
            Files.createDirectories(dir);

            // Act
            List<String> result = PropertiesProfileLoader.discoverProfiles(
                    tempDir.toString(), "empty", "profiles", RESERVED);

            // Assert
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns profile names sorted alphabetically")
        void discoverProfiles_GivenMultipleProfiles_ReturnsSortedNames() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("sorted/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "zebra.properties");
            createPropertiesFile(dir, "alpha.properties");
            createPropertiesFile(dir, "mike.properties");

            // Act
            List<String> result = PropertiesProfileLoader.discoverProfiles(
                    tempDir.toString(), "sorted", "profiles", RESERVED);

            // Assert
            assertEquals(List.of("alpha", "mike", "zebra"), result);
        }

        @Test
        @DisplayName("excludes reserved profile names from discovery")
        void discoverProfiles_GivenReservedNames_ExcludesThem() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("reserved/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "profile-default.properties");
            createPropertiesFile(dir, "logging-default.properties");
            createPropertiesFile(dir, "user-profile.properties");

            // Act
            List<String> result = PropertiesProfileLoader.discoverProfiles(
                    tempDir.toString(), "reserved", "profiles", RESERVED);

            // Assert
            assertEquals(List.of("user-profile"), result);
        }

        @Test
        @DisplayName("ignores non-properties files")
        void discoverProfiles_GivenMixedFiles_OnlyReturnsProperties() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("mixed/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "valid.properties");
            Files.write(dir.resolve("readme.md"), "# Profiles".getBytes());
            Files.write(dir.resolve("config.json"), "{}".getBytes());

            // Act
            List<String> result = PropertiesProfileLoader.discoverProfiles(
                    tempDir.toString(), "mixed", "profiles", RESERVED);

            // Assert
            assertEquals(List.of("valid"), result);
        }

        @Test
        @DisplayName("works with null reserved names set")
        void discoverProfiles_GivenNullReserved_ReturnsAll() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("null/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "a.properties");

            // Act
            List<String> result = PropertiesProfileLoader.discoverProfiles(
                    tempDir.toString(), "null", "profiles", null);

            // Assert
            assertEquals(List.of("a"), result);
        }
    }

    // =====================================================================
    // countProfiles()
    // =====================================================================

    @Nested
    @DisplayName("countProfiles()")
    class CountProfilesTests {

        @Test
        @DisplayName("returns zero when no profiles exist")
        void countProfiles_GivenNoProfiles_ReturnsZero() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("count/module/profiles");
            Files.createDirectories(dir);

            // Act
            int result = PropertiesProfileLoader.countProfiles(
                    tempDir.toString(), "count", "profiles", RESERVED);

            // Assert
            assertEquals(0, result);
        }

        @Test
        @DisplayName("returns correct count excluding reserved names")
        void countProfiles_GivenMixed_ReturnsCorrectCount() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("count2/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "profile-default.properties");
            createPropertiesFile(dir, "mainnet.properties");
            createPropertiesFile(dir, "testnet.properties");

            // Act
            int result = PropertiesProfileLoader.countProfiles(
                    tempDir.toString(), "count2", "profiles", RESERVED);

            // Assert
            assertEquals(2, result);
        }
    }

    // =====================================================================
    // loadProfile()
    // =====================================================================

    @Nested
    @DisplayName("loadProfile()")
    class LoadProfileTests {

        @Test
        @DisplayName("returns empty Properties when file does not exist")
        void loadProfile_GivenMissingFile_ReturnsEmptyProperties() {
            // Act
            Properties result = PropertiesProfileLoader.loadProfile(
                    CONF_ROOT, "missing", "profiles", "nonexistent");

            // Assert
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("loads properties from valid file")
        void loadProfile_GivenValidFile_ReturnsProperties() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("load/module/profiles");
            Files.createDirectories(dir);
            String content = "key1=value1\nkey2=value2\n";
            Files.write(dir.resolve("test.properties"), content.getBytes(StandardCharsets.UTF_8));

            // Act
            Properties result = PropertiesProfileLoader.loadProfile(
                    tempDir.toString(), "load", "profiles", "test");

            // Assert
            assertEquals("value1", result.getProperty("key1"));
            assertEquals("value2", result.getProperty("key2"));
        }
    }

    // =====================================================================
    // loadAll() with factory
    // =====================================================================

    @Nested
    @DisplayName("loadAll() with Factory")
    class LoadAllTests {

        @Test
        @DisplayName("returns empty array when no profiles exist")
        void loadAll_GivenNoProfiles_ReturnsEmptyArray() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("all/module/profiles");
            Files.createDirectories(dir);

            // Act
            TestProfile[] result = PropertiesProfileLoader.loadAll(
                    tempDir.toString(), "all", "profiles", RESERVED,
                    TestProfile::new, TestProfile.class);

            // Assert
            assertNotNull(result);
            assertEquals(0, result.length);
        }

        @Test
        @DisplayName("loads all profiles using factory")
        void loadAll_GivenValidProfiles_ReturnsLoadedEntities() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("all2/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "alpha.properties", "name=Alpha\nvalue=100");
            createPropertiesFile(dir, "beta.properties", "name=Beta\nvalue=200");

            // Act
            TestProfile[] result = PropertiesProfileLoader.loadAll(
                    tempDir.toString(), "all2", "profiles", RESERVED,
                    TestProfile::new, TestProfile.class);

            // Assert
            assertEquals(2, result.length);
            assertEquals("alpha", result[0].getName());
            assertEquals("Alpha", result[0].getProperties().getProperty("name"));
            assertEquals("beta", result[1].getName());
            assertEquals("Beta", result[1].getProperties().getProperty("name"));
        }

        @Test
        @DisplayName("excludes reserved profiles from loadAll")
        void loadAll_GivenReservedProfiles_ExcludesThem() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("all3/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "profile-default.properties", "x=1");
            createPropertiesFile(dir, "logging-default.properties", "y=2");
            createPropertiesFile(dir, "user.properties", "z=3");

            // Act
            TestProfile[] result = PropertiesProfileLoader.loadAll(
                    tempDir.toString(), "all3", "profiles", RESERVED,
                    TestProfile::new, TestProfile.class);

            // Assert
            assertEquals(1, result.length);
            assertEquals("user", result[0].getName());
        }
    }

    // =====================================================================
    // syncDefaultFile() (hash-based)
    // =====================================================================

    @Nested
    @DisplayName("syncDefaultFile()")
    class SyncDefaultFileTests {

        @Test
        @DisplayName("creates file when it does not exist")
        void syncDefaultFile_GivenMissingTarget_CreatesFile() throws Exception {
            // Arrange
            Path targetDir = tempDir.resolve("sync/module/profiles");
            Files.createDirectories(targetDir);
            String content = "default.key=default.value";
            ByteArrayInputStream resource = new ByteArrayInputStream(
                    content.getBytes(StandardCharsets.UTF_8));

            // Act
            PropertiesProfileLoader.syncDefaultFile(
                    tempDir.toString(), "sync", "profiles",
                    "profile-default.properties", resource);

            // Assert
            Path file = targetDir.resolve("profile-default.properties");
            assertTrue(Files.exists(file));
            assertEquals("default.value",
                    Files.readString(file).split("=")[1]);
        }

        @Test
        @DisplayName("overwrites file when hash differs (update detected)")
        void syncDefaultFile_GivenHashDiffers_Overwrites() throws Exception {
            // Arrange
            Path targetDir = tempDir.resolve("sync2/module/profiles");
            Files.createDirectories(targetDir);
            Path file = targetDir.resolve("profile-default.properties");
            Files.write(file, "old.content=true".getBytes(StandardCharsets.UTF_8));

            String newContent = "new.content=false";
            ByteArrayInputStream resource = new ByteArrayInputStream(
                    newContent.getBytes(StandardCharsets.UTF_8));

            // Act
            PropertiesProfileLoader.syncDefaultFile(
                    tempDir.toString(), "sync2", "profiles",
                    "profile-default.properties", resource);

            // Assert
            String content = Files.readString(file);
            assertTrue(content.contains("new.content"));
            assertFalse(content.contains("old.content"));
        }

        @Test
        @DisplayName("does nothing when hash matches")
        void syncDefaultFile_GivenHashMatches_DoesNotOverwrite() throws Exception {
            // Arrange
            Path targetDir = tempDir.resolve("sync3/module/profiles");
            Files.createDirectories(targetDir);
            Path file = targetDir.resolve("profile-default.properties");
            String content = "unchanged=value";
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));

            ByteArrayInputStream resource = new ByteArrayInputStream(
                    content.getBytes(StandardCharsets.UTF_8));

            // Act
            PropertiesProfileLoader.syncDefaultFile(
                    tempDir.toString(), "sync3", "profiles",
                    "profile-default.properties", resource);

            // Assert - file content unchanged
            assertEquals(content, Files.readString(file));
        }

        @Test
        @DisplayName("throws exception when classpath resource is null")
        void syncDefaultFile_GivenNullResource_ThrowsException() {
            // Act & Assert
            assertThrows(IllegalArgumentException.class, () ->
                PropertiesProfileLoader.syncDefaultFile(
                        CONF_ROOT, "node", "profiles",
                        "profile-default.properties", null));
        }
    }

    // =====================================================================
    // ensureEmptyPlaceholderIfNoProfiles()
    // =====================================================================

    @Nested
    @DisplayName("ensureEmptyPlaceholderIfNoProfiles()")
    class PlaceholderTests {

        @Test
        @DisplayName("creates placeholder when no user profiles exist")
        void ensurePlaceholder_GivenNoProfiles_CreatesPlaceholder() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("placeholder/module/profiles");
            Files.createDirectories(dir);

            // Act
            PropertiesProfileLoader.ensureEmptyPlaceholderIfNoProfiles(
                    tempDir.toString(), "placeholder", "profiles", RESERVED, "empty-placeholder");

            // Assert
            Path file = dir.resolve("empty-placeholder.properties");
            assertTrue(Files.exists(file));
        }

        @Test
        @DisplayName("does not create placeholder when user profiles exist")
        void ensurePlaceholder_GivenUserProfiles_DoesNotCreate() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("placeholder2/module/profiles");
            Files.createDirectories(dir);
            createPropertiesFile(dir, "user.properties", "x=1");

            // Act
            PropertiesProfileLoader.ensureEmptyPlaceholderIfNoProfiles(
                    tempDir.toString(), "placeholder2", "profiles", RESERVED, "empty-placeholder");

            // Assert
            Path file = dir.resolve("empty-placeholder.properties");
            assertFalse(Files.exists(file));
        }

        @Test
        @DisplayName("does not overwrite existing placeholder")
        void ensurePlaceholder_GivenExistingPlaceholder_DoesNotOverwrite() throws Exception {
            // Arrange
            Path dir = tempDir.resolve("placeholder3/module/profiles");
            Files.createDirectories(dir);
            Path existingFile = dir.resolve("empty-placeholder.properties");
            Files.write(existingFile, "existing=true".getBytes(StandardCharsets.UTF_8));

            // Act
            PropertiesProfileLoader.ensureEmptyPlaceholderIfNoProfiles(
                    tempDir.toString(), "placeholder3", "profiles", RESERVED, "empty-placeholder");

            // Assert
            String content = Files.readString(existingFile);
            assertTrue(content.contains("existing"));
        }
    }

    // =====================================================================
    // ensureProfileDirExists()
    // =====================================================================

    @Nested
    @DisplayName("ensureProfileDirExists()")
    class EnsureDirTests {

        @Test
        @DisplayName("creates directory when it does not exist")
        void ensureDir_GivenMissingDir_CreatesIt() throws Exception {
            // Act
            Path result = PropertiesProfileLoader.ensureProfileDirExists(
                    tempDir.toString(), "new-module", "profiles");

            // Assert
            assertTrue(Files.exists(result));
            assertTrue(Files.isDirectory(result));
        }

        @Test
        @DisplayName("returns existing directory when it already exists")
        void ensureDir_GivenExistingDir_ReturnsIt() throws Exception {
            // Arrange
            Path existing = tempDir.resolve("existing/module/profiles");
            Files.createDirectories(existing);

            // Act
            Path result = PropertiesProfileLoader.ensureProfileDirExists(
                    tempDir.toString(), "existing", "profiles");

            // Assert
            assertEquals(normalize(existing), normalize(result));
        }
    }

    // =====================================================================
    // computeSha256()
    // =====================================================================

    @Nested
    @DisplayName("computeSha256()")
    class Sha256Tests {

        @Test
        @DisplayName("produces consistent hash for same content")
        void computeSha256_GivenSameContent_ReturnsSameHash() throws Exception {
            // Arrange
            String content = "test content";
            ByteArrayInputStream is1 = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            ByteArrayInputStream is2 = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            // Act
            String hash1 = PropertiesProfileLoader.computeSha256(is1);
            String hash2 = PropertiesProfileLoader.computeSha256(is2);

            // Assert
            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("produces different hash for different content")
        void computeSha256_GivenDifferentContent_ReturnsDifferentHash() throws Exception {
            // Arrange
            ByteArrayInputStream is1 = new ByteArrayInputStream("content A".getBytes(StandardCharsets.UTF_8));
            ByteArrayInputStream is2 = new ByteArrayInputStream("content B".getBytes(StandardCharsets.UTF_8));

            // Act
            String hash1 = PropertiesProfileLoader.computeSha256(is1);
            String hash2 = PropertiesProfileLoader.computeSha256(is2);

            // Assert
            assertNotEquals(hash1, hash2);
        }

        @Test
        @DisplayName("produces 64-character hex string")
        void computeSha256_GivenAnyContent_Returns64CharHex() throws Exception {
            // Arrange
            ByteArrayInputStream is = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));

            // Act
            String hash = PropertiesProfileLoader.computeSha256(is);

            // Assert
            assertEquals(64, hash.length());
            assertTrue(hash.matches("[0-9a-f]+"));
        }
    }

    // =====================================================================
    // initializeModule() - Centralized Bootstrap
    // =====================================================================

    @Nested
    @DisplayName("initializeModule()")
    class InitializeModuleTests {

        @Test
        @DisplayName("creates profile and logging directories when they do not exist")
        void initializeModule_GivenMissingDirs_CreatesBoth() throws Exception {
            // Arrange
            String confRoot = tempDir.toString();
            String moduleId = "new-module";
            Set<String> reserved = RESERVED;

            // Act
            PropertiesProfileLoader.initializeModule(
                    confRoot, moduleId, reserved, "fallback", "logging-fallback");

            // Assert - directories created
            Path profilesDir = tempDir.resolve(moduleId + "/profiles");
            Path loggingDir = tempDir.resolve(moduleId + "/logging");
            assertTrue(Files.exists(profilesDir), "Profiles directory should exist");
            assertTrue(Files.exists(loggingDir), "Logging directory should exist");
        }

        @Test
        @DisplayName("creates fallback placeholder profile when no user profiles exist")
        void initializeModule_GivenNoUserProfiles_CreatesFallback() throws Exception {
            // Arrange
            String confRoot = tempDir.toString();
            String moduleId = "fallback-module";
            Set<String> reserved = RESERVED;

            // Act
            PropertiesProfileLoader.initializeModule(
                    confRoot, moduleId, reserved, "my-fallback", "logging-fallback");

            // Assert - fallback profile created
            Path fallbackFile = tempDir.resolve(moduleId + "/profiles/my-fallback.properties");
            assertTrue(Files.exists(fallbackFile), "Fallback profile should be created");
        }

        @Test
        @DisplayName("does not create fallback when user profiles already exist")
        void initializeModule_GivenUserProfilesExists_DoesNotCreateFallback() throws Exception {
            // Arrange
            String confRoot = tempDir.toString();
            String moduleId = "existing-module";
            Path profilesDir = tempDir.resolve(moduleId + "/profiles");
            Path loggingDir = tempDir.resolve(moduleId + "/logging");
            Files.createDirectories(profilesDir);
            Files.createDirectories(loggingDir);
            createPropertiesFile(profilesDir, "user-profile.properties", "key=value");
            Set<String> reserved = RESERVED;

            // Act
            PropertiesProfileLoader.initializeModule(
                    confRoot, moduleId, reserved, "my-fallback", "logging-fallback");

            // Assert - no fallback created
            Path fallbackFile = profilesDir.resolve("my-fallback.properties");
            assertFalse(Files.exists(fallbackFile), "Fallback should NOT be created when user profile exists");
        }

        @Test
        @DisplayName("gracefully skips sync when classpath resource does not exist but still creates dirs and fallback")
        void initializeModule_GivenMissingResource_SkipsSyncButCreatesFallback() throws Exception {
            // Arrange
            String confRoot = tempDir.toString();
            String moduleId = "no-resource-module";
            Set<String> reserved = RESERVED;

            // Act - should not throw, gracefully skips missing classpath resources
            PropertiesProfileLoader.initializeModule(
                    confRoot, moduleId, reserved, "fallback", "logging-fallback");

            // Assert - directories and fallback created even without classpath resources
            Path profilesDir = tempDir.resolve(moduleId + "/profiles");
            Path loggingDir = tempDir.resolve(moduleId + "/logging");
            assertTrue(Files.exists(profilesDir), "Profiles directory should exist");
            assertTrue(Files.exists(loggingDir), "Logging directory should exist");
            Path fallbackFile = profilesDir.resolve("fallback.properties");
            assertTrue(Files.exists(fallbackFile), "Fallback profile should be created");
        }
    }

    // =====================================================================
    // Constants
    // =====================================================================

    @Nested
    @DisplayName("Constants")
    class ConstantsTests {

        @Test
        @DisplayName("DEFAULT_CATEGORY_PROFILES is 'profiles'")
        void defaultCategoryProfiles_HasExpectedValue() {
            assertEquals("profiles", PropertiesProfileLoader.DEFAULT_CATEGORY_PROFILES);
        }

        @Test
        @DisplayName("DEFAULT_CATEGORY_LOGGING is 'logging'")
        void defaultCategoryLogging_HasExpectedValue() {
            assertEquals("logging", PropertiesProfileLoader.DEFAULT_CATEGORY_LOGGING);
        }

        @Test
        @DisplayName("DEFAULT_CONF_ROOT is 'conf'")
        void defaultConfRoot_HasExpectedValue() {
            assertEquals("conf", PropertiesProfileLoader.DEFAULT_CONF_ROOT);
        }

        @Test
        @DisplayName("DEFAULT_MODULE_DEFAULT_FILENAME ends with profile-default.properties")
        void defaultModuleFilename_HasExpectedValue() {
            assertEquals("profile-default.properties", PropertiesProfileLoader.DEFAULT_MODULE_DEFAULT_FILENAME);
        }

        @Test
        @DisplayName("DEFAULT_LOGGING_DEFAULT_FILENAME ends with logging-default.properties")
        void defaultLoggingFilename_HasExpectedValue() {
            assertEquals("logging-default.properties", PropertiesProfileLoader.DEFAULT_LOGGING_DEFAULT_FILENAME);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private void createPropertiesFile(Path dir, String name) throws Exception {
        Files.write(dir.resolve(name), "".getBytes(StandardCharsets.UTF_8));
    }

    private void createPropertiesFile(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private Path normalize(Path p) {
        return p.normalize().toAbsolutePath();
    }

    // =====================================================================
    // Test helper: minimal PropertiesProfileEntity implementation
    // =====================================================================

    static class TestProfile implements PropertiesProfileEntity {
        private final String name;
        private final Properties properties = new Properties();

        TestProfile(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Properties getProperties() {
            return properties;
        }

        @Override
        public void setProperties(Properties props) {
            this.properties.clear();
            this.properties.putAll(props);
        }
    }
}