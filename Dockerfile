FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle :backend-ktor:buildFatJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/backend-ktor/build/libs/*-all.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]