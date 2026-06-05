package application.module.node.db.store;

import application.module.node.Account;
import application.module.node.Block;
import application.module.node.Transaction;
import application.module.node.schema.tables.records.BlockRecord;
import application.module.node.schema.tables.records.TransactionRecord;
import org.jooq.DSLContext;
import org.jooq.Result;

import java.util.Collection;

/**
 * Store for both BlockchainImpl and BlockchainProcessorImpl
 */

public interface BlockchainStore {

    int[] totalTransactions = { 0 };

    int[] totalDeletedTransactions = { 0 };

    public int getTotalTransactions();

    public void setTotalTransactions(int totalTransactions);

    public int getTotalDeletedTransactions();

    public void setTotalDeletedTransactions(int totalDeletedTransactions);

    Collection<Block> getBlocks(int from, int to);

    Collection<Block> getBlocks(Account account, int timestamp, int from, int to);

    int getBlocksCount(long accountId, int from, int to);

    Collection<Block> getBlocks(Result<BlockRecord> blockRecords);

    Collection<Long> getBlockIdsAfter(long blockId, int limit);

    Collection<Block> getBlocksAfter(long blockId, int limit);

    int getTransactionCount();

    Collection<Transaction> getAllTransactions();

    long getAtBurnTotal();

    Collection<Transaction> getTransactions(Account account, int numberOfConfirmations, byte type, byte subtype,
            int blockTimestamp, int from, int to, boolean includeIndirectIncoming);

    Collection<Transaction> getTransactions(Long senderId, Long recipientId, int numberOfConfirmations,
            byte type, byte subtype, int blockTimestamp, int from, int to,
            boolean includeIndirectIncoming, boolean bidirectional);

    Collection<Transaction> getTransactions(long senderId, byte type, byte subtypeStart, byte subtypeEnd, int from,
            int to);

    int countTransactions(byte type, byte subtypeStart, byte subtypeEnd);

    Collection<Transaction> getTransactionsWithFullHashReference(String fullHash, int numberOfConfirmations, byte type,
            byte subtypeStart, byte subtypeEnd, int from, int to);

    Collection<Long> getTransactionIds(Long sender, Long recipient, int numberOfConfirmations, byte type, byte subtype,
            int blockTimestamp, int from, int to, boolean includeIndirectIncoming);

    Collection<Transaction> getTransactions(DSLContext ctx, Result<TransactionRecord> rs);

    void addBlock(Block block);

    Collection<Block> getLatestBlocks(int amountBlocks);

    long getCommittedAmount(long accountId, int height, int endHeight, Transaction skipTransaction);

    void prune(int fromHeight, int toHeight);

    String getProperty(String key);

    void setProperty(String key, String value);

    void deleteProperty(String key);
}
