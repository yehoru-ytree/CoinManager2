# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# чтобы кэшировать зависимости
COPY gradlew settings.gradle* build.gradle* gradle/ ./
RUN sh ./gradlew --no-daemon dependencies || true

# теперь исходники
COPY . .
RUN sh ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

# ограничим аппетит JVM на старом ноуте
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -Duser.timezone=Europe/Kyiv"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]