package application.module.node.web.api.http.handler;

import application.module.node.Blockchain;

import application.module.node.Account;
import application.module.node.SignumException;
import application.module.node.Subscription;
import application.module.node.Transaction;
import application.module.node.services.ParameterService;
import application.module.node.services.SubscriptionService;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.ACCOUNT_PARAMETER;

public final class GetSubscriptionsToAccount extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final SubscriptionService subscriptionService;
    private final Blockchain blockchain;

    public GetSubscriptionsToAccount(ParameterService parameterService, SubscriptionService subscriptionService,
            Blockchain blockchain) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER);
        this.parameterService = parameterService;
        this.subscriptionService = subscriptionService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        final Account account = parameterService.getAccount(req);

        JsonObject response = new JsonObject();

        JsonArray subscriptions = new JsonArray();

        for (Subscription subscription : subscriptionService.getSubscriptionsToId(account.getId())) {

            Transaction transaction = blockchain.getTransaction(subscription.getId());
            subscriptions.add(JSONData.subscription(subscription, null, null, transaction));
        }

        response.add("subscriptions", subscriptions);
        return response;
    }
}
