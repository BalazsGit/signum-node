package application.utils.config;

import application.utils.io.PathUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LOGGING layout SSOT (consistent logging layout requirement): all paths
 * are RELATIVE references resolved against the application root
 * ({@link PathUtils}), the generated files live under
 * {@code conf/{system,node}/logging/runtime/}, and the log files live
 * OUTSIDE the configuration tree ({@code logs/node}, {@code logs/system}).
 * Includes the idempotent legacy-layout migration
 * ({@code conf/logging-runtime -> conf/system/logging/runtime},
 * {@code conf/logs -> logs}).
 */
@DisplayName("LoggingPaths (logging layout SSOT + migration)")
class LoggingPathsTest {

    @TempDir
    Path appRoot;

    // ── SSOT path assertions ────────────────────────────────────────────

    @Test
    @DisplayName("generated files: conf/system/logging/runtime (global) + conf/node/logging/runtime (node)")
    void generatedFileLayout() {
        String conf = "conf";
        Path confRoot = PathUtils.resolvePath(conf);

        assertEquals(confRoot.resolve("system").resolve("logging").resolve("runtime")
                        .resolve("global.log4j2.properties"),
                LoggingPaths.globalGeneratedFile(conf),
                "the generated global config lives under conf/system/logging/runtime (consistent module layout)");
        assertEquals(confRoot.resolve("node").resolve("logging").resolve("runtime")
                        .resolve("mainnet.log4j2.properties"),
                LoggingPaths.nodeGeneratedFile(conf, "mainnet"),
                "the generated node config lives under conf/node/logging/runtime");
    }

    @Test
    @DisplayName("system layer files: conf/system/logging/default.properties + system-default.properties")
    void systemLayerLayout() {
        String conf = "conf";
        Path confRoot = PathUtils.resolvePath(conf);

        assertEquals(confRoot.resolve("system").resolve("logging")
                        .resolve(ReservedNames.DEFAULT_PROFILE_NAME + ".properties"),
                LoggingPaths.systemL1File(conf));
        assertEquals(confRoot.resolve("system").resolve("logging")
                        .resolve(ReservedNames.SYSTEM_DEFAULT_LOGGING_NAME + ".properties"),
                LoggingPaths.systemTemplateFile(conf));
        assertEquals("system-default.properties", LoggingPaths.SYSTEM_TEMPLATE_FILE_NAME);
    }

    @Test
    @DisplayName("log dirs are OUTSIDE conf, relative (portable), under the application root")
    void logDirsOutsideConf() {
        assertFalse(LoggingPaths.NODE_LOG_DIR_RELATIVE.startsWith("/"),
                "the node log dir reference must be relative: " + LoggingPaths.NODE_LOG_DIR_RELATIVE);
        assertFalse(LoggingPaths.SYSTEM_LOG_DIR_RELATIVE.startsWith("/"),
                "the system log dir reference must be relative: " + LoggingPaths.SYSTEM_LOG_DIR_RELATIVE);
        assertEquals(PathUtils.getApplicationRoot().resolve("logs").resolve("node"),
                LoggingPaths.nodeLogDir(),
                "logs/node resolves against the APPLICATION ROOT (portable)");
        assertEquals(PathUtils.getApplicationRoot().resolve("logs").resolve("system"),
                LoggingPaths.systemLogDir(),
                "logs/system resolves against the APPLICATION ROOT (portable)");
    }

    @Test
    @DisplayName("SSOT constants: ModuleIds.SYSTEM + CATEGORY_LOGGING_RUNTIME + reserved system-default")
    void ssotConstants() {
        assertEquals("system", ModuleIds.SYSTEM);
        assertEquals("runtime", ModuleIds.CATEGORY_LOGGING_RUNTIME);
        assertTrue(ReservedNames.isLoggingReservedName("system-default"),
                "system-default must be a reserved logging profile name");
        assertFalse(ReservedNames.isLoggingReservedName("my-profile"));
    }

    @Test
    @DisplayName("the system classpath resources (L1 seed + template) are present")
    void classpathResourcesExist() {
        assertFalse(LoggingPaths.class.getResource(LoggingPaths.SYSTEM_L1_RESOURCE) == null,
                "the system L1 seed resource must exist on the classpath: "
                        + LoggingPaths.SYSTEM_L1_RESOURCE);
        assertFalse(LoggingPaths.class.getResource(LoggingPaths.SYSTEM_TEMPLATE_RESOURCE) == null,
                "the system template resource must exist on the classpath: "
                        + LoggingPaths.SYSTEM_TEMPLATE_RESOURCE);
    }

    // ── migration ───────────────────────────────────────────────────────

    @Test
    @DisplayName("migration: conf/logging-runtime -> conf/system/logging/runtime, conf/logs -> logs")
    void migrationMovesLegacyLayout() throws Exception {
        // legacy state
        Path legacyGlobal = appRoot.resolve("conf").resolve("logging-runtime")
                .resolve("global.log4j2.properties");
        Files.createDirectories(legacyGlobal.getParent());
        Files.writeString(legacyGlobal, "legacy global config");
        Path legacyNodeLog = appRoot.resolve("conf").resolve("logs").resolve("node")
                .resolve("mainnet.log");
        Files.createDirectories(legacyNodeLog.getParent());
        Files.writeString(legacyNodeLog, "legacy node log");
        Path legacySystemLog = appRoot.resolve("conf").resolve("logs").resolve("system")
                .resolve("system.log");
        Files.createDirectories(legacySystemLog.getParent());
        Files.writeString(legacySystemLog, "legacy system log");

        java.util.List<String> performed = LoggingPaths.migrateLegacyLayout(appRoot);

        assertEquals(2, performed.size(), "both legacy directories must be migrated: " + performed);
        // new locations
        Path movedGlobal = appRoot.resolve("conf").resolve("system").resolve("logging")
                .resolve("runtime").resolve("global.log4j2.properties");
        assertTrue(Files.exists(movedGlobal),
                "the global config must move to conf/system/logging/runtime");
        assertEquals("legacy global config", Files.readString(movedGlobal));
        assertTrue(Files.exists(appRoot.resolve("logs").resolve("node").resolve("mainnet.log")),
                "node logs must move OUTSIDE conf to logs/node");
        assertTrue(Files.exists(appRoot.resolve("logs").resolve("system").resolve("system.log")),
                "system logs must move OUTSIDE conf to logs/system");
        // legacy locations gone
        assertFalse(Files.exists(appRoot.resolve("conf").resolve("logging-runtime")),
                "the legacy conf/logging-runtime directory must be removed");
        assertFalse(Files.exists(appRoot.resolve("conf").resolve("logs")),
                "the legacy conf/logs directory must be removed");
    }

    @Test
    @DisplayName("migration is idempotent and never clobbers existing targets")
    void migrationIsIdempotentAndSafe() throws Exception {
        Path legacyGlobal = appRoot.resolve("conf").resolve("logging-runtime")
                .resolve("global.log4j2.properties");
        Files.createDirectories(legacyGlobal.getParent());
        Files.writeString(legacyGlobal, "legacy");
        // a NEWER file already lives at the target: it must survive
        Path target = appRoot.resolve("conf").resolve("system").resolve("logging").resolve("runtime")
                .resolve("global.log4j2.properties");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "newer");

        LoggingPaths.migrateLegacyLayout(appRoot);
        assertEquals("newer", Files.readString(target),
                "an existing target file must NEVER be clobbered by the migration");
        // second run: nothing to do
        assertEquals(0, LoggingPaths.migrateLegacyLayout(appRoot).size(),
                "a second migration must be a no-op");
        assertEquals("newer", Files.readString(target));
    }

    @Test
    @DisplayName("migration without any legacy layout is a no-op (never throws)")
    void migrationNoLegacy_noop() {
        assertTrue(LoggingPaths.migrateLegacyLayout(appRoot).isEmpty());
        assertTrue(LoggingPaths.migrateLegacyLayout(null).isEmpty(), "null appRoot must be safe");
    }
}