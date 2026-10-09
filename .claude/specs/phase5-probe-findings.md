# Phase 5 provider probe findings (§10 step 4), from @tester, 2026-10-07

Live, unauthenticated probes on 2026-10-07:
- Greenhouse: 4 boards (airbnb, stripe, gitlab, discord), 1,146 jobs.
- Lever: 12 sites, 2,259 postings.
- Ashby: 4 boards (ramp, notion, linear, openai), 1,144 postings.
- **Adzuna: not probed.** No credentials were available.

Where this file and §4.2 of `phase5-feed.md` disagree, **this file wins**.

Fixtures (test classpath `feed/*.json`): `tests/resources/feed/{greenhouse-list,greenhouse-detail,lever,ashby}.json`.

## §11 assumptions

### 1. Greenhouse
- **`first_published` in the list: confirmed.**
  - It was present and non-null on every job probed.
  - It differs from `updated_at` on most jobs (gitlab: 185 of 217), so the "never `updated_at`" rule matters.
  - The fixture hand-adds one `first_published: null` job.
- **`content` entity-escaped:**
  - **Confirmed** for job detail (`&lt;div …&gt;`), including double-escaped `&amp;nbsp;`.
  - **Differs** for the board probe: `GET /v1/boards/{token}` returns `{"name","content"}`, where `content` is plain HTML.
- **Unknown board: confirmed 404.**
  - `/v1/boards/x/jobs` → `404 {"status":404,"error":"Job not found"}`. The message is misleading.
  - `/v1/boards/x` → `404 {"error":"Job board not found"}`.
  - Unknown job id → `404 {"error":"Job not found"}`.
- **ETag/Last-Modified: partly differs.**
  - Greenhouse sends a weak `etag: W/"…"`, and `If-None-Match` returns 304.
  - There is no `Last-Modified`, and `If-Modified-Since` is ignored (200).
- **Other findings:**
  - gzip is sent.
  - `cache-control: max-age=0, private, must-revalidate`.
  - No rate-limit headers.
  - Board tokens are case-insensitive.

### 2. Lever
- **No pagination needed: confirmed.**
  - `mode=json` with no limit returned all 931 veeva postings.
  - `skip`/`limit` work but are optional.
- **`country`: confirmed** as ISO-2, but **nullable** (1 of 2,259).
- **`workplaceType`: confirmed** as lowercase `hybrid|onsite|remote|unspecified`.
- **Unknown site: confirmed 404** with `{"ok":false,"error":"Document not found"}`.
- **Existing site with no postings: differs.** It returns `200 []`.
- **EU host: confirmed.** `https://api.eu.lever.co/v0/postings/{site}?mode=json` works, and `hostedUrl` is on `jobs.eu.lever.co`.
- **Other findings:**
  - Weak ETag, and `If-None-Match` returns 304.
  - **No gzip, even when requested.**
  - No `Last-Modified` and no rate-limit headers.
  - **Site names are case-sensitive** (`Spotify` → 404, `spotify` → 200).

### 3. Ashby
- **Path: confirmed.** It returns `{"apiVersion":"1","jobs":[…]}`. Without `includeCompensation=true` there is no `compensation` key.
- **Field names: confirmed**, with these differences:
  - `workplaceType` is `Remote|Hybrid|OnSite|null` (PascalCase). It is null on about 30% of notion and openai postings, and `isRemote` is null there too.
  - `employmentType` is `FullTime|Intern|Temporary|Contract`.
  - Extra fields: `descriptionHtml`, `shouldDisplayCompensationOnJobPostings`, `secondaryLocations[].address.postalAddress`, `compensation.{compensationTierSummary,scrapeableCompensationSalarySummary,compensationTiers}`, and `postalAddress.{addressLocality,addressRegion}`.
  - Every posting probed had `isListed=true`. The fixture hand-adds an `isListed=false` posting.
- **Unknown board: differs.** It returns `404 text/plain` with body `Not Found` (not JSON). A 200 response with an empty list is unverified.
- **Other findings:**
  - Weak ETag, and `If-None-Match` returns 304.
  - gzip is sent.
  - `cache-control: public, max-age=60`.
  - Board names are case-insensitive.
  - The openai board is 14 MB uncompressed, which is under the 20 MB cap but close.

### 4. Adzuna: unverified
There were no credentials. Limits, terms, `za` coverage and the `max_days_old`/`sort_by` behaviour are all unconfirmed, and there is no `adzuna.json` fixture yet.

### 5–6. Email and LangChain4j: out of scope for this probe.

## Required §4.2 mapping changes

### §4.2.1 Greenhouse
- **Company:** every job (list and detail) has `company_name`. Use it, and fall back to the source's `company_name` or the probe's `name`.
- **Salary:**
  - Fetch detail with `?pay_transparency=true`, which returns `pay_input_ranges[{min_cents,max_cents,currency_type,title,blurb}]`.
  - Map the first range: `min_cents/100` and `max_cents/100` with `currency_type`. The period is unknown, so leave `salaryPeriod` unset.
  - There may be several regional ranges. Treat `[]` as no salary.
- **Location and country:**
  - `offices` can be `[]` (on remote jobs).
  - `location.name` can hold several `;`-separated locations. Derive the country from that string or from `offices[].location`.
- **Escaping:** unescape only job `content`, never the board probe's `content`.
- **Conditional requests:** store the ETag only. There is no `Last-Modified`.
- **URL:** `absolute_url` is often the company's own careers site.

### §4.2.2 Lever
- **Salary:** `salaryRange.interval` is `per-year-salary|per-month-salary|per-hour-wage`. Map it to `SalaryPeriod`.
- **Nullable fields:**
  - `country` can be null.
  - `categories.commitment` (→ employmentType) can be missing.
- **Description:** the composition `descriptionPlain` + lists + `additionalPlain` is correct.
- **Probe:** `200 []` means the site exists with no postings → return a "no open postings" warning, not a 400.
- **Tokens:** keep site tokens case-sensitive. `SourceKeys` must not lowercase Lever tokens.
- **Pagination:** not needed.

### §4.2.3 Ashby
- **Workplace:**
  - Map `workplaceType` case-insensitively (`OnSite` → ONSITE).
  - When it is null, use `isRemote == true` → REMOTE. Otherwise use UNSPECIFIED.
- **Country:**
  - `addressCountry` is free text: `USA`, `United States`, `European Union`, `South Korea`, or missing.
  - Match English names plus aliases (`USA`, `US`, `UK`, `South Korea`, …).
  - Treat `European Union` or a missing value as null.
- **Salary:**
  - Use only the `summaryComponents` entries with `compensationType=="Salary"`.
  - Map intervals `1 YEAR|1 MONTH|1 HOUR`. Ignore `NONE` and entries with null `minValue`.
  - Several tiers: use min-of-mins and max-of-maxes.
  - The currency is `currencyCode`.
- **Content version:** there is no updated timestamp, so `contentVersion=null`.
- **Title:** trim it, because titles can have leading spaces.
- **Probe:** `404 text/plain` means no such board. Parse JSON only on 2xx.
- **Listing:** keep the `isListed=false` skip.

### SourceHttpClient
- **Conditional requests:** `If-None-Match` works on all three providers. `If-Modified-Since` has no effect anywhere.
- **Body cap:** Lever doesn't gzip, so the body cap must also apply to uncompressed streams.
- **Error bodies:** they can be non-JSON (Ashby). Check the status before parsing.

### §4.2.4 Adzuna
Still unverified. Probe it once keys are available (step 9).
