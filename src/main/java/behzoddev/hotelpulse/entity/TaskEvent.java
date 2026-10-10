package behzoddev.hotelpulse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Topshiriq tarixi: kim, qachon, nima qildi va qayerdan (sayt yoki bot). */
@Entity
@Table(name = "task_events")
@Getter
@Setter
@NoArgsConstructor
public class TaskEvent {

    public static final String SITE = "SITE";
    public static final String BOT = "BOT";
    public static final String SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskAction action;

    @Column(nullable = false, length = 8)
    private String channel = SITE;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
