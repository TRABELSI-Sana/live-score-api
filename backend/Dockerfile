FROM eclipse-temurin:21-jre

# Répertoire de travail dans le container
WORKDIR /app

# Copier le jar généré par Maven
COPY app.jar /app/app.jar

# Port exposé par Spring Boot
EXPOSE 8099

# Lancer l’application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]