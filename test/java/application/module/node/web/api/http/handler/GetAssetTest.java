package application.module.node.web.api.http.handler;

import application.module.node.Asset;
import application.module.node.Blockchain;
import application.module.node.Signum;
import application.module.node.SignumException;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.util.JSON;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.ASSET_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class GetAssetTest extends AbstractUnitTest {

    private ParameterService parameterServiceMock;
    private AssetExchange mockAssetExchange;
    private AccountService mockAccountService;

    private GetAsset t;

    @Before
    public void setUp() {
        parameterServiceMock = mock(ParameterService.class);
        mockAssetExchange = mock(AssetExchange.class);
        mockAccountService = mock(AccountService.class);
    }

    @Test
    public void processRequest() throws SignumException {
        final long assetId = 4;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(ASSET_PARAMETER, assetId));

        final Asset asset = mock(Asset.class);
        when(asset.getId()).thenReturn(assetId);
        when(asset.getName()).thenReturn("assetName");
        when(asset.getDescription()).thenReturn("assetDescription");
        when(asset.getDecimals()).thenReturn(Byte.parseByte("3"));

        when(parameterServiceMock.getAsset(eq(req))).thenReturn(asset);

        int tradeCount = 1;
        int transferCount = 2;
        int assetAccountsCount = 3;

        when(mockAssetExchange.getTradeCount(eq(assetId))).thenReturn(tradeCount);
        when(mockAssetExchange.getTransferCount(eq(assetId))).thenReturn(transferCount);
        when(mockAssetExchange.getAssetAccountsCount(eq(asset), eq(0L), eq(true), eq(false)))
                .thenReturn(assetAccountsCount);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            Blockchain mockBlockchain = mock(Blockchain.class);
            mocked.when(Signum::getBlockchain).thenReturn(mockBlockchain);
            when(mockBlockchain.getHeight()).thenReturn(Integer.MAX_VALUE);
            mocked.when(Signum::getFluxCapacitor).thenReturn(QuickMocker.latestValueFluxCapacitor());

            t = new GetAsset(parameterServiceMock, mockAssetExchange, mockAccountService);

            final JsonObject result = (JsonObject) t.processRequest(req);

            assertNotNull(result);
            assertEquals(asset.getName(), JSON.getAsString(result.get(NAME_RESPONSE)));
            assertEquals(asset.getDescription(), JSON.getAsString(result.get(DESCRIPTION_RESPONSE)));
            assertEquals(asset.getDecimals(), JSON.getAsInt(result.get(DECIMALS_RESPONSE)));
            assertEquals("" + asset.getQuantityQnt(), JSON.getAsString(result.get(QUANTITY_QNT_RESPONSE)));
            assertEquals("" + asset.getId(), JSON.getAsString(result.get(ASSET_RESPONSE)));
            assertEquals(tradeCount, JSON.getAsInt(result.get(NUMBER_OF_TRADES_RESPONSE)));
            assertEquals(transferCount, JSON.getAsInt(result.get(NUMBER_OF_TRANSFERS_RESPONSE)));
            assertEquals(assetAccountsCount, JSON.getAsInt(result.get(NUMBER_OF_ACCOUNTS_RESPONSE)));
        }
    }
}