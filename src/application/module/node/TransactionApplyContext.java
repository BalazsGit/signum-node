package application.module.node;

import application.module.node.at.AtConstants;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.db.store.AccountStore;
import application.module.node.db.store.ATStore;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import application.module.node.services.AccountService;
import application.module.node.services.AliasService;
import application.module.node.services.DGSGoodsStoreService;
import application.module.node.services.EscrowService;
import application.module.node.services.SubscriptionService;

/**
 * Immutable snapshot of all services required for transaction type processing.
 * <p>
 * <h3>Purpose</h3>
 * Eliminates static dependency access in {@link TransactionType} subclasses by providing
 * all runtime services through a single, read-only context object. Each
 * {@link application.module.node.instance.NodeCoreContext} creates its own instance,
 * enabling true multi-profile isolation.
 * </p>
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li><b>Immutability:</b> All fields are final; no setters.</li>
 *   <li><b>Constructor Injection:</b> All dependencies provided at construction time.</li>
 *   <li><b>No Static State:</b> This class carries zero static mutable state.</li>
 *   <li><b>Thread-Safe:</b> Safe to share across threads after construction.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Created once per NodeCoreContext during initialization:
 * TransactionApplyContext ctx = new TransactionApplyContext(
 *     blockchain, fluxCapacitor, accountService, dgsService,
 *     aliasService, assetExchange, subscriptionService, escrowService, propertyService);
 *
 * // Passed to transaction type methods:
 * transactionType.validate(transaction, ctx);
 * transactionType.apply(transaction, senderAccount, recipientAccount, ctx);
 * }</pre>
 *
 * @since 4.0
 */
public final class TransactionApplyContext {

    private final Blockchain blockchain;
    private final FluxCapacitor fluxCapacitor;
    private final AccountService accountService;
    private final DGSGoodsStoreService dgsGoodsStoreService;
    private final AliasService aliasService;
    private final AssetExchange assetExchange;
    private final SubscriptionService subscriptionService;
    private final EscrowService escrowService;
    private final PropertyService propertyService;
    private final ATStore atStore;
    private final AtConstants atConstants;
    private final AccountStore accountStore;

    /**
     * Creates a new immutable transaction processing context.
     *
     * @param blockchain            the blockchain instance
     * @param fluxCapacitor         feature flag / epoch tracking
     * @param accountService        account balance and data service
     * @param dgsGoodsStoreService  digital goods store service
     * @param aliasService          alias management service
     * @param assetExchange         asset exchange service
     * @param subscriptionService   subscription management service
     * @param escrowService         escrow transaction service
     * @param propertyService       configuration properties
     * @param atStore               automated transaction data store
     * @param atConstants           automated transaction constants
     */
    public TransactionApplyContext(
            Blockchain blockchain,
            FluxCapacitor fluxCapacitor,
            AccountService accountService,
            DGSGoodsStoreService dgsGoodsStoreService,
            AliasService aliasService,
            AssetExchange assetExchange,
            SubscriptionService subscriptionService,
            EscrowService escrowService,
            PropertyService propertyService,
            ATStore atStore,
            AtConstants atConstants,
            AccountStore accountStore) {

        this.blockchain = blockchain;
        this.fluxCapacitor = fluxCapacitor;
        this.accountService = accountService;
        this.dgsGoodsStoreService = dgsGoodsStoreService;
        this.aliasService = aliasService;
        this.assetExchange = assetExchange;
        this.subscriptionService = subscriptionService;
        this.escrowService = escrowService;
        this.propertyService = propertyService;
        this.atStore = atStore;
        this.atConstants = atConstants;
        this.accountStore = accountStore;
    }

    /** @return the blockchain instance for this context */
    public Blockchain getBlockchain() {
        return blockchain;
    }

    /** @return the flux capacitor for feature flag / epoch queries */
    public FluxCapacitor getFluxCapacitor() {
        return fluxCapacitor;
    }

    /** @return the account service for balance operations */
    public AccountService getAccountService() {
        return accountService;
    }

    /** @return the digital goods store service */
    public DGSGoodsStoreService getDgsGoodsStoreService() {
        return dgsGoodsStoreService;
    }

    /** @return the alias service for alias lookups and mutations */
    public AliasService getAliasService() {
        return aliasService;
    }

    /** @return the asset exchange service */
    public AssetExchange getAssetExchange() {
        return assetExchange;
    }

    /** @return the subscription service */
    public SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    /** @return the escrow service */
    public EscrowService getEscrowService() {
        return escrowService;
    }

    /** @return the property service for configuration access */
    public PropertyService getPropertyService() {
        return propertyService;
    }

    /** @return the AT store for automated transaction data access */
    public ATStore getAtStore() {
        return atStore;
    }

    /** @return the AT constants for automated transaction configuration */
    public AtConstants getAtConstants() {
        return atConstants;
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    @Override
    public String toString() {
        return "TransactionApplyContext{blockchain=" + (blockchain != null ? "present" : "null") + "}";
    }
}
