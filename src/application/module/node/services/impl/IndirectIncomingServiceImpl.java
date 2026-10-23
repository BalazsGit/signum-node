package application.module.node.services.impl;

import application.module.logging.context.NodeLoggers;
import application.module.node.IndirectIncoming;
import application.module.node.Transaction;
import application.module.node.db.store.IndirectIncomingStore;
import application.module.node.props.PropertyService;
import application.module.node.props.Props;
import application.module.node.services.IndirectIncomingService;
import org.apache.logging.log4j.Logger;

public class IndirectIncomingServiceImpl implements IndirectIncomingService {
    private final Logger logger;

    private final IndirectIncomingStore indirectIncomingStore;
    private final boolean disabled;

    public IndirectIncomingServiceImpl(IndirectIncomingStore indirectIncomingStore, PropertyService propertyService,
            NodeLoggers loggers) {
        this.logger = loggers.forClass(IndirectIncomingServiceImpl.class);
        this.indirectIncomingStore = indirectIncomingStore;
        this.disabled = !propertyService.getBoolean(Props.INDIRECT_INCOMING_SERVICE_ENABLE);
        if (disabled) {
            logger.warn("Indirect Incoming Service Disabled!");
        }
    }

    @Override
    public void processTransaction(Transaction transaction) {
        if (disabled)
            return;
        indirectIncomingStore.addIndirectIncomings(transaction.getType().getIndirectIncomings(transaction));
    }

    @Override
    public boolean isIndirectlyReceiving(Transaction transaction, long accountId) {
        // It would be confusing to have inconsistent behaviour so even when not loading
        // from database we should disable when told to do so.
        if (disabled)
            return false;
        for (IndirectIncoming indirect : transaction.getType().getIndirectIncomings(transaction)) {
            if (indirect.getAccountId() == accountId)
                return true;
        }
        return false;
    }

    @Override
    public void rollback(int height) {
        if (disabled)
            return;
        indirectIncomingStore.rollback(height);
    }

    @Override
    public IndirectIncoming getIndirectIncoming(long accountId, long transactionId) {
        return indirectIncomingStore.getIndirectIncoming(accountId, transactionId);
    }
}
