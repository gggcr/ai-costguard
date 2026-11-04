FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home costguard
USER costguard
# Build the jar first: ./gradlew :core:bootJar
COPY build/libs/core.jar /app/core.jar
ENTRYPOINT ["java", "-jar", "/app/core.jar"]
