package application.module.node.at;

import application.module.node.Account;
import application.module.node.Signum;
import application.module.node.crypto.Crypto;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.util.Convert;
import application.module.node.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.*;

/**
 * AT (Automated Transaction) controller orchestrating AT execution.
 * <p>
 * Manages AT machine lifecycle: creation, activation, state transitions,
 * fee calculation, and transaction generation within blocks.
 * Uses {@link ATProcessingContext} for dependency injection of runtime
 * services and configuration.
 * </p>
 */
public abstract class AtController {
    private AtController() {
    }

    private static final Logger logger = LoggerFactory.getLogger(AtController.class);

    private static volatile AtConstants atConstants;

    /**
     * Sets the AtConstants instance for use by AtController methods.
     * Called once during node initialization.
     */
    public static void setAtConstants(AtConstants constants) {
        atConstants = constants;
    }

    /**
     * Gets the AtConstants instance.
     */
    public static AtConstants getAtConstants() {
        return atConstants;
    }

    // Helper to get debug logger based on context property
    private static Logger getDebugLogger(PropertyService propertyService) {
        return propertyService.getBoolean(Props.ENABLE_AT_DEBUG_LOG) ? logger : NOPLogger.NOP_LOGGER;
    }

    private static int runSteps(AtMachineState state, ATProcessingContext ctx) {
        final Logger debugLog = getDebugLogger(ctx.getPropertyService());

        state.getMachineState().running = true;
        state.getMachineState().stopped = false;
        state.getMachineState().finished = false;
        state.getMachineState().dead = false;
        state.getMachineState().steps = 0;

        AtMachineProcessor processor = new AtMachineProcessor(state, ctx,
                ctx.getPropertyService().getBoolean(Props.ENABLE_AT_DEBUG_LOG));

        state.setFreeze(false);

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

    /**
     * @deprecated Use {@link #resetMachine(AtMachineState, ATProcessingContext)}.
     */
    @Deprecated(since = "4.0", forRemoval = true)
    public static void resetMachine(AtMachineState state) {
        ATProcessingContext ctx = new ATProcessingContext(
                AtController.getAtConstants(),
                ATProcessorCache.getInstance(),
                Signum.getPropertyService(),
                Signum.getFluxCapacitor(),
                Signum.getBlockchain(),
                Signum.getStores().getAtStore(),
                Signum.getStores().getAccountStore(),
                Signum.getAccountService(),
                Signum.getAssetExchange(),
                Signum.getStores().getIndirectIncomingStore(),
                Signum.getStores().getAssetStore());
        resetMachine(state, ctx);
    }

    public static void resetMachine(AtMachineState state, ATProcessingContext ctx) {
        state.getMachineState().reset();
        listCode(state, ctx, true, true);
    }

    private static void listCode(AtMachineState state, ATProcessingContext ctx, boolean disassembly, boolean determineJumps) {
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

    public static int checkCreationBytes(byte[] creation, int height, int minCodePages) throws AtException {
        if (creation == null)
            throw new AtException("Creation bytes cannot be null");

        int totalPages;
        try {
            ByteBuffer b = ByteBuffer.allocate(creation.length);
            b.order(ByteOrder.LITTLE_ENDIAN);
            b.put(creation);
            b.clear();

            AtConstants instance = atConstants;
            short version = b.getShort();
            if (version > instance.atVersion(height)) {
                throw new AtException(AtError.INCORRECT_VERSION.getDescription());
            }

            b.getShort(); // reserved
            short codePages = b.getShort();
            if (codePages > instance.maxMachineCodePages(version) || codePages < minCodePages) {
                throw new AtException(AtError.INCORRECT_CODE_PAGES.getDescription());
            }
            short dataPages = b.getShort();
            if (dataPages > instance.maxMachineDataPages(version) || dataPages < 0) {
                throw new AtException(AtError.INCORRECT_DATA_PAGES.getDescription());
            }
            short callStackPages = b.getShort();
            if (callStackPages > instance.maxMachineCallStackPages(version) || callStackPages < 0) {
                throw new AtException(AtError.INCORRECT_CALL_PAGES.getDescription());
            }
            short userStackPages = b.getShort();
            if (userStackPages > instance.maxMachineUserStackPages(version) || userStackPages < 0) {
                throw new AtException(AtError.INCORRECT_USER_PAGES.getDescription());
            }

            b.getLong(); // min activation amount
            int codeLen = getLength(codePages, b);
            if (codeLen == 0 && codePages == 1 && version > 2) {
                codeLen = 256;
            }
            if (codeLen < minCodePages || codeLen > codePages * 256) {
                throw new AtException(AtError.INCORRECT_CODE_LENGTH.getDescription());
            }
            byte[] code = new byte[codeLen];
            b.get(code, 0, codeLen);

            int dataLen = getLength(dataPages, b);
            if (dataLen == 0 && dataPages == 1 && b.capacity() - b.position() == 256 && version > 2) {
                dataLen = 256;
            }
            if (dataLen < 0 || dataLen > dataPages * 256) {
                throw new AtException(AtError.INCORRECT_DATA_LENGTH.getDescription());
            }
            byte[] data = new byte[dataLen];
            b.get(data, 0, dataLen);

            totalPages = codePages + dataPages + userStackPages + callStackPages;

            if (b.position() != b.capacity()) {
                throw new AtException(AtError.INCORRECT_CREATION_TX.getDescription());
            }

        } catch (BufferUnderflowException e) {
            throw new AtException(AtError.INCORRECT_CREATION_TX.getDescription());
        }

        return totalPages;
    }

    private static int getLength(int nPages, ByteBuffer buffer) throws AtException {
        int codeLen;
        if (nPages * 256 < 257) {
            codeLen = buffer.get();
            if (codeLen < 0)
                codeLen += (Byte.MAX_VALUE + 1) * 2;
        } else if (nPages * 256 < Short.MAX_VALUE + 1) {
            codeLen = buffer.getShort();
            if (codeLen < 0)
                codeLen += (Short.MAX_VALUE + 1) * 2;
        } else if (nPages * 256 <= Integer.MAX_VALUE) {
            codeLen = buffer.getInt();
        } else {
            throw new AtException(AtError.INCORRECT_CODE_LENGTH.getDescription());
        }
        return codeLen;
    }

    /**
     * @deprecated Use {@link #getCurrentBlockATs(ATProcessingContext, int, int, long, int)}.
     */
    @Deprecated(since = "4.0", forRemoval = true)
    public static AtBlock getCurrentBlockATs(int freePayload, int blockHeight, long generatorId, int indirectsCount) {
        ATProcessingContext ctx = createLegacyContext();
        return getCurrentBlockATs(ctx, freePayload, blockHeight, generatorId, indirectsCount);
    }

    public static AtBlock getCurrentBlockATs(ATProcessingContext ctx, int freePayload, int blockHeight,
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

        while (payload <= freePayload - costOfOneAT && keys.hasNext()) {
            Long id = keys.next();
            AT at = AT.getAT(ctx, id);
            at.addIndirectsCount(indirectsCount);

            long atAccountBalance = getATAccountBalance(id);
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

    public static AtBlock validateATsOriginal(byte[] blockATs, int blockHeight, long generatorId) throws AtException {
        if (blockATs == null) {
            return new AtBlock(0, 0, null);
        }

        throw new UnsupportedOperationException("validateATsOriginal requires ATProcessingContext. Use validateATs(ctx, ...) instead.");
    }

    /**
     * @deprecated Use {@link #validateATs(ATProcessingContext, byte[], int, long)}.
     */
    @Deprecated(since = "4.0", forRemoval = true)
    public static AtBlock validateATs(byte[] blockATs, int blockHeight, long generatorId) throws AtException {
        ATProcessingContext ctx = createLegacyContext();
        return validateATs(ctx, blockATs, blockHeight, generatorId);
    }

    public static AtBlock validateATs(ATProcessingContext ctx, byte[] blockATs, int blockHeight, long generatorId)
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

                long atAccountBalance = getATAccountBalance(atIdLong);
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
                if (!fluxCapacitor.getValue(FluxValues.AT_FIX_BLOCK_4, blockHeight)) {
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

    public static LinkedHashMap<Long, byte[]> getATsFromBlock(byte[] blockATs) throws AtException {
        if (blockATs.length > 0 && blockATs.length % (getCostOfOneAT()) != 0) {
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

    private static byte[] getBlockATBytes(List<AT> processedATs, int payload) {
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

    private static int getCostOfOneAT() {
        return AtConstants.AT_ID_SIZE + 16;
    }

    private static long makeTransactions(AT at, ATProcessingContext ctx, int blockHeight, long generatorId)
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

    @Deprecated(since = "4.0", forRemoval = true)
    private static ATProcessingContext createLegacyContext() {
        return new ATProcessingContext(
                AtController.getAtConstants(),
                ATProcessorCache.getInstance(),
                Signum.getPropertyService(),
                Signum.getFluxCapacitor(),
                Signum.getBlockchain(),
                Signum.getStores().getAtStore(),
                Signum.getStores().getAccountStore(),
                Signum.getAccountService(),
                Signum.getAssetExchange(),
                Signum.getStores().getIndirectIncomingStore(),
                Signum.getStores().getAssetStore());
    }

    private static long getATAccountBalance(Long id) {
        Account.Balance atAccount = Account.getAccountBalance(id);
        if (atAccount != null) {
            return atAccount.getBalanceNqt();
        }
        return 0;
    }
}