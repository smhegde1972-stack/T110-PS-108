
FROM maven:3.9.9-eclipse-temurin-17

WORKDIR /app

COPY pom.xml .
COPY src ./src
COPY data ./data

RUN mvn -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/dependency

EXPOSE 8081

CMD ["sh", "-c", "java -cp 'target/classes:target/dependency/*' in.ps108.WebMain"]
