package com.talentmatch.application.content;

import java.util.List;

/**
 * The contact header of a CV or cover letter. Always copied from the profile version by the server;
 * never sent to the model (decision q) and read-only in edits.
 */
public record Header(String fullName, String email, String phone, String location, List<LinkItem> links) {

    public Header {
        links = Lists.of(links);
    }
}
