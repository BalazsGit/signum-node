package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.SignumException;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.AMOUNT_NQT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.RECIPIENT_PARAMETER;

public final class SendMoney extends CreateTransaction {

    private final ParameterService parameterService;

    public SendMoney(ParameterService parameterService, APITransactionManager apiTransactionManager) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS, LegacyDocTag.TRANSACTIONS, LegacyDocTag.CREATE_TRANSACTION },
                apiTransactionManager, RECIPIENT_PARAMETER, AMOUNT_NQT_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        long recipient = ParameterParser.getRecipientId(req);
        long amountNQT = ParameterParser.getAmountNQT(req);
        Account account = parameterService.getSenderAccount(req);
        return createTransaction(req, account, recipient, amountNQT);
    }

}
