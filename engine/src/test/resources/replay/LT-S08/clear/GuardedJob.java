package com.example.jobs;

import org.springframework.scheduling.annotation.Scheduled;

public class GuardedJob {

    @Scheduled(cron = "0 0 3 1 * ?")
    public void run() {
        try {
            var rows = repository.loadLastMonth();
            mailer.send(render(rows));
        } catch (Throwable t) {
            log.error("monthly report failed; not rethrowing so the schedule survives", t);
        }
    }

    // A plain method with no guard is none of this rule's business, even though the word
    // @Scheduled appears right above it in this comment.
    public void helper() {
        risky();
    }

    public void braceInAString() {
        log.info("a closing brace } inside a string must not shift the method boundary");
    }
}
