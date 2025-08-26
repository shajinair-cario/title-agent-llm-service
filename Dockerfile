# JDK/JRE build that has the cgroup v2 fixes (e.g., Temurin 17.0.8+).
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/title-nlp-service-0.0.1.jar app.jar

# spring boot default profile to local profile
ENV SPRING_PROFILES_ACTIVE=local
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
