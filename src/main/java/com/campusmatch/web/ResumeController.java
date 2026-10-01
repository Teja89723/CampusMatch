package com.campusmatch.web;

import com.campusmatch.ats.AtsScorer;
import com.campusmatch.ats.ResumeParser;
import com.campusmatch.model.Job;
import com.campusmatch.model.Resume;
import com.campusmatch.model.Profile;
import com.campusmatch.repo.ProfileRepository;
import com.campusmatch.repo.JobRepository;
import com.campusmatch.repo.ResumeRepository;
import com.campusmatch.security.Crypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeController {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_UPLOADS_PER_HOUR = 10;
    private static final int MAX_RESUMES = 20;

    private final ResumeRepository resumes;
    private final ProfileRepository profiles;
    private final JobRepository jobs;
    private final ResumeParser parser;
    private final AtsScorer scorer;
    private final Crypto crypto;
    private final ObjectMapper mapper;
    private final Path baseDir;

    public ResumeController(ResumeRepository resumes, JobRepository jobs, ProfileRepository profiles, ResumeParser parser, AtsScorer scorer,
                            Crypto crypto, ObjectMapper mapper, @Value("${app.storage-dir}") String dir) {
        this.resumes = resumes; this.jobs = jobs; this.profiles = profiles; this.parser = parser; this.scorer = scorer;
        this.crypto = crypto; this.mapper = mapper;
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> upload(@AuthenticationPrincipal Long userId,
                                                      @RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) throw err(HttpStatus.BAD_REQUEST, "The file is empty.");
        if (file.getSize() > MAX_BYTES) throw err(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than 5 MB.");

        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("resume");
        String lower = original.toLowerCase(Locale.ROOT);
        boolean pdf = lower.endsWith(".pdf");
        boolean docx = lower.endsWith(".docx");
        if (!pdf && !docx) throw err(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF and DOCX files are accepted.");

        byte[] bytes = file.getBytes();
        boolean pdfMagic = bytes.length > 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
        boolean zipMagic = bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        if ((pdf && !pdfMagic) || (docx && !zipMagic))
            throw err(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The file content does not match its extension.");

        if (resumes.countByUserIdAndUploadedAtAfter(userId, Instant.now().minus(1, ChronoUnit.HOURS)) >= MAX_UPLOADS_PER_HOUR)
            throw err(HttpStatus.TOO_MANY_REQUESTS, "Upload limit reached (10 per hour). Please try again later.");
        if (resumes.countByUserId(userId) >= MAX_RESUMES)
            throw err(HttpStatus.CONFLICT, "You have reached the limit of " + MAX_RESUMES + " resumes. Delete an old one first.");

        String hash = sha256(bytes);
        Optional<Resume> dup = resumes.findFirstByUserIdAndSha256(userId, hash);
        if (dup.isPresent()) {
            Map<String, Object> m = view(dup.get());
            m.put("duplicate", true);
            return ResponseEntity.ok(m);
        }

        ResumeParser.Parsed parsed = parser.parse(bytes, pdf);       // rejects encrypted / macro / oversized files
        AtsScorer.Result result = scorer.score(parsed);

        Path dir = baseDir.resolve(String.valueOf(userId));
        Files.createDirectories(dir);
        String name = UUID.randomUUID() + ".enc";                     // never use the user's file name on disk
        Files.write(dir.resolve(name), crypto.encrypt(bytes));

        Resume r = new Resume();
        r.userId = userId;
        int detectedYears = detectExperienceYears(parsed.text());
        Profile profile = profiles.findById(userId).orElse(null);
        if (profile != null && (profile.experienceYears == null || profile.experienceYears == 0) && detectedYears > 0) { profile.experienceYears = detectedYears; profiles.save(profile); }
        r.originalName = original.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (r.originalName.length() > 100) r.originalName = r.originalName.substring(0, 100);
        r.storagePath = userId + "/" + name;
        r.sha256 = hash;
        r.sizeBytes = bytes.length;
        r.rubricVersion = result.rubricVersion();
        r.atsTotal = result.total();
        r.atsJson = mapper.writeValueAsString(result);
        r.skills.addAll(result.skills());
        for (Resume other : resumes.findByUserIdOrderByUploadedAtDesc(userId)) {
            if (other.active) { other.active = false; resumes.save(other); }
        }
        r.active = true;
        resumes.save(r);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(r));
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Resume r : resumes.findByUserIdOrderByUploadedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("originalName", r.originalName);
            m.put("uploadedAt", r.uploadedAt);
            m.put("active", r.active);
            m.put("atsTotal", r.atsTotal);
            m.put("rubricVersion", r.rubricVersion);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return view(owned(userId, id));     // always scoped by the userId from the token (no IDOR)
    }

    @PutMapping("/{id}/activate")
    @Transactional
    public Map<String, Object> activate(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        Resume target = owned(userId, id);
        for (Resume r : resumes.findByUserIdOrderByUploadedAtDesc(userId)) { r.active = r.id.equals(target.id); resumes.save(r); }
        return view(target);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) throws IOException {
        Resume r = owned(userId, id);
        Files.deleteIfExists(resolve(r.storagePath));
        resumes.delete(r);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Long userId, @PathVariable Long id) throws IOException {
        Resume r = owned(userId, id);
        byte[] plain = crypto.decrypt(Files.readAllBytes(resolve(r.storagePath)));
        boolean pdf = r.originalName != null && r.originalName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(r.originalName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(plain);
    }

    /** Job-specific keyword analysis, separate from the readiness score. Never suggests skills the student lacks. */
    @PostMapping("/{id}/score-against/{jobId}")
    public Map<String, Object> against(@AuthenticationPrincipal Long userId, @PathVariable Long id, @PathVariable Long jobId) {
        Resume r = owned(userId, id);
        Job j = jobs.findById(jobId).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "Job not found."));
        Set<String> have = new HashSet<>();
        r.skills.forEach(s -> have.add(s.toLowerCase(Locale.ROOT)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", j.id);
        m.put("requiredMatched", j.requiredSkills.stream().filter(s -> have.contains(s.toLowerCase(Locale.ROOT))).toList());
        m.put("requiredMissing", j.requiredSkills.stream().filter(s -> !have.contains(s.toLowerCase(Locale.ROOT))).toList());
        m.put("preferredMatched", j.preferredSkills.stream().filter(s -> have.contains(s.toLowerCase(Locale.ROOT))).toList());
        m.put("preferredMissing", j.preferredSkills.stream().filter(s -> !have.contains(s.toLowerCase(Locale.ROOT))).toList());
        m.put("advice", "Add a missing keyword only if you genuinely have that skill, and show it in a project or experience bullet.");
        return m;
    }

    // ---- helpers ----
    private Resume owned(Long userId, Long id) {
        return resumes.findByIdAndUserId(id, userId).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "Resume not found."));
    }

    private Path resolve(String rel) {
        Path p = baseDir.resolve(rel).normalize();
        if (!p.startsWith(baseDir)) throw err(HttpStatus.BAD_REQUEST, "Invalid path.");
        return p;
    }

    private Map<String, Object> view(Resume r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("originalName", r.originalName);
        m.put("uploadedAt", r.uploadedAt);
        m.put("active", r.active);
        m.put("sizeBytes", r.sizeBytes);
        try {
            JsonNode ats = mapper.readTree(r.atsJson);
            m.put("ats", ats);
        } catch (IOException e) {
            m.put("ats", null);
        }
        return m;
    }

    private static ResponseStatusException err(HttpStatus s, String msg) { return new ResponseStatusException(s, msg); }

    private static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private static int detectExperienceYears(String text) {
        if (text == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:over|more than|around|approximately)?\\s*(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)\\s*(?:of)?\\s*(?:experience|exp)?").matcher(text);
        int best = 0;
        while (m.find()) { try { best = Math.max(best, Integer.parseInt(m.group(1))); } catch (Exception ignored) {} }
        return Math.min(best, 50);
    }

}
