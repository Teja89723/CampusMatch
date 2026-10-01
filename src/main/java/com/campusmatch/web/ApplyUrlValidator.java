package com.campusmatch.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * An apply link is accepted only if it is HTTPS and points at the company's own domain
 * or at a well-known applicant-tracking host. Prevents phishing links in listings.
 */
public final class ApplyUrlValidator {
    private static final List<String> ATS_HOSTS = List.of(
        "boards.greenhouse.io", "job-boards.greenhouse.io", "jobs.lever.co", "jobs.ashbyhq.com",
        "apply.workable.com", "myworkdayjobs.com", "smartrecruiters.com", "jobs.smartrecruiters.com", "jobvetta.com");

    private ApplyUrlValidator() {}

    public static boolean isAllowed(String url, String companyDomain) {
        try {
            URI u = URI.create(url.strip());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) return false;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            if (host.matches("[0-9.]+") || host.contains(":")) return false;          // no raw IP addresses
            String dom = companyDomain == null ? "" : companyDomain.strip().toLowerCase(Locale.ROOT);
            if (!dom.isEmpty() && (host.equals(dom) || host.endsWith("." + dom))) return true;
            return ATS_HOSTS.stream().anyMatch(a -> host.equals(a) || host.endsWith("." + a));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return ""; }
    }
}
