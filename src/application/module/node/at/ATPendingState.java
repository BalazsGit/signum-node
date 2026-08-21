package application.module.node.at;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Instance-scoped holder for AT pending state (fees, transactions, map entries).
 * <p>
 * Previously these were {@code private static final} maps in {@link AT}, making them
 * JVM-wide shared mutable state. Under multi-node operation, two nodes at the same
 * (blockHeight + generatorId) key would collide and corrupt each other's pending AT
 * state. This class eliminates that by scoping the state to a single
 * {@link ATProcessingContext} (one per node instance).
 * </p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ATPendingState state = ctx.getPendingState();
 * state.addPendingFee(atId, fee, blockHeight, generatorId);
 * state.addPendingTransaction(tx, blockHeight, generatorId);
 * state.addMapUpdates(entries, blockHeight, generatorId);
 * state.clearPending(blockHeight, generatorId);
 * }</pre>
 *
 * @since 4.0
 */
public final class ATPendingState {

    private final LinkedHashMap<Long, LinkedHashMap<Long, Long>> pendingFeesMap = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<AtTransaction>> pendingTransactionsMap = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<AT.AtMapEntry>> pendingEntryUpdatesMap = new LinkedHashMap<>();

    /**
     * Creates a new pending state instance.
     * <p>Each node instance should have its own ATPendingState via ATProcessingContext.</p>
     */
    public ATPendingState() {
        // No external dependencies needed.
    }

    /**
     * Returns the compound key for a (blockHeight, generatorId) pair.
     */
    private static long hash(int blockHeight, long generatorId) {
        return blockHeight + generatorId;
    }

    // -------------------------------------------------------------------------
    // Pending Fees
    // -------------------------------------------------------------------------

    /**
     * Records a pending AT fee for the given AT id.
     *
     * @param id          the AT account ID
     * @param fee         the fee in NQT
     * @param blockHeight the current block height
     * @param generatorId the block generator's account ID
     */
    public void addPendingFee(long id, long fee, int blockHeight, long generatorId) {
        long key = hash(blockHeight, generatorId);
        LinkedHashMap<Long, Long> pendingFees = pendingFeesMap.get(key);
        if (pendingFees == null) {
            pendingFees = new LinkedHashMap<>();
            pendingFeesMap.put(key, pendingFees);
        }
        pendingFees.put(id, fee);
    }

    /**
     * Returns the pending fees for the given block/generator, or null if none.
     *
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     * @return a map of AT id to fee, or null
     */
    public LinkedHashMap<Long, Long> getPendingFees(int blockHeight, long generatorId) {
        return pendingFeesMap.get(hash(blockHeight, generatorId));
    }

    // -------------------------------------------------------------------------
    // Pending Transactions
    // -------------------------------------------------------------------------

    /**
     * Records a pending AT transaction.
     *
     * @param atTransaction the AT transaction to record
     * @param blockHeight   the current block height
     * @param generatorId   the block generator's account ID
     */
    public void addPendingTransaction(AtTransaction atTransaction, int blockHeight, long generatorId) {
        long key = hash(blockHeight, generatorId);
        List<AtTransaction> pendingTransactions = pendingTransactionsMap.get(key);
        if (pendingTransactions == null) {
            pendingTransactions = new ArrayList<>();
            pendingTransactionsMap.put(key, pendingTransactions);
        }
        pendingTransactions.add(atTransaction);
    }

    /**
     * Returns the list of pending AT transactions for the given block/generator, or null if none.
     *
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     * @return list of pending AT transactions, or null
     */
    public List<AtTransaction> getPendingTransactions(int blockHeight, long generatorId) {
        return pendingTransactionsMap.get(hash(blockHeight, generatorId));
    }

    /**
     * Checks whether a pending transaction exists for the given recipient.
     *
     * @param recipientId the recipient byte array to look up
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     * @return true if a pending transaction matches the recipient
     */
    public boolean findPendingTransaction(byte[] recipientId, int blockHeight, long generatorId) {
        long key = hash(blockHeight, generatorId);
        List<AtTransaction> transactions = pendingTransactionsMap.get(key);
        if (transactions == null) {
            return false;
        }
        for (AtTransaction tx : transactions) {
            if (Arrays.equals(recipientId, tx.getRecipientId())) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Pending Map Entry Updates
    // -------------------------------------------------------------------------

    /**
     * Records pending AT map entry updates.
     *
     * @param entries     the collection of map entries (null is tolerated)
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     */
    public void addMapUpdates(Collection<AT.AtMapEntry> entries, int blockHeight, long generatorId) {
        if (entries == null) {
            return;
        }
        long key = hash(blockHeight, generatorId);
        List<AT.AtMapEntry> pendingUpdates = pendingEntryUpdatesMap.get(key);
        if (pendingUpdates == null) {
            pendingUpdates = new ArrayList<>();
            pendingEntryUpdatesMap.put(key, pendingUpdates);
        }
        pendingUpdates.addAll(entries);
    }

    /**
     * Returns and clears the pending map entry updates for the given block/generator.
     *
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     * @return list of pending map entries (empty list if none)
     */
    public List<AT.AtMapEntry> getAndClearMapUpdates(int blockHeight, long generatorId) {
        long key = hash(blockHeight, generatorId);
        List<AT.AtMapEntry> updates = pendingEntryUpdatesMap.get(key);
        if (updates != null) {
            pendingEntryUpdatesMap.remove(key);
            return updates;
        }
        return List.of();
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /**
     * Clears all pending state (fees, transactions, map updates) for the given block/generator.
     * <p>
     * Called at the start of AT processing for a new block to discard any stale
     * pending state from a previous (possibly rolled-back) block.
     * </p>
     *
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     */
    public void clearPending(int blockHeight, long generatorId) {
        long key = hash(blockHeight, generatorId);
        pendingFeesMap.remove(key);
        pendingTransactionsMap.remove(key);
        pendingEntryUpdatesMap.remove(key);
    }

    /**
     * Removes pending fees and transactions after they have been applied.
     * <p>
     * This is a more targeted cleanup that only removes the fees and transactions
     * (not map updates) after the block has been successfully processed.
     * </p>
     *
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     */
    public void removeFeesAndTransactions(int blockHeight, long generatorId) {
        long key = hash(blockHeight, generatorId);
        pendingFeesMap.remove(key);
        pendingTransactionsMap.remove(key);
    }

    /**
     * Clears all pending state across all blocks.
     * <p>
     * This is a global reset, useful during shutdown or when re-initializing the AT module.
     * </p>
     */
    public void clearAll() {
        pendingFeesMap.clear();
        pendingTransactionsMap.clear();
        pendingEntryUpdatesMap.clear();
    }
}