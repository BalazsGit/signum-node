package application.module.brs.web.api.http.handler;

import application.module.brs.SignumException;
import application.module.brs.Order;
import application.module.brs.Order.Ask;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.services.ParameterService;
import application.module.brs.util.CollectionWithIndex;
import application.module.brs.util.Convert;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.*;
import static application.module.brs.web.api.http.common.ResultFields.ASK_ORDER_IDS_RESPONSE;
import static application.module.brs.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAskOrderIds extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AssetExchange assetExchange;

    public GetAskOrderIds(ParameterService parameterService, AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ASSET_PARAMETER, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.parameterService = parameterService;
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        long assetId = parameterService.getAsset(req).getId();
        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonArray orderIds = new JsonArray();
        CollectionWithIndex<Ask> orders = assetExchange.getSortedAskOrders(assetId, firstIndex, lastIndex);
        for (Order.Ask askOrder : orders) {
            orderIds.add(Convert.toUnsignedLong(askOrder.getId()));
        }

        JsonObject response = new JsonObject();
        response.add(ASK_ORDER_IDS_RESPONSE, orderIds);

        if (orders.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, orders.nextIndex());
        }

        return response;
    }
}
