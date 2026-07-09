# Stage 1: Build the JAR
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests

# Stage 2: Minimal image with just the JAR
FROM busybox:stable
COPY --from=builder /app/target/oswatcher-procedures-*.jar /procedure.jar
# Default: copy JAR to mounted volume
CMD ["cp", "/procedure.jar", "/plugin/procedure.jar"]
