# ---- Stage 1: Frontend build ----
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY echoflow-frontend/package.json echoflow-frontend/package-lock.json ./
RUN npm ci
COPY echoflow-frontend/ ./
ENV NEXT_PUBLIC_API_BASE=""
ENV STATIC_EXPORT="true"
RUN npm run build

# ---- Stage 2: Backend build ----
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY echoflow-backend/pom.xml echoflow-backend/pom.xml
COPY echoflow-backend/echoflow-domain/pom.xml echoflow-backend/echoflow-domain/pom.xml
COPY echoflow-backend/echoflow-application/pom.xml echoflow-backend/echoflow-application/pom.xml
COPY echoflow-backend/echoflow-infrastructure/pom.xml echoflow-backend/echoflow-infrastructure/pom.xml
COPY echoflow-backend/echoflow-web/pom.xml echoflow-backend/echoflow-web/pom.xml
RUN chmod +x mvnw && ./mvnw dependency:go-offline -pl echoflow-backend/echoflow-web -am -pl !echoflow-frontend
COPY echoflow-backend/ echoflow-backend/
RUN ./mvnw clean package -pl echoflow-backend/echoflow-web -am -pl !echoflow-frontend -DskipTests \
    && mv echoflow-backend/echoflow-web/target/echoflow-web-*.jar /app/app.jar

# ---- Stage 3: Runtime image ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S echoflow && adduser -S echoflow -G echoflow
WORKDIR /app
COPY --from=backend-build /app/app.jar app.jar
COPY --from=frontend-build /app/frontend/out/ /app/static/
RUN chown -R echoflow:echoflow /app
USER echoflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.web.resources.static-locations=file:/app/static/"]
