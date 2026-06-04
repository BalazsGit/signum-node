package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Attachment;
import application.module.brs.SignumException;
import application.module.brs.services.ParameterService;
import application.module.brs.web.api.http.common.APITransactionManager;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.RECIPIENT_PARAMETER;

public final class SendMessage extends CreateTransaction {

    private final ParameterService parameterService;

    public SendMessage(ParameterService parameterService, APITransactionManager apiTransactionManager) {
        super(new LegacyDocTag[] { LegacyDocTag.MESSAGES, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager,
                RECIPIENT_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        long recipient = ParameterParser.getRecipientId(req);
        Account account = parameterService.getSenderAccount(req);
        return createTransaction(req, account, recipient, 0, Attachment.ARBITRARY_MESSAGE);
    }

}
