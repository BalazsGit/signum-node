package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AliasService;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;
import application.module.node.util.TextUtils;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.TLD_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.AMOUNT_NQT_PARAMETER;
import static application.module.node.web.api.http.common.ResultFields.ERROR_CODE_RESPONSE;
import static application.module.node.web.api.http.common.ResultFields.ERROR_DESCRIPTION_RESPONSE;

public final class SetTLD extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AliasService aliasService;

    public SetTLD(ParameterService parameterService, Blockchain blockchain, AliasService aliasService,
            APITransactionManager apiTransactionManager, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.ALIASES, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                TLD_PARAMETER, AMOUNT_NQT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.aliasService = aliasService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        String tldName = Convert.emptyToNull(req.getParameter(TLD_PARAMETER));

        if (tldName == null) {
            return missing(TLD_PARAMETER);
        }

        tldName = tldName.trim();
        if (tldName.isEmpty() || tldName.length() > Constants.MAX_TLD_LENGTH) {
            return incorrect(TLD_PARAMETER);
        }

        if (!TextUtils.isInAlphabet(tldName)) {
            return incorrect(TLD_PARAMETER);
        }

        Alias alias = aliasService.getTLD(tldName);
        if (alias != null) {
            JsonObject response = new JsonObject();
            response.addProperty(ERROR_CODE_RESPONSE, 8);
            response.addProperty(ERROR_DESCRIPTION_RESPONSE, "\"" + tldName + "\" is already used");
            return response;
        }

        long recipient = 0L;
        long amountNQT = ParameterParser.getAmountNQT(req);
        if (amountNQT < TransactionType.BASELINE_TLD_ASSIGNMENT_FACTOR
                * this.fluxCapacitor.getValue(FluxValues.FEE_QUANT, blockchain.getLastBlock().getHeight())) {
            return incorrect(AMOUNT_NQT_PARAMETER);
        }

        Account account = parameterService.getSenderAccount(req);
        Attachment attachment = new Attachment.MessagingTldAssignment(tldName, blockchain.getHeight());
        return createTransaction(req, account, recipient, amountNQT, attachment);
    }

}