package com.campusmatch.web;

import com.campusmatch.ats.SkillCatalog;
import com.campusmatch.model.Profile;
import com.campusmatch.model.User;
import com.campusmatch.repo.ProfileRepository;
import com.campusmatch.repo.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/profile")
public class ProfileController {

    public record ProfileRequest(
        @Size(max = 100) String fullName,
        @Pattern(regexp = "^$|^[0-9+()\\-\\s]{7,20}$", message = "Enter a valid phone number") String phone,
        @Size(max = 100) String location,
        @Size(max = 150) String institution,
        @Size(max = 100) String degree,
        @Size(max = 100) String fieldOfStudy,
        @Min(value = 1990, message = "Graduation year looks wrong") @Max(value = 2100, message = "Graduation year looks wrong") Integer gradYear,
        @Min(value = 0, message = "Experience cannot be negative") @Max(value = 50, message = "Experience looks wrong") Integer experienceYears,
        @Size(max = 1000) String summary,
        @Size(max = 3000) String experience,
        @Size(max = 200) String desiredRoles,
        @Pattern(regexp = "ANY|REMOTE|HYBRID|ONSITE", message = "Invalid work mode") String workMode,
        @Pattern(regexp = "ANY|INTERNSHIP|FULLTIME|PARTTIME|CONTRACT|TEMPORARY", message = "Invalid job type") String jobType,
        @Size(max = 50, message = "Up to 50 skills") List<@NotBlank @Size(max = 40, message = "Skill names must be 40 characters or fewer") String> skills) {}

    private final ProfileRepository profiles;
    private final UserRepository users;
    private final SkillCatalog catalog;

    public ProfileController(ProfileRepository profiles, UserRepository users, SkillCatalog catalog) {
        this.profiles = profiles; this.users = users; this.catalog = catalog;
    }

    @GetMapping
    public Map<String, Object> get(@AuthenticationPrincipal Long userId) {
        return view(users.findById(userId).orElseThrow(), profiles.findById(userId).orElseGet(() -> blank(userId)));
    }

    @PutMapping
    @Transactional
    public Map<String, Object> update(@AuthenticationPrincipal Long userId, @Valid @RequestBody ProfileRequest r) {
        User u = users.findById(userId).orElseThrow();
        Profile p = profiles.findById(userId).orElseGet(() -> blank(userId));
        if (r.fullName() != null && !r.fullName().isBlank()) u.fullName = r.fullName().strip();
        if (r.phone() != null && !r.phone().isBlank() && p.phone != null && !p.phone.equals(r.phone().replaceAll("[()\s-]", "")) && u.phoneVerified)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Your verified phone number cannot be changed from the profile page.");
        p.phone = r.phone() == null ? p.phone : r.phone().replaceAll("[()\s-]", "");
        p.location = blankToNull(r.location());
        p.institution = blankToNull(r.institution());
        p.degree = blankToNull(r.degree());
        p.fieldOfStudy = blankToNull(r.fieldOfStudy());
        p.gradYear = r.gradYear();
        p.experienceYears = r.experienceYears() == null ? 0 : r.experienceYears();
        p.summary = blankToNull(r.summary());
        p.experience = blankToNull(r.experience());
        p.desiredRoles = blankToNull(r.desiredRoles());
        p.workMode = r.workMode() == null ? "ANY" : r.workMode();
        p.jobType = r.jobType() == null ? "ANY" : r.jobType();
        p.skills.clear();
        if (r.skills() != null) for (String s : r.skills()) p.skills.add(catalog.normalize(s));
        users.save(u);
        profiles.save(p);
        return view(u, p);
    }

    static Profile blank(Long userId) { Profile p = new Profile(); p.userId = userId; return p; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    /** Completeness drives the "low confidence" badge on recommendations. */
    public static Map<String, Object> completeness(Profile p) {
        List<String> missing = new ArrayList<>();
        if (p.institution == null) missing.add("institution");
        if (p.degree == null) missing.add("degree");
        if (p.fieldOfStudy == null) missing.add("field of study");
        if (p.gradYear == null) missing.add("graduation year");
        if (p.skills.size() < 3) missing.add("at least 3 skills");
        if (p.desiredRoles == null) missing.add("desired roles");
        if (p.location == null) missing.add("location");
        int total = 7;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("percent", (int) Math.round(100.0 * (total - missing.size()) / total));
        m.put("missing", missing);
        return m;
    }

    private Map<String, Object> view(User u, Profile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.email);
        m.put("fullName", u.fullName);
        m.put("phone", p.phone);
        m.put("location", p.location);
        m.put("institution", p.institution);
        m.put("degree", p.degree);
        m.put("fieldOfStudy", p.fieldOfStudy);
        m.put("gradYear", p.gradYear);
        m.put("experienceYears", p.experienceYears == null ? 0 : p.experienceYears);
        m.put("summary", p.summary);
        m.put("experience", p.experience);
        m.put("desiredRoles", p.desiredRoles);
        m.put("workMode", p.workMode);
        m.put("jobType", p.jobType);
        m.put("skills", new ArrayList<>(p.skills));
        m.put("completeness", completeness(p));
        return m;
    }
}
