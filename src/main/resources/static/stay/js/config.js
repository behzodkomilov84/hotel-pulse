/* ========= SOZLAMALAR / НАСТРОЙКИ =========
   So'rovlar qaysi pochtaga kelishi: FormSubmit.co orqali.
   Birinchi so'rovdan keyin shu manzilga tasdiqlash xati keladi — "Activate" bosilsa, keyingi so'rovlar to'g'ridan-to'g'ri keladi.
   Tasdiqlangandan keyin FormSubmit bergan maxfiy kodni (masalan "a1b2c3...") RECIPIENT ga qo'yish mumkin — shunda pochta manzili sayt kodida ko'rinmaydi. */
const CONFIG = {
  RECIPIENT: "info@chorvoqstay.uz",
  TELEGRAM_ENDPOINT: "/api/stay/requests",  // HotelPulse: so'rov bazaga yoziladi va Telegram'ga yuboriladi
  PHONE: "+998 90 938-17-38"
};
