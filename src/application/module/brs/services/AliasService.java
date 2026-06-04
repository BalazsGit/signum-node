package application.module.brs.services;

import java.util.Collection;

import application.module.brs.Alias;
import application.module.brs.Alias.Offer;
import application.module.brs.Attachment;
import application.module.brs.Transaction;
import application.module.brs.util.CollectionWithIndex;

public interface AliasService {

    Alias getAlias(long aliasId);

    Alias getAlias(String aliasName, long tld);

    Alias getTLD(String tldName);

    Alias getTLD(Long tldId);

    Offer getOffer(Alias alias);

    int getAliasCount();

    int getAliasCount(long tld);

    CollectionWithIndex<Alias> getAliasesByOwner(long accountId, String name, Long tld, int from, int to);

    CollectionWithIndex<Alias> getTLDs(int from, int to);

    Collection<Alias> getTLDs(long accountId);

    CollectionWithIndex<Alias.Offer> getAliasOffers(long account, long buyer, int from, int to);

    void addOrUpdateAlias(Transaction transaction, Attachment.MessagingAliasAssignment attachment);

    void addTLD(long id, Transaction transaction, Attachment.MessagingTldAssignment attachment);

    void addDefaultTLDs();

    void sellAlias(Transaction transaction, Attachment.MessagingAliasSell attachment);

    void changeOwner(long newOwnerId, Alias alias, int timestamp, boolean updateSubscription);
}
