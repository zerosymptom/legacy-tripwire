package com.example.jobs;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonthlyReportJob {

    private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor();

    public void start() {
        // No guard at all: the first throw suppresses every later execution, in silence.
        pool.scheduleWithFixedDelay(() -> {
            var rows = repository.loadLastMonth();
            mailer.send(render(rows));
        }, 0, 1, TimeUnit.HOURS);
    }

    public void startNarrow() {
        // catch (Exception) is not enough — a NoClassDefFoundError suppresses it just the same.
        pool.scheduleAtFixedRate(() -> {
            try {
                reconcile();
            } catch (Exception e) {
                log.warn("reconcile failed", e);
            }
        }, 0, 5, TimeUnit.MINUTES);
    }

    public void startNamedRunnable() {
        // A named inner Runnable. Its body is in this file, so answering UNKNOWN would be the
        // checker giving up on something it can read.
        pool.scheduleWithFixedDelay(new SweepRunnable(), 1, 1, TimeUnit.MINUTES);
    }

    private class SweepRunnable implements Runnable {
        @Override
        public void run() {
            sweep();
        }
    }
}
