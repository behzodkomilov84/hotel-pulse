#!/bin/bash
# Production Telegram bot tokenini serverdagi /opt/hotelpulse/.env ga yozadi.
# Token ekranga chiqmaydi, buyruqlar tarixiga ham tushmaydi.
#
# Ishlatilishi (lokal kompyuterda, Git Bash'da):
#   1) lokal .env ga  TELEGRAM_BOT_TOKEN_PROD=<token>  qatorini yozing (yoki skript o'zi so'raydi)
#   2) ./scripts/set-telegram-token.sh
set -euo pipefail

SERVER="${HOTELPULSE_SERVER:-root@62.238.102.84}"
cd "$(dirname "$0")/.."

# Avval lokal .env'dagi TELEGRAM_BOT_TOKEN_PROD qatoridan olinadi; bo'sh bo'lsa — so'raladi.
TOKEN=""
if [ -f .env ]; then
    TOKEN=$(grep -m1 '^TELEGRAM_BOT_TOKEN_PROD=' .env | cut -d= -f2- | tr -d '\r[:space:]' || true)
fi
if [ -z "$TOKEN" ]; then
    read -r -s -p "Production bot tokenini kiriting (@BotFather bergan): " TOKEN
    echo
fi

if ! [[ "$TOKEN" =~ ^[0-9]+:[A-Za-z0-9_-]{30,}$ ]]; then
    echo "❌ Token formati noto'g'ri (masalan: 123456789:AAH...)." >&2
    exit 1
fi

# Lokal bot bilan bir xil token bo'lsa, ikkala server bir-biriga xalaqit beradi (409).
if [ -f .env ] && grep -qxF "TELEGRAM_BOT_TOKEN=$TOKEN" .env; then
    echo "❌ Bu — lokal botning tokeni. Production uchun @BotFather'da BOSHQA bot oching." >&2
    exit 1
fi

# Token stdin orqali uzatiladi (buyruq satrida ko'rinmaydi).
printf '%s\n' "$TOKEN" | ssh "$SERVER" 'read -r T
    cd /opt/hotelpulse
    if grep -q "^TELEGRAM_BOT_TOKEN=" .env; then
        sed -i "s|^TELEGRAM_BOT_TOKEN=.*|TELEGRAM_BOT_TOKEN=$T|" .env
    else
        echo "TELEGRAM_BOT_TOKEN=$T" >> .env
    fi
    docker compose -f docker-compose.prod.yml up -d app >/dev/null 2>&1
    echo "✅ Token saqlandi, ilova qayta ishga tushirilmoqda..."'

for i in $(seq 1 30); do
    line=$(ssh "$SERVER" "docker logs hotelpulse-app --since 2m 2>&1 | grep -E 'Telegram bot ishga tushdi|Telegram botni ishga tushirib bo.lmadi' | tail -1" || true)
    if [ -n "$line" ]; then
        echo "$line" | sed -E 's/.*: //'
        exit 0
    fi
    sleep 3
done
echo "⚠️  Bot holati 90 soniyada logda ko'rinmadi: ssh $SERVER \"docker logs hotelpulse-app --since 5m\""
