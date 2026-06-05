package application.module.node.services;

import application.module.node.util.Time.FasterTime;

public interface TimeService {

    int getEpochTime();

    long getEpochTimeMillis();

    void setTime(FasterTime fasterTime);
}
