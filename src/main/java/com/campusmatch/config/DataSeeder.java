package com.campusmatch.config;

import com.campusmatch.model.Job;
import com.campusmatch.model.Profile;
import com.campusmatch.model.User;
import com.campusmatch.repo.JobRepository;
import com.campusmatch.repo.ProfileRepository;
import com.campusmatch.repo.UserRepository;
import com.campusmatch.web.ApplyUrlValidator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Loads SAMPLE listings on first start so the app is usable immediately.
 * These are demo postings (clearly labelled "SAMPLE" in the UI) whose apply links point at each company's real careers page.
 * Replace them with real postings via POST /api/v1/admin/jobs or an ATS feed importer.
 */
@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JobRepository jobs;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final PasswordEncoder encoder;
    private final String adminEmail, adminPassword;

    public DataSeeder(JobRepository jobs, UserRepository users, ProfileRepository profiles, PasswordEncoder encoder,
                      @Value("${app.admin-email:}") String adminEmail, @Value("${app.admin-password:}") String adminPassword) {
        this.jobs = jobs; this.users = users; this.profiles = profiles; this.encoder = encoder;
        this.adminEmail = adminEmail; this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (!adminEmail.isBlank() && adminPassword.length() >= 12 && users.findByEmail(adminEmail.toLowerCase()).isEmpty()) {
            User a = new User();
            a.email = adminEmail.toLowerCase(); a.fullName = "Administrator"; a.userRole = "ADMIN";
            a.passwordHash = encoder.encode(adminPassword);
            users.save(a);
            Profile p = new Profile(); p.userId = a.id; profiles.save(p);
            log.info("Admin account created for {}", adminEmail);
        }
        if (jobs.countBySource("SAMPLE") > 0) return;

        seed("Java Backend Developer Intern", "Infosys", "infosys.com", "Bengaluru", "HYBRID", "INTERNSHIP", "INTERN",
             "Work with a services team building REST APIs and database-backed features. Pair with mentors, write unit tests and take part in code reviews.",
             List.of("Java", "SQL", "Git"), List.of("Spring Boot", "REST APIs", "Docker"), "https://www.infosys.com/careers/");
        seed("Software Engineer, New Graduate", "Google", "google.com", "Hyderabad", "ONSITE", "FULLTIME", "ENTRY",
             "Design and build reliable services and tools used at scale. Strong data structures and algorithms fundamentals expected.",
             List.of("Data Structures", "Algorithms", "Python"), List.of("Java", "C++", "Linux"), "https://www.google.com/about/careers/applications/");
        seed("Frontend Developer (React)", "Flipkart", "flipkartcareers.com", "Bengaluru", "ONSITE", "FULLTIME", "ENTRY",
             "Build fast, accessible storefront experiences with React and TypeScript. Own features from design handoff to release.",
             List.of("JavaScript", "React", "HTML", "CSS"), List.of("TypeScript", "Git", "REST APIs"), "https://www.flipkartcareers.com/");
        seed("Cloud Engineer Intern", "Microsoft", "microsoft.com", "Hyderabad", "HYBRID", "INTERNSHIP", "INTERN",
             "Help automate infrastructure and monitoring on Azure. Learn CI/CD, containers and scripting on a real team.",
             List.of("Python", "Linux", "Git"), List.of("Azure", "Docker", "Kubernetes"), "https://careers.microsoft.com/");
        seed("Data Analyst Trainee", "TCS", "tcs.com", "Chennai", "ONSITE", "FULLTIME", "ENTRY",
             "Clean, analyse and visualise business data. Build dashboards and write SQL queries for stakeholders.",
             List.of("SQL", "Excel", "Data Analysis"), List.of("Python", "Tableau", "Power BI"), "https://www.tcs.com/careers");
        seed("Machine Learning Engineer Intern", "Amazon", "amazon.jobs", "Bengaluru", "ONSITE", "INTERNSHIP", "INTERN",
             "Prototype and evaluate ML models on production data with a mentor. Coursework in ML and strong Python required.",
             List.of("Python", "Machine Learning"), List.of("PyTorch", "TensorFlow", "Pandas", "NumPy"), "https://www.amazon.jobs/en/");
        seed("Full Stack Developer", "Zoho", "zoho.com", "Chennai", "ONSITE", "FULLTIME", "ENTRY",
             "Build features across the stack for a suite of business products, from database to UI.",
             List.of("Java", "JavaScript", "SQL"), List.of("HTML", "CSS", "REST APIs"), "https://www.zoho.com/careers/");
        seed("Associate Software Engineer", "Wipro", "wipro.com", "Pune", "HYBRID", "FULLTIME", "ENTRY",
             "Join a delivery team building and maintaining enterprise applications. Training provided.",
             List.of("Java", "SQL"), List.of("Spring Boot", "Agile", "Git"), "https://careers.wipro.com/");
        seed("QA Automation Engineer Intern", "Atlassian", "atlassian.com", "Bengaluru", "REMOTE", "INTERNSHIP", "INTERN",
             "Write automated tests that keep collaboration tools reliable. Learn test design, CI pipelines and debugging.",
             List.of("Java", "Git"), List.of("Selenium", "JUnit", "CI/CD"), "https://www.atlassian.com/company/careers");
        seed("Backend Engineer (Node.js)", "Razorpay", "razorpay.com", "Bengaluru", "HYBRID", "FULLTIME", "ENTRY",
             "Build payment APIs used by thousands of businesses. Focus on correctness, performance and security.",
             List.of("Node.js", "JavaScript", "SQL"), List.of("Redis", "Docker", "Microservices"), "https://razorpay.com/jobs/");
        seed("UI/UX Design Intern", "Adobe", "adobe.com", "Noida", "HYBRID", "INTERNSHIP", "INTERN",
             "Support designers on user research and prototyping for creative tools.",
             List.of("Figma"), List.of("HTML", "CSS"), "https://careers.adobe.com/");
        seed("Technology Analyst", "Accenture", "accenture.com", "Gurugram", "HYBRID", "FULLTIME", "ENTRY",
             "Analyse requirements and build solutions for enterprise clients across cloud and data projects.",
             List.of("SQL", "Agile"), List.of("Java", "Python", "AWS"), "https://www.accenture.com/in-en/careers");
        seed("Support Engineer, Customer Success", "Freshworks", "freshworks.com", "Chennai", "ONSITE", "FULLTIME", "ENTRY",
             "Troubleshoot customer issues using logs and SQL, and work with engineering to fix root causes.",
             List.of("SQL", "REST APIs"), List.of("Linux", "JavaScript"), "https://www.freshworks.com/company/careers/");
        log.info("Seeded {} sample jobs", jobs.countBySource("SAMPLE"));
    }

    private int n = 0;

    private void seed(String title, String company, String domain, String loc, String mode, String type, String level,
                      String desc, List<String> req, List<String> pref, String url) {
        if (!ApplyUrlValidator.isAllowed(url, domain)) { log.warn("Skipping seed job with invalid apply URL: {}", url); return; }
        Job j = new Job();
        j.title = title; j.company = company; j.companyDomain = domain; j.location = loc;
        j.workMode = mode; j.jobType = type; j.seniority = level;
        j.description = "[Sample listing] " + desc;
        j.applyUrl = url; j.source = "SAMPLE";
        j.postedAt = Instant.now().minus(n++, ChronoUnit.DAYS);
        j.requiredSkills.addAll(req);
        j.preferredSkills.addAll(pref);
        jobs.save(j);
    }
}
