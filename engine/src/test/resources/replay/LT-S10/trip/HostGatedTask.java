package com.example.jobs;

public class HostGatedTask {
    public void run() {
        String host = ApolloConfigure.getString("local.ip", null);
        List<String> addresses = Net.localAddresses();
        if (!addresses.contains(host)) {
            return;
        }
        doWork();
    }
}
