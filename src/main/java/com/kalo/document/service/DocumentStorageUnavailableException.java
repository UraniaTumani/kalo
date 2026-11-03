package com.kalo.document.service;

/** Safe API error; SDK details and credentials must not reach the client. */
public class DocumentStorageUnavailableException extends RuntimeException {
    public DocumentStorageUnavailableException() {
        super("Document storage is temporarily unavailable");
    }
}
