FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /app
COPY proto ./proto
COPY control-plane ./control-plane
WORKDIR /app/control-plane
RUN mvn --batch-mode package

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /app/control-plane/target/cascadeserve-control-plane-0.1.0-SNAPSHOT.jar ./control-plane.jar
EXPOSE 50051 8080
ENTRYPOINT ["java", "-jar", "control-plane.jar"]
