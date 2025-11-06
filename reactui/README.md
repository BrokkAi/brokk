# Brokk MOP Executor React UI

A modern React + TypeScript + Vite + Tailwind CSS web interface for the Brokk Headless Executor.

## Features

- ⚡ Fast development with Vite HMR (Hot Module Reloading)
- 🎨 Styled with Tailwind CSS for responsive design
- 📝 TypeScript for type safety
- 🔧 Configuration management (executor URL, bearer token)
- 📤 Session ZIP file upload
- 🔑 Manual session ID input for existing sessions
- ⚙️ Job creation with configurable mode and models
- 📊 Real-time job event display
- ❌ Job cancellation
- 💾 localStorage persistence for configuration
- 🔄 Multi-step workflow with visual progress indicators

## Prerequisites

- Node.js 16+ 
- npm or yarn or pnpm
- Access to a running Brokk Headless Executor instance

## Quick Start

### 1. Install dependencies

```bash
cd reactui
npm install
```

### 2. Start the development server

```bash
npm run dev
```

The UI will open automatically at `http://localhost:5174`.

### 3. Build for production

```bash
npm run build
```

The optimized bundle will be generated in the `dist/` directory.

### 4. Preview production build

```bash
npm run preview
```

## Configuration

### Executor Connection

Before using the UI, you must configure the connection to your Brokk Headless Executor:

1. **Executor URL**: The base URL of your running executor instance (e.g., `http://localhost:8080`)
2. **Bearer Token**: The authentication token for API requests

These values are:
- Automatically saved to browser localStorage
- Validated when entered (connection test runs automatically)
- Persisted across browser sessions

The connection status indicator shows:
- 🟢 **Connected**: Successfully connected to executor
- 🟡 **Connecting**: Testing connection...
- 🔴 **Error**: Connection failed (check URL and token)
- ⚪ **Disconnected**: Not yet configured

## Workflow

The UI guides you through a 4-step workflow:

### Step 1: Configure Executor

Enter your executor URL and bearer token. The connection is tested automatically.

### Step 2: Session Setup

You have two options:

#### Option A: Upload a New Session

1. Click **Choose File** to select a session ZIP file
2. The file is uploaded to `/v1/session`
3. The returned session ID is displayed and saved

#### Option B: Use an Existing Session ID

1. Enter a session ID from a previous upload
2. Click **Use Session** to proceed

💡 **Tip**: Session IDs are persistent. If you uploaded a session earlier, you can reuse its ID without re-uploading.

### Step 3: Create Job

Configure and submit a job to the executor:

1. **Task Command**: Describe the task you want the executor to perform (required)
2. **Mode**: Select execution mode
   - `ASK`: Question-answering mode
   - `CODE`: Code generation and modification
   - `ARCHITECT`: High-level architectural planning
3. **Models**: Configure AI models
   - **Planner Model**: Model for task planning (default: `claude-sonnet-4-5`)
   - **Code Model**: Model for code generation (default: `claude-haiku-4-5`)
4. **Options**:
   - ✅ **Auto-commit changes**: Automatically commit generated changes
   - ✅ **Auto-compress output**: Compress job output automatically

Click **Create Job** to submit. The job ID is displayed upon successful creation.

### Step 4: Monitor Job Execution

Once a job is created, you can:

#### Job Controls

- View current job state (Running, Completed, Failed, Cancelled)
- See job metadata (ID, creation time, last update)
- Cancel running jobs with the **Cancel Job** button

#### Event Viewer

Watch job events in real-time:

- Events are polled every 2 seconds
- Auto-scrolls to show newest events
- Color-coded event type badges
- Displays event data (JSON)
- **Pause/Resume** polling as needed
- **Clear** events to reset the view

Event types include:
- 🔵 `STARTED`: Job has started
- 🟣 `PROGRESS`: Progress update
- 📝 `LOG`: Log message
- 🟢 `COMPLETED`: Job finished successfully
- 🔴 `FAILED`: Job failed
- 🟡 `CANCELLED`: Job was cancelled

## Project Structure

```
reactui/
├── src/
│   ├── components/
│   │   ├── ConfigPanel.tsx      # Executor configuration
│   │   ├── SessionUploader.tsx  # Session ZIP upload
│   │   ├── JobCreator.tsx       # Job creation form
│   │   ├── JobControls.tsx      # Job status and cancellation
│   │   └── EventViewer.tsx      # Real-time event display
│   ├── main.tsx                 # React entry point
│   ├── App.tsx                  # Main App component (workflow orchestration)
│   ├── index.css                # Tailwind CSS imports
│   └── vite-env.d.ts            # Vite type definitions
├── index.html                   # HTML entry point
├── vite.config.ts               # Vite configuration
├── tsconfig.json                # TypeScript configuration
├── tailwind.config.js           # Tailwind CSS configuration
├── postcss.config.js            # PostCSS configuration
├── package.json                 # Project dependencies and scripts
└── README.md                    # This file
```

## API Integration

The UI communicates with the Brokk Headless Executor via REST API:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Test executor connectivity |
| `/v1/session` | POST | Upload session ZIP file |
| `/v1/jobs` | POST | Create a new job |
| `/v1/jobs/{jobId}` | GET | Get job status |
| `/v1/jobs/{jobId}/cancel` | POST | Cancel a running job |
| `/v1/jobs/{jobId}/events?after={seq}` | GET | Poll job events |

All requests include:
- `Authorization: Bearer {token}` header
- `Content-Type: application/json` (or `application/zip` for session upload)

Job creation includes an auto-generated `Idempotency-Key` header to prevent duplicate submissions.

## Development Tips

- Use **React DevTools** browser extension for component debugging
- Check browser console for API errors and logging
- Enable TypeScript strict mode for better type safety
- Leverage Tailwind CSS utility classes for rapid UI development
- Configuration is persisted in localStorage:
  - `executor_url`: Executor base URL
  - `executor_token`: Bearer token

## Common Issues

### Connection Failed
- Verify the executor is running and accessible
- Check the URL format (include protocol: `http://` or `https://`)
- Ensure the bearer token is correct
- Check for CORS issues in browser console

### Session Upload Failed
- Verify the file is a valid ZIP archive
- Check file size limits on your executor instance
- Ensure bearer token has upload permissions

### Job Creation Failed
- Ensure a valid session ID is set
- Verify the task command is not empty
- Check model names are correct

### Events Not Appearing
- Ensure the job ID is correct
- Check that the executor is generating events
- Verify polling is not paused (Resume button)

## Building and Deployment

For production deployment:

```bash
npm run build
```

This generates a fully optimized bundle in `dist/` that can be served by any static file server (nginx, Apache, CDN, etc.).

### Environment Variables

If you want to pre-configure the executor URL, you can set it in your static file server configuration or via a reverse proxy.

### CORS Configuration

Ensure your executor instance is configured to allow CORS requests from your UI domain:

```
Access-Control-Allow-Origin: https://your-ui-domain.com
Access-Control-Allow-Headers: Authorization, Content-Type, Idempotency-Key
Access-Control-Allow-Methods: GET, POST, OPTIONS
```

---

For more information about the Brokk Headless Executor, see the main project documentation.
