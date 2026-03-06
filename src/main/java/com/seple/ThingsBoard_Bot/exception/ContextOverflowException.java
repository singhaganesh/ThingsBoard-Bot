package com.seple.ThingsBoard_Bot.exception;

public class ContextOverflowException extends RuntimeException {

    public ContextOverflowException(String message) {
        super(message);
    }

    public ContextOverflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
