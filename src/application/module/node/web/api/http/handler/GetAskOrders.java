package application.module.node.web.api.http.handler;

import application.module.node.Asset;
import application.module.node.SignumException;
import application.module.node.Order;
import application.module.node.Order.Ask;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.services.ParameterService;
import application.module.node.util.CollectionWithIndex;

import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.ASK_ORDERS_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAskOrders extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AssetExchange assetExchange;

    public GetAskOrders(ParameterService parameterService, AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ASSET_PARAMETER, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.parameterService = parameterService;
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        long assetId = parameterService.getAsset(req).getId();
        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonArray ordersData = new JsonArray();
        Asset asset = null;
        CollectionWithIndex<Ask> orders = assetExchange.getSortedAskOrders(assetId, firstIndex, lastIndex);
        for (Order.Ask askOrder : orders) {
            if (asset == null || asset.getId() != askOrder.getAssetId()) {
                asset = assetExchange.getAsset(askOrder.getAssetId());
            }
            ordersData.add(JSONData.askOrder(askOrder, asset));
        }

        JsonObject response = new JsonObject();
        response.add(ASK_ORDERS_RESPONSE, ordersData);

        if (orders.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, orders.nextIndex());
        }

        return response;
    }
}
