/*
 * Some portion .. Copyright (c) 2014 CIYAM Developers

 Distributed under the MIT/X11 software license, please refer to the file LICENSE.txt
 */

package application.module.node.at;

import application.module.node.Account;
import application.module.node.Block;
import application.module.node.SignumException;
import application.module.node.Transaction;
import application.module.node.db.SignumKey;
import application.module.node.db.TransactionDb;
import application.module.node.db.VersionedEntityTable;
import application.module.node.services.AccountService;
import application.module.node.util.Listener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class AT extends AtMachineState {

    public final SignumKey dbKey;
    private final String name;
    private final String description;
    private final int nextHeight;

    /**
     * @deprecated Use the constructor that accepts {@link AtConstants} explicitly.
     */
    @Deprecated
    private AT(byte[] atId, byte[] creator, String name, String description, byte[] creationBytes, int height, int currentHeight,
            SignumKey.LongKeyFactory<AT> atDbKeyFactory) {
        super(AtController.getAtConstants(), atId, creator, creationBytes, height);
        this.name = name.trim();
        this.description = description.trim();
        dbKey = atDbKeyFactory.newKey(AtApiHelper.getLong(atId));
        this.nextHeight = currentHeight;
    }

    /**
     * Creates a new AT instance from creation bytes with explicit AtConstants.
     */
    protected AT(AtConstants atConstants, byte[] atId, byte[] creator, String name, String description,
            byte[] creationBytes, int height, int currentHeight,
            SignumKey.LongKeyFactory<AT> atDbKeyFactory) {
        super(atConstants, atId, creator, creationBytes, height);
        this.name = name.trim();
        this.description = description.trim();
        dbKey = atDbKeyFactory.newKey(AtApiHelper.getLong(atId));
        this.nextHeight = currentHeight;
    }

    /**
     * @deprecated Use the constructor that accepts {@link AtConstants} explicitly.
     */
    @Deprecated
    public AT(byte[] atId, byte[] creator, String name, String description, short version,
            int height,
            byte[] stateBytes, int csize, int dsize, int cUserStackBytes, int cCallStackBytes,
            int creationBlockHeight, int sleepBetween, int nextHeight,
            boolean freezeWhenSameBalance, long minActivationAmount, byte[] apCode, long apCodeHashId,
            SignumKey.LongKeyFactory<AT> atDbKeyFactory) {
        super(AtController.getAtConstants(), atId, creator, version,
                height,
                stateBytes, csize, dsize, cUserStackBytes, cCallStackBytes,
                creationBlockHeight, sleepBetween,
                freezeWhenSameBalance, minActivationAmount, apCode, apCodeHashId);
        this.name = name.trim();
        this.description = description.trim();
        dbKey = atDbKeyFactory.newKey(AtApiHelper.getLong(atId));
        this.nextHeight = nextHeight;
    }

    /**
     * Creates a new AT instance from stored state with explicit AtConstants.
     */
    public AT(AtConstants atConstants, byte[] atId, byte[] creator, String name, String description, short version,
            int height,
            byte[] stateBytes, int csize, int dsize, int cUserStackBytes, int cCallStackBytes,
            int creationBlockHeight, int sleepBetween, int nextHeight,
            boolean freezeWhenSameBalance, long minActivationAmount, byte[] apCode, long apCodeHashId,
            SignumKey.LongKeyFactory<AT> atDbKeyFactory) {
        super(atConstants, atId, creator, version,
                height,
                stateBytes, csize, dsize, cUserStackBytes, cCallStackBytes,
                creationBlockHeight, sleepBetween,
                freezeWhenSameBalance, minActivationAmount, apCode, apCodeHashId);
        this.name = name.trim();
        this.description = description.trim();
        dbKey = atDbKeyFactory.newKey(AtApiHelper.getLong(atId));
        this.nextHeight = nextHeight;
    }

    // -- Pending state operations (instance-scoped via ATPendingState) --

    /**
     * Clears all pending AT state for the given block/generator.
     * <p>
     * Delegates to the instance-scoped {@link ATPendingState} obtained from the context.
     * This eliminates JVM-wide shared mutable state that would corrupt multi-node isolation.
     * </p>
     *
     * @param ctx         the AT processing context (provides the instance-scoped pending state)
     * @param blockHeight the block height
     * @param generatorId the generator account ID
     */
    public static void clearPending(ATProcessingContext ctx, int blockHeight, long generatorId) {
        ctx.getPendingState().clearPending(blockHeight, generatorId);
    }

    public static void addPendingFee(ATPendingState state, long id, long fee, int blockHeight, long generatorId) {
        state.addPendingFee(id, fee, blockHeight, generatorId);
    }

    public static void addPendingTransaction(ATPendingState state, AtTransaction atTransaction, int blockHeight, long generatorId) {
        state.addPendingTransaction(atTransaction, blockHeight, generatorId);
    }

    public static void addMapUpdates(ATPendingState state, Collection<AtMapEntry> entries, int blockHeight, long generatorId) {
        state.addMapUpdates(entries, blockHeight, generatorId);
    }

    public static boolean findPendingTransaction(ATPendingState state, byte[] recipientId, int blockHeight, long generatorId) {
        return state.findPendingTransaction(recipientId, blockHeight, generatorId);
    }

    private static SignumKey.LongKeyFactory<AT> atDbKeyFactory(ATProcessingContext ctx) {
        return ctx.getAtStore().getAtDbKeyFactory();
    }

    private static VersionedEntityTable<AT> atTable(ATProcessingContext ctx) {
        return ctx.getAtStore().getAtTable();
    }

    private static SignumKey.LongKeyFactory<ATState> atStateDbKeyFactory(ATProcessingContext ctx) {
        return ctx.getAtStore().getAtStateDbKeyFactory();
    }

    private static VersionedEntityTable<ATState> atStateTable(ATProcessingContext ctx) {
        return ctx.getAtStore().getAtStateTable();
    }

    public static AT getAT(ATProcessingContext ctx, Long id) {
        return ctx.getAtStore().getAT(id, -1);
    }

    public static void addAT(ATProcessingContext ctx, Long atId, Long senderAccountId, String name, String description, byte[] creationBytes,
            int height, long atCodeHashId) {
        ByteBuffer bf = ByteBuffer.allocate(8 + 8);
        bf.order(ByteOrder.LITTLE_ENDIAN);

        bf.putLong(atId);

        byte[] id = new byte[8];

        bf.putLong(8, senderAccountId);

        byte[] creator = new byte[8];
        bf.clear();
        bf.get(id, 0, 8);
        bf.get(creator, 0, 8);

        AT at = new AT(ctx.getAtConstants(), id, creator, name, description, creationBytes,
                height, ctx.getBlockchain().getHeight(),
                ctx.getAtStore().getAtDbKeyFactory());

        if (at.getApCodeHashId() == 0L)
            at.setApCodeHashId(atCodeHashId);

        AtController.resetMachine(at, ctx);

        atTable(ctx).insert(at);

        at.saveState(ctx);

        Account account = ctx.getAccountService().getOrAddAccount(atId);
        account.apply(new byte[32], height);
    }

    public static List<Long> getOrderedATs(ATProcessingContext ctx) {
        return ctx.getAtStore().getOrderedATs();
    }

    public static byte[] compressState(byte[] stateBytes) {
        if (stateBytes == null || stateBytes.length == 0) {
            return null;
        }

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
                gzip.write(stateBytes);
                gzip.flush();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static byte[] decompressState(byte[] stateBytes) {
        if (stateBytes == null || stateBytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bis = new ByteArrayInputStream(stateBytes);
                GZIPInputStream gzip = new GZIPInputStream(bis);
                ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int read;
            while ((read = gzip.read(buffer, 0, buffer.length)) > 0) {
                bos.write(buffer, 0, read);
            }
            bos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void saveState(ATProcessingContext ctx) {
        int prevHeight = ctx.getBlockchain().getHeight();
        int newNextHeight = prevHeight + getWaitForNumberOfBlocks();
        ATState state = new ATState(AtApiHelper.getLong(this.getId()),
                getState(), newNextHeight, getSleepBetween(),
                getpBalance(), freezeOnSameBalance(), minActivationAmount(),
                atStateDbKeyFactory(ctx));
        state.setPrevHeight(prevHeight);

        atStateTable(ctx).insert(state);
    }


    public static void saveMapUpdates(ATProcessingContext ctx, int blockHeight, long generatorId) {
        List<AtMapEntry> updates = ctx.getPendingState().getAndClearMapUpdates(blockHeight, generatorId);
        if (!updates.isEmpty()) {
            VersionedEntityTable<AtMapEntry> table = ctx.getAtStore().getAtMapTable();
            for (AtMapEntry e : updates) {
                AtMapEntry cacheEntry = ctx.getAtStore().getMapValueEntry(e.getAtId(), e.getKey1(),
                        e.getKey2());
                if (cacheEntry != null) {
                    cacheEntry.setValue(e.getValue());
                    e = cacheEntry;
                }
                table.insert(e);
            }
        }
    }


    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int nextHeight() {
        return nextHeight;
    }

    public static class HandleATBlockTransactionsListener implements Listener<Block> {
        private final ATProcessingContext processingContext;
        private final TransactionDb transactionDb;

        public HandleATBlockTransactionsListener(ATProcessingContext processingContext, TransactionDb transactionDb) {
            this.processingContext = processingContext;
            this.transactionDb = transactionDb;
        }

        @Override
        public void notify(Block block) {
            ATPendingState pendingState = processingContext.getPendingState();
            AccountService accountService = processingContext.getAccountService();
            int blockHeight = block.getHeight();
            long generatorId = block.getGeneratorId();

            LinkedHashMap<Long, Long> pendingFees = pendingState.getPendingFees(blockHeight, generatorId);
            if (pendingFees != null) {
                pendingFees.forEach((key, value) -> {
                    Account atAccount = accountService.getAccount(key);
                    accountService.addToBalanceAndUnconfirmedBalanceNQT(atAccount, -value);
                });
            }

            List<Transaction> transactions = new ArrayList<>();
            List<AtTransaction> pendingTransactions = pendingState.getPendingTransactions(blockHeight, generatorId);
            if (pendingTransactions != null) {
                for (AtTransaction atTransaction : pendingTransactions) {
                    try {
                        Transaction transaction = atTransaction.build(block, processingContext.getFluxCapacitor());

                        if (!transactionDb.hasTransaction(transaction.getIdCheckSignature(false))) {
                            atTransaction.apply(processingContext, transaction);
                            transactions.add(transaction);
                        }
                    } catch (SignumException.NotValidException e) {
                        throw new RuntimeException("Failed to construct AT payment transaction", e);
                    }
                }
            }

            // Clean up pending fees and transactions (map updates are handled by saveMapUpdates)
            pendingState.removeFeesAndTransactions(blockHeight, generatorId);

            if (!transactions.isEmpty()) {
                transactionDb.saveTransactions(transactions);
                block.setAtTransactions(transactions);
            }
        }
    }

    public static class ATState {

        public final SignumKey dbKey;
        private final long atId;
        private byte[] state;
        private int prevHeight;
        private int nextHeight;
        private int sleepBetween;
        private long prevBalance;
        private boolean freezeWhenSameBalance;
        private long minActivationAmount;


        protected ATState(long atId, byte[] state,
                int nextHeight, int sleepBetween, long prevBalance, boolean freezeWhenSameBalance,
                long minActivationAmount, SignumKey.LongKeyFactory<ATState> atStateDbKeyFactory) {
            this.atId = atId;
            this.dbKey = atStateDbKeyFactory.newKey(this.atId);
            this.state = state;
            this.nextHeight = nextHeight;
            this.sleepBetween = sleepBetween;
            this.prevBalance = prevBalance;
            this.freezeWhenSameBalance = freezeWhenSameBalance;
            this.minActivationAmount = minActivationAmount;
        }

        public long getATId() {
            return atId;
        }

        public byte[] getState() {
            return state;
        }

        void setState(byte[] newState) {
            state = newState;
        }

        public int getPrevHeight() {
            return prevHeight;
        }

        void setPrevHeight(int prevHeight) {
            this.prevHeight = prevHeight;
        }

        public int getNextHeight() {
            return nextHeight;
        }

        void setNextHeight(int newNextHeight) {
            nextHeight = newNextHeight;
        }

        public int getSleepBetween() {
            return sleepBetween;
        }

        void setSleepBetween(int newSleepBetween) {
            sleepBetween = newSleepBetween;
        }

        public long getPrevBalance() {
            return prevBalance;
        }

        void setPrevBalance(long newPrevBalance) {
            this.prevBalance = newPrevBalance;
        }

        public boolean getFreezeWhenSameBalance() {
            return freezeWhenSameBalance;
        }

        void setFreezeWhenSameBalance(boolean newFreezeWhenSameBalance) {
            freezeWhenSameBalance = newFreezeWhenSameBalance;
        }

        public long getMinActivationAmount() {
            return minActivationAmount;
        }

        void setMinActivationAmount(long newMinActivationAmount) {
            minActivationAmount = newMinActivationAmount;
        }
    }

    public static class AtMapEntry {
        private long atId;
        private long key1;
        private long key2;
        private long value;

        public AtMapEntry(long atId, long key1, long key2, long value) {
            this.atId = atId;
            this.key1 = key1;
            this.key2 = key2;
            this.value = value;
        }

        public long getValue() {
            return value;
        }

        public void setValue(long value) {
            this.value = value;
        }

        public long getAtId() {
            return atId;
        }

        public long getKey1() {
            return key1;
        }

        public long getKey2() {
            return key2;
        }

    }
}