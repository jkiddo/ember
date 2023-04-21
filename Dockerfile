FROM maven:3.9.1-eclipse-temurin-17 as build-cache
WORKDIR /tmp/app

COPY pom.xml .
RUN mvn -ntp dependency:go-offline

COPY src/ /tmp/app/src/
RUN mvn install -DskipTests

FROM build-cache AS build-distroless
RUN mvn package spring-boot:repackage
RUN mkdir /app && \
    cp /tmp/app/target/ember.jar /app/main.jar

FROM gcr.io/distroless/java17-debian11:nonroot AS release-distroless
COPY --chown=nonroot:nonroot --from=build-distroless /app /app
# 65532 is the nonroot user's uid
# used here instead of the name to allow Kubernetes to easily detect that the container
# is running as a non-root (uid != 0) user.
USER 65532:65532
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/main.jar"]