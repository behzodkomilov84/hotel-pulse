#!/bin/bash
# hotel-pulse.uz uchun HTTPS'ni yoqadi (StudyGrow serveridagi umumiy nginx + Let's Encrypt).
#
#   ./scripts/enable-https.sh
#
# Qadamlar (har biri tekshiriladi, xato bo'lsa — HotelPulse nginx bloki olib tashlanib, to'xtaydi):
#   0. DNS tekshiruvi: hotel-pulse.uz → server IP
#   1. hotelpulse-app StudyGrow tarmog'iga ulanadi (yangi docker-compose.prod.yml)
#   2. nginx'ga 80-port bloki (ACME uchun) → nginx -t → reload (uzilishsiz)
#   3. Let's Encrypt sertifikati (StudyGrow'ning mavjud certbot akkaunti/konteyneri orqali)
#   4. nginx'ga to'liq HTTPS bloki → nginx -t → reload
#   5. APP_SITE_URL=https://hotel-pulse.uz, 8081 port tashqaridan yopiladi
#   6. Tekshiruv: https://hotel-pulse.uz va study-grow.uz
#
# StudyGrow fayllari O'ZGARTIRILMAYDI — faqat /opt/studygrow/nginx/templates/ ga
# yangi hotel-pulse.conf.template fayli qo'shiladi.
set -euo pipefail

SERVER="${HOTELPULSE_SERVER:-root@62.238.102.84}"
IP="${SERVER#*@}"
DOMAIN=hotel-pulse.uz
SG=/opt/studygrow
HP=/opt/hotelpulse
cd "$(dirname "$0")/.."

say() { echo "▶ $*"; }

say "0/6 DNS tekshiruvi..."
for d in "$DOMAIN" "www.$DOMAIN"; do
    got=$(ssh "$SERVER" "getent ahostsv4 $d | awk '{print \$1; exit}'" || true)
    if [ "$got" != "$IP" ]; then
        echo "❌ $d → '${got:-topilmadi}' (kutilgan: $IP). Domen hali serverga yo'nalmagan."; exit 1
    fi
done
echo "   ✅ $DOMAIN va www → $IP"

say "1/6 hotelpulse-app StudyGrow tarmog'iga ulanmoqda..."
scp -q docker-compose.prod.yml "$SERVER:$HP/"
ssh "$SERVER" "cd $HP && docker compose -f docker-compose.prod.yml up -d app >/dev/null 2>&1 && \
    docker inspect hotelpulse-app --format '{{range \$k,\$v := .NetworkSettings.Networks}}{{\$k}} {{end}}'" | grep -q studygrow_default \
    || { echo "❌ hotelpulse-app studygrow_default tarmog'iga ulanmadi"; exit 1; }
echo "   ✅ ulandi"

# nginx blokini o'rnatish: templates/ (doimiy) + conf.d/ (darhol) → tekshiruv → reload.
install_block() {
    local src="$1"
    scp -q "$src" "$SERVER:$SG/nginx/templates/hotel-pulse.conf.template"
    ssh "$SERVER" "docker exec nginx-proxy sh -c 'cp /etc/nginx/templates/hotel-pulse.conf.template /etc/nginx/conf.d/hotel-pulse.conf' && \
        docker exec nginx-proxy nginx -t 2>&1 | tail -2 && docker exec nginx-proxy nginx -s reload" \
        || { echo "❌ nginx sozlamasi xato — HotelPulse bloki olib tashlanmoqda";
             ssh "$SERVER" "rm -f $SG/nginx/templates/hotel-pulse.conf.template; docker exec nginx-proxy rm -f /etc/nginx/conf.d/hotel-pulse.conf; docker exec nginx-proxy nginx -s reload";
             exit 1; }
}

say "2/6 nginx: 80-port bloki (ACME uchun)..."
install_block deploy/nginx/hotel-pulse-http.conf.template
echo "   ✅ http://$DOMAIN nginx orqali"

say "3/6 Let's Encrypt sertifikati..."
if ssh "$SERVER" "test -f $SG/certbot/conf/live/$DOMAIN/fullchain.pem"; then
    echo "   ℹ️  sertifikat allaqachon bor"
else
    ssh "$SERVER" "cd $SG && EMAIL=\$(grep -m1 '^LETSENCRYPT_EMAIL=' .env | cut -d= -f2-) && \
        docker compose -f docker-compose.prod.yml run --rm --entrypoint certbot certbot certonly \
            --webroot -w /var/www/certbot -d $DOMAIN -d www.$DOMAIN \
            --email \"\$EMAIL\" --agree-tos --no-eff-email --non-interactive 2>&1 | tail -4" \
        || { echo "❌ sertifikat olinmadi — HTTP bloki qoldirildi, HTTPS yoqilmadi"; exit 1; }
    ssh "$SERVER" "test -f $SG/certbot/conf/live/$DOMAIN/fullchain.pem" || { echo "❌ sertifikat fayli topilmadi"; exit 1; }
fi
echo "   ✅ sertifikat tayyor"

say "4/6 nginx: HTTPS bloki..."
install_block deploy/nginx/hotel-pulse.conf.template
echo "   ✅ https://$DOMAIN"

say "5/6 Ilova sozlamalari (APP_SITE_URL, port yopish)..."
ssh "$SERVER" "cd $HP && \
    sed -i 's|^APP_SITE_URL=.*|APP_SITE_URL=https://$DOMAIN|' .env && \
    (grep -q '^APP_BIND=' .env && sed -i 's|^APP_BIND=.*|APP_BIND=127.0.0.1|' .env || echo 'APP_BIND=127.0.0.1' >> .env) && \
    docker compose -f docker-compose.prod.yml up -d app >/dev/null 2>&1"
for i in $(seq 1 40); do
    ssh "$SERVER" "docker logs hotelpulse-app --since 2m 2>&1 | grep -q 'Started HotelPulseApplication'" && break
    sleep 3
done
echo "   ✅ ilova qayta ishga tushdi"

say "6/6 Tekshiruv..."
curl -s -m 20 -o /dev/null -w "   https://$DOMAIN/login → %{http_code}\n" "https://$DOMAIN/login"
curl -s -m 20 -o /dev/null -w "   http://$DOMAIN → %{http_code} (301 kutiladi)\n" "http://$DOMAIN/"
curl -s -m 20 -o /dev/null -w "   https://www.$DOMAIN → %{http_code} (301 kutiladi)\n" "https://www.$DOMAIN/"
curl -s -m 20 -L -o /dev/null -w "   study-grow.uz → %{http_code}\n" "https://study-grow.uz/"
curl -s -m 10 -o /dev/null -w "   http://$IP:8081 (tashqaridan) → %{http_code} (000 kutiladi — yopiq)\n" "http://$IP:8081/login" || true
echo "✅ HTTPS yoqildi."
