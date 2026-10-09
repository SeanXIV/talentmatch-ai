package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** §9.1 item 15 (source part): ranges, base URL rules, defaults. */
class SourcePropertiesTest {

    private static SourceProperties.Http http(Duration connect, Duration read, long maxBody, int threads,
                                              Duration spacing, String ua) {
        return new SourceProperties.Http(connect, read, maxBody, threads, spacing, ua);
    }

    private static SourceProperties.Http okHttp() {
        return SourceProperties.Http.defaults();
    }

    @Test
    void defaults() {
        SourceProperties p = SourceProperties.defaults();
        assertThat(p.maxPostingsPerSource()).isEqualTo(5000);
        assertThat(p.http().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.http().readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.http().maxBodyBytes()).isEqualTo(20_971_520L);
        assertThat(p.http().minHostSpacing()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.greenhouse().baseUrl()).isEqualTo(URI.create("https://boards-api.greenhouse.io"));
        assertThat(p.greenhouse().maxDetailCallsPerPoll()).isEqualTo(20);
        assertThat(p.lever().baseUrl()).isEqualTo(URI.create("https://api.lever.co"));
        assertThat(p.lever().euBaseUrl()).isEqualTo(URI.create("https://api.eu.lever.co"));
        assertThat(p.ashby().baseUrl()).isEqualTo(URI.create("https://api.ashbyhq.com"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 50_001})
    void maxPostingsOutOfRange(int value) {
        assertThatThrownBy(() -> new SourceProperties(value, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("talentmatch.feed.max-postings-per-source");
    }

    @Test
    void maxPostingsBounds() {
        assertThat(new SourceProperties(1, null, null, null, null).maxPostingsPerSource()).isEqualTo(1);
        assertThat(new SourceProperties(50_000, null, null, null, null).maxPostingsPerSource()).isEqualTo(50_000);
    }

    @Test
    void timeoutsMustBeOneToSixtySeconds() {
        assertThatThrownBy(() -> http(Duration.ofMillis(999), Duration.ofSeconds(5), 2048, 2, Duration.ZERO, "ua"))
                .hasMessageContaining("talentmatch.feed.http.connect-timeout");
        assertThatThrownBy(() -> http(Duration.ofSeconds(5), Duration.ofSeconds(61), 2048, 2, Duration.ZERO, "ua"))
                .hasMessageContaining("talentmatch.feed.http.read-timeout");
        assertThatThrownBy(() -> http(null, Duration.ofSeconds(5), 2048, 2, Duration.ZERO, "ua"))
                .hasMessageContaining("connect-timeout");
        assertThat(http(Duration.ofSeconds(1), Duration.ofSeconds(60), 2048, 2, Duration.ZERO, "ua")).isNotNull();
    }

    @Test
    void bodyCapThreadsAndSpacingRanges() {
        Duration s = Duration.ofSeconds(5);
        assertThatThrownBy(() -> http(s, s, 1023, 2, Duration.ZERO, "ua"))
                .hasMessageContaining("talentmatch.feed.http.max-body-bytes");
        assertThatThrownBy(() -> http(s, s, 100L * 1024 * 1024 + 1, 2, Duration.ZERO, "ua"))
                .hasMessageContaining("max-body-bytes");
        assertThatThrownBy(() -> http(s, s, 2048, 0, Duration.ZERO, "ua"))
                .hasMessageContaining("talentmatch.feed.http.poll-threads");
        assertThatThrownBy(() -> http(s, s, 2048, 17, Duration.ZERO, "ua")).hasMessageContaining("poll-threads");
        assertThatThrownBy(() -> http(s, s, 2048, 2, Duration.ofMillis(-1), "ua"))
                .hasMessageContaining("talentmatch.feed.http.min-host-spacing");
        assertThatThrownBy(() -> http(s, s, 2048, 2, Duration.ofSeconds(61), "ua"))
                .hasMessageContaining("min-host-spacing");
        assertThat(http(s, s, 1024, 1, Duration.ofSeconds(60), "ua").pollThreads()).isEqualTo(1);
        assertThat(http(s, s, 100L * 1024 * 1024, 16, Duration.ZERO, "ua").pollThreads()).isEqualTo(16);
    }

    @Test
    void userAgentRules() {
        Duration s = Duration.ofSeconds(5);
        assertThatThrownBy(() -> http(s, s, 2048, 2, Duration.ZERO, " "))
                .hasMessageContaining("talentmatch.feed.http.user-agent");
        assertThatThrownBy(() -> http(s, s, 2048, 2, Duration.ZERO, "a\r\nX-Evil: 1"))
                .hasMessageContaining("user-agent");
        assertThatThrownBy(() -> http(s, s, 2048, 2, Duration.ZERO, "x".repeat(301))).hasMessageContaining("user-agent");
        assertThat(http(s, s, 2048, 2, Duration.ZERO, "  TalentMatch/0.5  ").userAgent()).isEqualTo("TalentMatch/0.5");
    }

    @Test
    void greenhouseDetailCallRange() {
        URI base = URI.create("https://boards-api.greenhouse.io");
        assertThatThrownBy(() -> new SourceProperties.Greenhouse(base, -1))
                .hasMessageContaining("talentmatch.feed.greenhouse.max-detail-calls-per-poll");
        assertThatThrownBy(() -> new SourceProperties.Greenhouse(base, 201))
                .hasMessageContaining("max-detail-calls-per-poll");
        assertThat(new SourceProperties.Greenhouse(base, 0).maxDetailCallsPerPoll()).isZero();
        assertThat(new SourceProperties.Greenhouse(base, 200).maxDetailCallsPerPoll()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://api.lever.co", "file:///etc/passwd", "https://api.lever.co?x=1",
            "https://api.lever.co#frag", "/relative", "javascript:alert(1)"})
    void baseUrlMustBeHttpWithHostAndNoQuery(String url) {
        assertThatThrownBy(() -> new SourceProperties.Lever(URI.create(url), URI.create("https://api.eu.lever.co")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("talentmatch.feed.lever.base-url");
        assertThatThrownBy(() -> new SourceProperties.Ashby(URI.create(url)))
                .hasMessageContaining("talentmatch.feed.ashby.base-url");
    }

    @Test
    void baseUrlTrailingSlashRemoved() {
        assertThat(new SourceProperties.Ashby(URI.create("https://api.ashbyhq.com//")).baseUrl())
                .isEqualTo(URI.create("https://api.ashbyhq.com"));
        assertThat(new SourceProperties.Lever(URI.create("http://127.0.0.1:9999/"), URI.create("HTTPS://eu.x/"))
                .euBaseUrl()).isEqualTo(URI.create("HTTPS://eu.x"));
    }

    @Test
    void nullNestedUseDefaults() {
        SourceProperties p = new SourceProperties(10, null, null, null, null);
        assertThat(p.http()).isEqualTo(okHttp());
    }
}
