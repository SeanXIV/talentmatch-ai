package com.talentmatch.application.render;

import static com.talentmatch.application.render.RenderText.hasText;

import com.talentmatch.application.content.CoverLetterContent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed cover-letter layout (decision b, §4.8): contact line, date, "Hiring team, {company}",
 * "Re: {jobTitle}", salutation, the paragraphs, closing and signature. Pure and thread-safe.
 */
public final class LetterLayout {

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};

    private LetterLayout() {
    }

    public static List<Block> layout(CoverLetterContent letter) {
        List<Block> blocks = new ArrayList<>();
        String contact = CvLayout.contactLine(letter.header());
        if (contact != null) {
            blocks.add(new Block.ContactLine(contact));
            blocks.add(new Block.Spacer());
        }
        if (letter.date() != null) {
            blocks.add(new Block.Paragraph(formatDate(letter.date())));
            blocks.add(new Block.Spacer());
        }
        boolean addressed = false;
        if (hasText(letter.company())) {
            blocks.add(new Block.Paragraph("Hiring team, " + letter.company().strip()));
            addressed = true;
        }
        if (hasText(letter.jobTitle())) {
            blocks.add(new Block.Paragraph("Re: " + letter.jobTitle().strip()));
            addressed = true;
        }
        if (addressed) {
            blocks.add(new Block.Spacer());
        }
        paragraph(blocks, letter.salutation());
        for (String paragraph : CvLayout.orEmpty(letter.paragraphs())) {
            paragraph(blocks, paragraph);
        }
        paragraph(blocks, letter.closing());
        paragraph(blocks, letter.signature());
        return List.copyOf(blocks);
    }

    /** British long date, locale-independent: 19 October 2026. */
    public static String formatDate(LocalDate date) {
        return date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
    }

    private static void paragraph(List<Block> blocks, String text) {
        if (hasText(text)) {
            blocks.add(new Block.Paragraph(text.strip()));
        }
    }
}
