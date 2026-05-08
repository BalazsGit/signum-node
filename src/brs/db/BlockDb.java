package brs.db;

import brs.Block;
import brs.SignumException;
import org.jooq.DSLContext;
import org.jooq.Record;

public interface BlockDb extends Table {
    Block findBlock(long blockId);

    Block findPrunedBlock(long blockId);

    boolean hasBlock(long blockId);

    long findBlockIdAtHeight(int height);

    Block findBlockAtHeight(int height);

    Block findLastBlock();

    Block findLastBlock(int timestamp);

    Block loadBlock(Record r) throws SignumException.ValidationException;

    void saveBlock(DSLContext ctx, Block block);

    // relying on cascade triggers in the database to delete the transactions for
    // all deleted blocks
    void deleteBlocksFrom(long blockId);

    void deleteAll(boolean force);
}
