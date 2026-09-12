# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the pom.xml and maven wrapper files
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Ensure mvnw has execute permissions
RUN chmod +x ./mvnw

# Copy the source code
COPY src ./src

# Build the application
RUN ./mvnw clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/*.jar app.jar

# Expose port 8080
EXPOSE 8080

# Run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"]
