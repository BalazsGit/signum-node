package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.SignumException;
import application.module.node.services.AccountService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

import static application.module.node.web.api.http.common.Parameters.ACCOUNTS_RESPONSE;
import static application.module.node.web.api.http.common.Parameters.NAME_PARAMETER;

public class GetAccountsWithName extends ApiServlet.JsonRequestHandler {

    private final AccountService accountService;

    public GetAccountsWithName(AccountService accountService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, NAME_PARAMETER);
        this.accountService = accountService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest request) throws SignumException {
        Collection<Account> accounts = accountService.getAccountsWithName(request.getParameter(NAME_PARAMETER));
        JsonArray accountIds = new JsonArray();

        for (Account account : accounts) {
            accountIds.add(Convert.toUnsignedLong(account.id));
        }

        JsonObject response = new JsonObject();
        response.add(ACCOUNTS_RESPONSE, accountIds);
        return response;
    }
}
