package com.example.jobs;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

public class GuardedJob {

    private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor();

    /** Spring decorates the task, so the same two method names are not this rule's business. */
    private final TaskScheduler springScheduler = null;

    public void start() {
        pool.scheduleWithFixedDelay(() -> {
            try {
                var rows = repository.loadLastMonth();
                mailer.send(render(rows));
            } catch (Throwable t) {
                log.error("monthly report failed; not rethrowing so the schedule survives", t);
            }
        }, 0, 1, TimeUnit.HOURS);
    }

    public void startViaSpring() {
        // Must stay clear: Spring wraps this in DelegatingErrorHandlingRunnable, which catches
        // and logs. Measured 2026-11-13 — this is why the rule was re-scoped away from @Scheduled.
        springScheduler.scheduleAtFixedRate(() -> reconcile(), 60_000L);
    }

    /** A framework annotation is no longer in scope at all; the task survives a throw. */
    @Scheduled(cron = "0 0 3 1 * ?")
    public void annotatedIsNotThisRule() {
        risky();
    }

    public void methodReferenceIsResolvedInThisFile() {
        pool.scheduleAtFixedRate(this::sweepGuarded, 0, 1, TimeUnit.MINUTES);
    }

    private void sweepGuarded() {
        try {
            sweep();
        } catch (Throwable t) {
            log.error("sweep failed", t);
        }
    }

    public void braceInAString() {
        log.info("a closing brace } inside a string must not shift the method boundary");
    }
}
