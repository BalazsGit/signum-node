package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterException;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.*;

public final class SellAlias extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public SellAlias(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.ALIASES, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                ALIAS_PARAMETER, ALIAS_NAME_PARAMETER, TLD_PARAMETER, RECIPIENT_PARAMETER, PRICE_NQT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        Alias alias = parameterService.getAlias(req);
        Account owner = parameterService.getSenderAccount(req);

        String priceValueNQT = Convert.emptyToNull(req.getParameter(PRICE_NQT_PARAMETER));
        if (priceValueNQT == null) {
            return MISSING_PRICE;
        }
        long priceNQT;
        try {
            priceNQT = Long.parseLong(priceValueNQT);
        } catch (RuntimeException e) {
            return INCORRECT_PRICE;
        }
        if (priceNQT < 0 || priceNQT > Constants.MAX_BALANCE_NQT) {
            throw new ParameterException(INCORRECT_PRICE);
        }

        String recipientValue = Convert.emptyToNull(req.getParameter(RECIPIENT_PARAMETER));
        long recipientId = 0;
        if (recipientValue != null) {
            try {
                recipientId = Convert.parseAccountId(recipientValue);
            } catch (RuntimeException e) {
                return INCORRECT_RECIPIENT;
            }
            if (recipientId == 0) {
                return INCORRECT_RECIPIENT;
            }
        }

        if (alias.getAccountId() != owner.getId()) {
            return INCORRECT_ALIAS_OWNER;
        }

        Attachment attachment = new Attachment.MessagingAliasSell(fluxCapacitor, alias.getAliasName(), priceNQT,
                blockchain.getHeight());
        return createTransaction(req, owner, recipientId, 0, attachment);
    }
}