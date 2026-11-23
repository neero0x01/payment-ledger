# Builds the ledger from source for local Docker Compose. Run from the repository root:
#   docker build -f ledger/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY ledger/pom.xml ledger/pom.xml
# The ledger's tests run the projector, so the reactor needs it too.
COPY projector/pom.xml projector/pom.xml
RUN ./mvnw -B -ntp -pl ledger -am dependency:go-offline
COPY projector/src projector/src
COPY ledger/src ledger/src
RUN ./mvnw -B -ntp -pl ledger -am package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/ledger/target/ledger-*.jar ledger.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ledger.jar"]
