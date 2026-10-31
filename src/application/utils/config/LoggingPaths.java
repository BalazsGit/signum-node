package application.utils.config;

import application.utils.io.PathUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for the on-disk LOGGING layout (per-node isolated
 * LoggerContext plan §6.4, updated by the "consistent logging layout"
 * requirement).
 * <p>
 * <h3>Portability contract</h3>
 * EVERY path in this class is a <b>relative</b> reference resolved against the
 * application root (the JAR's parent directory, or the working directory in
 * IDE runs) via {@link PathUtils#resolvePath(String)} — no absolute path is
 * ever hard-coded or persisted. The whole installation (JAR + {@code conf/}
 * + {@code logs/} + {@code database/}) can be copied to another disk and run
 * immediately. The generated Log4j2 files therefore embed the relative
 * {@link #NODE_LOG_DIR_RELATIVE} directory so a copied installation keeps
 * working after a simple re-Apply / restart.
 * <p>
 * <h3>Layout</h3>
 * <pre>{@code
 * {appRoot}/
 * ├── conf/
 * │   ├── node/logging/
 * │   │   ├── default.properties                  ← node L1 (user-editable)
 * │   │   ├── logging-default.properties          ← node L0 reference template
 * │   │   ├── {profile}.properties                ← node L2 (per-profile)
 * │   │   └── runtime/
 * │   │       └── {profile}.log4j2.properties     ← GENERATED node context config
 * │   └── system/logging/
 * │       ├── default.properties                  ← system L1 (user-editable, JVM-global)
 * │       ├── system-default.properties           ← system L0 reference template
 * │       └── runtime/
 * │           └── global.log4j2.properties        ← GENERATED global context config
 * └── logs/                                        ← OUTSIDE conf/ (runtime data)
 *     ├── node/{profile}.log (+ %i.log.gz)
 *     └── system/                                  ← system-level (global) file log
 * }</pre>
 *
 * <p>Headless-safe (no Swing, no logging — the caller reports errors).</p>
 *
 * @see PathUtils
 * @see ConfigPaths
 * @see ModuleIds
 */
public final class LoggingPaths {

    /**
     * Relative (portable) node file-log directory, as embedded in the
     * generated Log4j2 node config and as the {@code node.fileLog.dir} L0
     * default. Log4j2 resolves it against the JVM working directory, which
     * the launcher keeps equal to the application root.
     */
    public static final String NODE_LOG_DIR_RELATIVE = "logs" + "/" + ModuleIds.NODE;

    /**
     * Relative (portable) system (JVM-global) file-log directory — the
     * destination of the optional system-level file appender.
     */
    public static final String SYSTEM_LOG_DIR_RELATIVE = "logs" + "/" + ModuleIds.SYSTEM;

    /** File name of the generated GLOBAL context config. */
    public static final String GLOBAL_GENERATED_FILE_NAME = "global.log4j2.properties";

    /** File name of the system L0 reference template (SSOT: {@link ReservedNames}). */
    public static final String SYSTEM_TEMPLATE_FILE_NAME =
            ReservedNames.SYSTEM_DEFAULT_LOGGING_NAME + ".properties";

    /** Classpath resource of the system L1 auto-create seed. */
    public static final String SYSTEM_L1_RESOURCE =
            ConfigPaths.CLASSPATH_PREFIX + ModuleIds.SYSTEM + "/" + ModuleIds.CATEGORY_LOGGING + "/"
                    + ReservedNames.DEFAULT_PROFILE_NAME + ".properties";

    /** Classpath resource of the system L0 reference template. */
    public static final String SYSTEM_TEMPLATE_RESOURCE =
            ConfigPaths.CLASSPATH_PREFIX + ModuleIds.SYSTEM + "/" + ModuleIds.CATEGORY_LOGGING + "/"
                    + SYSTEM_TEMPLATE_FILE_NAME;

    /** Legacy (pre-consistent-layout) generated-global directory segment, for migration. */
    private static final String LEGACY_GLOBAL_RUNTIME_DIR = "logging-runtime";

    /** Legacy (pre-consistent-layout) log directory (under conf), for migration. */
    private static final String LEGACY_LOG_DIR = "logs";

    private LoggingPaths() {
        // static utility
    }

    // ── conf paths (all relative, resolved via PathUtils) ──────────────

    /** The resolved conf root (default {@code ./conf}). */
    public static Path confRoot() {
        return PathUtils.resolvePath(ConfigPaths.RUNTIME_CONF_ROOT);
    }

    /** The system module's logging directory: {@code {conf}/system/logging}. */
    public static Path systemLoggingDir(String confRoot) {
        return PathUtils.resolvePath(confRoot).resolve(ModuleIds.SYSTEM)
                .resolve(ModuleIds.CATEGORY_LOGGING);
    }

    /** The node module's logging directory: {@code {conf}/node/logging}. */
    public static Path nodeLoggingDir(String confRoot) {
        return PathUtils.resolvePath(confRoot).resolve(ModuleIds.NODE)
                .resolve(ModuleIds.CATEGORY_LOGGING);
    }

    /** The system L1 (user-editable) layer: {@code {conf}/system/logging/default.properties}. */
    public static Path systemL1File(String confRoot) {
        return systemLoggingDir(confRoot)
                .resolve(ReservedNames.DEFAULT_PROFILE_NAME + ".properties");
    }

    /**
     * The system L0 reference template:
     * {@code {conf}/system/logging/system-default.properties} (read-only,
     * SHA-256 synced from classpath resources).
     */
    public static Path systemTemplateFile(String confRoot) {
        return systemLoggingDir(confRoot).resolve(SYSTEM_TEMPLATE_FILE_NAME);
    }

    /**
     * The GENERATED global context config:
     * {@code {conf}/system/logging/runtime/global.log4j2.properties}
     * (generated artifact — NOT the source of truth).
     */
    public static Path globalGeneratedFile(String confRoot) {
        return systemLoggingDir(confRoot).resolve(ModuleIds.CATEGORY_LOGGING_RUNTIME)
                .resolve(GLOBAL_GENERATED_FILE_NAME);
    }

    /**
     * The GENERATED node context config:
     * {@code {conf}/node/logging/runtime/{profile}.log4j2.properties}
     * (generated artifact — NOT the source of truth).
     */
    public static Path nodeGeneratedFile(String confRoot, String profileName) {
        return nodeLoggingDir(confRoot).resolve(ModuleIds.CATEGORY_LOGGING_RUNTIME)
                .resolve(profileName + ".log4j2.properties");
    }

    // ── log paths (OUTSIDE conf; relative, resolved via PathUtils) ─────

    /** The log root: {@code {appRoot}/logs} (resolved). */
    public static Path logRoot() {
        return PathUtils.resolvePath("logs");
    }

    /** The node file-log directory: {@code {appRoot}/logs/node} (resolved). */
    public static Path nodeLogDir() {
        return PathUtils.resolvePath(NODE_LOG_DIR_RELATIVE);
    }

    /** The system (global) file-log directory: {@code {appRoot}/logs/system} (resolved). */
    public static Path systemLogDir() {
        return PathUtils.resolvePath(SYSTEM_LOG_DIR_RELATIVE);
    }

    // ── bootstrap / migration ──────────────────────────────────────────

    /**
     * One-shot, idempotent migration of the LEGACY logging layout to the
     * consistent one (runs at startup, before any logging configuration is
     * applied):
     * <ol>
     *   <li>{@code {appRoot}/conf/logging-runtime/**} →
     *       {@code {appRoot}/conf/system/logging/runtime/**} (the generated
     *       global config moved next to its owning module);</li>
     *   <li>{@code {appRoot}/conf/logs/**} →
     *       {@code {appRoot}/logs/**} (log files are runtime data — they
     *       belong OUTSIDE the configuration tree).</li>
     * </ol>
     * Files that already exist at the target are kept (never clobbered); the
     * (emptied) legacy source directories are removed.
     *
     * @param appRoot the application root ({@link PathUtils#getApplicationRoot()})
     * @return the human-readable descriptions of the moves performed (empty
     *         when nothing needed migration; never null)
     */
    public static List<String> migrateLegacyLayout(Path appRoot) {
        List<String> performed = new ArrayList<>();
        if (appRoot == null) {
            return Collections.emptyList();
        }
        Path conf = appRoot.resolve("conf");
        // 1. generated global config: conf/logging-runtime -> conf/system/logging/runtime
        moveDirectoryContents(
                conf.resolve(LEGACY_GLOBAL_RUNTIME_DIR),
                conf.resolve(ModuleIds.SYSTEM).resolve(ModuleIds.CATEGORY_LOGGING)
                        .resolve(ModuleIds.CATEGORY_LOGGING_RUNTIME),
                "conf/" + LEGACY_GLOBAL_RUNTIME_DIR, "conf/system/logging/runtime", performed);
        // 2. log files: conf/logs -> logs
        moveDirectoryContents(
                conf.resolve(LEGACY_LOG_DIR), appRoot.resolve(LEGACY_LOG_DIR),
                "conf/" + LEGACY_LOG_DIR, "logs", performed);
        return performed;
    }

    /**
     * Ensures the system logging layout exists (idempotent, startup bootstrap):
     * <ul>
     *   <li>{@code conf/system/logging/default.properties} — auto-created from
     *       the classpath seed when missing, NEVER overwritten (user-owned L1,
     *       the same contract as the node L1);</li>
     *   <li>{@code conf/system/logging/system-default.properties} — SHA-256
     *       synced from the classpath reference template (read-only
     *       documentation, safe to overwrite).</li>
     * </ul>
     *
     * @param confRoot the conf root (relative, e.g. {@code conf})
     * @return true when both files exist after the call (even when nothing
     *         had to be created); false when a classpath resource is missing
     *         or unwritable (the layout then degrades gracefully — the layers
     *         simply resolve as empty)
     */
    public static boolean ensureSystemLoggingFiles(String confRoot) {
        boolean ok = true;
        if (LoggingPaths.class.getResource(SYSTEM_L1_RESOURCE) == null) {
            ok = false;
        } else {
            PropertiesProfileLoader.ensureUserDefaultFile(confRoot, ModuleIds.SYSTEM,
                    ModuleIds.CATEGORY_LOGGING,
                    ReservedNames.DEFAULT_PROFILE_NAME + ".properties", SYSTEM_L1_RESOURCE);
        }
        if (LoggingPaths.class.getResource(SYSTEM_TEMPLATE_RESOURCE) == null) {
            ok = false;
        } else {
            PropertiesProfileLoader.syncDefaultFileFromClasspath(confRoot, ModuleIds.SYSTEM,
                    ModuleIds.CATEGORY_LOGGING, SYSTEM_TEMPLATE_FILE_NAME, SYSTEM_TEMPLATE_RESOURCE);
        }
        return ok;
    }

    // ── private helpers ────────────────────────────────────────────────

    /**
     * Moves the CONTENTS of {@code source} into {@code target} (recursive;
     * existing target files are kept, never clobbered) and removes the
     * (possibly emptied) source tree. A no-op when the source does not exist.
     */
    private static void moveDirectoryContents(Path source, Path target, String sourceDesc,
                                              String targetDesc, List<String> performed) {
        if (source == null || target == null || !Files.isDirectory(source)) {
            return;
        }
        try {
            Files.createDirectories(target);
            moveRecursive(source, target);
            deleteRecursively(source);
            performed.add(sourceDesc + " -> " + targetDesc);
        } catch (IOException e) {
            // migration is best-effort: a failing move must never break startup
            // (the app simply continues with the new layout)
        }
    }

    private static void moveRecursive(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path child : stream) {
                    moveRecursive(child, target.resolve(child.getFileName()));
                }
            }
        } else if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(source);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path child : stream) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(root);
    }
}