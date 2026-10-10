package behzoddev.hotelpulse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Mehmonxona bo'limi (Resepshn, Buxgalteriya, ...) — xodimlar shunga biriktiriladi, topshiriq bo'lim bo'yicha beriladi. */
@Entity
@Table(name = "departments")
@Getter
@Setter
@NoArgsConstructor
public class Department {

    /** Yangi mehmonxonada tayyor turadigan bo'limlar (tahlil tavsiyalaridagi nomlar). */
    public static final java.util.List<String> DEFAULTS = java.util.List.of("Resepshn", "Buxgalteriya", "Rahbariyat");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hotel_id")
    private Hotel hotel;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
