# Stage 1: Build the application
# We use a maven image to compile the code so you don't need Maven installed locally
FROM maven:3.9.15-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN sed -i -e 's|<module>modules/ikanos-coverage</module>||g' \
    -e 's|<module>modules/ikanos-rules-tests</module>||g' pom.xml
COPY modules/ikanos-spec ./modules/ikanos-spec
COPY modules/ikanos-engine ./modules/ikanos-engine
COPY modules/ikanos-cli ./modules/ikanos-cli
COPY modules/ikanos-docs ./modules/ikanos-docs
COPY modules/ikanos-tunnel-ziti ./modules/ikanos-tunnel-ziti

# Build the jar
RUN mvn clean package -DskipTests

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-jre-alpine

RUN apk upgrade --no-cache

LABEL org.opencontainers.image.title="Ikanos" \
      org.opencontainers.image.description="Ikanos Capability Engine." \
      org.opencontainers.image.url="https://github.com/naftiko/ikanos" \
      org.opencontainers.image.source="https://github.com/naftiko/ikanos" \
      org.opencontainers.image.licenses="Apache-2.0"
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/modules/ikanos-cli/target/ikanos.jar ikanos.jar

# Extension directory: every jar in /app/lib joins the class path, so an image built FROM this
# one can add a JDBC driver or an optional module without rebuilding Ikanos. JDBC 4 drivers
# register themselves through their own META-INF/services/java.sql.Driver.
RUN mkdir -p /app/lib

# Run the CLI from the class path rather than with -jar, which would ignore /app/lib, and default
# to the runtime subcommand. The class path is in exec form, so the shell never expands the
# wildcard: the JVM does.
ENTRYPOINT ["java", "-cp", "/app/ikanos.jar:/app/lib/*", "io.ikanos.Cli"]
CMD ["serve"]
