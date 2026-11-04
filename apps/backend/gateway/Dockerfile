FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home costguard
USER costguard
# Build the jar first: ./gradlew :gateway:bootJar
COPY build/libs/gateway.jar /app/gateway.jar
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
