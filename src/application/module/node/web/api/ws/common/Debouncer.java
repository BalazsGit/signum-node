package application.module.node.web.api.ws.common;

import application.module.logging.context.NodeLoggers;
import org.apache.logging.log4j.Logger;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class Debouncer {

    private final Logger logger;

    private final int delay;
    private final Timer timer;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public Debouncer(int delayMillies, NodeLoggers loggers) {
        timer = new Timer();
        isRunning.set(false);
        this.delay = delayMillies;
        this.logger = loggers.forClass(Debouncer.class);
    }

    public void debounce(Runnable task) {
        if (isRunning.get()) {
            return;
        }
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                task.run();
                isRunning.set(false);
            }
        }, delay);

        isRunning.set(true);
    }

    public void shutdown() {
        try {
            if (isRunning.get()) {
                timer.cancel();
            }
        } catch (Exception e) {
            logger.warn("Debouncer graceful shutdown failed: {}", e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }
}
