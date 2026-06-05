package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Account.AccountAsset;
import application.module.node.SignumException;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.*;

public final class GetAccountAssets extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AccountService accountService;

    public GetAccountAssets(ParameterService parameterService, AccountService accountService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER, FIRST_INDEX_PARAMETER,
                LAST_INDEX_PARAMETER);
        this.parameterService = parameterService;
        this.accountService = accountService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getAccount(req);

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonObject response = new JsonObject();

        JsonArray assetBalances = new JsonArray();
        JsonArray unconfirmedAssetBalances = new JsonArray();

        CollectionWithIndex<AccountAsset> assets = accountService.getAssets(account.getId(), firstIndex, lastIndex);
        for (Account.AccountAsset accountAsset : assets) {
            JsonObject assetBalance = new JsonObject();
            assetBalance.addProperty(ASSET_RESPONSE, Convert.toUnsignedLong(accountAsset.getAssetId()));
            assetBalance.addProperty(BALANCE_QNT_RESPONSE, String.valueOf(accountAsset.getQuantityQnt()));
            assetBalances.add(assetBalance);
            JsonObject unconfirmedAssetBalance = new JsonObject();
            unconfirmedAssetBalance.addProperty(ASSET_RESPONSE, Convert.toUnsignedLong(accountAsset.getAssetId()));
            unconfirmedAssetBalance.addProperty(UNCONFIRMED_BALANCE_QNT_RESPONSE,
                    String.valueOf(accountAsset.getUnconfirmedQuantityQnt()));
            unconfirmedAssetBalances.add(unconfirmedAssetBalance);
        }

        response.add(ASSET_BALANCES_RESPONSE, assetBalances);
        response.add(UNCONFIRMED_ASSET_BALANCES_RESPONSE, unconfirmedAssetBalances);

        if (assets.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, assets.nextIndex());
        }

        return response;
    }
}
