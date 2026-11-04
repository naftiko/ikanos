# The Ikanos image run.sh builds from this checkout's jar (mvn package first): the same runtime as
# deployment/Dockerfile, without rebuilding the sources inside Docker.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY ikanos.jar ikanos.jar
ENTRYPOINT ["java", "-jar", "ikanos.jar"]
CMD ["serve"]
