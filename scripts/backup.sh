#!/bin/bash
# Har kuni BACKUP_HOUR:BACKUP_MINUTE da (standart 03:30) MySQL bazasining
# zaxira nusxasini oladi va BACKUP_RETENTION_DAYS kundan eskilarini o'chiradi.
# docker-compose.prod.yml'dagi "backup" servisi ishga tushiradi (mysql:8.0
# image — mysqldump versiyasi server bilan mos).
#
# DIQQAT: nusxalar serverning o'zida (/opt/hotelpulse/backups). Server butunlay
# ishdan chiqsa, ular ham yo'qoladi — vaqti-vaqti bilan tashqariga ko'chiring
# (docs/DEPLOYMENT.md).
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_HOUR="${BACKUP_HOUR:-03}"
BACKUP_MINUTE="${BACKUP_MINUTE:-30}"

mkdir -p "$BACKUP_DIR"

log() {
    echo "[$(date '+%F %T')] $1"
}

run_backup() {
    local ts dump
    ts=$(date +%Y%m%d_%H%M%S)
    dump="$BACKUP_DIR/${MYSQL_DATABASE}-${ts}.sql.gz"
    log "Zaxira nusxalash boshlandi..."
    # --single-transaction — ilova ishlab turganda ham izchil nusxa, jadvallar bloklanmaydi.
    if MYSQL_PWD="$MYSQL_PASSWORD" mysqldump \
        -h "$MYSQL_HOST" -u "$MYSQL_USER" \
        --single-transaction --routines --triggers \
        --databases "$MYSQL_DATABASE" | gzip > "$dump"; then
        log "✅ Zaxira saqlandi: $dump ($(du -h "$dump" | cut -f1))"
    else
        log "❌ Zaxira MUVAFFAQIYATSIZ (mysqldump xatolik qaytardi)"
        rm -f "$dump"
    fi
    find "$BACKUP_DIR" -name "*.sql.gz" -mtime "+${RETENTION_DAYS}" -delete
    log "Tozalash: ${RETENTION_DAYS} kundan eski nusxalar o'chirildi."
}

# Konteyner ishga tushgani zahoti birinchi nusxa olinadi.
run_backup

while true; do
    now_epoch=$(date +%s)
    next_epoch=$(date -d "today ${BACKUP_HOUR}:${BACKUP_MINUTE}:00" +%s)
    if [ "$next_epoch" -le "$now_epoch" ]; then
        next_epoch=$(date -d "tomorrow ${BACKUP_HOUR}:${BACKUP_MINUTE}:00" +%s)
    fi
    log "Keyingi zaxira: $(date -d "@$next_epoch" '+%F %T')"
    sleep $((next_epoch - now_epoch))
    run_backup
done
