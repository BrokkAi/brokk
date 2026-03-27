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

The SlopScan backend automatically looks for the latest JAR file in `../app/build/libs/`. It prioritizes `jdeploy-prelaunch.jar` but will fallback to the most recently modified `brokk-*.jar` file found in that directory.

As long as you have run `./gradlew :app:shadowJar`, the backend should be able to locate the executor without manual configuration.

### 3. Environment Variables

Create a `.env` file in the `slopscan` directory to configure the server and analysis parameters.

**Template:**
```env
# Server Port
PORT=3001

# Brokk API Authentication
BROKK_API_KEY=your_brokk_api_key_here

# LLM Configuration
BROKK_PLANNER_MODEL=gemini-flash-3-preview

# SlopScan Economic Parameters (Optional Overrides)
SLOP_V_RATIO=1.0
SLOP_I_DRIFT=0.1
SLOP_E_IGNORE=50
SLOP_C_DAY=1200
SLOP_M_MULTIPLIER=1.5
SLOP_N_TEAM=6
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

## Docker Testing (Experimental)

> [!WARNING]
> Docker support is currently **untested** and may not work as expected. Local testing is recommended.

If you want to try testing via Docker, make sure you've built the JAR first, as the `docker-compose.yml` mounts the `../app` directory to provide the JAR to the container.

```bash
cd slopscan
docker compose up --build
```

## Troubleshooting

- **"Service Unavailable" / Health check fails**: This means the Express backend (`slopscan/server/index.js`) is either not running or returning an error on `/api/health`. Make sure the Node server is started.
- **Scan Hangs at "Pending" or "Cloned"**: The executor JAR likely failed to start. Check the Node.js server console output. The `ExecutorManager` in `executor.js` pipes stdout and stderr from the Java process to the Node console. If the JAR path is wrong, it will log an error.
- **Database Issues**: The backend uses SQLite. If you need a clean slate, delete the `slopscan.db` file in the `slopscan/server` directory.
