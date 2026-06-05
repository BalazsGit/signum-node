package application.module.node.web.api.http.handler;

import application.module.node.Alias;
import application.module.node.Alias.Offer;
import application.module.node.services.AliasService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterException;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.*;

public final class GetAlias extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AliasService aliasService;

    public GetAlias(ParameterService parameterService, AliasService aliasService) {
        super(new LegacyDocTag[] { LegacyDocTag.ALIASES }, ALIAS_PARAMETER, ALIAS_NAME_PARAMETER, TLD_PARAMETER);
        this.parameterService = parameterService;
        this.aliasService = aliasService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws ParameterException {
        final Alias alias = parameterService.getAlias(req);
        final Offer offer = aliasService.getOffer(alias);
        final Alias tld = aliasService.getTLD(alias.getTld());

        return JSONData.alias(alias, tld, offer, 0);
    }

}
