package com.thestill.config;

/** The value types a {@link Dial} can hold. Drives both parsing and how the /config GUI renders it. */
public enum DialKind {
    DOUBLE,
    INT,
    LONG,
    BOOLEAN,
    STRING;

    public boolean isNumeric() {
        return this == DOUBLE || this == INT || this == LONG;
    }
}
