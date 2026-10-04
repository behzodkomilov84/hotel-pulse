#!/bin/bash
# Zaxira nusxadan (backup.sh yaratgan .sql.gz) bazani tiklaydi.
#
# Ishlatilishi (serverda, /opt/hotelpulse):
#   docker compose -f docker-compose.prod.yml exec backup bash /scripts/restore-db.sh /backups/hotel_pulse-20260101_033000.sql.gz
#
# DIQQAT: joriy bazadagi BARCHA ma'lumot zaxiradagi holat bilan almashtiriladi
# (qaytarib bo'lmaydi). Tiklashdan oldin ilovani to'xtating:
#   docker compose -f docker-compose.prod.yml stop app
set -euo pipefail

DUMP_FILE="${1:?Foydalanish: restore-db.sh <dump-fayli.sql.gz>}"

if [ ! -f "$DUMP_FILE" ]; then
    echo "❌ Fayl topilmadi: $DUMP_FILE" >&2
    exit 1
fi

echo "⚠️  DIQQAT: '$MYSQL_DATABASE' bazasi '$DUMP_FILE' fayli bilan ALMASHTIRILADI."
read -r -p "Davom etasizmi? (ha yozib Enter bosing): " confirm
if [ "$confirm" != "ha" ]; then
    echo "Bekor qilindi."
    exit 0
fi

# Dump olingandan keyin qo'shilgan jadvallar "eski" holatda qolib ketmasligi
# uchun baza butunlay qayta yaratiladi.
MYSQL_PWD="$MYSQL_PASSWORD" mysql -h "$MYSQL_HOST" -u "$MYSQL_USER" \
    -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`; CREATE DATABASE \`$MYSQL_DATABASE\`;"
gunzip -c "$DUMP_FILE" | MYSQL_PWD="$MYSQL_PASSWORD" mysql -h "$MYSQL_HOST" -u "$MYSQL_USER" "$MYSQL_DATABASE"

echo "✅ Tiklandi. Ilovani qayta ishga tushiring:"
echo "   docker compose -f docker-compose.prod.yml start app"
