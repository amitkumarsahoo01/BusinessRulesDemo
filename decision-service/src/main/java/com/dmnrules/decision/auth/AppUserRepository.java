package com.dmnrules.decision.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AppUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AppUserRow> findByUsername(String username) {
        List<AppUserRow> rows = jdbcTemplate.query(
                "SELECT * FROM app_user WHERE username = ?",
                (rs, n) -> new AppUserRow(
                        rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("role")),
                username);
        return rows.stream().findFirst();
    }
}
