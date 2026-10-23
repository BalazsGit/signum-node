package application.module.logging.context;

import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.utils.config.ModuleIds;
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
        final Path root = confRoot.toAbsolutePath();
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
        // [0]=global file, [1]=node file
        final Path nodeFile = NodeLoggingConfigWriter.write(root, profileName, resolved)[1];
        final URI uri = nodeFile.toUri();

        final NodeLoggingContext ctx = manager.getOrCreate(profileName, uri);
        if (!ctx.isStarted()) {
            ctx.start(); // restart path: the stop() during node stop() closed the context (Q6)
        }
        // minimal and full write the SAME node file, so the normal path is a
        // plain file reconfigure(); reconfigureFrom() covers an unexpected path change
        if (!uri.equals(ctx.configUri())) {
            ctx.reconfigureFrom(uri);
        } else {
            ctx.reconfigure();
        }
        return ctx;
    }

    // ── Layer loaders (plan §8) ────────────────────────────────────────

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
     * L2: the node profile's assigned logging profile for the node module
     * (missing/blank assignment → empty layer; the reserved fallback is the
     * assignment store's own contract).
     */
    static void loadL2(Path confRoot, String profileName, Properties target) {
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
     * The default node log file directory ({@code {confRoot}/logs/node}),
     * absolute, as the resolver expects.
     */
    static String defaultFileLogDir(Path confRoot) {
        return confRoot.toAbsolutePath().resolve("logs").resolve("node").toString();
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