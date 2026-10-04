# HotelPulse — serverga chiqarish

Hozirgi holat: HotelPulse **StudyGrow serverida** (Hetzner, `62.238.102.84`) alohida
Docker loyihasi sifatida ishlaydi — `/opt/hotelpulse`, konteynerlar `hotelpulse-app`,
`hotelpulse-mysql`, `hotelpulse-backup`. StudyGrow (`/opt/studygrow`) fayllari va
konteynerlariga tegilmaydi.

Sayt: **https://hotel-pulse.uz** (Let's Encrypt). `www` va `http://` → `https://hotel-pulse.uz` ga yo'naltiriladi.
8081 port tashqaridan yopiq (`APP_BIND=127.0.0.1`) — kirish faqat nginx orqali.

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

## Domen va HTTPS

- Domen: **hotel-pulse.uz** — ahost.uz'da ro'yxatdan o'tgan; DNS — Cloudflare (Free, nameserver'lar
  `marge`/`peter.ns.cloudflare.com`), A yozuvlari `@` va `www` → `62.238.102.84`, **DNS only** (proksisiz).
- 80/443 portlari StudyGrow'ning `nginx-proxy` konteynerida. HotelPulse bloki:
  `/opt/studygrow/nginx/templates/hotel-pulse.conf.template` (manbasi: `deploy/nginx/`).
  StudyGrow'ning boshqa fayllari o'zgartirilmagan.
- `hotelpulse-app` StudyGrow tarmog'iga (`studygrow_default`) ham ulangan — nginx unga `hotelpulse-app:8081` orqali murojaat qiladi.
  Upstream o'zgaruvchi + Docker resolver orqali yozilgan: HotelPulse to'xtasa ham nginx (va StudyGrow) ishlayveradi.
- Sertifikat StudyGrow'ning `certbot` konteyneri tomonidan avtomatik yangilanadi (`certbot renew` — barcha sertifikatlar).
  Yangilangan sertifikatni nginx faqat reload'dan keyin ko'radi — shuning uchun serverda
  `/etc/cron.d/nginx-proxy-reload`: har kuni 04:00 da `nginx -t` + `nginx -s reload` (uzilishsiz,
  study-grow.uz uchun ham).

Qayta sozlash (masalan, nginx bloki o'zgarganda): `./scripts/enable-https.sh` — har qadamni tekshiradi,
nginx sozlamasi xato bo'lsa o'zgarishni qaytaradi.
