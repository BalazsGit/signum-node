package application.module.node.web.api.http.handler;

import application.module.node.Asset;
import application.module.node.Blockchain;
import application.module.node.Signum;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.common.AbstractUnitTest;
import application.module.node.common.QuickMocker;
import application.module.node.common.QuickMocker.MockParam;
import application.module.node.services.AccountService;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.node.web.api.http.common.Parameters.FIRST_INDEX_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.LAST_INDEX_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class GetAllAssetsTest extends AbstractUnitTest {

    private GetAllAssets t;

    private AssetExchange assetExchange;
    private AccountService accountService;

    @Before
    public void setUp() {
        assetExchange = mock(AssetExchange.class);
        accountService = mock(AccountService.class);
    }

    @Test
    public void processRequest() {
        final int firstIndex = 1;
        final int lastIndex = 2;

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                new MockParam(LAST_INDEX_PARAMETER, lastIndex));

        final long mockAssetId = 1;

        final Asset mockAsset = mock(Asset.class);
        when(mockAsset.getId()).thenReturn(1L);
        when(mockAsset.getId()).thenReturn(mockAssetId);
        when(mockAsset.getName()).thenReturn("name");
        when(mockAsset.getDescription()).thenReturn("description");
        when(mockAsset.getDecimals()).thenReturn((byte) 1);
        when(mockAsset.getQuantityQnt()).thenReturn(2L);

        final Collection<Asset> mockAssetIterator = mockCollection(mockAsset);

        when(assetExchange.getAllAssets(eq(firstIndex), eq(lastIndex)))
                .thenReturn(new CollectionWithIndex<Asset>(mockAssetIterator, -1));
        when(assetExchange.getAssetAccountsCount(eq(mockAsset), eq(0L), eq(true), eq(true))).thenReturn(1);
        when(assetExchange.getTransferCount(eq(mockAssetId))).thenReturn(2);
        when(assetExchange.getTradeCount(eq(mockAssetId))).thenReturn(3);

        try (MockedStatic<Signum> mocked = mockStatic(Signum.class)) {
            Blockchain mockBlockchain = mock(Blockchain.class);
            mocked.when(Signum::getBlockchain).thenReturn(mockBlockchain);
            when(mockBlockchain.getHeight()).thenReturn(Integer.MAX_VALUE);

            t = new GetAllAssets(assetExchange, accountService);

            final JsonObject result = (JsonObject) t.processRequest(req);
            assertNotNull(result);

            final JsonArray assetsResult = (JsonArray) result.get(ASSETS_RESPONSE);
            assertNotNull(assetsResult);
            assertEquals(1, assetsResult.size());

            final JsonObject assetResult = (JsonObject) assetsResult.get(0);
            assertNotNull(assetResult);

            assertEquals(mockAsset.getName(), JSON.getAsString(assetResult.get(NAME_RESPONSE)));
            assertEquals(mockAsset.getDescription(), JSON.getAsString(assetResult.get(DESCRIPTION_RESPONSE)));
            assertEquals(mockAsset.getDecimals(), JSON.getAsByte(assetResult.get(DECIMALS_RESPONSE)));
            assertEquals("" + mockAsset.getQuantityQnt(), JSON.getAsString(assetResult.get(QUANTITY_QNT_RESPONSE)));
            assertEquals("" + mockAsset.getId(), JSON.getAsString(assetResult.get(ASSET_RESPONSE)));
        }
    }

}