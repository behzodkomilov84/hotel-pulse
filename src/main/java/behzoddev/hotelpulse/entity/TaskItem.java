package behzoddev.hotelpulse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Topshiriqqa ilova qilingan ro'yxat qatori — bitta qarzdor yashash (topshiriq berilgan paytdagi holat). */
@Entity
@Table(name = "task_items")
@Getter
@Setter
@NoArgsConstructor
public class TaskItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id")
    private Task task;

    @Column(nullable = false)
    private int position;

    @Column(name = "booking_number", length = 64)
    private String bookingNumber;

    @Column(name = "guest_name", length = 255)
    private String guestName;

    @Column(length = 255)
    private String source;

    /** Qarz toifasi nomi: Ketgan, Yashayapti, Vyselenie qilinmagan. */
    @Column(length = 32)
    private String category;

    private LocalDate arrival;

    private LocalDate departure;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal paid;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal debt;

    /** Ketish sanasidan topshiriq berilgan kungacha o'tgan kunlar. */
    @Column(name = "age_days", nullable = false)
    private int ageDays;

    @Column(nullable = false)
    private boolean done;

    @Column(name = "done_at")
    private LocalDateTime doneAt;
}
