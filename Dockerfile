FROM eclipse-temurin:17-jdk AS build

WORKDIR /app
COPY src ./src
COPY lib ./lib

RUN mkdir -p bin && javac -encoding UTF-8 -cp "lib/*" -d bin src/*.java

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /app/bin ./bin
COPY --from=build /app/lib ./lib

ENV PORT=10000
EXPOSE 10000

CMD ["java", "-cp", "bin:lib/*", "WebServer"]
