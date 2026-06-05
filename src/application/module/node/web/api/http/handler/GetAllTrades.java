package application.module.node.web.api.http.handler;

import application.module.node.Asset;
import application.module.node.SignumException;
import application.module.node.Trade;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import application.module.node.web.api.http.common.Parameters;
import application.module.node.util.FilteringIterator;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.TRADES_RESPONSE;

public final class GetAllTrades extends ApiServlet.JsonRequestHandler {

    private final AssetExchange assetExchange;

    public GetAllTrades(AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, TIMESTAMP_PARAMETER, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER,
                INCLUDE_ASSET_INFO_PARAMETER);
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        final int timestamp = ParameterParser.getTimestamp(req);
        final int firstIndex = ParameterParser.getFirstIndex(req);
        final int lastIndex = ParameterParser.getLastIndex(req);
        final boolean includeAssetInfo = !Parameters.isFalse(req.getParameter(INCLUDE_ASSET_INFO_PARAMETER));

        final JsonObject response = new JsonObject();
        final JsonArray trades = new JsonArray();

        FilteringIterator<Trade> tradeIterator = new FilteringIterator<>(
                assetExchange.getAllTrades(0, -1).getCollection(),
                trade -> trade.getTimestamp() >= timestamp, firstIndex, lastIndex);
        Asset asset = null;
        while (tradeIterator.hasNext()) {
            final Trade trade = tradeIterator.next();
            if (includeAssetInfo && (asset == null || asset.getId() != trade.getAssetId())) {
                asset = assetExchange.getAsset(trade.getAssetId());
            }

            trades.add(JSONData.trade(trade, asset));
        }

        response.add(TRADES_RESPONSE, trades);
        return response;
    }

}
