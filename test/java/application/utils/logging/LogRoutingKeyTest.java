package application.utils.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogRoutingKey}.
 * <p>
 * Verifies immutability, equality/hasCode contract, factory method behavior,
 * and the critical multi-module isolation scenario (same profile name, different modules).
 * </p>
 */
@DisplayName("LogRoutingKey Tests")
class LogRoutingKeyTest {

    // ------------------------ Factory Method ------------------------

    @Nested
    @DisplayName("of() factory method")
    class OfFactoryTests {

        @Test
        @DisplayName("creates key with valid moduleId and profileName")
        void of_GivenValidParams_ReturnsKey() {
            LogRoutingKey key = LogRoutingKey.of("node", "profil-bela");

            assertNotNull(key);
            assertEquals("node", key.getModuleId());
            assertEquals("profil-bela", key.getProfileName());
            assertTrue(key.hasModule());
            assertTrue(key.hasProfile());
            assertFalse(key.isEmpty());
        }

        @Test
        @DisplayName("creates key with null moduleId (profile-only)")
        void of_GivenNullModule_ReturnsKeyWithProfileOnly() {
            LogRoutingKey key = LogRoutingKey.of(null, "profil-bela");

            assertNotNull(key);
            assertNull(key.getModuleId());
            assertEquals("profil-bela", key.getProfileName());
            assertFalse(key.hasModule());
            assertTrue(key.hasProfile());
            assertFalse(key.isEmpty());
        }

        @Test
        @DisplayName("creates key with null profileName (module-only)")
        void of_GivenNullProfile_ReturnsKeyWithModuleOnly() {
            LogRoutingKey key = LogRoutingKey.of("node", null);

            assertNotNull(key);
            assertEquals("node", key.getModuleId());
            assertNull(key.getProfileName());
            assertTrue(key.hasModule());
            assertFalse(key.hasProfile());
            assertFalse(key.isEmpty());
        }

        @Test
        @DisplayName("returns null when both moduleId and profileName are null")
        void of_GivenBothNull_ReturnsNull() {
            LogRoutingKey key = LogRoutingKey.of(null, null);

            assertNull(key);
        }

        @Test
        @DisplayName("returns null when both moduleId and profileName are empty")
        void of_GivenBothEmpty_ReturnsNull() {
            LogRoutingKey key = LogRoutingKey.of("", "");

            assertNull(key);
        }

        @Test
        @DisplayName("returns null when moduleId is empty and profileName is null")
        void of_GivenEmptyModuleAndNullProfile_ReturnsNull() {
            LogRoutingKey key = LogRoutingKey.of("", null);

            assertNull(key);
        }
    }

    // ------------------------ Equality & HashCode ------------------------

    @Nested
    @DisplayName("equals() and hashCode() contract")
    class EqualityTests {

        @Test
        @DisplayName("same module + same profile → equal")
        void equals_GivenSameModuleAndProfile_ReturnsTrue() {
            LogRoutingKey key1 = LogRoutingKey.of("node", "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of("node", "profil-bela");

            assertEquals(key1, key2);
            assertEquals(key1.hashCode(), key2.hashCode());
        }

        @Test
        @DisplayName("different module + same profile → NOT equal (V2.3 isolation)")
        void equals_GivenDifferentModuleSameProfile_ReturnsFalse() {
            // CRITICAL: This is the core V2.3 multi-module isolation guarantee
            LogRoutingKey key1 = LogRoutingKey.of("node", "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of("database", "profil-bela");

            assertNotEquals(key1, key2);
        }

        @Test
        @DisplayName("same module + different profile → NOT equal")
        void equals_GivenSameModuleDifferentProfile_ReturnsFalse() {
            LogRoutingKey key1 = LogRoutingKey.of("node", "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of("node", "mainnet-prune");

            assertNotEquals(key1, key2);
        }

        @Test
        @DisplayName("different module + different profile → NOT equal")
        void equals_GivenDifferentModuleAndProfile_ReturnsFalse() {
            LogRoutingKey key1 = LogRoutingKey.of("node", "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of("mining", "mainnet");

            assertNotEquals(key1, key2);
        }

        @Test
        @DisplayName("null module keys with same profile → equal")
        void equals_GivenNullModuleSameProfile_ReturnsTrue() {
            LogRoutingKey key1 = LogRoutingKey.of(null, "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of(null, "profil-bela");

            assertEquals(key1, key2);
        }

        @Test
        @DisplayName("null module vs empty string module with same profile → NOT equal")
        void equals_GivenNullVsEmptyModule_ReturnsFalse() {
            LogRoutingKey key1 = LogRoutingKey.of(null, "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of("", "profil-bela");

            assertNotEquals(key1, key2);
        }

        @Test
        @DisplayName("equals is reflexive")
        void equals_GivenSameInstance_ReturnsTrue() {
            LogRoutingKey key = LogRoutingKey.of("node", "test");

            assertEquals(key, key);
        }

        @Test
        @DisplayName("equals is symmetric")
        void equals_GivenTwoKeys_IsSymmetric() {
            LogRoutingKey key1 = LogRoutingKey.of("node", "test");
            LogRoutingKey key2 = LogRoutingKey.of("node", "test");

            assertTrue(key1.equals(key2));
            assertTrue(key2.equals(key1));
        }

        @Test
        @DisplayName("equals is transitive")
        void equals_GivenThreeKeys_IsTransitive() {
            LogRoutingKey key1 = LogRoutingKey.of("node", "test");
            LogRoutingKey key2 = LogRoutingKey.of("node", "test");
            LogRoutingKey key3 = LogRoutingKey.of("node", "test");

            assertTrue(key1.equals(key2));
            assertTrue(key2.equals(key3));
            assertTrue(key1.equals(key3));
        }

        @Test
        @DisplayName("equals returns false for null")
        void equals_GivenNull_ReturnsFalse() {
            LogRoutingKey key = LogRoutingKey.of("node", "test");

            assertFalse(key.equals(null));
        }

        @Test
        @DisplayName("equals returns false for different type")
        void equals_GivenDifferentType_ReturnsFalse() {
            LogRoutingKey key = LogRoutingKey.of("node", "test");

            assertFalse(key.equals("node:test"));
            assertFalse(key.equals(new Object()));
        }

        @Test
        @DisplayName("equal keys have same hashCode (Set compatibility)")
        void hashCode_GivenEqualKeys_ReturnsSameHashCode() {
            Set<LogRoutingKey> set = new HashSet<>();
            LogRoutingKey key1 = LogRoutingKey.of("node", "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of("node", "profil-bela");

            set.add(key1);
            assertTrue(set.contains(key2));
            assertEquals(1, set.size()); // Only one unique entry
        }

        @Test
        @DisplayName("different keys (V2.3 scenario) coexist in HashSet")
        void hashCode_GivenDifferentModuleKeys_CoexistInSet() {
            Set<LogRoutingKey> set = new HashSet<>();
            LogRoutingKey key1 = LogRoutingKey.of("node", "profil-bela");
            LogRoutingKey key2 = LogRoutingKey.of("database", "profil-bela");

            set.add(key1);
            set.add(key2);

            // Both should coexist - this is the V2.3 multi-module guarantee
            assertEquals(2, set.size());
            assertTrue(set.contains(key1));
            assertTrue(set.contains(key2));
        }
    }

    // ------------------------ toString ------------------------

    @Nested
    @DisplayName("toString() representation")
    class ToStringTests {

        @Test
        @DisplayName("format: moduleId:profileName")
        void toString_GivenValidKey_ReturnsColonSeparatedString() {
            LogRoutingKey key = LogRoutingKey.of("node", "profil-bela");

            assertEquals("node:profil-bela", key.toString());
        }

        @Test
        @DisplayName("null module produces ':profileName'")
        void toString_GivenNullModule_ReturnsColonPrefix() {
            LogRoutingKey key = LogRoutingKey.of(null, "profil-bela");

            assertEquals(":profil-bela", key.toString());
        }

        @Test
        @DisplayName("null profile produces 'moduleId:'")
        void toString_GivenNullProfile_ReturnsColonSuffix() {
            LogRoutingKey key = LogRoutingKey.of("node", null);

            assertEquals("node:", key.toString());
        }
    }

    // ------------------------ Helper Methods ------------------------

    @Nested
    @DisplayName("hasModule(), hasProfile(), isEmpty()")
    class HelperMethodTests {

        @Test
        @DisplayName("both set → not empty")
        void isEmpty_GivenBothSet_ReturnsFalse() {
            LogRoutingKey key = LogRoutingKey.of("node", "profil-bela");
            assertTrue(key.hasModule());
            assertTrue(key.hasProfile());
            assertFalse(key.isEmpty());
        }

        @Test
        @DisplayName("only module set → not empty")
        void isEmpty_GivenOnlyModule_ReturnsFalse() {
            LogRoutingKey key = LogRoutingKey.of("node", null);
            assertTrue(key.hasModule());
            assertFalse(key.hasProfile());
            assertFalse(key.isEmpty());
        }

        @Test
        @DisplayName("only profile set → not empty")
        void isEmpty_GivenOnlyProfile_ReturnsFalse() {
            LogRoutingKey key = LogRoutingKey.of(null, "profil-bela");
            assertFalse(key.hasModule());
            assertTrue(key.hasProfile());
            assertFalse(key.isEmpty());
        }

        @Test
        @DisplayName("empty string module → hasModule returns false")
        void hasModule_GivenEmptyString_ReturnsFalse() {
            LogRoutingKey key = LogRoutingKey.of("", "profil-bela");
            assertFalse(key.hasModule());
            assertTrue(key.hasProfile());
        }

        @Test
        @DisplayName("empty string profile → hasProfile returns false")
        void hasProfile_GivenEmptyString_ReturnsFalse() {
            LogRoutingKey key = LogRoutingKey.of("node", "");
            assertTrue(key.hasModule());
            assertFalse(key.hasProfile());
        }
    }

    // ------------------------ Multi-Module Isolation Scenario ------------------------

    @Nested
    @DisplayName("V2.3 Multi-Module Isolation (Core Scenario)")
    class MultiModuleIsolationTests {

        @Test
        @DisplayName("3 modules with same profile name produce 3 distinct keys")
        void multiModule_GivenSameProfileNameAcrossModules_ProducesDistinctKeys() {
            LogRoutingKey nodeKey = LogRoutingKey.of("node", "profil-bela");
            LogRoutingKey dbKey = LogRoutingKey.of("database", "profil-bela");
            LogRoutingKey miningKey = LogRoutingKey.of("mining", "profil-bela");

            assertAll("Three distinct routing keys",
                    () -> assertNotEquals(nodeKey, dbKey),
                    () -> assertNotEquals(nodeKey, miningKey),
                    () -> assertNotEquals(dbKey, miningKey),
                    () -> assertEquals(3, Set.of(nodeKey, dbKey, miningKey).size())
            );
        }

        @Test
        @DisplayName("keys can be used as HashMap keys without collision")
        void multiModule_GivenHashMapWithSameProfileKeys_NoCollision() {
            java.util.Map<LogRoutingKey, String> map = new java.util.HashMap<>();

            map.put(LogRoutingKey.of("node", "profil-bela"), "NodeConsole");
            map.put(LogRoutingKey.of("database", "profil-bela"), "DatabaseConsole");
            map.put(LogRoutingKey.of("mining", "profil-bela"), "MiningConsole");

            assertEquals(3, map.size());
            assertEquals("NodeConsole", map.get(LogRoutingKey.of("node", "profil-bela")));
            assertEquals("DatabaseConsole", map.get(LogRoutingKey.of("database", "profil-bela")));
            assertEquals("MiningConsole", map.get(LogRoutingKey.of("mining", "profil-bela")));
        }
    }

    // ------------------------ Separator Constant ------------------------

    @Test
    @DisplayName("SEPARATOR constant is colon")
    void separatorConstant_IsColon() {
        assertEquals(":", LogRoutingKey.SEPARATOR);
    }
}
