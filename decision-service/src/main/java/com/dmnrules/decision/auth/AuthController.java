package com.dmnrules.decision.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo login: checks the submitted password against the {@code app_user} table and returns the
 * user plus a signed session token. Most of the API still identifies the caller by the
 * {@code X-User} header (see docs/DECISIONS.md); {@code GET /api/auth/me} verifies the token and is
 * what ai-service calls before letting anyone into the admin console.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(AppUserRepository userRepository, TokenService tokenService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AppUserRow user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return new LoginResponse(user.username(), user.displayName(), user.role(),
                tokenService.issue(user.username(), user.role()));
    }

    /**
     * The verified identity behind a bearer token. The role is re-read from the database, so a
     * user whose role changed (or who was removed) loses access without waiting for token expiry.
     */
    @GetMapping("/me")
    public CurrentUser me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : null;
        TokenService.Claims claims = tokenService.verify(token).orElseThrow(UnauthenticatedException::new);
        AppUserRow user = userRepository.findByUsername(claims.username()).orElseThrow(UnauthenticatedException::new);
        return new CurrentUser(user.username(), user.displayName(), user.role());
    }

    public record CurrentUser(String username, String displayName, String role) {
    }
}
