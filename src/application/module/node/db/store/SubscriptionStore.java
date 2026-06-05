package application.module.node.db.store;

import application.module.node.Subscription;
import application.module.node.db.SignumKey;
import application.module.node.db.VersionedEntityTable;

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
