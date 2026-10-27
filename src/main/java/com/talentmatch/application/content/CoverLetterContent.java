package com.talentmatch.application.content;

import java.time.LocalDate;
import java.util.List;

/**
 * A cover letter version (stored as {@code application_document.content}). Only {@code paragraphs} is
 * written by the model (or the owner); everything else is set by the server (decision p) and read-only
 * in edits.
 *
 * @param company    the job's company (snapshot)
 * @param jobTitle   the job's title (snapshot), printed as "Re: {jobTitle}"
 * @param date       the letter date (today in {@code talentmatch.applications.zone} at generation)
 * @param salutation "Dear Hiring Manager,"
 * @param closing    "Kind regards,"
 * @param signature  the owner's full name
 */
public record CoverLetterContent(
        Header header,
        String company,
        String jobTitle,
        LocalDate date,
        String salutation,
        List<String> paragraphs,
        String closing,
        String signature) {

    public CoverLetterContent {
        paragraphs = Lists.of(paragraphs);
    }
}
