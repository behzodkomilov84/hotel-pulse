package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final HotelRepository hotelRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Foydalanuvchi topilmadi"));
        user.getHotels().size();
        return user;
    }

    @Transactional
    public User create(String username, String password, String fullName, String phone,
                       Role role, List<Long> hotelIds) {
        if (username == null || !username.trim().matches("[A-Za-z0-9_.\\-]{3,64}")) {
            throw new IllegalArgumentException("Login 3–64 belgidan iborat bo'lsin (lotin harflari, raqamlar, _ . -)");
        }
        if (userRepository.existsByUsername(username.trim())) {
            throw new IllegalArgumentException("Bu login band");
        }
        validatePassword(password);
        if (role == null || role == Role.OWNER) {
            // Platforma egasi bitta — OwnerBootstrap yaratadi; paneldan qo'shilmaydi.
            throw new IllegalArgumentException("Rolni tanlang");
        }
        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(password));
        user.setFullName(blankToNull(fullName));
        user.setPhone(blankToNull(phone));
        user.setRole(role);
        user.setHotels(loadHotels(hotelIds));
        return userRepository.save(user);
    }

    @Transactional
    public void update(Long id, String fullName, String phone, Role role, boolean enabled,
                       List<Long> hotelIds, String newPassword) {
        update(id, null, fullName, phone, role, enabled, hotelIds, newPassword);
    }

    /** @param username yangi login (null — o'zgarmaydi) */
    @Transactional
    public User update(Long id, String username, String fullName, String phone, Role role, boolean enabled,
                       List<Long> hotelIds, String newPassword) {
        User user = getById(id);
        if (username != null && !username.trim().equals(user.getUsername())) {
            String login = username.trim();
            if (!login.matches("[A-Za-z0-9_.\\-]{3,64}")) {
                throw new IllegalArgumentException("Login 3–64 belgidan iborat bo'lsin (lotin harflari, raqamlar, _ . -)");
            }
            if (userRepository.existsByUsername(login)) {
                throw new IllegalArgumentException("Bu login band");
            }
            user.setUsername(login);
        }
        user.setFullName(blankToNull(fullName));
        user.setPhone(blankToNull(phone));
        if (user.getRole() != Role.OWNER) {
            if (role == null || role == Role.OWNER) {
                throw new IllegalArgumentException("Rolni tanlang");
            }
            user.setRole(role);
            user.setEnabled(enabled);
            user.setHotels(loadHotels(hotelIds));
            // Mehmonxonadan chiqarilgan xodim o'sha mehmonxona bo'limlaridan ham chiqadi.
            user.getDepartments().removeIf(d -> user.getHotels().stream().noneMatch(h -> h.getId().equals(d.getHotel().getId())));
        }
        if (newPassword != null && !newPassword.isBlank()) {
            validatePassword(newPassword);
            user.setPassword(passwordEncoder.encode(newPassword));
        }
        return user;
    }

    /** Profil sahifasidan: foydalanuvchi o'z ismi va telefonini o'zgartiradi (login/rol/mehmonxonalar — yo'q). */
    @Transactional
    public User updateOwnProfile(Long userId, String fullName, String phone) {
        String name = blankToNull(fullName);
        String tel = blankToNull(phone);
        if (name != null && name.length() > 150) {
            throw new IllegalArgumentException("F.I.Sh. 150 belgidan oshmasin");
        }
        if (tel != null && !tel.matches("\\+?[0-9 ()\\-]{7,32}")) {
            throw new IllegalArgumentException("Telefon raqami noto'g'ri (masalan: +998 90 123 45 67)");
        }
        User user = getById(userId);
        user.setFullName(name);
        user.setPhone(tel);
        return user;
    }

    /** Profil sahifasidan parol almashtirish — joriy parol tekshiriladi. */
    @Transactional
    public void changeOwnPassword(Long userId, String currentPassword, String newPassword, String confirmPassword) {
        User user = getById(userId);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Joriy parol noto'g'ri");
        }
        validatePassword(newPassword);
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("Yangi parol va uning takrori bir xil emas");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new IllegalArgumentException("Yangi parol eskisidan farq qilishi kerak");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
    }

    private LinkedHashSet<Hotel> loadHotels(List<Long> hotelIds) {
        return hotelIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(hotelRepository.findAllById(hotelIds));
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Parol kamida " + MIN_PASSWORD_LENGTH + " belgidan iborat bo'lsin");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
