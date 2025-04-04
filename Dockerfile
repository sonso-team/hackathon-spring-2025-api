FROM openjdk:21-slim
EXPOSE 7000
COPY ./build/libs/*.jar app.jar
CMD ["java", "-jar", "app.jar"]