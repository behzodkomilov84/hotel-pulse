# HotelPulse — production image.
# Jar lokal kompyuterda yig'iladi (./mvnw clean package) va serverga
# ko'chiriladi — serverda Maven ishlatilmaydi (RAM va Docker build keshi tejaladi).
FROM eclipse-temurin:17-jre-jammy

# Ilova root huquqisiz ishlaydi.
RUN groupadd --system app && useradd --system --gid app --home /app app

WORKDIR /app
COPY target/HotelPulse-0.0.1-SNAPSHOT.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8081

# Server RAM'i StudyGrow bilan bo'lishiladi — heap qat'iy cheklangan
# (docker-compose.prod.yml'da konteyner chegarasi ham bor).
# Qiymatni o'zgartirish uchun: .env → JAVA_OPTS.
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
