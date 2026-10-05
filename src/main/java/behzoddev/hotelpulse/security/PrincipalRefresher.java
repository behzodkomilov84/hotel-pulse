package behzoddev.hotelpulse.security;

import behzoddev.hotelpulse.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

/** Joriy foydalanuvchi o'z ma'lumotini (ism, login) o'zgartirganda — sessiyadagi ma'lumotni yangilaydi (qayta kirmasdan). */
@Component
public class PrincipalRefresher {

    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public void refresh(User user, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        CustomUserDetails fresh = new CustomUserDetails(user);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                fresh, current == null ? null : current.getCredentials(), fresh.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
