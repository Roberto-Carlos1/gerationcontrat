FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY frontend frontend
RUN mvn -B -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-fra tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
RUN groupadd --system pacte && useradd --system --gid pacte pacte
COPY --from=build /workspace/backend/target/pacte-2.0.0.jar app.jar
USER pacte
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
