package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Department;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.DepartmentRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mehmonxona jamoasi: bo'limlar va xodimlar. Mehmonxona egasi, boshqaruv kompaniyasi (o'z mehmonxonalarida)
 * va platforma egasi (hammasida) bo'lim yaratadi, xodim qo'shadi va bo'limlarga biriktiradi.
 * Xodim qaysi mehmonxonada ishlashi — uning bo'limlaridan kelib chiqadi (bo'lim mehmonxonaga tegishli).
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    static final int NAME_MAX = 64;

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final HotelService hotelService;
    private final UserService userService;
    private final Clock clock;

    /** Mehmonxona va uning bo'limlari (har bo'limdagi xodimlar soni bilan). */
    public record HotelDepartments(Hotel hotel, List<Department> departments, Map<Long, Long> members) {
    }

    public static boolean canManage(CustomUserDetails user) {
        return TaskService.ASSIGNER_ROLES.contains(user.getRole());
    }

    // ---------------------------------------------------------------- Bo'limlar

    @Transactional(readOnly = true)
    public List<HotelDepartments> departments(CustomUserDetails user) {
        requireManager(user);
        List<Hotel> hotels = hotelService.accessibleHotels(user);
        List<Long> ids = hotels.stream().map(Hotel::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Department>> byHotel = departmentRepository.findAllByHotelIds(ids).stream()
                .collect(Collectors.groupingBy(d -> d.getHotel().getId()));
        Map<Long, Long> members = new HashMap<>();
        for (Object[] r : departmentRepository.countMembers(ids)) {
            members.put((Long) r[0], ((Number) r[1]).longValue());
        }
        List<HotelDepartments> result = new ArrayList<>();
        for (Hotel h : hotels) {
            result.add(new HotelDepartments(h, byHotel.getOrDefault(h.getId(), List.of()), members));
        }
        return result;
    }

    /** Mehmonxona bo'limlari (topshiriq formasi uchun) — ruxsat tekshirilmaydi, chaqiruvchi tekshiradi. */
    @Transactional(readOnly = true)
    public List<Department> departmentsOf(Long hotelId) {
        return departmentRepository.findAllByHotelIds(List.of(hotelId));
    }

    @Transactional
    public Department createDepartment(CustomUserDetails user, Long hotelId, String name) {
        requireManager(user);
        Hotel hotel = hotelService.getAccessible(user, hotelId);
        String n = validName(name);
        if (departmentRepository.findByHotelAndName(hotel.getId(), n).isPresent()) {
            throw new IllegalArgumentException("\"" + n + "\" bo'limi bu mehmonxonada bor");
        }
        Department d = new Department();
        d.setHotel(hotel);
        d.setName(n);
        d.setCreatedAt(LocalDateTime.now(clock));
        return departmentRepository.save(d);
    }

    @Transactional
    public Department renameDepartment(CustomUserDetails user, Long id, String name) {
        Department d = accessibleDepartment(user, id);
        String n = validName(name);
        departmentRepository.findByHotelAndName(d.getHotel().getId(), n)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("\"" + n + "\" bo'limi bu mehmonxonada bor");
                });
        d.setName(n);
        return d;
    }

    /** Bo'lim o'chiriladi: xodimlar bo'limdan chiqadi (o'zlari qoladi), topshiriqlarda bo'lim nomi saqlanadi. */
    @Transactional
    public String deleteDepartment(CustomUserDetails user, Long id) {
        Department d = accessibleDepartment(user, id);
        String name = d.getName();
        departmentRepository.delete(d);
        return name;
    }

    // ---------------------------------------------------------------- Xodimlar

    /** Foydalanuvchi boshqaradigan mehmonxonalardagi xodimlar. */
    @Transactional(readOnly = true)
    public List<User> staff(CustomUserDetails user) {
        requireManager(user);
        List<Long> ids = hotelService.accessibleHotels(user).stream().map(Hotel::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllByRoleAndHotelIds(Role.HOTEL_STAFF, ids).stream()
                .sorted(Comparator.comparing((User u) -> u.getFullName() == null ? u.getUsername() : u.getFullName(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public User staffMember(CustomUserDetails user, Long id) {
        return editableStaff(user, id);
    }

    @Transactional
    public User createStaff(CustomUserDetails user, String username, String password, String fullName, String phone,
                            List<Long> departmentIds) {
        requireManager(user);
        List<Department> depts = accessibleDepartments(user, departmentIds);
        User created = userService.create(username, password, fullName, phone, Role.HOTEL_STAFF, hotelIds(depts));
        created.setDepartments(new LinkedHashSet<>(depts));
        return created;
    }

    @Transactional
    public User updateStaff(CustomUserDetails user, Long id, String username, String fullName, String phone,
                            boolean enabled, List<Long> departmentIds, String newPassword) {
        editableStaff(user, id);
        List<Department> depts = accessibleDepartments(user, departmentIds);
        User saved = userService.update(id, username, fullName, phone, Role.HOTEL_STAFF, enabled, hotelIds(depts), newPassword);
        saved.setDepartments(new LinkedHashSet<>(depts));
        return saved;
    }

    // ---------------------------------------------------------------- yordamchilar

    /**
     * Tahrirlash mumkin: faqat "Mehmonxona xodimi" va uning barcha mehmonxonalari shu foydalanuvchiga ochiq
     * (boshqa egasining xodimini o'zgartirib bo'lmasin).
     */
    private User editableStaff(CustomUserDetails user, Long id) {
        requireManager(user);
        User staff = userRepository.findWithTeam(id).orElseThrow(() -> new NotFoundException("Xodim topilmadi"));
        if (staff.getRole() != Role.HOTEL_STAFF) {
            throw new AccessDeniedException("Faqat mehmonxona xodimlarini tahrirlash mumkin");
        }
        Set<Long> accessible = hotelService.accessibleHotels(user).stream().map(Hotel::getId).collect(Collectors.toSet());
        if (staff.getHotels().isEmpty() && !user.isOwner()
                || !staff.getHotels().stream().allMatch(h -> accessible.contains(h.getId()))) {
            throw new AccessDeniedException("Bu xodim sizning mehmonxonangizda emas");
        }
        return staff;
    }

    private List<Department> accessibleDepartments(CustomUserDetails user, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Kamida bitta bo'limni tanlang");
        }
        Set<Long> accessible = hotelService.accessibleHotels(user).stream().map(Hotel::getId).collect(Collectors.toSet());
        List<Department> depts = departmentRepository.findAllById(new LinkedHashSet<>(ids));
        if (depts.size() != new LinkedHashSet<>(ids).size()
                || !depts.stream().allMatch(d -> accessible.contains(d.getHotel().getId()))) {
            throw new AccessDeniedException("Bo'lim sizning mehmonxonangizda emas");
        }
        return depts;
    }

    private Department accessibleDepartment(CustomUserDetails user, Long id) {
        requireManager(user);
        Department d = departmentRepository.findFull(id).orElseThrow(() -> new NotFoundException("Bo'lim topilmadi"));
        hotelService.getAccessible(user, d.getHotel().getId());
        return d;
    }

    private static List<Long> hotelIds(List<Department> depts) {
        return depts.stream().map(d -> d.getHotel().getId()).distinct().toList();
    }

    private static String validName(String name) {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > NAME_MAX) {
            throw new IllegalArgumentException("Bo'lim nomi 1–" + NAME_MAX + " belgi bo'lsin");
        }
        return n;
    }

    private static void requireManager(CustomUserDetails user) {
        if (!canManage(user)) {
            throw new AccessDeniedException("Ruxsat yo'q");
        }
    }
}
