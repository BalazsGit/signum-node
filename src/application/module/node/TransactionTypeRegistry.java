package application.module.node;

import signum.net.NetworkParameters;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry for transaction type lookups by type/subtype byte codes.
 * <p>
 * <h3>Purpose</h3>
 * Extracts the static {@code TRANSACTION_TYPES} map and {@code init()} logic from
 * {@link TransactionType} into an instance-based registry. Each
 * {@link application.module.node.instance.NodeCoreContext} creates its own registry,
 * enabling true multi-profile isolation.
 * </p>
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li><b>Immutability after init:</b> The map is populated once and then read-only.</li>
 *   <li><b>No Static Mutable State:</b> All state is instance-based.</li>
 *   <li><b>Thread-Safe:</b> Safe to share across threads after construction.</li>
 * </ul>
 *
 * @since 4.0
 */
public final class TransactionTypeRegistry {

    // Multi-profile: instance-based registry, not static
    private final Map<TransactionType.Type, Map<Byte, TransactionType>> transactionTypes = new HashMap<>();

    /**
     * Creates and initializes a new transaction type registry.
     * Populates all type/subtype mappings for every transaction family.
     */
    public TransactionTypeRegistry() {
        initPaymentTypes();
        initMessagingTypes();
        initColoredCoinsTypes();
        initDigitalGoodsTypes();
        initAutomatedTransactionsTypes();
        initAccountControlTypes();
        initSignaMiningTypes();
        initAdvancedPaymentTypes();
    }

    /**
     * Adjusts transaction type configurations based on network parameters.
     *
     * @param params the network parameters to apply
     */
    public void applyNetworkParameters(NetworkParameters params) {
        params.adjustTransactionTypes(transactionTypes);
    }

    /**
     * Finds a transaction type by its type and subtype byte codes.
     *
     * @param type    the type byte
     * @param subtype the subtype byte
     * @return the matching {@link TransactionType}, or {@code null} if not found
     */
    public TransactionType findTransactionType(byte type, byte subtype) {
        for (TransactionType.Type t : transactionTypes.keySet()) {
            if (t.getType() == type) {
                Map<Byte, TransactionType> subtypes = transactionTypes.get(t);
                return subtypes == null ? null : subtypes.get(subtype);
            }
        }
        return null;
    }

    /**
     * Returns an unmodifiable view of all registered transaction types.
     *
     * @return the complete type map
     */
    public Map<TransactionType.Type, Map<Byte, TransactionType>> getTransactionTypes() {
        return Collections.unmodifiableMap(transactionTypes);
    }

    // ---- Type family initialization methods ----

    private void initPaymentTypes() {
        Map<Byte, TransactionType> paymentTypes = new HashMap<>();
        paymentTypes.put(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT, TransactionType.Payment.ORDINARY);
        paymentTypes.put(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_OUT, TransactionType.Payment.MULTI_OUT);
        paymentTypes.put(TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_SAME_OUT, TransactionType.Payment.MULTI_SAME_OUT);
        transactionTypes.put(TransactionType.TYPE_PAYMENT, paymentTypes);
    }

    private void initMessagingTypes() {
        Map<Byte, TransactionType> messagingTypes = new HashMap<>();
        messagingTypes.put(TransactionType.SUBTYPE_MESSAGING_ARBITRARY_MESSAGE, TransactionType.Messaging.ARBITRARY_MESSAGE);
        messagingTypes.put(TransactionType.SUBTYPE_MESSAGING_ALIAS_ASSIGNMENT, TransactionType.Messaging.ALIAS_ASSIGNMENT);
        messagingTypes.put(TransactionType.SUBTYPE_MESSAGING_ACCOUNT_INFO, TransactionType.Messaging.ACCOUNT_INFO);
        messagingTypes.put(TransactionType.SUBTYPE_MESSAGING_ALIAS_BUY, TransactionType.Messaging.ALIAS_BUY);
        messagingTypes.put(TransactionType.SUBTYPE_MESSAGING_ALIAS_SELL, TransactionType.Messaging.ALIAS_SELL);
        messagingTypes.put(TransactionType.SUBTYPE_MESSAGING_TLD_ASSIGNMENT, TransactionType.Messaging.TLD_ASSIGNMENT);
        transactionTypes.put(TransactionType.TYPE_MESSAGING, messagingTypes);
    }

    private void initColoredCoinsTypes() {
        Map<Byte, TransactionType> coloredCoinsTypes = new HashMap<>();
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_ASSET_ISSUANCE, TransactionType.ColoredCoins.ASSET_ISSUANCE);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_ASSET_TRANSFER, TransactionType.ColoredCoins.ASSET_TRANSFER);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_ASK_ORDER_PLACEMENT, TransactionType.ColoredCoins.ASK_ORDER_PLACEMENT);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_BID_ORDER_PLACEMENT, TransactionType.ColoredCoins.BID_ORDER_PLACEMENT);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_ASK_ORDER_CANCELLATION, TransactionType.ColoredCoins.ASK_ORDER_CANCELLATION);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_BID_ORDER_CANCELLATION, TransactionType.ColoredCoins.BID_ORDER_CANCELLATION);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_ASSET_MINT, TransactionType.ColoredCoins.ASSET_MINT);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_ADD_TREASURY_ACCOUNT, TransactionType.ColoredCoins.ASSET_ADD_TREASURY_ACCOUNT);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_DISTRIBUTE_TO_HOLDERS, TransactionType.ColoredCoins.ASSET_DISTRIBUTE_TO_HOLDERS);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_ASSET_MULTI_TRANSFER, TransactionType.ColoredCoins.ASSET_MULTI_TRANSFER);
        coloredCoinsTypes.put(TransactionType.SUBTYPE_COLORED_COINS_TRANSFER_OWNERSHIP, TransactionType.ColoredCoins.ASSET_TRANSFER_OWNERSHIP);
        transactionTypes.put(TransactionType.TYPE_COLORED_COINS, coloredCoinsTypes);
    }

    private void initDigitalGoodsTypes() {
        Map<Byte, TransactionType> digitalGoodsTypes = new HashMap<>();
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_LISTING, TransactionType.DigitalGoods.LISTING);
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_DELISTING, TransactionType.DigitalGoods.DELISTING);
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_PRICE_CHANGE, TransactionType.DigitalGoods.PRICE_CHANGE);
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_QUANTITY_CHANGE, TransactionType.DigitalGoods.QUANTITY_CHANGE);
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_PURCHASE, TransactionType.DigitalGoods.PURCHASE);
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_DELIVERY, TransactionType.DigitalGoods.DELIVERY);
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_FEEDBACK, TransactionType.DigitalGoods.FEEDBACK);
        digitalGoodsTypes.put(TransactionType.SUBTYPE_DIGITAL_GOODS_REFUND, TransactionType.DigitalGoods.REFUND);
        transactionTypes.put(TransactionType.TYPE_DIGITAL_GOODS, digitalGoodsTypes);
    }

    private void initAutomatedTransactionsTypes() {
        Map<Byte, TransactionType> atTypes = new HashMap<>();
        atTypes.put(TransactionType.SUBTYPE_AT_CREATION, TransactionType.AutomatedTransactions.AUTOMATED_TRANSACTION_CREATION);
        atTypes.put(TransactionType.SUBTYPE_AT_NXT_PAYMENT, TransactionType.AutomatedTransactions.AT_PAYMENT);
        transactionTypes.put(TransactionType.TYPE_AUTOMATED_TRANSACTIONS, atTypes);
    }

    private void initAccountControlTypes() {
        Map<Byte, TransactionType> accountControlTypes = new HashMap<>();
        accountControlTypes.put(TransactionType.SUBTYPE_ACCOUNT_CONTROL_EFFECTIVE_BALANCE_LEASING,
                TransactionType.AccountControl.EFFECTIVE_BALANCE_LEASING);
        transactionTypes.put(TransactionType.TYPE_ACCOUNT_CONTROL, accountControlTypes);
    }

    private void initSignaMiningTypes() {
        Map<Byte, TransactionType> signumMiningTypes = new HashMap<>();
        signumMiningTypes.put(TransactionType.SUBTYPE_SIGNA_MINING_REWARD_RECIPIENT_ASSIGNMENT,
                TransactionType.SignaMining.REWARD_RECIPIENT_ASSIGNMENT);
        signumMiningTypes.put(TransactionType.SUBTYPE_SIGNA_MINING_COMMITMENT_ADD, TransactionType.SignaMining.COMMITMENT_ADD);
        signumMiningTypes.put(TransactionType.SUBTYPE_SIGNA_MINING_COMMITMENT_REMOVE, TransactionType.SignaMining.COMMITMENT_REMOVE);
        transactionTypes.put(TransactionType.TYPE_SIGNA_MINING, signumMiningTypes);
    }

    private void initAdvancedPaymentTypes() {
        Map<Byte, TransactionType> advancedPaymentTypes = new HashMap<>();
        advancedPaymentTypes.put(TransactionType.SUBTYPE_ADVANCED_PAYMENT_ESCROW_CREATION, TransactionType.AdvancedPayment.ESCROW_CREATION);
        advancedPaymentTypes.put(TransactionType.SUBTYPE_ADVANCED_PAYMENT_ESCROW_SIGN, TransactionType.AdvancedPayment.ESCROW_SIGN);
        advancedPaymentTypes.put(TransactionType.SUBTYPE_ADVANCED_PAYMENT_ESCROW_RESULT, TransactionType.AdvancedPayment.ESCROW_RESULT);
        advancedPaymentTypes.put(TransactionType.SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_SUBSCRIBE,
                TransactionType.AdvancedPayment.SUBSCRIPTION_SUBSCRIBE);
        advancedPaymentTypes.put(TransactionType.SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_CANCEL,
                TransactionType.AdvancedPayment.SUBSCRIPTION_CANCEL);
        advancedPaymentTypes.put(TransactionType.SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_PAYMENT,
                TransactionType.AdvancedPayment.SUBSCRIPTION_PAYMENT);
        transactionTypes.put(TransactionType.TYPE_ADVANCED_PAYMENT, advancedPaymentTypes);
    }

    @Override
    public String toString() {
        return "TransactionTypeRegistry{registeredTypes=" + transactionTypes.size() + "}";
    }
}