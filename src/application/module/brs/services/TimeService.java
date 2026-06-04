package application.module.brs.services;

import application.module.brs.util.Time.FasterTime;

public interface TimeService {

    int getEpochTime();

    long getEpochTimeMillis();

    void setTime(FasterTime fasterTime);
}
