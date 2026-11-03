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
# Bake the embedding model into the image so a cold start (Cloud Run scale-to-zero) never has to
# download ~90MB from Hugging Face. Same file application.yml points at by default; the env var
# below overrides that URL with the local copy.
ADD https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx /app/model/model.onnx
ENV SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODELURI=file:/app/model/model.onnx
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
