package com.talentmatch.application.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

/**
 * Renders blocks as a DOCX with Apache POI XWPF (§4.8): A4, 2 cm margins, Noto Sans as the run font
 * (not embedded; Word substitutes a similar font when it is missing), headings bold 12 pt, body
 * 10.5 pt, bullets through one numbering definition, no tables. Draft → a footer on every page.
 * Stateless and thread-safe. The output isn't byte-deterministic (zip entry times).
 */
public final class DocxWriter {

    static final String FONT = "Noto Sans";

    // Twentieths of a point (twips): A4 = 210 × 297 mm; 2 cm = 1134; header/footer distance 1.25 cm.
    private static final BigInteger PAGE_WIDTH = BigInteger.valueOf(11906);
    private static final BigInteger PAGE_HEIGHT = BigInteger.valueOf(16838);
    private static final BigInteger MARGIN = BigInteger.valueOf(1134);
    private static final BigInteger HEADER_FOOTER_DISTANCE = BigInteger.valueOf(709);
    private static final BigInteger BULLET_INDENT = BigInteger.valueOf(360);

    private static final double TITLE_SIZE = 18;
    private static final double HEADING_SIZE = 12;
    private static final double BODY_SIZE = 10.5;
    private static final double FOOTER_SIZE = 8;

    public byte[] write(List<Block> blocks, DocumentMeta meta, boolean draft) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pageSetup(doc);
            BigInteger bulletNumId = null;
            List<String> footers = new ArrayList<>();
            for (Block block : blocks) {
                if (block instanceof Block.Title b) {
                    XWPFParagraph p = paragraph(doc, 0, 60);
                    run(p, b.text(), TITLE_SIZE, true);
                } else if (block instanceof Block.ContactLine b) {
                    XWPFParagraph p = paragraph(doc, 0, 120);
                    run(p, b.text(), BODY_SIZE, false);
                } else if (block instanceof Block.Heading b) {
                    XWPFParagraph p = paragraph(doc, 240, 80);
                    p.setBorderBottom(Borders.SINGLE);
                    keepWithNext(p);
                    run(p, b.text(), HEADING_SIZE, true);
                } else if (block instanceof Block.Subheading b) {
                    XWPFParagraph p = paragraph(doc, 80, 40);
                    keepWithNext(p);
                    XWPFRun first = run(p, b.text(), BODY_SIZE, true);
                    String detail = RenderText.clean(b.detail());
                    if (!detail.isEmpty()) {
                        first.addBreak();
                        run(p, detail, BODY_SIZE, false);
                    }
                } else if (block instanceof Block.Paragraph b) {
                    XWPFParagraph p = paragraph(doc, 0, 80);
                    run(p, b.text(), BODY_SIZE, false);
                } else if (block instanceof Block.BulletItem b) {
                    if (bulletNumId == null) {
                        bulletNumId = bulletNumbering(doc);
                    }
                    XWPFParagraph p = paragraph(doc, 0, 40);
                    p.setNumID(bulletNumId);
                    p.setNumILvl(BigInteger.ZERO);
                    run(p, b.text(), BODY_SIZE, false);
                } else if (block instanceof Block.Spacer) {
                    paragraph(doc, 0, 0);
                } else if (block instanceof Block.Footer b) {
                    footers.add(b.text());
                }
            }
            if (draft) {
                footers.add(Block.Footer.DRAFT_TEXT);
            }
            if (!footers.isEmpty()) {
                XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
                for (String text : footers) {
                    XWPFParagraph p = footer.createParagraph();
                    p.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun r = run(p, text, FOOTER_SIZE, false);
                    r.setItalic(true);
                    r.setColor("666666");
                }
            }
            properties(doc, meta);
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the DOCX", e);
        }
    }

    private static void pageSetup(XWPFDocument doc) {
        CTBody body = doc.getDocument().getBody();
        CTSectPr sect = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        size.setW(PAGE_WIDTH);
        size.setH(PAGE_HEIGHT);
        CTPageMar margins = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        margins.setTop(MARGIN);
        margins.setBottom(MARGIN);
        margins.setLeft(MARGIN);
        margins.setRight(MARGIN);
        margins.setHeader(HEADER_FOOTER_DISTANCE);
        margins.setFooter(HEADER_FOOTER_DISTANCE);
        margins.setGutter(BigInteger.ZERO);
    }

    /** One abstract bullet definition (level 0, "•", hanging indent) and its num id. */
    private static BigInteger bulletNumbering(XWPFDocument doc) {
        CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
        abstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = abstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewStart().setVal(BigInteger.ONE);
        level.addNewNumFmt().setVal(STNumberFormat.BULLET);
        level.addNewLvlText().setVal("•");
        CTInd indent = level.addNewPPr().addNewInd();
        indent.setLeft(BULLET_INDENT);
        indent.setHanging(BULLET_INDENT);
        CTFonts fonts = level.addNewRPr().addNewRFonts();
        fonts.setAscii(FONT);
        fonts.setHAnsi(FONT);
        fonts.setCs(FONT);

        XWPFNumbering numbering = doc.createNumbering();
        BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(abstractNum, numbering));
        return numbering.addNum(abstractId);
    }

    private static XWPFParagraph paragraph(XWPFDocument doc, int spacingBefore, int spacingAfter) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(spacingBefore);
        p.setSpacingAfter(spacingAfter);
        return p;
    }

    private static XWPFRun run(XWPFParagraph p, String text, double size, boolean bold) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(size);
        r.setBold(bold);
        r.setText(RenderText.clean(text));
        return r;
    }

    private static void keepWithNext(XWPFParagraph p) {
        CTP ctp = p.getCTP();
        CTPPr ppr = ctp.isSetPPr() ? ctp.getPPr() : ctp.addNewPPr();
        if (!ppr.isSetKeepNext()) {
            ppr.addNewKeepNext();
        }
    }

    private static void properties(XWPFDocument doc, DocumentMeta meta) {
        POIXMLProperties.CoreProperties core = doc.getProperties().getCoreProperties();
        core.setTitle(RenderText.clean(meta.title()));
        core.setCreator(RenderText.clean(meta.author()));
        core.setCreated(Optional.of(Date.from(meta.created())));
    }
}
