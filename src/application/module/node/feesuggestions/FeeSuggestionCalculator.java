package application.module.node.feesuggestions;

import application.module.node.Block;
import application.module.node.Blockchain;
import application.module.node.BlockchainProcessor;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.BlockchainProcessor.Event;
import application.module.node.unconfirmedtransactions.UnconfirmedTransactionStore;

import java.util.concurrent.atomic.AtomicReference;

public class FeeSuggestionCalculator {

    private final Blockchain blockchain;
    private final UnconfirmedTransactionStore unconfirmedTransactionStore;
    private final FluxCapacitor fluxCapacitor;

    private AtomicReference<FeeSuggestion> feeSuggestion = new AtomicReference<>();

    public FeeSuggestionCalculator(BlockchainProcessor blockchainProcessor,
            UnconfirmedTransactionStore unconfirmedTransactionStore,
            Blockchain blockchain,
            FluxCapacitor fluxCapacitor) {
        this.blockchain = blockchain;
        this.unconfirmedTransactionStore = unconfirmedTransactionStore;
        this.fluxCapacitor = fluxCapacitor;
        blockchainProcessor.addListener(this::newBlockApplied, Event.AFTER_BLOCK_APPLY);

        // Just an initial guess until we have the unconfirmed transactions information
        long cheap = 1;
        long standard = 1;
        long priority = 3;
        Block lastBlock = blockchain.getLastBlock();
        if (lastBlock != null) {
            standard = Math.max(1, lastBlock.getTransactions().size() - 2);
            priority = lastBlock.getTransactions().size() + 2;
        }

        long FEE_QUANT = fluxCapacitor.getValue(FluxValues.FEE_QUANT);
        feeSuggestion.set(new FeeSuggestion(cheap * FEE_QUANT, standard * FEE_QUANT, priority * FEE_QUANT));
    }

    public FeeSuggestion giveFeeSuggestion() {
        return feeSuggestion.get();
    }

    private void newBlockApplied(Block block) {
        recalculateSuggestion();
    }

    private void recalculateSuggestion() {
        long cheap = unconfirmedTransactionStore.getFreeSlot(15); // should confirm in about 1 hour
        long standard = unconfirmedTransactionStore.getFreeSlot(3); // should confirm in about 15 min
        long priority = unconfirmedTransactionStore.getFreeSlot(1) + 2; // should confirm in the next block

        if (standard <= cheap) {
            standard = cheap + 1;
        }
        if (priority <= standard) {
            priority = standard + 1;
        }

        long FEE_QUANT = fluxCapacitor.getValue(FluxValues.FEE_QUANT);
        long cheapFee = cheap * FEE_QUANT;
        long standardFee = standard * FEE_QUANT;
        long priorityFee = priority * FEE_QUANT;

        feeSuggestion.set(new FeeSuggestion(cheapFee, standardFee, priorityFee));
    }
}