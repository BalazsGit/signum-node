package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Alias;
import application.module.brs.Signum;
import application.module.brs.SignumException;
import application.module.brs.Subscription;
import application.module.brs.Transaction;
import application.module.brs.services.AliasService;
import application.module.brs.services.ParameterService;
import application.module.brs.services.SubscriptionService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.brs.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static application.module.brs.web.api.http.common.Parameters.SUBSCRIPTIONS_RESPONSE;

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
