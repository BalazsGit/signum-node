package application.module.node.services.impl;

import static application.module.node.schema.Tables.ALIAS;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

import org.jooq.SelectJoinStep;

import application.module.node.Account;
import application.module.node.Alias;
import application.module.node.Alias.Offer;
import application.module.node.Attachment;
import application.module.node.Subscription;
import application.module.node.Transaction;
import application.module.node.TransactionType;
import application.module.node.db.SignumKey;
import application.module.node.db.VersionedEntityTable;
import application.module.node.db.sql.Db;
import application.module.node.db.store.AliasStore;
import application.module.node.db.store.Stores;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.props.Props;
import application.module.node.props.PropertyService;
import application.module.node.services.AliasService;
import application.module.node.services.SubscriptionService;
import application.module.node.util.CollectionWithIndex;
import signumj.crypto.SignumCrypto;

public class AliasServiceImpl implements AliasService {

    private final AliasStore aliasStore;
    private final VersionedEntityTable<Alias> aliasTable;
    private final SignumKey.LongKeyFactory<Alias> aliasDbKeyFactory;
    private final VersionedEntityTable<Offer> offerTable;
    private final SignumKey.LongKeyFactory<Offer> offerDbKeyFactory;
    private final Stores stores;
    private final FluxCapacitor fluxCapacitor;
    private final PropertyService propertyService;
    private SubscriptionService subscriptionService;

    private static final String MAIN_TLD = "signum";
    private static final String[] DEFAULT_TLDS = {
            "blockchain", "coin", "crypto", "dao", "decentral", "dex", "free", "nft", "p2p", "sig", "signa", "sns",
            "w3", "wallet", "web3", "x", "y", "z"
    };

    public AliasServiceImpl(AliasStore aliasStore, Stores stores, FluxCapacitor fluxCapacitor,
            PropertyService propertyService) {
        this.aliasStore = aliasStore;
        this.aliasTable = aliasStore.getAliasTable();
        this.aliasDbKeyFactory = aliasStore.getAliasDbKeyFactory();
        this.offerTable = aliasStore.getOfferTable();
        this.offerDbKeyFactory = aliasStore.getOfferDbKeyFactory();
        this.stores = stores;
        this.fluxCapacitor = fluxCapacitor;
        this.propertyService = propertyService;
    }

    /**
     * Set the subscription service after it's created (avoids circular dependency).
     */
    public void setSubscriptionService(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    public void addDefaultTLDs() {
        try {
            this.stores.beginTransaction();

            // TODO: should be removed prior to the next release
            // try {
            // Statement selectTx = Db.getConnection().createStatement();
            // selectTx.executeUpdate(
            // "update alias set latest=1 where alias_name like 'signum' and height=0;" +
            // "delete from alias where alias_name like 'signum' and height <>0 and tld is
            // null;"
            // );
            // }
            // catch (Exception e) {
            // e.printStackTrace();
            // }
            // // TODO: end of DB patch, to be removed

            if (aliasStore.getTLD(MAIN_TLD) == null) {
                Attachment.MessagingTldAssignment attachment = new Attachment.MessagingTldAssignment(fluxCapacitor, MAIN_TLD, 0);
                addTLD(0L, null, attachment);
            }
            for (String tldName : DEFAULT_TLDS) {
                Alias tld = aliasStore.getTLD(tldName);
                if (tld != null) {
                    continue;
                }

                SignumCrypto crypto = SignumCrypto.getInstance();
                long id = crypto.hashToId(crypto.getSha256().digest(tldName.getBytes(StandardCharsets.UTF_8)))
                        .getSignedLongId();
                Attachment.MessagingTldAssignment attachment = new Attachment.MessagingTldAssignment(fluxCapacitor, tldName, 0);
                addTLD(id, null, attachment);
            }
            this.stores.commitTransaction();
        } finally {
            this.stores.endTransaction();
        }
    }

    public Alias getAlias(String aliasName, long tld) {
        return aliasStore.getAlias(aliasName, tld);
    }

    public Alias getAlias(long id) {
        return aliasTable.get(aliasDbKeyFactory.newKey(id));
    }

    public Alias getTLD(String tldName) {
        return aliasStore.getTLD(tldName);
    }

    public Alias getTLD(Long tldId) {
        return tldId == null ? null : aliasStore.getTLD(tldId);
    }

    @Override
    public Offer getOffer(Alias alias) {
        return offerTable.get(offerDbKeyFactory.newKey(alias.getId()));
    }

    @Override
    public int getAliasCount() {
        return aliasTable.getCount();
    }

    @Override
    public int getAliasCount(long tld) {
        return Db.fetchWithDSLContext(ctx -> {
            SelectJoinStep<?> r = ctx.selectCount().from(ALIAS);
            return (r.where(ALIAS.LATEST.isTrue()).and(ALIAS.TLD.eq(tld))).fetchOne(0, int.class);
        });
    }

    @Override
    public CollectionWithIndex<Alias> getAliasesByOwner(long accountId, String name, Long tld, int from, int to) {
        return new CollectionWithIndex<Alias>(aliasStore.getAliasesByOwner(accountId, name, tld, from, to), from, to);
    }

    @Override
    public CollectionWithIndex<Alias> getTLDs(int from, int to) {
        return new CollectionWithIndex<Alias>(aliasStore.getTLDs(from, to), from, to);
    }

    @Override
    public Collection<Alias> getTLDs(long accountId) {
        return aliasStore.getAliasesByOwner(accountId, null, null, 0, -1);
    }

    @Override
    public CollectionWithIndex<Alias.Offer> getAliasOffers(long account, long buyer, int from, int to) {
        return new CollectionWithIndex<Alias.Offer>(aliasStore.getAliasOffers(account, buyer, from, to), from, to);
    }

    private void createSubscription(Alias alias, int timestamp, boolean updateSubscription) {
        if (!this.fluxCapacitor.getValue(FluxValues.SMART_ALIASES)) {
            return;
        }

        int frequency = this.propertyService.getInt(Props.ALIAS_RENEWAL_FREQUENCY);
        long fee = this.fluxCapacitor.getValue(FluxValues.FEE_QUANT)
                * TransactionType.BASELINE_ALIAS_RENEWAL_FACTOR;
        Subscription subscription = this.subscriptionService.getSubscription(alias.getId());
        if (subscription != null && updateSubscription && subscription.getSenderId() != alias.getAccountId()) {
            subscription.setSenderId(alias.getAccountId());
            ArrayList<Subscription> subscriptions = new ArrayList<>();
            subscriptions.add(subscription);
            this.stores.getSubscriptionStore().saveSubscriptions(subscriptions);
        }
        if (subscription == null) {
            this.subscriptionService.addSubscription(Account.getAccount(stores.getAccountStore(), alias.getAccountId()), alias.getId(), alias.getId(),
                    fee, timestamp, frequency);
        }
    }

    @Override
    public void addOrUpdateAlias(Transaction transaction, Attachment.MessagingAliasAssignment attachment) {
        Alias alias = getAlias(attachment.getAliasName(), attachment.getTld());
        if (alias == null) {
            SignumKey aliasDBId = aliasDbKeyFactory.newKey(transaction.getId());
            alias = new Alias(transaction.getId(), aliasDBId, transaction, attachment);
        } else {
            alias.setAccountId(transaction.getSenderId());
            alias.setAliasUri(attachment.getAliasUri());
            alias.setTimestamp(transaction.getBlockTimestamp());
        }
        aliasTable.insert(alias);

        createSubscription(alias, transaction.getBlockTimestamp(), true);
    }

    @Override
    public void addTLD(long id, Transaction transaction, Attachment.MessagingTldAssignment attachment) {
        SignumKey aliasDBId = aliasDbKeyFactory.newKey(id);
        Alias alias = new Alias(id, aliasDBId, transaction, attachment);
        aliasTable.insert(alias);
    }

    @Override
    public void sellAlias(Transaction transaction, Attachment.MessagingAliasSell attachment) {
        final long priceNQT = attachment.getPriceNqt();
        final long buyerId = transaction.getRecipientId();
        Alias alias = attachment.getVersion() > 1 ? getAlias(attachment.getAliasId())
                : getAlias(attachment.getAliasName(), 0L);
        if (priceNQT > 0) {
            Offer offer = getOffer(alias);
            if (offer == null) {
                SignumKey dbKey = offerDbKeyFactory.newKey(alias.getId());
                offerTable.insert(new Offer(dbKey, alias.getId(), priceNQT, buyerId));
            } else {
                offer.setPriceNqt(priceNQT);
                offer.setBuyerId(buyerId);
                offerTable.insert(offer);
            }
        } else {
            changeOwner(buyerId, alias, transaction.getBlockTimestamp(), false);
        }
    }

    @Override
    public void changeOwner(long newOwnerId, Alias alias, int timestamp, boolean updateSubscription) {
        alias.setAccountId(newOwnerId);
        alias.setTimestamp(timestamp);
        aliasTable.insert(alias);

        final Offer offer = getOffer(alias);
        offerTable.delete(offer);

        if (alias.getTld() != null) {
            // only create the subscription if this is not a TLD (that has a null TLD)
            createSubscription(alias, timestamp, updateSubscription);
        }
    }
}