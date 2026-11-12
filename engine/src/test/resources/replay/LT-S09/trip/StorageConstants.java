package com.example.cfg;

public final class StorageConstants {
    // frozen at class load; changing it in the config service does nothing until a restart
    public static final String TABLE = ConfigCenter.getString("storage.collection.name", "uploads");
}
