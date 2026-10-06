package com.gacs.backend.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DotenvLoaderTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("spring.datasource.url");
        System.clearProperty("spring.datasource.username");
        System.clearProperty("spring.datasource.password");
    }

    @Test
    void testConfigureJdbcFromDatabaseUrlWithSpecialCharactersInPassword() {
        String dbUrl = "postgresql://postgres.uohlmgqkfxulvamevpoi:cqt3i%*HqLK#G@1m%@aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres";
        DotenvLoader.configureJdbcFromDatabaseUrl(dbUrl);

        assertEquals("postgres.uohlmgqkfxulvamevpoi", System.getProperty("spring.datasource.username"));
        assertEquals("cqt3i%*HqLK#G@1m%", System.getProperty("spring.datasource.password"));
        assertEquals("jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0",
                System.getProperty("spring.datasource.url"));
    }

    @Test
    void testConfigureJdbcFromDatabaseUrlWithQuotedString() {
        String dbUrl = "\"postgresql://myuser:secret123@localhost:5432/testdb\"";
        DotenvLoader.configureJdbcFromDatabaseUrl(dbUrl);

        assertEquals("myuser", System.getProperty("spring.datasource.username"));
        assertEquals("secret123", System.getProperty("spring.datasource.password"));
        assertEquals("jdbc:postgresql://localhost:5432/testdb?sslmode=require&prepareThreshold=0",
                System.getProperty("spring.datasource.url"));
    }

    @Test
    void testDecodeUserInfoWithValidPercentEncoding() {
        assertEquals("hello world", DotenvLoader.decodeUserInfo("hello%20world"));
        assertEquals("user@domain", DotenvLoader.decodeUserInfo("user%40domain"));
    }

    @Test
    void testDecodeUserInfoWithInvalidPercentSequences() {
        assertEquals("cqt3i%*HqLK#G@1m%", DotenvLoader.decodeUserInfo("cqt3i%*HqLK#G@1m%"));
        assertEquals("test%", DotenvLoader.decodeUserInfo("test%"));
        assertEquals("%test", DotenvLoader.decodeUserInfo("%test"));
    }
}
