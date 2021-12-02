FROM maven:3.6.3-jdk-8 AS serverBuild
COPY frontend /frontend
COPY src /src
COPY pom.xml /pom.xml
RUN mvn -f /pom.xml clean install

FROM java:8
EXPOSE 8080
COPY --from=serverBuild /target/eea-rdf-river-indexer-2.0.3-altered.jar app.jar
ENTRYPOINT ["java", "-jar", "-Xmx4096M", "-Xms2048M", "app.jar"]
