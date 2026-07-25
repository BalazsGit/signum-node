package application.module.node.profile;

import application.module.node.lifecycle.NodeProfileRuntime;
import application.module.node.logging.NodeLoggingProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NodeProfile}.
 * <p>
 * Follows AAA pattern (Arrange-Act-Assert) with JUnit 5.
 * Tests cover isReservedProfileName() filtering logic, loadAll() discovery behavior,
 * Builder pattern, and new fields (runtime, propertiesPath, headlessMode, loggingProfile).
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
            boolean result = NodeProfile.isReservedProfileName("node-default");
            assertTrue(result);
        }

        @Test
        @DisplayName("returns true for 'logging-default' reserved profile name")
        void isReservedProfileName_GivenLoggingDefault_ReturnsTrue() {
            boolean result = NodeProfile.isReservedProfileName("logging-default");
            assertTrue(result);
        }

        @Test
        @DisplayName("returns false for user profile ending with '-default' (exact-name matching, not suffix)")
        void isReservedProfileName_GivenCustomDefaultSuffix_ReturnsFalse() {
            assertFalse(NodeProfile.isReservedProfileName("test-node-default"));
            assertFalse(NodeProfile.isReservedProfileName("my-custom-default"));
        }

        @Test
        @DisplayName("returns false for regular profile names")
        void isReservedProfileName_GivenRegularProfileName_ReturnsFalse() {
            assertFalse(NodeProfile.isReservedProfileName("mainnet"));
            assertFalse(NodeProfile.isReservedProfileName("testnet"));
        }

        @Test
        @DisplayName("returns false for null profile name")
        void isReservedProfileName_GivenNull_ReturnsFalse() {
            assertFalse(NodeProfile.isReservedProfileName(null));
        }

        @Test
        @DisplayName("returns false for empty string")
        void isReservedProfileName_GivenEmptyString_ReturnsFalse() {
            assertFalse(NodeProfile.isReservedProfileName(""));
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
            NodeProfile profile = new NodeProfile("testProfile");
            assertEquals("testProfile", profile.getName());
        }

        @Test
        @DisplayName("getProperty returns value from properties")
        void getProperty_GivenSetProperty_ReturnsValue() {
            NodeProfile profile = new NodeProfile("test");
            profile.setProperty("DB.Url", "jdbc:sqlite:file:./db/test.db");
            assertEquals("jdbc:sqlite:file:./db/test.db", profile.getProperty("DB.Url"));
        }

        @Test
        @DisplayName("getProperty with default returns default when key not found")
        void getPropertyWithDefault_GivenMissingKey_ReturnsDefault() {
            NodeProfile profile = new NodeProfile("test");
            assertEquals("fallback", profile.getProperty("nonexistent.key", "fallback"));
        }

        @Test
        @DisplayName("setProperty with null removes the property")
        void setProperty_GivenNullValue_RemovesProperty() {
            NodeProfile profile = new NodeProfile("test");
            profile.setProperty("key1", "value1");
            profile.setProperty("key1", null);
            assertNull(profile.getProperty("key1"));
        }

        @Test
        @DisplayName("setProperties replaces all existing properties")
        void setProperties_GivenNewProps_ReplacesAll() {
            NodeProfile profile = new NodeProfile("test");
            Properties original = new Properties();
            original.setProperty("key1", "value1");
            original.setProperty("key2", "value2");
            profile.setProperties(original);

            Properties replacement = new Properties();
            replacement.setProperty("key3", "value3");
            profile.setProperties(replacement);

            assertNull(profile.getProperty("key1"));
            assertNull(profile.getProperty("key2"));
            assertEquals("value3", profile.getProperty("key3"));
        }
    }

    // =====================================================================
    // Logging preset methods
    // =====================================================================

    @Nested
    @DisplayName("Logging Preset Methods")
    class LoggingPresetTests {

        @Test
        @DisplayName("getLoggingPreset returns default 'standard' when not set")
        void getLoggingPreset_GivenNotSet_ReturnsDefault() {
            NodeProfile profile = new NodeProfile("test");
            assertEquals(NodeProfile.DEFAULT_LOGGING_PRESET, profile.getLoggingPreset());
        }

        @Test
        @DisplayName("getLoggingPreset returns configured preset")
        void getLoggingPreset_GivenVerbose_ReturnsVerbose() {
            NodeProfile profile = new NodeProfile("test");
            profile.setLoggingPreset("verbose");
            assertEquals("verbose", profile.getLoggingPreset());
        }

        @Test
        @DisplayName("setLoggingPreset with empty string clears the preset")
        void setLoggingPreset_GivenEmptyString_ClearsPreset() {
            NodeProfile profile = new NodeProfile("test");
            profile.setLoggingPreset("debug");
            profile.setLoggingPreset("");
            assertEquals(NodeProfile.DEFAULT_LOGGING_PRESET, profile.getLoggingPreset());
            assertFalse(profile.hasLoggingPreset());
        }

        @Test
        @DisplayName("hasLoggingPreset returns true when preset is set")
        void hasLoggingPreset_GivenPresetSet_ReturnsTrue() {
            NodeProfile profile = new NodeProfile("test");
            profile.setLoggingPreset("minimal");
            assertTrue(profile.hasLoggingPreset());
        }

        @Test
        @DisplayName("PROPERTY_LOGGING_PRESET constant has expected value")
        void propertyKey_HasExpectedValue() {
            assertEquals("logging.preset", NodeProfile.PROPERTY_LOGGING_PRESET);
        }
    }

    // =====================================================================
    // Autostart methods
    // =====================================================================

    @Nested
    @DisplayName("Autostart Methods")
    class AutostartTests {

        @Test
        @DisplayName("isAutostart returns default false when not set")
        void isAutostart_GivenNotSet_ReturnsDefaultFalse() {
            NodeProfile profile = new NodeProfile("test");
            assertFalse(profile.isAutostart());
        }

        @Test
        @DisplayName("isAutostart returns true when set to 'true'")
        void isAutostart_GivenTrueString_ReturnsTrue() {
            NodeProfile profile = new NodeProfile("test");
            profile.setAutostart(true);
            assertTrue(profile.isAutostart());
        }

        @Test
        @DisplayName("isAutostart supports 'on' value")
        void isAutostart_GivenOnValue_ReturnsTrue() {
            NodeProfile profile = new NodeProfile("test");
            profile.setProperty(NodeProfile.PROPERTY_AUTOSTART, "on");
            assertTrue(profile.isAutostart());
        }

        @Test
        @DisplayName("isAutostart supports 'yes' value")
        void isAutostart_GivenYesValue_ReturnsTrue() {
            NodeProfile profile = new NodeProfile("test");
            profile.setProperty(NodeProfile.PROPERTY_AUTOSTART, "yes");
            assertTrue(profile.isAutostart());
        }

        @Test
        @DisplayName("hasAutostartSetting returns true when set")
        void hasAutostartSetting_GivenSet_ReturnsTrue() {
            NodeProfile profile = new NodeProfile("test");
            profile.setAutostart(true);
            assertTrue(profile.hasAutostartSetting());
        }

        @Test
        @DisplayName("hasAutostartSetting returns false when not set")
        void hasAutostartSetting_GivenNotSet_ReturnsFalse() {
            NodeProfile profile = new NodeProfile("test");
            assertFalse(profile.hasAutostartSetting());
        }

        @Test
        @DisplayName("PROPERTY_AUTOSTART constant has expected value")
        void propertyKey_AutostartHasExpectedValue() {
            assertEquals("node.autostart", NodeProfile.PROPERTY_AUTOSTART);
        }

        @Test
        @DisplayName("DEFAULT_AUTOSTART constant is false")
        void defaultAutostart_IsFalse() {
            assertFalse(NodeProfile.DEFAULT_AUTOSTART);
        }
    }

    // =====================================================================
    // Runtime Access (NEW - Phase 2b)
    // =====================================================================

    @Nested
    @DisplayName("Runtime Access")
    class RuntimeAccessTests {

        @Test
        @DisplayName("getRuntime returns non-null NodeProfileRuntime for legacy constructor")
        void getRuntime_GivenLegacyConstructor_ReturnsRuntime() {
            NodeProfile profile = new NodeProfile("test");
            assertNotNull(profile.getRuntime());
            assertTrue(profile.getRuntime() instanceof NodeProfileRuntime);
        }

        @Test
        @DisplayName("getRuntime returns non-null NodeProfileRuntime for Builder constructor")
        void getRuntime_GivenBuilderConstructor_ReturnsRuntime() {
            NodeProfile profile = new NodeProfile.Builder("test").build();
            assertNotNull(profile.getRuntime());
        }

        @Test
        @DisplayName("Runtime has default lifecycle state IDLE")
        void runtime_HasDefaultLifecycleStateIdle() {
            NodeProfile profile = new NodeProfile("test");
            // LifecycleStateMachine defaults to IDLE
            assertEquals(application.module.node.lifecycle.NodeLifecycleState.IDLE,
                    profile.getRuntime().getLifecycleState());
        }

        @Test
        @DisplayName("Each profile gets its own independent runtime instance")
        void runtime_EachProfileHasIndependentRuntime() {
            NodeProfile profile1 = new NodeProfile("mainnet");
            NodeProfile profile2 = new NodeProfile("testnet");
            assertNotSame(profile1.getRuntime(), profile2.getRuntime());
        }
    }

    // =====================================================================
    // PropertiesPath Access (NEW - Phase 2b)
    // =====================================================================

    @Nested
    @DisplayName("PropertiesPath Access")
    class PropertiesPathTests {

        @Test
        @DisplayName("getPropertiesPath returns null for legacy constructor")
        void getPropertiesPath_GivenLegacyConstructor_ReturnsNull() {
            NodeProfile profile = new NodeProfile("test");
            assertNull(profile.getPropertiesPath());
        }

        @Test
        @DisplayName("getPropertiesPath returns set path for Builder constructor")
        void getPropertiesPath_GivenBuilderWithPath_ReturnsPath() {
            Path expectedPath = Paths.get("/some/path/node.properties");
            NodeProfile profile = new NodeProfile.Builder("test")
                    .propertiesPath(expectedPath)
                    .build();
            assertEquals(expectedPath, profile.getPropertiesPath());
        }
    }

    // =====================================================================
    // Headless Mode Access (NEW - Phase 2b)
    // =====================================================================

    @Nested
    @DisplayName("Headless Mode Access")
    class HeadlessModeTests {

        @Test
        @DisplayName("isHeadlessMode returns true for legacy constructor")
        void isHeadlessMode_GivenLegacyConstructor_ReturnsTrue() {
            NodeProfile profile = new NodeProfile("test");
            assertTrue(profile.isHeadlessMode());
        }

        @Test
        @DisplayName("isHeadlessMode returns false when Builder sets headless(false)")
        void isHeadlessMode_GivenBuilderGuiMode_ReturnsFalse() {
            NodeProfile profile = new NodeProfile.Builder("test")
                    .headless(false)
                    .build();
            assertFalse(profile.isHeadlessMode());
        }

        @Test
        @DisplayName("isHeadlessMode returns true when Builder sets headless(true)")
        void isHeadlessMode_GivenBuilderHeadless_ReturnsTrue() {
            NodeProfile profile = new NodeProfile.Builder("test")
                    .headless(true)
                    .build();
            assertTrue(profile.isHeadlessMode());
        }

        @Test
        @DisplayName("getGuiSettings returns null in headless mode")
        void getGuiSettings_GivenHeadlessMode_ReturnsNull() {
            NodeProfile profile = new NodeProfile.Builder("test")
                    .headless(true)
                    .build();
            assertNull(profile.getGuiSettings());
        }

        @Test
        @DisplayName("getGuiSettings returns null when GuiSettingsLoader stubbed (Phase 4 pending)")
        void getGuiSettings_GivenGuiMode_ReturnsNull() {
            NodeProfile profile = new NodeProfile.Builder("test")
                    .headless(false)
                    .build();
            // Stub until GuiSettingsLoader is implemented in Phase 4
            assertNull(profile.getGuiSettings());
        }
    }

    // =====================================================================
    // LoggingProfile Access (NEW - Phase 2b)
    // =====================================================================

    @Nested
    @DisplayName("LoggingProfile Access")
    class LoggingProfileTests {

        @Test
        @DisplayName("getLoggingProfile returns null initially")
        void getLoggingProfile_GivenNotSet_ReturnsNull() {
            NodeProfile profile = new NodeProfile("test");
            assertNull(profile.getLoggingProfile());
        }

        @Test
        @DisplayName("setLoggingProfile sets and returns the logging profile")
        void setLoggingProfile_GivenProfile_ReturnsIt() {
            NodeProfile profile = new NodeProfile("test");
            NodeLoggingProfile logProfile = new NodeLoggingProfile();
            profile.setLoggingProfile(logProfile);
            assertSame(logProfile, profile.getLoggingProfile());
        }

        @Test
        @DisplayName("setLoggingProfile with null clears the reference")
        void setLoggingProfile_GivenNull_ClearsReference() {
            NodeProfile profile = new NodeProfile("test");
            profile.setLoggingProfile(new NodeLoggingProfile());
            profile.setLoggingProfile(null);
            assertNull(profile.getLoggingProfile());
        }
    }

    // =====================================================================
    // Builder Pattern (NEW - Phase 2c)
    // =====================================================================

    @Nested
    @DisplayName("Builder Pattern")
    class BuilderTests {

        @Test
        @DisplayName("Builder creates profile with given name")
        void builder_CreatesProfileWithGivenName() {
            NodeProfile profile = new NodeProfile.Builder("mainnet").build();
            assertEquals("mainnet", profile.getName());
        }

        @Test
        @DisplayName("Builder throws NullPointerException when name is null")
        void builder_ThrowsNPEWhenNameIsNull() {
            assertThrows(NullPointerException.class, () -> new NodeProfile.Builder(null));
        }

        @Test
        @DisplayName("Builder sets properties correctly")
        void builder_SetsPropertiesCorrectly() {
            Properties props = new Properties();
            props.setProperty("DB.Url", "jdbc:sqlite:test.db");
            props.setProperty("P2P.Port", "4150");

            NodeProfile profile = new NodeProfile.Builder("test")
                    .properties(props)
                    .build();

            assertEquals("jdbc:sqlite:test.db", profile.getProperty("DB.Url"));
            assertEquals("4150", profile.getProperty("P2P.Port"));
        }

        @Test
        @DisplayName("Builder handles null properties gracefully")
        void builder_HandlesNullPropertiesGracefully() {
            NodeProfile profile = new NodeProfile.Builder("test").build();
            // Properties are empty but not null
            assertNotNull(profile.getProperties());
            assertEquals(0, profile.getProperties().size());
        }

        @Test
        @DisplayName("Builder fluent API returns builder instance for chaining")
        void builder_FluentAPI_ReturnsBuilder() {
            NodeProfile.Builder builder = new NodeProfile.Builder("test");
            assertSame(builder, builder.properties(new Properties()));
            assertSame(builder, builder.propertiesPath(Paths.get("/path")));
            assertSame(builder, builder.headless(true));
        }

        @Test
        @DisplayName("Builder defaults headlessMode to false")
        void builder_DefaultsHeadlessModeToFalse() {
            NodeProfile profile = new NodeProfile.Builder("test").build();
            assertFalse(profile.isHeadlessMode());
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
            NodeProfile[] result = NodeProfile.loadAll();
            assertNotNull(result);
            for (NodeProfile profile : result) {
                assertFalse(NodeProfile.isReservedProfileName(profile.getName()),
                        "Reserved profile '" + profile.getName() + "' should not appear");
            }
        }

        @Test
        @DisplayName("loadByName returns null for non-existent profile")
        void loadByName_GivenNonExistentProfile_ReturnsNull() {
            assertNull(NodeProfile.loadByName("nonexistent-profile-xyz"));
        }
    }

    // =====================================================================
    // PropertiesProfileEntity contract
    // =====================================================================

    @Nested
    @DisplayName("PropertiesProfileEntity Contract")
    class ProfileEntityContractTests {

        @Test
        @DisplayName("NodeProfile implements getName() correctly")
        void implementsGetName() {
            NodeProfile profile = new NodeProfile("entity_test");
            assertEquals("entity_test", profile.getName());
        }

        @Test
        @DisplayName("NodeProfile implements getProperties() correctly")
        void implementsGetProperties() {
            NodeProfile profile = new NodeProfile("entity_test");
            assertNotNull(profile.getProperties());
            assertTrue(profile.getProperties() instanceof Properties);
        }

        @Test
        @DisplayName("NodeProfile implements setProperties() correctly")
        void implementsSetProperties() {
            NodeProfile profile = new NodeProfile("entity_test");
            Properties props = new Properties();
            props.setProperty("test.key", "test.value");
            profile.setProperties(props);
            assertEquals("test.value", profile.getProperty("test.key"));
        }
    }

    // =====================================================================
    // toString / equals / hashCode
    // =====================================================================

    @Nested
    @DisplayName("Object Contract")
    class ObjectContractTests {

        @Test
        @DisplayName("toString includes name and runtime info")
        void toString_ContainsNameAndRuntimeInfo() {
            NodeProfile profile = new NodeProfile.Builder("test").build();
            String result = profile.toString();
            assertTrue(result.contains("test"));
            assertTrue(result.contains("runtime="));
        }

        @Test
        @DisplayName("equals returns true for profiles with same name and properties")
        void equals_ReturnsTrueForSameNameAndProperties() {
            NodeProfile p1 = new NodeProfile.Builder("test").build();
            NodeProfile p2 = new NodeProfile.Builder("test").build();
            assertEquals(p1, p2);
        }

        @Test
        @DisplayName("equals returns false for different names")
        void equals_ReturnsFalseForDifferentNames() {
            NodeProfile p1 = new NodeProfile.Builder("mainnet").build();
            NodeProfile p2 = new NodeProfile.Builder("testnet").build();
            assertNotEquals(p1, p2);
        }

        @Test
        @DisplayName("hashCode is consistent with equals")
        void hashCode_IsConsistentWithEquals() {
            NodeProfile p1 = new NodeProfile.Builder("test").build();
            NodeProfile p2 = new NodeProfile.Builder("test").build();
            assertEquals(p1.hashCode(), p2.hashCode());
        }
    }
}