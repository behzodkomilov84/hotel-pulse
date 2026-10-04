package behzoddev.hotelpulse.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico", "/login", "/error").permitAll()
                        // Faqat "local" profilda mavjud (DevLoginController); boshqa
                        // muhitlarda bu manzilda controller yo'q — 404.
                        .requestMatchers("/dev-login").permitAll()
                        // Mehmonxona/foydalanuvchi boshqaruvi — faqat platforma egasi.
                        .requestMatchers("/admin/**").hasRole("OWNER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                // Sessiya eskirgan (masalan, sahifa uzoq ochiq qolib ketgan) bo'lsa, forma
                // yuborilganda CSRF tokeni mos kelmaydi — "ruxsat yo'q" (403) o'rniga
                // qayta kirishga tushunarli xabar bilan yo'naltiriladi.
                .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, denied) -> {
                    if (denied instanceof CsrfException) {
                        response.sendRedirect(request.getContextPath() + "/login?expired");
                    } else {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    }
                }))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
