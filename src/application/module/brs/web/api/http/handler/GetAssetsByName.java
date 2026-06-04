package application.module.brs.web.api.http.handler;

import application.module.brs.Asset;
import application.module.brs.Signum;
import application.module.brs.SignumException;
import application.module.brs.assetexchange.AssetExchange;
import application.module.brs.services.AccountService;
import application.module.brs.util.CollectionWithIndex;
import application.module.brs.util.Convert;
import application.module.brs.util.TextUtils;

import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.JSONResponses.INCORRECT_ASSET_NAME;
import static application.module.brs.web.api.http.common.JSONResponses.MISSING_NAME;
import static application.module.brs.web.api.http.common.Parameters.*;
import static application.module.brs.web.api.http.common.ResultFields.*;

public final class GetAssetsByName extends AbstractAssetsRetrieval {

    private final AssetExchange assetExchange;

    public GetAssetsByName(AssetExchange assetExchange, AccountService accountService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, assetExchange, accountService, NAME_PARAMETER,
                FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER,
                HEIGHT_START_PARAMETER, HEIGHT_END_PARAMETER, SKIP_ZERO_VOLUME_PARAMETER);
        this.assetExchange = assetExchange;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        String name = req.getParameter(NAME_PARAMETER);

        if (name == null) {
            return MISSING_NAME;
        }
        name = name.trim();

        if (name.length() < 1 || !TextUtils.isInAlphabet(name)) {
            return INCORRECT_ASSET_NAME;
        }

        int heightEnd = Signum.getBlockchain().getHeight();
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

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonObject response = new JsonObject();
        CollectionWithIndex<Asset> assets = assetExchange.getAssetsByName(name, firstIndex, lastIndex);
        response.add(ASSETS_RESPONSE, assetsToJson(assets.iterator(),
                heightStart, heightEnd, skipZeroVolume));

        if (assets.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, assets.nextIndex());
        }

        return response;

    }
}
