# bx-xtdb

Native XTDB JDBC datasource support for BoxLang 1.18+ on Java 21+.
The initial supported server and JDBC driver are XTDB 2.2.0.

## Development

Use Java 21 (`JAVA_HOME` pointing at a JDK), CommandBox, and a Docker-compatible
runtime. On macOS, Docker CLI with Colima is sufficient:

```sh
brew install openjdk@21 docker colima
colima start --cpu 4 --memory 6
bash scripts/xtdb.sh start
./gradlew build consumerTest
bash scripts/xtdb.sh stop
```

To use an existing development node, set `XTDB_JDBC_URL`, `XTDB_USER`, and
`XTDB_PASSWORD` instead of starting a container. Use a disposable development
database: tests write uniquely identified documents.

The build produces an installable ZIP and checksum in `build/distributions/`.
The consumer runs with an isolated BoxLang home and loads only the packaged JARs.
