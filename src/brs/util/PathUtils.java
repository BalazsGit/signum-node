package brs.util;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathUtils {

    private PathUtils() {
    } // never

    /**
     * Resolves a path string, handling both absolute and relative paths.
     * Relative paths are resolved based on the application's execution location
     * (JAR directory or current working directory).
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
            // Resolve relative to the application's location
            File codeSourceFile = new File(PathUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path applicationBasePath = codeSourceFile.isFile()
                    && codeSourceFile.getName().toLowerCase().endsWith(".jar") ? codeSourceFile.getParentFile().toPath()
                            : Paths.get(".").toAbsolutePath().normalize();
            return applicationBasePath.resolve(pathStr).normalize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve path: " + pathStr, e);
        }
    }
}