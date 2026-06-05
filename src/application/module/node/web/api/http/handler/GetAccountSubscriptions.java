package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Alias;
import application.module.node.Signum;
import application.module.node.SignumException;
import application.module.node.Subscription;
import application.module.node.Transaction;
import application.module.node.services.AliasService;
import application.module.node.services.ParameterService;
import application.module.node.services.SubscriptionService;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.node.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.SUBSCRIPTIONS_RESPONSE;

public final class GetAccountSubscriptions extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final SubscriptionService subscriptionService;
    private final AliasService aliasService;

    public GetAccountSubscriptions(ParameterService parameterService, SubscriptionService subscriptionService,
            AliasService aliasService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER);
        this.parameterService = parameterService;
        this.subscriptionService = subscriptionService;
        this.aliasService = aliasService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getAccount(req);

        JsonObject response = new JsonObject();

        JsonArray subscriptions = new JsonArray();

        Collection<Subscription> accountSubscriptions = subscriptionService
                .getSubscriptionsByParticipant(account.getId());

        for (Subscription accountSubscription : accountSubscriptions) {
            Alias alias = aliasService.getAlias(accountSubscription.getRecipientId());
            Alias tld = alias == null ? null : aliasService.getTLD(alias.getTld());

            Transaction transaction = Signum.getBlockchain()
                    .getTransaction(alias == null ? accountSubscription.getId() : alias.getId());
            subscriptions.add(JSONData.subscription(accountSubscription, alias, tld, transaction));
        }

        response.add(SUBSCRIPTIONS_RESPONSE, subscriptions);
        return response;
    }
}
