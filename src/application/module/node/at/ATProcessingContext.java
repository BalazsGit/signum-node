package application.module.node.at;

import application.module.node.Blockchain;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.db.store.AccountStore;
import application.module.node.db.store.AssetStore;
import application.module.node.db.store.ATStore;
import application.module.node.db.store.IndirectIncomingStore;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import application.module.node.services.AccountService;

/**
 * Immutable context holder for AT (Automated Transaction) processing.
 * <p>
 * Provides all dependencies required by the AT module's internal components
 * (AtController, AtMachineProcessor, AtMachineState, AtApiPlatformImpl) without
 * relying on static accessors like {@code Signum.getXxx()} or singleton patterns.
 * </p>
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li><b>Immutability:</b> All fields are final; no setters.</li>
 *   <li><b>Constructor Injection:</b> All dependencies provided at construction time.</li>
 *   <li><b>Thread-Safe:</b> Safe to share across threads after construction.</li>
 * </ul>
 *
 * @since 4.0
 */
public final class ATProcessingContext {

    private final AtConstants atConstants;
    private final ATProcessorCache processorCache;
    private final PropertyService propertyService;
    private final FluxCapacitor fluxCapacitor;
    private final Blockchain blockchain;
    private final ATStore atStore;
    private final AccountStore accountStore;
    private final AccountService accountService;
    private final AssetExchange assetExchange;
    private final IndirectIncomingStore indirectIncomingStore;
    private final AssetStore assetStore;
    /**
     * Instance-scoped pending AT state (fees, transactions, map updates).
     * One per node, eliminating JVM-wide shared mutable state.
     */
    private final ATPendingState pendingState;

    /**
     * Creates a new AT processing context with all required dependencies.
     *
     * The {@link ATPendingState} is created internally to guarantee
     * per-instance isolation.
     */
    @Deprecated
    public ATProcessingContext(
            AtConstants atConstants,
            ATProcessorCache processorCache,
            PropertyService propertyService,
            FluxCapacitor fluxCapacitor,
            Blockchain blockchain,
            ATStore atStore,
            AccountStore accountStore,
            AccountService accountService,
            AssetExchange assetExchange,
            IndirectIncomingStore indirectIncomingStore,
            AssetStore assetStore) {
        this(atConstants, processorCache, propertyService, fluxCapacitor, blockchain,
                atStore, accountStore, accountService, assetExchange, indirectIncomingStore,
                assetStore, new ATPendingState());
    }

    /**
     * Creates a new AT processing context with an explicit pending state.
     */
    public ATProcessingContext(
            AtConstants atConstants,
            ATProcessorCache processorCache,
            PropertyService propertyService,
            FluxCapacitor fluxCapacitor,
            Blockchain blockchain,
            ATStore atStore,
            AccountStore accountStore,
            AccountService accountService,
            AssetExchange assetExchange,
            IndirectIncomingStore indirectIncomingStore,
            AssetStore assetStore,
            ATPendingState pendingState) {
        this.atConstants = atConstants;
        this.processorCache = processorCache;
        this.propertyService = propertyService;
        this.fluxCapacitor = fluxCapacitor;
        this.blockchain = blockchain;
        this.atStore = atStore;
        this.accountStore = accountStore;
        this.accountService = accountService;
        this.assetExchange = assetExchange;
        this.indirectIncomingStore = indirectIncomingStore;
        this.assetStore = assetStore;
        this.pendingState = pendingState;
    }

    /** @return the AT configuration constants */
    public AtConstants getAtConstants() {
        return atConstants;
    }

    /** @return the AT processor cache */
    public ATProcessorCache getProcessorCache() {
        return processorCache;
    }

    /** @return the configuration properties service */
    public PropertyService getPropertyService() {
        return propertyService;
    }

    /** @return the feature flag / epoch tracking */
    public FluxCapacitor getFluxCapacitor() {
        return fluxCapacitor;
    }

    /** @return the blockchain instance */
    public Blockchain getBlockchain() {
        return blockchain;
    }

    /** @return the AT data store */
    public ATStore getAtStore() {
        return atStore;
    }

    /** @return the account data store */
    public AccountStore getAccountStore() {
        return accountStore;
    }

    /** @return the account service */
    public AccountService getAccountService() {
        return accountService;
    }

    /** @return the asset exchange service */
    public AssetExchange getAssetExchange() {
        return assetExchange;
    }

    /** @return the indirect incoming data store */
    public IndirectIncomingStore getIndirectIncomingStore() {
        return indirectIncomingStore;
    }

    /** @return the asset data store */
    public AssetStore getAssetStore() {
        return assetStore;
    }

    /**
     * @return the instance-scoped pending AT state (fees, transactions, map updates).
     */
    public ATPendingState getPendingState() {
        return pendingState;
    }

    @Override
    public String toString() {
        return "ATProcessingContext{atConstants=" + (atConstants != null ? "present" : "null") + "}";
    }

}
