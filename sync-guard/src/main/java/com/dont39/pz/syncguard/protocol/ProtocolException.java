package com.dont39.pz.syncguard.protocol;

/** A message that does not decode: it concerns the peer that sent it, not the mod. */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
