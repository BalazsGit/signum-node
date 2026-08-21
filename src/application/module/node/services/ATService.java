package application.module.node.services;

import application.module.node.at.AT;
import application.module.node.at.AtBlock;
import application.module.node.at.ATProcessingContext;
import application.module.node.util.CollectionWithIndex;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * Service interface for AT (Automated Transaction) operations.
 * <p>
 * Provides both CRUD operations and AT processing/orchestration methods
 * that were previously static in {@link application.module.node.at.AtController}.
 * </p>
 *
 * @since 4.0
 */
public interface ATService {

    // -------------------------------------------------------------------------
    // CRUD operations (original)
    // -------------------------------------------------------------------------

    Collection<Long> getAllATIds(Long codeHashId);

    CollectionWithIndex<Long> getATsIssuedBy(Long accountId, Long codeHashId, int from, int to);

    AT getAT(Long atId);

    AT getAT(Long atId, int height);

    // -------------------------------------------------------------------------
    // AT Processing / Orchestration (migrated from AtController)
    // -------------------------------------------------------------------------

    /**
     * Validates ATs contained in a block.
     *
     * @param ctx         the AT processing context
     * @param blockATs    the raw AT bytes from the block
     * @param blockHeight the height of the block being processed
     * @param generatorId the generator account ID
     * @return an {@link AtBlock} describing fees, amounts and resulting bytes
     * @throws application.module.node.at.AtException if validation fails
     */
    AtBlock validateATs(ATProcessingContext ctx, byte[] blockATs, int blockHeight, long generatorId)
            throws application.module.node.at.AtException;

    /**
     * Processes ATs for a newly forged (current) block.
     *
     * @param ctx         the AT processing context
     * @param freePayload the remaining payload size available in the block
     * @param blockHeight the height of the block being forged
     * @param generatorId the generator account ID
     * @param indirectsCount initial indirect transactions count
     * @return an {@link AtBlock} describing fees, amounts and resulting bytes
     */
    AtBlock getCurrentBlockATs(ATProcessingContext ctx, int freePayload, int blockHeight,
            long generatorId, int indirectsCount);

    /**
     * Extracts AT identifiers and their MD5 checksums from block AT bytes.
     *
     * @param blockATs the raw AT bytes from a block
     * @return a map from AT id to its MD5 hash
     * @throws application.module.node.at.AtException if parsing fails
     */
    LinkedHashMap<Long, byte[]> getATsFromBlock(byte[] blockATs)
            throws application.module.node.at.AtException;

    // -------------------------------------------------------------------------
    // Convenience overloads (use stored processing context inside ATServiceImpl)
    // Called from BlockchainProcessorImpl without explicitly building context
    // -------------------------------------------------------------------------

    /**
     * Validates ATs contained in a block, using the service's internal context.
     *
     * @param blockATs    the raw AT bytes from the block
     * @param blockHeight the height of the block being processed
     * @param generatorId the generator account ID
     * @return an {@link AtBlock} describing fees, amounts and resulting bytes
     * @throws application.module.node.at.AtException if validation fails
     */
    AtBlock validateATs(byte[] blockATs, int blockHeight, long generatorId)
            throws application.module.node.at.AtException;

     /**
      * Processes ATs for a newly forged (current) block, using the service's internal context.
      *
      * @param freePayload the remaining payload size available in the block
      * @param blockHeight the height of the block being forged
      * @param generatorId the generator account ID
      * @param indirectsCount initial indirect transactions count
      * @return an {@link AtBlock} describing fees, amounts and resulting bytes
      */
     AtBlock getCurrentBlockATs(int freePayload, int blockHeight,
             long generatorId, int indirectsCount);

     /**
      * Clears all pending AT state (fees, transactions, map updates) for the given block/generator.
      *
      * @param blockHeight the block height
      * @param generatorId the generator account ID
      */
     void clearPending(int blockHeight, long generatorId);
 }
