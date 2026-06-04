package application.module.brs.services.impl;

import application.module.brs.at.AT;
import application.module.brs.db.store.ATStore;
import application.module.brs.services.ATService;
import application.module.brs.util.CollectionWithIndex;

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
