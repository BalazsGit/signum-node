package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.SignumException;
import application.module.brs.crypto.EncryptedData;
import application.module.brs.services.AccountService;
import application.module.brs.services.ParameterService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.JSONResponses.INCORRECT_RECIPIENT;
import static application.module.brs.web.api.http.common.Parameters.*;

public final class EncryptTo extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final AccountService accountService;

    public EncryptTo(ParameterService parameterService, AccountService accountService) {
        super(new LegacyDocTag[] { LegacyDocTag.MESSAGES }, RECIPIENT_PARAMETER, MESSAGE_TO_ENCRYPT_PARAMETER,
                MESSAGE_TO_ENCRYPT_IS_TEXT_PARAMETER, SECRET_PHRASE_PARAMETER);
        this.parameterService = parameterService;
        this.accountService = accountService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        long recipientId = ParameterParser.getRecipientId(req);
        Account recipientAccount = accountService.getAccount(recipientId);
        if (recipientAccount == null || recipientAccount.getPublicKey() == null) {
            return INCORRECT_RECIPIENT;
        }

        EncryptedData encryptedData = parameterService.getEncryptedMessage(req, recipientAccount,
                recipientAccount.getPublicKey());
        return JSONData.encryptedData(encryptedData);

    }

}
