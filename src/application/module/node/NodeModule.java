package application.module.node;

import application.api.Module;
import application.api.ModuleContext;
import application.api.ShutdownPriority;
import application.api.Shutdownable;
import application.module.node.gui.NodePanel;
import application.module.node.lifecycle.NodeLifecycleManager;
import application.module.node.logging.NodeLoggingProvider;

import javax.swing.JComponent;
import javax.swing.JFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Signum Node module implementation for the application framework.
 * 
 * This module manages the entire blockchain node lifecycle including:
 * - Profile discovery and management (via NodeLifecycleManager)
 * - Node startup/shutdown coordination
 * - Web server, P2P network, and blockchain processor management
 * 
 * Shutdown Priority: HIGHEST - The Node module is shut down first because it
 * manages active network connections, blockchain processing, and web servers
 * that must be stopped before lower-level resources (database, logging) are cleaned up.
 * 
 * Design note for Solution B migration: Currently this module delegates to the
 * static Signum class for core operations. In Solution B, each NodeInstance will
 * be a fully independent object graph with its own WebServer, BlockchainProcessor,
 * etc. The stop() contract remains the same but will target specific instances.
 */
public class NodeModule implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeModule.class);

    public static final String ID = "node";
    public static final String DISPLAY_NAME = "Node";

    private ModuleContext context;
    private NodePanel gui;
    private NodeLoggingProvider loggingProvider;
    private volatile boolean stopped = false;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public void init(ModuleContext context) {
        this.context = context;
    }

    @Override
    public void start() {
        // Register logging provider so the composite logging infrastructure
        // knows about the Node module's built-in defaults & presets.
        if (loggingProvider == null) {
            loggingProvider = new NodeLoggingProvider();
        }
        loggingProvider.register();
    }

    @Override
    public void stop() {
        // Idempotent guard - safe to call multiple times
        if (stopped) {
            LOGGER.debug("NodeModule already stopped, skipping.");
            return;
        }
        stopped = true;

        LOGGER.info("Stopping NodeModule - initiating full node shutdown sequence");

        try {
            // 1. Unregister the logging provider first (cleanup registration)
            if (loggingProvider != null) {
                loggingProvider.unregister();
                LOGGER.debug("Unregistered NodeLoggingProvider");
            }

            // 2. Stop all running node profiles via lifecycle manager
            //    This calls Signum.shutdownNode() which handles:
            //    - WebServer shutdown
            //    - BlockchainProcessor shutdown
            //    - Peers/threadPool shutdown
            //    - DBCacheManager cleanup
            //    - Database shutdown
            NodeLifecycleManager lifecycleManager = NodeLifecycleManager.getInstance();
            lifecycleManager.stopAllProfiles();
            LOGGER.debug("Stopped all node profiles via NodeLifecycleManager");

        } catch (Exception e) {
            LOGGER.error("Error during NodeModule stop sequence", e);
        }
    }

    @Override
    public JComponent getUI() {
        if (gui == null) {
            LOGGER.info("[DIAG] NodeModule.getUI() - creating NEW NodePanel instance");
            JFrame parentFrame = null;
            for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                if (f instanceof JFrame) {
                    parentFrame = (JFrame) f;
                    if (f.isVisible()) {
                        break;
                    }
                }
            }
            try {
                gui = new NodePanel(parentFrame);
                LOGGER.info("[DIAG] NodeModule.getUI() - NodePanel created successfully");
            } catch (Exception e) {
                LOGGER.error("[DIAG] NodeModule.getUI() - FAILED to create NodePanel", e);
                throw e;
            }
        } else {
            LOGGER.debug("[DIAG] NodeModule.getUI() - returning cached NodePanel instance");
        }
        return gui;
    }

    // =====================================================================
    // Shutdownable contract overrides
    // =====================================================================

    /**
     * The Node module has the HIGHEST shutdown priority because it manages
     * active network connections, blockchain processing, web servers, and
     * thread pools that must be stopped before database/logging cleanup.
     */
    @Override
    public ShutdownPriority getShutdownPriority() {
        return ShutdownPriority.HIGHEST;
    }

    /**
     * Override shutdown to delegate to stop().
     * Since stop() already handles internal error logging, we wrap
     * any unexpected exceptions in ShutdownException for the orchestrator.
     */
    @Override
    public void shutdown() throws ShutdownException {
        try {
            stop();
        } catch (Exception e) {
            throw new ShutdownException(getId(), "Failed to stop NodeModule", e);
        }
    }
}
