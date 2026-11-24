# Builds the FPS simulator from source for local Docker Compose and the ledger's end-to-end test.
# Run from the repository root:
#   docker build -f fps-simulator/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
# The parent pom lists every module, so the reactor needs each one's pom.
COPY fps-simulator/pom.xml fps-simulator/pom.xml
COPY ledger/pom.xml ledger/pom.xml
COPY projector/pom.xml projector/pom.xml
COPY seed/pom.xml seed/pom.xml
RUN ./mvnw -B -ntp -pl fps-simulator -am dependency:go-offline
COPY fps-simulator/src fps-simulator/src
RUN ./mvnw -B -ntp -pl fps-simulator -am package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/fps-simulator/target/fps-simulator-*.jar fps-simulator.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/fps-simulator.jar"]
