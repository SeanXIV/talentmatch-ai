package com.talentmatch.notify;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Startup checks of {@code talentmatch.notify.email.*} (§4.9). Pure; throws
 * {@link NotifyConfigurationException}, whose texts name environment variables and never values.
 * Missing values never fail startup: a partially configured channel is simply not configured.
 */
public final class EmailSettings {

    public static final String HOST_VAR = "SMTP_HOST";
    public static final String FROM_VAR = "NOTIFY_EMAIL_FROM";
    public static final String TO_VAR = "NOTIFY_EMAIL_TO";

    private static final Pattern IPV4_LOOPBACK = Pattern.compile("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

    private EmailSettings() {
    }

    /**
     * @throws NotifyConfigurationException username without password (or the reverse); when configured,
     *                                      an invalid from/to, or security NONE with a non-loopback host
     */
    public static void validate(NotifyProperties.Email e) {
        if (e.username() != null && e.password() == null) {
            throw new NotifyConfigurationException("SMTP_USERNAME is set but SMTP_PASSWORD is empty.",
                    "Set both, or neither if your server needs no login.");
        }
        if (e.username() == null && e.password() != null) {
            throw new NotifyConfigurationException("SMTP_PASSWORD is set but SMTP_USERNAME is empty.",
                    "Set both, or neither if your server needs no login.");
        }
        if (!e.configured()) {
            return;
        }
        requireAddress(e.from(), FROM_VAR);
        requireAddress(e.to(), TO_VAR);
        if (e.security() == NotifyProperties.Security.NONE && !isLoopback(e.host())) {
            throw new NotifyConfigurationException(
                    "SMTP_SECURITY=NONE is only allowed for a local test server (localhost, 127.0.0.1, ::1).",
                    "Use STARTTLS (port 587) or SSL (port 465) for a remote mail server.");
        }
    }

    /** Host, from and to are all set. */
    public static boolean configured(NotifyProperties.Email e) {
        return e != null && e.configured();
    }

    /** The environment variables still blank among SMTP_HOST, NOTIFY_EMAIL_FROM, NOTIFY_EMAIL_TO. */
    public static List<String> missing(NotifyProperties.Email e) {
        List<String> missing = new ArrayList<>(3);
        if (e == null || e.host() == null) {
            missing.add(HOST_VAR);
        }
        if (e == null || e.from() == null) {
            missing.add(FROM_VAR);
        }
        if (e == null || e.to() == null) {
            missing.add(TO_VAR);
        }
        return List.copyOf(missing);
    }

    /** The domain of the from address (for Message-IDs); "localhost" when there is none. */
    public static String fromDomain(NotifyProperties.Email e) {
        String domain = e == null ? null : EmailAddresses.domain(e.from());
        if (domain == null) {
            return "localhost";
        }
        // A Message-ID's right-hand side: keep it to safe characters.
        String safe = domain.replaceAll("[^a-z0-9.\\-\\[\\]:]", "");
        return safe.isEmpty() ? "localhost" : safe;
    }

    /**
     * True for a loopback literal: {@code localhost}, {@code 127.0.0.0/8}, {@code ::1} (also bracketed or
     * fully written). A literal check only: <b>no DNS lookup</b>.
     */
    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.equals("localhost.") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (IPV4_LOOPBACK.matcher(h).matches()) {
            for (String part : h.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Parses one address strictly; null when it isn't exactly one valid address with a domain. */
    public static InternetAddress parse(String value) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(value, true);
            if (parsed.length != 1) {
                return null;
            }
            InternetAddress address = parsed[0];
            address.validate();
            String a = address.getAddress();
            int at = a == null ? -1 : a.lastIndexOf('@');
            // InternetAddress accepts "owner" (no domain) even in strict mode: require local@domain.
            if (at <= 0 || at == a.length() - 1) {
                return null;
            }
            return address;
        } catch (AddressException e) {
            return null;
        }
    }

    private static void requireAddress(String value, String variable) {
        if (parse(value) == null) {
            throw new NotifyConfigurationException(variable + " is not a single valid email address.",
                    "Set " + variable + " to one address such as alerts@example.com (or \"Name <alerts@example.com>\").");
        }
    }
}
