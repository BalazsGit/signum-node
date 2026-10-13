package application.module.node.profile;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
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
 *       prefix and a {@code ?query} suffix are tolerated on input and dropped on output);</li>
 *   <li>discovered database files are the {@code *.db} regular files of a directory.</li>
 * </ul>
 * <p>
 * Headless and defensive: no method throws for missing/unreadable input — the discovery
 * methods return empty lists and the URL splitters return {@code null}.
 * </p>
 */
public final class SqliteDatabasePaths {

    /** The extension of the discoverable SQLite database files (case-insensitive). */
    private static final String DB_FILE_EXTENSION = ".db";

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
     * name itself (the file name is {@code ""} for a directory-only URL).
     */
    public record SqliteUrlParts(Path dir, String fileName) {
    }

    /**
     * Splits a SQLite JDBC URL into its directory and database file name. Tolerates the
     * {@code file:} prefix and a {@code ?query} suffix (both dropped); a non-SQLite URL
     * or a malformed input yields {@code null}.
     * <p>
     * The last path segment is a database file only when it is a {@code *.db} file — so a
     * directory-only URL (optionally with a query suffix, e.g.
     * {@code jdbc:sqlite:file:./database/SQLite/mainnet?mode=ro}) yields the whole directory
     * and an empty file name. A URL without a path separator (e.g.
     * {@code jdbc:sqlite:signum.sqlite.db}) is a {@code *.db} file in the working directory:
     * {@code dir = "."}.
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
        if (idx < 0) {
            // No separator: a file in the working directory.
            dir = ".";
            file = normalized;
        } else if (isDbFile(normalized.substring(idx + 1))) {
            // The last segment names a database file: split it off the directory.
            dir = normalized.substring(0, idx);
            file = normalized.substring(idx + 1);
        } else {
            // A directory-only URL (or a non-db file name): everything is the directory.
            dir = normalized;
            file = "";
        }
        if (dir.isEmpty()) {
            dir = ".";
        }
        return new SqliteUrlParts(Path.of(dir), file);
    }

    /**
     * Composes the canonical SQLite JDBC URL for the given directory and database file:
     * {@code jdbc:sqlite:file:<dir>/<file>}. A relative directory is rendered
     * {@code ./}-prefixed (an absolute one is kept as-is); an empty/blank file name yields
     * the directory-only form ({@code jdbc:sqlite:file:<dir>}).
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
                ? "jdbc:sqlite:file:" + dirStr
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

    /** Whether the file name is a discoverable database file ({@code *.db}, case-insensitive). */
    public static boolean isDbFile(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(DB_FILE_EXTENSION);
    }

    /**
     * Whether the given SQLite JDBC URL points at a database of the named database profile
     * (a file inside {@code ./database/SQLite/<profile>}, or the profile directory itself).
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
