package application.module.logging.context;

import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.utils.config.ModuleIds;
import application.utils.logging.ConsoleLimits;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates the node-context Apply pipeline (per-node isolated
 * LoggerContext plan, §9.2A) on top of the P1 building blocks:
 * <pre>
 *   resolve (L0/L1/L2, {@link NodeLoggingConfigResolver})
 *     -&gt; write the generated file ({@link NodeLoggingConfigWriter})
 *     -&gt; create-or-reconfigure the node's context
 *        ({@link NodeLoggingContextManager}/{@link NodeLoggingContext})
 * </pre>
 *
 * <h3>Two phases (plan §6.3 two-phase start)</h3>
 * <ul>
 *   <li>{@link #applyMinimal} — the {@code Signum} constructor phase:
 *       L0 layers only and the file log <b>forced off</b>, so no log file is
 *       opened before {@code start()} (the GUI is attached first)</li>
 *   <li>{@link #applyFull} — the {@code start()} / GUI-Apply phase: the
 *       complete L0+L1+L2 resolution; the SAME context is reconfigured
 *       (no restart, §9.1) and the generated node file is rewritten in
 *       place first (the writer is atomic)</li>
 * </ul>
 *
 * <h3>Layer sources (plan §8)</h3>
 * <ul>
 *   <li><b>L0-node</b>: the node module's built-in defaults from the
 *       {@link LoggingModuleRegistry} (empty when the provider is not
 *       registered yet — the resolver falls back to the global root)</li>
 *   <li><b>L0-global</b>: the built-in defaults of every other registered
 *       module (plan §8.4: the other modules' L0 is global)</li>
 *   <li><b>L1</b>: {@code {confRoot}/node/logging/default.properties}
 *       (the user-editable default layer)</li>
 *   <li><b>L2</b>: the node profile's assigned logging profile for the node
 *       module ({@link LoggingAssignmentStore} + repository file)</li>
 * </ul>
 *
 * <p>Headless-safe (no Swing). I/O errors propagate as
 * {@link IOException} — the caller (Signum) is defensive and never breaks
 * node startup on a logging failure (plan §5.3).</p>
 */
public final class NodeLoggingContextApplier {

    private NodeLoggingContextApplier() {
        // static pipeline
    }

    /**
     * Per-profile Apply serialization (plan §14.4-A3 / T11): the generated node
     * file write ({@link NodeLoggingConfigWriter#writeNode}) happens OUTSIDE
     * the node context's own applyLock (it precedes {@code getOrCreate}), so
     * two concurrent full Applies on the SAME profile would race on the same
     * target file — on Windows the atomic temp&#8594;target {@code Files.move}
     * then fails because the other Apply (or the freshly loaded config) still
     * holds the file. A per-profile lock covering write + getOrCreate +
     * reconfigure makes one Apply atomic per profile (no file-lock fight, no
     * half-applied state). It is distinct from the global Apply lock and the
     * node applyLock; the profile&#8594;node ordering is one-way, so there is
     * no deadlock cycle. Bounded by the number of profiles (small).
     */
    private static final ConcurrentHashMap<String, ReentrantLock> PROFILE_APPLY_LOCKS =
            new ConcurrentHashMap<>();

    private static ReentrantLock profileLock(final String profileName) {
        return PROFILE_APPLY_LOCKS.computeIfAbsent(profileName, k -> new ReentrantLock());
    }

    /**
     * Minimal config for the constructor phase: L0 layers only, file log
     * forced off (no file opened before {@code start()}).
     *
     * @return the running node context (created or reused via the manager)
     */
    public static NodeLoggingContext applyMinimal(Path confRoot, NodeLoggingContextManager manager,
                                                  String profileName) throws IOException {
        return apply(confRoot, manager, profileName, true);
    }

    /**
     * Full config for the {@code start()} / GUI-Apply phase: complete
     * L0+L1+L2 resolution; the same context is reconfigured (no restart).
     *
     * @return the running node context (created or reused via the manager)
     */
    public static NodeLoggingContext applyFull(Path confRoot, NodeLoggingContextManager manager,
                                               String profileName) throws IOException {
        return apply(confRoot, manager, profileName, false);
    }

    private static NodeLoggingContext apply(Path confRoot, NodeLoggingContextManager manager,
                                            String profileName, boolean minimal) throws IOException {
        // Per-profile serialization (plan §14.4-A3 / T11): one Apply (file write +
        // getOrCreate + reconfigure) is atomic per profile, so parallel Applies
        // on the same node cannot fight over the generated node file.
        ReentrantLock lock = profileLock(profileName);
        lock.lock();
        try {
            return doApply(confRoot, manager, profileName, minimal);
        } finally {
            lock.unlock();
        }
    }

    private static NodeLoggingContext doApply(Path confRoot, NodeLoggingContextManager manager,
                                              String profileName, boolean minimal) throws IOException {
        // Portable resolution: a relative conf root is resolved against the
        // application root (PathUtils SSOT), an absolute one (tests) passes
        // through unchanged.
        final Path root = application.utils.io.PathUtils.resolvePath(confRoot.toString());
        final Properties l2 = new Properties();
        if (minimal) {
            // never open a log file in the constructor phase (plan §6.3)
            l2.setProperty(NodeLoggingConfigResolver.KEY_FILE_LOG_ENABLE, "false");
        } else {
            loadL2(root, profileName, l2);
        }
        final NodeLoggingConfigResolver.Resolved resolved = NodeLoggingConfigResolver.resolve(
                new NodeLoggingConfigResolver.Layers(loadL0Node(), loadL0Global(), loadL1(root), l2),
                defaultFileLogDir(root));
        // The node pipeline writes ONLY its own generated node file (P2.4):
        // the global generated file belongs to the global context and is
        // written exclusively by the global Apply pipeline
        // (GlobalLoggingApplier) — one node's L2 must never leak into the
        // JVM-wide global file, and parallel node/global Applies must not
        // fight over the same file (Windows file locks, plan T17).
        final Path nodeFile = NodeLoggingConfigWriter.writeNode(root, profileName, resolved);
        final URI uri = nodeFile.toUri();

        final NodeLoggingContext ctx = manager.getOrCreate(profileName, uri);
        if (!ctx.isStarted()) {
            ctx.start(); // restart path: the stop() during node stop() closed the context (Q6)
        }
        // minimal and full write the SAME node file, so the normal path is a
        // plain file reconfigure(); reconfigureFrom() covers an unexpected path change
        final boolean reconfigured = !uri.equals(ctx.configUri())
                ? ctx.reconfigureFrom(uri)
                : ctx.reconfigure();
        // P1.4: NodeLoggingContext rejects an unloadable target (it keeps the previous
        // configuration and logs a WARN). Surface that rejection as a failure here so the
        // Apply chain (Signum.applyNodeLoggingConfiguration -> the GUI Apply handler)
        // reports an error instead of a false success.
        if (!reconfigured) {
            throw new IOException("node logging reconfigure rejected for profile '" + profileName
                    + "' (unloadable config at " + uri + ") - the previous configuration was kept");
        }
        return ctx;
    }

    // ── Layer loaders (plan §8) ────────────────────────────────────────

    /**
     * Loads the four configuration layers for the given node profile
     * (plan §8) from the same sources the Apply pipeline uses — exposed so
     * the global Apply pipeline ({@code GlobalLoggingApplier}) resolves from
     * exactly the same layer files.
     *
     * @return the loaded layers (never null; missing files pass as empty)
     */
    public static NodeLoggingConfigResolver.Layers loadLayers(Path confRoot, String profileName) {
        final Path root = application.utils.io.PathUtils.resolvePath(confRoot.toString());
        final Properties l2 = new Properties();
        loadL2(root, profileName, l2);
        return new NodeLoggingConfigResolver.Layers(
                loadL0Node(), loadL0Global(), loadL1(root), l2);
    }

    /**
     * The layers for the GLOBAL context resolution (used by the global Apply
     * pipeline). Identical to {@link #loadLayers(Path, String)} with a blank
     * profile, EXCEPT the L1 layer is the node L1 ({@code
     * {confRoot}/node/logging/default.properties}) overlaid with the
     * SYSTEM L1 ({@code {confRoot}/system/logging/default.properties}) —
     * the system L1 is the system-level (JVM-global) user layer and has
     * priority over the node L1's global-scope keys (the node L1 keeps its
     * legacy role for backward compatibility).
     */
    public static NodeLoggingConfigResolver.Layers loadGlobalLayers(Path confRoot) {
        final Path root = application.utils.io.PathUtils.resolvePath(confRoot.toString());
        final Properties l1 = new Properties();
        l1.putAll(loadL1(root));
        l1.putAll(loadL1System(root)); // system L1 wins over the node L1 (global scope)
        return new NodeLoggingConfigResolver.Layers(loadL0Node(), loadL0Global(), l1, new Properties());
    }

    /**
     * Resolves the effective node GUI console line buffer
     * ({@code node.console.maxLines}, F4 / decision D5) for a node profile, from the
     * SAME L0-node → L1 → L2 layers the Apply pipeline uses, so the console buffer and
     * the node logging configuration share a single source of truth (the JSON
     * {@code ConsoleSettings.maxConsoleLines} no longer governs the console).
     * <p>
     * L2 (the profile's assigned logging profile) wins over L1 (the node default layer),
     * which wins over L0-node (the built-in default, {@code 3000}).
     *
     * @param confRoot    the configuration root (relative roots resolve against the app root)
     * @param profileName the node profile name (null/blank → no L2, L0-node + L1 only)
     * @return the console line buffer (always positive; the L0 default when no layer sets it)
     */
    public static int resolveConsoleMaxLines(Path confRoot, String profileName) {
        final Path root = application.utils.io.PathUtils.resolvePath(confRoot.toString());
        final Properties merged = new Properties();
        merged.putAll(loadL0Node());
        merged.putAll(loadL1(root));
        final Properties l2 = new Properties();
        loadL2(root, profileName, l2);
        merged.putAll(l2);
        return ConsoleLimits.nodeMaxLines(merged);
    }

    /** L0-node: the node module's built-in defaults (empty until the provider registers). */
    static Properties loadL0Node() {
        final Properties props = new Properties();
        final ModuleLoggingProvider provider = LoggingModuleRegistry.getInstance().getProvider(ModuleIds.NODE);
        if (provider != null) {
            props.putAll(provider.getProfile().getDefaults());
        }
        return props;
    }

    /** L0-global: the built-in defaults of every OTHER registered module (plan §8.4). */
    static Properties loadL0Global() {
        final Properties props = new Properties();
        for (final ModuleLoggingProvider provider : LoggingModuleRegistry.getInstance().getAllProviders()) {
            if (!ModuleIds.NODE.equals(provider.getModuleId())) {
                props.putAll(provider.getProfile().getDefaults());
            }
        }
        return props;
    }

    /** L1: the user-editable default layer ({@code {confRoot}/node/logging/default.properties}). */
    static Properties loadL1(Path confRoot) {
        final LoggingProfileRepository repository = new LoggingProfileRepository(confRoot.toString());
        return readPropertiesQuietly(repository.getProfileFile(
                ModuleIds.NODE, LoggingProfileRepository.RESERVED_PROFILE_NAME));
    }

    /**
     * L1-system: the system-level (JVM-global) user-editable layer
     * ({@code {confRoot}/system/logging/default.properties}, SSOT:
     * {@link application.utils.config.LoggingPaths#systemL1File(String)}).
     */
    static Properties loadL1System(Path confRoot) {
        return readPropertiesQuietly(application.utils.config.LoggingPaths
                .systemL1File(confRoot.toString()));
    }

    /**
     * L2: the node profile's assigned logging profile for the node module
     * (null/blank profile — global-only resolution — or a missing/blank
     * assignment → empty layer; the reserved fallback is the assignment
     * store's own contract).
     */
    static void loadL2(Path confRoot, String profileName, Properties target) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        final String effective = new LoggingAssignmentStore(confRoot.toString())
                .resolveEffectiveForModule(profileName, ModuleIds.NODE);
        if (effective == null || effective.isBlank()) {
            return;
        }
        final LoggingProfileRepository repository = new LoggingProfileRepository(confRoot.toString());
        final Properties props = repository.loadProps(ModuleIds.NODE, effective);
        if (props != null) {
            target.putAll(props);
        }
    }

    /**
     * The default node log file directory: the portable, RELATIVE
     * {@code logs/node} (SSOT: {@link application.utils.config.LoggingPaths#NODE_LOG_DIR_RELATIVE})
     * — OUTSIDE the configuration tree, resolved against the application
     * root at read time (the launcher keeps the JVM working directory equal
     * to the application root), so a copied installation keeps working.
     * <p>
     * The {@code confRoot} parameter is kept for API compatibility; the
     * default no longer depends on it (the node file log has never belonged
     * under {@code conf/}).
     */
    public static String defaultFileLogDir(Path confRoot) {
        return application.utils.config.LoggingPaths.NODE_LOG_DIR_RELATIVE;
    }

    private static Properties readPropertiesQuietly(Path file) {
        final Properties props = new Properties();
        if (file == null || !Files.exists(file)) {
            return props;
        }
        try (InputStream is = Files.newInputStream(file);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (Exception e) {
            // unreadable layer: behave as absent (the resolver falls back)
        }
        return props;
    }
}