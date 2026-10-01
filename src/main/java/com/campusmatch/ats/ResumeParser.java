package com.campusmatch.ats;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Extracts visible text and layout signals from PDF / DOCX. Never executes content. */
@Component
public class ResumeParser {
    public static final int MAX_PAGES = 10;

    public record Parsed(String text, int pages, int hiddenTinyChars, int whiteChars, int tables, boolean pdf) {}

    public Parsed parse(byte[] bytes, boolean pdf) {
        try {
            return pdf ? parsePdf(bytes) : parseDocx(bytes);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "We couldn't read this file. It may be corrupted or in an unsupported format.");
        }
    }

    private static ResponseStatusException reject(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private Parsed parsePdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) throw reject("Password-protected PDFs can't be analysed. Export an unprotected copy.");
            if (doc.getNumberOfPages() > MAX_PAGES) throw reject("Resumes longer than " + MAX_PAGES + " pages are not accepted.");
            GuardedStripper s = new GuardedStripper();
            String text = s.getText(doc);
            return new Parsed(text, doc.getNumberOfPages(), s.tiny, s.white, 0, true);
        } catch (InvalidPasswordException e) {
            throw reject("Password-protected PDFs can't be analysed. Export an unprotected copy.");
        }
    }

    /** Drops text smaller than 2pt (a classic keyword-stuffing trick) and counts white text for a warning. */
    static class GuardedStripper extends PDFTextStripper {
        int tiny = 0;
        int white = 0;

        GuardedStripper() throws IOException { super(); }

        @Override
        protected void processTextPosition(TextPosition t) {
            String u = t.getUnicode();
            if (u == null || u.isBlank()) { super.processTextPosition(t); return; }
            if (t.getFontSizeInPt() < 2f) { tiny++; return; }          // excluded from scoring
            try {
                PDColor c = getGraphicsState().getNonStrokingColor();
                if (c != null && (c.toRGB() & 0xFFFFFF) == 0xFFFFFF) white++;   // kept, but flagged
            } catch (IOException | RuntimeException ignored) { }
            super.processTextPosition(t);
        }
    }

    private Parsed parseDocx(byte[] bytes) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            int n = 0;
            while ((e = z.getNextEntry()) != null) {
                if (++n > 2000) throw reject("This document has an unusual structure and was rejected.");
                if (e.getName().toLowerCase().contains("vbaproject")) throw reject("Documents containing macros are rejected.");
            }
        }
        StringBuilder sb = new StringBuilder();
        int tables = 0;
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement el : d.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    sb.append(p.getText()).append('\n');
                } else if (el instanceof XWPFTable t) {
                    tables++;
                    for (XWPFTableRow r : t.getRows())
                        for (XWPFTableCell c : r.getTableCells()) sb.append(c.getText()).append('\n');
                }
            }
        }
        String text = sb.toString();
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        int pages = Math.max(1, (int) Math.ceil(words / 500.0));      // DOCX has no fixed pagination: estimate
        if (pages > MAX_PAGES) throw reject("Resumes longer than " + MAX_PAGES + " pages are not accepted.");
        return new Parsed(text, pages, 0, 0, tables, false);
    }
}
