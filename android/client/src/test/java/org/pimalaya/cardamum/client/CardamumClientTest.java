package org.pimalaya.cardamum.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Pins the bridge reply parsing: an error reply raises a
 * CardamumException carrying the message and, when the failure was an
 * HTTP round, the status the callers branch on (412 retries unguarded,
 * 404 converges a removal, 401 refreshes the token).
 */
public class CardamumClientTest {
    @Test
    public void errorReplyCarriesTheHttpStatus() {
        try {
            CardamumClient.object("{\"error\": \"WebDAV server returned HTTP 412\", \"status\": 412}");
            fail("an error reply must throw");
        } catch (CardamumException error) {
            assertEquals("WebDAV server returned HTTP 412", error.getMessage());
            assertEquals(Integer.valueOf(412), error.status);
        }
    }

    @Test
    public void nonHttpErrorReplyCarriesNoStatus() {
        try {
            CardamumClient.object("{\"error\": \"Invalid URL\"}");
            fail("an error reply must throw");
        } catch (CardamumException error) {
            assertEquals("Invalid URL", error.getMessage());
            assertNull(error.status);
        }
    }

    @Test
    public void successReplyPassesThrough() throws Exception {
        assertEquals("ok", CardamumClient.object("{\"value\": \"ok\"}").getString("value"));
    }
}
