package application.module.node.web.api.http.handler;

import application.module.node.Account;
import application.module.node.Attachment;
import application.module.node.Blockchain;
import application.module.node.SignumException;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;

import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.INCORRECT_FEE;
import static application.module.node.web.api.http.common.JSONResponses.NOT_ENOUGH_FUNDS;
import static application.module.node.web.api.http.common.Parameters.AMOUNT_NQT_PARAMETER;

public final class AddCommitment extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final FluxCapacitor fluxCapacitor;

    public AddCommitment(ParameterService parameterService, Blockchain blockchain, AccountService accountService,
            APITransactionManager apiTransactionManager, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS, LegacyDocTag.MINING, LegacyDocTag.CREATE_TRANSACTION },
                apiTransactionManager, fluxCapacitor, AMOUNT_NQT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        final Account account = parameterService.getSenderAccount(req);
        long amountNQT = ParameterParser.getAmountNQT(req);

        long minimumFeeNQT = this.fluxCapacitor.getValue(FluxValues.FEE_QUANT);
        long feeNQT = ParameterParser.getFeeNQT(req);
        if (feeNQT < minimumFeeNQT) {
            return INCORRECT_FEE;
        }

        try {
            if (Convert.safeAdd(amountNQT, feeNQT) > account.getUnconfirmedBalanceNqt()) {
                return NOT_ENOUGH_FUNDS;
            }
        } catch (ArithmeticException e) {
            return NOT_ENOUGH_FUNDS;
        }

        Attachment attachment = new Attachment.CommitmentAdd(amountNQT, blockchain.getHeight());
        return createTransaction(req, account, attachment);
    }

}