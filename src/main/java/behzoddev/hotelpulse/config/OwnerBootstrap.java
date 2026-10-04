package behzoddev.hotelpulse.config;

import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Bazada birorta OWNER bo'lmasa (birinchi ishga tushish), platforma egasini
 * yaratadi. OWNER_PASSWORD berilmasa — tasodifiy parol generatsiya qilib,
 * logga bir marta chiqaradi (keyin profildan almashtirish kerak).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.owner.username:owner}")
    private String ownerUsername;

    @Value("${app.owner.password:}")
    private String ownerPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.OWNER)) {
            return;
        }
        String password = ownerPassword;
        boolean generated = password == null || password.isBlank();
        if (generated) {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        User owner = new User();
        owner.setUsername(ownerUsername);
        owner.setPassword(passwordEncoder.encode(password));
        owner.setFullName("Platforma egasi");
        owner.setRole(Role.OWNER);
        userRepository.save(owner);

        if (generated) {
            log.warn("OWNER yaratildi: login='{}', vaqtinchalik parol='{}' — kirgandan keyin almashtiring!", ownerUsername, password);
        } else {
            log.info("OWNER yaratildi: login='{}' (parol OWNER_PASSWORD'dan olindi)", ownerUsername);
        }
    }
}
