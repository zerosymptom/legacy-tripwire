package com.example.jobs;

public class HostGatedTask {
    public void run() {
        String host = ConfigCenter.getString("scheduler.host", null);
        List<String> addresses = Net.localAddresses();
        if (!addresses.contains(host)) {
            return;
        }
        doWork();
    }
}
