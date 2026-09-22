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
    @DisplayName("nextCloneName: non-clone source -> <source>_clone")
    void nextCloneName_NonCloneSource() {
        assertEquals("node_clone",
                ProfileNameSuggester.nextCloneName("node", Set.of("node")));
    }

    @Test
    @DisplayName("nextCloneName: <source>_clone taken -> <source>_clone_01 (fallback scheme)")
    void nextCloneName_NonCloneSourceBaseTaken() {
        assertEquals("node_clone_01",
                ProfileNameSuggester.nextCloneName("node", Set.of("node", "node_clone")));
    }

    @Test
    @DisplayName("nextCloneName: bare _clone source -> _clone2 (bare counts as #1)")
    void nextCloneName_BareCloneSource() {
        assertEquals("node_clone2",
                ProfileNameSuggester.nextCloneName("node_clone", Set.of("node", "node_clone")));
    }

    @Test
    @DisplayName("nextCloneName: continues the _cloneNN sequence from the highest taken number")
    void nextCloneName_ContinuesNumberedSequence() {
        assertEquals("node_clone4",
                ProfileNameSuggester.nextCloneName("node_clone2",
                        Set.of("node", "node_clone", "node_clone2", "node_clone3")));
    }

    @Test
    @DisplayName("nextCloneName: discovers the max across all scheme-conforming names (gaps kept)")
    void nextCloneName_UsesMaxNotCount() {
        // bare _clone = #1, node_clone2 = #2, node_clone5 = #5 -> next is #6 (no gap filling)
        assertEquals("node_clone6",
                ProfileNameSuggester.nextCloneName("node_clone5",
                        Set.of("node_clone", "node_clone2", "node_clone5")));
    }

    @Test
    @DisplayName("nextCloneName: unrelated profiles with other prefixes are ignored")
    void nextCloneName_IgnoresOtherPrefixes() {
        assertEquals("node_clone2",
                ProfileNameSuggester.nextCloneName("node_clone",
                        Set.of("node_clone", "other_clone9", "node_clone_other")));
    }

    @Test
    @DisplayName("nextCloneName: increments past already-taken candidates")
    void nextCloneName_SkipsTakenCandidates() {
        assertEquals("node_clone6",
                ProfileNameSuggester.nextCloneName("node_clone",
                        Set.of("node_clone", "node_clone2", "node_clone3", "node_clone4", "node_clone5")));
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