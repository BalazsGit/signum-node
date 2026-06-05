package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Attachment;
import application.module.node.Blockchain;
import application.module.node.SignumException;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.RECIPIENT_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.ERROR_CODE_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.ERROR_DESCRIPTION_RESPONSE;

public final class SetRewardRecipient extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AccountService accountService;

    public SetRewardRecipient(ParameterService parameterService, Blockchain blockchain, AccountService accountService,
            APITransactionManager apiTransactionManager) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS, LegacyDocTag.MINING, LegacyDocTag.CREATE_TRANSACTION },
                apiTransactionManager, RECIPIENT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.accountService = accountService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        final Account account = parameterService.getSenderAccount(req);
        Long recipient = ParameterParser.getRecipientId(req);
        Account recipientAccount = accountService.getAccount(recipient);
        if (recipientAccount == null || recipientAccount.getPublicKey() == null) {
            JsonObject response = new JsonObject();
            response.addProperty(ERROR_CODE_RESPONSE, 8);
            response.addProperty(ERROR_DESCRIPTION_RESPONSE, "recipient account does not have public key");
            return response;
        }
        Attachment attachment = new Attachment.SignaMiningRewardRecipientAssignment(blockchain.getHeight());
        return createTransaction(req, account, recipient, 0, attachment);
    }

}
