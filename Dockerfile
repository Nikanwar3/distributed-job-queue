FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S jobqueue && adduser -S jobqueue -G jobqueue
WORKDIR /app
COPY --from=build /build/target/distributed-job-queue.jar app.jar
USER jobqueue
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
