package brs;

import brs.peer.Peer;
import brs.peer.PeerMetric;
import brs.util.JSON;
import brs.util.Observable;
import com.google.gson.JsonObject;
import brs.util.Listener;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public interface BlockchainProcessor extends Observable<Block, BlockchainProcessor.Event> {

    /**
     * Holds the status of the download/processing queue.
     * This includes the number of unverified blocks,
     * the number of verified blocks, and the total size of the queue.
     * This is used for monitoring the blockchain processing queue
     * and is updated periodically.
     * * The unverified size indicates how many blocks are waiting to be verified.
     * * The verified size indicates how many blocks have been verified
     * but not yet applied to the blockchain.
     * * The total size is the sum of unverified and verified sizes.
     */
    class QueueStatus {
        public final int unverifiedSize;
        public final int verifiedSize;
        public final int totalSize;
        public final int cacheFullness;

        public QueueStatus(int unverifiedSize, int verifiedSize, int totalSize, int cacheFullness) {
            this.unverifiedSize = unverifiedSize;
            this.verifiedSize = verifiedSize;
            this.totalSize = totalSize;
            this.cacheFullness = cacheFullness;

        }
    }

    /**
     * Holds performance statistics for the last processed block.
     * This includes the total processing time, database time,
     * application transaction time, and the block itself.
     * This is used for performance monitoring and debugging.
     * The times are in milliseconds.
     * This class is immutable and thread-safe.
     * It is created at the end of block processing and can be used
     * to analyze the performance of the blockchain processing.
     * It is also used to update the GUI with performance metrics.
     */
    class PerformanceStats {
        public final long totalTimeMs;
        public final long validationTimeMs;
        public final long txLoopTimeMs;
        public final long housekeepingTimeMs;
        public final long txApplyTimeMs;
        public final long atTimeMs;
        public final long subscriptionTimeMs;
        public final long blockApplyTimeMs;
        public final long commitTimeMs;
        public final long miscTimeMs;
        public final int height;
        public final int allTransactionCount;
        public final int systemTransactionCount;
        public final int atCount;
        public final int payloadSize;
        public final int maxPayloadSize;

        public PerformanceStats(long totalTimeMs, long validationTimeMs, long txLoopTimeMs,
                long housekeepingTimeMs, long txApplyTimeMs, long atTimeMs,
                long subscriptionTimeMs, long blockApplyTimeMs, long commitTimeMs,
                long miscTimeMs, int height,
                int allTransactionCount, int systemTransactionCount, int atCount,
                int payloadSize, int maxPayloadSize) {
            this.totalTimeMs = totalTimeMs;
            this.validationTimeMs = validationTimeMs;
            this.txLoopTimeMs = txLoopTimeMs;
            this.housekeepingTimeMs = housekeepingTimeMs;
            this.txApplyTimeMs = txApplyTimeMs;
            this.atTimeMs = atTimeMs;
            this.subscriptionTimeMs = subscriptionTimeMs;
            this.blockApplyTimeMs = blockApplyTimeMs;
            this.commitTimeMs = commitTimeMs;
            this.miscTimeMs = miscTimeMs;
            this.height = height;
            this.allTransactionCount = allTransactionCount;
            this.systemTransactionCount = systemTransactionCount;
            this.atCount = atCount;
            this.payloadSize = payloadSize;
            this.maxPayloadSize = maxPayloadSize;
        }

    }

    class ForkCacheStats {
        public final int forkCacheSize;
        public final int restoreBlocksCount;

        public ForkCacheStats(int forkCacheSize, int restoreBlocksCount) {
            this.forkCacheSize = forkCacheSize;
            this.restoreBlocksCount = restoreBlocksCount;
        }
    }

    class TrimStats {
        public final int currentHeight;
        public final int targetHeight;

        public TrimStats(int currentHeight, int targetHeight) {
            this.currentHeight = currentHeight;
            this.targetHeight = targetHeight;
        }
    }

    class PruneStats {
        public final int currentHeight;
        public final int targetHeight;

        public PruneStats(int currentHeight, int targetHeight) {
            this.currentHeight = currentHeight;
            this.targetHeight = targetHeight;
        }
    }

    enum Event {
        BLOCK_PUSHED, BLOCK_AUTO_POPPED, BLOCK_MANUAL_POPPED, BLOCK_GENERATED, BLOCK_SCANNED,
        RESCAN_BEGIN, RESCAN_END,
        BEFORE_BLOCK_ACCEPT,
        BEFORE_BLOCK_APPLY, AFTER_BLOCK_APPLY, DATABASE_CONSISTENCY_UPDATE,
        CONSISTENCY_RESOLUTION_STARTED, CONSISTENCY_RESOLUTION_FINISHED,
        PEERS_UPDATED, NET_VOLUME_CHANGED, QUEUE_STATUS_CHANGED, FORK_CACHE_CHANGED, PERFORMANCE_STATS_UPDATED,
        SYNC_STATE_CHANGED,
        TRIM_START, TRIM_END,
        PRUNE_START, PRUNE_END
    }

    enum ArchivalMode {
        /** Keep all data, no blocks or transactions are deleted. */
        ARCHIVE,
        /** Clear derived tables, blocks and transactions remain. */
        TRIM,
        /** Blocks are deleted beyond the rollback limit. */
        PRUNE
    }

    enum PeerMetricEvent {
        METRIC
    }

    /**
     * Represents the consistency state of the database.
     * The state is checked periodically and on demand.
     */
    enum ConsistencyState {
        /** The state has not been checked yet. */
        UNDEFINED,
        /**
         * The database is consistent (total supply matches total effective balances).
         */
        CONSISTENT,
        /** The database is inconsistent. */
        INCONSISTENT
    }

    /**
     * Tracks the lifecycle of the database consistency resolution process.
     * This state machine ensures that only one resolution process runs at a time
     * and provides feedback on its outcome.
     */
    enum ResolutionState {
        /** Default state. No resolution process is running. */
        IDLE,
        /**
         * A resolution process is currently active (popping off blocks). New requests
         * are ignored.
         */
        ACTIVE,
        /**
         * The last resolution process finished successfully, and the database is now
         * consistent.
         */
        SUCCESS,
        /**
         * The last resolution process failed to restore consistency. Auto-resolve will
         * not retry until manually triggered.
         */
        FAILED
    }

    /**
     * Represents the state of a pop-off process (removing blocks from the
     * blockchain).
     */
    enum PopOffState {
        /** No pop-off is currently in progress. */
        IDLE,
        /** A pop-off process is currently active. */
        ACTIVE
    }

    Peer getLastBlockchainFeeder();

    int getLastBlockchainFeederHeight();

    int getForkCacheSize();

    /**
     * Returns the number of blocks currently held in the temporary rollback buffer
     * during a fork resolution process.
     */
    int getRestoreBlocksCount();

    int getManualPopOffBlocksCount();

    int getManualLastPopOffHeight();

    int getAutoPopOffBlocksCount();

    int getAutoLastPopOffHeight();

    int getBeforeRollbackHeight();

    int getMinRollbackHeight();

    boolean isScanning();

    boolean isTrimming();

    boolean isPruning();

    AtomicInteger getCurrentTrimHeight();

    AtomicInteger getCurrentPruneHeight();

    int getEstimatedTrimHeight();

    String getCurrentlyTrimmingTable();

    QueueStatus getQueueStatus();

    Collection<Peer> getAllPeers();

    PerformanceStats getPerformanceStats();

    long getAccumulatedSyncTimeMs();

    long getAccumulatedSyncInProgressTimeMs();

    long getUploadedVolume();

    long getDownloadedVolume();

    int checkDatabaseStateRequest();

    long getTotalMined();

    long getTotalEffectiveBalance();

    long getLastCheckTotalMined();

    long getLastCheckTotalEffectiveBalance();

    int getLastCheckHeight();

    ConsistencyState getConsistencyState();

    ArchivalMode getArchivalMode();

    ResolutionState getResolutionState();

    PopOffState getManualPopOffState();

    PopOffState getAutoPopOffState();

    String getDbType();

    String getDbVersion();

    void processPeerBlock(JsonObject request, Peer peer) throws SignumException;

    void fullReset();

    void setGetMoreBlocksPause(boolean getMoreBlocksPause);

    void setBlockImporterPause(boolean blockImporterPause);

    boolean isSkipDbCheckOnManualPopOff();

    void setSkipDbCheckOnManualPopOff(boolean skip);

    void setSyncPaused(boolean paused);

    void generateBlock(String secretPhrase, byte[] publicKey, Long nonce)
            throws BlockNotAcceptedException;

    void shutdown();

    List<Block> popOffTo(int height);

    void popOff(int count);

    void onQueueStatusUpdated(QueueStatus newStatus);

    void scheduleTrim(Block block);

    void manualResolveDatabaseConsistency();

    void autoResolveDatabaseConsistency();

    void addForkCacheStatsListener(Listener<ForkCacheStats> listener);

    void removeForkCacheStatsListener(Listener<ForkCacheStats> listener);

    void addPeerMetricListener(Listener<PeerMetric> listener);

    void removePeerMetricListener(Listener<PeerMetric> listener);

    void notifyPeerMetric(PeerMetric metric);

    void addPerformanceStatsListener(Listener<PerformanceStats> listener);

    void removePerformanceStatsListener(Listener<PerformanceStats> listener);

    void addQueueStatusListener(Listener<QueueStatus> listener);

    void removeQueueStatusListener(Listener<QueueStatus> listener);

    void addSyncStateListener(Listener<Boolean> listener);

    void removeSyncStateListener(Listener<Boolean> listener);

    void addTrimListener(Listener<TrimStats> listener, Event eventType);

    void removeTrimListener(Listener<TrimStats> listener, Event eventType);

    void addPruneListener(Listener<PruneStats> listener, Event eventType);

    void removePruneListener(Listener<PruneStats> listener, Event eventType);

    class BlockNotAcceptedException extends SignumException {

        public BlockNotAcceptedException(String message) {
            super(message);
        }

        /**
         * Indicates whether the validation failure is linked to the local node's state.
         *
         * <p>
         * This method allows the blockchain processor to differentiate between two
         * types of errors during fork processing:
         * </p>
         * <ul>
         * <li><b>Objective Errors (false):</b> Violations that are independent of the
         * local database,
         * such as invalid cryptographic signatures or protocol version mismatches.
         * These typically
         * originate from a faulty or malicious peer, leading to a blacklist
         * action.</li>
         *
         * <li><b>Subjective/State Errors (true):</b> Failures that depend on local
         * data, such as
         * insufficient account balances, incorrect PoC commitments, or structural gaps
         * in the local
         * ledger. These indicate that the local node's state is likely out of sync or
         * inconsistent
         * with the network consensus, triggering an automated recovery (rollback)
         * instead of
         * blacklisting the peer.</li>
         * </ul>
         *
         * @return {@code true} if the rejection is due to local state inconsistency;
         *         {@code false} otherwise.
         */
        public boolean isStateRelated() {
            return false;
        }
    }

    class TransactionNotAcceptedException extends BlockNotAcceptedException {

        private final transient Transaction transaction;

        public TransactionNotAcceptedException(String message, Transaction transaction) {
            super(message + " transaction: " + JSON.toJsonString(transaction.getJsonObject()));
            this.transaction = transaction;
        }

        Transaction getTransaction() {
            return transaction;
        }

        @Override
        public boolean isStateRelated() {
            return true;
        }
    }

    class GenerationSignatureException extends BlockNotAcceptedException {
        public GenerationSignatureException(String message) {
            super(message);
        }

        @Override
        public boolean isStateRelated() {
            return true;
        }
    }

    class ConsensusMismatchException extends BlockNotAcceptedException {
        public ConsensusMismatchException(String message) {
            super(message);
        }

        @Override
        public boolean isStateRelated() {
            return true;
        }
    }

    class BlockOutOfOrderException extends BlockNotAcceptedException {

        public BlockOutOfOrderException(String message) {
            super(message);
        }

        @Override
        public boolean isStateRelated() {
            return true;
        }

    }

}
