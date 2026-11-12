package com.gacs.backend;

import com.gacs.backend.model.User;
import com.gacs.backend.repository.RefreshTokenRepository;
import com.gacs.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ban and unban end to end: the admin endpoint, the stored flag, the user list and login. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BanIntegrationTest {

    private static final String PASSWORD = "Correct-horse-1";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordEncoder encoder;

    private User admin;
    private User target;

    @BeforeEach
    void setup() {
        admin = save("ROLE_ADMIN");
        target = save("ROLE_USER");
    }

    @AfterEach
    void cleanup() {
        for (User u : java.util.List.of(admin, target)) {
            refreshTokens.deleteAll(refreshTokens.findAllByUser(u));
            users.delete(u);
        }
    }

    private User save(String role) {
        User u = new User(UUID.randomUUID() + "@test.io", encoder.encode(PASSWORD), "Test " + role, role);
        u.setVerified(true);
        return users.save(u);
    }

    private ResultActions login(User u) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions setBanned(User who, boolean banned) throws Exception {
        return mvc.perform(patch("/api/admin/users/" + who.getId() + "/ban")
                .with(user(admin.getEmail()).roles("ADMIN"))
                .contentType(APPLICATION_JSON)
                .content("{\"banned\":" + banned + "}"));
    }

    @Test
    void banBlocksLoginAndUnbanRestoresIt() throws Exception {
        login(target).andExpect(status().isOk());

        setBanned(target, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(target.getId()))
                .andExpect(jsonPath("$.data.banned").value(true));

        mvc.perform(get("/api/admin/users").with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + target.getId() + ")].banned").value(true));

        login(target)
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value("This account is disabled. Contact an administrator."));

        setBanned(target, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.banned").value(false));

        login(target).andExpect(status().isOk());
    }

    @Test
    void adminCannotBanSelf() throws Exception {
        setBanned(admin, true).andExpect(status().isBadRequest());
        login(admin).andExpect(status().isOk());
    }

    @Test
    void standardUserCannotBan() throws Exception {
        mvc.perform(patch("/api/admin/users/" + admin.getId() + "/ban")
                        .with(user(target.getEmail()).roles("USER"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"banned\":true}"))
                .andExpect(status().isForbidden());
    }
}
