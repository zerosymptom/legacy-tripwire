package com.example.jobs;

public class GuardedHostGate {
    public void run() {
        String host = ApolloConfigure.getString("local.ip", null);
        if (host == null) {
            log.error("local.ip is not configured; refusing to decide which host runs this");
            return;
        }
        if (!Net.localAddresses().contains(host)) {
            return;
        }
        doWork();
    }

    public void explicitDefault() {
        // a non-null default is not this rule
        String region = ApolloConfigure.getString("region", "cn");
        if (!Net.regions().contains(region)) {
            return;
        }
    }
}
