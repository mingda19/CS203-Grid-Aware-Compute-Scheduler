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
            configureFromSystemEnv();
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
        File f = new File("../.env");
        if (f.exists()) return f;
        return null;
    }

    private static void configureFromSystemEnv() {
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl != null && !dbUrl.isBlank() && System.getProperty("spring.datasource.url") == null) {
            configureJdbcFromDatabaseUrl(dbUrl);
        }
    }

    static void configureJdbcFromDatabaseUrl(String dbUrl) {
        try {
            if (dbUrl == null || dbUrl.isBlank()) {
                return;
            }
            dbUrl = dbUrl.trim();
            if ((dbUrl.startsWith("\"") && dbUrl.endsWith("\"")) ||
                (dbUrl.startsWith("'") && dbUrl.endsWith("'"))) {
                dbUrl = dbUrl.substring(1, dbUrl.length() - 1).trim();
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
                        String user = decodeUserInfo(userInfo.substring(0, colon));
                        String pass = decodeUserInfo(userInfo.substring(colon + 1));
                        System.setProperty("spring.datasource.username", user);
                        System.setProperty("spring.datasource.password", pass);
                    } else {
                        System.setProperty("spring.datasource.username", decodeUserInfo(userInfo));
                    }
                } else {
                    hostAndDb = withoutScheme;
                }

                StringBuilder jdbcUrlBuilder = new StringBuilder("jdbc:postgresql://").append(hostAndDb);
                String separator = hostAndDb.contains("?") ? "&" : "?";
                if (!hostAndDb.contains("sslmode=")) {
                    jdbcUrlBuilder.append(separator).append("sslmode=require");
                    separator = "&";
                }
                if (!hostAndDb.contains("prepareThreshold=")) {
                    jdbcUrlBuilder.append(separator).append("prepareThreshold=0");
                }
                String jdbcUrl = jdbcUrlBuilder.toString();
                System.setProperty("spring.datasource.url", jdbcUrl);
                logger.info("Successfully configured JDBC datasource from DATABASE_URL: jdbc:postgresql://{}", hostAndDb);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse DATABASE_URL into JDBC format: {}", e.getMessage());
        }
    }

    static String decodeUserInfo(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Value contains raw characters like '%' that are not valid hex escape sequences (e.g. '%*').
            return safeUrlDecode(value);
        }
    }

    private static String safeUrlDecode(String value) {
        StringBuilder sanitized = new StringBuilder();
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 < len && isHexDigit(value.charAt(i + 1)) && isHexDigit(value.charAt(i + 2))) {
                    sanitized.append('%');
                } else {
                    sanitized.append("%25");
                }
            } else {
                sanitized.append(c);
            }
        }
        try {
            return URLDecoder.decode(sanitized.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') ||
               (c >= 'a' && c <= 'f') ||
               (c >= 'A' && c <= 'F');
    }
}
