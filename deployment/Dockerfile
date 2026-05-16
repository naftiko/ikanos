# Stage 1: Build the application
# We use a maven image to compile the code so you don't need Maven installed locally
FROM maven:3.9.15-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY ikanos-spec ./ikanos-spec
COPY ikanos-engine ./ikanos-engine
COPY ikanos-cli ./ikanos-cli
COPY ikanos-docs ./ikanos-docs
# Build the jar
RUN mvn clean package -DskipTests

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="Ikanos" \
      org.opencontainers.image.description="Ikanos capability engine." \
      org.opencontainers.image.url="https://github.com/naftiko/ikanos" \
      org.opencontainers.image.source="https://github.com/naftiko/ikanos" \
      org.opencontainers.image.licenses="Apache-2.0"
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/ikanos-cli/target/ikanos.jar ikanos.jar

# Set the entrypoint to run the CLI jar and default to the runtime subcommand
ENTRYPOINT ["java", "-jar", "ikanos.jar"]
CMD ["serve"]
