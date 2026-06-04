package application.module.brs.db.store;

import application.module.brs.Subscription;
import application.module.brs.db.SignumKey;
import application.module.brs.db.VersionedEntityTable;

import java.util.Collection;

public interface SubscriptionStore {

    SignumKey.LongKeyFactory<Subscription> getSubscriptionDbKeyFactory();

    VersionedEntityTable<Subscription> getSubscriptionTable();

    void saveSubscriptions(Collection<Subscription> subscriptions);

    Collection<Subscription> getSubscriptionsByParticipant(Long accountId);

    Collection<Subscription> getIdSubscriptions(Long accountId);

    Collection<Subscription> getSubscriptionsToId(Long accountId);

    Collection<Subscription> getUpdateSubscriptions(int timestamp);
}
