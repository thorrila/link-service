# --- Build stage ---
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.2_13_1.9.9_2.13.12 AS build
WORKDIR /app
COPY . .
RUN sbt stage

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/universal/stage /app
EXPOSE 9000
# ENTRYPOINT ["sh", "-c", "/app/bin/bokun-link-service -Dhttp.port=${PORT:-9000}"]
ENTRYPOINT ["/bin/sh", "-c", "/app/bin/bokun-link-service -Dhttp.port=${PORT:-9000} -Dhttp.address=0.0.0.0"]
