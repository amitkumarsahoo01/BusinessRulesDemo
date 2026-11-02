FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY decision-service/pom.xml decision-service/pom.xml
COPY ai-service/pom.xml ai-service/pom.xml
RUN chmod +x mvnw && ./mvnw -q -pl ai-service -am dependency:go-offline
COPY ai-service/src ai-service/src
RUN ./mvnw -q -pl ai-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/ai-service/target/ai-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
