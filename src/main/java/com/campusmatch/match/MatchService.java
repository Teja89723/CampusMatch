package com.campusmatch.match;

import com.campusmatch.model.Job;
import com.campusmatch.model.Profile;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * JOB MATCH score (0-100). Deterministic weighted rubric; every component is returned so the student sees exactly why.
 * Weights: required skills 45, preferred skills 15, role/title 15, seniority/type 10, location/work mode 10, evidence 5.
 * Uses no name, gender, age, photo or college prestige.
 */
@Service
public class MatchService {
    public static final String VERSION = "2026.1";

    public record Component(String component, double points, int max, String detail) {}
    public record Match(int score, List<Component> breakdown, List<String> matchedRequired, List<String> missingRequired,
                        List<String> matchedPreferred, List<String> missingPreferred, boolean lowConfidence) {}

    public Match compute(Profile p, Set<String> resumeSkills, Job j) {
        Set<String> claimed = lower(p.skills);
        Set<String> resume = lower(resumeSkills);
        Set<String> have = new HashSet<>(claimed);
        have.addAll(resume);

        List<String> reqMatched = new ArrayList<>(), reqMissing = new ArrayList<>();
        List<String> prefMatched = new ArrayList<>(), prefMissing = new ArrayList<>();
        split(j.requiredSkills, have, reqMatched, reqMissing);
        split(j.preferredSkills, have, prefMatched, prefMissing);

        List<Component> parts = new ArrayList<>();

        double reqPts = j.requiredSkills.isEmpty() ? 45 : 45.0 * reqMatched.size() / j.requiredSkills.size();
        parts.add(new Component("Required skills", round(reqPts), 45,
                j.requiredSkills.isEmpty() ? "No required skills listed." : reqMatched.size() + " of " + j.requiredSkills.size() + " covered."));

        double prefPts = j.preferredSkills.isEmpty() ? 15 : 15.0 * prefMatched.size() / j.preferredSkills.size();
        parts.add(new Component("Preferred skills", round(prefPts), 15,
                j.preferredSkills.isEmpty() ? "No preferred skills listed." : prefMatched.size() + " of " + j.preferredSkills.size() + " covered."));

        parts.add(roleFit(p, j));

        double lvl = switch (j.seniority) { case "INTERN", "ENTRY" -> 10; case "MID" -> 4; default -> 0; };
        String lvlDetail = "Seniority: " + j.seniority.toLowerCase(Locale.ROOT) + ".";
        if (!"ANY".equals(p.jobType) && p.jobType != null && !p.jobType.equals(j.jobType)) {
            lvl = lvl / 2;
            lvlDetail += " Job type (" + j.jobType.toLowerCase(Locale.ROOT) + ") differs from your preference.";
        }
        parts.add(new Component("Seniority and type", round(lvl), 10, lvlDetail));

        parts.add(locationFit(p, j));

        // Evidence: skills backed by BOTH the profile and the resume count fully, one source counts half.
        List<String> matchedAll = new ArrayList<>(reqMatched);
        matchedAll.addAll(prefMatched);
        double ev = 0;
        for (String s : matchedAll) ev += (claimed.contains(s.toLowerCase(Locale.ROOT)) && resume.contains(s.toLowerCase(Locale.ROOT))) ? 1 : 0.5;
        double evPts = matchedAll.isEmpty() ? 0 : 5.0 * ev / matchedAll.size();
        parts.add(new Component("Evidence strength", round(evPts), 5,
                matchedAll.isEmpty() ? "No matched skills to verify." : "Skills listed in both your profile and your resume count fully."));

        long total = Math.round(parts.stream().mapToDouble(Component::points).sum());
        boolean low = resume.isEmpty() || claimed.isEmpty();
        return new Match((int) total, parts, reqMatched, reqMissing, prefMatched, prefMissing, low);
    }

    private Component roleFit(Profile p, Job j) {
        if (p.desiredRoles == null || p.desiredRoles.isBlank())
            return new Component("Role fit", 7.5, 15, "No desired roles set in your profile (neutral score).");
        Set<String> title = tokens(j.title);
        double best = 0;
        for (String phrase : p.desiredRoles.split(",")) {
            Set<String> t = tokens(phrase);
            if (t.isEmpty()) continue;
            long hit = t.stream().filter(title::contains).count();
            best = Math.max(best, (double) hit / t.size());
        }
        return new Component("Role fit", round(15 * best), 15, "Overlap between your desired roles and the job title.");
    }

    private Component locationFit(Profile p, Job j) {
        double pts = 0;
        List<String> notes = new ArrayList<>();
        String wm = p.workMode == null ? "ANY" : p.workMode;
        if ("ANY".equals(wm) || wm.equals(j.workMode)) { pts += 5; notes.add("work mode fits"); }
        else if ("REMOTE".equals(j.workMode)) { pts += 2; notes.add("job is remote"); }
        else notes.add("work mode differs");
        if ("REMOTE".equals(j.workMode)) { pts += 5; }
        else if (p.location == null || p.location.isBlank()) { pts += 3; notes.add("no location set"); }
        else if (j.location != null && j.location.toLowerCase(Locale.ROOT).contains(p.location.toLowerCase(Locale.ROOT).split(",")[0].strip())) { pts += 5; notes.add("location matches"); }
        else notes.add("different location");
        return new Component("Location and work mode", pts, 10, String.join(", ", notes) + ".");
    }

    private static void split(Set<String> wanted, Set<String> have, List<String> matched, List<String> missing) {
        for (String s : wanted) (have.contains(s.toLowerCase(Locale.ROOT)) ? matched : missing).add(s);
    }

    private static Set<String> lower(Collection<String> in) {
        return in == null ? new HashSet<>() : in.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static Set<String> tokens(String s) {
        Set<String> stop = Set.of("and", "the", "for", "with", "intern", "junior", "associate", "trainee");
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+"))
                .filter(t -> t.length() >= 3 && !stop.contains(t)).collect(Collectors.toSet());
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
