FROM node:14.16.1 AS frontendBuild
RUN git clone https://github.com/opendata-mvcr/dashboard-indexer-frontend.git /indexer-frontend
WORKDIR /indexer-frontend
RUN npm install
RUN npm run build

FROM maven:3.6.3-jdk-8 AS serverBuild
COPY --from=frontendBuild /indexer-frontend/build /frontend/build
COPY src /src
COPY pom.xml /pom.xml
RUN mvn -f /pom.xml clean install

FROM java:8
EXPOSE 8080
COPY --from=serverBuild /target/eea-rdf-river-indexer-*-altered.jar app.jar
ENTRYPOINT java -jar -Xmx$Xmx -Xms$Xms app.jar
