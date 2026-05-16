# ReadAccess Desktop

A JavaFX desktop application that imports exam result data from Microsoft Access (`.accdb`/`.mdb`) files and uploads it to a PostgreSQL database via a configurable REST API.

## Features

- Drag-and-drop or file-picker to load Access database files
- Supports multiple exam types (configurable via dropdown)
- Batch upload to a REST API endpoint with retry logic and parallel threads
- PostgreSQL connection configuration with a built-in connection tester
- Real-time progress bar and timestamped activity log
- Persistent configuration stored as JSON (DB credentials + API settings)

## Requirements

- Java 21+
- Maven 3.6+

## Build

```bash
mvn clean package
```

This produces a fat JAR at `target/desktopapp-1.0.0-launcher.jar`.

## Run

```bash
java -jar target/desktopapp-1.0.0-launcher.jar
```

Or via the Maven JavaFX plugin during development:

```bash
mvn javafx:run
```

## Configuration

On first launch, use the **Configure** button to set:

- **Database** — PostgreSQL host, port, database name, username, and password
- **API** — Endpoint URL, batch size, connection/read timeouts, max retries, and parallel upload threads

Settings are saved locally as JSON and loaded automatically on subsequent launches.

## Tech Stack

| Library | Purpose |
|---|---|
| JavaFX 21 | UI framework |
| UCanAccess 5 | Read Microsoft Access files via JDBC |
| PostgreSQL JDBC 42 | PostgreSQL connectivity |
| Gson 2.10 | JSON config persistence |
