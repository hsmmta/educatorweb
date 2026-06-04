package org.example.educatorweb.resourcegen.infrastructure;

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

    // 16:9 aspect ratio in EMU (English Metric Units)
    // 13.333" x 7.5" = 12192000 x 6858000 EMU (1 inch = 914400 EMU)
    private static final int SLIDE_WIDTH = 12192000;
    private static final int SLIDE_HEIGHT = 6858000;

    private static final Color TITLE_COLOR = new Color(0x1A56DB);    // Blue
    private static final Color BULLET_COLOR = new Color(0x4B5563);   // Gray
    private static final Color SUBTITLE_COLOR = new Color(0x6B7280); // Light gray
    private static final Color NOTES_COLOR = new Color(0x9CA3AF);    // Lighter gray
    private static final Color BG_COLOR = new Color(0xF9FAFB);       // Very light background

    public byte[] buildPresentation(String topicTitle, List<SlideData> slides) {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new java.awt.Dimension(SLIDE_WIDTH, SLIDE_HEIGHT));

            // Title slide
            createTitleSlide(ppt, topicTitle);

            // Content slides
            for (SlideData slide : slides) {
                createContentSlide(ppt, slide);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ppt.write(baos);
            byte[] result = baos.toByteArray();
            log.info("PptxBuilder: generated PPTX with {} slides ({} bytes)",
                1 + slides.size(), result.length);
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to build PPTX: " + e.getMessage(), e);
        }
    }

    private void createTitleSlide(XMLSlideShow ppt, String topicTitle) {
        XSLFSlide slide = ppt.createSlide();

        // Background color
        setSlideBackground(slide, BG_COLOR);

        // Title text box — centered, large font
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(
            SLIDE_WIDTH / 10,
            SLIDE_HEIGHT * 2 / 7,
            SLIDE_WIDTH * 8 / 10,
            SLIDE_HEIGHT * 2 / 7));
        XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
        titlePara.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun titleRun = titlePara.addNewTextRun();
        titleRun.setText(topicTitle);
        titleRun.setFontSize(44.0);
        titleRun.setBold(true);
        titleRun.setFontColor(TITLE_COLOR);

        // Subtitle text box
        XSLFTextBox subBox = slide.createTextBox();
        subBox.setAnchor(new Rectangle(
            SLIDE_WIDTH / 10,
            SLIDE_HEIGHT * 3 / 5,
            SLIDE_WIDTH * 8 / 10,
            SLIDE_HEIGHT / 8));
        XSLFTextParagraph subPara = subBox.addNewTextParagraph();
        subPara.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun subRun = subPara.addNewTextRun();
        subRun.setText("教学内容精讲");
        subRun.setFontSize(24.0);
        subRun.setFontColor(SUBTITLE_COLOR);

        // Decorative line
        XSLFTextBox lineBox = slide.createTextBox();
        lineBox.setAnchor(new Rectangle(
            SLIDE_WIDTH / 4,
            SLIDE_HEIGHT * 7 / 10,
            SLIDE_WIDTH / 2,
            SLIDE_HEIGHT / 30));
        XSLFTextParagraph linePara = lineBox.addNewTextParagraph();
        linePara.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun lineRun = linePara.addNewTextRun();
        lineRun.setText("━━━━━━━━━━━━━━━━━━━━━━━━━━");
        lineRun.setFontSize(12.0);
        lineRun.setFontColor(SUBTITLE_COLOR);
    }

    private void createContentSlide(XMLSlideShow ppt, SlideData slideData) {
        XSLFSlide slide = ppt.createSlide();

        setSlideBackground(slide, BG_COLOR);

        // Slide title — top area, large blue text
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(
            SLIDE_WIDTH / 12,
            SLIDE_HEIGHT / 12,
            SLIDE_WIDTH * 10 / 12,
            SLIDE_HEIGHT / 8));
        XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
        XSLFTextRun titleRun = titlePara.addNewTextRun();
        titleRun.setText(slideData.title());
        titleRun.setFontSize(32.0);
        titleRun.setBold(true);
        titleRun.setFontColor(TITLE_COLOR);

        // Thin separator line under title
        XSLFTextBox sepBox = slide.createTextBox();
        sepBox.setAnchor(new Rectangle(
            SLIDE_WIDTH / 12,
            SLIDE_HEIGHT * 7 / 40,
            SLIDE_WIDTH * 10 / 12,
            SLIDE_HEIGHT / 40));
        XSLFTextParagraph sepPara = sepBox.addNewTextParagraph();
        XSLFTextRun sepRun = sepPara.addNewTextRun();
        sepRun.setText("────────────────────────────");
        sepRun.setFontSize(10.0);
        sepRun.setFontColor(SUBTITLE_COLOR);

        // Bullet points — main content area
        XSLFTextBox bulletBox = slide.createTextBox();
        bulletBox.setAnchor(new Rectangle(
            SLIDE_WIDTH / 8,
            SLIDE_HEIGHT * 9 / 40,
            SLIDE_WIDTH * 6 / 8,
            SLIDE_HEIGHT * 13 / 20));

        List<String> bullets = slideData.bullets();
        if (bullets != null && !bullets.isEmpty()) {
            for (String bullet : bullets) {
                XSLFTextParagraph bulletPara = bulletBox.addNewTextParagraph();
                bulletPara.setLeftMargin(28.0);
                bulletPara.setIndent(-28.0);
                XSLFTextRun bulletRun = bulletPara.addNewTextRun();
                bulletRun.setText("•  " + bullet);
                bulletRun.setFontSize(20.0);
                bulletRun.setFontColor(BULLET_COLOR);
            }
        }

        // Notes — small italic text at bottom of slide
        if (slideData.notes() != null && !slideData.notes().isBlank()) {
            XSLFTextBox notesBox = slide.createTextBox();
            notesBox.setAnchor(new Rectangle(
                SLIDE_WIDTH / 12,
                SLIDE_HEIGHT * 35 / 40,
                SLIDE_WIDTH * 10 / 12,
                SLIDE_HEIGHT / 20));
            XSLFTextParagraph notesPara = notesBox.addNewTextParagraph();
            notesPara.setTextAlign(TextParagraph.TextAlign.LEFT);
            XSLFTextRun notesRun = notesPara.addNewTextRun();
            notesRun.setText("📝 " + slideData.notes());
            notesRun.setFontSize(10.0);
            notesRun.setItalic(true);
            notesRun.setFontColor(NOTES_COLOR);
        }
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
