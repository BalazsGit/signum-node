package application.module.node.gui.metrics;

import application.module.node.BlockchainImpl;
import application.module.node.BlockchainProcessor;
import application.module.node.Generator;
import application.module.node.Signum;
import application.module.node.TransactionProcessorImpl;
import application.module.node.db.store.AccountStore;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.peer.PeerManager;
import application.module.node.props.PropertyService;

/**
 * Provides profile-aware access to node components for metrics panels.
 * <p>
 * Encapsulates a reference to the profile-specific {@code Signum} instance and exposes
 * typed getters for each component needed by the three metrics panels
 * (Synchronization, Block Generation, Peer).
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // In MetricsPanel constructor:
 * Signum signum = NodeModule.getInstance().get(profileName);
 * MetricsPanelContext ctx = new MetricsPanelContext(signum);
 *
 * SynchronizationMetricsPanel syncPanel = new SynchronizationMetricsPanel(parent, executor, ctx);
 * }</pre>
 *
 * <h2>Thread-safety</h2>
 * The Signum reference is immutable (final). Individual components carry their
 * own thread-safety guarantees. Null values are returned gracefully when the
 * node has not yet started.
 *
 * @since 4.0
 */
public final class MetricsPanelContext {

    private final Signum signum;

    /**
     * Creates a new context wrapping the given Signum instance.
     *
     * @param signum the node instance, must not be null
     */
    public MetricsPanelContext(Signum signum) {
        if (signum == null) {
            throw new IllegalArgumentException("Signum must not be null");
        }
        this.signum = signum;
    }

    /**
     * Returns the underlying Signum instance.
     *
     * @return the Signum for this profile
     */
    public Signum getSignum() {
        return signum;
    }

    // ── Component getters (delegate to Signum) ──

    /**
     * Returns the PropertyService for this profile.
     * Used by: SynchronizationMetricsPanel, BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getPropertyService()}
     */
    public PropertyService getPropertyService() {
        return signum != null ? signum.getPropertyService() : null;
    }

    /**
     * Returns the Blockchain instance for this profile.
     * Used by: SynchronizationMetricsPanel, BlockGenerationMetricsPanel, PeerMetricsPanel.
     * Replaces: {@code Signum.getBlockchain()}
     *
     * @return blockchain instance, or null if not yet initialized
     */
    public BlockchainImpl getBlockchain() {
        return signum != null ? signum.getBlockchain() : null;
    }

    /**
     * Returns the BlockchainProcessor for this profile.
     * Used by: SynchronizationMetricsPanel, PeerMetricsPanel, BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getBlockchainProcessor()}
     *
     * @return blockchain processor, or null if not yet initialized
     */
    public BlockchainProcessor getBlockchainProcessor() {
        return signum != null ? signum.getBlockchainProcessor() : null;
    }

    /**
     * Returns the FluxCapacitor for this profile.
     * Used by: SynchronizationMetricsPanel, BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getFluxCapacitor()}
     *
     * @return flux capacitor, or null if not yet initialized
     */
    public FluxCapacitor getFluxCapacitor() {
        return signum != null ? signum.getFluxCapacitor() : null;
    }

    /**
     * Returns the Generator for this profile.
     * Used by: BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getGenerator()}
     *
     * @return generator instance, or null if not yet initialized
     */
    public Generator getGenerator() {
        return signum != null ? signum.getGenerator() : null;
    }

    /**
     * Returns the TransactionProcessor for this profile.
     * Used by: SynchronizationMetricsPanel.
     * Replaces: {@code Signum.getTransactionProcessor()}
     *
     * @return transaction processor, or null if not yet initialized
     */
    public TransactionProcessorImpl getTransactionProcessor() {
        return signum != null ? signum.getTransactionProcessor() : null;
    }

    /**
     * Returns the PeerManager for this profile.
     * Used by: PeerMetricsPanel.
     * Replaces: {@code Peers.getAllPeers()}, {@code Peers.getPeer()}
     *
     * @return peer manager, or null if not yet initialized
     */
    public PeerManager getPeerManager() {
        return signum != null ? signum.getPeerManager() : null;
    }

    /**
     * Returns the AccountStore for this profile.
     * Used by: BlockGenerationMetricsPanel, MinersListDialog.
     * Replaces: {@code Signum.getStores().getAccountStore()}
     *
     * @return account store, or null if not yet initialized
     * @since 4.1 P3 Bridge Cleanup
     */
    public AccountStore getAccountStore() {
        return signum != null ? signum.getStores().getAccountStore() : null;
    }
}
