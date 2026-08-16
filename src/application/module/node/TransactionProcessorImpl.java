package application.module.node;

import application.module.node.SignumException.ValidationException;
import application.module.node.db.store.Dbs;
import application.module.node.db.store.Stores;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.peer.Peer;
import application.module.node.peer.Peers;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.AccountService;
import application.module.node.services.TimeService;
import application.module.node.services.TransactionService;
import application.module.node.unconfirmedtransactions.UnconfirmedTransactionStore;
import application.module.node.util.JSON;
import application.module.node.util.Listener;
import application.module.node.util.Listeners;
import application.module.node.util.ThreadPool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static application.module.node.web.api.http.common.ResultFields.UNCONFIRMED_TRANSACTIONS_RESPONSE;

public class TransactionProcessorImpl implements TransactionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessorImpl.class);

    private final boolean testUnconfirmedTransactions;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private final Object unconfirmedTransactionsSyncObj = new Object();

    private final Listeners<List<? extends Transaction>, Event> transactionListeners = new Listeners<>();

    private final EconomicClustering economicClustering;
    private final FluxCapacitor fluxCapacitor;

    private final Stores stores;
    private final TimeService timeService;
    private final TransactionService transactionService;
    private final Dbs dbs;
    private final Blockchain blockchain;
    private final AccountService accountService;
    private final UnconfirmedTransactionStore unconfirmedTransactionStore;
    private final Function<Peer, List<Transaction>> foodDispenser;
    private final BiConsumer<Peer, List<Transaction>> doneFeedingLog;

    /**
     * Constructs TransactionProcessorImpl with instance-scoped dependencies.
     * Eliminates static Signum.getFluxCapacitor() calls.
     */
    public TransactionProcessorImpl(PropertyService propertyService,
            EconomicClustering economicClustering, Blockchain blockchain, Stores stores, TimeService timeService,
            Dbs dbs, AccountService accountService,
            TransactionService transactionService, FluxCapacitor fluxCapacitor, ThreadPool threadPool) {
        this.economicClustering = economicClustering;
        this.fluxCapacitor = fluxCapacitor;
        this.blockchain = blockchain;
        this.timeService = timeService;

        this.stores = stores;
        this.dbs = dbs;

        this.accountService = accountService;
        this.transactionService = transactionService;

        this.testUnconfirmedTransactions = propertyService.getBoolean(Props.NODE_TEST_UNCONFIRMED_TRANSACTIONS);
        this.unconfirmedTransactionStore = stores.getUnconfirmedTransactionStore();

        this.foodDispenser = (unconfirmedTransactionStore::getAllFor);
        this.doneFeedingLog = (unconfirmedTransactionStore::markFingerPrintsOf);

        Runnable getUnconfirmedTransactions = () -> {
            // The initial peer selection and request should be outside the synchronized
            if (isShutdown.get()) {
                logger.debug("TransactionProcessor is shutting down, skipping pull unconfirmed transactions.");
                return;
            }
            // block
            // to avoid blocking the entire unconfirmedTransactionsSyncObj while waiting for
            // network I/O.
            Peer initialPeer = Peers.getAnyPeer(Peer.State.CONNECTED);
            if (initialPeer == null) {
                return;
            }

            Peers.readUnconfirmedTransactionsNonBlocking(initialPeer)
                    .whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            logger.debug("Error pulling unconfirmed transactions from initial peer {}: {}",
                                    initialPeer.getAnnouncedAddress(), throwable.getMessage(), throwable);
                            initialPeer.blacklist((Exception) throwable, "error pulling unconfirmed transactions");
                            return;
                        }
                        if (response == null) {
                            return;
                        }

                        JsonArray transactionsData = JSON
                                .getAsJsonArray(response.get(UNCONFIRMED_TRANSACTIONS_RESPONSE));
                        if (transactionsData == null) {
                            return;
                        }

                        // Process transactions and update shared state within a synchronized block
                        // to ensure atomicity and prevent race conditions on the unconfirmed
                        // transaction pool.
                        synchronized (unconfirmedTransactionsSyncObj) {
                            try {
                                List<Transaction> addedTransactions = processPeerTransactions(transactionsData,
                                        initialPeer);
                                Peers.feedingTime(initialPeer, foodDispenser, doneFeedingLog);

                                if (!addedTransactions.isEmpty()) {
                                    List<Peer> activePrioPlusExtra = Peers.getAllActivePriorityPlusSomeExtraPeers();
                                    activePrioPlusExtra.remove(initialPeer);

                                    List<CompletableFuture<Void>> expectedResults = new ArrayList<>();

                                    for (Peer otherPeer : activePrioPlusExtra) {
                                        CompletableFuture<Void> peerTransactionFuture = Peers
                                                .readUnconfirmedTransactionsNonBlocking(otherPeer)
                                                .whenComplete((otherResponse, otherThrowable) -> {
                                                    if (otherThrowable != null) {
                                                        logger.debug(
                                                                "Error pulling unconfirmed transactions from other peer {}: {}",
                                                                otherPeer.getAnnouncedAddress(),
                                                                otherThrowable.getMessage(), otherThrowable);
                                                        otherPeer.blacklist((Exception) otherThrowable,
                                                                "error pulling unconfirmed transactions");
                                                        return;
                                                    }
                                                    if (otherResponse != null) {
                                                        // Process transactions from other peers, also within a
                                                        // synchronized block
                                                        synchronized (unconfirmedTransactionsSyncObj) {
                                                            try {
                                                                processPeerTransactions(
                                                                        JSON.getAsJsonArray(otherResponse.get(
                                                                                UNCONFIRMED_TRANSACTIONS_RESPONSE)),
                                                                        otherPeer);
                                                                Peers.feedingTime(otherPeer, foodDispenser,
                                                                        doneFeedingLog);
                                                            } catch (ValidationException | RuntimeException e) {
                                                                otherPeer.blacklist(e,
                                                                        "pulled invalid data using getUnconfirmedTransactions");
                                                            }
                                                        }
                                                    }
                                                })
                                                .thenApply(v -> null); // Convert to CompletableFuture<Void>
                                        expectedResults.add(peerTransactionFuture);
                                    }

                                    CompletableFuture.allOf(expectedResults.toArray(new CompletableFuture[0]))
                                            .whenComplete((v, t) -> {
                                                if (t != null) {
                                                    logger.debug("Multi-peer transaction pull finished with errors: {}",
                                                            t.getMessage());
                                                } else {
                                                    logger.debug("Multi-peer transaction pull finished successfully.");
                                                }
                                            });
                                }
                            } catch (ValidationException | RuntimeException e) {
                                initialPeer.blacklist(e, "pulled invalid data using getUnconfirmedTransactions");
                            }
                        } // end synchronized (unconfirmedTransactionsSyncObj)
                    })
                    .exceptionally(t -> {
                        logger.info(
                                "CRITICAL ERROR in getUnconfirmedTransactions: {}. PLEASE REPORT TO THE DEVELOPERS.",
                                t.toString(), t);
                        System.exit(1); // Still a critical error, but handled at the top-level future.
                        return null;
                    });
        };
        threadPool.scheduleThread("PullUnconfirmedTransactions", getUnconfirmedTransactions, 5);
    }

    @Override
    public boolean addListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<List<? extends Transaction>> listener, Event eventType) {
        return transactionListeners.removeListener(listener, eventType);
    }

    void notifyListeners(List<? extends Transaction> transactions, Event eventType) {
        transactionListeners.notify(transactions, eventType);
    }

    public Object getUnconfirmedTransactionsSyncObj() {
        return unconfirmedTransactionsSyncObj;
    }

    @Override
    public List<Transaction> getAllUnconfirmedTransactions() {
        return unconfirmedTransactionStore.getAll();
    }

    @Override
    public int getAmountUnconfirmedTransactions() {
        return unconfirmedTransactionStore.getAmount();
    }

    @Override
    public List<Transaction> getAllUnconfirmedTransactionsFor(Peer peer) {
        return unconfirmedTransactionStore.getAllFor(peer);
    }

    @Override
    public void markFingerPrintsOf(Peer peer, List<Transaction> transactions) {
        unconfirmedTransactionStore.markFingerPrintsOf(peer, transactions);
    }

    @Override
    public Transaction getUnconfirmedTransaction(long transactionId) {
        return unconfirmedTransactionStore.get(transactionId);
    }

    @Override
    public Transaction.Builder newTransactionBuilder(byte[] senderPublicKey, long amountNQT, long feeNQT,
            short deadline, Attachment attachment) {
        byte version = (byte) getTransactionVersion(blockchain.getHeight());
        int timestamp = timeService.getEpochTime();
        Transaction.Builder builder = new Transaction.Builder(version, senderPublicKey, amountNQT, feeNQT, timestamp,
                deadline, (Attachment.AbstractAttachment) attachment);
        if (version > 0) {
            Block ecBlock = this.economicClustering.getECBlock(timestamp);
            builder.ecBlockHeight(ecBlock.getHeight());
            builder.ecBlockId(ecBlock.getId());
        }
        return builder;
    }

    @Override
    public Integer broadcast(Transaction transaction) throws SignumException.ValidationException {
        if (!transaction.verifySignature()) {
            throw new SignumException.NotValidException("Transaction signature verification failed");
        }
        List<Transaction> processedTransactions;
        if (dbs.getTransactionDb().hasTransaction(transaction.getId())) {
            if (logger.isInfoEnabled()) {
                logger.info("Transaction {} already in blockchain, will not broadcast again",
                        transaction.getStringId());
            }
            return null;
        }

        if (unconfirmedTransactionStore.exists(transaction.getId())) {
            if (logger.isInfoEnabled()) {
                logger.info("Transaction {} already in unconfirmed pool, will not broadcast again",
                        transaction.getStringId());
            }
            return null;
        }

        processedTransactions = processTransactions(Collections.singleton(transaction), null);

        if (!processedTransactions.isEmpty()) {
            return broadcastToPeers(true);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Could not accept new transaction {}", transaction.getStringId());
            }
            throw new SignumException.NotValidException("Invalid transaction " + transaction.getStringId());
        }
    }

    @Override
    public void processPeerTransactions(JsonObject request, Peer peer) throws SignumException.ValidationException {
        JsonArray transactionsData = JSON.getAsJsonArray(request.get("transactions"));
        List<Transaction> processedTransactions = processPeerTransactions(transactionsData, peer);

        if (!processedTransactions.isEmpty()) {
            broadcastToPeers(false);
        }
    }

    @Override
    public Transaction parseTransaction(byte[] bytes) throws SignumException.ValidationException {
        return Transaction.parseTransaction(bytes);
    }

    @Override
    public Transaction parseTransaction(JsonObject transactionData) throws SignumException.NotValidException {
        return Transaction.parseTransaction(transactionData, blockchain.getHeight());
    }

    @Override
    public void clearUnconfirmedTransactions() {
        synchronized (unconfirmedTransactionsSyncObj) {
            List<Transaction> removed;
            try {
                stores.beginTransaction();
                removed = unconfirmedTransactionStore.getAll();
                accountService.flushAccountTable();
                unconfirmedTransactionStore.clear();
                stores.commitTransaction();
            } catch (Exception e) {
                logger.error(e.toString(), e);
                stores.rollbackTransaction();
                throw e;
            } finally {
                stores.endTransaction();
            }

            transactionListeners.notify(removed, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        }
    }

    void requeueAllUnconfirmedTransactions() {
        synchronized (unconfirmedTransactionsSyncObj) {
            unconfirmedTransactionStore.resetAccountBalances();
        }
    }

    @Override
    public int getTransactionVersion(int previousBlockHeight) {
        if (fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, previousBlockHeight)) {
            if (fluxCapacitor.getValue(FluxValues.SMART_FEES, previousBlockHeight)) {
                return 2;
            }
            return 1;
        }
        return 0;
    }

    // Watch: This is not really clean
    void processLater(Collection<Transaction> transactions) {
        for (Transaction transaction : transactions) {
            try {
                unconfirmedTransactionStore.put(transaction, null);
            } catch (SignumException.ValidationException e) {
                logger.debug("Discarding invalid transaction in for later processing: "
                        + JSON.toJsonString(transaction.getJsonObject()), e);
            }
        }
    }

    private List<Transaction> processPeerTransactions(JsonArray transactionsData, Peer peer)
            throws SignumException.ValidationException {
        if (blockchain.getLastBlock().getTimestamp() < timeService.getEpochTime() - 60 * 1440
                && !testUnconfirmedTransactions) {
            return new ArrayList<>();
        }

        List<Transaction> transactions = new ArrayList<>();
        for (JsonElement transactionData : transactionsData) {
            try {
                Transaction transaction = parseTransaction(JSON.getAsJsonObject(transactionData));
                transactionService.validate(transaction);
                if (!this.economicClustering.verifyFork(transaction)) {
                    continue;
                }
                transactions.add(transaction);
            } catch (SignumException.NotCurrentlyValidException ignore) {
            } catch (SignumException.NotValidException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Invalid transaction from peer: {}", JSON.toJsonString(transactionData));
                }
                throw e;
            }
        }
        return processTransactions(transactions, peer);
    }

    private List<Transaction> processTransactions(Collection<Transaction> transactions, Peer peer)
            throws SignumException.ValidationException {
        synchronized (unconfirmedTransactionsSyncObj) {
            if (transactions.isEmpty()) {
                return Collections.emptyList();
            }

            List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();

            for (Transaction transaction : transactions) {

                try {
                    int curTime = timeService.getEpochTime();
                    if (transaction.getTimestamp() > curTime + 15 || transaction.getExpiration() < curTime
                            || transaction.getDeadline() > 1440) {
                        continue;
                    }

                    try {
                        stores.beginTransaction();

                        if (dbs.getTransactionDb().hasTransaction(transaction.getId())
                                || unconfirmedTransactionStore.exists(transaction.getId())) {
                            stores.commitTransaction();
                            unconfirmedTransactionStore.markFingerPrintsOf(peer,
                                    Collections.singletonList(transaction));
                            continue;
                        }

                        if (!(transaction.verifySignature() && transactionService.verifyPublicKey(transaction))) {
                            if (accountService.getAccount(transaction.getSenderId()) != null
                                    && logger.isDebugEnabled()) {
                                logger.debug("Transaction {} failed to verify",
                                        JSON.toJsonString(transaction.getJsonObject()));
                            }
                            stores.commitTransaction();
                            continue;
                        }

                        if (unconfirmedTransactionStore.put(transaction, peer)) {
                            addedUnconfirmedTransactions.add(transaction);
                        }

                        stores.commitTransaction();
                    } catch (Exception e) {
                        stores.rollbackTransaction();
                        throw e;
                    } finally {
                        stores.endTransaction();
                    }
                } catch (RuntimeException e) {
                    logger.info("Error processing transaction", e);
                }
            }

            if (!addedUnconfirmedTransactions.isEmpty()) {
                transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
            }

            return addedUnconfirmedTransactions;
        }
    }

    private int broadcastToPeers(boolean toAll) {
        List<? extends Peer> peersToSendTo = toAll
                ? Peers.getActivePeers().stream().limit(100).collect(Collectors.toList())
                : Peers.getAllActivePriorityPlusSomeExtraPeers();

        logger.trace("Queueing up {} Peers for feeding", peersToSendTo.size());

        for (Peer p : peersToSendTo) {
            Peers.feedingTime(p, foodDispenser, doneFeedingLog);
        }

        return peersToSendTo.size();
    }

    public void revalidateUnconfirmedTransactions() {
        final List<Transaction> invalidTransactions = new ArrayList<>();

        for (Transaction t : unconfirmedTransactionStore.getAll()) {
            try {
                this.transactionService.validate(t);
            } catch (ValidationException e) {
                invalidTransactions.add(t);
            }
        }

        for (Transaction t : invalidTransactions) {
            unconfirmedTransactionStore.remove(t);
        }
    }

    public void removeForgedTransactions(List<Transaction> transactions) {
        this.unconfirmedTransactionStore.removeForgedTransactions(transactions);
    }

    @Override
    public void shutdown() {
        isShutdown.set(true);
        logger.info("Transaction processor shutdown initiated.");
    }
}