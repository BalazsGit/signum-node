package application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard against reintroducing JUnit 4 into the test tree.
 * <p>
 * The codebase was migrated to JUnit 5 (Jupiter) 6.1.3; the legacy
 * {@code junit:junit} / {@code junit-vintage-engine} dependencies were removed
 * from {@code build.gradle}. Any file that imports a JUnit 4 API again (directly
 * or the JUnit 3 {@code junit.framework} API) fails this test instead of only
 * failing at discovery time.
 */
class TestSourceGuardTest {

    private static final Pattern JUNIT4_IMPORT = Pattern.compile(
            "^import\\s+(static\\s+)?org\\.junit\\.(Assert|Test|Before|After|BeforeClass|AfterClass"
                    + "|Ignore|Rule|runner|runners|rules|internal)([.;]|\\s*$)",
            Pattern.MULTILINE);
    private static final Pattern JUNIT3_IMPORT = Pattern.compile(
            "^import\\s+(static\\s+)?junit\\.framework\\.",
            Pattern.MULTILINE);
    private static final Pattern JUNIT4_ANNOTATION = Pattern.compile(
            "@" + "RunWith|" + "@" + "BeforeClass|" + "@" + "AfterClass|" + "@" + "Category\\(|expected\\s*=\\s*\\w+\\.class");

    @Test
    @DisplayName("no JUnit 4 (or 3) imports/annotations anywhere under test/java")
    void testTreeIsPureJupiter() throws IOException {
        Path testRoot = Path.of("test", "java");
        assertTrue(Files.isDirectory(testRoot), "test/java must exist for the guard to work");

        try (Stream<Path> paths = Files.walk(testRoot)) {
            for (Path file : (Iterable<Path>) paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList()
                    ::iterator) {
                String source = Files.readString(file);
                assertTrue(!JUNIT4_IMPORT.matcher(source).find(),
                        "JUnit 4 import found in " + file);
                assertTrue(!JUNIT3_IMPORT.matcher(source).find(),
                        "JUnit 3 (junit.framework) import found in " + file);
                assertTrue(!JUNIT4_ANNOTATION.matcher(source).find(),
                        "JUnit 4 annotation/attribute found in " + file);
            }
        }
    }
}