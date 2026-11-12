package com.example.jobs;

public class GuardedHostGate {
    public void run() {
        String host = ConfigCenter.getString("scheduler.host", null);
        if (host == null) {
            log.error("scheduler.host is not configured; refusing to decide which host runs this");
            return;
        }
        if (!Net.localAddresses().contains(host)) {
            return;
        }
        doWork();
    }

    public void explicitDefault() {
        // a non-null default is not this rule
        String region = ConfigCenter.getString("region", "cn");
        if (!Net.regions().contains(region)) {
            return;
        }
    }
}
