package com.kalo.common.exception;

public class InvalidOperationException
        extends RuntimeException {

    public InvalidOperationException(String message) {
        super(message);
    }
}