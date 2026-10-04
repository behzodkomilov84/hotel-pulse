# HotelPulse — serverga chiqarish

Hozirgi holat: HotelPulse **StudyGrow serverida** (Hetzner, `62.238.102.84`) alohida
Docker loyihasi sifatida ishlaydi — `/opt/hotelpulse`, konteynerlar `hotelpulse-app`,
`hotelpulse-mysql`, `hotelpulse-backup`. StudyGrow (`/opt/studygrow`) fayllari va
konteynerlariga tegilmaydi.

Domen hali yo'q — sayt: **http://62.238.102.84:8081** (HTTPS'siz).

> ⚠️ HTTPS yo'qligi sababli parol va ma'lumotlar shifrlanmasdan uzatiladi. Haqiqiy
> mehmonxonalarni ulashdan oldin domen + HTTPS qo'shish kerak (pastda).

## Server RAM'i

Server: 3.8 GB RAM, swap yo'q, StudyGrow ~2.4 GB ishlatadi. Shuning uchun HotelPulse
qat'iy cheklangan: ilova ≤ 512 MB (Java heap 256 MB), MySQL ≤ 400 MB (kichik profil —
`docker/mysql/hotelpulse.cnf`), backup ≤ 128 MB.

Tekshirish: `ssh root@62.238.102.84 "free -m; docker stats --no-stream"`

## Birinchi marta

```bash
# 1. Serverda papka va maxfiy .env (tasodifiy parollar; ekranga chiqmaydi)
ssh root@62.238.102.84 'bash -s' < scripts/server-init.sh

# 2. Deploy
./scripts/deploy.sh
```

OWNER paroli serverdagi `.env` faylida: `ssh root@62.238.102.84 "grep OWNER_ /opt/hotelpulse/.env"`

## Keyingi deploylar

```bash
./scripts/deploy.sh
```

Skript: testlar → `clean package` → jar va Docker fayllarini `scp` → `docker compose up -d --build`
→ `docker builder prune -f` (build keshi diskni to'ldirmasligi uchun) → ishga tushganini tekshirish.

> Lokal dev server ishlab turgan bo'lsa, avval uni to'xtating — `clean` `target/` ni o'chiradi.

## Telegram

Production uchun **alohida bot** kerak (bitta token bilan faqat bitta server so'rov qila oladi —
aks holda 409). @BotFather → `/newbot` → tokenni serverdagi `/opt/hotelpulse/.env` →
`TELEGRAM_BOT_TOKEN=` ga yozing, keyin:

```bash
ssh root@62.238.102.84 "cd /opt/hotelpulse && docker compose -f docker-compose.prod.yml up -d app"
```

## Zaxira nusxalar

Har kuni 03:30 da `/opt/hotelpulse/backups/hotel_pulse-YYYYMMDD_HHMMSS.sql.gz`, 14 kun saqlanadi.

Tiklash:

```bash
ssh root@62.238.102.84
cd /opt/hotelpulse
docker compose -f docker-compose.prod.yml stop app
docker compose -f docker-compose.prod.yml exec backup bash /scripts/restore-db.sh /backups/<fayl>.sql.gz
docker compose -f docker-compose.prod.yml start app
```

## Foydali buyruqlar

```bash
docker logs hotelpulse-app --since 10m          # ilova logi
docker compose -f docker-compose.prod.yml ps    # holat (/opt/hotelpulse ichida)
docker compose -f docker-compose.prod.yml restart app
```

## Domen olingach (keyin)

1. DNS: domen A yozuvi → `62.238.102.84`.
2. 80/443 portlari StudyGrow nginx'ida — HotelPulse domeni uchun alohida `server { }` bloki
   qo'shiladi (proxy → `hotelpulse-app:8081`, umumiy Docker tarmog'i orqali) va Let's Encrypt
   sertifikati olinadi.
3. `APP_SITE_URL=https://<domen>`, 8081 port tashqaridan yopiladi.
