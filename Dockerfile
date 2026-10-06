FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY pom.xml .

RUN apt-get update \
    && apt-get install -y maven \
    && mvn dependency:go-offline \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY src ./src

RUN mvn clean package -DskipTests

EXPOSE 10000

CMD ["sh", "-c", "java -Dserver.port=${PORT:-10000} -jar target/campusmatch-1.0.0.jar"]