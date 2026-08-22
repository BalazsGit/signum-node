package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.TransactionType.ColoredCoins.ASSET_TRANSFER;
import static application.module.node.web.api.http.common.JSONResponses.NOT_ENOUGH_ASSETS;
import static application.module.node.web.api.http.common.Parameters.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class TransferAssetTest extends AbstractTransactionTest {

    private TransferAsset t;

    @Mock
    private ParameterService mockParameterService;
    @Mock
    private Blockchain mockBlockchain;
    @Mock
    private APITransactionManager apiTransactionManagerMock;
    @Mock
    private AccountService mockAccountService;

    @BeforeEach
    void setUp() {
        FluxCapacitor fluxCapacitor = QuickMocker.latestValueFluxCapacitor();
        t = new TransferAsset(mockParameterService, mockBlockchain, apiTransactionManagerMock, mockAccountService, fluxCapacitor);
    }

    @Test
    void processRequest() throws SignumException {
        final long recipientParameter = 34L;
        final long assetIdParameter = 456L;
        final long quantityQNTParameter = 56L;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(RECIPIENT_PARAMETER, recipientParameter),
                new MockParam(ASSET_PARAMETER, assetIdParameter),
                new MockParam(QUANTITY_QNT_PARAMETER, quantityQNTParameter));

        Asset mockAsset = mock(Asset.class);

        when(mockParameterService.getAsset(eq(req))).thenReturn(mockAsset);
        when(mockAsset.getId()).thenReturn(assetIdParameter);

        final Account mockSenderAccount = mock(Account.class);
        when(mockAccountService.getUnconfirmedAssetBalanceQNT(eq(mockSenderAccount), eq(assetIdParameter)))
                .thenReturn(500L);

        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSenderAccount);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(Constants.FEE_QUANT_SIP3).when(fluxCapacitor).getValue(eq(FluxValues.FEE_QUANT));

            final Attachment.ColoredCoinsAssetTransfer attachment = (Attachment.ColoredCoinsAssetTransfer) attachmentCreatedTransaction(
                    () -> t.processRequest(req), apiTransactionManagerMock);
            assertNotNull(attachment);

            assertEquals(ASSET_TRANSFER, attachment.getTransactionType());
            assertEquals(assetIdParameter, attachment.getAssetId());
            assertEquals(quantityQNTParameter, attachment.getQuantityQnt());
        }
    }

    @Test
    void processRequest_assetBalanceLowerThanQuantityNQTParameter() throws SignumException {
        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(RECIPIENT_PARAMETER, "123"),
                new MockParam(ASSET_PARAMETER, "456"),
                new MockParam(QUANTITY_QNT_PARAMETER, "5"));

        Asset mockAsset = mock(Asset.class);

        when(mockParameterService.getAsset(eq(req))).thenReturn(mockAsset);
        when(mockAsset.getId()).thenReturn(456l);

        final Account mockSenderAccount = mock(Account.class);
        when(mockParameterService.getSenderAccount(eq(req))).thenReturn(mockSenderAccount);

        when(mockAccountService.getUnconfirmedAssetBalanceQNT(eq(mockSenderAccount), anyLong())).thenReturn(2L);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            final FluxCapacitor fluxCapacitor = QuickMocker
                    .fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
            doReturn(false).when(fluxCapacitor).getValue(eq(FluxValues.SMART_TOKEN));

            assertEquals(NOT_ENOUGH_ASSETS, t.processRequest(req));
        }
    }
}