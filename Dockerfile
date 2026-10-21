FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/Ai-Java-Interview-1.0-SNAPSHOT.jar /app/app.jar
COPY deploy/healthcheck.sh /app/healthcheck.sh
RUN groupadd --system interview && useradd --system --gid interview interview
USER interview
EXPOSE 8200
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 CMD ["bash", "/app/healthcheck.sh"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
