# ── ETAPA 1: Compilación ──────────────────────────────────────
FROM gradle:jdk-25-and-25 AS build

WORKDIR /home/gradle/src

COPY --chown=gradle:gradle . .

RUN gradle shadowJar --no-daemon

# ── ETAPA 2: Ejecución ───────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

RUN mkdir -p /app/data && chown -R appuser:appgroup /app

WORKDIR /app

COPY --from=build /home/gradle/src/build/libs/*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 7070 8082

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:7070/login || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]