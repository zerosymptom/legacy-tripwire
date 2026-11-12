package com.example.store;

public final class Safe {
    // the canonical static final method call — freezing a logger is correct
    private static final Logger LOG = LoggerFactory.getLogger(Safe.class);
    // not final: can be refreshed, so not this rule
    private static String table = ApolloConfigure.getString("mongodb.table.name", "uploads");
    // a plain constant whose *value* mentions getConfig must not match
    public static final String KEY = "live.gift.getConfigGroupGift";
}
