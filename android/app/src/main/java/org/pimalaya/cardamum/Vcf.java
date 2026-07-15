package org.pimalaya.cardamum;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The vCard file (.vcf) codec behind import and export: pure text
 * splitting and joining, so the round-trip is JVM-testable without a
 * device. Documents are never parsed here; the store indexes them
 * through the bridge at write time.
 */
final class Vcf {
    /** One raw vCard document, BEGIN through END, any case. */
    private static final Pattern CARD =
            Pattern.compile("BEGIN:VCARD.*?END:VCARD", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private Vcf() {}

    /** Every vCard document in the text, in order; junk between
     *  documents is skipped. */
    static List<String> split(String text) {
        List<String> vcards = new ArrayList<>();
        Matcher matcher = CARD.matcher(text);
        while (matcher.find()) {
            vcards.add(matcher.group());
        }
        return vcards;
    }

    /** One file: each document appended verbatim (trimmed, one CRLF
     *  after each), so the file round-trips through {@link #split}. */
    static String join(List<String> vcards) {
        StringBuilder vcf = new StringBuilder();
        for (String vcard : vcards) {
            vcf.append(vcard.trim());
            vcf.append("\r\n");
        }
        return vcf.toString();
    }
}
