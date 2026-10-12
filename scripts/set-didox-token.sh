#!/bin/bash
# Didox hamkor tokenini serverdagi /opt/hotelpulse/.env ga (DIDOX_PARTNER_TOKEN) yozadi va ilovani qayta ishga tushiradi.
# Token ekranga chiqmaydi, buyruqlar tarixiga ham tushmaydi.
#
# Ishlatilishi (lokal kompyuterda, Git Bash'da):
#   ./scripts/set-didox-token.sh        — token yashirin so'raladi
set -euo pipefail

SERVER="${HOTELPULSE_SERVER:-root@62.238.102.84}"
cd "$(dirname "$0")/.."

read -r -s -p "Didox hamkor tokenini kiriting (Didox bergan): " TOKEN
echo
TOKEN=$(printf '%s' "$TOKEN" | tr -d '\r[:space:]')

if ! [[ "$TOKEN" =~ ^[A-Za-z0-9._:-]{8,}$ ]]; then
    echo "❌ Token bo'sh yoki noto'g'ri belgilar bor." >&2
    exit 1
fi

# Token stdin orqali uzatiladi (buyruq satrida ko'rinmaydi).
printf '%s\n' "$TOKEN" | ssh "$SERVER" 'read -r T
    cd /opt/hotelpulse
    if grep -q "^DIDOX_PARTNER_TOKEN=" .env; then
        sed -i "s|^DIDOX_PARTNER_TOKEN=.*|DIDOX_PARTNER_TOKEN=$T|" .env
    else
        echo "DIDOX_PARTNER_TOKEN=$T" >> .env
    fi
    docker compose -f docker-compose.prod.yml up -d app >/dev/null 2>&1
    echo "✅ Token saqlandi, ilova qayta ishga tushirilmoqda (~1 daqiqa)."
    echo "   Keyin: Бошқарув → Меҳмонхоналар → mehmonxona → ЭСФ → Didox → «Уланишни текшириш»."'
