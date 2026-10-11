package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceKind;
import com.talentmatch.notify.NotificationContent;
import com.talentmatch.notify.NotificationContentSource;
import com.talentmatch.service.exception.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The feed's side of {@link NotificationContentSource}: the job facts of a notification, read with
 * {@link FeedJobService#get} (a read-only transaction, plain MVCC reads, no locks). Only job data and the
 * names of its required skills split into matched and missing; never the description or anything from
 * the owner's profile.
 */
@Component
public class FeedNotificationContent implements NotificationContentSource {

    private final FeedJobService jobs;

    public FeedNotificationContent(FeedJobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public Optional<NotificationContent> load(UUID jobId) {
        FeedJobView v;
        try {
            v = jobs.get(jobId);
        } catch (NotFoundException e) {
            return Optional.empty();
        }
        List<FeedJobView.Source> open = v.sources().stream().filter(s -> s.closedAt() == null).toList();
        List<FeedJobView.Source> shown = open.isEmpty() ? v.sources() : open;
        Set<String> kinds = new LinkedHashSet<>();
        boolean adzuna = false;
        for (FeedJobView.Source s : shown) {
            if (s.via() != null) {
                kinds.add(s.via());
            }
            adzuna |= s.kind() == SourceKind.ADZUNA;
        }
        return Optional.of(new NotificationContent(v.jobId(), v.title(), v.company(),
                v.workplace() == null ? null : v.workplace().name(), v.locationText(),
                v.countryCodes() == null ? List.of() : v.countryCodes(), v.postedAt(), v.firstSeenAt(),
                v.primaryUrl(), v.summary(), v.matchedRequired(), v.missingRequired(), v.flags(),
                new ArrayList<>(kinds), adzuna));
    }
}
