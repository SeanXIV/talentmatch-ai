package com.talentmatch.notify;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code talentmatch.notify.*} (§7).
 *
 * <p>The constructors only normalize and range-check <b>non-secret</b> values. The cross-field email
 * checks (username/password pairing, address syntax, {@code NONE} only on loopback) run when the
 * {@link EmailNotifier} bean is created ({@link EmailSettings#validate}) and throw
 * {@link NotifyConfigurationException}: a failure during binding would be reported by Boot's
 * {@code BindFailureAnalyzer}, which may print the bound values (the username, an address).
 *
 * <p>{@code toString} masks the password, the username and both addresses (D5).
 *
 * @param zone             time zone of the times in an email (default Africa/Johannesburg)
 * @param appBaseUrl       absolute http(s) URL of this API, only used for the "Details:" link (no trailing /)
 * @param maxAttempts      sends per notification before it is FAILED (1..20, default 5: delays 1, 2, 4, 8 min)
 * @param retryInitial     first retry delay after a transient failure (1s..1h)
 * @param retryMax         cap of the retry delay (retry-initial..24h)
 * @param configErrorPause how long the dispatcher pauses after a configuration error (1m..24h)
 * @param maxAge           a notification not sent within this time of being queued expires (1h..7d)
 * @param testInterval     at most one {@code POST /api/notifications/test} per interval (1s..10m)
 * @param email            the SMTP channel
 */
@Validated
@ConfigurationProperties("talentmatch.notify")
public record NotifyProperties(
        @DefaultValue("Africa/Johannesburg") String zone,
        @DefaultValue("http://127.0.0.1:8080") String appBaseUrl,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1m") Duration retryInitial,
        @DefaultValue("15m") Duration retryMax,
        @DefaultValue("15m") Duration configErrorPause,
        @DefaultValue("24h") Duration maxAge,
        @DefaultValue("30s") Duration testInterval,
        @DefaultValue @Valid Email email) {

    @ConstructorBinding
    public NotifyProperties {
        zone = zone == null || zone.isBlank() ? "Africa/Johannesburg" : zone.strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("talentmatch.notify.zone is " + zone + " but must be a time zone id "
                    + "such as Africa/Johannesburg or UTC");
        }
        appBaseUrl = normalizeBaseUrl(appBaseUrl);
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("talentmatch.notify.max-attempts is " + maxAttempts
                    + " but must be between 1 and 20");
        }
        retryInitial = retryInitial == null ? Duration.ofMinutes(1) : retryInitial;
        requireBetween("talentmatch.notify.retry-initial", retryInitial, Duration.ofSeconds(1), Duration.ofHours(1));
        retryMax = retryMax == null ? Duration.ofMinutes(15) : retryMax;
        requireBetween("talentmatch.notify.retry-max", retryMax, retryInitial, Duration.ofHours(24));
        configErrorPause = configErrorPause == null ? Duration.ofMinutes(15) : configErrorPause;
        requireBetween("talentmatch.notify.config-error-pause", configErrorPause, Duration.ofMinutes(1),
                Duration.ofHours(24));
        maxAge = maxAge == null ? Duration.ofHours(24) : maxAge;
        requireBetween("talentmatch.notify.max-age", maxAge, Duration.ofHours(1), Duration.ofDays(7));
        testInterval = testInterval == null ? Duration.ofSeconds(30) : testInterval;
        requireBetween("talentmatch.notify.test-interval", testInterval, Duration.ofSeconds(1), Duration.ofMinutes(10));
        email = email == null ? Email.unconfigured() : email;
    }

    /** All defaults, email not configured. */
    public static NotifyProperties defaults() {
        return withEmail(Email.unconfigured());
    }

    /** All defaults with the given email settings. */
    public static NotifyProperties withEmail(Email email) {
        return new NotifyProperties(null, null, 5, null, null, null, null, null, email);
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    @Override
    public String toString() {
        return "NotifyProperties[zone=" + zone + ", appBaseUrl=" + appBaseUrl + ", maxAttempts=" + maxAttempts
                + ", retryInitial=" + retryInitial + ", retryMax=" + retryMax + ", configErrorPause="
                + configErrorPause + ", maxAge=" + maxAge + ", testInterval=" + testInterval + ", email=" + email + "]";
    }

    public enum Security {
        /** Port 587; the client upgrades with STARTTLS and never falls back to plaintext. */
        STARTTLS,
        /** Port 465; TLS from the first byte. */
        SSL,
        /** Plaintext; only for a loopback test server (Mailpit, GreenMail). */
        NONE
    }

    /**
     * {@code talentmatch.notify.email.*}. Configured means host, from and to are all set.
     *
     * @param host              SMTP host; blank = email off
     * @param port              SMTP port (1..65535, default 587)
     * @param security          STARTTLS (default) | SSL | NONE (an empty value means STARTTLS)
     * @param username          SMTP login; blank = no login (then the password must be blank too)
     * @param password          from the SMTP_PASSWORD env var only; never logged, never returned
     * @param from              the sender address (one address, personal-name form allowed)
     * @param to                the recipient address (one address)
     * @param connectionTimeout connect timeout (1s..60s)
     * @param readTimeout       read timeout per SMTP reply (1s..60s)
     * @param writeTimeout      write timeout (1s..60s)
     */
    public record Email(
            String host,
            @DefaultValue("587") Integer port,
            Security security,
            String username,
            String password,
            String from,
            String to,
            @DefaultValue("10s") Duration connectionTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("10s") Duration writeTimeout) {

        public Email {
            host = strip(host);
            port = port == null ? 587 : port;
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("talentmatch.notify.email.port is " + port
                        + " but must be between 1 and 65535");
            }
            security = security == null ? Security.STARTTLS : security;
            username = strip(username);
            // The password is never stripped or altered: only a blank one counts as absent.
            password = password == null || password.isBlank() ? null : password;
            from = strip(from);
            to = strip(to);
            connectionTimeout = connectionTimeout == null ? Duration.ofSeconds(10) : connectionTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
            writeTimeout = writeTimeout == null ? Duration.ofSeconds(10) : writeTimeout;
            requireBetween("talentmatch.notify.email.connection-timeout", connectionTimeout, Duration.ofSeconds(1),
                    Duration.ofSeconds(60));
            requireBetween("talentmatch.notify.email.read-timeout", readTimeout, Duration.ofSeconds(1),
                    Duration.ofSeconds(60));
            requireBetween("talentmatch.notify.email.write-timeout", writeTimeout, Duration.ofSeconds(1),
                    Duration.ofSeconds(60));
        }

        public static Email unconfigured() {
            return new Email(null, 587, Security.STARTTLS, null, null, null, null, null, null, null);
        }

        /** Host, from and to are all set (the syntax is checked at startup by {@link EmailSettings#validate}). */
        public boolean configured() {
            return host != null && from != null && to != null;
        }

        public boolean authenticated() {
            return username != null;
        }

        @Override
        public String toString() {
            return "Email[host=" + host + ", port=" + port + ", security=" + security
                    + ", username=" + (username == null ? null : "******")
                    + ", password=" + (password == null ? null : "******")
                    + ", from=" + EmailAddresses.mask(from) + ", to=" + EmailAddresses.mask(to)
                    + ", connectionTimeout=" + connectionTimeout + ", readTimeout=" + readTimeout
                    + ", writeTimeout=" + writeTimeout + "]";
        }

        private static String strip(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    private static String normalizeBaseUrl(String value) {
        String v = value == null || value.isBlank() ? "http://127.0.0.1:8080" : value.strip();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(v);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null
                || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("talentmatch.notify.app-base-url is " + v
                    + " but must be an absolute http(s) URL such as http://127.0.0.1:8080");
        }
        return v;
    }

    private static void requireBetween(String name, Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " is " + value + " but must be between " + min + " and " + max);
        }
    }
}
