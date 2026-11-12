package com.example.jobs;

import org.springframework.scheduling.annotation.Scheduled;

public class MonthlyReportJob {

    @Scheduled(cron = "0 0 3 1 * ?")
    public void run() {
        var rows = repository.loadLastMonth();
        mailer.send(render(rows));
    }
}
