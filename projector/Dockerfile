# Builds the projector from source for local Docker Compose. Run from the repository root:
#   docker build -f projector/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY projector/pom.xml projector/pom.xml
COPY ledger/pom.xml ledger/pom.xml
RUN ./mvnw -B -ntp -pl projector -am dependency:go-offline
COPY projector/src projector/src
RUN ./mvnw -B -ntp -pl projector -am package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/projector/target/projector-*-exec.jar projector.jar
ENTRYPOINT ["java", "-jar", "/app/projector.jar"]
