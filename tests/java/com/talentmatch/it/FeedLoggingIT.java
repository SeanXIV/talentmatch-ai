package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.talentmatch.support.AbstractFeedIT;
import com.talentmatch.support.RouteStubServer.Reply;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** §6.2 logs: no posting text, no URL query, no response body (DEBUG included). */
@ExtendWith(OutputCaptureExtension.class)
class FeedLoggingIT extends AbstractFeedIT {

    private final Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(3));
    private Logger feedLogger;
    private Level previous;

    @BeforeEach
    void debugFeed() {
        feedLogger = (Logger) LoggerFactory.getLogger("com.talentmatch.feed");
        previous = feedLogger.getLevel();
        feedLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLevel() {
        feedLogger.setLevel(previous);
    }

    @Test
    void pollLogsHoldNoPostingTextQueryOrBody(CapturedOutput output) {
        UUID lv = lever("acme", "Acme");
        leverReplies("acme",
                new LeverJob("a", "ZQXTITLE Engineer", "ZQXDESC secret description", t)
                        .withUrl("https://jobs.lever.co/acme/a?utm_source=ZQXQUERY"),
                new LeverJob("b", "Engineer B", "desc", t),
                new LeverJob("c", "Engineer C", "desc", t),
                new LeverJob("d", "ZQXBADTITLE", "ZQXBADDESC", t).withUrl("javascript:ZQXJS"));
        pollOk(lv);

        // a failure with a body, a detail failure, and a 429
        STUB.route(leverPath("acme"), Reply.json(500, "{\"error\":\"ZQXBODY\"}"));
        pollSync(lv);
        UUID gh = greenhouse("acme");
        STUB.route(ghListPath("acme"), Reply.json(200, ghListBody(
                new GhJob("1", "ZQXGHTITLE", "Acme", "Remote", t, t, "<p>ZQXGHDESC</p>"))));
        STUB.route(ghDetailPath("acme", "1"), Reply.json(503, "ZQXDETAILBODY"));
        pollSync(gh);
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL");
        STUB.route(ghListPath("acme"), Reply.json(429, "ZQXRATEBODY").withHeader("Retry-After", "120"));
        pollSync(gh);

        assertThat(output.getAll()).contains("Feed poll source=" + lv);
        assertThat(output.getAll()).doesNotContain("ZQX");
        assertThat(output.getAll()).doesNotContain("mode=json").doesNotContain("pay_transparency");
    }
}
