# One image recipe for both services: docker build --build-arg MODULE=ingest-api .   (or MODULE=consumer)
# Stage 1 builds the jar with Maven, stage 2 only has a JRE and the jar.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl ${MODULE} -am package -DskipTests \
 && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
