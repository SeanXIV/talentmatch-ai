package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.talentmatch.feed.PostingNormalizer.InvalidPostingException;
import com.talentmatch.feed.source.RawPosting;
import com.talentmatch.preferences.SalaryPeriod;
import com.talentmatch.preferences.Seniority;
import com.talentmatch.preferences.Workplace;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** §4.4 normalization to the V5 columns. */
class PostingNormalizerTest {

    private static final Instant POSTED = Instant.parse("2026-10-01T10:00:00Z");

    /** Builder over RawPosting's long canonical constructor. */
    private static final class Raw {
        String id = "123";
        String url = "https://jobs.example.com/123";
        String title = "Senior Backend Engineer";
        String company = "Acme";
        String description = "Build things.";
        boolean html = false;
        boolean complete = true;
        String location = "Cape Town";
        String country = "ZA";
        Workplace workplace = Workplace.HYBRID;
        String employmentType = "Full-time";
        BigDecimal min = new BigDecimal("100000");
        BigDecimal max = new BigDecimal("150000");
        String currency = "ZAR";
        SalaryPeriod period = SalaryPeriod.YEAR;
        boolean estimated = false;
        Instant postedAt = POSTED;

        RawPosting build() {
            return new RawPosting(id, url, title, company, description, html, complete, location, country, workplace,
                    employmentType, min, max, currency, period, estimated, postedAt, null, null);
        }

        NormalizedPosting norm() {
            return PostingNormalizer.normalize(build());
        }
    }

    @Test
    void validPostingMapsEveryField() {
        NormalizedPosting p = new Raw().norm();
        assertThat(p.externalId()).isEqualTo("123");
        assertThat(p.title()).isEqualTo("Senior Backend Engineer");
        assertThat(p.description()).isEqualTo("Build things.");
        assertThat(p.descriptionComplete()).isTrue();
        assertThat(p.countryCode()).isEqualTo("ZA");
        assertThat(p.salaryMin()).isEqualByComparingTo("100000");
        assertThat(p.salaryMin().scale()).isEqualTo(2);
        assertThat(p.salaryCurrency()).isEqualTo("ZAR");
        assertThat(p.salaryPeriod()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(p.seniority()).isEqualTo(Seniority.SENIOR);
        assertThat(p.contentHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsUnstorablePostings() {
        Raw noId = new Raw();
        noId.id = "  ";
        assertThatThrownBy(noId::norm).isInstanceOf(InvalidPostingException.class).hasMessageContaining("id");
        Raw nullId = new Raw();
        nullId.id = null;
        assertThatThrownBy(nullId::norm).isInstanceOf(InvalidPostingException.class);
        Raw longId = new Raw();
        longId.id = "9".repeat(201);
        assertThatThrownBy(longId::norm).isInstanceOf(InvalidPostingException.class);
        Raw id200 = new Raw();
        id200.id = "9".repeat(200);
        assertThat(id200.norm().externalId()).hasSize(200);

        Raw js = new Raw();
        js.url = "javascript:alert(1)";
        assertThatThrownBy(js::norm).isInstanceOf(InvalidPostingException.class).hasMessageContaining("URL");
        Raw ftp = new Raw();
        ftp.url = "ftp://x/y";
        assertThatThrownBy(ftp::norm).isInstanceOf(InvalidPostingException.class);
        Raw noUrl = new Raw();
        noUrl.url = null;
        assertThatThrownBy(noUrl::norm).isInstanceOf(InvalidPostingException.class);

        Raw noTitle = new Raw();
        noTitle.title = " \t ";
        assertThatThrownBy(noTitle::norm).isInstanceOf(InvalidPostingException.class).hasMessageContaining("title");
        Raw noCompany = new Raw();
        noCompany.company = "\u0007";
        assertThatThrownBy(noCompany::norm).isInstanceOf(InvalidPostingException.class)
                .hasMessageContaining("company");
        assertThatThrownBy(() -> PostingNormalizer.normalize(null)).isInstanceOf(InvalidPostingException.class);
    }

    @Test
    void rejectionMessageNeverHoldsPostingText() {
        Raw r = new Raw();
        r.url = "javascript:SECRET_TEXT";
        r.title = "SECRET_TEXT";
        assertThatThrownBy(r::norm).message().doesNotContain("SECRET_TEXT");
    }

    @Test
    void urlSchemeIsLowerCasedAndLengthCapped() {
        Raw r = new Raw();
        r.url = "HTTPS://Jobs.Example.com/A?b=C";
        assertThat(r.norm().url()).isEqualTo("https://Jobs.Example.com/A?b=C");
        Raw longUrl = new Raw();
        longUrl.url = "https://x.com/" + "a".repeat(2000);
        assertThatThrownBy(longUrl::norm).isInstanceOf(InvalidPostingException.class);
    }

    @Test
    void lengthCapsAndOneLineFields() {
        Raw r = new Raw();
        r.title = "  Senior\n\tBackend   Engineer ";
        r.company = "c".repeat(250);
        r.location = "l".repeat(600);
        r.employmentType = "e".repeat(80);
        NormalizedPosting p = r.norm();
        assertThat(p.title()).isEqualTo("Senior Backend Engineer");
        assertThat(p.company()).hasSize(PostingNormalizer.MAX_COMPANY);
        assertThat(p.locationText()).hasSize(PostingNormalizer.MAX_LOCATION);
        assertThat(p.employmentType()).hasSize(PostingNormalizer.MAX_EMPLOYMENT_TYPE);
        Raw t = new Raw();
        t.title = "t".repeat(400);
        assertThat(t.norm().title()).hasSize(PostingNormalizer.MAX_TITLE);
    }

    @Test
    void htmlDescriptionBecomesText() {
        Raw r = new Raw();
        r.html = true;
        r.description = "<p>Hello <b>world</b></p><script>alert('x')</script>";
        String d = r.norm().description();
        assertThat(d).contains("Hello").contains("world").doesNotContain("<").doesNotContain("alert");
    }

    @Test
    void plainDescriptionKeepsLinesAndCollapsesBlankRuns() {
        Raw r = new Raw();
        r.description = "Line one   with  spaces\r\n\r\n\r\n\r\nLine <b>two</b>\n";
        assertThat(r.norm().description()).isEqualTo("Line one with spaces\n\nLine <b>two</b>");
    }

    @Test
    void longDescriptionIsCutAtAWord() {
        Raw r = new Raw();
        r.description = "word ".repeat(5000);                      // 25000 chars
        String d = r.norm().description();
        assertThat(d.length()).isLessThanOrEqualTo(PostingNormalizer.MAX_DESCRIPTION)
                .isGreaterThan(PostingNormalizer.MAX_DESCRIPTION * 9 / 10);
        assertThat(d).endsWith("word");
        Raw noSpace = new Raw();
        noSpace.description = "x".repeat(25_000);
        assertThat(noSpace.norm().description()).hasSize(PostingNormalizer.MAX_DESCRIPTION);
    }

    @Test
    void blankDescriptionIsNullAndNotComplete() {
        Raw r = new Raw();
        r.description = "   \n  ";
        NormalizedPosting p = r.norm();
        assertThat(p.description()).isNull();
        assertThat(p.descriptionComplete()).isFalse();
        Raw h = new Raw();
        h.html = true;
        h.description = "<p> </p>";
        assertThat(h.norm().description()).isNull();
    }

    @Test
    void badCountryCodeBecomesNull() {
        Raw r = new Raw();
        r.country = "XX";
        assertThat(r.norm().countryCode()).isNull();
        Raw lower = new Raw();
        lower.country = " za ";
        assertThat(lower.norm().countryCode()).isEqualTo("ZA");
        Raw name = new Raw();
        name.country = "South Africa";
        assertThat(name.norm().countryCode()).isNull();
    }

    @Test
    void salaryRules() {
        Raw swapped = new Raw();
        swapped.min = new BigDecimal("200");
        swapped.max = new BigDecimal("100.555");
        NormalizedPosting s = swapped.norm();
        assertThat(s.salaryMin()).isEqualByComparingTo("100.56");
        assertThat(s.salaryMax()).isEqualByComparingTo("200");

        Raw negative = new Raw();
        negative.min = new BigDecimal("-1");
        NormalizedPosting n = negative.norm();
        assertThat(n.salaryMin()).isNull();
        assertThat(n.salaryMax()).isEqualByComparingTo("150000");

        Raw huge = new Raw();
        huge.min = null;
        huge.max = new BigDecimal("1000000000000");
        NormalizedPosting h = huge.norm();
        assertThat(h.salaryMax()).isNull();
        assertThat(h.salaryCurrency()).as("currency without an amount").isNull();
        assertThat(h.salaryPeriod()).isNull();

        Raw noAmount = new Raw();
        noAmount.min = null;
        noAmount.max = null;
        noAmount.estimated = true;
        NormalizedPosting na = noAmount.norm();
        assertThat(na.salaryCurrency()).isNull();
        assertThat(na.salaryPeriod()).isNull();
        assertThat(na.salaryEstimated()).isFalse();

        Raw badCurrency = new Raw();
        badCurrency.currency = "rand";
        assertThat(badCurrency.norm().salaryCurrency()).isNull();
        Raw lowerCurrency = new Raw();
        lowerCurrency.currency = "usd";
        assertThat(lowerCurrency.norm().salaryCurrency()).isEqualTo("USD");
    }

    @Test
    void hashIsStableAndChangesWithContent() {
        String base = new Raw().norm().contentHash();
        assertThat(new Raw().norm().contentHash()).isEqualTo(base);

        Raw desc = new Raw();
        desc.description = "Build other things.";
        assertThat(desc.norm().contentHash()).isNotEqualTo(base);
        Raw title = new Raw();
        title.title = "Staff Backend Engineer";
        assertThat(title.norm().contentHash()).isNotEqualTo(base);
        Raw salary = new Raw();
        salary.max = new BigDecimal("150001");
        assertThat(salary.norm().contentHash()).isNotEqualTo(base);
        Raw posted = new Raw();
        posted.postedAt = POSTED.plusSeconds(1);
        assertThat(posted.norm().contentHash()).isNotEqualTo(base);
        Raw workplace = new Raw();
        workplace.workplace = Workplace.REMOTE;
        assertThat(workplace.norm().contentHash()).isNotEqualTo(base);

        // the external id isn't content; whitespace-only differences normalize away
        Raw id = new Raw();
        id.id = "456";
        assertThat(id.norm().contentHash()).isEqualTo(base);
        Raw spaces = new Raw();
        spaces.title = " Senior  Backend Engineer ";
        assertThat(spaces.norm().contentHash()).isEqualTo(base);
    }

    @Test
    void nullDescriptionVsEmptyStringFieldsHashDifferently() {
        Raw a = new Raw();
        a.location = null;
        a.employmentType = "Cape Town";
        Raw b = new Raw();
        b.location = "Cape Town";
        b.employmentType = null;
        assertThat(a.norm().contentHash()).isNotEqualTo(b.norm().contentHash());
    }
}
