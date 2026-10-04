package behzoddev.hotelpulse.security;

import behzoddev.hotelpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsername(username.trim())
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("Foydalanuvchi topilmadi"));
    }
}
