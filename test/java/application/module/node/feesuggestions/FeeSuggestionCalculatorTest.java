package application.module.node.feesuggestions;

import application.module.node.Block;
import application.module.node.Blockchain;
import application.module.node.BlockchainProcessor;
import application.module.node.Constants;
import application.module.node.BlockchainProcessor.Event;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.unconfirmedtransactions.UnconfirmedTransactionStore;
import application.module.node.util.Listener;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

import static application.module.node.Constants.FEE_QUANT_SIP3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FeeSuggestionCalculatorTest extends AbstractUnitTest {

    private FeeSuggestionCalculator t;

    private BlockchainProcessor blockchainProcessorMock;
    private UnconfirmedTransactionStore unconfirmedTransactionStoreMock;
    private Blockchain blockchainMock;

    private ArgumentCaptor<Listener<Block>> listenerArgumentCaptor;

    @Before
    public void setUp() {
        blockchainProcessorMock = mock(BlockchainProcessor.class);
        unconfirmedTransactionStoreMock = mock(UnconfirmedTransactionStore.class);
        blockchainMock = mock(Blockchain.class);

        Block mockLastBlock = mock(Block.class);
        when(mockLastBlock.getTransactions()).thenReturn(new ArrayList<>());
        when(blockchainMock.getLastBlock()).thenReturn(mockLastBlock);

        FluxCapacitor mockFluxCapacitor = QuickMocker.fluxCapacitorEnabledFunctionalities(FluxValues.PRE_POC2,
                FluxValues.DIGITAL_GOODS_STORE);
        doReturn(Constants.FEE_QUANT_SIP3).when(mockFluxCapacitor).getValue(eq(FluxValues.FEE_QUANT), anyInt());
        doReturn(Constants.FEE_QUANT_SIP3).when(mockFluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

        listenerArgumentCaptor = ArgumentCaptor.forClass(Listener.class);
        when(blockchainProcessorMock.addListener(listenerArgumentCaptor.capture(), eq(Event.AFTER_BLOCK_APPLY)))
                .thenReturn(true);

        t = new FeeSuggestionCalculator(blockchainProcessorMock, unconfirmedTransactionStoreMock, blockchainMock, mockFluxCapacitor);
    }

    @Test
    public void getFeeSuggestion() {

        Block mockBlock1 = mock(Block.class);
        when(mockBlock1.getTransactions()).thenReturn(new ArrayList<>());

        when(unconfirmedTransactionStoreMock.getFreeSlot(eq(15))).thenReturn(1L);
        when(unconfirmedTransactionStoreMock.getFreeSlot(eq(3))).thenReturn(2L);
        when(unconfirmedTransactionStoreMock.getFreeSlot(eq(1))).thenReturn(10L);

        listenerArgumentCaptor.getValue().notify(mockBlock1);

        FeeSuggestion feeSuggestionOne = t.giveFeeSuggestion();
        assertEquals(1 * FEE_QUANT_SIP3, feeSuggestionOne.getCheapFee());
        assertEquals(2 * FEE_QUANT_SIP3, feeSuggestionOne.getStandardFee());
        assertEquals(12 * FEE_QUANT_SIP3, feeSuggestionOne.getPriorityFee());
    }
}
