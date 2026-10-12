FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/phu360-sync-agent.jar /app/app.jar

RUN mkdir -p /var/lib/phu360-sync

EXPOSE 8091

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
