package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Asset;
import application.module.node.SignumException;
import application.module.node.Trade;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import application.module.node.web.api.http.common.Parameters;
import application.module.node.services.ParameterService;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;

import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.TRADES_RESPONSE;

public final class GetTrades extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AssetExchange assetExchange;

    public GetTrades(ParameterService parameterService, AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ASSET_PARAMETER, ACCOUNT_PARAMETER, FIRST_INDEX_PARAMETER,
                LAST_INDEX_PARAMETER, INCLUDE_ASSET_INFO_PARAMETER);
        this.parameterService = parameterService;
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        String assetId = Convert.emptyToNull(req.getParameter(ASSET_PARAMETER));
        String accountId = Convert.emptyToNull(req.getParameter(ACCOUNT_PARAMETER));

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);
        boolean includeAssetInfo = !Parameters.isFalse(req.getParameter(INCLUDE_ASSET_INFO_PARAMETER));

        JsonObject response = new JsonObject();
        JsonArray tradesData = new JsonArray();
        CollectionWithIndex<Trade> trades;
        Asset asset = null;
        if (accountId == null) {
            asset = parameterService.getAsset(req);
            trades = assetExchange.getTrades(asset.getId(), firstIndex, lastIndex);
        } else if (assetId == null) {
            Account account = parameterService.getAccount(req);
            trades = assetExchange.getAccountTrades(account.getId(), firstIndex, lastIndex);
        } else {
            asset = parameterService.getAsset(req);
            Account account = parameterService.getAccount(req);
            trades = assetExchange.getAccountAssetTrades(account.getId(), asset.getId(), firstIndex, lastIndex);
        }
        if (!includeAssetInfo) {
            asset = null;
        }
        for (Trade trade : trades) {
            if (includeAssetInfo && (asset == null || asset.getId() != trade.getAssetId())) {
                asset = assetExchange.getAsset(trade.getAssetId());
            }
            tradesData.add(JSONData.trade(trade, asset));
        }
        response.add(TRADES_RESPONSE, tradesData);
        if (trades.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, trades.nextIndex());
        }

        return response;
    }
}
