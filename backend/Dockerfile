# Multi-stage build for Spring Boot with Java 25
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy Maven wrapper and POM for dependency caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Download dependencies (fail-safe cache layer)
RUN ./mvnw dependency:go-offline -B || true

# Copy source code and build executable JAR
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Render assigns dynamic port via PORT environment variable (defaults to 10000 on Render)
ENV PORT=10000
EXPOSE 10000

# -XX:TieredStopAtLevel=1 and -Xmx384m drastically speed up startup and avoid memory/CPU throttling on Render free tier
ENTRYPOINT ["sh", "-c", "java -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx384m -Dserver.port=${PORT:-10000} -jar app.jar"]

