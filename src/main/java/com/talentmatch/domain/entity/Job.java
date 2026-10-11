package com.talentmatch.domain.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A job opening. {@code origin} is MANUAL (API / ETL; (title, company) is unique among these) or FEED
 * (created and kept up to date by the job feed through JDBC; read-only here).
 */
@Entity
@Table(name = "job")
public class Job {

    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_FEED = "FEED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "company", nullable = false, length = 200)
    private String company;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Never written through JPA: the column default is MANUAL; FEED jobs are inserted by the feed (JDBC). */
    @Column(name = "origin", nullable = false, length = 10, insertable = false, updatable = false)
    private String origin;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<JobSkill> skills = new HashSet<>();

    protected Job() {
    }

    public Job(String title, String company, String description) {
        this.title = title;
        this.company = company;
        this.description = description;
        this.origin = ORIGIN_MANUAL;   // matches the column default the insert relies on
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOrigin() {
        return origin;
    }

    public boolean isFeed() {
        return ORIGIN_FEED.equals(origin);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<JobSkill> getSkills() {
        return skills;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Job other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Job.class.hashCode();
    }
}
