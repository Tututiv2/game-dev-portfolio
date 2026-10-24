package com.testpilot;

/** A request the pilot refuses, with the HTTP status to answer it with. */
public final class PilotException extends RuntimeException {
    public final int status;

    public PilotException(int status, String message) {
        super(message);
        this.status = status;
    }

    public PilotException(String message) {
        this(400, message);
    }
}
