FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY decision-service/pom.xml decision-service/pom.xml
COPY ai-service/pom.xml ai-service/pom.xml
RUN chmod +x mvnw && ./mvnw -q -pl decision-service -am dependency:go-offline
COPY decision-service/src decision-service/src
RUN ./mvnw -q -pl decision-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/decision-service/target/decision-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
