package it.java.node;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import application.module.node.NodeModule;
import application.module.node.Signum;

/**
 * Integration test for the v4 mission (multi_node_profile_architecture_v4.md §6):
 * two independent profiles running in parallel, each with its own HTTP/P2P port.
 * <p>
 * <ul>
 *   <li>Start profile A &rarr; RUNNING,</li>
 *   <li>Start profile B &rarr; RUNNING (both at the same time — the actual mission),</li>
 *   <li>Stop A &rarr; B is untouched and stays RUNNING,</li>
 *   <li>stopAll() in teardown.</li>
 * </ul>
 * Each profile resolves its configuration from
 * {@code {confRoot}/node/profiles/{name}.properties} (see
 * {@code Signum.loadPropertiesForProfile}), so the two nodes get independent
 * database directories and ports. This test uses its own temporary conf root and
 * therefore does not interfere with the shared {@code ./conf} state.
 * </p>
 */
public class MultiNodeParallelIT {

    private static final String ALPHA = "parallel-it-alpha-" + System.nanoTime();
    private static final String BETA = "parallel-it-beta-" + System.nanoTime();

    private static final int ALPHA_HTTP_PORT = 18125;
    private static final int ALPHA_P2P_PORT = 18123;
    private static final int BETA_HTTP_PORT = 18126;
    private static final int BETA_P2P_PORT = 18124;

    @org.junit.Rule
    public TemporaryFolder confRoot = new TemporaryFolder();

    @After
    public void teardown() {
        // Never leak running nodes into other tests (v4: NodeModule is the sole lifecycle root).
        NodeModule.getInstance().stopAll();
    }

    private Path writeProfile(Path root, String name, int httpPort, int p2pPort) throws Exception {
        Path profilesDir = root.resolve("node").resolve("profiles");
        Files.createDirectories(profilesDir);
        Path file = profilesDir.resolve(name + ".properties");
        // Hermetic setup: the profile must pin its own SQLite file under the
        // temporary conf root — the default DB.Url is CWD-relative and would
        // collide with the developer's local database (shared schema, Flyway
        // validation failure).
        Files.write(file, List.of(
                "API.Port=" + httpPort,
                "API.WebSocketEnable=false",
                "P2P.Port=" + p2pPort,
                "P2P.UPnP=false",
                "P2P.BootstrapPeers=",
                "DB.Url=jdbc:sqlite:file:" + root.resolve("db").resolve(name).resolve("signum.sqlite.db").toString().replace('\\', '/')));
        return file;
    }

    @Test
    public void twoProfiles_differentPorts_bothRunning_independently() throws Exception {
        Path root = confRoot.getRoot().toPath();
        writeProfile(root, ALPHA, ALPHA_HTTP_PORT, ALPHA_P2P_PORT);
        writeProfile(root, BETA, BETA_HTTP_PORT, BETA_P2P_PORT);

        NodeModule module = NodeModule.getInstance();
        Signum alpha = module.startNode(ALPHA, root);
        try {
            assertTrue("alpha must be RUNNING, was " + alpha.getState(),
                    alpha.getState() == Signum.State.RUNNING);

            // ── The actual mission: start the SECOND node while the first runs ──
            Signum beta = module.startNode(BETA, root);
            try {
                assertNotSame("each profile must own its own Signum instance", alpha, beta);
                assertTrue("beta must be RUNNING while alpha runs, was " + beta.getState(),
                        beta.getState() == Signum.State.RUNNING);
                assertTrue("alpha must stay RUNNING after beta started, was " + alpha.getState(),
                        alpha.getState() == Signum.State.RUNNING);

                // ── Stop A: B is untouched ──
                module.stopNode(ALPHA);
                assertEquals("stopping alpha must not affect beta",
                        Signum.State.RUNNING, beta.getState());
            } finally {
                module.stopNode(BETA);
            }
        } finally {
            module.stopNode(ALPHA);
        }
    }
}
