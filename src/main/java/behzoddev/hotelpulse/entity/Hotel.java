package behzoddev.hotelpulse.entity;

import behzoddev.hotelpulse.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "hotels")
@Getter
@Setter
@NoArgsConstructor
public class Hotel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String city;

    @Column(name = "rooms_count", nullable = false)
    private int roomsCount;

    @Column(name = "exely_property_id", length = 64)
    private String exelyPropertyId;

    /** Bazada shifrlangan holda saqlanadi; sahifalarga hech qachon to'liq chiqarilmaydi. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "exely_api_key", length = 1024)
    private String exelyApiKey;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isExelyConnected() {
        return exelyApiKey != null && !exelyApiKey.isBlank();
    }
}
