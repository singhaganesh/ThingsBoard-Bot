package com.seple.ThingsBoard_Bot.exception;

public class ThingsBoardException extends RuntimeException {

    public ThingsBoardException(String message) {
        super(message);
    }

    public ThingsBoardException(String message, Throwable cause) {
        super(message, cause);
    }
}
