package com.talentmatch.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.notify.SmtpFailure.Kind;
import com.talentmatch.notify.SmtpFailure.Reason;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

/** §4.9 / §9.1 #14: SMTP failures → TRANSIENT, PERMANENT or CONFIG, with the reply code; texts never leak. */
class SmtpErrorClassifierTest {

    /** Distinctive secrets put into exception messages; none may reach a text. */
    private static final String ADDRESS = "zq-owner@private-example.org";
    private static final String PASSWORD = "Zq-Secret-Pw-987";
    private static final String SERVER_TEXT = "5.7.8 Zqserver says go away " + ADDRESS + " " + PASSWORD;

    private static SMTPSendFailedException sendFailed(int code) {
        return new SMTPSendFailedException("DATA", code, code + " " + SERVER_TEXT, null, null, null, null);
    }

    private static InternetAddress address() {
        try {
            return new InternetAddress(ADDRESS);
        } catch (AddressException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ reply-code table

    @ParameterizedTest
    @ValueSource(ints = {421, 450, 451, 452, 454})
    void fourHundredsAreTransient(int code) {
        SmtpFailure f = SmtpErrorClassifier.classify(sendFailed(code));
        assertThat(f.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(f.reason()).isEqualTo(Reason.SMTP_TRANSIENT);
        assertThat(f.smtpCode()).isEqualTo(code);
        assertThat(f.historyText()).isEqualTo("SMTP " + code + " (transient)");
    }

    @ParameterizedTest
    @ValueSource(ints = {550, 551, 552, 553, 554})
    void fiveHundredsArePermanent(int code) {
        SmtpFailure f = SmtpErrorClassifier.classify(sendFailed(code));
        assertThat(f.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(f.smtpCode()).isEqualTo(code);
        assertThat(f.reason()).isEqualTo(code == 552 ? Reason.TOO_LARGE
                : code == 554 ? Reason.REJECTED : Reason.RECIPIENT_REJECTED);
    }

    @Test
    void permanentHistoryTextMatchesSpecExample() {
        assertThat(SmtpErrorClassifier.classify(sendFailed(550)).historyText()).isEqualTo("SMTP 550 (rejected)");
    }

    @ParameterizedTest
    @ValueSource(ints = {530, 534, 535, 538})
    void authAndTlsRequiredCodesAreConfig(int code) {
        SmtpFailure f = SmtpErrorClassifier.classify(sendFailed(code));
        assertThat(f.kind()).isEqualTo(Kind.CONFIG);
        assertThat(f.smtpCode()).isEqualTo(code);
        assertThat(f.reason()).isEqualTo(code == 535 ? Reason.AUTH_FAILED : Reason.AUTH_OR_TLS_REQUIRED);
    }

    @Test
    void addressAndSenderFailuresKeepTheirCodeAndReason() {
        SmtpFailure rcpt = SmtpErrorClassifier.classify(
                new SMTPAddressFailedException(address(), "RCPT TO", 550, "550 " + SERVER_TEXT));
        assertThat(rcpt.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(rcpt.reason()).isEqualTo(Reason.RECIPIENT_REJECTED);
        SmtpFailure sender = SmtpErrorClassifier.classify(
                new SMTPSenderFailedException(address(), "MAIL FROM", 553, "553 " + SERVER_TEXT));
        assertThat(sender.reason()).isEqualTo(Reason.SENDER_REJECTED);
        assertThat(sender.smtpCode()).isEqualTo(553);
        SmtpFailure tempRcpt = SmtpErrorClassifier.classify(
                new SMTPAddressFailedException(address(), "RCPT TO", 450, "450 " + SERVER_TEXT));
        assertThat(tempRcpt.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(tempRcpt.smtpCode()).isEqualTo(450);
    }

    // ------------------------------------------------------------------ CONFIG by exception type / message

    @Test
    void authenticationFailedIsConfigAndReadsTheLeadingCode() {
        SmtpFailure f = SmtpErrorClassifier.classify(new AuthenticationFailedException("535 " + SERVER_TEXT));
        assertThat(f.kind()).isEqualTo(Kind.CONFIG);
        assertThat(f.reason()).isEqualTo(Reason.AUTH_FAILED);
        assertThat(f.smtpCode()).isEqualTo(535);
        assertThat(f.historyText()).isEqualTo("SMTP login failed (535). Check SMTP_USERNAME and SMTP_PASSWORD.");
        assertThat(f.userMessage("smtp.example.com", 587, Duration.ofSeconds(10)))
                .isEqualTo("The mail server rejected the login (535). Check SMTP_USERNAME and SMTP_PASSWORD.");
    }

    @Test
    void springAuthenticationExceptionWrappingAngusIsConfig() {
        var spring = new MailAuthenticationException(new AuthenticationFailedException("535 " + SERVER_TEXT));
        SmtpFailure f = SmtpErrorClassifier.classify(spring);
        assertThat(f.kind()).isEqualTo(Kind.CONFIG);
        assertThat(f.smtpCode()).isEqualTo(535);
        assertThat(SmtpErrorClassifier.classify(new MailAuthenticationException("no details")).kind())
                .isEqualTo(Kind.CONFIG);
    }

    @Test
    void starttlsRequiredMessageIsConfig() {
        SmtpFailure f = SmtpErrorClassifier.classify(
                new MessagingException(SmtpErrorClassifier.STARTTLS_REQUIRED_MESSAGE));
        assertThat(f.kind()).isEqualTo(Kind.CONFIG);
        assertThat(f.reason()).isEqualTo(Reason.STARTTLS_UNAVAILABLE);
        assertThat(f.userMessage("smtp.example.com", 587, Duration.ofSeconds(10)))
                .isEqualTo("The mail server doesn't offer STARTTLS on port 587. Use port 465 with SMTP_SECURITY=SSL.");
    }

    @Test
    void tlsHandshakeAndUnknownHostAreConfigEvenWhenNested() {
        var tls = new MessagingException("Could not convert socket to TLS",
                new SSLHandshakeException("PKIX path building failed " + ADDRESS));
        assertThat(SmtpErrorClassifier.classify(tls).reason()).isEqualTo(Reason.TLS_HANDSHAKE);
        assertThat(SmtpErrorClassifier.classify(tls).kind()).isEqualTo(Kind.CONFIG);
        var host = new MessagingException("Unknown SMTP host: smtp.nowhere.invalid",
                new UnknownHostException("smtp.nowhere.invalid"));
        assertThat(SmtpErrorClassifier.classify(new MailSendException("x", host)).reason())
                .isEqualTo(Reason.UNKNOWN_HOST);
    }

    // ------------------------------------------------------------------ connect / IO

    @Test
    void angusConnectRefusedChainIsTransientConnectRefused() {
        var sce = new SocketConnectException("Using socket factory", new ConnectException("Connection refused"),
                "127.0.0.1", 2525, 10_000);
        var mce = new MailConnectException(sce);
        SmtpFailure f = SmtpErrorClassifier.classify(new MailSendException("Mail server connection failed", mce));
        assertThat(f.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(f.reason()).isEqualTo(Reason.CONNECT_REFUSED);
        assertThat(f.historyText()).isEqualTo("connection refused");
        assertThat(f.userMessage("smtp.example.com", 587, Duration.ofSeconds(10)))
                .isEqualTo("Couldn't connect to smtp.example.com:587: the connection was refused. "
                        + "Check SMTP_HOST and SMTP_PORT.");
    }

    @Test
    void angusConnectTimeoutChainIsTransientConnectTimeout() {
        var sce = new SocketConnectException("Using socket factory", new SocketTimeoutException("connect timed out"),
                "smtp.example.com", 587, 10_000);
        SmtpFailure f = SmtpErrorClassifier.classify(new MailConnectException(sce));
        assertThat(f.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(f.reason()).isEqualTo(Reason.CONNECT_TIMEOUT);
        assertThat(f.historyText()).isEqualTo("connect timeout");
        // §5.4 example text
        assertThat(f.userMessage("smtp.example.com", 587, Duration.ofSeconds(10)))
                .isEqualTo("Couldn't connect to smtp.example.com:587 within 10 seconds.");
    }

    @Test
    void readTimeoutIsTransient() {
        var e = new MessagingException("Exception reading response", new SocketTimeoutException("Read timed out"));
        SmtpFailure f = SmtpErrorClassifier.classify(e);
        assertThat(f.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(f.reason()).isEqualTo(Reason.READ_TIMEOUT);
        assertThat(SmtpErrorClassifier.classify(new SocketTimeoutException()).reason()).isEqualTo(Reason.READ_TIMEOUT);
    }

    @Test
    void plainConnectExceptionAndIoAreTransient() {
        assertThat(SmtpErrorClassifier.classify(new ConnectException("refused")).reason())
                .isEqualTo(Reason.CONNECT_REFUSED);
        assertThat(SmtpErrorClassifier.classify(new MessagingException("x", new IOException("broken pipe"))).reason())
                .isEqualTo(Reason.IO);
    }

    // ------------------------------------------------------------------ PERMANENT without a code

    @Test
    void invalidAddressesArePermanent() {
        var sfe = new SendFailedException("Invalid Addresses", null, new Address[0], new Address[0],
                new Address[]{address()});
        assertThat(SmtpErrorClassifier.classify(sfe).kind()).isEqualTo(Kind.PERMANENT);
        assertThat(SmtpErrorClassifier.classify(sfe).reason()).isEqualTo(Reason.RECIPIENT_REJECTED);
        SmtpFailure addr = SmtpErrorClassifier.classify(new AddressException("Missing final '@domain'", ADDRESS));
        assertThat(addr.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(addr.reason()).isEqualTo(Reason.INVALID_ADDRESS);
    }

    // ------------------------------------------------------------------ nesting

    @Test
    void codeFoundInsideMailSendExceptionFailedMessages() {
        Map<Object, Exception> failed = new LinkedHashMap<>();
        failed.put(new Object(), new MessagingException("wrapper " + ADDRESS, sendFailed(452)));
        var mse = new MailSendException(failed);
        SmtpFailure f = SmtpErrorClassifier.classify(mse);
        assertThat(f.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(f.smtpCode()).isEqualTo(452);
    }

    @Test
    void codeFoundThroughGetNextException() {
        var outer = new MessagingException("outer");
        outer.setNextException(sendFailed(554));
        SmtpFailure f = SmtpErrorClassifier.classify(outer);
        assertThat(f.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(f.smtpCode()).isEqualTo(554);
    }

    @Test
    void configWinsOverPermanentAndTransientInOneGraph() {
        Map<Object, Exception> failed = new LinkedHashMap<>();
        failed.put("a", sendFailed(550));
        failed.put("b", sendFailed(421));
        failed.put("c", new AuthenticationFailedException("535 nope"));
        assertThat(SmtpErrorClassifier.classify(new MailSendException(failed)).kind()).isEqualTo(Kind.CONFIG);
        Map<Object, Exception> f2 = new LinkedHashMap<>();
        f2.put("a", sendFailed(421));
        f2.put("b", sendFailed(550));
        assertThat(SmtpErrorClassifier.classify(new MailSendException(f2)).kind()).isEqualTo(Kind.PERMANENT);
    }

    @Test
    void cyclicChainsTerminate() {
        var a = new MessagingException("a");
        var b = new MessagingException("b", a);
        a.setNextException(b);                     // a -> b -> a
        SmtpFailure f = SmtpErrorClassifier.classify(a);
        assertThat(f.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(f.reason()).isEqualTo(Reason.UNKNOWN);

        RuntimeException deep = new RuntimeException("0");
        for (int i = 1; i < 200; i++) {
            deep = new RuntimeException(String.valueOf(i), deep);
        }
        assertThat(SmtpErrorClassifier.flatten(deep)).hasSize(SmtpErrorClassifier.MAX_NODES);
    }

    @Test
    void nullAndUnknownAreTransient() {
        assertThat(SmtpErrorClassifier.classify(null).kind()).isEqualTo(Kind.TRANSIENT);
        SmtpFailure f = SmtpErrorClassifier.classify(new IllegalStateException(SERVER_TEXT));
        assertThat(f.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(f.reason()).isEqualTo(Reason.UNKNOWN);
        assertThat(f.historyText()).isEqualTo("send failed (IllegalStateException)");
    }

    // ------------------------------------------------------------------ no leaks

    @Test
    void noTextEverContainsTheExceptionMessage() {
        Exception[] all = {
                sendFailed(421), sendFailed(535), sendFailed(550), sendFailed(552), sendFailed(530),
                new AuthenticationFailedException("535 " + SERVER_TEXT),
                new MailAuthenticationException(SERVER_TEXT),
                new MessagingException(SERVER_TEXT, new SSLHandshakeException(SERVER_TEXT)),
                new MessagingException(SERVER_TEXT, new UnknownHostException(SERVER_TEXT)),
                new MessagingException(SERVER_TEXT, new SocketTimeoutException(SERVER_TEXT)),
                new MailSendException(SERVER_TEXT, new IOException(SERVER_TEXT)),
                new SMTPAddressFailedException(address(), "RCPT", 550, SERVER_TEXT),
                new SMTPSenderFailedException(address(), "MAIL", 553, SERVER_TEXT),
                new AddressException(SERVER_TEXT, ADDRESS),
                new IllegalArgumentException(SERVER_TEXT)};
        for (Exception e : all) {
            SmtpFailure f = SmtpErrorClassifier.classify(e);
            String texts = f.historyText() + " | " + f.userMessage("smtp.example.com", 587, Duration.ofSeconds(10))
                    + " | " + new NotifyException(f).getMessage() + " | " + f;
            assertThat(texts).as("texts for %s", e.getClass().getSimpleName())
                    .doesNotContain(ADDRESS).doesNotContain(PASSWORD).doesNotContain("Zqserver");
            assertThat(f.historyText().length()).isLessThanOrEqualTo(SmtpFailure.MAX_TEXT);
            assertThat(new NotifyException(f).getCause()).isNull();
        }
    }

    @Test
    void everyReasonHasBothTexts() {
        for (Reason r : Reason.values()) {
            SmtpFailure f = new SmtpFailure(Kind.TRANSIENT, null, r, null);
            assertThat(f.historyText()).isNotBlank();
            assertThat(f.userMessage(null, 25, null)).isNotBlank();
            SmtpFailure withCode = new SmtpFailure(Kind.TRANSIENT, 451, r, "a.b.C");
            assertThat(withCode.historyText()).isNotBlank();
        }
        assertThat(new SmtpFailure(Kind.TRANSIENT, null, Reason.CONNECT_TIMEOUT, null)
                .userMessage("h", 25, Duration.ofSeconds(1))).endsWith("within 1 second.");
    }
}
