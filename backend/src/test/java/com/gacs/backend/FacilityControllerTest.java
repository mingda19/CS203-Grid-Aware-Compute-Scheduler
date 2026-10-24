package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.FacilityController;
import com.gacs.backend.dto.FacilityRequestDto;
import com.gacs.backend.dto.FacilityResponseDto;
import com.gacs.backend.exception.GlobalExceptionHandler;
import com.gacs.backend.service.FacilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FacilityController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class FacilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    private FacilityService facilityService;

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("GET /api/facilities/me returns 200 with facility configuration")
    void getFacility_Success() throws Exception {
        FacilityResponseDto response = FacilityResponseDto.builder()
                .id(1L)
                .ownerEmail("operator@datacenter.io")
                .name("Austin Primary Cluster")
                .maxCpuHours(256.0)
                .maxGpuHours(64.0)
                .location("LZ_NORTH")
                .updatedAt(LocalDateTime.now())
                .build();

        when(facilityService.getOrCreateFacility("operator@datacenter.io")).thenReturn(response);

        mockMvc.perform(get("/api/facilities/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Austin Primary Cluster"))
                .andExpect(jsonPath("$.data.maxCpuHours").value(256.0))
                .andExpect(jsonPath("$.data.maxGpuHours").value(64.0))
                .andExpect(jsonPath("$.data.location").value("LZ_NORTH"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("PUT /api/facilities/me updates facility and returns 200")
    void updateFacility_Success() throws Exception {
        FacilityRequestDto request = FacilityRequestDto.builder()
                .name("Dallas High-Density Pod")
                .maxCpuHours(512.0)
                .maxGpuHours(128.0)
                .location("LZ_HOUSTON")
                .build();

        FacilityResponseDto response = FacilityResponseDto.builder()
                .id(1L)
                .ownerEmail("operator@datacenter.io")
                .name("Dallas High-Density Pod")
                .maxCpuHours(512.0)
                .maxGpuHours(128.0)
                .location("LZ_HOUSTON")
                .updatedAt(LocalDateTime.now())
                .build();

        when(facilityService.updateFacility(any(FacilityRequestDto.class), eq("operator@datacenter.io")))
                .thenReturn(response);

        mockMvc.perform(put("/api/facilities/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Dallas High-Density Pod"))
                .andExpect(jsonPath("$.data.maxCpuHours").value(512.0))
                .andExpect(jsonPath("$.data.maxGpuHours").value(128.0))
                .andExpect(jsonPath("$.data.location").value("LZ_HOUSTON"));
    }
}
