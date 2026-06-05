package application.module.node.web.api.http.handler;

import application.module.node.Transaction;
import application.module.node.TransactionProcessor;
import application.module.node.services.IndirectIncomingService;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

import static application.module.node.web.api.http.common.JSONResponses.INCORRECT_ACCOUNT;
import static application.module.node.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.INCLUDE_INDIRECT_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.UNCONFIRMED_TRANSACTIONS_RESPONSE;

public final class GetUnconfirmedTransactions extends ApiServlet.JsonRequestHandler {

    private final TransactionProcessor transactionProcessor;
    private final IndirectIncomingService indirectIncomingService;
    private final ParameterService parameterService;

    public GetUnconfirmedTransactions(TransactionProcessor transactionProcessor,
            IndirectIncomingService indirectIncomingService, ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.TRANSACTIONS, LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER,
                INCLUDE_INDIRECT_PARAMETER);
        this.transactionProcessor = transactionProcessor;
        this.indirectIncomingService = indirectIncomingService;
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        final String accountIdString = Convert.emptyToNull(req.getParameter(ACCOUNT_PARAMETER));
        boolean includeIndirect = parameterService.getIncludeIndirect(req);

        long accountId = 0;

        if (accountIdString != null) {
            try {
                accountId = Convert.parseAccountId(accountIdString);
            } catch (RuntimeException e) {
                return INCORRECT_ACCOUNT;
            }
        }

        final List<Transaction> unconfirmedTransactions = transactionProcessor.getAllUnconfirmedTransactions();

        final JsonArray transactions = new JsonArray();

        for (Transaction transaction : unconfirmedTransactions) {
            if (accountId == 0
                    || (accountId == transaction.getSenderId() || accountId == transaction.getRecipientId())
                    || (includeIndirect && indirectIncomingService.isIndirectlyReceiving(transaction, accountId))) {
                transactions.add(JSONData.unconfirmedTransaction(transaction));
            }
        }

        final JsonObject response = new JsonObject();

        response.add(UNCONFIRMED_TRANSACTIONS_RESPONSE, transactions);

        return response;
    }

}
