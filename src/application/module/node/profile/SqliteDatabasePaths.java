package application.module.node.profile;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SSOT of the file-based SQLite database path/URL model.
 * <p>
 * Every place that turns a SQLite {@code DB.Url} into a directory + database file (or back),
 * derives a database-profile name from a path, or discovers {@code *.db} files must go through
 * this class so the rules live in exactly one place:
 * </p>
 * <ul>
 *   <li>the per-profile convention: {@code ./database/SQLite/<profile>/} (CWD-relative) —
 *       the same base {@link ProfileCreateDefaults#sqliteDataDir} derives from;</li>
 *   <li>the database-profile name is the <b>last folder</b> of a convention path (never a
 *       free input of the configuration UI);</li>
 *   <li>the canonical URL form is {@code jdbc:sqlite:file:<dir>/<file>} (the {@code file:}
 *       prefix and a {@code ?query} suffix are tolerated on input and dropped on output); the
 *       final non-empty path segment is the configured database file, while a directory-only URL
 *       ends with {@code /}; configured database files must have the {@code .db} extension;</li>
 *   <li>discovered database files are the {@code *.db} regular files of a directory;</li>
 *   <li>the database assignment of a node profile is its own {@code DB.Url} property —
 *       derived via {@link #assignmentProfileName}, never stored separately; a database
 *       profile's {@code profile.json} stores engine settings only (a stored
 *       {@code DB.Url} in it is ignored — the URL is derived).</li>
 * </ul>
 * <p>
 * Headless and defensive: no method throws for missing/unreadable input — the discovery
 * methods return empty lists and the URL splitters return {@code null}.
 * </p>
 */
public final class SqliteDatabasePaths {

    /** The extension of the discoverable SQLite database files (case-insensitive). */
    private static final String DB_FILE_EXTENSION = ".db";

    /**
     * The conventional database file name of a per-profile database
     * ({@code signum.sqlite.db}) — the active file of a profile whose directory
     * holds no {@code *.db} file yet (the database is created lazily).
     */
    public static final String DEFAULT_DATABASE_FILE_NAME = "signum.sqlite.db";

    /** The normalized (slash-separated, {@code ./}-free) convention base: {@code database/SQLite}. */
    private static final String NORMALIZED_BASE = "database/SQLite";

    private SqliteDatabasePaths() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The per-profile SQLite database convention base: {@code ./database/SQLite}
     * (CWD-relative — the same base every other node-profile path uses).
     */
    public static Path conventionBase() {
        return Path.of("database", "SQLite");
    }

    /**
     * The data directory of the named database profile:
     * {@code ./database/SQLite/<profile>}.
     */
    public static Path profileDir(String profileName) {
        return conventionBase().resolve(profileName);
    }

    /**
     * Normalizes a directory path to its canonical relative form: backslashes become
     * forward slashes, a leading {@code ./} is dropped and trailing slashes are removed.
     * (Absolute paths keep their form — only their separators are normalized.)
     */
    public static String normalize(String dir) {
        if (dir == null) {
            return "";
        }
        String s = dir.replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * The database-profile name the given directory belongs to under the per-profile
     * convention, or {@code null} when the directory does not follow it:
     * <ul>
     *   <li>{@code ./database/SQLite/mainnet} → {@code "mainnet"} (the last folder);</li>
     *   <li>{@code ./database/SQLite} (the base itself) → {@code null} (no profile folder);</li>
     *   <li>deeper paths ({@code database/SQLite/a/b}) and non-convention paths → {@code null}.</li>
     * </ul>
     */
    public static String deriveProfileName(Path dir) {
        if (dir == null) {
            return null;
        }
        String normalized = normalize(dir.toString());
        String prefix = NORMALIZED_BASE + "/";
        if (!normalized.startsWith(prefix)) {
            return null;
        }
        String rest = normalized.substring(prefix.length());
        if (rest.isEmpty() || rest.indexOf('/') >= 0) {
            return null;
        }
        return rest;
    }

    /**
     * A SQLite JDBC URL split into the directory the database file lives in and the file
     * name itself (the file name is {@code ""} for a directory-only URL ending in {@code /}).
     */
    public record SqliteUrlParts(Path dir, String fileName) {
    }

    /**
     * Splits a SQLite JDBC URL into its directory and database file name. Tolerates the
     * {@code file:} prefix and a {@code ?query} suffix (both dropped); a non-SQLite URL
     * or a malformed input yields {@code null}.
     * <p>
     * The final non-empty path segment is the configured database file; callers validate its
     * extension with {@link #isDbFile(String)}. A directory-only URL is identified by its
     * trailing slash (optionally before a query
     * suffix), e.g. {@code jdbc:sqlite:file:./database/SQLite/mainnet/?mode=ro}. A URL without
     * a path separator (e.g. {@code jdbc:sqlite:signum.sqlite}) is a file in the working
     * directory: {@code dir = "."}.
     * </p>
     */
    public static SqliteUrlParts splitUrl(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return null;
        }
        String pathStr = url.substring("jdbc:sqlite:".length());
        if (pathStr.startsWith("file:")) {
            pathStr = pathStr.substring(5);
        }
        int paramIdx = pathStr.indexOf('?');
        if (paramIdx != -1) {
            pathStr = pathStr.substring(0, paramIdx);
        }
        String normalized = pathStr.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String dir;
        String file;
        if (normalized.isEmpty() || normalized.endsWith("/")) {
            // A trailing slash is the unambiguous directory-only form.
            dir = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
            file = "";
        } else if (idx < 0) {
            // No separator: a file in the working directory.
            dir = ".";
            file = normalized;
        } else {
            // Keep the configured file name intact so callers can report invalid extensions.
            dir = normalized.substring(0, idx);
            file = normalized.substring(idx + 1);
        }
        if (dir.isEmpty()) {
            dir = idx == 0 ? "/" : ".";
        }
        try {
            return new SqliteUrlParts(Path.of(dir), file);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Composes the canonical SQLite JDBC URL for the given directory and database file:
     * {@code jdbc:sqlite:file:<dir>/<file>}. A relative directory is rendered
     * {@code ./}-prefixed (an absolute one is kept as-is); an empty/blank file name yields
     * the trailing-slash directory-only form ({@code jdbc:sqlite:file:<dir>/}).
     */
    public static String composeUrl(Path dir, String fileName) {
        String dirStr = normalize(dir.toString());
        if (dirStr.isEmpty()) {
            dirStr = ".";
        }
        if (!dirStr.startsWith(".") && !dir.isAbsolute()) {
            dirStr = "./" + dirStr;
        }
        String file = fileName == null ? "" : fileName.trim();
        return file.isEmpty()
                ? "jdbc:sqlite:file:" + dirStr + "/"
                : "jdbc:sqlite:file:" + dirStr + "/" + file;
    }

    /**
     * The discoverable ({@code *.db}) database file names of the given directory, sorted
     * case-insensitively. A missing, unreadable or non-directory path yields an empty list
     * (never throws); directory entries named {@code *.db} are not database files.
     */
    public static List<String> discoverDbFiles(Path dir) {
        List<String> names = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && isDbFile(p.getFileName().toString())) {
                    names.add(p.getFileName().toString());
                }
            }
        } catch (Exception e) {
            // Missing/unreadable directory: nothing is discoverable there.
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Whether the value is a valid SQLite database file name
     * ({@code *.db}, case-insensitive).
     */
    public static boolean isDbFile(String fileName) {
        return fileName != null
                && !fileName.isBlank()
                && fileName.indexOf('/') < 0
                && fileName.indexOf('\\') < 0
                && fileName.toLowerCase(Locale.ROOT).endsWith(DB_FILE_EXTENSION);
    }

    /**
     * The database-profile name the given node-profile {@code DB.Url} is assigned to —
     * derived live from the URL itself (the node profile's property is the SSOT of the
     * database assignment; there is no separate assignment store):
     * <ul>
     *   <li>a convention URL naming a database file in
     *       {@code ./database/SQLite/<profile>} → {@code <profile>};</li>
     *   <li>an absent/blank URL (the node runs its default database), a non-SQLite URL,
     *       a malformed URL, a URL naming no {@code *.db} file, or a URL outside the
     *       per-profile convention (a custom path) → {@code null}.</li>
     * </ul>
     * Callers that must distinguish "no URL" from a custom URL check the URL's blankness
     * themselves.
     */
    public static String assignmentProfileName(String dbUrl) {
        if (dbUrl == null || dbUrl.isBlank()) {
            return null;
        }
        SqliteUrlParts parts = splitUrl(dbUrl);
        if (parts == null || !isDbFile(parts.fileName())) {
            return null;
        }
        return deriveProfileName(parts.dir());
    }

    /**
     * Whether the given SQLite JDBC URL points at a database of the named database profile
     * (a file inside {@code ./database/SQLite/<profile>}, or the profile directory itself
     * when the URL has the directory-only trailing slash form).
     * Non-SQLite or malformed URLs match nothing.
     */
    public static boolean matchesProfile(String profileName, String dbUrl) {
        if (profileName == null || profileName.isEmpty()) {
            return false;
        }
        SqliteUrlParts parts = splitUrl(dbUrl);
        if (parts == null) {
            return false;
        }
        return profileName.equals(deriveProfileName(parts.dir()));
    }
}
