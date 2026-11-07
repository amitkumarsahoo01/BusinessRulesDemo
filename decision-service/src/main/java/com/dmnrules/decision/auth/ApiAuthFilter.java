package com.dmnrules.decision.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;

/**
 * Every {@code /api/**} call must authenticate, except login and health. Two schemes:
 * <ul>
 *   <li>{@code Authorization: Basic base64(username:password)} — scripts, Postman, integrations.</li>
 *   <li>{@code Authorization: Bearer <token>} — the web app, with the token from {@code /api/auth/login}.</li>
 * </ul>
 * The authenticated user then <em>replaces</em> any {@code X-User} header the caller sent, so the
 * caller/author/approver recorded everywhere (audit, four-eyes approval) is always the verified
 * identity. Only internal service accounts (role {@code service}, i.e. ai-service) may pass
 * {@code X-User} to act on behalf of the signed-in user they are serving.
 *
 * <p>Read-only roles ({@code api-client}, {@code auditor}) may only evaluate/simulate and read ({@code GET}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "com.dmnrules.decision.auth.principal";
    static final String USER_HEADER = "X-User";

    private static final Set<String> OPEN_PATHS = Set.of("/api/auth/login", "/api/health");

    // Resolved lazily: @WebMvcTest slices register servlet filters but not these collaborators, and
    // must still start (their open endpoints, like /api/health, never reach the lookups).
    private final ObjectProvider<TokenService> tokenService;
    private final ObjectProvider<CredentialVerifier> credentialVerifier;
    private final ObjectProvider<AppUserRepository> userRepository;
    private final ObjectMapper objectMapper;

    public ApiAuthFilter(ObjectProvider<TokenService> tokenService, ObjectProvider<CredentialVerifier> credentialVerifier,
            ObjectProvider<AppUserRepository> userRepository, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.credentialVerifier = credentialVerifier;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /** Who made this request, as verified by {@link ApiAuthFilter}. */
    public record Principal(String username, String displayName, String role, String scheme) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || OPEN_PATHS.contains(path) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Principal> principal = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (principal.isEmpty()) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "Not signed in",
                    "This API needs credentials: HTTP Basic (username:password) or a Bearer token from POST /api/auth/login.");
            return;
        }
        Principal p = principal.get();
        String effectiveUser = effectiveUser(p, request.getHeader(USER_HEADER));
        // A service acting on behalf of a user is limited by that user's role, not its own.
        String effectiveRole = effectiveUser.equals(p.username()) ? p.role()
                : userRepository.getObject().findByUsername(effectiveUser).map(AppUserRow::role).orElse(Roles.AUDITOR);
        if (Roles.isReadOnly(effectiveRole) && !readOnlyMayCall(request)) {
            reject(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                    "The " + effectiveRole + " role may evaluate/simulate decisions and read data, but not change rules.");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, p);
        chain.doFilter(new IdentityRequest(request, effectiveUser), response);
    }

    Optional<Principal> authenticate(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return tokenService.getObject().verify(authorization.substring(7).trim())
                    // Re-read the user so a removed account or changed role takes effect immediately.
                    .flatMap(claims -> userRepository.getObject().findByUsername(claims.username()))
                    .filter(u -> !Roles.isApiOnly(u.role()))
                    .map(u -> new Principal(u.username(), u.displayName(), u.role(), "Bearer"));
        }
        if (authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            return credentialVerifier.getObject().verify(decoded.substring(0, colon), decoded.substring(colon + 1))
                    .map(u -> new Principal(u.username(), u.displayName(), u.role(), "Basic"));
        }
        return Optional.empty();
    }

    private static boolean readOnlyMayCall(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod())
                || ("POST".equals(request.getMethod()) && path.startsWith("/api/decisions/"));
    }

    /** Service accounts may act on behalf of a user; everyone else is exactly who they authenticated as. */
    static String effectiveUser(Principal principal, String requestedUser) {
        if (Roles.SERVICE.equals(principal.role()) && requestedUser != null && !requestedUser.isBlank()) {
            return requestedUser.trim();
        }
        return principal.username();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String title,
            String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        // The web app sends X-Requested-With; skipping the Basic challenge there stops browsers from
        // popping up their own username/password dialog over the app's login page.
        if (status == HttpStatus.UNAUTHORIZED && !"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"dmn-rules\", charset=\"UTF-8\"");
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Presents the verified identity as {@code X-User} to the existing controllers. */
    private static final class IdentityRequest extends HttpServletRequestWrapper {
        private final String user;

        IdentityRequest(HttpServletRequest request, String user) {
            super(request);
            this.user = user;
        }

        @Override
        public String getHeader(String name) {
            return USER_HEADER.equalsIgnoreCase(name) ? user : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return USER_HEADER.equalsIgnoreCase(name) ? Collections.enumeration(java.util.List.of(user)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            java.util.Set<String> names = new java.util.LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.add(USER_HEADER);
            return Collections.enumeration(names);
        }
    }
}
