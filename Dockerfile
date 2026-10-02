# AgentCore Runtime requires an ARM64 image.
# Alternative to Jib (see pom.xml). Build the jar first: mvn -B package -DskipTests
FROM --platform=linux/arm64 amazoncorretto:25-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app target/agentcore-managed-kb-agent.jar app.jar
USER app

# AgentCore Runtime contract: port 8080, POST /invocations, GET /ping
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
