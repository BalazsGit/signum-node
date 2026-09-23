package application.module.browser.core;

import application.module.browser.util.OsArch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JcefPathResolver} using the testable
 * {@code resolveAt(appRoot, workingDir, osArch)} core (no system state,
 * deterministic platform).
 */
class JcefPathResolverTest {

    private static final OsArch ARCH = OsArch.of("Windows 11", "amd64");

    private static Path distribution(Path root) throws IOException {
        Path dir = root.resolve("jcef").resolve(ARCH.key());
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    @DisplayName("resolves jcef/<os>-arch/ directly under the application root (fat jar layout)")
    void resolvesFromAppRoot(@TempDir Path root) throws IOException {
        Path expected = distribution(root);

        Optional<Path> resolved = JcefPathResolver.resolveAt(root, root, ARCH);

        assertEquals(expected, resolved.orElse(null));
    }

    @Test
    @DisplayName("climbs above the app root (jpackage app image: jar under app/)")
    void resolvesFromAppRootParent(@TempDir Path imageRoot) throws IOException {
        Path expected = distribution(imageRoot);
        Path appDir = imageRoot.resolve("app");
        Files.createDirectories(appDir);

        Optional<Path> resolved = JcefPathResolver.resolveAt(appDir, imageRoot, ARCH);

        assertEquals(expected, resolved.orElse(null));
    }

    @Test
    @DisplayName("resolves from the working directory (dev layout: gradlew run from project root)")
    void resolvesFromWorkingDir(@TempDir Path appRoot, @TempDir Path workDir) throws IOException {
        Path expected = distribution(workDir);

        Optional<Path> resolved = JcefPathResolver.resolveAt(appRoot, workDir, ARCH);

        assertEquals(expected, resolved.orElse(null));
    }

    @Test
    @DisplayName("the application root takes priority over the working directory")
    void appRootPreferredOverWorkingDir(@TempDir Path appRoot, @TempDir Path workDir) throws IOException {
        Path expected = distribution(appRoot);
        distribution(workDir);

        Optional<Path> resolved = JcefPathResolver.resolveAt(appRoot, workDir, ARCH);

        assertEquals(expected, resolved.orElse(null));
    }

    @Test
    @DisplayName("empty when no candidate root contains the distribution")
    void emptyWhenAbsent(@TempDir Path appRoot, @TempDir Path workDir) {
        assertTrue(JcefPathResolver.resolveAt(appRoot, workDir, ARCH).isEmpty());
        assertTrue(JcefPathResolver.resolveAt(null, workDir, ARCH).isEmpty());
    }
}
