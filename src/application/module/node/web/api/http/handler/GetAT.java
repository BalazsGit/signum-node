package application.module.node.web.api.http.handler;

import application.module.node.Attachment;
import application.module.node.Blockchain;
import application.module.node.SignumException;
import application.module.node.Transaction;
import application.module.node.at.AT;
import application.module.node.at.AtApiHelper;
import application.module.node.at.AtMachineState;
import application.module.node.services.ParameterService;

import application.module.node.web.api.http.ApiServlet;
import application.module.node.web.api.http.common.JSONData;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.Parameters.AT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.INCLUDE_DETAILS_PARAMETER;

public final class GetAT extends ApiServlet.JsonRequestHandler {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public GetAT(ParameterService parameterService, Blockchain blockchain) {
        super(new LegacyDocTag[] { LegacyDocTag.AT }, AT_PARAMETER, INCLUDE_DETAILS_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {
        boolean includeDetails = !("false".equalsIgnoreCase(req.getParameter(INCLUDE_DETAILS_PARAMETER)));

        AT at = parameterService.getAT(req);
        AtMachineState atCreation = null;

        if (includeDetails) {
            Transaction transaction = blockchain.getTransaction(AtApiHelper.getLong(at.getId()));
            if (transaction.getAttachment() != null
                    && transaction.getAttachment() instanceof Attachment.AutomatedTransactionsCreation) {
                Attachment.AutomatedTransactionsCreation atCreationAttachment = (Attachment.AutomatedTransactionsCreation) transaction
                        .getAttachment();

                atCreation = AtMachineState.parse(at.getId(), at.getCreator(), atCreationAttachment.getCreationBytes(),
                        at.getCreationBlockHeight());
            }
        }

        return JSONData.at(at, atCreation, includeDetails);
    }

}
