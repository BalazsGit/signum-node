package application.module.brs.web.api.http.handler;

import static application.module.brs.web.api.http.common.JSONResponses.INCORRECT_TRANSACTION;
import static application.module.brs.web.api.http.common.JSONResponses.MISSING_TRANSACTION;
import static application.module.brs.web.api.http.common.JSONResponses.UNKNOWN_TRANSACTION;
import static application.module.brs.web.api.http.common.Parameters.ACCOUNT_PARAMETER;
import static application.module.brs.web.api.http.common.Parameters.TRANSACTION_PARAMETER;

import jakarta.servlet.http.HttpServletRequest;

import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import application.module.brs.Account;
import application.module.brs.Blockchain;
import application.module.brs.Signum;
import application.module.brs.SignumException;
import application.module.brs.IndirectIncoming;
import application.module.brs.services.ParameterService;
import application.module.brs.util.Convert;

public final class GetIndirectIncoming extends ApiServlet.JsonRequestHandler {

    private final Blockchain blockchain;
    private final ParameterService parameterService;

    public GetIndirectIncoming(Blockchain blockchain, ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.TRANSACTIONS }, TRANSACTION_PARAMETER, ACCOUNT_PARAMETER);
        this.blockchain = blockchain;
        this.parameterService = parameterService;
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

        IndirectIncoming indirect = Signum.getStores().getIndirectIncomingStore().getIndirectIncoming(account.getId(),
                transactionId);

        if (indirect == null) {
            return UNKNOWN_TRANSACTION;
        }
        return JSONData.indirect(indirect, blockchain.getHeight());

    }

}
