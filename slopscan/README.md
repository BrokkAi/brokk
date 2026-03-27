# SlopScan

SlopScan is a web application that provides a "Forensic Audit" dashboard for code quality analysis. It communicates with the **Brokk Headless Executor** (a Java backend) to analyze Git repositories.

## Prerequisites for Local Testing

Before running SlopScan locally, you must ensure that the Brokk backend is built and the resulting JAR file is available for the Node.js server to execute.

### 1. Build the Brokk Executor

From the root of the repository, run the Gradle build to generate the JAR file:

```bash
cd ..
./gradlew :app:shadowJar
```

This will create a JAR file in `app/build/libs/` (for example, `app/build/libs/brokk-0.23.3.beta3-34-g279b83237.jar` or similar).

### 2. Configure the JAR Path

The SlopScan backend needs to know where this JAR file is. By default, it looks for `../app/build/libs/jdeploy-prelaunch.jar` or similar. If your JAR is named differently (e.g., `brokk-0.23.3.beta3-34-g279b83237.jar`), you might need to ensure it's in the expected location or update `slopscan/server/executor.js` to point to the correct JAR file, or simply symlink/rename your generated JAR to match what the server expects.

If you are just testing locally, the easiest way is to either rename the JAR or adjust `jarPath` in `slopscan/server/executor.js` to match the exact filename in your `app/build/libs/` directory.

### 3. Environment Variables

If you are using LLMs for the analysis (like Comment Semantics), make sure you have your Brokk API key or other necessary keys available. You can create a `.env` file in the `slopscan` directory:

```
BROKK_API_KEY=your_key_here
```

## Running Locally (Development Mode)

You will need to run both the Vite frontend and the Node.js Express backend.

### Start the Backend Server
```bash
cd slopscan/server
npm install
npm run start
```
The server will start on `http://localhost:3001` (or whatever is in your config).

### Start the Frontend
In a new terminal:
```bash
cd slopscan
npm install
npm run dev
```
The Vite development server will start, typically on `http://localhost:5173`.

## Docker Testing

If you want to test via Docker, make sure you've built the JAR first, as the `docker-compose.yml` mounts the `../app` directory to provide the JAR to the container.

```bash
cd slopscan
docker compose up --build
```

## Troubleshooting

- **"Service Unavailable" / Health check fails**: This means the Express backend (`slopscan/server/index.js`) is either not running or returning an error on `/api/health`. Make sure the Node server is started.
- **Scan Hangs at "Pending" or "Cloned"**: The executor JAR likely failed to start. Check the Node.js server console output. The `ExecutorManager` in `executor.js` pipes stdout and stderr from the Java process to the Node console. If the JAR path is wrong, it will log an error.
- **Database Issues**: The backend uses SQLite. If you need a clean slate, delete the `slopscan.db` file in the `slopscan/server` directory.
