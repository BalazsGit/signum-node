package application.module.node.web.api.http.handler;

import application.module.node.Asset;
import application.module.node.SignumException;
import application.module.node.Order;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.node.web.api.http.common.Parameters.ORDER_PARAMETER;

public final class GetAskOrder extends ApiServlet.JsonRequestHandler {

    private final AssetExchange assetExchange;

    public GetAskOrder(AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ORDER_PARAMETER);
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        long orderId = ParameterParser.getOrderId(req);
        Order.Ask askOrder = assetExchange.getAskOrder(orderId);
        if (askOrder == null) {
            return UNKNOWN_ORDER;
        }
        Asset asset = assetExchange.getAsset(askOrder.getAssetId());
        return JSONData.askOrder(askOrder, asset);
    }

}
