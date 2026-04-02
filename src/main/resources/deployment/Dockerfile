# Stage 1: Build the application
# We use a maven image to compile the code so you don't need Maven installed locally
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Build the jar
RUN mvn clean package -DskipTests
LABEL org.opencontainers.image.title="naftiko-cli"

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/capability.jar app.jar

# Set the entrypoint to run the jar
ENTRYPOINT ["java", "-jar", "app.jar"]
