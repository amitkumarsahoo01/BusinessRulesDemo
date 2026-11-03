package com.dmnrules.decision.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo login: checks the submitted password against the seeded {@code app_user} table. There is
 * no session token / JWT yet (see docs/DECISIONS.md) — the frontend remembers the returned
 * username and sends it back as the {@code X-User} header the rest of the API already reads.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AppUserRow user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return new LoginResponse(user.username(), user.displayName(), user.role());
    }
}
