package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
public class HealthController {

    private static final int DB_TIMEOUT_SECONDS = 5;
    // Calls within this window share one DB check, so /health can't be used to flood the pool.
    private static final long CACHE_TTL_MS = 10_000;

    private final DataSource dataSource;
    // Virtual threads: a check stuck on a dead connection doesn't hold a platform thread.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private CompletableFuture<Boolean> currentCheck;
    private long currentCheckStartedAt;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Liveness + database check. Public, so load balancers and uptime monitors can call it.
     * Returns 200 when everything is up, 503 when the database is unreachable.
     */
    @GetMapping({"/health", "/api/health"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        boolean dbUp = isDatabaseUp();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", dbUp ? "UP" : "DOWN");
        data.put("database", dbUp ? "UP" : "DOWN");
        data.put("timestamp", Instant.now().toString());

        if (dbUp) {
            return ResponseEntity.ok(ApiResponse.ok("Service is healthy", data));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(false, "Database is unreachable", data));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Bounded by DB_TIMEOUT_SECONDS end to end. getConnection() alone can block for
     * Hikari's connection-timeout (30s default) when the database is unreachable.
     */
    private boolean isDatabaseUp() {
        try {
            return sharedCheck().get(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the running or recently finished check, or starts a new one.
     * A check that is still stuck is reused rather than piling up another one behind it.
     */
    private synchronized CompletableFuture<Boolean> sharedCheck() {
        long now = System.currentTimeMillis();
        boolean expired = currentCheck == null
                || (currentCheck.isDone() && now - currentCheckStartedAt >= CACHE_TTL_MS);
        if (expired) {
            currentCheck = CompletableFuture.supplyAsync(this::checkConnection, executor);
            currentCheckStartedAt = now;
        }
        return currentCheck;
    }

    private boolean checkConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_TIMEOUT_SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
