package com.dmnrules.decision.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-app login: checks the password against {@code app_user} and returns the user plus a signed
 * session token. Every other {@code /api} call is authenticated by {@link ApiAuthFilter} (Bearer token
 * or HTTP Basic). API-only accounts ({@code api-client}, {@code ai-service}) cannot sign in here.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CredentialVerifier credentialVerifier;
    private final TokenService tokenService;

    public AuthController(CredentialVerifier credentialVerifier, TokenService tokenService) {
        this.credentialVerifier = credentialVerifier;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AppUserRow user = credentialVerifier.verify(request.username(), request.password())
                .filter(u -> !Roles.isApiOnly(u.role()))
                .orElseThrow(InvalidCredentialsException::new);
        return new LoginResponse(user.username(), user.displayName(), user.role(),
                tokenService.issue(user.username(), user.role()));
    }

    /**
     * Who the caller is, as verified by {@link ApiAuthFilter} (token or Basic). ai-service uses this
     * to identify chat users and admins without ever trusting a self-declared {@code X-User}.
     */
    @GetMapping("/me")
    public CurrentUser me(HttpServletRequest request) {
        ApiAuthFilter.Principal p = (ApiAuthFilter.Principal) request.getAttribute(ApiAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (p == null) {
            throw new UnauthenticatedException();
        }
        return new CurrentUser(p.username(), p.displayName(), p.role());
    }

    public record CurrentUser(String username, String displayName, String role) {
    }
}
