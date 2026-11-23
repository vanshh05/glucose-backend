# Build stage — compile the fat jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Download deps first (better Docker layer caching)
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage — slim JRE only
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Render injects the PORT env var; Spring Boot reads it from application.yml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]