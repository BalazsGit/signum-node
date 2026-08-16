package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Account.AccountAsset;
import application.module.node.Asset;
import application.module.node.SignumException;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
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
import static application.module.node.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAssetAccounts extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AssetExchange assetExchange;
    private final FluxCapacitor fluxCapacitor;

    public GetAssetAccounts(ParameterService parameterService, AssetExchange assetExchange, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ASSET_PARAMETER, ASSET_IGNORE_TREASURY_PARAMETER,
                QUANTITY_MININUM_QNT_PARAMETER, FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.parameterService = parameterService;
        this.assetExchange = assetExchange;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Asset asset = parameterService.getAsset(req);
        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);
        long minimumQuantity = Convert.parseUnsignedLong(req.getParameter(QUANTITY_MININUM_QNT_PARAMETER));
        // default is to filter out ignored accounts
        boolean filterTreasury = "false".equals(req.getParameter(ASSET_IGNORE_TREASURY_PARAMETER)) ? false : true;

        JsonArray accountAssetsArray = new JsonArray();
        boolean unconfirmed = !fluxCapacitor.getValue(FluxValues.DISTRIBUTION_FIX);
        CollectionWithIndex<AccountAsset> accountAssets = assetExchange.getAssetAccounts(asset,
                filterTreasury, minimumQuantity, unconfirmed, firstIndex, lastIndex);
        for (Account.AccountAsset accountAsset : accountAssets) {
            accountAssetsArray.add(JSONData.accountAsset(accountAsset));
        }

        JsonObject response = new JsonObject();
        response.add("accountAssets", accountAssetsArray);

        if (accountAssets.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, accountAssets.nextIndex());
        }

        return response;
    }
}
