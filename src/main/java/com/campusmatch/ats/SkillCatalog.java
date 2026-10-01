package com.campusmatch.ats;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Canonical skill taxonomy with aliases. Used for resume parsing, profile normalisation and job matching.
 * Matching quality depends on this list more than on any algorithm: grow it (or seed it from ESCO / O*NET).
 */
@Component
public class SkillCatalog {
    private final Map<String, Pattern> patterns = new LinkedHashMap<>();
    private final Map<String, String> aliasToCanonical = new HashMap<>();

    public SkillCatalog() {
        add("Java", "java");
        add("Python", "python");
        add("JavaScript", "javascript", "js", "ecmascript");
        add("TypeScript", "typescript", "ts");
        add("C++", "c++", "cpp");
        add("C#", "c#", "csharp");
        add(".NET", ".net", "dotnet");
        add("Golang", "golang");
        add("Kotlin", "kotlin");
        add("Swift", "swift");
        add("PHP", "php");
        add("Ruby", "ruby");
        add("SQL", "sql");
        add("MySQL", "mysql");
        add("PostgreSQL", "postgresql", "postgres");
        add("MongoDB", "mongodb", "mongo");
        add("Redis", "redis");
        add("Spring Boot", "spring boot", "springboot", "spring-boot");
        add("Spring", "spring", "spring framework");
        add("Hibernate", "hibernate", "jpa");
        add("React", "react", "react.js", "reactjs");
        add("Angular", "angular", "angularjs");
        add("Vue", "vue", "vue.js", "vuejs");
        add("Node.js", "node.js", "nodejs", "node");
        add("Express.js", "express.js", "expressjs");
        add("Django", "django");
        add("Flask", "flask");
        add("FastAPI", "fastapi");
        add("HTML", "html", "html5");
        add("CSS", "css", "css3");
        add("REST APIs", "rest", "restful", "rest api", "rest apis");
        add("GraphQL", "graphql");
        add("Microservices", "microservices", "microservice");
        add("Docker", "docker");
        add("Kubernetes", "kubernetes", "k8s");
        add("AWS", "aws", "amazon web services");
        add("Azure", "azure");
        add("GCP", "gcp", "google cloud");
        add("Git", "git");
        add("GitHub", "github", "github actions");
        add("Jenkins", "jenkins");
        add("CI/CD", "ci/cd", "cicd");
        add("Linux", "linux");
        add("Machine Learning", "machine learning", "ml");
        add("Deep Learning", "deep learning");
        add("TensorFlow", "tensorflow");
        add("PyTorch", "pytorch");
        add("Pandas", "pandas");
        add("NumPy", "numpy");
        add("Scikit-learn", "scikit-learn", "sklearn");
        add("Data Analysis", "data analysis", "data analytics");
        add("Excel", "excel");
        add("Tableau", "tableau");
        add("Power BI", "power bi", "powerbi");
        add("Figma", "figma");
        add("Agile", "agile");
        add("Scrum", "scrum");
        add("JUnit", "junit");
        add("Selenium", "selenium");
        add("Data Structures", "data structures");
        add("Algorithms", "algorithms");
        add("OOP", "oop", "object-oriented", "object oriented");
        add("Android", "android");
        add("Flutter", "flutter");
    }

    private void add(String canonical, String... aliases) {
        List<String> all = new ArrayList<>(Arrays.asList(aliases));
        all.add(canonical.toLowerCase(Locale.ROOT));
        StringBuilder alt = new StringBuilder();
        for (String a : new LinkedHashSet<>(all)) {
            aliasToCanonical.put(a.toLowerCase(Locale.ROOT), canonical);
            if (alt.length() > 0) alt.append('|');
            alt.append(Pattern.quote(a));
        }
        // whole-token match: not glued to letters/digits/+/# on either side (so "Java" never matches inside "JavaScript")
        patterns.put(canonical, Pattern.compile("(?<![A-Za-z0-9+#])(?:" + alt + ")(?![A-Za-z0-9+#])", Pattern.CASE_INSENSITIVE));
    }

    /** How many times each known skill is mentioned in the text. */
    public Map<String, Integer> mentions(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            int n = 0;
            while (m.find()) n++;
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }

    public Set<String> extract(String text) {
        return new TreeSet<>(mentions(text).keySet());
    }

    /** Maps user-typed skills ("js", "postgres") to canonical names; unknown skills are kept as typed. */
    public String normalize(String raw) {
        String t = raw == null ? "" : raw.strip();
        return aliasToCanonical.getOrDefault(t.toLowerCase(Locale.ROOT), t);
    }
}
