package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Signum;
import application.module.brs.SignumException;
import application.module.brs.Subscription;
import application.module.brs.Transaction;
import application.module.brs.services.ParameterService;
import application.module.brs.services.SubscriptionService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.ACCOUNT_PARAMETER;

public final class GetSubscriptionsToAccount extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final SubscriptionService subscriptionService;

    public GetSubscriptionsToAccount(ParameterService parameterService, SubscriptionService subscriptionService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER);
        this.parameterService = parameterService;
        this.subscriptionService = subscriptionService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        final Account account = parameterService.getAccount(req);

        JsonObject response = new JsonObject();

        JsonArray subscriptions = new JsonArray();

        for (Subscription subscription : subscriptionService.getSubscriptionsToId(account.getId())) {

            Transaction transaction = Signum.getBlockchain().getTransaction(subscription.getId());
            subscriptions.add(JSONData.subscription(subscription, null, null, transaction));
        }

        response.add("subscriptions", subscriptions);
        return response;
    }
}
