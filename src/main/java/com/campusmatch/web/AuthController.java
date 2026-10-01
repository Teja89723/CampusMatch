package com.campusmatch.web;

import com.campusmatch.model.Profile;
import com.campusmatch.model.User;
import com.campusmatch.repo.ProfileRepository;
import com.campusmatch.repo.UserRepository;
import com.campusmatch.security.JwtService;
import com.campusmatch.security.VerificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record RegisterRequest(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Pattern(regexp="^[0-9+()\\-\\s]{7,20}$", message="Enter a valid phone number") String phone,
        @NotBlank @Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).{10,100}$", message="Password must be 10-100 characters and include a letter and a number") String password,
        @NotBlank @Size(max=100) String fullName,
        @AssertTrue(message="You must accept the terms and privacy policy") boolean consent) {}
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
    public record VerifyRequest(@NotBlank @Email @Size(max=254) String email, @NotBlank @Pattern(regexp="^\\d{6}$") String code) {}
    public record ResendRequest(@NotBlank @Email @Size(max=254) String email) {}
    private static final int MAX_FAILED=5;
    private final UserRepository users; private final ProfileRepository profiles; private final PasswordEncoder encoder; private final JwtService jwt; private final VerificationService verification; private final String dummyHash;
    public AuthController(UserRepository users, ProfileRepository profiles, PasswordEncoder encoder, JwtService jwt, VerificationService verification) { this.users=users; this.profiles=profiles; this.encoder=encoder; this.jwt=jwt; this.verification=verification; this.dummyHash=encoder.encode("not-a-real-password"); }

    @PostMapping("/register") @Transactional
    public ResponseEntity<Map<String,Object>> register(@Valid @RequestBody RegisterRequest r) {
        String email=r.email().strip().toLowerCase(Locale.ROOT); String phone=normalizePhone(r.phone());
        if(users.findByEmail(email).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"An account with this email already exists. Try signing in.");
        User u=new User(); u.email=email; u.passwordHash=encoder.encode(r.password()); u.fullName=r.fullName().strip(); u.consentAt=Instant.now();
        users.save(u);
        Profile p=new Profile(); p.userId=u.id; p.phone=phone; profiles.save(p);
        String ec=verification.issueEmailOtp(u), pc=verification.issuePhoneOtp(u); users.save(u);
        verification.sendEmail(email,u.fullName,ec); verification.sendSms(phone,pc);
        Map<String,Object> out=new LinkedHashMap<>(); out.put("verificationRequired",true); out.put("email",email); out.put("phone",mask(phone));
        if(verification.isDevMode()){ out.put("devEmailCode",ec); out.put("devPhoneCode",pc); }
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @PostMapping("/verify-email") @Transactional
    public Map<String,Object> verifyEmail(@Valid @RequestBody VerifyRequest r){ User u=find(r.email()); if(!verification.verifyEmail(u,r.code())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired email verification code."); u.emailVerified=true; u.emailOtpHash=null; u.emailOtpExpiresAt=null; users.save(u); return verificationState(u); }
    @PostMapping("/verify-phone") @Transactional
    public Map<String,Object> verifyPhone(@Valid @RequestBody VerifyRequest r){ User u=find(r.email()); if(!verification.verifyPhone(u,r.code())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired phone verification code."); u.phoneVerified=true; u.phoneOtpHash=null; u.phoneOtpExpiresAt=null; users.save(u); return verificationState(u); }

    @PostMapping("/resend") @Transactional
    public Map<String,Object> resend(@Valid @RequestBody ResendRequest r){ User u=find(r.email()); String ec=null,pc=null; if(!u.emailVerified){ec=verification.issueEmailOtp(u); verification.sendEmail(u.email,u.fullName,ec);} if(!u.phoneVerified){pc=verification.issuePhoneOtp(u); Profile p=profiles.findById(u.id).orElseThrow(); verification.sendSms(p.phone,pc);} users.save(u); Map<String,Object> out=verificationState(u); if(verification.isDevMode()){out.put("devEmailCode",ec);out.put("devPhoneCode",pc);} return out; }

    @PostMapping("/login")
    public Map<String,Object> login(@Valid @RequestBody LoginRequest r){ ResponseStatusException bad=new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid email or password."); User u=users.findByEmail(r.email().strip().toLowerCase(Locale.ROOT)).orElse(null); if(u==null){encoder.matches(r.password(),dummyHash);throw bad;} if(u.lockedUntil!=null&&u.lockedUntil.isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many failed attempts. Try again in a few minutes."); if(!encoder.matches(r.password(),u.passwordHash)){u.failedLogins++;if(u.failedLogins>=MAX_FAILED){u.lockedUntil=Instant.now().plus(15,ChronoUnit.MINUTES);u.failedLogins=0;}users.save(u);throw bad;} if(!u.emailVerified||!u.phoneVerified){Map<String,Object> m=verificationState(u);m.put("verificationRequired",true);throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Verify your email and phone number before signing in.");}u.failedLogins=0;u.lockedUntil=null;users.save(u);return session(u); }
    private User find(String email){return users.findByEmail(email.strip().toLowerCase(Locale.ROOT)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found."));}
    private Map<String,Object> verificationState(User u){Map<String,Object> m=new LinkedHashMap<>();m.put("emailVerified",u.emailVerified);m.put("phoneVerified",u.phoneVerified);m.put("verificationComplete",u.emailVerified&&u.phoneVerified);m.put("email",u.email);if(u.emailVerified&&u.phoneVerified){m.putAll(session(u));}return m;}
    private Map<String,Object> session(User u){Map<String,Object> m=new LinkedHashMap<>();m.put("accessToken",jwt.issue(u));m.put("fullName",u.fullName);m.put("role",u.userRole);return m;}
    private static String normalizePhone(String p){return p.replaceAll("[()\\s-]","");}
    private static String mask(String p){return p.length()<=4?"****": "*".repeat(Math.max(0,p.length()-4))+p.substring(p.length()-4);}
}
