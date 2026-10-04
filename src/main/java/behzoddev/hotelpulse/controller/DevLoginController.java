package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.security.DbUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * FAQAT LOKAL ISHLAB CHIQISH UCHUN: parolsiz kirish — /dev-login?user=owner.
 * Ikki himoya: (1) faqat "local" Spring profili yoqilganda bean yaratiladi
 * (production'da bu controller umuman mavjud emas — URL 404 qaytaradi),
 * (2) so'rov faqat shu kompyuterning o'zidan (loopback) qabul qilinadi.
 * Maqsad — sahifalarni brauzerda tezkor tekshirish (parol bilan ovora bo'lmasdan).
 */
@Slf4j
@Profile("local")
@Controller
@RequiredArgsConstructor
public class DevLoginController {

    private final DbUserDetailsService userDetailsService;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    @GetMapping("/dev-login")
    public String devLogin(@RequestParam(defaultValue = "owner") String user,
                           HttpServletRequest request, HttpServletResponse response) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        UserDetails details = userDetailsService.loadUserByUsername(user);
        if (!details.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Foydalanuvchi bloklangan");
        }
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        log.warn("DEV-LOGIN: '{}' sifatida parolsiz kirildi (local profil)", user);
        return "redirect:/";
    }

    private static boolean isLoopback(String remoteAddr) {
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
