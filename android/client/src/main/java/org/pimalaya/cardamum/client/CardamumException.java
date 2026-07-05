package org.pimalaya.cardamum.client;

/** Raised when a Cardamum operation fails (connection, auth, server error, ...). */
public class CardamumException extends RuntimeException {
    public CardamumException(String message) {
        super(message);
    }
}
