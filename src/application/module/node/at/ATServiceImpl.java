package application.module.node.at;

import application.module.node.Account;
import application.module.node.Blockchain;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.crypto.Crypto;
import application.module.node.db.store.AccountStore;
import application.module.node.db.store.AssetStore;
import application.module.node.db.store.ATStore;
import application.module.node.db.store.IndirectIncomingStore;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.AccountService;
import application.module.node.services.ATService;
import application.module.node.TransactionType;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.*;

/**
 * Service implementation for AT (Automated Transaction) operations.
 * <p>
 * Placed in the {@code application.module.node.at} package so it has access to
 * package-private VM internals ({@link AtMachineProcessor}, {@link AtBlock},
 * {@link AtMachineState#MachineState}). Orchestrates AT execution by holding an
 * {@link ATProcessingContext} that provides all dependencies without relying on
 * static {@code Signum.getXxx()} accessors.
 * </p>
 *
 * @since 4.0
 */
public class ATServiceImpl implements ATService {

    private static final Logger logger = LoggerFactory.getLogger(ATServiceImpl.class);

    private final ATStore atStore;
    private final ATProcessingContext processingContext;

    /**
     * Creates a minimal AT service that only supports CRUD operations.
     * <p>
     * Processing methods (validateATs, getCurrentBlockATs, getATsFromBlock)
     * will throw {@link IllegalStateException} since no context is available.
     * Use the full constructor for block-processing scenarios.
     *
     * @param atStore the AT data store
     */
    public ATServiceImpl(ATStore atStore) {
        this(atStore, null);
    }

    /**
     * Creates a new AT service implementation with the given processing context.
     *
     * @param atStore           the AT data store
     * @param processingContext the full AT processing context
     */
    public ATServiceImpl(ATStore atStore, ATProcessingContext processingContext) {
        this.atStore = atStore;
        this.processingContext = processingContext;
    }

    // Convenience constructor that builds the context from individual dependencies.
    public ATServiceImpl(
            ATStore atStore,
            AtConstants atConstants,
            ATProcessorCache processorCache,
            PropertyService propertyService,
            FluxCapacitor fluxCapacitor,
            Blockchain blockchain,
            AccountStore accountStore,
            AccountService accountService,
            AssetExchange assetExchange,
            IndirectIncomingStore indirectIncomingStore,
            AssetStore assetStore) {
        this(
                atStore,
                new ATProcessingContext(
                        atConstants,
                        processorCache,
                        propertyService,
                        fluxCapacitor,
                        blockchain,
                        atStore,
                        accountStore,
                        accountService,
                        assetExchange,
                        indirectIncomingStore,
                        assetStore));
    }

    // -------------------------------------------------------------------------
    // CRUD operations (delegate to ATStore)
    // -------------------------------------------------------------------------

    @Override
    public Collection<Long> getAllATIds(Long codeHashId) {
        return atStore.getAllATIds(codeHashId);
    }

    @Override
    public CollectionWithIndex<Long> getATsIssuedBy(Long accountId, Long codeHashId, int from, int to) {
        return new CollectionWithIndex<>(atStore.getATsIssuedBy(accountId, codeHashId, from, to), from, to);
    }

    @Override
    public AT getAT(Long id, int height) {
        return atStore.getAT(id, height);
    }

    @Override
    public AT getAT(Long id) {
        return atStore.getAT(id);
    }

    // -------------------------------------------------------------------------
    // AT Processing / Orchestration (migrated from AtController static methods)
    // -------------------------------------------------------------------------

    /**
     * Validates ATs contained in a block.
     */
    @Override
    public AtBlock validateATs(ATProcessingContext ctx, byte[] blockATs, int blockHeight, long generatorId)
            throws AtException {
        if (blockATs == null) {
            return new AtBlock(0, 0, null);
        }

        final FluxCapacitor fluxCapacitor = ctx.getFluxCapacitor();
        final Logger debugLog = getDebugLogger(ctx.getPropertyService());

        ATProcessorCache atProcessorCache = ctx.getProcessorCache();
        atProcessorCache.loadBlock(blockATs, blockHeight);

        List<AT> processedATs = new ArrayList<>();
        long totalFee = 0;
        MessageDigest digest = Crypto.md5();
        byte[] md5;
        long totalAmount = 0;

        AtConstants atConstants = ctx.getAtConstants();

        for (Long atIdLong : atProcessorCache.getCurrentBlockAtIds()) {
            ATProcessorCache.ATContext atContext = atProcessorCache.getATContext(atIdLong);
            if (atContext == null) continue;

            AT at = atContext.at;
            byte[] receivedMd5 = atContext.md5;

            logger.debug("Running AT {}", Convert.toUnsignedLong(atIdLong));
            try {
                at.clearLists();
                at.setHeight(blockHeight);
                at.setWaitForNumberOfBlocks(at.getSleepBetween());

                long atAccountBalance = getATAccountBalance(atIdLong, ctx.getAccountService());
                if (atAccountBalance < Convert.safeMultiply(atConstants.stepFee(at.getVersion()),
                        atConstants.apiStepMultiplier(at.getVersion()))) {
                    throw new AtException("AT has insufficient balance to run");
                }

                if (at.freezeOnSameBalance()
                        && (Convert.safeSubtract(atAccountBalance, at.getgBalance()) < at.minActivationAmount())) {
                    throw new AtException("AT should be frozen due to unchanged balance");
                }

                if (at.nextHeight() > blockHeight) {
                    throw new AtException("AT not allowed to run again yet");
                }

                at.setgBalance(atAccountBalance);
                listCode(at, ctx, true, true);
                runSteps(at, ctx);

                long fee = Convert.safeMultiply(at.getMachineState().steps,
                        atConstants.stepFee(at.getVersion()));
                if (at.getMachineState().dead) {
                    fee = Convert.safeAdd(fee, at.getgBalance());
                    at.setgBalance(0L);
                }
                at.setpBalance(at.getgBalance());

                long amount = makeTransactions(at, ctx, blockHeight, generatorId);
                if (!fluxCapacitor.getValue(FluxValues.AT_FIX_BLOCK_4, at.getHeight())) {
                    totalAmount = amount;
                } else {
                    totalAmount = Convert.safeAdd(totalAmount, amount);
                }

                totalFee = Convert.safeAdd(totalFee, fee);
                AT.addPendingFee(atIdLong, fee, blockHeight, generatorId);
                processedATs.add(at);

                md5 = digest.digest(at.getBytes());
                if (!Arrays.equals(md5, receivedMd5)) {
                    logger.error("MD5 mismatch for AT {}", Convert.toUnsignedLong(atIdLong));
                    throw new AtException("Calculated md5 and received md5 are not matching");
                }
            } catch (Exception e) {
                debugLog.debug("ATs error", e);
                throw new AtException("ATs error. Block rejected", e);
            }
            logger.debug("Finished running AT {}", Convert.toUnsignedLong(atIdLong));
        }

        processedATs.forEach(at -> at.saveState(ctx));
        AT.saveMapUpdates(ctx, blockHeight, generatorId);
        return new AtBlock(totalFee, totalAmount, new byte[1]);
    }

    /**
     * Processes ATs for a newly forged (current) block.
     */
    @Override
    public AtBlock getCurrentBlockATs(ATProcessingContext ctx, int freePayload, int blockHeight,
            long generatorId, int indirectsCount) {

        final FluxCapacitor fluxCapacitor = ctx.getFluxCapacitor();
        final Logger debugLog = getDebugLogger(ctx.getPropertyService());

        List<Long> orderedATs = AT.getOrderedATs(ctx);
        Iterator<Long> keys = orderedATs.iterator();
        List<AT> processedATs = new ArrayList<>();

        int costOfOneAT = getCostOfOneAT();
        int payload = 0;
        long totalFee = 0;
        long totalAmount = 0;

        AtConstants atConstants = ctx.getAtConstants();

        while (payload <= freePayload - costOfOneAT && keys.hasNext()) {
            Long id = keys.next();
            AT at = AT.getAT(ctx, id);
            at.addIndirectsCount(indirectsCount);

            long atAccountBalance = getATAccountBalance(id, ctx.getAccountService());
            long atStateBalance = at.getgBalance();

            if (at.freezeOnSameBalance()
                    && (Convert.safeSubtract(atAccountBalance, atStateBalance) < at.minActivationAmount())) {
                continue;
            }

            if (atAccountBalance >= Convert.safeMultiply(atConstants.stepFee(at.getVersion()),
                    atConstants.apiStepMultiplier(at.getVersion()))) {
                try {
                    at.setgBalance(atAccountBalance);
                    at.setHeight(blockHeight);
                    at.clearLists();
                    at.setWaitForNumberOfBlocks(at.getSleepBetween());
                    listCode(at, ctx, true, true);
                    runSteps(at, ctx);
                    indirectsCount = at.getIndirectsCount();

                    long fee = Convert.safeMultiply(at.getMachineState().steps,
                            atConstants.stepFee(at.getVersion()));
                    if (at.getMachineState().dead) {
                        fee = Convert.safeAdd(fee, at.getgBalance());
                        at.setgBalance(0L);
                    }
                    at.setpBalance(at.getgBalance());

                    long amount = makeTransactions(at, ctx, blockHeight, generatorId);
                    if (!fluxCapacitor.getValue(FluxValues.AT_FIX_BLOCK_4, blockHeight)) {
                        totalAmount = amount;
                    } else {
                        totalAmount = Convert.safeAdd(totalAmount, amount);
                    }

                    totalFee = Convert.safeAdd(totalFee, fee);
                    AT.addPendingFee(id, fee, blockHeight, generatorId);
                    payload += costOfOneAT;
                    processedATs.add(at);
                } catch (Exception e) {
                    debugLog.debug("Error handling AT", e);
                }
            }
        }

        byte[] bytesForBlock = getBlockATBytes(processedATs, payload);
        return new AtBlock(totalFee, totalAmount, bytesForBlock);
    }

    /**
     * Extracts AT identifiers and their MD5 checksums from block AT bytes.
     */
    @Override
    public LinkedHashMap<Long, byte[]> getATsFromBlock(byte[] blockATs) throws AtException {
        if (blockATs.length > 0 && blockATs.length % getCostOfOneAT() != 0) {
            throw new AtException("blockATs must be a multiple of cost of one AT ( " + getCostOfOneAT() + " )");
        }

        ByteBuffer b = ByteBuffer.wrap(blockATs);
        b.order(ByteOrder.LITTLE_ENDIAN);

        LinkedHashMap<Long, byte[]> ats = new LinkedHashMap<>();
        byte[] atId = new byte[AtConstants.AT_ID_SIZE];
        byte[] md5 = new byte[16];

        while (b.remaining() >= AtConstants.AT_ID_SIZE + 16) {
            b.get(atId);
            b.get(md5);
            long atIdLong = AtApiHelper.getLong(atId);
            if (ats.containsKey(atIdLong)) {
                throw new AtException("AT included in block multiple times");
            }
            ats.put(atIdLong, md5.clone());
        }

        if (b.remaining() != 0) {
            throw new AtException("bytebuffer not matching");
        }

        return ats;
    }

    // -------------------------------------------------------------------------
    // Internal helpers (migrated from AtController)
    // -------------------------------------------------------------------------

    private Logger getDebugLogger(PropertyService propertyService) {
        return propertyService.getBoolean(Props.ENABLE_AT_DEBUG_LOG) ? logger : NOPLogger.NOP_LOGGER;
    }

    private int runSteps(AtMachineState state, ATProcessingContext ctx) {
        final Logger debugLog = getDebugLogger(ctx.getPropertyService());

        state.getMachineState().running = true;
        state.getMachineState().stopped = false;
        state.getMachineState().finished = false;
        state.getMachineState().dead = false;
        state.getMachineState().steps = 0;

        AtMachineProcessor processor = new AtMachineProcessor(state, ctx,
                ctx.getPropertyService().getBoolean(Props.ENABLE_AT_DEBUG_LOG));

        state.setFreeze(false);

        AtConstants atConstants = ctx.getAtConstants();
        long stepFee = atConstants.stepFee(state.getVersion());
        int numSteps = 0;

        while (state.getMachineState().steps +
                (numSteps = processor.getNumSteps(state.getApCode().get(state.getMachineState().pc),
                        state.getIndirectsCount())) <= atConstants.maxSteps(state.getHeight())) {

            if ((state.getgBalance() < stepFee * numSteps)) {
                debugLog.debug("stopped - not enough balance");
                state.setFreeze(true);
                return 3;
            }

            state.setgBalance(state.getgBalance() - (stepFee * numSteps));
            state.getMachineState().steps += numSteps;
            int rc = processor.processOp(false, false);

            if (rc >= 0) {
                if (state.getMachineState().stopped) {
                    debugLog.debug("stopped");
                    state.getMachineState().running = false;
                    return 2;
                } else if (state.getMachineState().finished) {
                    debugLog.debug("finished");
                    state.getMachineState().running = false;
                    return 1;
                }
            } else {
                if (rc == -1)
                    debugLog.debug("error: overflow");
                else if (rc == -2)
                    debugLog.debug("error: invalid code");
                else
                    debugLog.debug("unexpected error");

                if (state.getMachineState().jumps.contains(state.getMachineState().err)) {
                    state.getMachineState().pc = state.getMachineState().err;
                } else {
                    state.getMachineState().dead = true;
                    state.getMachineState().running = false;
                    return 0;
                }
            }
        }

        return 5;
    }

    private void listCode(AtMachineState state, ATProcessingContext ctx, boolean disassembly, boolean determineJumps) {
        AtMachineProcessor machineProcessor = new AtMachineProcessor(state, ctx,
                ctx.getPropertyService().getBoolean(Props.ENABLE_AT_DEBUG_LOG));

        int opc = state.getMachineState().pc;
        int osteps = state.getMachineState().steps;

        state.getApCode().order(ByteOrder.LITTLE_ENDIAN);
        state.getApData().order(ByteOrder.LITTLE_ENDIAN);

        state.getMachineState().pc = 0;
        state.getMachineState().opc = opc;

        while (true) {
            int rc = machineProcessor.processOp(disassembly, determineJumps);
            if (rc <= 0)
                break;
            state.getMachineState().pc += rc;
        }

        state.getMachineState().steps = osteps;
        state.getMachineState().pc = opc;
    }

    private long getATAccountBalance(Long id, AccountService accountService) {
        Account.Balance atAccount = accountService.getAccountBalance(id);
        if (atAccount != null) {
            return atAccount.getBalanceNqt();
        }
        return 0;
    }

    private long makeTransactions(AT at, ATProcessingContext ctx, int blockHeight, long generatorId)
            throws AtException {
        final FluxCapacitor fluxCapacitor = ctx.getFluxCapacitor();
        long totalAmount = 0;

        List<AtTransaction> ordered = new ArrayList<>(at.getTransactions());

        ordered.sort((tx1, tx2) -> {
            if (tx1.getAssetId() == tx2.getAssetId()) {
                boolean tx1isMint = tx1.getType() == TransactionType.ColoredCoins.ASSET_MINT;
                boolean tx2isMint = tx2.getType() == TransactionType.ColoredCoins.ASSET_MINT;
                boolean tx1isTransfer = tx1.getType() == TransactionType.ColoredCoins.ASSET_TRANSFER;
                boolean tx2isTransfer = tx2.getType() == TransactionType.ColoredCoins.ASSET_TRANSFER;

                if (tx1isMint && tx2isTransfer) return -1;
                if (tx1isTransfer && tx2isMint) return 1;
            }
            return 0;
        });

        if (!fluxCapacitor.getValue(FluxValues.AT_FIX_BLOCK_4, at.getHeight())) {
            for (AtTransaction tx : ordered) {
                if (AT.findPendingTransaction(tx.getRecipientId(), blockHeight, generatorId)) {
                    throw new AtException("Conflicting transaction found");
                }
            }
        }

        for (AtTransaction tx : ordered) {
            totalAmount = Convert.safeAdd(totalAmount, tx.getAmount());
            AT.addPendingTransaction(tx, blockHeight, generatorId);
            if (logger.isDebugEnabled()) {
                logger.debug(
                        "Transaction to {}, amount {}",
                        tx.getRecipientId() == null ? 0L
                                : Convert.toUnsignedLong(AtApiHelper.getLong(tx.getRecipientId())),
                        tx.getAmount());
            }
        }

        AT.addMapUpdates(at.getMapUpdates(), blockHeight, generatorId);
        return totalAmount;
    }

    private byte[] getBlockATBytes(List<AT> processedATs, int payload) {
        if (payload <= 0) {
            return null;
        }

        ByteBuffer b = ByteBuffer.allocate(payload);
        b.order(ByteOrder.LITTLE_ENDIAN);

        MessageDigest digest = Crypto.md5();
        for (AT at : processedATs) {
            b.put(at.getId());
            digest.update(at.getBytes());
            b.put(digest.digest());
        }

        return b.array();
    }

    private int getCostOfOneAT() {
        return AtConstants.AT_ID_SIZE + 16;
    }

    /**
     * Returns the internal ATProcessingContext for advanced consumers.
     */
    public ATProcessingContext getProcessingContext() {
        return processingContext;
    }

    // -------------------------------------------------------------------------
    // Convenience overloads — delegate to context-aware methods using stored ctx
    // -------------------------------------------------------------------------

    @Override
    public AtBlock validateATs(byte[] blockATs, int blockHeight, long generatorId) throws AtException {
        if (processingContext == null) {
            throw new IllegalStateException("ATProcessingContext not available. Use full constructor for block processing.");
        }
        return validateATs(processingContext, blockATs, blockHeight, generatorId);
    }

    @Override
    public AtBlock getCurrentBlockATs(int freePayload, int blockHeight, long generatorId, int indirectsCount) {
        if (processingContext == null) {
            throw new IllegalStateException("ATProcessingContext not available. Use full constructor for block processing.");
        }
        return getCurrentBlockATs(processingContext, freePayload, blockHeight, generatorId, indirectsCount);
    }
}
