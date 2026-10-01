# CampusMatch

Job-search platform for students: profile and resume upload, a transparent **ATS Readiness Score**,
explainable **Job Match scores**, and direct apply links to company portals.

* **Backend:** Spring Boot 3.3 (Java 21), Spring Security (JWT), Spring Data JPA
* **Frontend:** plain JavaScript SPA served by Spring Boot (`src/main/resources/static`), no build step
* **Database:** H2 file DB out of the box, PostgreSQL via the `postgres` profile
* **Resume parsing:** Apache PDFBox (PDF) and Apache POI (DOCX)

## Run it

Requirements: **JDK 21** and **Maven 3.9+**.

```bash
mvn spring-boot:run            # starts on http://localhost:8080 with an embedded H2 database
```

Open http://localhost:8080, create an account, fill your profile, upload a resume.

PostgreSQL instead of H2:

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Run the tests: `mvn test`

### Production settings (required)

Set these environment variables. The built-in defaults are for local development only.

| Variable | Meaning |
|---|---|
| `APP_JWT_SECRET` | 32+ character random string used to sign tokens |
| `APP_ENC_KEY` | 32 random bytes, base64 (`openssl rand -base64 32`). Encrypts resume files and phone numbers. **If you lose it, stored resumes cannot be decrypted** |
| `APP_STORAGE_DIR` | Where encrypted resumes are written |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection (profile `postgres`) |
| `APP_ADMIN_EMAIL`, `APP_ADMIN_PASSWORD` | Optional. Creates an admin (password 12+ chars) who can add jobs |

Always serve over HTTPS (reverse proxy or load balancer) in production.

## Folder structure

```
campusmatch/
├── pom.xml
├── docker-compose.yml                 PostgreSQL for the "postgres" profile
├── README.md
└── src/
    ├── main/
    │   ├── java/com/campusmatch/
    │   │   ├── CampusMatchApplication.java
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java          stateless JWT security, CSP and security headers
    │   │   │   ├── GlobalExceptionHandler.java  RFC 7807 errors, no internals leaked
    │   │   │   └── DataSeeder.java              sample jobs + optional admin
    │   │   ├── security/
    │   │   │   ├── JwtService.java, JwtAuthFilter.java
    │   │   │   ├── Crypto.java                  AES-256-GCM
    │   │   │   └── EncryptedStringConverter.java  field-level encryption (phone)
    │   │   ├── model/       User, Profile, Resume, Job, ApplicationRecord
    │   │   ├── repo/        Spring Data repositories
    │   │   ├── ats/
    │   │   │   ├── ResumeParser.java            PDF/DOCX text + hidden-text detection
    │   │   │   ├── AtsScorer.java               deterministic 100-point rubric (v2026.1)
    │   │   │   └── SkillCatalog.java            skill taxonomy and aliases
    │   │   ├── match/MatchService.java          explainable job match rubric
    │   │   └── web/
    │   │       ├── AuthController.java          register / login
    │   │       ├── ProfileController.java       profile + completeness
    │   │       ├── ResumeController.java        upload, score, list, download, delete
    │   │       ├── JobController.java           search, recommendations, apply tracking, admin create
    │   │       └── ApplyUrlValidator.java       HTTPS + company-domain allowlist for apply links
    │   └── resources/
    │       ├── application.yml, application-postgres.yml
    │       └── static/      index.html, styles.css, app.js   (the frontend)
    └── test/java/com/campusmatch/ats/AtsScorerTest.java
```

## API (base `/api/v1`, JSON, errors are `application/problem+json` with a `detail` message)

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /auth/register` | no | `{email, password, fullName, consent}` returns `{accessToken, fullName, role}` |
| `POST /auth/login` | no | `{email, password}` returns the same. Locks for 15 min after 5 failures |
| `GET /me/profile`, `PUT /me/profile` | yes | Profile fields, skills, preferences, `completeness` |
| `POST /resumes` | yes | multipart `file` (PDF/DOCX, max 5 MB). Returns the resume with its `ats` result |
| `GET /resumes`, `GET /resumes/{id}` | yes | List / detail with full score breakdown |
| `PUT /resumes/{id}/activate` | yes | Choose the resume used for matching |
| `GET /resumes/{id}/download` | yes | Decrypted download (owner only) |
| `DELETE /resumes/{id}` | yes | Permanent delete (file and row) |
| `POST /resumes/{id}/score-against/{jobId}` | yes | Matched / missing keywords for one job |
| `GET /jobs?q&location&workMode&type&page&size` | no | Search (max 50 per page) |
| `GET /jobs/{id}` | no | Job detail incl. `applyUrl` |
| `GET /me/recommendations?page&size` | yes | Ranked jobs with per-component breakdown |
| `POST /jobs/{id}/apply-intent` | yes | Records the click in your tracker |
| `GET /me/applications`, `PATCH /me/applications/{jobId}` | yes | Tracker. Status is self-reported |
| `POST /admin/jobs` | ADMIN | Add a real job. The apply URL must be HTTPS on the company domain or a known ATS host |

## How scoring works (no guessing)

**ATS Readiness Score (0-100)** measures how reliably software can read your resume. It is a fixed,
published rubric. The same file always gets the same score, and every category shows its evidence and fixes.

| Category | Points |
|---|---|
| Parseability (text density, reading-order fragmentation) | 25 |
| Contact details | 10 |
| Standard sections (Education, Experience/Projects, Skills) | 15 |
| Dates and structure | 10 |
| Content quality (action verbs, measurable results) | 20 |
| Formatting safety | 10 |
| Length | 10 |

It is **not** "the score a company's ATS will give you". Real ATS products differ and have no common score.
The UI says so. Hidden text under 2pt is excluded and flagged, keyword stuffing is penalised, and lines that
try to instruct an AI are removed and flagged. No AI model is involved, so there is nothing to prompt-inject.

**Job Match (0-100):** required skills 45, preferred skills 15, role fit 15, seniority/type 10,
location/work mode 10, evidence strength 5 (skills present in both profile and resume count fully).
No name, gender, age, photo or college prestige is used.

## What is and is not included

Included and working by design: registration/login, lockout, encrypted resume storage, scoring, matching,
tracker, admin job creation with apply-link validation, CSP and security headers, unit tests for the scorer.

**Sample jobs.** On first start the app inserts 13 clearly labelled *sample* listings so you can try it.
Their apply links go to each company's careers page, but the postings themselves are fictional.
Replace them using `POST /admin/jobs` or an importer for public ATS feeds (Greenhouse, Lever, Ashby).

Not built yet (recommended next steps):
* Email verification and password reset (needs an SMTP provider). Registration currently returns 409 for a duplicate email, which reveals that the email exists. Switch to a neutral response once email verification is in place.
* Malware scanning (ClamAV) and an async worker queue. Parsing currently runs inside the upload request, which is fine for 5 MB files.
* Scheduled apply-link health checks (HEAD requests every 24h, hide dead links).
* Flyway migrations (the app uses `ddl-auto: update`; use `validate` plus Flyway in production).
* Refresh-token rotation (the access token lasts 60 minutes and is kept in `sessionStorage`).
* Rate limiting per IP (only per-user upload limits and login lockout exist) and MFA.
* OCR for scanned PDFs (they are detected and the student is told to export a text PDF).


## Live India jobs

CampusMatch can import current India job openings through the Jobvetta REST API. Jobvetta documents that its index is gathered from official employer sources and supports filtering by keyword, Indian location, and posting age. The API key is optional for local/demo runs and must be kept server-side.

### Enable live jobs

1. Create a Jobvetta API key from its developer dashboard.
2. In PowerShell, from the project directory run:

```powershell
$env:JOBVETTA_API_KEY="YOUR_KEY"
mvn spring-boot:run
```

3. CampusMatch performs an initial sync at startup and then refreshes on a schedule (12 hours by default).
4. An admin can also trigger a sync with `POST /api/v1/admin/jobs/sync`.

The importer searches multiple broad job categories, de-duplicates by the source job id, records the original Jobvetta listing URL, and expires live records that have not been seen for the configured number of days. Demo `SAMPLE` jobs are deactivated after the first successful live sync.

The free Jobvetta API currently allows up to 50 requests per key per UTC day and returns up to 10 jobs per search, so the importer intentionally limits its scheduled searches.

## Live job feeds

CampusMatch can import published India jobs directly from public employer ATS feeds. The default configuration uses Lever and Ashby boards and does **not** require a Jobvetta API key.

Supported feeds in the default configuration include companies currently publishing public jobs on:
- Lever: Stable Money, 100ms, Acceldata, Gushwork, Saviynt, Findem, Weekday, Level AI, Kobie Marketing.
- Ashby: Overview, Plotline, Ema, Founders Factory, Handshake, CUBE, Runbook.

The importer runs at startup and every 12 hours by default. It only keeps locations matching the configured India location list, deduplicates by source/board/job ID, preserves the original ATS application URL, and deactivates stale live records after 30 days when a source sync succeeds.

To add more public boards, set `LEVER_BOARDS` or `ASHBY_BOARDS` as comma-separated `Company Name|board-slug` values. No API key is required for the included public ATS feeds.


## Student verification and personalized matching

This version adds mandatory email and phone verification after signup. For local development, `APP_VERIFICATION_DEV_MODE=true` (the default) returns the verification codes in the signup/resend response and shows them in the UI. For production, set it to `false` and configure SMTP plus Twilio environment variables.

Environment variables for production verification:
- `APP_VERIFICATION_DEV_MODE=false`
- `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`
- `APP_VERIFICATION_FROM`
- `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`

After both verifications succeed, the student receives a session token. The profile records years of professional experience. Resume upload detects an experience-years phrase when possible and updates an otherwise-fresher profile. Recommendations then restrict freshers to INTERN/ENTRY jobs and experienced students to MID/SENIOR jobs, while still scoring resume/profile skills.

After a successful resume upload, the UI redirects to `#/recommended`.
