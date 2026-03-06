package com.seple.ThingsBoard_Bot.exception;

public class OpenAIException extends RuntimeException {

    public OpenAIException(String message) {
        super(message);
    }

    public OpenAIException(String message, Throwable cause) {
        super(message, cause);
    }
}
