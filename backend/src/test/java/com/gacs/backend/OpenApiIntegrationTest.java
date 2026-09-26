package com.gacs.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("OpenAPI JSON specification should be publicly accessible and return 200 OK")
    void openApiDocs_ShouldReturnJsonWithGacsMetadata() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("Grid Aware Compute Scheduler (GACS) REST API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.paths['/register']").exists())
                .andExpect(jsonPath("$.paths['/login']").exists())
                .andExpect(jsonPath("$.paths['/verify-otp']").exists())
                .andExpect(jsonPath("$.paths['/resend-otp']").exists())
                .andExpect(jsonPath("$.paths['/refresh']").exists())
                .andExpect(jsonPath("$.paths['/me']").exists())
                .andExpect(jsonPath("$.paths['/logout']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users/{id}/role']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists())
                .andExpect(jsonPath("$.components.securitySchemes.cookieAuth").exists());
    }

    @Test
    @DisplayName("Swagger UI endpoint should be publicly accessible without authentication")
    void swaggerUi_ShouldBeAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
