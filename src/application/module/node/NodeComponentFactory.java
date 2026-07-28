package application.module.node;

import application.module.node.db.BlockDb;
import application.module.node.db.TransactionDb;
import application.module.node.db.cache.DBCacheManagerImpl;
import application.module.node.db.store.BlockchainStore;
import application.module.node.db.store.DerivedTableManager;
import application.module.node.db.store.Stores;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import application.module.node.services.AccountService;
import application.module.node.services.AliasService;
import application.module.node.services.BlockService;
import application.module.node.services.EscrowService;
import application.module.node.services.IndirectIncomingService;
import application.module.node.services.SubscriptionService;
import application.module.node.services.TimeService;
import application.module.node.services.TransactionService;
import application.module.node.statistics.StatisticsManagerImpl;
import application.module.node.util.DownloadCacheImpl;

/**
 * Factory for creating node components that have package-private constructors.
 * <p>
 * This class resides in {@code application.module.node} so it has access to
 * package-private constructors of {@link BlockchainImpl} and inner classes
 * like {@link GeneratorImpl.MockGenerator}. This allows {@code NodeCoreContext}
 * (which lives in the {@code instance} sub-package) to instantiate these
 * components through dependency-injection-friendly factory methods.
 * </p>
 *
 * @since 4.0
 */
public final class NodeComponentFactory {

    private NodeComponentFactory() {
        // Utility factory - no instantiation
    }

    // -------------------------------------------------------------------------
    // Blockchain
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link BlockchainImpl} instance.
     * <p>
     * Required because the constructor is package-private and cannot be called
     * from sub-packages like {@code application.module.node.instance}.
     * </p>
     *
     * @param transactionDb   the transaction database access
     * @param blockDb         the block database access
     * @param blockchainStore the blockchain data store
     * @param propertyService the resolved properties for this profile
     * @return a new {@link BlockchainImpl} instance
     */
    public static BlockchainImpl createBlockchain(
            TransactionDb transactionDb,
            BlockDb blockDb,
            BlockchainStore blockchainStore,
            PropertyService propertyService) {
        return new BlockchainImpl(transactionDb, blockDb, blockchainStore, propertyService);
    }

    // -------------------------------------------------------------------------
    // Generator (normal + mock variant)
    // -------------------------------------------------------------------------

    /**
     * Creates the appropriate {@link Generator} implementation based on the
     * mining mode configuration.
     * <p>
     * When {@code dev.mockMining} is {@code true}, a {@link GeneratorImpl.MockGenerator}
     * is returned; otherwise a standard {@link GeneratorImpl} is created.
     * </p>
     *
     * @param mockMining      whether mock mining is enabled
     * @param propertyService the resolved properties for this profile
     * @param blockchain      the blockchain implementation
     * @param accountService  the account service
     * @param timeService     the time service
     * @param fluxCapacitor   the flux capacitor instance
     * @param downloadCache   the block download cache (null for mock mining)
     * @return a new {@link Generator} instance
     */
    public static Generator createGenerator(
            boolean mockMining,
            PropertyService propertyService,
            Blockchain blockchain,
            AccountService accountService,
            TimeService timeService,
            FluxCapacitor fluxCapacitor,
            DownloadCacheImpl downloadCache) {
        if (mockMining) {
            return new GeneratorImpl.MockGenerator(
                    propertyService,
                    blockchain,
                    accountService,
                    timeService,
                    fluxCapacitor);
        }
        return new GeneratorImpl(
                blockchain,
                downloadCache,
                accountService,
                timeService,
                fluxCapacitor);
    }

    // -------------------------------------------------------------------------
    // BlockchainProcessor
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link BlockchainProcessorImpl} instance.
     * <p>
     * Although the constructor is public, this factory method provides a
     * centralised location for all component instantiation and makes it easy
     * to swap implementations in the future.
     * </p>
     *
     * @param threadPool            the dedicated thread pool
     * @param blockService          the block service
     * @param transactionProcessor  the transaction processor
     * @param blockchain            the blockchain implementation
     * @param propertyService       the resolved properties for this profile
     * @param subscriptionService   the subscription service
     * @param timeService           the time service
     * @param derivedTableManager   the derived table manager
     * @param blockDb               the block database access
     * @param transactionDb         the transaction database access
     * @param economicClustering    economic clustering for tx prioritization
     * @param blockchainStore       the blockchain data store
     * @param stores                all data stores
     * @param escrowService         the escrow service
     * @param transactionService    the transaction service
     * @param downloadCache         the block download cache
     * @param generator             the block generator
     * @param statisticsManager     the statistics manager
     * @param dbCacheManager        the DB cache manager
     * @param accountService        the account service
     * @param indirectIncomingService the indirect incoming service
     * @param aliasService          the alias service
     * @return a new {@link BlockchainProcessorImpl} instance
     */
    public static BlockchainProcessorImpl createBlockchainProcessor(
            application.module.node.util.ThreadPool threadPool,
            BlockService blockService,
            TransactionProcessorImpl transactionProcessor,
            BlockchainImpl blockchain,
            PropertyService propertyService,
            SubscriptionService subscriptionService,
            TimeService timeService,
            DerivedTableManager derivedTableManager,
            BlockDb blockDb,
            TransactionDb transactionDb,
            EconomicClustering economicClustering,
            BlockchainStore blockchainStore,
            Stores stores,
            EscrowService escrowService,
            TransactionService transactionService,
            DownloadCacheImpl downloadCache,
            Generator generator,
            StatisticsManagerImpl statisticsManager,
            DBCacheManagerImpl dbCacheManager,
            AccountService accountService,
            IndirectIncomingService indirectIncomingService,
            AliasService aliasService) {
        return new BlockchainProcessorImpl(
                threadPool,
                blockService,
                transactionProcessor,
                blockchain,
                propertyService,
                subscriptionService,
                timeService,
                derivedTableManager,
                blockDb,
                transactionDb,
                economicClustering,
                blockchainStore,
                stores,
                escrowService,
                transactionService,
                downloadCache,
                generator,
                statisticsManager,
                dbCacheManager,
                accountService,
                indirectIncomingService,
                aliasService);
    }

}
