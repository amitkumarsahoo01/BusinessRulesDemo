package com.dmnrules.decision.notify;

import com.dmnrules.decision.model.ModelRegistry;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Listens on the Postgres {@code model_published} channel so every decision-service instance
 * hot-swaps a newly published model version without a restart (CLAUDE.md rule 4,
 * docs/Architecture.md "Runtime registry"). Uses its own dedicated (non-pooled) JDBC connection
 * because LISTEN must stay open on one connection for the process lifetime.
 */
@Component
public class ModelPublishedNotifier {

    private static final Logger log = LoggerFactory.getLogger(ModelPublishedNotifier.class);
    private static final String CHANNEL = "model_published";

    private final ModelRegistry registry;
    private final String url;
    private final String username;
    private final String password;

    private volatile boolean running = true;
    private Thread thread;
    private Connection listenConnection;

    public ModelPublishedNotifier(
            ModelRegistry registry,
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        this.registry = registry;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            listenConnection = DriverManager.getConnection(url, username, password);
            try (Statement statement = listenConnection.createStatement()) {
                statement.execute("LISTEN " + CHANNEL);
            }
        } catch (SQLException e) {
            log.error("Could not start model_published listener; live reload on publish is disabled", e);
            return;
        }
        thread = new Thread(this::loop, "model-published-listener");
        thread.setDaemon(true);
        thread.start();
        log.info("Listening on Postgres channel '{}' for model_published notifications", CHANNEL);
    }

    private void loop() {
        while (running) {
            try {
                PGConnection pgConnection = listenConnection.unwrap(PGConnection.class);
                PGNotification[] notifications = pgConnection.getNotifications(5000);
                if (notifications != null && notifications.length > 0) {
                    log.info("Received {} model_published notification(s); reloading registry", notifications.length);
                    registry.reload();
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("model_published listener error, retrying", e);
                    sleepQuietly();
                }
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        if (listenConnection != null) {
            try {
                listenConnection.close();
            } catch (SQLException ignored) {
                // shutting down anyway
            }
        }
    }
}
