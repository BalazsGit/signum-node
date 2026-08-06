package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Attachment;
import application.module.node.SignumException;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import application.module.node.fluxcapacitor.FluxCapacitor;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.RECIPIENT_PARAMETER;

public final class SendMessage extends CreateTransaction {

    private final ParameterService parameterService;
    private final FluxCapacitor fluxCapacitor;

    public SendMessage(ParameterService parameterService, APITransactionManager apiTransactionManager,
            FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.MESSAGES, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager,
                fluxCapacitor, RECIPIENT_PARAMETER);
        this.parameterService = parameterService;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        long recipient = ParameterParser.getRecipientId(req);
        Account account = parameterService.getSenderAccount(req);
        return createTransaction(req, account, recipient, 0, Attachment.ARBITRARY_MESSAGE);
    }

}