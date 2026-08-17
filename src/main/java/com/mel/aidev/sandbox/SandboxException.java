package com.mel.aidev.sandbox;

/** Raised when the sandbox cannot be created, driven or destroyed. */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
