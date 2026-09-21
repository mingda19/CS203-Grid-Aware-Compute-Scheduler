package com.gacs.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class DotenvLoader {

    private static final Logger logger = LoggerFactory.getLogger(DotenvLoader.class);

    public static void load() {
        File envFile = findDotenvFile();
        if (envFile == null || !envFile.exists()) {
            logger.info("No .env file found; using standard environment/property configuration.");
            return;
        }

        logger.info("Loading environment variables from: {}", envFile.getAbsolutePath());
        try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equalsIdx = line.indexOf('=');
                if (equalsIdx <= 0) {
                    continue;
                }
                String key = line.substring(0, equalsIdx).trim();
                String value = line.substring(equalsIdx + 1).trim();

                // Strip quotes if present
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                if (System.getProperty(key) == null && System.getenv(key) == null) {
                    System.setProperty(key, value);
                }

                // Handle DATABASE_URL conversion for JDBC
                if ("DATABASE_URL".equalsIgnoreCase(key)) {
                    configureJdbcFromDatabaseUrl(value);
                }

                // Also map mail properties if found in .env
                if ("MAIL_USERNAME".equalsIgnoreCase(key)) {
                    System.setProperty("spring.mail.username", value);
                }
                if ("MAIL_PASSWORD".equalsIgnoreCase(key)) {
                    System.setProperty("spring.mail.password", value);
                }
                if ("CORS_ALLOWED_ORIGINS".equalsIgnoreCase(key)) {
                    System.setProperty("cors.allowed-origins", value);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not parse .env file: {}", e.getMessage());
        }
    }

    private static File findDotenvFile() {
        File f1 = new File(".env");
        if (f1.exists()) return f1;
        File f2 = new File("backend/.env");
        if (f2.exists()) return f2;
        File f3 = new File("../backend/.env");
        if (f3.exists()) return f3;
        return null;
    }

    private static void configureJdbcFromDatabaseUrl(String dbUrl) {
        try {
            if (dbUrl == null || dbUrl.isBlank()) {
                return;
            }
            if (dbUrl.startsWith("jdbc:")) {
                System.setProperty("spring.datasource.url", dbUrl);
                return;
            }
            if (dbUrl.startsWith("postgresql://") || dbUrl.startsWith("postgres://")) {
                String withoutScheme = dbUrl;
                if (withoutScheme.startsWith("postgresql://")) {
                    withoutScheme = withoutScheme.substring("postgresql://".length());
                } else if (withoutScheme.startsWith("postgres://")) {
                    withoutScheme = withoutScheme.substring("postgres://".length());
                }

                // The host starts after the LAST '@' to safely handle passwords with '@'
                int lastAt = withoutScheme.lastIndexOf('@');
                String hostAndDb;
                if (lastAt != -1) {
                    String userInfo = withoutScheme.substring(0, lastAt);
                    hostAndDb = withoutScheme.substring(lastAt + 1);

                    int colon = userInfo.indexOf(':');
                    if (colon != -1) {
                        String user = userInfo.substring(0, colon);
                        String pass = userInfo.substring(colon + 1);
                        System.setProperty("spring.datasource.username", user);
                        System.setProperty("spring.datasource.password", pass);
                    } else {
                        System.setProperty("spring.datasource.username", userInfo);
                    }
                } else {
                    hostAndDb = withoutScheme;
                }

                String sslParam = hostAndDb.contains("?") ? "" : "?sslmode=require";
                String jdbcUrl = "jdbc:postgresql://" + hostAndDb + sslParam;
                System.setProperty("spring.datasource.url", jdbcUrl);
                logger.info("Successfully configured JDBC datasource from DATABASE_URL: jdbc:postgresql://{}", hostAndDb);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse DATABASE_URL into JDBC format: {}", e.getMessage());
        }
    }
}
