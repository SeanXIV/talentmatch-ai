package com.talentmatch.feed;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What one successful fetch asks the {@link PollWriter} to store (built outside any transaction).
 *
 * @param entries        one per posting in the listing that could be read
 * @param fetchedIds     external ids of every posting in the listing (closing compares against these)
 * @param complete       the listing holds every open posting (missing ones close)
 * @param fetched        postings in the listing
 * @param skipped        postings the adapter or the normalizer couldn't use
 * @param detailsPending some Greenhouse descriptions are still missing or outdated: the ETag and body
 *                       hash are not stored, so the next fetch is a full one and fills them
 * @param etag           the provider's ETag (nullable)
 * @param lastModified   the provider's Last-Modified (nullable)
 * @param bodyHash       sha256 of the listing body
 * @param detailCalls    Greenhouse detail requests made
 */
record PollPlan(List<Entry> entries, Set<String> fetchedIds, boolean complete, int fetched, int skipped,
                boolean detailsPending, String etag, String lastModified, String bodyHash, int detailCalls) {

    PollPlan {
        entries = List.copyOf(entries);
        fetchedIds = Set.copyOf(fetchedIds);
    }

    /**
     * One posting of the listing.
     *
     * @param posting the content to store; null means "seen, content unchanged or not known yet"
     *                (only {@code last_seen_at} is written, and a closed posting reopens)
     */
    record Entry(String externalId, NormalizedPosting posting) {

        Entry {
            Objects.requireNonNull(externalId, "externalId");
        }

        static Entry touch(String externalId) {
            return new Entry(externalId, null);
        }
    }
}
