package application.module.node;

import static application.module.node.Constants.AT_PUBLIC_KEY_BYTES;

import application.module.node.crypto.Crypto;
import application.module.node.crypto.EncryptedData;
import application.module.node.db.SignumKey;
import application.module.node.db.store.AccountStore;
import application.module.node.util.Convert;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

//TODO: Create JavaDocs and remove this
@SuppressWarnings({ "checkstyle:MissingJavadocTypeCheck", "checkstyle:MissingJavadocMethodCheck" })

public class Account {

    private static final Logger logger = Logger.getLogger(Account.class.getSimpleName());

    public final long id;
    public final SignumKey nxtKey;
    private final int creationHeight;
    private byte[] publicKey;
    private int keyHeight;
    private boolean isAutomatedTransaction;
    private AccountStore originStore;

    protected String name;
    protected String description;

    public AccountStore getOriginStore() {
        return originStore;
    }

    public void setOriginStore(AccountStore store) {
        this.originStore = store;
    }

    public static class Balance {
        public final long id;
        public final SignumKey nxtKey;

        protected long balanceNqt;
        protected long unconfirmedBalanceNqt;
        protected long forgedBalanceNqt;

        protected Balance(long id, SignumKey signumKey) {
            this.id = id;
            this.nxtKey = signumKey;
        }

        public static Balance of(long id, SignumKey signumKey) {
            return new Balance(id, signumKey);
        }

        public void setForgedBalanceNqt(long forgedBalanceNqt) {
            this.forgedBalanceNqt = forgedBalanceNqt;
        }

        public void setUnconfirmedBalanceNqt(long unconfirmedBalanceNqt) {
            this.unconfirmedBalanceNqt = unconfirmedBalanceNqt;
        }

        public void setBalanceNqt(long balanceNqt) {
            this.balanceNqt = balanceNqt;
        }

        public long getId() {
            return id;
        }

        public long getBalanceNqt() {
            return balanceNqt;
        }

        public long getUnconfirmedBalanceNqt() {
            return unconfirmedBalanceNqt;
        }

        public long getForgedBalanceNqt() {
            return forgedBalanceNqt;
        }

        public void checkBalance() {
            Account.checkBalance(this.id, this.balanceNqt, this.unconfirmedBalanceNqt);
        }

    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPublicKey(byte[] publicKey) {
        this.publicKey = publicKey;
    }

    public void setKeyHeight(int keyHeight) {
        this.keyHeight = keyHeight;
    }

    public void setIsAt(boolean isAutomatedTransaction) {
        this.isAutomatedTransaction = isAutomatedTransaction;
    }

    public boolean isAutomatedTransaction() {
        return this.isAutomatedTransaction;
    }

    public enum Event {
        BALANCE, UNCONFIRMED_BALANCE, ASSET_BALANCE, UNCONFIRMED_ASSET_BALANCE,
        LEASE_SCHEDULED, LEASE_STARTED, LEASE_ENDED

    }

    public static class AccountAsset {
        public final long accountId;
        public final long assetId;
        public final SignumKey signumKey;
        private long quantityQnt;
        private long unconfirmedQuantityQnt;
        private boolean isTreasury;

        protected AccountAsset(
                long accountId,
                long assetId,
                long quantityQnt,
                long unconfirmedQuantityQnt,
                SignumKey signumKey) {
            this.accountId = accountId;
            this.assetId = assetId;
            this.quantityQnt = quantityQnt;
            this.unconfirmedQuantityQnt = unconfirmedQuantityQnt;
            this.signumKey = signumKey;
            this.isTreasury = false;
        }

        public AccountAsset(
                SignumKey signumKey,
                long accountId,
                long assetId,
                long quantityQnt,
                long unconfirmedQuantityQnt) {
            this.accountId = accountId;
            this.assetId = assetId;
            this.signumKey = signumKey;
            this.quantityQnt = quantityQnt;
            this.unconfirmedQuantityQnt = unconfirmedQuantityQnt;
            this.isTreasury = false;
        }

        public long getAccountId() {
            return accountId;
        }

        public long getAssetId() {
            return assetId;
        }

        public long getQuantityQnt() {
            return quantityQnt;
        }

        public long getUnconfirmedQuantityQnt() {
            return unconfirmedQuantityQnt;
        }

        public void checkBalance() {
            Account.checkBalance(this.accountId, this.quantityQnt, this.unconfirmedQuantityQnt);
        }

        @Override
        public String toString() {
            return "AccountAsset account_id: "
                    + Convert.toUnsignedLong(accountId)
                    + " asset_id: "
                    + Convert.toUnsignedLong(assetId)
                    + " quantity: "
                    + quantityQnt
                    + " unconfirmedQuantity: "
                    + unconfirmedQuantityQnt;
        }

        public void setQuantityQnt(long quantityQnt) {
            this.quantityQnt = quantityQnt;
        }

        public void setUnconfirmedQuantityQnt(long unconfirmedQuantityQnt) {
            this.unconfirmedQuantityQnt = unconfirmedQuantityQnt;
        }

        public void setTreasury(boolean isTreasury) {
            this.isTreasury = isTreasury;
        }

        public boolean isTreasury() {
            return isTreasury;
        }

    }

    public static class RewardRecipientAssignment {
        public final Long accountId;
        private Long prevRecipientId;
        private Long recipientId;
        private int fromHeight;
        public final SignumKey signumKey;

        public RewardRecipientAssignment(
                Long accountId,
                Long prevRecipientId,
                Long recipientId,
                int fromHeight,
                SignumKey signumKey) {
            this.accountId = accountId;
            this.prevRecipientId = prevRecipientId;
            this.recipientId = recipientId;
            this.fromHeight = fromHeight;
            this.signumKey = signumKey;
        }

        public long getAccountId() {
            return accountId;
        }

        public long getPrevRecipientId() {
            return prevRecipientId;
        }

        public long getRecipientId() {
            return recipientId;
        }

        public int getFromHeight() {
            return fromHeight;
        }

        public void setRecipient(long newRecipientId, int fromHeight) {
            prevRecipientId = recipientId;
            recipientId = newRecipientId;
            this.fromHeight = fromHeight;
        }
    }

    static class DoubleSpendingException extends RuntimeException {

        DoubleSpendingException(String message) {
            super(message);
        }

    }

    // =========================================================================
    // Store-scoped (multi-node) accessors.
    // Prefer these over the legacy static methods above: they operate on the
    // AccountStore owned by a specific node, guaranteeing cross-node isolation.
    //
    // @since 4.1
    // =========================================================================

    /**
     * Retrieves an account by id from the given {@link AccountStore}.
     *
     * @param store the node's account store
     * @param id    the account id
     * @return the account, or {@code null} if id is 0 or not found
     */
    public static Account getAccount(AccountStore store, long id) {
        Account result = id == 0 ? null : store.getAccountTable().get(store.getAccountKeyFactory().newKey(id));
        if (result != null) {
            result.setOriginStore(store);
        }
        return result;
    }

    /**
     * Retrieves an account balance by id from the given {@link AccountStore}.
     *
     * @param store the node's account store
     * @param id    the account id
     * @return the balance, or {@code null} if id is 0 or not found
     */
    public static Account.Balance getAccountBalance(AccountStore store, long id) {
        return id == 0 ? null : store.getAccountBalanceTable()
                .get(store.getAccountBalanceKeyFactory().newKey(id));
    }

    /**
     * Retrieves an account asset balance from the given {@link AccountStore}.
     *
     * @param store   the node's account store
     * @param id      the account id
     * @param assetId the asset id
     * @return the account asset
     */
    public static Account.AccountAsset getAccountAssetBalance(AccountStore store, long id, long assetId) {
        return store.getAccountAsset(id, assetId);
    }

    public long getId() {
        return id;
    }

    public static long getId(byte[] publicKey) {
        byte[] publicKeyHash = Crypto.sha256().digest(publicKey);
        return Convert.fullHashToId(publicKeyHash);
    }

    public static Account getOrAddAccount(AccountStore store, long id, int height) {
        Account account = getAccount(store, id);
        if (account == null) {
            account = new Account(id, height, store);
            store.getAccountTable().insert(account);
        }
        return account;
    }

    public Account(long id, int creationHeight, AccountStore store) {
        if (id != Crypto.rsDecode(Crypto.rsEncode(id))) {
            logger.log(Level.INFO, "CRITICAL ERROR: Reed-Solomon encoding fails for {0}", id);
        }
        this.id = id;
        this.nxtKey = store.getAccountKeyFactory().newKey(this.id);
        this.creationHeight = creationHeight;
        this.originStore = store;
    }

    protected Account(long id, SignumKey signumKey, int creationHeight) {
        if (id != Crypto.rsDecode(Crypto.rsEncode(id))) {
            logger.log(Level.INFO, "CRITICAL ERROR: Reed-Solomon encoding fails for {0}", id);
        }
        this.id = id;
        this.nxtKey = signumKey;
        this.creationHeight = creationHeight;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public byte[] getPublicKey() {
        if (this.keyHeight == -1) {
            return null;
        }
        return publicKey;
    }

    public int getCreationHeight() {
        return creationHeight;
    }

    public int getKeyHeight() {
        return keyHeight;
    }

    public long getUnconfirmedBalanceNqt() {
        Balance balance = getBalanceFromStore();
        return balance == null ? 0L : balance.getUnconfirmedBalanceNqt();
    }

    public long getBalanceNqt() {
        Balance balance = getBalanceFromStore();
        return balance == null ? 0L : balance.getBalanceNqt();
    }

    public long getForgedBalanceNqt() {
        Balance balance = getBalanceFromStore();
        return balance == null ? 0L : balance.getForgedBalanceNqt();
    }

    private Balance getBalanceFromStore() {
        return Account.getAccountBalance(this.originStore, id);
    }

    public EncryptedData encryptTo(byte[] data, String senderSecretPhrase) {
        if (getPublicKey() == null) {
            throw new IllegalArgumentException("Recipient account doesn't have a public key set");
        }
        return EncryptedData.encrypt(data, Crypto.getPrivateKey(senderSecretPhrase), publicKey);
    }

    public static EncryptedData encryptTo(
            byte[] data,
            String senderSecretPhrase,
            byte[] publicKey) {
        if (publicKey == null) {
            throw new IllegalArgumentException("public key required");
        }
        return EncryptedData.encrypt(data, Crypto.getPrivateKey(senderSecretPhrase), publicKey);
    }

    public byte[] decryptFrom(EncryptedData encryptedData, String recipientSecretPhrase) {
        if (getPublicKey() == null) {
            throw new IllegalArgumentException("Sender account doesn't have a public key set");
        }
        return encryptedData.decrypt(Crypto.getPrivateKey(recipientSecretPhrase), publicKey);
    }

    // returns true iff:
    // this.publicKey is set to null (in which case this.publicKey also gets set to
    // key)
    // or
    // this.publicKey is already set to an array equal to key
    public boolean setOrVerify(byte[] key, int height) {
        return this.originStore.setOrVerify(this, key, height);
    }

    public void apply(byte[] key, int height) {
        if (!setOrVerify(key, this.creationHeight)) {
            throw new IllegalStateException("Public key mismatch");
        }
        if (this.publicKey == null) {
            throw new IllegalStateException(
                    "Public key has not been set for account " + Convert.toUnsignedLong(id)
                            + " at height " + height + ", key height is " + keyHeight);
        }
        if (this.keyHeight == -1 || this.keyHeight > height) {
            this.keyHeight = height;
            this.originStore.getAccountTable().insert(this);
        }
    }

    public static boolean checkIsAutomatedTransaction(Account account) {
        return Arrays.equals(account.getPublicKey(), AT_PUBLIC_KEY_BYTES);
    }

    private static void checkBalance(long accountId, long confirmed, long unconfirmed) {
        if (confirmed < 0) {
            throw new DoubleSpendingException("Negative balance or quantity ("
                    + confirmed
                    + ") for account "
                    + Convert.toUnsignedLong(accountId));
        }
        if (unconfirmed < 0) {
            throw new DoubleSpendingException("Negative unconfirmed balance or quantity ("
                    + unconfirmed
                    + ") for account "
                    + Convert.toUnsignedLong(accountId));
        }
        if (unconfirmed > confirmed) {
            throw new DoubleSpendingException("Unconfirmed ("
                    + unconfirmed
                    + ") exceeds confirmed ("
                    + confirmed
                    + ") balance or quantity for account "
                    + Convert.toUnsignedLong(accountId));
        }
    }

}
