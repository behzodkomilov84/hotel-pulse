package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.entity.TelegramLinkToken;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.TelegramLinkTokenRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.service.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/** Sayt foydalanuvchisi ↔ Telegram chat bog'lanishi. */
@Service
@RequiredArgsConstructor
public class TelegramLinkService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final TelegramLinkTokenRepository tokenRepository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * Yangi bir martalik token (eskilari bekor bo'ladi). Telegram start
     * parametri faqat [A-Za-z0-9_-] va 64 belgigacha — URL-safe base64 mos.
     */
    @Transactional
    public String createToken(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        tokenRepository.deleteForUserOrExpired(userId, now);
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        TelegramLinkToken t = new TelegramLinkToken();
        t.setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        t.setUserId(userId);
        t.setExpiresAt(now.plus(TOKEN_TTL));
        tokenRepository.save(t);
        return t.getToken();
    }

    /**
     * Botdagi /start &lt;token&gt;: token to'g'ri bo'lsa chat'ni foydalanuvchiga ulaydi.
     * Shu chat boshqa foydalanuvchiga ulangan bo'lsa — undan uziladi (bitta chat — bitta foydalanuvchi).
     */
    @Transactional
    public Optional<User> link(String token, long chatId) {
        Optional<TelegramLinkToken> found = tokenRepository.findById(token);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TelegramLinkToken t = found.get();
        tokenRepository.delete(t);
        if (t.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            return Optional.empty();
        }
        User user = userRepository.findById(t.getUserId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return Optional.empty();
        }
        userRepository.findByTelegramChatId(chatId)
                .filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> {
                    other.setTelegramChatId(null);
                    other.setTelegramLinkedAt(null);
                    userRepository.saveAndFlush(other);
                });
        user.setTelegramChatId(chatId);
        user.setTelegramLinkedAt(LocalDateTime.now(clock));
        return Optional.of(user);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByChat(long chatId) {
        return userRepository.findByTelegramChatId(chatId);
    }

    @Transactional
    public void unlinkUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Фойдаланувчи топилмади"));
        user.setTelegramChatId(null);
        user.setTelegramLinkedAt(null);
    }

    @Transactional
    public void unlinkChat(long chatId) {
        userRepository.findByTelegramChatId(chatId).ifPresent(u -> {
            u.setTelegramChatId(null);
            u.setTelegramLinkedAt(null);
        });
    }

    /** @return yangi holat */
    @Transactional
    public boolean setDailyReport(Long userId, boolean enabled) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Фойдаланувчи топилмади"));
        user.setTelegramDailyReport(enabled);
        return enabled;
    }
}
