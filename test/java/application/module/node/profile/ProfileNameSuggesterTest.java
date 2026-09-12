package application.module.node.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProfileNameSuggester} (SSOT name base + {@code _01/_02/...} suffixing).
 */
@DisplayName("ProfileNameSuggester Tests")
class ProfileNameSuggesterTest {

    @Test
    @DisplayName("mainnet + mariadb + default port -> mainnet-mariadb")
    void baseName_MainnetMariaDb_DefaultPort() {
        assertEquals("mainnet-mariadb", ProfileNameSuggester.baseName(false, "mariaDb", false, 8125));
    }

    @Test
    @DisplayName("testnet + postgres + non-default port -> testnet-postgres-27101")
    void baseName_TestnetPostgres_NonDefaultPort() {
        assertEquals("testnet-postgres-27101", ProfileNameSuggester.baseName(true, "postgresql", true, 27101));
    }

    @Test
    @DisplayName("sqlite engine -> no engine suffix")
    void baseName_Sqlite_NoEngineSuffix() {
        assertEquals("mainnet", ProfileNameSuggester.baseName(false, "sqlite", false, 8125));
    }

    @Test
    @DisplayName("nextAvailable returns the base when it is free")
    void nextAvailable_BaseFree() {
        assertEquals("mainnet-mariadb",
                ProfileNameSuggester.nextAvailableName("mainnet-mariadb", Set.of("other")));
    }

    @Test
    @DisplayName("nextAvailable appends _01 when the base is taken")
    void nextAvailable_BaseTaken_AppendsSuffix() {
        assertEquals("mainnet-mariadb_01",
                ProfileNameSuggester.nextAvailableName("mainnet-mariadb", Set.of("mainnet-mariadb")));
    }

    @Test
    @DisplayName("nextAvailable increments to _02 when _01 is also taken")
    void nextAvailable_SuffixTaken_Increments() {
        assertEquals("mainnet-mariadb_02",
                ProfileNameSuggester.nextAvailableName("mainnet-mariadb",
                        Set.of("mainnet-mariadb", "mainnet-mariadb_01")));
    }

    @Test
    @DisplayName("nextAvailable never returns a reserved name")
    void nextAvailable_NeverReserved() {
        assertEquals("node-default_01",
                ProfileNameSuggester.nextAvailableName("node-default", Set.of()));
    }

    @Test
    @DisplayName("deriveProfilePorts returns 3 distinct, valid, name-stable ports (A1)")
    void deriveProfilePorts_DistinctValidAndStable() {
        int[] a = ProfileNameSuggester.deriveProfilePorts("alpha");
        int[] b = ProfileNameSuggester.deriveProfilePorts("beta");
        assertEquals(3, a.length);
        // 3 distinct ports within a profile
        assertTrue(a[0] != a[1] && a[1] != a[2] && a[0] != a[2]);
        // all in valid TCP range and clear of the shared-default band (8123/8125/8126)
        for (int p : a) {
            assertTrue(p >= 1024 && p <= 65535, "port must be a valid TCP port: " + p);
            assertTrue(p >= 9000, "derived port should avoid the shared-default band: " + p);
        }
        // deterministic / name-stable
        assertEquals(ProfileNameSuggester.deriveProfilePorts("alpha")[0], a[0]);
        // different names -> different ports (no collision)
        assertNotEquals(a[0], b[0], "two different profiles must not share an API port");
    }
}