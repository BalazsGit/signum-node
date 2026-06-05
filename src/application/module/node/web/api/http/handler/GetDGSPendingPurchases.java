package application.module.node.web.api.http.handler;

import application.module.node.SignumException;
import application.module.node.DigitalGoodsStore;
import application.module.node.services.DGSGoodsStoreService;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.MISSING_SELLER;
import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.PURCHASES_RESPONSE;

public final class GetDGSPendingPurchases extends ApiServlet.JsonRequestHandler {

    private final DGSGoodsStoreService dgsGoodStoreService;

    public GetDGSPendingPurchases(DGSGoodsStoreService dgsGoodStoreService) {
        super(new LegacyDocTag[] { LegacyDocTag.DGS }, SELLER_PARAMETER, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.dgsGoodStoreService = dgsGoodStoreService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        long sellerId = ParameterParser.getSellerId(req);

        if (sellerId == 0) {
            return MISSING_SELLER;
        }

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonObject response = new JsonObject();
        JsonArray purchasesJSON = new JsonArray();

        for (DigitalGoodsStore.Purchase purchase : dgsGoodStoreService.getPendingSellerPurchases(sellerId, firstIndex,
                lastIndex)) {
            purchasesJSON.add(JSONData.purchase(purchase));
        }

        response.add(PURCHASES_RESPONSE, purchasesJSON);
        return response;
    }

}
