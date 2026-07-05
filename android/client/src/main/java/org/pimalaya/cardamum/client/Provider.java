package org.pimalaya.cardamum.client;

import java.util.Locale;

/**
 * The contacts provider family behind an email address, detected from
 * its domain. Google and Microsoft consumer domains do not speak
 * password-authenticated CardDAV, so the connection screen proposes
 * their dedicated (upcoming) configurations instead of running RFC 6764
 * discovery.
 */
public enum Provider {
    GOOGLE,
    MICROSOFT,
    OTHER;

    // NOTE: domain-suffix heuristic only; MX-based detection (catching
    // hosted domains) can come later via pimconf.
    public static Provider detect(String email) {
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? "" : email.substring(at + 1).trim().toLowerCase(Locale.ROOT);

        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            return GOOGLE;
        }

        if (domain.startsWith("outlook.")
                || domain.startsWith("hotmail.")
                || domain.startsWith("live.")
                || domain.equals("msn.com")) {
            return MICROSOFT;
        }

        return OTHER;
    }
}
