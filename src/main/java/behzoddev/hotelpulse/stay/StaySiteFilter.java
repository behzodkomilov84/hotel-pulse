package behzoddev.hotelpulse.stay;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chorvoq Stay mehmonlar sayti domeni (chorvoqstay.uz): sayt fayllari /stay/ ostida turadi, shu domendan esa
 * ildizda ochiladi ("/" → /stay/index.html, "/css/x.css" → /stay/css/x.css). Sayt API'si (/api/stay/**) o'zgarishsiz.
 * HotelPulse'ning qolgan sahifalari (kirish, dashboard, admin) bu domendan ko'rinmaydi — 404.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StaySiteFilter extends OncePerRequestFilter {

    /** Sayt fayllari: faqat shular (yo'l chiqib ketishiga — ".." — yo'l qo'yilmaydi). */
    private static final Pattern ASSET = Pattern.compile("^/(css|js)/[A-Za-z0-9_./-]+\\.(css|js)$|^/(og\\.jpg|robots\\.txt|sitemap\\.xml)$");

    private final Set<String> hosts;

    public StaySiteFilter(@Value("${app.stay.hosts:chorvoqstay.uz,www.chorvoqstay.uz}") String hosts) {
        this.hosts = Arrays.stream(hosts.split(","))
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toSet());
    }

    boolean isStayHost(HttpServletRequest request) {
        String host = request.getServerName();
        return host != null && hosts.contains(host.toLowerCase(Locale.ROOT));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isStayHost(request)) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/stay/") || path.equals("/error")) {
            chain.doFilter(request, response);
        } else if (path.isEmpty() || path.equals("/") || path.equals("/index.html")) {
            request.getRequestDispatcher("/stay/index.html").forward(request, response);
        } else if (!path.contains("..") && ASSET.matcher(path).matches()) {
            request.getRequestDispatcher("/stay" + path).forward(request, response);
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
