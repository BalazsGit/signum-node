package application.utils.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility for resolving file paths relative to the application's installation root.
 *
 * <h3>How it works</h3>
 * When running from a JAR file ( {@code java -jar signum-node.jar} ), the JAR file's
 * parent directory is used as the application root. All relative paths are then
 * resolved against this root, ensuring consistent behavior regardless of the
 * current working directory.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * // If JAR is at: /opt/signum/signum-node.jar
 * PathUtils.resolvePath("./conf")    -> /opt/signum/conf
 * PathUtils.resolvePath("./database") -> /opt/signum/database
 * PathUtils.resolvePath("/etc/foo")   -> /etc/foo (absolute, unchanged)
 * }</pre>
 *
 * <h3>Fallback</h3>
 * If the JAR location cannot be determined (e.g., running from an IDE classpath),
 * the current working directory is used as a fallback.
 */
public final class PathUtils {

    private static final Logger logger = LoggerFactory.getLogger(PathUtils.class);

    /**
     * Cached application root directory. Determined once at first call and reused.
     */
    private static volatile Path applicationRoot;

    private PathUtils() {
        // utility class — never instantiated
    }

    /**
     * Resolves a path string, handling both absolute and relative paths.
     * Relative paths are resolved based on the application's installation root
     * (the directory containing the JAR file).
     *
     * @param pathStr The path string to resolve.
     * @return The resolved, normalized {@link Path}.
     * @throws RuntimeException if path resolution fails.
     */
    public static Path resolvePath(String pathStr) {
        try {
            Path configuredAsPath = Paths.get(pathStr);
            if (configuredAsPath.isAbsolute()) {
                return configuredAsPath;
            }
            return getApplicationRoot().resolve(pathStr).normalize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve path: " + pathStr, e);
        }
    }

    /**
     * Returns the application root directory.
     *
     * <p>Detection order:</p>
     * <ol>
     *   <li>JAR file location: If running from a JAR file, the parent directory
     *       of that JAR is the root.</li>
     *   <li>Fallback: Current working directory (for IDE/debug runs).</li>
     * </ol>
     *
     * @return The application root {@link Path}.
     */
    public static Path getApplicationRoot() {
        if (applicationRoot == null) {
            synchronized (PathUtils.class) {
                if (applicationRoot == null) {
                    applicationRoot = detectApplicationRoot();
                    logger.info("Application root detected: {}", applicationRoot.toAbsolutePath().normalize());
                }
            }
        }
        return applicationRoot;
    }

    /**
     * Detects the application root directory.
     *
     * @return The detected application root path.
     */
    private static Path detectApplicationRoot() {
        // Strategy 1: Use CodeSource to find JAR file location
        try {
            URL jarUrl = PathUtils.class.getProtectionDomain().getCodeSource().getLocation();
            if (jarUrl != null) {
                Path jarPath = Paths.get(jarUrl.toURI());

                // If it's a regular file (JAR), use its parent directory
                if (Files.isRegularFile(jarPath)) {
                    Path parent = jarPath.getParent();
                    if (parent != null) {
                        logger.debug("Application root from JAR location: {}", parent.toAbsolutePath());
                        return parent;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not detect application root from CodeSource: {}", e.getMessage());
        }

        // Fallback: Use current working directory
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        logger.warn("Using CWD as application root fallback: {}", cwd);
        return cwd;
    }
}