package com.gacs.backend;

import com.gacs.backend.dto.UserDto;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.AdminService;
import com.gacs.backend.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AdminService adminService;

    @Test
    void setBanned_True_ShouldSaveFlagAndRevokeTokens() {
        User target = new User("operator@datacenter.io", "pass", "Operator", "ROLE_USER");
        target.setId(2L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.save(target)).thenReturn(target);

        UserDto result = adminService.setBanned(2L, true, "admin@datacenter.io");

        assertTrue(result.isBanned());
        verify(refreshTokenService).revokeAllUserTokens(target);
    }

    @Test
    void setBanned_ShouldThrow_WhenAdminBansSelf() {
        User self = new User("Admin@DataCenter.io", "pass", "Admin", "ROLE_ADMIN");
        self.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adminService.setBanned(1L, true, "admin@datacenter.io"));

        assertEquals("You cannot ban your own account.", ex.getMessage());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(refreshTokenService);
    }
}
