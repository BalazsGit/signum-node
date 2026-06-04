package signum.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import application.module.brs.Transaction;
import application.module.brs.TransactionType;
import application.module.brs.fluxcapacitor.FluxValue;
import application.module.brs.fluxcapacitor.FluxValue.ValueChange;
import application.module.brs.fluxcapacitor.HistoricalMoments;
import application.module.brs.web.api.http.ApiServlet.HttpRequestHandler;
import application.module.brs.props.Prop;
import application.module.brs.web.api.http.common.APITransactionManager;
import application.module.brs.services.AccountService;
import application.module.brs.services.ParameterService;

public class NetworkParametersBase implements NetworkParameters {

    private final Properties properties = new Properties();
    protected ParameterService parameterService;
    protected AccountService accountService;
    protected APITransactionManager apiTransactionManager;

    @Override
    public void initialize(ParameterService parameterService, AccountService accountService,
            APITransactionManager apiTransactionManager) {
        this.parameterService = parameterService;
        this.accountService = accountService;
        this.apiTransactionManager = apiTransactionManager;
    }

    protected <T> void setProperty(Prop<T> prop, String value) {
        properties.setProperty(prop.getName(), value);
    }

    protected <T> void setFluxValue(FluxValue<T> fluxValue, HistoricalMoments moment, T value) {
        ValueChange<T> valueChange = new FluxValue.ValueChange<T>(moment, value);
        List<ValueChange<T>> valueChages = new ArrayList<>();
        valueChages.add(valueChange);
        fluxValue.updateValueChanges(valueChages);
    }

    @Override
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    @Override
    public Map<Long, Integer> getBlockRewardDistribution(int height) {
        return null;
    }

    @Override
    public void adjustTransactionTypes(Map<TransactionType.Type, Map<Byte, TransactionType>> types) {
    }

    @Override
    public void adjustAPIs(Map<String, HttpRequestHandler> map) {
    }

    @Override
    public void unconfirmedTransactionAdded(Transaction transaction) {
    }

    @Override
    public void unconfirmedTransactionRemoved(Transaction transaction) {
    }

    @Override
    public void transactionApplied(Transaction transaction) {
    }

}
