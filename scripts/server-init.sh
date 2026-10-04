#!/bin/bash
# Serverda BIR MARTA ishga tushiriladi: /opt/hotelpulse papkasi va maxfiy
# qiymatlar bilan .env faylini yaratadi. Maxfiy qiymatlar tasodifiy
# generatsiya qilinadi va ekranga CHIQARILMAYDI — ko'rish uchun:
#   cat /opt/hotelpulse/.env
#
# Ishlatilishi (lokal kompyuterdan):
#   ssh root@SERVER 'bash -s' < scripts/server-init.sh
set -euo pipefail

DIR=/opt/hotelpulse
mkdir -p "$DIR/backups" "$DIR/docker/mysql" "$DIR/scripts" "$DIR/target"

if [ -f "$DIR/.env" ]; then
    echo "ℹ️  $DIR/.env allaqachon mavjud — o'zgartirilmadi."
    exit 0
fi

rand() { openssl rand -base64 "$1" | tr -d '/+=\n' | cut -c1-"$2"; }
SERVER_IP=$(hostname -I | awk '{print $1}')

umask 077
cat > "$DIR/.env" <<EOF
# HotelPulse production sozlamalari. Bu fayl faqat serverda turadi (git'da yo'q).
DB_PASSWORD=$(rand 32 32)
OWNER_USERNAME=owner
OWNER_PASSWORD=$(rand 24 16)
# Exely client_secret'larni shifrlash kaliti. O'ZGARTIRMANG — aks holda saqlangan secret'lar ochilmay qoladi.
APP_ENCRYPTION_KEY=$(openssl rand -base64 32)
APP_PORT=8081
APP_SITE_URL=http://${SERVER_IP}:8081
# Production uchun ALOHIDA Telegram bot tokeni (lokal bot bilan bir xil bo'lmasin).
TELEGRAM_BOT_TOKEN=
BACKUP_RETENTION_DAYS=14
EOF

echo "✅ $DIR/.env yaratildi (maxfiy qiymatlar ichida, ekranga chiqarilmadi)."
