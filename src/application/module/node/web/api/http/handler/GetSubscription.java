package application.module.node.web.api.http.handler;

import application.module.node.Blockchain;

import application.module.node.Alias;
import application.module.node.Subscription;
import application.module.node.Transaction;
import application.module.node.services.AliasService;
import application.module.node.services.SubscriptionService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.SUBSCRIPTION_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.ERROR_CODE_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.ERROR_DESCRIPTION_RESPONSE;

public final class GetSubscription extends ApiServlet.JsonRequestHandler {

    private final SubscriptionService subscriptionService;
    private final AliasService aliasService;
    private final Blockchain blockchain;

    public GetSubscription(SubscriptionService subscriptionService, AliasService aliasService, Blockchain blockchain) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, SUBSCRIPTION_PARAMETER);
        this.subscriptionService = subscriptionService;
        this.aliasService = aliasService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        long subscriptionId;
        try {
            subscriptionId = Convert.parseUnsignedLong(Convert.emptyToNull(req.getParameter(SUBSCRIPTION_PARAMETER)));
        } catch (Exception e) {
            JsonObject response = new JsonObject();
            response.addProperty(ERROR_CODE_RESPONSE, 3);
            response.addProperty(ERROR_DESCRIPTION_RESPONSE, "Invalid or not specified subscription");
            return response;
        }

        Subscription subscription = subscriptionService.getSubscription(subscriptionId);

        if (subscription == null) {
            JsonObject response = new JsonObject();
            response.addProperty(ERROR_CODE_RESPONSE, 5);
            response.addProperty(ERROR_DESCRIPTION_RESPONSE, "Subscription not found");
            return response;
        }
        Alias alias = aliasService.getAlias(subscription.getRecipientId());
        Alias tld = alias == null ? null : aliasService.getTLD(alias.getTld());

        Transaction transaction = blockchain.getTransaction(subscriptionId);

        return JSONData.subscription(subscription, alias, tld, transaction);
    }
}
