package com.talentmatch.feed;

import com.talentmatch.feed.source.SourceAdapter;
import com.talentmatch.feed.source.SourceKind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The adapter for each source kind (one bean per provider; Adzuna arrives in step 9). */
@Component
public class SourceAdapters {

    private final Map<SourceKind, SourceAdapter> byKind;

    public SourceAdapters(List<SourceAdapter> adapters) {
        EnumMap<SourceKind, SourceAdapter> map = new EnumMap<>(SourceKind.class);
        for (SourceAdapter adapter : adapters) {
            SourceAdapter previous = map.put(adapter.kind(), adapter);
            if (previous != null) {
                throw new IllegalStateException("Two source adapters for " + adapter.kind() + ": "
                        + previous.getClass().getSimpleName() + " and " + adapter.getClass().getSimpleName());
            }
        }
        this.byKind = Collections.unmodifiableMap(map);
    }

    public Optional<SourceAdapter> find(SourceKind kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    public boolean supports(SourceKind kind) {
        return byKind.containsKey(kind);
    }
}
