package application.module.brs.web.api.http.handler;

import application.module.brs.Asset;
import application.module.brs.Order;
import application.module.brs.Order.Bid;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.util.CollectionWithIndex;

import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.FIRST_INDEX_PARAMETER;
import static application.module.brs.web.api.http.common.Parameters.LAST_INDEX_PARAMETER;
import static application.module.brs.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAllOpenBidOrders extends ApiServlet.JsonRequestHandler {

    private final AssetExchange assetExchange;

    public GetAllOpenBidOrders(AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {

        JsonObject response = new JsonObject();
        JsonArray ordersData = new JsonArray();

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        Asset asset = null;
        CollectionWithIndex<Bid> orders = assetExchange.getAllBidOrders(firstIndex, lastIndex);
        for (Order.Bid bidOrder : orders) {
            if (asset == null || asset.getId() != bidOrder.getAssetId()) {
                asset = assetExchange.getAsset(bidOrder.getAssetId());
            }
            ordersData.add(JSONData.bidOrder(bidOrder, asset));
        }

        response.add("openOrders", ordersData);

        if (orders.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, orders.nextIndex());
        }

        return response;
    }

}
