package application.module.node.db.store;

import application.module.node.Blockchain;
import application.module.node.db.BlockDb;
import application.module.node.db.TransactionDb;
import application.module.node.db.cache.DBCacheManagerImpl;
import application.module.node.db.sql.*;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.Props;
import application.module.node.props.PropertyService;
import application.module.node.services.TimeService;
import application.module.node.unconfirmedtransactions.UnconfirmedTransactionStore;
import application.module.node.unconfirmedtransactions.UnconfirmedTransactionStoreImpl;
import signum.net.NetworkParameters;

public class Stores {
    private final AccountStore accountStore;
    private final AliasStore aliasStore;
    private final AssetTransferStore assetTransferStore;
    private final AssetStore assetStore;
    private final ATStore atStore;
    private final BlockchainStore blockchainStore;
    private final DigitalGoodsStoreStore digitalGoodsStoreStore;
    private final EscrowStore escrowStore;
    private final OrderStore orderStore;
    private final TradeStore tradeStore;
    private final SqlSubscriptionStore subscriptionStore;
    private final UnconfirmedTransactionStore unconfirmedTransactionStore;
    private final SqlIndirectIncomingStore indirectIncomingStore;
    private final SqlAliasStore aliasStoreImpl;
    private final SqlOrderStore orderStoreImpl;
    private final DbContext dbContext;

    public Stores(DerivedTableManager derivedTableManager, DBCacheManagerImpl dbCacheManager, TimeService timeService,
            PropertyService propertyService, TransactionDb transactionDb,
            BlockDb blockDb, NetworkParameters params, StoreDependencies storeDependencies) {
        int insertMaxBatchSize = propertyService.getInt(Props.DB_INSERT_BATCH_MAX_SIZE);
        DbContext dbContext = storeDependencies.dbContext();
        this.dbContext = dbContext;
        this.accountStore = new SqlAccountStore(derivedTableManager, dbCacheManager, storeDependencies);
        this.aliasStoreImpl = new SqlAliasStore(derivedTableManager, dbContext);
        this.aliasStore = this.aliasStoreImpl;
        this.assetStore = new SqlAssetStore(derivedTableManager, storeDependencies);
        this.assetTransferStore = new SqlAssetTransferStore(derivedTableManager, dbContext);
        this.atStore = new SqlATStore(derivedTableManager, storeDependencies);
        this.digitalGoodsStoreStore = new SqlDigitalGoodsStoreStore(derivedTableManager, storeDependencies);
        this.escrowStore = new SqlEscrowStore(derivedTableManager, storeDependencies);
        this.orderStoreImpl = new SqlOrderStore(derivedTableManager, dbContext);
        this.orderStore = this.orderStoreImpl;
        this.tradeStore = new SqlTradeStore(derivedTableManager, dbContext);
        this.subscriptionStore = new SqlSubscriptionStore(derivedTableManager, insertMaxBatchSize, dbContext);
        this.unconfirmedTransactionStore = new UnconfirmedTransactionStoreImpl(timeService, propertyService,
                accountStore, transactionDb, params);
        this.indirectIncomingStore = new SqlIndirectIncomingStore(derivedTableManager, insertMaxBatchSize, dbContext);
        this.blockchainStore = new SqlBlockchainStore(transactionDb, blockDb, storeDependencies);
    }

    /**
     * Wires dependencies (like Blockchain) into stores after construction.
     * This breaks the circular dependency where Stores are created before Blockchain.
     */
    public void wireDependencies(Blockchain blockchain) {
        subscriptionStore.setBlockchain(blockchain);
        aliasStoreImpl.setBlockchain(blockchain);
        orderStoreImpl.setBlockchain(blockchain);
        ((SqlBlockchainStore) blockchainStore).setBlockchain(blockchain);
        ((SqlAccountStore) accountStore).setBlockchain(blockchain);
        ((SqlATStore) atStore).setBlockchain(blockchain);
        ((SqlAssetStore) assetStore).setBlockchain(blockchain);
        ((SqlDigitalGoodsStoreStore) digitalGoodsStoreStore).setBlockchain(blockchain);
        ((SqlEscrowStore) escrowStore).setBlockchain(blockchain);
    }

    /**
     * Wires the FluxCapacitor into stores after construction.
     * The FluxCapacitor is created after Stores (it depends on Blockchain),
     * so it cannot be provided via {@link StoreDependencies} at construction time.
     */
    public void wireFluxCapacitor(FluxCapacitor fluxCapacitor) {
        ((SqlBlockchainStore) blockchainStore).setFluxCapacitor(fluxCapacitor);
        ((SqlAccountStore) accountStore).setFluxCapacitor(fluxCapacitor);
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public AliasStore getAliasStore() {
        return aliasStore;
    }

    public AssetStore getAssetStore() {
        return assetStore;
    }

    public AssetTransferStore getAssetTransferStore() {
        return assetTransferStore;
    }

    public ATStore getAtStore() {
        return atStore;
    }

    public BlockchainStore getBlockchainStore() {
        return blockchainStore;
    }

    public DigitalGoodsStoreStore getDigitalGoodsStoreStore() {
        return digitalGoodsStoreStore;
    }

    public void beginTransaction() {
        dbContext.beginTransaction();
    }

    public void commitTransaction() {
        dbContext.commitTransaction();
    }

    public void rollbackTransaction() {
        dbContext.rollbackTransaction();
    }

    public void endTransaction() {
        dbContext.endTransaction();
    }

    public EscrowStore getEscrowStore() {
        return escrowStore;
    }

    public OrderStore getOrderStore() {
        return orderStore;
    }

    public TradeStore getTradeStore() {
        return tradeStore;
    }

    public UnconfirmedTransactionStore getUnconfirmedTransactionStore() {
        return unconfirmedTransactionStore;
    }

    public SubscriptionStore getSubscriptionStore() {
        return subscriptionStore;
    }

    public IndirectIncomingStore getIndirectIncomingStore() {
        return indirectIncomingStore;
    }

    /**
     * Returns the per-instance {@link DbContext} for this stores instance.
     * Enables multi-node isolation by providing instance-scoped database access
     * instead of static {@link Db} singleton calls.
     *
     * @return the DbContext managing connections, transactions, and migrations
     * @since 4.1
     */
    public DbContext getDbContext() {
        return dbContext;
    }
}
