package org.pimalaya.cardamum.client;

/** Raised when a Cardamum operation fails (connection, auth, server error, ...). */
public class CardamumException extends RuntimeException {
    /**
     * HTTP status of the failed round, null when the failure was not
     * an HTTP one. Callers branch on it (412 retries unguarded, 404
     * converges a removal, 401 refreshes the token) instead of
     * matching the message prose.
     */
    public final Integer status;

    public CardamumException(String message) {
        this(message, null);
    }

    public CardamumException(String message, Integer status) {
        super(message);
        this.status = status;
    }
}
