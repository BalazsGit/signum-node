package application.module.logging.context;

import application.module.logging.context.NodeLoggingConfigResolver.Resolved;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the generated Log4j2 .properties files into the runtime conf layout
 * (per-node isolated LoggerContext plan, §6.4).
 * <p>
 * On-disk contract:
 * <ul>
 *   <li>{@code {confRoot}/logging-runtime/global.log4j2.properties} — the
 *       global context configuration</li>
 *   <li>{@code {confRoot}/node/logging/runtime/{profile}.log4j2.properties}
 *       — the node context configuration of the given profile</li>
 * </ul>
 * The files are <b>generated artifacts, not the source of truth</b> (the SSOT
 * stays the stored L0/L1/L2 layers + assignment); they exist so that
 * {@code LoggerContext} creation and {@code reconfigure()} file reload have a
 * deterministic source, and for debugging. Writes are atomic (temp file +
 * move) so a concurrent {@code reconfigure()} never reads a half-written file.
 * </p>
 *
 * <p>Headless-safe (no Swing, no logging — the caller reports errors).</p>
 */
public final class NodeLoggingConfigWriter {

    private NodeLoggingConfigWriter() {
        // Utility class
    }

    /**
     * Writes both generated files for the resolved configuration.
     *
     * @param confRoot    the base configuration root (e.g. {@code ./conf})
     * @param profileName the node profile name (file name of the node config)
     * @param resolved    the resolved configuration (never null)
     * @return the pair of written paths as {@code [globalFile, nodeFile]}
     * @throws IOException if a file cannot be written
     */
    public static Path[] write(Path confRoot, String profileName, Resolved resolved) throws IOException {
        Path globalFile = writeGlobal(confRoot, resolved);
        Path nodeFile = writeNode(confRoot, profileName, resolved);
        return new Path[]{globalFile, nodeFile};
    }

    /**
     * Writes the global generated config to
     * {@code {confRoot}/logging-runtime/global.log4j2.properties}.
     */
    public static Path writeGlobal(Path confRoot, Resolved resolved) throws IOException {
        Path file = confRoot.resolve("logging-runtime").resolve("global.log4j2.properties");
        writeAtomic(file, NodeLoggingConfigResolver.renderGlobalProperties(resolved));
        return file;
    }

    /**
     * Writes the node generated config to
     * {@code {confRoot}/node/logging/runtime/{profile}.log4j2.properties}.
     */
    public static Path writeNode(Path confRoot, String profileName, Resolved resolved) throws IOException {
        Path file = confRoot.resolve("node").resolve("logging")
                .resolve("runtime").resolve(profileName + ".log4j2.properties");
        writeAtomic(file, NodeLoggingConfigResolver.renderNodeProperties(resolved, profileName));
        return file;
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            // Filesystems without atomic rename (e.g. some network drives): plain replace.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}