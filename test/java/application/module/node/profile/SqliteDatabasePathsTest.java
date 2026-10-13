package application.module.node.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for {@link SqliteDatabasePaths} — the SSOT of the SQLite
 * database path/URL model (convention base, profile-name derivation,
 * URL split/compose, {@code *.db} discovery).
 */
@DisplayName("SqliteDatabasePaths (SQLite path/URL SSOT)")
class SqliteDatabasePathsTest {

    @Test
    @DisplayName("convention base and profile dir")
    void conventionBaseAndProfileDir() {
        assertEquals("database/SQLite", SqliteDatabasePaths.normalize(SqliteDatabasePaths.conventionBase().toString()));
        assertEquals(
                "database/SQLite/my-node",
                SqliteDatabasePaths.normalize(SqliteDatabasePaths.profileDir("my-node").toString()));
    }

    @Test
    @DisplayName("normalize: backslashes, leading ./ and trailing slashes")
    void normalize() {
        assertEquals("database/SQLite/mainnet", SqliteDatabasePaths.normalize("./database/SQLite/mainnet/"));
        assertEquals("database/SQLite/mainnet", SqliteDatabasePaths.normalize("database\\SQLite\\mainnet"));
        assertEquals("C:/data", SqliteDatabasePaths.normalize("C:\\data\\"));
        assertEquals("", SqliteDatabasePaths.normalize(null));
    }

    @Test
    @DisplayName("deriveProfileName: the last folder of a convention path")
    void deriveProfileNameConvention() {
        assertEquals("mainnet", SqliteDatabasePaths.deriveProfileName(Path.of("database", "SQLite", "mainnet")));
        assertEquals("mainnet", SqliteDatabasePaths.deriveProfileName(Path.of("./database/SQLite/mainnet/")));
    }

    @Test
    @DisplayName("deriveProfileName: the base itself, deeper paths and foreign paths yield null")
    void deriveProfileNameNegative() {
        assertNull(SqliteDatabasePaths.deriveProfileName(Path.of("database", "SQLite")));
        assertNull(SqliteDatabasePaths.deriveProfileName(Path.of("database", "SQLite", "a", "b")));
        assertNull(SqliteDatabasePaths.deriveProfileName(Path.of("D:\\data\\db")));
        assertNull(SqliteDatabasePaths.deriveProfileName(null));
    }

    @Test
    @DisplayName("splitUrl: the canonical file: form with dir and file")
    void splitUrlCanonical() {
        SqliteDatabasePaths.SqliteUrlParts parts =
                SqliteDatabasePaths.splitUrl("jdbc:sqlite:file:./database/SQLite/mainnet/signum.sqlite.db");
        assertEquals("database/SQLite/mainnet", SqliteDatabasePaths.normalize(parts.dir().toString()));
        assertEquals("signum.sqlite.db", parts.fileName());
    }

    @Test
    @DisplayName("splitUrl: tolerates a missing file: prefix and a ?query suffix")
    void splitUrlTolerant() {
        SqliteDatabasePaths.SqliteUrlParts plain =
                SqliteDatabasePaths.splitUrl("jdbc:sqlite:./database/SQLite/mainnet/signum.sqlite.db");
        assertEquals("signum.sqlite.db", plain.fileName());

        SqliteDatabasePaths.SqliteUrlParts withFile =
                SqliteDatabasePaths.splitUrl("jdbc:sqlite:file:./database/SQLite/mainnet/signum.sqlite.db?mode=ro");
        assertEquals("signum.sqlite.db", withFile.fileName());

        SqliteDatabasePaths.SqliteUrlParts dirOnly =
                SqliteDatabasePaths.splitUrl("jdbc:sqlite:file:./database/SQLite/mainnet?mode=ro");
        assertEquals("database/SQLite/mainnet", SqliteDatabasePaths.normalize(dirOnly.dir().toString()));
        assertEquals("", dirOnly.fileName());
    }

    @Test
    @DisplayName("splitUrl: a separator-free URL is a file in the working directory")
    void splitUrlSeparatorFree() {
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl("jdbc:sqlite:signum.sqlite.db");
        assertEquals(".", SqliteDatabasePaths.normalize(parts.dir().toString()));
        assertEquals("signum.sqlite.db", parts.fileName());
    }

    @Test
    @DisplayName("splitUrl: non-SQLite and null URLs yield null")
    void splitUrlNegative() {
        assertNull(SqliteDatabasePaths.splitUrl("jdbc:mariadb://localhost/signum"));
        assertNull(SqliteDatabasePaths.splitUrl(null));
    }

    @Test
    @DisplayName("composeUrl: the canonical form for a relative dir, plus the directory-only form")
    void composeUrl() {
        assertEquals("jdbc:sqlite:file:./database/SQLite/mainnet/signum.sqlite.db",
                SqliteDatabasePaths.composeUrl(Path.of("database", "SQLite", "mainnet"), "signum.sqlite.db"));
        assertEquals("jdbc:sqlite:file:./database/SQLite/mainnet",
                SqliteDatabasePaths.composeUrl(Path.of("database", "SQLite", "mainnet"), ""));
        assertEquals("jdbc:sqlite:file:./database/SQLite/mainnet",
                SqliteDatabasePaths.composeUrl(Path.of("database", "SQLite", "mainnet"), null));
    }

    @Test
    @DisplayName("composeUrl/splitUrl: a round trip keeps dir and file")
    void composeSplitRoundTrip() {
        String url = SqliteDatabasePaths.composeUrl(Path.of("database", "SQLite", "mainnet"), "signum.sqlite.db");
        SqliteDatabasePaths.SqliteUrlParts parts = SqliteDatabasePaths.splitUrl(url);
        assertEquals("database/SQLite/mainnet", SqliteDatabasePaths.normalize(parts.dir().toString()));
        assertEquals("signum.sqlite.db", parts.fileName());
    }

    @Test
    @DisplayName("discoverDbFiles: lists *.db regular files, case-insensitively sorted")
    void discoverDbFiles(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("b.db"));
        Files.createFile(dir.resolve("A.DB"));
        Files.createFile(dir.resolve("notes.txt"));
        Files.createDirectory(dir.resolve("sub.db"));

        assertEquals(List.of("A.DB", "b.db"), SqliteDatabasePaths.discoverDbFiles(dir));
    }

    @Test
    @DisplayName("discoverDbFiles: missing or non-directory paths yield an empty list")
    void discoverDbFilesNegative(@TempDir Path dir) throws Exception {
        assertTrue(SqliteDatabasePaths.discoverDbFiles(dir.resolve("does-not-exist")).isEmpty());
        assertTrue(SqliteDatabasePaths.discoverDbFiles(Files.createFile(dir.resolve("a.file"))).isEmpty());
        assertTrue(SqliteDatabasePaths.discoverDbFiles(null).isEmpty());
    }

    @Test
    @DisplayName("matchesProfile: a file of the profile dir matches, others do not")
    void matchesProfile() {
        assertTrue(SqliteDatabasePaths.matchesProfile("mainnet",
                "jdbc:sqlite:file:./database/SQLite/mainnet/signum.sqlite.db"));
        assertTrue(SqliteDatabasePaths.matchesProfile("mainnet",
                "jdbc:sqlite:file:./database/SQLite/mainnet"));
        assertFalse(SqliteDatabasePaths.matchesProfile("mainnet",
                "jdbc:sqlite:file:./database/SQLite/other/signum.sqlite.db"));
        assertFalse(SqliteDatabasePaths.matchesProfile("mainnet",
                "jdbc:sqlite:file:D:/data/signum.sqlite.db"));
        assertFalse(SqliteDatabasePaths.matchesProfile("mainnet",
                "jdbc:mariadb://localhost/signum"));
        assertFalse(SqliteDatabasePaths.matchesProfile(null,
                "jdbc:sqlite:file:./database/SQLite/mainnet/signum.sqlite.db"));
    }

    @Test
    @DisplayName("isDbFile: extension check is case-insensitive")
    void isDbFile() {
        assertTrue(SqliteDatabasePaths.isDbFile("signum.sqlite.db"));
        assertTrue(SqliteDatabasePaths.isDbFile("SIGNUM.DB"));
        assertFalse(SqliteDatabasePaths.isDbFile("signum.sqlite"));
        assertFalse(SqliteDatabasePaths.isDbFile(null));
    }
}