package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.SignumException;
import application.module.node.crypto.EncryptedData;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import application.module.node.web.api.http.common.Parameters;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.ApiServlet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.DECRYPTION_FAILED;
import static application.module.node.web.api.http.common.JSONResponses.INCORRECT_ACCOUNT;
import static application.module.node.web.api.http.common.Parameters.*;
import static application.module.node.web.api.http.common.ResultFields.DECRYPTED_MESSAGE_RESPONSE;

public final class DecryptFrom extends ApiServlet.JsonRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(DecryptFrom.class);

    private final ParameterService parameterService;

    public DecryptFrom(ParameterService parameterService) {
        super(new LegacyDocTag[] { LegacyDocTag.MESSAGES }, ACCOUNT_PARAMETER, DATA_PARAMETER, NONCE_PARAMETER,
                DECRYPTED_MESSAGE_IS_TEXT_PARAMETER, SECRET_PHRASE_PARAMETER);
        this.parameterService = parameterService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        Account account = parameterService.getAccount(req);
        if (account.getPublicKey() == null) {
            return INCORRECT_ACCOUNT;
        }
        String secretPhrase = ParameterParser.getSecretPhrase(req);
        byte[] data = Convert.parseHexString(Convert.nullToEmpty(req.getParameter(DATA_PARAMETER)));
        byte[] nonce = Convert.parseHexString(Convert.nullToEmpty(req.getParameter(NONCE_PARAMETER)));
        EncryptedData encryptedData = new EncryptedData(data, nonce);
        boolean isText = !Parameters.isFalse(req.getParameter(DECRYPTED_MESSAGE_IS_TEXT_PARAMETER));
        try {
            byte[] decrypted = account.decryptFrom(encryptedData, secretPhrase);
            JsonObject response = new JsonObject();
            response.addProperty(DECRYPTED_MESSAGE_RESPONSE,
                    isText ? Convert.toString(decrypted) : Convert.toHexString(decrypted));
            return response;
        } catch (RuntimeException e) {
            logger.debug(e.toString());
            return DECRYPTION_FAILED;
        }
    }

}
