#!/bin/bash
# HotelPulse'ni serverga chiqarish (lokal kompyuterda, Git Bash'da):
#   ./scripts/deploy.sh
#
# Bosqichlar: testlar → jar yig'ish → fayllarni serverga ko'chirish →
# konteynerni qayta yig'ish → ishga tushganini tekshirish.
# Birinchi marta oldin: ssh root@SERVER 'bash -s' < scripts/server-init.sh
set -euo pipefail

SERVER="${HOTELPULSE_SERVER:-root@62.238.102.84}"
DIR=/opt/hotelpulse
cd "$(dirname "$0")/.."

echo "▶ 1/5 Testlar..."
./mvnw -q -B clean test

echo "▶ 2/5 Jar yig'ilmoqda..."
./mvnw -q -B package -DskipTests
JAR=target/HotelPulse-0.0.1-SNAPSHOT.jar
[ -f "$JAR" ] || { echo "❌ $JAR topilmadi"; exit 1; }

echo "▶ 3/5 Serverga ko'chirilmoqda ($SERVER)..."
ssh "$SERVER" "mkdir -p $DIR/target $DIR/docker/mysql $DIR/scripts $DIR/backups && test -f $DIR/.env" \
    || { echo "❌ $DIR/.env yo'q — avval scripts/server-init.sh ni ishga tushiring"; exit 1; }
scp -q "$JAR" "$SERVER:$DIR/target/"
scp -q Dockerfile docker-compose.prod.yml "$SERVER:$DIR/"
scp -q docker/mysql/hotelpulse.cnf "$SERVER:$DIR/docker/mysql/"
scp -q scripts/backup.sh scripts/restore-db.sh "$SERVER:$DIR/scripts/"

echo "▶ 4/5 Konteynerlar yangilanmoqda..."
ssh "$SERVER" "cd $DIR && docker compose -f docker-compose.prod.yml up -d --build && docker builder prune -f >/dev/null"

echo "▶ 5/5 Ishga tushishi tekshirilmoqda..."
for i in $(seq 1 40); do
    if ssh "$SERVER" "docker logs hotelpulse-app --since 3m 2>&1 | grep -q 'Started HotelPulseApplication'"; then
        ssh "$SERVER" "docker logs hotelpulse-app --since 3m 2>&1 | grep -E 'Started HotelPulseApplication|ERROR|Telegram bot' | head -5"
        echo "✅ Deploy tugadi."
        exit 0
    fi
    sleep 3
done
echo "❌ Ilova 2 daqiqada ishga tushmadi. Log:"
ssh "$SERVER" "docker logs hotelpulse-app --tail 60"
exit 1
