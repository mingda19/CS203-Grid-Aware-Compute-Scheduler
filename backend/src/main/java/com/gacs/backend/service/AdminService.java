package com.gacs.backend.service;

import com.gacs.backend.dto.UserDto;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminService {

    private final UserRepository userRepository;

    public AdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Retrieves all registered users in descending order of creation.
     */
    @Transactional
    public List<UserDto> getAllUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(UserDto::new)
                .toList();
    }

    /**
     * Updates the role of a user, preventing the authenticated admin from demoting themselves.
     */
    @Transactional
    public UserDto updateUserRole(Long userId, String newRole, String currentAdminEmail) {
        if (newRole == null || (!newRole.equals("ROLE_ADMIN") && !newRole.equals("ROLE_USER"))) {
            throw new IllegalArgumentException("Invalid role. Role must be either 'ROLE_ADMIN' or 'ROLE_USER'.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));

        // Safeguard: Prevent admin from demoting their own account
        if (user.getEmail().equalsIgnoreCase(currentAdminEmail) && !"ROLE_ADMIN".equals(newRole)) {
            throw new IllegalArgumentException("You cannot demote your own administrator account.");
        }

        user.setRole(newRole);
        User savedUser = userRepository.save(user);
        return new UserDto(savedUser);
    }
}
