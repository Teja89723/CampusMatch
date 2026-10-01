package com.campusmatch.web;

import com.campusmatch.ats.SkillCatalog;
import com.campusmatch.config.PublicAtsImporter;
import com.campusmatch.match.MatchService;
import com.campusmatch.model.*;
import com.campusmatch.repo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    public record JobRequest(
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 100) String company,
        @NotBlank @Size(max = 100) String companyDomain,
        @NotBlank @Size(max = 100) String location,
        @Pattern(regexp = "REMOTE|HYBRID|ONSITE") String workMode,
        @Pattern(regexp = "INTERNSHIP|FULLTIME|PARTTIME|CONTRACT|TEMPORARY") String jobType,
        @Pattern(regexp = "INTERN|ENTRY|MID|SENIOR") String seniority,
        @NotBlank @Size(max = 5000) String description,
        List<@NotBlank @Size(max = 40) String> requiredSkills,
        List<@NotBlank @Size(max = 40) String> preferredSkills,
        @NotBlank @Size(max = 1000) String applyUrl) {}

    public record StatusRequest(@NotBlank @Pattern(regexp = "CLICKED|APPLIED|INTERVIEW|OFFER|REJECTED", message = "Invalid status") String status) {}

    private final JobRepository jobs;
    private final ProfileRepository profiles;
    private final ResumeRepository resumes;
    private final ApplicationRepository applications;
    private final MatchService matcher;
    private final SkillCatalog catalog;
    private final PublicAtsImporter publicAtsImporter;

    public JobController(JobRepository jobs, ProfileRepository profiles, ResumeRepository resumes,
                         ApplicationRepository applications, MatchService matcher, SkillCatalog catalog, PublicAtsImporter publicAtsImporter) {
        this.jobs = jobs; this.profiles = profiles; this.resumes = resumes;
        this.applications = applications; this.matcher = matcher; this.catalog = catalog; this.publicAtsImporter = publicAtsImporter;
    }

    // ---------- public search ----------
    @GetMapping("/jobs")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "") String location,
                                      @RequestParam(defaultValue = "") String company,
                                      @RequestParam(defaultValue = "") String skill,
                                      @RequestParam(defaultValue = "") String workMode,
                                      @RequestParam(defaultValue = "") String type,
                                      @RequestParam(defaultValue = "0") int days,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "10") int size) {
        size = Math.min(Math.max(size, 1), 50);
        page = Math.max(page, 0);
        days = Math.min(Math.max(days, 0), 365);
        Instant postedAfter = days == 0 ? null : Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        Page<Job> p = jobs.search("%" + q.strip().toLowerCase(Locale.ROOT) + "%",
                "%" + location.strip().toLowerCase(Locale.ROOT) + "%", company.strip().toLowerCase(Locale.ROOT),
                "%" + skill.strip().toLowerCase(Locale.ROOT) + "%", workMode.toUpperCase(Locale.ROOT), type.toUpperCase(Locale.ROOT), postedAfter,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "postedAt")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", p.getContent().stream().map(j -> jobView(j, false)).toList());
        m.put("page", p.getNumber());
        m.put("totalPages", p.getTotalPages());
        m.put("total", p.getTotalElements());
        return m;
    }

    @GetMapping("/jobs/companies")
    public List<String> companies() { return jobs.findActiveCompanies(); }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Job j = jobs.findById(id).filter(x -> x.active).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found."));
        return jobView(j, true);
    }

    // ---------- personalised ----------
    @GetMapping("/me/recommendations")
    public Map<String, Object> recommendations(@AuthenticationPrincipal Long userId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        size = Math.min(Math.max(size, 1), 50);
        Profile p = profiles.findById(userId).orElseGet(() -> ProfileController.blank(userId));
        Set<String> resumeSkills = resumes.findFirstByUserIdAndActiveTrue(userId).map(r -> r.skills).orElse(Set.of());

        // Stage 1: bounded candidate set (newest 500 active jobs). Stage 2: precise in-memory scoring.
        List<Job> candidates = jobs.findByActiveTrue(PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "postedAt")));
        List<Map<String, Object>> scored = new ArrayList<>();
        int years = p.experienceYears == null ? 0 : p.experienceYears;
        for (Job j : candidates) {
            if (years == 0 && !("INTERN".equals(j.seniority) || "ENTRY".equals(j.seniority))) continue;
            if (years > 0 && !("MID".equals(j.seniority) || "SENIOR".equals(j.seniority))) continue;
            MatchService.Match m = matcher.compute(p, resumeSkills, j);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", jobView(j, false));
            row.put("matchScore", m.score());
            row.put("breakdown", m.breakdown());
            row.put("matchedRequired", m.matchedRequired());
            row.put("missingRequired", m.missingRequired());
            row.put("matchedPreferred", m.matchedPreferred());
            row.put("missingPreferred", m.missingPreferred());
            scored.add(row);
        }
        scored.sort(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("matchScore")).reversed());
        int from = Math.min(page * size, scored.size());
        int to = Math.min(from + size, scored.size());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", scored.subList(from, to));
        m.put("total", scored.size());
        m.put("page", page);
        m.put("totalPages", (int) Math.ceil(scored.size() / (double) size));
        m.put("matcherVersion", MatchService.VERSION);
        m.put("hasResume", !resumeSkills.isEmpty());
        m.put("profileCompleteness", ProfileController.completeness(p));
        m.put("lowConfidence", resumeSkills.isEmpty() || p.skills.isEmpty());
        return m;
    }

    // ---------- apply tracking ----------
    /** Records that the student opened the company's portal. The platform never sends resume data to the company. */
    @PostMapping("/jobs/{id}/apply-intent")
    @Transactional
    public Map<String, Object> applyIntent(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        Job j = jobs.findById(id).filter(x -> x.active).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found."));
        if (!ApplyUrlValidator.isAllowed(j.applyUrl, j.companyDomain))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This job's apply link failed verification and was disabled.");
        ApplicationRecord a = applications.findByUserIdAndJobId(userId, id).orElseGet(() -> {
            ApplicationRecord n = new ApplicationRecord();
            n.userId = userId; n.jobId = id;
            return n;
        });
        a.updatedAt = Instant.now();
        applications.save(a);
        return Map.of("applyUrl", j.applyUrl);
    }

    @GetMapping("/me/applications")
    public List<Map<String, Object>> myApplications(@AuthenticationPrincipal Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApplicationRecord a : applications.findByUserIdOrderByUpdatedAtDesc(userId)) {
            Job j = jobs.findById(a.jobId).orElse(null);
            if (j == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", a.jobId);
            m.put("title", j.title);
            m.put("company", j.company);
            m.put("applyUrl", j.applyUrl);
            m.put("status", a.status);
            m.put("clickedAt", a.clickedAt);
            m.put("updatedAt", a.updatedAt);
            out.add(m);
        }
        return out;
    }

    @PatchMapping("/me/applications/{jobId}")
    @Transactional
    public Map<String, Object> setStatus(@AuthenticationPrincipal Long userId, @PathVariable Long jobId,
                                         @Valid @RequestBody StatusRequest r) {
        if (!jobs.existsById(jobId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found.");
        ApplicationRecord a = applications.findByUserIdAndJobId(userId, jobId).orElseGet(() -> {
            ApplicationRecord n = new ApplicationRecord();
            n.userId = userId; n.jobId = jobId;
            return n;
        });
        a.status = r.status();
        a.updatedAt = Instant.now();
        applications.save(a);
        return Map.of("jobId", jobId, "status", a.status);
    }

    // ---------- admin ----------
    @PostMapping("/admin/jobs/sync")
    public Map<String, Object> syncLiveJobs() {
        return publicAtsImporter.sync();
    }

    @PostMapping("/admin/jobs")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody JobRequest r) {
        if (!ApplyUrlValidator.isAllowed(r.applyUrl(), r.companyDomain()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "applyUrl must be HTTPS and on the company's domain (or a known ATS host).");
        Job j = new Job();
        j.title = r.title().strip(); j.company = r.company().strip(); j.companyDomain = r.companyDomain().strip().toLowerCase(Locale.ROOT);
        j.location = r.location().strip();
        j.workMode = r.workMode() == null ? "ONSITE" : r.workMode();
        j.jobType = r.jobType() == null ? "FULLTIME" : r.jobType();
        j.seniority = r.seniority() == null ? "ENTRY" : r.seniority();
        j.description = r.description();
        j.applyUrl = r.applyUrl().strip();
        j.source = "ADMIN";
        if (r.requiredSkills() != null) r.requiredSkills().forEach(s -> j.requiredSkills.add(catalog.normalize(s)));
        if (r.preferredSkills() != null) r.preferredSkills().forEach(s -> j.preferredSkills.add(catalog.normalize(s)));
        jobs.save(j);
        return jobView(j, true);
    }

    private Map<String, Object> jobView(Job j, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.id);
        m.put("title", j.title);
        m.put("company", j.company);
        m.put("location", j.location);
        m.put("workMode", j.workMode);
        m.put("jobType", j.jobType);
        m.put("seniority", j.seniority);
        m.put("postedAt", j.postedAt);
        m.put("source", j.source);
        m.put("live", "LEVER".equals(j.source) || "ASHBY".equals(j.source));
        m.put("requiredSkills", j.requiredSkills);
        m.put("preferredSkills", j.preferredSkills);
        m.put("applyHost", ApplyUrlValidator.hostOf(j.applyUrl));
        m.put("applyUrl", j.applyUrl);
        m.put("description", full ? j.description : abbreviate(j.description, 220));
        return m;
    }

    private static String abbreviate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n).stripTrailing() + "...";
    }
}
