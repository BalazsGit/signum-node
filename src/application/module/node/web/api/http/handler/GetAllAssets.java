package application.module.node.web.api.http.handler;

import application.module.node.Asset;
import application.module.node.Blockchain;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.services.AccountService;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;

import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.ASSETS_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAllAssets extends AbstractAssetsRetrieval {

    private final AssetExchange assetExchange;
    private final Blockchain blockchain;

    public GetAllAssets(AssetExchange assetExchange, AccountService accountService, Blockchain blockchain) {
        super(new LegacyDocTag[] { LegacyDocTag.AE }, assetExchange, accountService, FIRST_INDEX_PARAMETER,
                LAST_INDEX_PARAMETER, HEIGHT_START_PARAMETER, HEIGHT_END_PARAMETER, SKIP_ZERO_VOLUME_PARAMETER);
        this.assetExchange = assetExchange;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        int heightEnd = blockchain.getHeight();
        // default is one day window
        int heightStart = heightEnd - 360;

        String heightStartString = Convert.emptyToNull(req.getParameter(HEIGHT_START_PARAMETER));
        if (heightStartString != null) {
            heightStart = Integer.parseInt(heightStartString);
        }

        String heightEndString = Convert.emptyToNull(req.getParameter(HEIGHT_END_PARAMETER));
        if (heightEndString != null) {
            heightEnd = Integer.parseInt(heightEndString);
        }

        boolean skipZeroVolume = "true".equalsIgnoreCase(req.getParameter(SKIP_ZERO_VOLUME_PARAMETER));

        JsonObject response = new JsonObject();

        CollectionWithIndex<Asset> assets = assetExchange.getAllAssets(firstIndex, lastIndex);
        response.add(ASSETS_RESPONSE, assetsToJson(assets.iterator(), heightStart, heightEnd, skipZeroVolume));

        if (assets.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, assets.nextIndex());
        }

        return response;
    }

}
