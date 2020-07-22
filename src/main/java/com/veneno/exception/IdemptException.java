package com.veneno.exception;



public class IdemptException extends RuntimeException {

    public IdemptException(String message) {
        super(message);
    }

    public IdemptException(String message, Throwable cause) {
        super(message, cause);
    }

    public IdemptException(Throwable cause) {
        super(cause);
    }

    public IdemptException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
