package com.campusmatch.config;

import com.campusmatch.model.Job;
import com.campusmatch.repo.JobRepository;
import com.campusmatch.web.ApplyUrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Imports current public jobs directly from employer-hosted ATS feeds.
 * No aggregator API key is required. Supported public feeds: Lever and Ashby.
 */
@Service
public class PublicAtsImporter {
    private static final Logger log = LoggerFactory.getLogger(PublicAtsImporter.class);
    private static final String LEVER = "LEVER";
    private static final String ASHBY = "ASHBY";
    private static final Pattern HTML = Pattern.compile("<[^>]+>");

    private final JobRepository jobs;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final List<Board> leverBoards;
    private final List<Board> ashbyBoards;
    private final int expireDays;
    private final String indiaLocations;

    public PublicAtsImporter(JobRepository jobs, ObjectMapper mapper,
                             @Value("${app.job-import.expire-days:30}") int expireDays,
                             @Value("${app.job-import.india-locations:Bengaluru,Bangalore,Hyderabad,Chennai,Pune,Mumbai,Delhi,Noida,Gurugram,Gurgaon,India,Remote}") String indiaLocations,
                             @Value("${app.job-import.lever-boards:Stable Money|stable-money1,100ms|100ms,Acceldata|acceldata,Gushwork|gushwork,Saviynt|saviynt,Findem|findem,Weekday|weekdayworks,Level AI|levelai,Kobie Marketing|kobie}") String leverConfig,
                             @Value("${app.job-import.ashby-boards:Overview|overview,Plotline|plotlineso,Ema|ema,Founders Factory|founders-factory,Handshake|handshake,CUBE|CUBE,Runbook|Runbook}") String ashbyConfig) {
        this.jobs = jobs;
        this.mapper = mapper;
        this.client = RestClient.builder().build();
        this.expireDays = Math.max(7, expireDays);
        this.indiaLocations = indiaLocations == null ? "" : indiaLocations;
        this.leverBoards = parseBoards(leverConfig);
        this.ashbyBoards = parseBoards(ashbyConfig);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialSync() {
        sync();
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.job-import.interval-ms:43200000}",
            initialDelayString = "${app.job-import.scheduled-initial-delay-ms:60000}")
    public void scheduledSync() {
        sync();
    }

    public synchronized Map<String, Object> sync() {
        int imported = 0, updated = 0, failed = 0, boardsOk = 0;
        boolean gotLiveData = false;
        Set<String> successfulSources = new HashSet<>();

        for (Board board : leverBoards) {
            try {
                int[] result = importLever(board);
                imported += result[0]; updated += result[1];
                boardsOk++;
                successfulSources.add(LEVER);
                gotLiveData |= result[0] + result[1] > 0;
            } catch (Exception e) {
                failed++;
                log.warn("Lever sync failed for {} ({}): {}", board.name, board.slug, e.getMessage());
            }
        }

        for (Board board : ashbyBoards) {
            try {
                int[] result = importAshby(board);
                imported += result[0]; updated += result[1];
                boardsOk++;
                successfulSources.add(ASHBY);
                gotLiveData |= result[0] + result[1] > 0;
            } catch (Exception e) {
                failed++;
                log.warn("Ashby sync failed for {} ({}): {}", board.name, board.slug, e.getMessage());
            }
        }

        if (gotLiveData) {
            List<Job> all = jobs.findAll();
            all.stream().filter(j -> "SAMPLE".equals(j.source)).forEach(j -> j.active = false);
            jobs.saveAll(all.stream().filter(j -> "SAMPLE".equals(j.source)).toList());
        }

        Instant cutoff = Instant.now().minus(expireDays, ChronoUnit.DAYS);
        for (String source : successfulSources) jobs.deactivateNotSeenSince(source, cutoff);

        log.info("Public ATS sync complete: {} boards OK, {} imported, {} updated, {} failed", boardsOk, imported, updated, failed);
        return Map.of("enabled", true, "boardsOk", boardsOk, "imported", imported, "updated", updated,
                "failed", failed, "sources", List.of("LEVER", "ASHBY"));
    }

    private int[] importLever(Board board) throws Exception {
        String body = client.get()
                .uri("https://api.lever.co/v0/postings/" + board.slug + "?mode=json")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class);
        JsonNode root = mapper.readTree(body);
        int imported = 0, updated = 0;
        if (!root.isArray()) return new int[]{0, 0};
        for (JsonNode p : root) {
            String location = firstText(p.path("categories"), "location");
            if (!isIndia(location)) continue;
            String id = text(p, "id");
            String title = text(p, "text");
            String applyUrl = text(p, "applyUrl");
            if (id.isBlank() || title.isBlank() || applyUrl.isBlank()) continue;
            Job j = jobs.findBySourceAndExternalId(LEVER, board.slug + ":" + id).orElseGet(Job::new);
            boolean created = j.id == null;
            fill(j, LEVER, board.slug + ":" + id, board.name, title, location, applyUrl,
                    text(p.path("categories"), "commitment"), text(p, "descriptionPlain"),
                    p.path("createdAt").asLong(0), text(p.path("categories"), "team"));
            jobs.save(j);
            if (created) imported++; else updated++;
        }
        return new int[]{imported, updated};
    }

    private int[] importAshby(Board board) throws Exception {
        String body = client.get()
                .uri("https://api.ashbyhq.com/posting-api/job-board/" + board.slug + "?includeCompensation=true")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class);
        JsonNode root = mapper.readTree(body);
        int imported = 0, updated = 0;
        for (JsonNode p : root.path("jobs")) {
            if (!p.path("isListed").asBoolean(true)) continue;
            String location = text(p, "location");
            if (!isIndia(location)) continue;
            String id = text(p, "jobUrl");
            String title = text(p, "title");
            String applyUrl = text(p, "applyUrl");
            if (id.isBlank() || title.isBlank() || applyUrl.isBlank()) continue;
            Job j = jobs.findBySourceAndExternalId(ASHBY, board.slug + ":" + id).orElseGet(Job::new);
            boolean created = j.id == null;
            fill(j, ASHBY, board.slug + ":" + id, board.name, title, location, applyUrl,
                    text(p, "employmentType"), text(p, "descriptionPlain"), parseIsoMillis(text(p, "publishedDate")),
                    text(p, "department"));
            jobs.save(j);
            if (created) imported++; else updated++;
        }
        return new int[]{imported, updated};
    }

    private void fill(Job j, String source, String externalId, String company, String title, String location,
                      String applyUrl, String employmentType, String description, long postedMillis, String team) {
        if (!ApplyUrlValidator.isAllowed(applyUrl, "")) throw new IllegalArgumentException("Rejected apply URL: " + applyUrl);
        j.source = source;
        j.externalId = externalId;
        j.title = clip(title, 150);
        j.company = clip(company, 100);
        j.companyDomain = "";
        j.location = clip(location, 100);
        j.workMode = inferWorkMode(location + " " + description);
        j.jobType = mapJobType(employmentType);
        j.seniority = mapSeniority(title);
        j.description = clip(clean(description), 5000);
        j.applyUrl = applyUrl;
        j.active = true;
        j.lastSeenAt = Instant.now();
        j.postedAt = postedMillis > 0 ? Instant.ofEpochMilli(postedMillis) : Instant.now();
        j.requiredSkills.clear();
        j.preferredSkills.clear();
        addInferredSkills(title + " " + team + " " + description, j.requiredSkills);
    }

    private boolean isIndia(String location) {
        if (location == null || location.isBlank()) return false;
        String x = location.toLowerCase(Locale.ROOT);
        return Arrays.stream(indiaLocations.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .anyMatch(x::contains);
    }

    private static List<Board> parseBoards(String config) {
        if (config == null || config.isBlank()) return List.of();
        List<Board> out = new ArrayList<>();
        for (String item : config.split(",")) {
            String[] parts = item.trim().split("\\|", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) out.add(new Board(parts[0].trim(), parts[1].trim()));
        }
        return List.copyOf(out);
    }

    private static String firstText(JsonNode node, String field) { return text(node, field); }

    private static String text(JsonNode node, String field) {
        JsonNode x = node.path(field);
        return x.isMissingNode() || x.isNull() ? "" : x.asText("").strip();
    }

    private static long parseIsoMillis(String value) {
        try { return value.isBlank() ? 0 : Instant.parse(value).toEpochMilli(); }
        catch (Exception ignored) { return 0; }
    }

    private static String clean(String s) { return s == null ? "" : HTML.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim(); }
    private static String clip(String s, int n) { return s == null ? "" : s.substring(0, Math.min(n, s.length())).strip(); }

    private static String inferWorkMode(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        if (x.contains("remote") || x.contains("work from home")) return "REMOTE";
        if (x.contains("hybrid")) return "HYBRID";
        return "ONSITE";
    }

    private static String mapJobType(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        if (x.contains("intern")) return "INTERNSHIP";
        if (x.contains("part")) return "PARTTIME";
        if (x.contains("contract")) return "CONTRACT";
        if (x.contains("temporary")) return "TEMPORARY";
        return "FULLTIME";
    }

    private static String mapSeniority(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        if (x.contains("intern")) return "INTERN";
        if (x.contains("senior") || x.contains("sr.") || x.contains("lead") || x.contains("principal") || x.contains("director") || x.contains("staff") || x.contains("manager")) return "SENIOR";
        if (x.contains("mid") || x.contains("ii") || x.contains("iii")) return "MID";
        return "ENTRY";
    }

    private static void addInferredSkills(String text, Set<String> target) {
        String x = text.toLowerCase(Locale.ROOT);
        String[] skills = {"java","spring boot","python","javascript","typescript","react","angular","node.js","sql","aws","azure","gcp","docker","kubernetes","salesforce","excel","finance","marketing","sales","customer support","qa","selenium","linux","terraform","machine learning","data analysis","cybersecurity"};
        for (String skill : skills) if (x.contains(skill)) target.add(skill);
    }

    private record Board(String name, String slug) {}
}
