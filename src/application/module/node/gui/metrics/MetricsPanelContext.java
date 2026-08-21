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
import application.module.node.instance.NodeCoreContext;

/**
 * Provides profile-aware access to node components for metrics panels.
 * <p>
 * Previously, metrics panels accessed components through static {@code Signum.getXxx()}
 * calls, which prevented multi-profile operation and made unit testing impossible.
 * {@code MetricsPanelContext} encapsulates a reference to the profile-specific
 * {@link NodeCoreContext} and exposes typed getters for each component needed
 * by the three metrics panels (Synchronization, Block Generation, Peer).
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // In MetricsPanel constructor:
 * NodeProfile profile = ...;
 * NodeCoreContext coreCtx = profile.getRuntime().getCoreContext();
 * MetricsPanelContext ctx = new MetricsPanelContext(coreCtx);
 *
 * SynchronizationMetricsPanel syncPanel = new SynchronizationMetricsPanel(parent, executor, ctx);
 * }</pre>
 *
 * <h2>Thread-safety</h2>
 * The context reference is immutable (final). Individual components carry their
 * own thread-safety guarantees. Null values are returned gracefully when the
 * node has not yet started.
 *
 * @since 4.0
 */
public final class MetricsPanelContext {

    private final NodeCoreContext coreContext;

    /**
     * Creates a new context wrapping the given profile-specific core context.
     *
     * @param coreContext the node core context, must not be null
     */
    public MetricsPanelContext(NodeCoreContext coreContext) {
        if (coreContext == null) {
            throw new IllegalArgumentException("NodeCoreContext must not be null");
        }
        this.coreContext = coreContext;
    }

    /**
     * Convenience constructor that extracts the NodeCoreContext from a Signum facade.
     * This is the greenfield way to wire metrics panels directly from a Signum instance.
     *
     * @param signum the Signum facade (owns the NodeCoreContext), must not be null
     * @since 4.0 Phase G - Greenfield wiring
     */
    public MetricsPanelContext(Signum signum) {
        this(signum != null ? signum.getContext() : null);
    }

    /**
     * Returns the underlying {@link NodeCoreContext}.
     *
     * @return the core context for this profile
     */
    public NodeCoreContext getCoreContext() {
        return coreContext;
    }

    // ── Component getters (delegate to NodeCoreContext) ──

    /**
     * Returns the PropertyService for this profile.
     * Used by: SynchronizationMetricsPanel, BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getPropertyService()}
     */
    public PropertyService getPropertyService() {
        return coreContext != null ? coreContext.getPropertyService() : null;
    }

    /**
     * Returns the Blockchain instance for this profile.
     * Used by: SynchronizationMetricsPanel, BlockGenerationMetricsPanel, PeerMetricsPanel.
     * Replaces: {@code Signum.getBlockchain()}
     *
     * @return blockchain instance, or null if not yet initialized
     */
    public BlockchainImpl getBlockchain() {
        return coreContext != null ? coreContext.getBlockchain() : null;
    }

    /**
     * Returns the BlockchainProcessor for this profile.
     * Used by: SynchronizationMetricsPanel, PeerMetricsPanel, BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getBlockchainProcessor()}
     *
     * @return blockchain processor, or null if not yet initialized
     */
    public BlockchainProcessor getBlockchainProcessor() {
        return coreContext != null ? coreContext.getBlockchainProcessor() : null;
    }

    /**
     * Returns the FluxCapacitor for this profile.
     * Used by: SynchronizationMetricsPanel, BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getFluxCapacitor()}
     *
     * @return flux capacitor, or null if not yet initialized
     */
    public FluxCapacitor getFluxCapacitor() {
        return coreContext != null ? coreContext.getFluxCapacitor() : null;
    }

    /**
     * Returns the Generator for this profile.
     * Used by: BlockGenerationMetricsPanel.
     * Replaces: {@code Signum.getGenerator()}
     *
     * @return generator instance, or null if not yet initialized
     */
    public Generator getGenerator() {
        return coreContext != null ? coreContext.getGenerator() : null;
    }

    /**
     * Returns the TransactionProcessor for this profile.
     * Used by: SynchronizationMetricsPanel.
     * Replaces: {@code Signum.getTransactionProcessor()}
     *
     * @return transaction processor, or null if not yet initialized
     */
    public TransactionProcessorImpl getTransactionProcessor() {
        return coreContext != null ? coreContext.getTransactionProcessor() : null;
    }

    /**
     * Returns the PeerManager for this profile.
     * Used by: PeerMetricsPanel.
     * Replaces: {@code Peers.getAllPeers()}, {@code Peers.getPeer()}
     *
     * @return peer manager, or null if not yet initialized
     */
    public PeerManager getPeerManager() {
        return coreContext != null ? coreContext.getPeerManager() : null;
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
        return coreContext != null ? coreContext.getStores().getAccountStore() : null;
    }
}
