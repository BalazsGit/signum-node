package application.module.brs.services;

import application.module.brs.Account;
import application.module.brs.Block;
import application.module.brs.Subscription;

import java.util.Collection;

public interface SubscriptionService {

    Subscription getSubscription(Long id);

    Collection<Subscription> getSubscriptionsByParticipant(Long accountId);

    Collection<Subscription> getSubscriptionsToId(Long accountId);

    void addSubscription(Account sender, long recipientId, Long id, Long amountNQT, int startTimestamp, int frequency);

    boolean isEnabled();

    void applyConfirmed(Block block, int blockchainHeight);

    void removeSubscription(Long id);

    long calculateFees(int timestamp, int height);

    void clearRemovals();

    void addRemoval(Long id);

    long applyUnconfirmed(int timestamp, int blockchainHeight);
}
