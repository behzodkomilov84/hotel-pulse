package behzoddev.hotelpulse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Mehmonxona xodimiga berilgan topshiriq (odatda tahlil tavsiyasidan). */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
public class Task {

    /** Topshiriq qayerdan kelgan. */
    public static final String SOURCE_DEBT_ANALYSIS = "DEBT_ANALYSIS";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hotel_id")
    private Hotel hotel;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Bo'lim: Resepshn, Buxgalteriya, Rahbariyat (tahlil tavsiyasidagi). */
    @Column(length = 64)
    private String department;

    /** Bo'lim (o'chirilsa — null, nomi department maydonida qoladi). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department departmentRef;

    /** Tahlildagi "birinchi navbatda tekshirish" bronidan berilgan bo'lsa — bron raqami. */
    @Column(name = "booking_number", length = 64)
    private String bookingNumber;

    @Column(nullable = false, length = 32)
    private String source = SOURCE_MANUAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_id")
    private User assignedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.NEW;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Xodim "bajarildi" degan vaqt. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Tasdiqlangan yoki qaytarilgan vaqt. */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "last_reminded_on")
    private LocalDate lastRemindedOn;

    /** Muddati o'tgan: hali ochiq va muddat kuni o'tib ketgan. */
    public boolean isOverdue(LocalDate today) {
        return status.isOpen() && dueDate != null && dueDate.isBefore(today);
    }

    /** Muddatdan necha kun o'tgan (o'tmagan bo'lsa — 0). */
    public long daysOverdue(LocalDate today) {
        return isOverdue(today) ? java.time.temporal.ChronoUnit.DAYS.between(dueDate, today) : 0;
    }

    public boolean isDueToday(LocalDate today) {
        return status.isOpen() && today.equals(dueDate);
    }
}
