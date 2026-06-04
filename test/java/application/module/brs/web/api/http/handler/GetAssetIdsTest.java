package application.module.brs.web.api.http.handler;

import application.module.brs.Asset;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.common.AbstractUnitTest;
import application.module.brs.common.QuickMocker;
import application.module.brs.common.QuickMocker.MockParam;
import application.module.brs.util.CollectionWithIndex;
import application.module.brs.util.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.brs.web.api.http.common.Parameters.FIRST_INDEX_PARAMETER;
import static application.module.brs.web.api.http.common.Parameters.LAST_INDEX_PARAMETER;
import static application.module.brs.web.api.http.common.ResultFields.ASSET_IDS_RESPONSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

;

public class GetAssetIdsTest extends AbstractUnitTest {

    private GetAssetIds t;

    private AssetExchange mockAssetExchange;

    @Before
    public void setUp() {
        mockAssetExchange = mock(AssetExchange.class);

        t = new GetAssetIds(mockAssetExchange);
    }

    @Test
    public void processRequest() {
        int firstIndex = 1;
        int lastIndex = 2;

        final Asset mockAsset = mock(Asset.class);
        when(mockAsset.getId()).thenReturn(5L);

        final Collection<Asset> mockAssetIterator = mockCollection(mockAsset);

        when(mockAssetExchange.getAllAssets(eq(firstIndex), eq(lastIndex)))
                .thenReturn(new CollectionWithIndex<Asset>(mockAssetIterator, -1));

        final HttpServletRequest req = QuickMocker.httpServletRequest(
                new MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                new MockParam(LAST_INDEX_PARAMETER, lastIndex));

        final JsonObject result = (JsonObject) t.processRequest(req);

        assertNotNull(result);

        final JsonArray resultAssetIds = (JsonArray) result.get(ASSET_IDS_RESPONSE);
        assertNotNull(resultAssetIds);
        assertEquals(1, resultAssetIds.size());

        final String resultAssetId = JSON.getAsString(resultAssetIds.get(0));
        assertEquals("5", resultAssetId);
    }

}
