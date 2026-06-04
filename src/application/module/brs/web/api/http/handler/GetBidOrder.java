package application.module.brs.web.api.http.handler;

import application.module.brs.Asset;
import application.module.brs.SignumException;
import application.module.brs.Order;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.JSONResponses.UNKNOWN_ORDER;
import static application.module.brs.web.api.http.common.Parameters.ORDER_PARAMETER;

public final class GetBidOrder extends ApiServlet.JsonRequestHandler {

    private final AssetExchange assetExchange;

    public GetBidOrder(AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ORDER_PARAMETER);
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        long orderId = ParameterParser.getOrderId(req);
        Order.Bid bidOrder = assetExchange.getBidOrder(orderId);

        if (bidOrder == null) {
            return UNKNOWN_ORDER;
        }
        Asset asset = assetExchange.getAsset(bidOrder.getAssetId());

        return JSONData.bidOrder(bidOrder, asset);
    }

}
