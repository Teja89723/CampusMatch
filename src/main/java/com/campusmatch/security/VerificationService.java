package com.campusmatch.security;

import com.campusmatch.model.User;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VerificationService {

    private final SecureRandom random = new SecureRandom();

    private final JavaMailSender mail;

    private final boolean devMode;
    private final String from;

    private final String twilioSid;
    private final String twilioToken;
    private final String twilioFrom;

    public VerificationService(
            JavaMailSender mail,
            @Value("${app.verification.dev-mode:true}") boolean devMode,
            @Value("${app.verification.from:}") String from,
            @Value("${app.verification.twilio-account-sid:}") String twilioSid,
            @Value("${app.verification.twilio-auth-token:}") String twilioToken,
            @Value("${app.verification.twilio-from:}") String twilioFrom) {

        this.mail = mail;
        this.devMode = devMode;
        this.from = from;
        this.twilioSid = twilioSid;
        this.twilioToken = twilioToken;
        this.twilioFrom = twilioFrom;
    }

    /**
     * Generate a 6-digit email verification code.
     */
    public String issueEmailOtp(User user) {

        String code = generateCode();

        user.emailOtpHash = hash(code);

        user.emailOtpExpiresAt =
                Instant.now().plus(10, ChronoUnit.MINUTES);

        return code;
    }

    /**
     * Generate a 6-digit phone verification OTP.
     */
    public String issuePhoneOtp(User user) {

        String code = generateCode();

        user.phoneOtpHash = hash(code);

        user.phoneOtpExpiresAt =
                Instant.now().plus(10, ChronoUnit.MINUTES);

        return code;
    }

    /**
     * Verify email OTP.
     */
    public boolean verifyEmail(User user, String code) {

        return verify(
                code,
                user.emailOtpHash,
                user.emailOtpExpiresAt
        );
    }

    /**
     * Verify phone OTP.
     */
    public boolean verifyPhone(User user, String code) {

        return verify(
                code,
                user.phoneOtpHash,
                user.phoneOtpExpiresAt
        );
    }

    /**
     * Send email verification code.
     *
     * In development mode the email is not actually sent.
     */
    public void sendEmail(
            String email,
            String name,
            String code) {

        if (devMode) {
            return;
        }

        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "Email sender is not configured."
            );
        }

        SimpleMailMessage message =
                new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(email);
        message.setSubject(
                "CampusMatch email verification code"
        );

        message.setText(
                "Hi " + name + ",\n\n"
                        + "Your CampusMatch verification code is: "
                        + code
                        + "\n\n"
                        + "This code expires in 10 minutes."
        );

        mail.send(message);
    }

    /**
     * Send SMS verification code through Twilio.
     */
    public void sendSms(
            String phone,
            String code) {

        if (devMode) {
            return;
        }

        if (twilioSid == null || twilioSid.isBlank()
                || twilioToken == null || twilioToken.isBlank()
                || twilioFrom == null || twilioFrom.isBlank()) {

            throw new IllegalStateException(
                    "SMS provider is not configured."
            );
        }

        try {

            String message =
                    "CampusMatch phone verification code: "
                            + code
                            + ". Expires in 10 minutes.";

            String form =
                    "To=" + encode(phone)
                            + "&From=" + encode(twilioFrom)
                            + "&Body=" + encode(message);

            String credentials =
                    twilioSid + ":" + twilioToken;

            String auth =
                    Base64.getEncoder()
                            .encodeToString(
                                    credentials.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            );

            String url =
                    "https://api.twilio.com/2010-04-01/Accounts/"
                            + twilioSid
                            + "/Messages.json";

            HttpRequest request =
                    HttpRequest.newBuilder(
                            URI.create(url)
                    )
                    .header(
                            "Authorization",
                            "Basic " + auth
                    )
                    .header(
                            "Content-Type",
                            "application/x-www-form-urlencoded"
                    )
                    .POST(
                            HttpRequest.BodyPublishers
                                    .ofString(form)
                    )
                    .build();

            HttpResponse<String> response =
                    HttpClient.newHttpClient()
                            .send(
                                    request,
                                    HttpResponse.BodyHandlers
                                            .ofString()
                            );

            if (response.statusCode() >= 300) {

                throw new IllegalStateException(
                        "SMS provider rejected the message. "
                                + "HTTP status: "
                                + response.statusCode()
                );
            }

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Could not send SMS verification code.",
                    e
            );
        }
    }

    /**
     * Development mode indicator.
     */
    public boolean isDevMode() {
        return devMode;
    }

    /**
     * Verify an OTP against its stored hash.
     */
    private boolean verify(
            String code,
            String expectedHash,
            Instant expiry) {

        if (code == null
                || !code.matches("\\d{6}")
                || expectedHash == null
                || expiry == null
                || expiry.isBefore(Instant.now())) {

            return false;
        }

        String actualHash = hash(code);

        return MessageDigest.isEqual(
                actualHash.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Generate a random 6-digit OTP.
     */
    private String generateCode() {

        return String.format(
                "%06d",
                random.nextInt(1_000_000)
        );
    }

    /**
     * SHA-256 hash of the OTP.
     */
    private String hash(String value) {

        try {

            byte[] digest =
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest(
                                    value.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            );

            return HexFormat
                    .of()
                    .formatHex(digest);

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to hash verification code.",
                    e
            );
        }
    }

    /**
     * URL encode a value for the Twilio request.
     */
    private static String encode(String value) {

        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }
}