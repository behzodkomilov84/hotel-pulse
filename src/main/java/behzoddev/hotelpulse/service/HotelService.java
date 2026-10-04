package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HotelService {

    private final HotelRepository hotelRepository;

    /** OWNER — hamma mehmonxonalar; qolganlar — faqat o'ziga biriktirilganlari. */
    @Transactional(readOnly = true)
    public List<Hotel> accessibleHotels(CustomUserDetails user) {
        return user.isOwner()
                ? hotelRepository.findAllByOrderByNameAsc()
                : hotelRepository.findAllByUserId(user.getId());
    }

    /** Mehmonxonaga kirish huquqini tekshiradi — boshqa mehmonxona id'sini URL'ga yozib ko'rishga qarshi. */
    @Transactional(readOnly = true)
    public Hotel getAccessible(CustomUserDetails user, Long hotelId) {
        if (!user.isOwner() && !hotelRepository.isUserLinked(user.getId(), hotelId)) {
            throw new AccessDeniedException("Bu mehmonxonaga ruxsat yo'q");
        }
        return getById(hotelId);
    }

    @Transactional(readOnly = true)
    public List<Hotel> findAll() {
        return hotelRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Hotel getById(Long id) {
        return hotelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Mehmonxona topilmadi"));
    }

    @Transactional
    public Hotel save(Long id, String name, String city, int roomsCount,
                      String exelyPropertyId, String exelyApiKey, boolean active) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Mehmonxona nomi bo'sh bo'lmasligi kerak");
        }
        if (roomsCount < 0) {
            throw new IllegalArgumentException("Xonalar soni manfiy bo'lmasligi kerak");
        }
        Hotel hotel = id == null ? new Hotel() : getById(id);
        hotel.setName(name.trim());
        hotel.setCity(blankToNull(city));
        hotel.setRoomsCount(roomsCount);
        hotel.setExelyPropertyId(blankToNull(exelyPropertyId));
        // Kalit maydoni bo'sh qoldirilsa — eskisi o'zgarmaydi (sahifada kalit
        // hech qachon ko'rsatilmaydi, shuning uchun har safar qayta kiritish shart emas).
        if (exelyApiKey != null && !exelyApiKey.isBlank()) {
            hotel.setExelyApiKey(exelyApiKey.trim());
        }
        hotel.setActive(active);
        return hotelRepository.save(hotel);
    }

    @Transactional
    public void removeExelyKey(Long id) {
        getById(id).setExelyApiKey(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
