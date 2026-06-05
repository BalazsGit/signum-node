package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Asset;
import application.module.node.AssetTransfer;
import application.module.node.SignumException;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import application.module.node.web.api.http.common.Parameters;
import application.module.node.services.AccountService;
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
import static application.module.node.web.api.http.common.ResultFields.TRANSFERS_RESPONSE;

public final class GetAssetTransfers extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AccountService accountService;
    private final AssetExchange assetExchange;

    public GetAssetTransfers(ParameterService parameterService, AccountService accountService,
            AssetExchange assetExchange) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, ASSET_PARAMETER, ACCOUNT_PARAMETER, FIRST_INDEX_PARAMETER,
                LAST_INDEX_PARAMETER, INCLUDE_ASSET_INFO_PARAMETER);
        this.parameterService = parameterService;
        this.accountService = accountService;
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
        JsonArray transfersData = new JsonArray();
        CollectionWithIndex<AssetTransfer> transfers;
        if (accountId == null) {
            Asset asset = parameterService.getAsset(req);
            transfers = assetExchange.getAssetTransfers(asset.getId(), firstIndex, lastIndex);
        } else if (assetId == null) {
            Account account = parameterService.getAccount(req);
            transfers = accountService.getAssetTransfers(account.getId(), firstIndex, lastIndex);
        } else {
            Asset asset = parameterService.getAsset(req);
            Account account = parameterService.getAccount(req);
            transfers = assetExchange.getAccountAssetTransfers(account.getId(), asset.getId(), firstIndex, lastIndex);
        }
        for (AssetTransfer transfer : transfers) {
            final Asset asset = includeAssetInfo ? assetExchange.getAsset(transfer.getAssetId()) : null;
            transfersData.add(JSONData.assetTransfer(transfer, asset));
        }

        response.add(TRANSFERS_RESPONSE, transfersData);
        if (transfers.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, transfers.nextIndex());
        }

        return response;
    }
}
