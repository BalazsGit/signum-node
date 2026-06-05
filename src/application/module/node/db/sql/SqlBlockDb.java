package application.module.node.db.sql;

import application.module.node.Block;
import application.module.node.Signum;
import application.module.node.SignumException;
import application.module.node.db.BlockDb;
import application.module.node.schema.tables.records.BlockRecord;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Optional;

import static application.module.node.schema.Tables.BLOCK;

public class SqlBlockDb implements BlockDb {

    private static final Logger logger = LoggerFactory.getLogger(SqlBlockDb.class);

    public Block findBlock(long blockId) {
        return Db.fetchWithDSLContext(ctx -> {
            try {
                BlockRecord r = ctx.selectFrom(BLOCK).where(BLOCK.ID.eq(blockId)).fetchAny();
                return r == null ? null : loadBlock(r);
            } catch (SignumException.ValidationException e) {
                throw new RuntimeException("Block already in database, id = " + blockId + ", does not pass validation!",
                        e);
            }
        });
    }

    @Override
    public Block findPrunedBlock(long blockId) {
        return null;
    }

    public boolean hasBlock(long blockId) {
        return Db.fetchWithDSLContext(ctx -> {
            return ctx.fetchExists(ctx.selectOne().from(BLOCK).where(BLOCK.ID.eq(blockId)));
        });
    }

    public long findBlockIdAtHeight(int height) {
        return Db.fetchWithDSLContext(ctx -> {
            Long id = ctx.select(BLOCK.ID).from(BLOCK).where(BLOCK.HEIGHT.eq(height)).fetchOne(BLOCK.ID);
            if (id == null) {
                throw new RuntimeException("Block at height " + height + " not found in database!");
            }
            return id;
        });
    }

    public Block findBlockAtHeight(int height) {
        return Db.fetchWithDSLContext(ctx -> {
            try {
                BlockRecord r = ctx.selectFrom(BLOCK).where(BLOCK.HEIGHT.eq(height)).fetchAny();
                Block block = r != null ? loadBlock(r) : null;
                if (block == null) {
                    throw new RuntimeException("Block at height " + height + " not found in database!");
                }
                return block;
            } catch (SignumException.ValidationException e) {
                throw new RuntimeException(e.toString(), e);
            }
        });
    }

    public Block findLastBlock() {
        return Db.fetchWithDSLContext(ctx -> {
            try {

                // avoid table scans through ordering - using indexed columns for direct lookups
                SelectConditionStep<BlockRecord> query = ctx.selectFrom(BLOCK)
                        .where(BLOCK.DB_ID.eq(
                                ctx.select(DSL.max(BLOCK.DB_ID)).from(BLOCK)));

                return loadBlock(query.fetchAny());
                // old statement
                // return
                // loadBlock(ctx.selectFrom(BLOCK).orderBy(BLOCK.DB_ID.desc()).limit(1).fetchAny());
            } catch (SignumException.ValidationException e) {
                throw new RuntimeException("Last block already in database does not pass validation!", e);
            }
        });
    }

    public Block findLastBlock(int timestamp) {
        return Db.fetchWithDSLContext(ctx -> {
            try {
                return loadBlock(ctx.selectFrom(BLOCK).where(BLOCK.TIMESTAMP.lessOrEqual(timestamp))
                        .orderBy(BLOCK.DB_ID.desc()).limit(1).fetchAny());
            } catch (SignumException.ValidationException e) {
                throw new RuntimeException(
                        "Block already in database at timestamp " + timestamp + " does not pass validation!", e);
            }
        });
    }

    public Block loadBlock(Record r) throws SignumException.ValidationException {
        if (r == null) {
            return null;
        }

        int version = r.get(BLOCK.VERSION);
        int timestamp = r.get(BLOCK.TIMESTAMP);
        long previousBlockId = Optional.ofNullable(r.get(BLOCK.PREVIOUS_BLOCK_ID)).orElse(0L);
        long totalAmountNQT = r.field(BLOCK.TOTAL_AMOUNT) != null ? r.get(BLOCK.TOTAL_AMOUNT) : 0L;
        long totalFeeNQT = r.field(BLOCK.TOTAL_FEE) != null ? r.get(BLOCK.TOTAL_FEE) : 0L;
        long totalFeeCashBackNQT = r.field(BLOCK.TOTAL_FEE_CASH_BACK) != null ? r.get(BLOCK.TOTAL_FEE_CASH_BACK) : 0L;
        long totalFeeBurntNQT = r.field(BLOCK.TOTAL_FEE_BURNT) != null ? r.get(BLOCK.TOTAL_FEE_BURNT) : 0L;
        int payloadLength = r.field(BLOCK.PAYLOAD_LENGTH) != null ? r.get(BLOCK.PAYLOAD_LENGTH) : 0;
        byte[] generatorPublicKey = r.field(BLOCK.GENERATOR_PUBLIC_KEY) != null ? r.get(BLOCK.GENERATOR_PUBLIC_KEY)
                : new byte[32];
        byte[] previousBlockHash = r.field(BLOCK.PREVIOUS_BLOCK_HASH) != null ? r.get(BLOCK.PREVIOUS_BLOCK_HASH)
                : new byte[32];
        BigInteger cumulativeDifficulty = new BigInteger(r.get(BLOCK.CUMULATIVE_DIFFICULTY));
        long baseTarget = r.get(BLOCK.BASE_TARGET);
        long nextBlockId = Optional.ofNullable(r.get(BLOCK.NEXT_BLOCK_ID)).orElse(0L);
        int height = r.get(BLOCK.HEIGHT);
        byte[] generationSignature = r.get(BLOCK.GENERATION_SIGNATURE);
        byte[] blockSignature = r.get(BLOCK.BLOCK_SIGNATURE);
        byte[] payloadHash = r.get(BLOCK.PAYLOAD_HASH);
        long id = r.get(BLOCK.ID);
        long nonce = r.field(BLOCK.NONCE) != null ? r.get(BLOCK.NONCE) : 0L;
        byte[] blockATs = r.get(BLOCK.ATS);

        return new Block(version, timestamp, previousBlockId, totalAmountNQT, totalFeeNQT,
                totalFeeCashBackNQT, totalFeeBurntNQT,
                payloadLength, payloadHash,
                generatorPublicKey, generationSignature, blockSignature, previousBlockHash,
                cumulativeDifficulty, baseTarget, nextBlockId, height, id, nonce, blockATs);
    }

    public void saveBlock(DSLContext ctx, Block block) {
        ctx.insertInto(BLOCK, BLOCK.ID, BLOCK.VERSION, BLOCK.TIMESTAMP, BLOCK.PREVIOUS_BLOCK_ID,
                BLOCK.TOTAL_AMOUNT, BLOCK.TOTAL_FEE, BLOCK.TOTAL_FEE_CASH_BACK, BLOCK.TOTAL_FEE_BURNT,
                BLOCK.PAYLOAD_LENGTH, BLOCK.GENERATOR_PUBLIC_KEY,
                BLOCK.PREVIOUS_BLOCK_HASH, BLOCK.CUMULATIVE_DIFFICULTY, BLOCK.BASE_TARGET, BLOCK.HEIGHT,
                BLOCK.GENERATION_SIGNATURE, BLOCK.BLOCK_SIGNATURE, BLOCK.PAYLOAD_HASH, BLOCK.GENERATOR_ID,
                BLOCK.NONCE, BLOCK.ATS)
                .values(block.getId(), block.getVersion(), block.getTimestamp(),
                        block.getPreviousBlockId() == 0 ? null : block.getPreviousBlockId(),
                        block.getTotalAmountNqt(), block.getTotalFeeNqt(),
                        block.getTotalFeeCashBackNqt(), block.getTotalFeeBurntNqt(),
                        block.getPayloadLength(),
                        block.getGeneratorPublicKey(), block.getPreviousBlockHash(),
                        block.getCumulativeDifficulty().toByteArray(), block.getBaseTarget(), block.getHeight(),
                        block.getGenerationSignature(), block.getBlockSignature(), block.getPayloadHash(),
                        block.getGeneratorId(), block.getNonce(), block.getBlockAts())
                .execute();

        Signum.getDbs().getTransactionDb().saveTransactions(block.getTransactions());

        if (block.getPreviousBlockId() != 0) {
            ctx.update(BLOCK)
                    .set(BLOCK.NEXT_BLOCK_ID, block.getId())
                    .where(BLOCK.ID.eq(block.getPreviousBlockId()))
                    .execute();
        }
    }

    // relying on cascade triggers in the database to delete the transactions for
    // all deleted blocks
    @Override
    public void deleteBlocksFrom(long blockId) {
        if (!Db.isInTransaction()) {
            try {
                Db.beginTransaction();
                deleteBlocksFrom(blockId);
                Db.commitTransaction();
            } catch (Exception e) {
                Db.rollbackTransaction();
                throw e;
            } finally {
                Db.endTransaction();
            }
            return;
        }
        Db.useDSLContext(ctx -> {
            SelectQuery<Record> blockHeightQuery = ctx.selectQuery();
            blockHeightQuery.addFrom(BLOCK);
            blockHeightQuery.addSelect(BLOCK.HEIGHT);
            blockHeightQuery.addConditions(BLOCK.ID.eq(blockId));
            Integer blockHeight = blockHeightQuery.fetchOne().get(BLOCK.HEIGHT);

            if (blockHeight != null) {
                DeleteQuery deleteQuery = ctx.deleteQuery(BLOCK);
                deleteQuery.addConditions(BLOCK.HEIGHT.ge(blockHeight));
                deleteQuery.execute();
            }
        });
    }

    public void deleteAll(boolean force) {
        if (!Db.isInTransaction()) {
            try {
                Db.beginTransaction();
                deleteAll(force);
                Db.commitTransaction();
            } catch (Exception e) {
                Db.rollbackTransaction();
                throw e;
            }
            Db.endTransaction();
            return;
        }
        logger.info("Deleting blockchain...");
        Db.clean();
    }

    @Override
    public void optimize() {
        Db.optimizeTable(BLOCK.getName());
    }
}
