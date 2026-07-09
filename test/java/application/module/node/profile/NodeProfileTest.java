package application.module.node.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NodeProfile}.
 * <p>
 * Follows AAA pattern (Arrange-Act-Assert) with JUnit 5.
 * Tests cover isReservedProfileName() filtering logic and loadAll() discovery behavior.
 *
 * @since 4.0
 */
@DisplayName("NodeProfile Tests")
class NodeProfileTest {

    // =====================================================================
    // isReservedProfileName()
    // =====================================================================

    @Nested
    @DisplayName("isReservedProfileName()")
    class IsReservedProfileNameTests {

        @Test
        @DisplayName("returns true for 'node-default' reserved profile name")
        void isReservedProfileName_GivenNodeDefault_ReturnsTrue() {
            // Act
            boolean result = NodeProfile.isReservedProfileName("node-default");

            // Assert
            assertTrue(result, "'node-default' is a reserved template name and should be excluded");
        }

        @Test
        @DisplayName("returns true for 'logging-default' reserved profile name")
        void isReservedProfileName_GivenLoggingDefault_ReturnsTrue() {
            // Act
            boolean result = NodeProfile.isReservedProfileName("logging-default");

            // Assert
            assertTrue(result, "'logging-default' is a reserved template name and should be excluded");
        }

        @Test
        @DisplayName("returns false for user profile ending with '-default' (exact-name matching, not suffix)")
        void isReservedProfileName_GivenCustomDefaultSuffix_ReturnsFalse() {
            // Act & Assert
            // We use exact-name matching (not endsWith) so user profiles like these are allowed
            assertFalse(NodeProfile.isReservedProfileName("test-profile-default"));
            assertFalse(NodeProfile.isReservedProfileName("my-custom-default"));
            assertFalse(NodeProfile.isReservedProfileName("some-module-default"));
        }

        @Test
        @DisplayName("returns false for regular profile names")
        void isReservedProfileName_GivenRegularProfileName_ReturnsFalse() {
            // Act & Assert
            assertFalse(NodeProfile.isReservedProfileName("mainnet"));
            assertFalse(NodeProfile.isReservedProfileName("testnet"));
            assertFalse(NodeProfile.isReservedProfileName("sqlite"));
            assertFalse(NodeProfile.isReservedProfileName("mariadb"));
        }

        @Test
        @DisplayName("returns false for profile name containing 'default' but not an exact reserved name")
        void isReservedProfileName_GivenNameContainingDefault_ReturnsFalse() {
            // Act & Assert
            assertFalse(NodeProfile.isReservedProfileName("my-default-settings"));
            assertFalse(NodeProfile.isReservedProfileName("defaults-v2"));
        }

        @Test
        @DisplayName("returns false for null profile name")
        void isReservedProfileName_GivenNull_ReturnsFalse() {
            // Act
            boolean result = NodeProfile.isReservedProfileName(null);

            // Assert
            assertFalse(result, "null should not be considered a reserved name");
        }

        @Test
        @DisplayName("returns false for empty string")
        void isReservedProfileName_GivenEmptyString_ReturnsFalse() {
            // Act
            boolean result = NodeProfile.isReservedProfileName("");

            // Assert
            assertFalse(result, "empty string should not be considered a reserved name");
        }
    }

    // =====================================================================
    // Instance methods
    // =====================================================================

    @Nested
    @DisplayName("Instance Methods")
    class InstanceMethodTests {

        @Test
        @DisplayName("getName returns the profile name set in constructor")
        void getName_ReturnsProfileName() {
            // Arrange
            String expectedName = "testProfile";
            NodeProfile profile = new NodeProfile(expectedName);

            // Act
            String result = profile.getName();

            // Assert
            assertEquals(expectedName, result);
        }

        @Test
        @DisplayName("getProperty returns value from properties")
        void getProperty_GivenSetProperty_ReturnsValue() {
            // Arrange
            NodeProfile profile = new NodeProfile("test");
            profile.setProperty("DB.Url", "jdbc:sqlite:file:./db/test.db");

            // Act
            String result = profile.getProperty("DB.Url");

            // Assert
            assertEquals("jdbc:sqlite:file:./db/test.db", result);
        }

        @Test
        @DisplayName("getProperty with default returns default when key not found")
        void getPropertyWithDefault_GivenMissingKey_ReturnsDefault() {
            // Arrange
            NodeProfile profile = new NodeProfile("test");

            // Act
            String result = profile.getProperty("nonexistent.key", "fallback");

            // Assert
            assertEquals("fallback", result);
        }

        @Test
        @DisplayName("setProperty with null removes the property")
        void setProperty_GivenNullValue_RemovesProperty() {
            // Arrange
            NodeProfile profile = new NodeProfile("test");
            profile.setProperty("key1", "value1");

            // Act
            profile.setProperty("key1", null);

            // Assert
            assertNull(profile.getProperty("key1"));
        }

        @Test
        @DisplayName("setProperties replaces all existing properties")
        void setProperties_GivenNewProps_ReplacesAll() {
            // Arrange
            NodeProfile profile = new NodeProfile("test");
            Properties original = new Properties();
            original.setProperty("key1", "value1");
            original.setProperty("key2", "value2");
            profile.setProperties(original);

            Properties replacement = new Properties();
            replacement.setProperty("key3", "value3");

            // Act
            profile.setProperties(replacement);

            // Assert
            assertNull(profile.getProperty("key1"));
            assertNull(profile.getProperty("key2"));
            assertEquals("value3", profile.getProperty("key3"));
        }
    }

    // =====================================================================
    // loadAll() / loadByName() - static method tests
    // =====================================================================

    @Nested
    @DisplayName("loadAll() Profile Discovery")
    class LoadAllTests {

        @Test
        @DisplayName("loadAll never returns reserved profile names in results")
        void loadAll_NeverReturnsReservedProfiles() {
            // Act
            NodeProfile[] result = NodeProfile.loadAll();

            // Assert: returns an array (never null)
            assertNotNull(result);

            // No reserved profile should appear in the results
            for (NodeProfile profile : result) {
                assertFalse(NodeProfile.isReservedProfileName(profile.getName()),
                        "Reserved profile '" + profile.getName() +
                        "' should never appear in loadAll() results");
            }
        }

        @Test
        @DisplayName("loadByName returns null for non-existent profile")
        void loadByName_GivenNonExistentProfile_ReturnsNull() {
            // Act
            NodeProfile result = NodeProfile.loadByName("nonexistent-profile-xyz");

            // Assert
            assertNull(result);
        }
    }
}