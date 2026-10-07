package com.talentmatch.profile;

/** Extraction state of an uploaded CV. */
public enum ResumeStatus {
    /** Waiting for the extraction queue. */
    PENDING,
    /** The model is reading the CV. */
    RUNNING,
    /** A draft profile is ready for review. */
    SUCCEEDED,
    /** No draft; see the failure reason. The owner can retry or enter the profile by hand. */
    FAILED
}
