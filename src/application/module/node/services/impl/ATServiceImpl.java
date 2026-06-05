package application.module.node.services.impl;

import application.module.node.at.AT;
import application.module.node.db.store.ATStore;
import application.module.node.services.ATService;
import application.module.node.util.CollectionWithIndex;

import java.util.Collection;

public class ATServiceImpl implements ATService {

    private final ATStore atStore;

    public ATServiceImpl(ATStore atStore) {
        this.atStore = atStore;
    }

    @Override
    public Collection<Long> getAllATIds(Long codeHashId) {
        return atStore.getAllATIds(codeHashId);
    }

    @Override
    public CollectionWithIndex<Long> getATsIssuedBy(Long accountId, Long codeHashId, int from, int to) {
        return new CollectionWithIndex<Long>(atStore.getATsIssuedBy(accountId, codeHashId, from, to), from, to);
    }

    @Override
    public AT getAT(Long id, int height) {
        return atStore.getAT(id, height);
    }

    @Override
    public AT getAT(Long id) {
        return atStore.getAT(id);
    }

}
