package application.module.node.web.api.http.handler;

import application.module.node.Alias;
import application.module.node.Alias.Offer;
import application.module.node.SignumException;
import application.module.node.services.AliasService;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;
import application.module.node.util.TextUtils;

import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.JSONResponses;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.ALIASES_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.NEXT_INDEX_RESPONSE;

public final class GetAliasesByName extends ApiServlet.JsonRequestHandler {

    private final AliasService aliasService;

    public GetAliasesByName(AliasService aliasService) {
        super(new LegacyDocTag[] { LegacyDocTag.ALIASES }, TIMESTAMP_PARAMETER, ALIAS_NAME_PARAMETER,
                FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER);
        this.aliasService = aliasService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        final int timestamp = ParameterParser.getTimestamp(req);

        String aliasName = Convert.emptyToNull(req.getParameter(ALIAS_NAME_PARAMETER));
        if (aliasName != null) {
            aliasName = aliasName.trim();
        }
        if (aliasName == null || aliasName.length() < 1 || !TextUtils.isInAlphabetOrUnderline(aliasName)) {
            return JSONResponses.incorrect(ALIAS_NAME_PARAMETER);
        }
        aliasName = "%" + aliasName.toLowerCase() + "%";

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JsonArray aliases = new JsonArray();
        CollectionWithIndex<Alias> aliasesByOwner = aliasService.getAliasesByOwner(0L, aliasName, null, firstIndex,
                lastIndex);
        for (Alias alias : aliasesByOwner) {
            if (alias.getTimestamp() < timestamp) {
                continue;
            }
            final Offer offer = aliasService.getOffer(alias);
            final Alias tld = aliasService.getTLD(alias.getTld());
            aliases.add(JSONData.alias(alias, tld, offer, 0));
        }

        JsonObject response = new JsonObject();
        response.add(ALIASES_RESPONSE, aliases);

        if (aliasesByOwner.hasNextIndex()) {
            response.addProperty(NEXT_INDEX_RESPONSE, aliasesByOwner.nextIndex());
        }

        return response;
    }

}
