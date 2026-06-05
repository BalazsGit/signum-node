package application.module.node.web.api.http.handler;

import application.module.node.Asset;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.FIRST_INDEX_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.LAST_INDEX_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.ASSET_IDS_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAssetIds extends ApiServlet.JsonRequestHandler {

    private final AssetExchange assetExchange;

    public GetAssetIds(AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonArray assetIds = new JsonArray();
        CollectionWithIndex<Asset> assets = assetExchange.getAllAssets(firstIndex, lastIndex);
        for (Asset asset : assets) {
            assetIds.add(Convert.toUnsignedLong(asset.getId()));
        }
        JsonObject response = new JsonObject();
        response.add(ASSET_IDS_RESPONSE, assetIds);

        if (assets.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, assets.nextIndex());
        }

        return response;
    }

}
