package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Attachment;
import application.module.brs.Blockchain;
import application.module.brs.Signum;
import application.module.brs.SignumException;
import application.module.brs.fluxcapacitor.FluxValues;
import application.module.brs.services.AccountService;
import application.module.brs.services.ParameterService;
import application.module.brs.util.Convert;

import application.module.brs.web.api.http.common.APITransactionManager;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.JSONResponses.INCORRECT_FEE;
import static application.module.brs.web.api.http.common.JSONResponses.NOT_ENOUGH_FUNDS;
import static application.module.brs.web.api.http.common.Parameters.AMOUNT_NQT_PARAMETER;

public final class AddCommitment extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public AddCommitment(ParameterService parameterService, Blockchain blockchain, AccountService accountService,
            APITransactionManager apiTransactionManager) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS, LegacyDocTag.MINING, LegacyDocTag.CREATE_TRANSACTION },
                apiTransactionManager, AMOUNT_NQT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        final Account account = parameterService.getSenderAccount(req);
        long amountNQT = ParameterParser.getAmountNQT(req);

        long minimumFeeNQT = Signum.getFluxCapacitor().getValue(FluxValues.FEE_QUANT);
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
