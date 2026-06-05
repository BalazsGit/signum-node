package application.module.node.services;

import application.module.node.at.AT;
import application.module.node.util.CollectionWithIndex;

import java.util.Collection;

public interface ATService {

    Collection<Long> getAllATIds(Long codeHashId);

    CollectionWithIndex<Long> getATsIssuedBy(Long accountId, Long codeHashId, int from, int to);

    AT getAT(Long atId);

    AT getAT(Long atId, int height);
}
