package com.cvtailor.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentService {

    // Helper data structure for inline bold formatting
    public static class TextSegment {
        public final String text;
        public final boolean isBold;

        public TextSegment(String text, boolean isBold) {
            this.text = text;
            this.isBold = isBold;
        }
    }

    // Helper class to manage PDF content streams cleanly without stream leakage
    private static class PdfWriter implements AutoCloseable {
        final PDDocument doc;
        final float margin = 45f;
        final float width;
        final float height;
        PDPage currentPage;
        PDPageContentStream cs;
        float y;

        PdfWriter(PDDocument doc) throws IOException {
            this.doc = doc;
            this.currentPage = new PDPage(PDRectangle.A4);
            this.doc.addPage(currentPage);
            this.width = currentPage.getMediaBox().getWidth() - 2 * margin;
            this.height = currentPage.getMediaBox().getHeight();
            this.y = height - margin;
            this.cs = new PDPageContentStream(doc, currentPage);
        }

        void ensureSpace(float requiredHeight) throws IOException {
            if (y - requiredHeight < margin) {
                if (cs != null) {
                    cs.close();
                }
                currentPage = new PDPage(PDRectangle.A4);
                doc.addPage(currentPage);
                cs = new PDPageContentStream(doc, currentPage);
                y = height - margin;
            }
        }

        @Override
        public void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }
    }

    public String extractText(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";

        if (filename.endsWith(".pdf")) {
            return extractTextFromPdf(file);
        } else if (filename.endsWith(".docx") || filename.endsWith(".doc")) {
            return extractTextFromDocx(file);
        } else {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        }
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractTextFromDocx(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             XWPFDocument doc = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /* ==========================================================================
       PROFESSIONAL PDF GENERATION (PDFBox)
       ========================================================================== */

    public byte[] generatePdf(String content, String title) throws IOException {
        try (PDDocument doc = new PDDocument();
             PdfWriter writer = new PdfWriter(doc)) {

            PDType1Font fontNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            // Document Main Header / Title
            if (title != null && !title.isBlank()) {
                writer.cs.beginText();
                writer.cs.setFont(fontBold, 18);
                writer.cs.setNonStrokingColor(15 / 255f, 23 / 255f, 42 / 255f); // Dark slate
                writer.cs.newLineAtOffset(writer.margin, writer.y);
                writer.cs.showText(cleanText(title));
                writer.cs.endText();

                writer.y -= 24f;

                // Subtle header accent line under title
                writer.cs.setStrokingColor(99 / 255f, 102 / 255f, 241 / 255f); // Indigo accent
                writer.cs.setLineWidth(1.5f);
                writer.cs.moveTo(writer.margin, writer.y);
                writer.cs.lineTo(writer.margin + writer.width, writer.y);
                writer.cs.stroke();

                writer.y -= 18f;
            }

            String[] lines = content != null ? content.split("\r?\n") : new String[0];

            for (String rawLine : lines) {
                if (rawLine == null || rawLine.trim().isEmpty()) {
                    writer.y -= 6f; // Paragraph gap
                    continue;
                }

                String trimmedLine = rawLine.trim();

                // Check for horizontal separator
                if (isSeparator(trimmedLine)) {
                    writer.y -= 6f;
                    writer.ensureSpace(15f);

                    writer.cs.setStrokingColor(226 / 255f, 232 / 255f, 240 / 255f); // Light divider
                    writer.cs.setLineWidth(0.75f);
                    writer.cs.moveTo(writer.margin, writer.y);
                    writer.cs.lineTo(writer.margin + writer.width, writer.y);
                    writer.cs.stroke();
                    writer.y -= 12f;
                    continue;
                }

                // 1. Main Heading (H1)
                if (isMainHeading(trimmedLine)) {
                    writer.y -= 10f;
                    writer.ensureSpace(30f);

                    String cleanHeading = cleanHeadingText(trimmedLine).toUpperCase(Locale.ROOT);

                    writer.cs.beginText();
                    writer.cs.setFont(fontBold, 12.5f);
                    writer.cs.setNonStrokingColor(30 / 255f, 58 / 255f, 138 / 255f); // Deep Navy
                    writer.cs.newLineAtOffset(writer.margin, writer.y);
                    writer.cs.showText(cleanText(cleanHeading));
                    writer.cs.endText();

                    writer.y -= 5f;

                    // Draw line below main heading
                    writer.cs.setStrokingColor(203 / 255f, 213 / 255f, 225 / 255f);
                    writer.cs.setLineWidth(0.75f);
                    writer.cs.moveTo(writer.margin, writer.y);
                    writer.cs.lineTo(writer.margin + writer.width, writer.y);
                    writer.cs.stroke();

                    writer.y -= 14f;
                }
                // 2. Subheading (H2 / H3)
                else if (isSubheading(trimmedLine)) {
                    writer.y -= 6f;
                    String cleanSubheading = cleanHeadingText(trimmedLine);
                    List<String> wrapped = wrapLine(cleanSubheading, fontBold, 10.5f, writer.width);

                    for (String wLine : wrapped) {
                        writer.ensureSpace(16f);

                        writer.cs.beginText();
                        writer.cs.setFont(fontBold, 10.5f);
                        writer.cs.setNonStrokingColor(51 / 255f, 65 / 255f, 85 / 255f); // Dark Slate
                        writer.cs.newLineAtOffset(writer.margin, writer.y);
                        writer.cs.showText(cleanText(wLine));
                        writer.cs.endText();
                        writer.y -= 14f;
                    }
                    writer.y -= 2f;
                }
                // 3. Bullet Point
                else if (isBullet(trimmedLine)) {
                    String bulletContent = cleanBulletText(trimmedLine);
                    float indent = 14f;
                    float bulletWidth = writer.width - indent;

                    List<String> wrappedLines = wrapLine(bulletContent, fontNormal, 9.5f, bulletWidth);

                    for (int i = 0; i < wrappedLines.size(); i++) {
                        writer.ensureSpace(14f);

                        float xPos = writer.margin + indent;

                        // Render bullet icon on first line
                        if (i == 0) {
                            writer.cs.beginText();
                            writer.cs.setFont(fontBold, 9.5f);
                            writer.cs.setNonStrokingColor(30 / 255f, 58 / 255f, 138 / 255f); // Navy Bullet
                            writer.cs.newLineAtOffset(writer.margin + 2f, writer.y);
                            writer.cs.showText("-");
                            writer.cs.endText();
                        }

                        // Render text segments (handling inline **bold**)
                        renderSegmentedLine(writer.cs, wrappedLines.get(i), fontNormal, fontBold, 9.5f, xPos, writer.y);
                        writer.y -= 13f;
                    }
                    writer.y -= 2f;
                }
                // 4. Regular Paragraph Text
                else {
                    List<String> wrappedLines = wrapLine(trimmedLine, fontNormal, 9.5f, writer.width);

                    for (String wLine : wrappedLines) {
                        writer.ensureSpace(14f);

                        renderSegmentedLine(writer.cs, wLine, fontNormal, fontBold, 9.5f, writer.margin, writer.y);
                        writer.y -= 13f;
                    }
                    writer.y -= 2f;
                }
            }

            // Close content stream before saving document
            writer.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private void renderSegmentedLine(PDPageContentStream cs, String line, PDType1Font fontNormal, PDType1Font fontBold, float fontSize, float startX, float y) throws IOException {
        List<TextSegment> segments = parseSegments(line);
        float currentX = startX;

        cs.setNonStrokingColor(31 / 255f, 41 / 255f, 55 / 255f); // Charcoal body text

        for (TextSegment seg : segments) {
            if (seg.text.isEmpty()) continue;

            PDType1Font font = seg.isBold ? fontBold : fontNormal;
            String text = cleanText(seg.text);

            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(currentX, y);
            cs.showText(text);
            cs.endText();

            float textWidth = (font.getStringWidth(text) / 1000f) * fontSize;
            currentX += textWidth;
        }
    }

    /* ==========================================================================
       PROFESSIONAL DOCX GENERATION (Apache POI)
       ========================================================================== */

    public byte[] generateDocx(String content, String title) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            
            // Set 1-inch margins (1440 dxa) safely
            try {
                CTSectPr sectPr = doc.getDocument().getBody().isSetSectPr() ?
                        doc.getDocument().getBody().getSectPr() : doc.getDocument().getBody().addNewSectPr();
                CTPageMar pageMar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
                pageMar.setLeft(1440);
                pageMar.setRight(1440);
                pageMar.setTop(1440);
                pageMar.setBottom(1440);
            } catch (Exception e) {
                // Fallback gracefully if XML Schema binding differs
            }

            // Document Title Header
            if (title != null && !title.isBlank()) {
                XWPFParagraph titleP = doc.createParagraph();
                titleP.setSpacingBefore(100);
                titleP.setSpacingAfter(200);

                XWPFRun titleRun = titleP.createRun();
                titleRun.setText(title);
                titleRun.setBold(true);
                titleRun.setFontSize(18);
                titleRun.setFontFamily("Arial");
                titleRun.setColor("0F172A"); // Dark slate
            }

            String[] lines = content != null ? content.split("\r?\n") : new String[0];

            for (String rawLine : lines) {
                if (rawLine == null || rawLine.trim().isEmpty()) {
                    continue;
                }

                String trimmedLine = rawLine.trim();

                // Separator
                if (isSeparator(trimmedLine)) {
                    XWPFParagraph sepP = doc.createParagraph();
                    sepP.setSpacingBefore(80);
                    sepP.setSpacingAfter(120);

                    XWPFRun sepRun = sepP.createRun();
                    sepRun.setText("_________________________________________________________________________________");
                    sepRun.setColor("CBD5E1");
                    sepRun.setFontSize(8);
                    continue;
                }

                // 1. Main Heading (H1)
                if (isMainHeading(trimmedLine)) {
                    String cleanHeading = cleanHeadingText(trimmedLine).toUpperCase(Locale.ROOT);

                    XWPFParagraph hP = doc.createParagraph();
                    hP.setSpacingBefore(240);
                    hP.setSpacingAfter(80);

                    hP.setBorderBottom(Borders.SINGLE);

                    XWPFRun hRun = hP.createRun();
                    hRun.setText(cleanHeading);
                    hRun.setBold(true);
                    hRun.setFontSize(12);
                    hRun.setFontFamily("Arial");
                    hRun.setColor("1E3A8A"); // Navy Blue
                }
                // 2. Subheading (H2 / H3)
                else if (isSubheading(trimmedLine)) {
                    String cleanSubheading = cleanHeadingText(trimmedLine);

                    XWPFParagraph subP = doc.createParagraph();
                    subP.setSpacingBefore(140);
                    subP.setSpacingAfter(40);

                    XWPFRun subRun = subP.createRun();
                    subRun.setText(cleanSubheading);
                    subRun.setBold(true);
                    subRun.setFontSize(11);
                    subRun.setFontFamily("Arial");
                    subRun.setColor("334155"); // Dark Slate
                }
                // 3. Bullet Point
                else if (isBullet(trimmedLine)) {
                    String bulletContent = cleanBulletText(trimmedLine);

                    XWPFParagraph bulletP = doc.createParagraph();
                    bulletP.setSpacingBefore(20);
                    bulletP.setSpacingAfter(40);
                    bulletP.setIndentationLeft(360); // 0.25 in indent

                    XWPFRun bRun = bulletP.createRun();
                    bRun.setText("• ");
                    bRun.setBold(true);
                    bRun.setFontFamily("Arial");
                    bRun.setFontSize(10.5);
                    bRun.setColor("1E3A8A"); // Navy bullet

                    List<TextSegment> segments = parseSegments(bulletContent);
                    for (TextSegment seg : segments) {
                        XWPFRun r = bulletP.createRun();
                        r.setText(seg.text);
                        r.setBold(seg.isBold);
                        r.setFontSize(10.5);
                        r.setFontFamily("Arial");
                        r.setColor("1F2937");
                    }
                }
                // 4. Regular Paragraph Text
                else {
                    XWPFParagraph bodyP = doc.createParagraph();
                    bodyP.setSpacingBefore(20);
                    bodyP.setSpacingAfter(60);

                    List<TextSegment> segments = parseSegments(trimmedLine);
                    for (TextSegment seg : segments) {
                        XWPFRun r = bodyP.createRun();
                        r.setText(seg.text);
                        r.setBold(seg.isBold);
                        r.setFontSize(10.5);
                        r.setFontFamily("Arial");
                        r.setColor("1F2937");
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        }
    }

    /* ==========================================================================
       PARSING & UTILITY HELPERS
       ========================================================================== */

    private boolean isMainHeading(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.startsWith("# ") || trimmed.startsWith("## ")) return true;
        if (trimmed.startsWith("---") && trimmed.endsWith("---") && trimmed.length() > 6) return true;

        String upper = trimmed.toUpperCase(Locale.ROOT).replaceAll("[:\\-*_#]", "").trim();
        return upper.equals("CONTACT INFORMATION") ||
               upper.equals("CONTACT") ||
               upper.equals("PROFESSIONAL SUMMARY") ||
               upper.equals("SUMMARY") ||
               upper.equals("CORE COMPETENCIES") ||
               upper.equals("KEY SKILLS") ||
               upper.equals("SKILLS") ||
               upper.equals("TECHNICAL SKILLS") ||
               upper.equals("PROFESSIONAL EXPERIENCE") ||
               upper.equals("WORK EXPERIENCE") ||
               upper.equals("EXPERIENCE") ||
               upper.equals("EDUCATION") ||
               upper.equals("PROJECTS") ||
               upper.equals("ACHIEVEMENTS") ||
               upper.equals("CERTIFICATIONS") ||
               upper.equals("LANGUAGES");
    }

    private boolean isSubheading(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.startsWith("### ")) return true;
        if (trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length() > 4 && !trimmed.substring(2, trimmed.length() - 2).contains("**")) return true;
        return false;
    }

    private boolean isBullet(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") || trimmed.startsWith("o ");
    }

    private boolean isSeparator(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___");
    }

    private String cleanHeadingText(String line) {
        if (line == null) return "";
        return line.replaceAll("^[#\\-*_\\s]+", "")
                   .replaceAll("[#\\-*_\\s]+$", "")
                   .replaceAll(":$", "")
                   .trim();
    }

    private String cleanBulletText(String line) {
        if (line == null) return "";
        String trimmed = line.trim();
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") || trimmed.startsWith("o ")) {
            return trimmed.substring(2).trim();
        }
        return trimmed;
    }

    public List<TextSegment> parseSegments(String line) {
        List<TextSegment> segments = new ArrayList<>();
        if (line == null || line.isEmpty()) return segments;

        String[] parts = line.split("\\*\\*");
        boolean isBold = false;
        for (String part : parts) {
            if (!part.isEmpty()) {
                segments.add(new TextSegment(part, isBold));
            }
            isBold = !isBold;
        }
        return segments;
    }

    private List<String> wrapLine(String text, PDType1Font fontNormal, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            lines.add("");
            return lines;
        }

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        StringBuilder currentPlainLine = new StringBuilder();

        for (String word : words) {
            String plainWord = word.replaceAll("\\*\\*", "");
            String candidatePlain = currentPlainLine.length() == 0 ? plainWord : currentPlainLine + " " + plainWord;
            float width = fontNormal.getStringWidth(cleanText(candidatePlain)) / 1000f * fontSize;

            if (width > maxWidth && currentLine.length() > 0) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
                currentPlainLine = new StringBuilder(plainWord);
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                    currentPlainLine.append(" ");
                }
                currentLine.append(word);
                currentPlainLine.append(plainWord);
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }

    private String cleanText(String input) {
        if (input == null) return "";
        return input.replace("\u2022", "-")
                    .replace("\u2013", "-")
                    .replace("\u2014", "--")
                    .replace("\u201C", "\"")
                    .replace("\u201D", "\"")
                    .replace("\u2018", "'")
                    .replace("\u2019", "'")
                    .replace("\t", "    ")
                    .replaceAll("[^\\x00-\\x7F]", " ");
    }
}
