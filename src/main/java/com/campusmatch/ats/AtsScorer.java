package com.campusmatch.ats;

import java.time.Year;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * ATS READINESS score (0-100): how reliably automated systems can read the resume.
 * Fully deterministic and rule based (no AI model, no randomness), so identical input always gives identical output
 * and every point can be traced to evidence. It is NOT "the score a company's ATS will give you".
 */
@Component
public class AtsScorer {
    public static final String VERSION = "2026.1";
    public static final String DISCLAIMER =
        "This score estimates how well automated systems can read and structure your resume. Different employers' "
        + "ATS products behave differently, and no score guarantees an interview.";

    public record Category(String category, int score, int max, List<String> evidence, List<String> fixes) {}
    public record Warning(String code, String severity, String detail) {}
    public record Result(String rubricVersion, int total, int maxTotal, List<Category> breakdown,
                         List<Warning> warnings, List<String> sections, List<String> skills,
                         int words, int pages, String disclaimer) {}

    private final SkillCatalog catalog;

    public AtsScorer(SkillCatalog catalog) { this.catalog = catalog; }

    // ---- patterns -----------------------------------------------------------------------------------------------
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{8,}\\d");
    private static final Pattern LINK = Pattern.compile("(?i)(linkedin\\.com/|github\\.com/|gitlab\\.com/|behance\\.net/|https?://)");
    private static final String MON = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    private static final Pattern RANGE = Pattern.compile(
        "(?i)(?:" + MON + "\\s+|\\d{1,2}/)?((?:19|20)\\d{2})\\s*(?:-|\u2013|\u2014|to)\\s*"
        + "(?:(?:" + MON + "\\s+|\\d{1,2}/)?((?:19|20)\\d{2})|(present|current|ongoing|now))");
    private static final Pattern BULLET = Pattern.compile("^\\s*[\u2022\u25AA\u25CF\u25E6\u25BA\u27A2\u2013\u2014*\\-\u00B7]\\s*(.+)$");
    private static final Pattern INJECTION = Pattern.compile(
        "(?i)(ignore (all |any )?(previous|prior|above) (instructions|prompts)|system prompt|you are (an?|the) (ai|assistant|language model)"
        + "|(rate|score|rank) (this|me|the) (resume|candidate|applicant)[^.]{0,30}(100|highest|top|perfect)|disregard .{0,20}instructions)");
    private static final String RATING_GLYPHS = "\u2605\u2606\u25CF\u25CB\u25D0\u25D1\u25A0\u25A1";

    private static final Map<String, Set<String>> HEADINGS = Map.of(
        "Education", Set.of("education", "academic background", "academics", "qualifications", "academic qualifications"),
        "Experience", Set.of("experience", "work experience", "professional experience", "internships", "internship experience",
                             "employment", "employment history", "work history"),
        "Projects", Set.of("projects", "academic projects", "personal projects", "key projects"),
        "Skills", Set.of("skills", "technical skills", "key skills", "core competencies", "technologies", "tech stack"),
        "Summary", Set.of("summary", "objective", "profile", "career objective", "professional summary", "about me"),
        "Certifications", Set.of("certifications", "certificates", "courses"),
        "Achievements", Set.of("achievements", "awards", "honors", "honours"));

    private static final Set<String> ACTION_VERBS = Set.of(
        "built", "developed", "designed", "implemented", "created", "led", "managed", "optimized", "optimised", "improved",
        "reduced", "increased", "automated", "deployed", "analyzed", "analysed", "collaborated", "delivered", "engineered",
        "integrated", "launched", "migrated", "maintained", "tested", "wrote", "organized", "organised", "mentored",
        "presented", "architected", "configured", "debugged", "established", "coordinated", "researched", "trained",
        "achieved", "secured", "streamlined", "refactored", "resolved", "contributed", "produced", "supported", "initiated",
        "spearheaded", "authored", "documented", "drove", "executed", "owned", "scaled", "simplified", "won", "ranked",
        "published", "taught", "volunteered", "participated", "completed", "applied", "utilized", "leveraged", "assisted",
        "handled", "conducted", "generated", "enhanced", "extended", "modeled", "visualized", "processed", "queried", "shipped");

    // ---- scoring ------------------------------------------------------------------------------------------------
    public Result score(ResumeParser.Parsed doc) {
        List<Warning> warnings = new ArrayList<>();

        // 1. Remove lines that try to instruct an AI/ATS. Scoring has no LLM, so this cannot steer it; we still flag it.
        List<String> lines = new ArrayList<>();
        int injected = 0;
        for (String raw : doc.text().split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (INJECTION.matcher(line).find()) { injected++; continue; }
            lines.add(line);
        }
        if (injected > 0) warnings.add(new Warning("INJECTION_ATTEMPT", "HIGH",
            injected + " line(s) look like instructions aimed at an AI or ATS. They were removed and ignored. "
            + "Scoring uses fixed rules, not an AI model, so it cannot be steered this way."));

        String text = String.join("\n", lines);
        int words = text.isBlank() ? 0 : text.split("\\s+").length;
        int pages = Math.max(1, doc.pages());

        if (doc.hiddenTinyChars() > 0) warnings.add(new Warning("HIDDEN_TEXT", "HIGH",
            doc.hiddenTinyChars() + " characters in a font smaller than 2pt were found. They are invisible to readers, "
            + "were excluded from scoring, and this file is queued for review."));
        if (doc.whiteChars() > 20) warnings.add(new Warning("WHITE_TEXT", "MEDIUM",
            doc.whiteChars() + " characters are rendered in white. This is normal on dark header bars, but white text on a "
            + "white page is hidden text. We can't see the background, so please check."));
        if (doc.pdf() && words == 0) warnings.add(new Warning("OCR_REQUIRED", "HIGH",
            "No readable text was found. This looks like a scanned image. ATS software can't read it either. "
            + "Export your resume as a text-based PDF from Word or Google Docs."));

        // 2. Sections
        Map<String, Integer> sectionAt = detectSections(lines);
        List<String> sections = new ArrayList<>(sectionAt.keySet());

        // 3. Skills and stuffing check
        Map<String, Integer> mentions = catalog.mentions(text);
        List<String> stuffed = new ArrayList<>();
        mentions.forEach((k, v) -> { if (v > 10) stuffed.add(k + " (" + v + "x)"); });
        if (!stuffed.isEmpty()) warnings.add(new Warning("KEYWORD_STUFFING", "MEDIUM",
            "Some skills are repeated unusually often: " + String.join(", ", stuffed)
            + ". Mention each skill where you actually used it."));

        List<Category> cats = new ArrayList<>();
        cats.add(parseability(lines, words, pages));
        cats.add(contact(text));
        cats.add(sectionsCategory(sectionAt));
        cats.add(dates(text));
        cats.add(content(lines, sectionAt, !stuffed.isEmpty()));
        cats.add(formatting(text, lines, doc, words));
        cats.add(length(words, pages));

        int total = cats.stream().mapToInt(Category::score).sum();
        int max = cats.stream().mapToInt(Category::max).sum();
        return new Result(VERSION, total, max, cats, warnings, sections, new ArrayList<>(new TreeSet<>(mentions.keySet())),
                words, pages, DISCLAIMER);
    }

    // ---- categories ---------------------------------------------------------------------------------------------
    private Category parseability(List<String> lines, int words, int pages) {
        List<String> ev = new ArrayList<>(), fix = new ArrayList<>();
        if (words == 0) {
            ev.add("No text could be extracted.");
            fix.add("Use a text-based PDF or DOCX (not a scan or an image).");
            return new Category("Parseability", 0, 25, ev, fix);
        }
        double wpp = (double) words / pages;
        int density = (int) Math.min(15, Math.round(wpp / 10.0));
        ev.add(words + " words extracted across " + pages + " page(s) (" + Math.round(wpp) + " per page).");
        if (density < 15) fix.add("Text density is low. Make sure all content is real text, not images or text boxes.");

        long shortLines = lines.stream().filter(l -> l.split("\\s+").length <= 2).count();
        double ratio = lines.isEmpty() ? 1 : (double) shortLines / lines.size();
        int frag = ratio <= 0.40 ? 10 : ratio <= 0.55 ? 7 : ratio <= 0.70 ? 4 : 1;
        ev.add(Math.round(ratio * 100) + "% of lines are 1-2 words long (high values suggest columns or tables broke the reading order).");
        if (frag < 10) fix.add("Use a single-column layout so text is read top-to-bottom in the right order.");
        return new Category("Parseability", density + frag, 25, ev, fix);
    }

    private Category contact(String text) {
        List<String> ev = new ArrayList<>(), fix = new ArrayList<>();
        int s = 0;
        if (EMAIL.matcher(text).find()) { s += 4; ev.add("Email address found."); } else fix.add("Add a professional email address.");
        boolean phone = false;
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            if (digits.length() >= 10 && digits.length() <= 13) { phone = true; break; }
        }
        if (phone) { s += 3; ev.add("Phone number found."); } else fix.add("Add a phone number.");
        if (LINK.matcher(text).find()) { s += 3; ev.add("Profile or portfolio link found (LinkedIn / GitHub / URL)."); }
        else fix.add("Add a LinkedIn or GitHub link.");
        return new Category("Contact details", s, 10, ev, fix);
    }

    private Category sectionsCategory(Map<String, Integer> found) {
        List<String> ev = new ArrayList<>(), fix = new ArrayList<>();
        int s = 0;
        if (found.containsKey("Education")) { s += 5; } else fix.add("Add an 'Education' section.");
        if (found.containsKey("Experience") || found.containsKey("Projects")) { s += 5; }
        else fix.add("Add an 'Experience', 'Internships' or 'Projects' section.");
        if (found.containsKey("Skills")) { s += 5; } else fix.add("Add a 'Skills' section with standard heading.");
        ev.add(found.isEmpty() ? "No standard section headings detected." : "Detected sections: " + String.join(", ", found.keySet()) + ".");
        if (!found.containsKey("Projects") && !found.containsKey("Experience"))
            fix.add("Entry-level recruiters look for projects or internships.");
        return new Category("Standard sections", s, 15, ev, fix);
    }

    private Category dates(String text) {
        List<String> ev = new ArrayList<>(), fix = new ArrayList<>();
        int year = Year.now().getValue();
        int ranges = 0, invalid = 0;
        Matcher m = RANGE.matcher(text);
        while (m.find()) {
            ranges++;
            int start = Integer.parseInt(m.group(1));
            Integer end = m.group(2) != null ? Integer.valueOf(m.group(2)) : null;   // null = present
            if ((end != null && end < start) || start > year + 1 || (end != null && end > year + 6)) invalid++;
        }
        int s;
        if (ranges == 0) {
            boolean anyYear = Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(text).find();
            s = anyYear ? 2 : 0;
            ev.add("No date ranges (e.g. 'Jun 2024 - Aug 2024') were recognised.");
            fix.add("Write dates as ranges like 'Jun 2024 - Aug 2024' next to each education, internship and project.");
        } else {
            s = Math.min(6, 3 + ranges);
            ev.add(ranges + " date range(s) recognised.");
            if (invalid == 0) { s += 4; ev.add("No impossible or reversed ranges."); }
            else fix.add(invalid + " date range(s) look impossible (end before start, or far in the future). Please check them.");
        }
        return new Category("Dates and structure", s, 10, ev, fix);
    }

    private Category content(List<String> lines, Map<String, Integer> sectionAt, boolean stuffing) {
        List<String> ev = new ArrayList<>(), fix = new ArrayList<>();
        List<String> bullets = new ArrayList<>();
        String current = "";
        for (String line : lines) {
            String head = headingOf(line);
            if (head != null) current = head;
            Matcher b = BULLET.matcher(line);
            boolean marked = b.matches();
            String body = marked ? b.group(1).strip() : line;
            int w = body.split("\\s+").length;
            boolean inWork = current.equals("Experience") || current.equals("Projects");
            if (marked && w >= 4) bullets.add(body);
            else if (!marked && inWork && head == null && w >= 6 && !RANGE.matcher(line).find()) bullets.add(body);
        }
        int verbs = 0, metrics = 0;
        for (String b : bullets) {
            String first = b.split("\\s+")[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (ACTION_VERBS.contains(first)) verbs++;
            if (b.matches(".*(\\d|%).*")) metrics++;
        }
        int s = 0;
        if (bullets.isEmpty()) {
            ev.add("No achievement bullets found.");
            fix.add("Describe projects and internships as bullets, e.g. 'Built X using Y, which did Z'.");
        } else {
            double vShare = (double) verbs / bullets.size();
            double mShare = (double) metrics / bullets.size();
            int vPts = (int) Math.round(10 * vShare);
            if (bullets.size() < 3) vPts = Math.min(vPts, 4);
            int mPts = (int) Math.round(10 * Math.min(1.0, mShare / 0.4));
            s = vPts + mPts;
            ev.add(verbs + " of " + bullets.size() + " bullets start with an action verb.");
            ev.add(metrics + " of " + bullets.size() + " bullets include a number or percentage.");
            if (bullets.size() < 3) fix.add("Write at least 3 bullets so your impact is clear.");
            if (vShare < 0.8) fix.add("Start bullets with action verbs (Built, Reduced, Led, Automated...).");
            if (mShare < 0.4) fix.add("Add measurable results (users, % improvement, time saved) where you truthfully can.");
        }
        if (stuffing) { s = Math.max(0, s - 4); ev.add("-4 for repeated keywords (see warning)."); }
        return new Category("Content quality", s, 20, ev, fix);
    }

    private Category formatting(String text, List<String> lines, ResumeParser.Parsed doc, int words) {
        List<String> ev = new ArrayList<>(), fix = new ArrayList<>();
        if (words == 0) return new Category("Formatting safety", 0, 10, List.of("No text to assess."), List.of());
        int s = 10;
        long rating = text.chars().filter(c -> RATING_GLYPHS.indexOf(c) >= 0).count();
        if (rating >= 8) { s -= 4; fix.add("Replace star or dot skill ratings with plain text. ATS can't interpret them."); }
        else ev.add("No symbol-based skill ratings.");
        long junk = text.chars().filter(c -> c == 0xFFFD || Character.getType(c) == Character.PRIVATE_USE).count();
        if (text.length() > 0 && junk * 200 > text.length()) { s -= 3; fix.add("Icon fonts or bad encoding produced unreadable characters. Use plain text for contact details."); }
        else ev.add("Character encoding looks clean.");
        double avg = lines.isEmpty() ? 0 : (double) text.length() / lines.size();
        if (avg > 140) { s -= 2; fix.add("Lines are very long, which can mean merged columns or dense paragraphs."); }
        if (doc.tables() > 0) { s -= 2; fix.add("Tables were used for layout. Some ATS read table cells out of order."); }
        else ev.add("No layout tables detected.");
        if (doc.hiddenTinyChars() > 0) { s -= 3; fix.add("Remove text smaller than 2pt."); }
        return new Category("Formatting safety", Math.max(0, s), 10, ev, fix);
    }

    private Category length(int words, int pages) {
        List<String> ev = new ArrayList<>(), fix = new ArrayList<>();
        if (words == 0) return new Category("Length", 0, 10, List.of("No text to assess."), List.of());
        int p = words < 60 ? 0 : pages <= 2 ? 5 : pages == 3 ? 3 : 1;
        int w = (words >= 250 && words <= 900) ? 5 : (words >= 150 && words <= 1200) ? 3 : words >= 60 ? 1 : 0;
        ev.add(pages + " page(s), " + words + " words.");
        if (pages > 2) fix.add("Students are best served by 1-2 pages.");
        if (words < 250) fix.add("The resume is thin. Add projects, coursework or internship detail.");
        if (words > 900) fix.add("The resume is long. Trim older or less relevant content.");
        return new Category("Length", p + w, 10, ev, fix);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------
    /** Returns the canonical section name if the line is a known heading ("Skills", or "Skills: Java, SQL"). */
    static String headingOf(String line) {
        String head = line.split(":", 2)[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").strip();
        if (head.isEmpty() || head.length() > 40) return null;
        for (Map.Entry<String, Set<String>> e : HEADINGS.entrySet())
            if (e.getValue().contains(head)) return e.getKey();
        return null;
    }

    private Map<String, Integer> detectSections(List<String> lines) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String h = headingOf(lines.get(i));
            if (h != null) found.putIfAbsent(h, i);
        }
        return found;
    }
}
