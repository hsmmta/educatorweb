package org.example.educatorweb.resourcegen.infrastructure;

import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Component
public class PptxBuilder {

    private static final Logger log = LoggerFactory.getLogger(PptxBuilder.class);

    public record SlideData(String title, List<String> bullets, String notes) {}

    // 16:9 in points (Apache POI legacy setPageSize API expects points, not EMU)
    private static final int W = 960;   // 13.333" × 72
    private static final int H = 540;   // 7.5" × 72

    private static final Color CLR_PRIMARY   = new Color(0x1E3A8A);  // deep navy
    private static final Color CLR_ACCENT    = new Color(0x3B82F6);  // bright blue
    private static final Color CLR_GOLD      = new Color(0xF59E0B);  // amber gold
    private static final Color CLR_BG        = Color.WHITE;
    private static final Color CLR_CARD_BG   = new Color(0xF0F7FF);  // ice blue
    private static final Color CLR_CARD2_BG  = new Color(0xF5F3FF);  // lavender tint (alt)
    private static final Color CLR_TEXT      = new Color(0x1E293B);  // slate-800
    private static final Color CLR_MUTED     = new Color(0x64748B);  // slate-500
    private static final Color CLR_LIGHT_BAR = new Color(0xDBEAFE);  // blue-100 (deco)

    // Accent bar colors for visual variety across slides
    private static final Color[] ACCENT_BARS = {
        new Color(0x3B82F6),  // blue
        new Color(0x8B5CF6),  // violet
        new Color(0x06B6D4),  // cyan
        new Color(0x10B981),  // emerald
        new Color(0xF59E0B),  // amber
        new Color(0xEF4444),  // red
    };

    public byte[] buildPresentation(String topicTitle, List<SlideData> slides) {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new java.awt.Dimension(W, H));

            createTitleSlide(ppt, topicTitle);

            int totalSlides = slides.size();
            for (int i = 0; i < slides.size(); i++) {
                createContentSlide(ppt, slides.get(i), i + 1, totalSlides);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ppt.write(baos);
            byte[] result = baos.toByteArray();
            log.info("PptxBuilder: generated PPTX with {} content slides ({} bytes)",
                slides.size(), result.length);
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to build PPTX: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════
    //  Title slide
    // ═══════════════════════════════════════════════

    private void createTitleSlide(XMLSlideShow ppt, String topicTitle) {
        XSLFSlide slide = ppt.createSlide();
        setSlideBackground(slide, CLR_BG);

        // ── Top header block (dark bar) ──
        addRect(slide, 0, 0, W, H * 4 / 10, CLR_PRIMARY);

        // ── Gold accent stripe ──
        addRect(slide, W * 3 / 10, H * 4 / 10 - 4, W * 4 / 10, 8, CLR_GOLD);

        // ── Course label ──
        XSLFTextBox labelBox = slide.createTextBox();
        labelBox.setAnchor(new Rectangle(W / 10, H * 7 / 40, W * 8 / 10, H / 10));
        XSLFTextParagraph labelPara = labelBox.addNewTextParagraph();
        labelPara.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun labelRun = labelPara.addNewTextRun();
        labelRun.setText("机器学习 · 课程精讲");
        labelRun.setFontSize(14.0);
        labelRun.setFontColor(new Color(0x93C5FD)); // blue-300
        labelRun.setBold(false);

        // ── Main title ──
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(W / 10, H / 5, W * 8 / 10, H * 2 / 10));
        XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
        titlePara.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun titleRun = titlePara.addNewTextRun();
        titleRun.setText(topicTitle);
        titleRun.setFontSize(40.0);
        titleRun.setBold(true);
        titleRun.setFontColor(Color.WHITE);

        // ── Bottom section (white area) ──
        // Subtitle
        XSLFTextBox subBox = slide.createTextBox();
        subBox.setAnchor(new Rectangle(W / 10, H * 55 / 100, W * 8 / 10, H / 12));
        XSLFTextParagraph subPara = subBox.addNewTextParagraph();
        subPara.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun subRun = subPara.addNewTextRun();
        subRun.setText("个性化 AI 学习平台 · 多模态教学资源");
        subRun.setFontSize(18.0);
        subRun.setFontColor(CLR_MUTED);

        // ── Decorative icon circles ──
        int cx = W / 2;
        int cy = H * 66 / 100;
        addOval(slide, cx - 40, cy - 20, 24, 24, CLR_ACCENT);
        addOval(slide, cx - 10, cy - 20, 24, 24, CLR_GOLD);
        addOval(slide, cx + 20, cy - 20, 24, 24, new Color(0x10B981)); // green

        // ── Footer line ──
        addRect(slide, W * 3 / 10, H * 94 / 100, W * 4 / 10, 2, CLR_LIGHT_BAR);
    }

    // ═══════════════════════════════════════════════
    //  Content slide
    // ═══════════════════════════════════════════════

    private void createContentSlide(XMLSlideShow ppt, SlideData data,
                                    int pageNum, int totalSlides) {
        XSLFSlide slide = ppt.createSlide();
        setSlideBackground(slide, CLR_BG);

        int leftMargin = 80;
        int accentBarW = 6;
        int contentX = leftMargin + accentBarW + 18;

        // Alternating accent bar color per slide
        Color barColor = ACCENT_BARS[(pageNum - 1) % ACCENT_BARS.length];

        // ── Left accent bar ──
        addRect(slide, leftMargin, 0, accentBarW, H, barColor);

        // ── Top separator line ──
        addRect(slide, leftMargin, 44, W - leftMargin * 2, 2, CLR_LIGHT_BAR);

        // ── Slide title ──
        int titleH = H / 10;
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(contentX, 34, W - contentX - 60, titleH));
        XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
        XSLFTextRun titleRun = titlePara.addNewTextRun();
        titleRun.setText(data.title());
        titleRun.setFontSize(30.0);
        titleRun.setBold(true);
        titleRun.setFontColor(CLR_PRIMARY);

        // ── Gold underline under title ──
        addRect(slide, contentX, 34 + titleH + 4, 56, 4, CLR_GOLD);

        // ── Bullet content area ──
        int bulletY = 100;
        int bulletW = W - contentX - 50;

        List<String> bullets = data.bullets();
        if (bullets != null && !bullets.isEmpty()) {
            // Card background (alternating tint for visual variety)
            Color cardColor = (pageNum % 2 == 0) ? CLR_CARD2_BG : CLR_CARD_BG;
            int cardPadding = 20;
            int lineH = 30; // per bullet line height
            int cardH = Math.min(cardPadding * 2 + bullets.size() * lineH, H - bulletY - 50);
            int usedCardH = cardPadding * 2 + bullets.size() * lineH;

            addRect(slide, contentX, bulletY, bulletW, usedCardH, cardColor);

            XSLFTextBox bulletBox = slide.createTextBox();
            bulletBox.setAnchor(new Rectangle(contentX + cardPadding, bulletY + 14,
                bulletW - cardPadding * 2, usedCardH - 28));

            for (String bullet : bullets) {
                XSLFTextParagraph bp = bulletBox.addNewTextParagraph();
                bp.setLeftMargin(22.0);
                bp.setIndent(-22.0);
                bp.setSpaceAfter(8.0);

                // Bullet marker
                XSLFTextRun marker = bp.addNewTextRun();
                marker.setText("●");
                marker.setFontSize(9.0);
                marker.setFontColor(barColor);

                // Bullet text
                XSLFTextRun bText = bp.addNewTextRun();
                bText.setText(" " + bullet);
                bText.setFontSize(17.0);
                bText.setFontColor(CLR_TEXT);
            }
        }

        // ── Speaker notes ──
        if (data.notes() != null && !data.notes().isBlank()) {
            addSpeakerNotes(ppt, slide, data.notes());
        }

        // ── Page number ──
        XSLFTextBox pageBox = slide.createTextBox();
        pageBox.setAnchor(new Rectangle(W - 80, H - 28, 60, 18));
        XSLFTextParagraph pagePara = pageBox.addNewTextParagraph();
        pagePara.setTextAlign(TextParagraph.TextAlign.RIGHT);
        XSLFTextRun pageRun = pagePara.addNewTextRun();
        pageRun.setText(pageNum + " / " + totalSlides);
        pageRun.setFontSize(9.0);
        pageRun.setFontColor(CLR_MUTED);

        // ── Bottom bar ──
        addRect(slide, leftMargin, H - 2, W - leftMargin * 2, 2, CLR_LIGHT_BAR);
    }

    // ═══════════════════════════════════════════════
    //  Speaker notes
    // ═══════════════════════════════════════════════

    private void addSpeakerNotes(XMLSlideShow ppt, XSLFSlide slide, String notesText) {
        try {
            // Check for existing notes slide (most POI versions support this getter)
            var notesMethod = XMLSlideShow.class.getMethod("getNotesSlide", XSLFSlide.class);
            XSLFNotes notesSlide = (XSLFNotes) notesMethod.invoke(ppt, slide);
            if (notesSlide != null) {
                for (XSLFTextShape shape : notesSlide.getPlaceholders()) {
                    shape.clearText();
                    XSLFTextParagraph p = shape.addNewTextParagraph();
                    XSLFTextRun r = p.addNewTextRun();
                    r.setText(notesText);
                    r.setFontSize(12.0);
                    break;
                }
            }
        } catch (Exception e) {
            // Speaker notes not supported or no notes slide exists — non-critical
            log.debug("Speaker notes skipped: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════
    //  Shape helpers
    // ═══════════════════════════════════════════════

    private void addRect(XSLFSlide slide, int x, int y, int w, int h, Color fill) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new Rectangle(x, y, w, h));
        shape.setFillColor(fill);
        shape.setLineWidth(0.0);
    }

    private void addOval(XSLFSlide slide, int x, int y, int w, int h, Color fill) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.ELLIPSE);
        shape.setAnchor(new Rectangle(x, y, w, h));
        shape.setFillColor(fill);
        shape.setLineWidth(0.0);
    }

    private void setSlideBackground(XSLFSlide slide, Color color) {
        if (slide.getFollowMasterBackground() || slide.getXmlObject().getCSld().getBg() == null) {
            try {
                var bg = slide.getXmlObject().getCSld().addNewBg();
                var bgPr = bg.addNewBgPr();
                var solidFill = bgPr.addNewSolidFill();
                var srgbClr = solidFill.addNewSrgbClr();
                srgbClr.setVal(new byte[]{
                    (byte) color.getRed(),
                    (byte) color.getGreen(),
                    (byte) color.getBlue()
                });
            } catch (Exception e) {
                log.debug("Could not set slide background: {}", e.getMessage());
            }
        }
    }
}
