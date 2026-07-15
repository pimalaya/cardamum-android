package org.pimalaya.cardamum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Pins the .vcf codec: what import splits and export joins. */
public class VcfTest {
    private static final String JANE =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane Doe\r\nEND:VCARD";

    private static final String BOB = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob\r\nEND:VCARD";

    @Test
    public void splitFindsEveryDocumentAndSkipsJunk() {
        List<String> vcards = Vcf.split("junk\r\n" + JANE + "\r\ngarbage\r\n" + BOB + "\r\n");
        assertEquals(List.of(JANE, BOB), vcards);
    }

    @Test
    public void splitIsCaseInsensitive() {
        List<String> vcards = Vcf.split("begin:vcard\r\nFN:X\r\nend:VCARD");
        assertEquals(1, vcards.size());
    }

    @Test
    public void joinRoundTripsThroughSplit() {
        String file = Vcf.join(List.of(JANE + "\r\n", BOB));
        assertTrue(file.endsWith("\r\n"));
        assertEquals(List.of(JANE, BOB), Vcf.split(file));
    }
}
