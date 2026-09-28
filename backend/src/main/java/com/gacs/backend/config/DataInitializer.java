package com.gacs.backend.config;

import com.gacs.backend.model.User;
import com.gacs.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        String adminEmail = "admin@datacenter.io";
        Optional<User> adminOpt = userRepository.findByEmail(adminEmail);

        if (adminOpt.isEmpty()) {
            User admin = new User(
                    adminEmail,
                    passwordEncoder.encode("Admin@2026!"),
                    "System Administrator",
                    "ROLE_ADMIN"
            );
            admin.setVerified(true);
            userRepository.save(admin);
            logger.info("Default administrator account created: {} (ROLE_ADMIN)", adminEmail);
        } else {
            User admin = adminOpt.get();
            if (!"ROLE_ADMIN".equals(admin.getRole()) || !admin.isVerified()) {
                admin.setRole("ROLE_ADMIN");
                admin.setVerified(true);
                userRepository.save(admin);
                logger.info("Existing admin account updated: {} (ROLE_ADMIN, verified)", adminEmail);
            }
        }
    }
}
