package application.module.node.web.api.http.handler;

import static application.module.node.web.api.http.common.JSONResponses.INCORRECT_TRANSACTION;
import static application.module.node.web.api.http.common.JSONResponses.MISSING_TRANSACTION;
import static application.module.node.web.api.http.common.JSONResponses.UNKNOWN_TRANSACTION;
import static application.module.node.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.TRANSACTION_PARAMETER;

import jakarta.servlet.http.HttpServletRequest;

import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import application.module.node.Account;
import application.module.node.Blockchain;
import application.module.node.SignumException;
import application.module.node.IndirectIncoming;
import application.module.node.services.IndirectIncomingService;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;

/**
 * Handles the getIndirectIncoming API request.
 * Uses IndirectIncomingService (service layer) rather than accessing the store directly,
 * following the layered architecture: Web API → Services → Stores → Database.
 */
public final class GetIndirectIncoming extends ApiServlet.JsonRequestHandler {

    private final Blockchain blockchain;
    private final ParameterService parameterService;
    private final IndirectIncomingService indirectIncomingService;

    public GetIndirectIncoming(Blockchain blockchain, ParameterService parameterService,
            IndirectIncomingService indirectIncomingService) {
        super(new LegacyDocTag[] { LegacyDocTag.TRANSACTIONS }, TRANSACTION_PARAMETER, ACCOUNT_PARAMETER);
        this.blockchain = blockchain;
        this.parameterService = parameterService;
        this.indirectIncomingService = indirectIncomingService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getAccount(req);
        String transactionIdString = Convert.emptyToNull(req.getParameter(TRANSACTION_PARAMETER));
        if (transactionIdString == null) {
            return MISSING_TRANSACTION;
        }

        long transactionId = 0;
        try {
            transactionId = Convert.parseUnsignedLong(transactionIdString);
        } catch (RuntimeException e) {
            return INCORRECT_TRANSACTION;
        }

        IndirectIncoming indirect = indirectIncomingService.getIndirectIncoming(account.getId(), transactionId);

        if (indirect == null) {
            return UNKNOWN_TRANSACTION;
        }
        return JSONData.indirect(indirect, blockchain.getHeight());

    }

}
