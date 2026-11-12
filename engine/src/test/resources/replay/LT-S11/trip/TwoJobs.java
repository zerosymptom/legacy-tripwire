package com.example.jobs;

public class TwoJobs {
    @Scheduled(cron = "0 0/1 * * * ?")
    public void refresh() {
        try { work(); } catch (Throwable t) { log.error("", t); }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void nightly() {
        try { work(); } catch (Throwable t) { log.error("", t); }
    }
}
