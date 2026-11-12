package com.example.store;

public final class MongoConstant {
    // frozen at class load; changing it in the config service does nothing until a restart
    public static final String TABLE = ApolloConfigure.getString("mongodb.table.name", "uploads");
}
