package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Asset;
import application.module.node.SignumException;
import application.module.node.Order;
import application.module.node.Trade;
import application.module.node.TransactionType;
import application.module.node.Order.OrderJournal;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.services.ParameterService;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.*;

public final class GetTradeJournal extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AssetExchange assetExchange;

    public GetTradeJournal(ParameterService parameterService, AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ASSET_PARAMETER, ACCOUNT_PARAMETER, FIRST_INDEX_PARAMETER,
                LAST_INDEX_PARAMETER);
        this.parameterService = parameterService;
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        final Account account = parameterService.getAccount(req);

        String assetValue = Convert.emptyToNull(req.getParameter(ASSET_PARAMETER));
        long assetId = assetValue == null ? 0L : Convert.parseUnsignedLong(assetValue);

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonObject response = new JsonObject();
        JsonArray journalData = new JsonArray();

        JSONData.putAccount(response, ACCOUNT_RESPONSE, account.getId());
        if (assetId != 0L) {
            response.addProperty(ASSET_RESPONSE, Convert.toUnsignedLong(assetId));
        }

        CollectionWithIndex<OrderJournal> orders = assetExchange.getOrderJournal(account.getId(), assetId, firstIndex,
                lastIndex);
        Asset asset = null;
        for (OrderJournal order : orders) {
            if (asset == null || asset.getId() != order.getAssetId()) {
                asset = assetExchange.getAsset(order.getAssetId());
            }
            JsonObject orderData = JSONData.order(order, asset);

            orderData.addProperty(TIMESTAMP_RESPONSE, order.getTimestamp());
            orderData.addProperty(EXECUTED_QUANTITY_QNT_RESPONSE, String.valueOf(order.getExecutedAmountQNT()));
            orderData.addProperty(EXECUTED_VOLUME_NQT_RESPONSE, String.valueOf(order.getExecutedVolumeNQT()));
            orderData.addProperty(TYPE_RESPONSE,
                    order.getSubtype() == TransactionType.SUBTYPE_COLORED_COINS_ASK_ORDER_PLACEMENT ? "ask" : "bid");
            orderData.addProperty(STATUS_RESPONSE, order.getStatus() == Order.ORDER_STATUS_OPEN ? "open"
                    : order.getStatus() == Order.ORDER_STATUS_FILLED ? "filled" : "cancelled");

            JsonArray tradesData = new JsonArray();
            for (Trade trade : order.getTrades()) {
                JsonObject tradeJson = JSONData.trade(trade, asset);
                tradeJson.remove(NAME_RESPONSE);
                tradeJson.remove(DECIMALS_RESPONSE);
                tradesData.add(tradeJson);
            }
            orderData.add(TRADES_RESPONSE, tradesData);

            journalData.add(orderData);
        }
        response.add(TRADE_JOURNAL_RESPONSE, journalData);

        if (orders.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, orders.nextIndex());
        }

        return response;
    }
}
