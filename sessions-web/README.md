# Brokk Sessions Web

A React-based web interface for managing multiple Brokk AI sessions, each running in its own Git worktree with a headless executor.

## Overview

This application provides a web UI to:
- Create and manage multiple Brokk sessions
- Stream executor output in real-time
- Send prompts in Ask mode (using plannerModel=gpt-4o, codeModel=gpt-4o-mini)
- View and switch between active sessions
- Merge sessions when needed

## Important Note

**This application is completely separate from `frontend-mop`** and does not modify or depend on it. The `frontend-mop` directory is for the embedded Brokk UI, while `sessions-web` is for the multi-session management interface.

## Development

### Prerequisites

- Node.js 18 or higher
- npm or pnpm

### Setup

1. Install dependencies:
   ```bash
   cd sessions-web
   npm install
   ```

2. Create a `.env` file from the example:
   ```bash
   cp .env.example .env
   ```

3. Update `.env` with your backend API URL if different from default:
   ```
   VITE_API_BASE_URL=http://localhost:8080
   ```

### Running the Dev Server

```bash
npm run dev
```

The application will start on `http://localhost:5174` (port 5174 to avoid conflicts with frontend-mop on 5173).

### Building for Production

```bash
npm run build
```

The production build will be output to the `dist` directory.

### Preview Production Build

```bash
npm run preview
```

## Architecture

### Pages

- **SessionsPage** (`/`): Lists all sessions, allows creation of new sessions, and provides session management controls
- **SessionView** (`/session/:id`): Displays streaming output from a specific session and provides a prompt input interface

### API Integration

The application communicates with the Brokk backend via REST APIs:
- `GET /api/sessions` - List all sessions
- `POST /api/sessions` - Create a new session
- `GET /api/sessions/:id` - Get session details
- `DELETE /api/sessions/:id` - Delete a session
- `POST /api/sessions/:id/prompt` - Send a prompt to a session
- `GET /api/sessions/:id/stream` - Stream executor output (SSE or WebSocket)

The backend manages Git worktrees, executor processes, and session state.

## Project Structure

```
sessions-web/
├── src/
│   ├── components/      # Reusable UI components
│   ├── pages/          # Page components
│   ├── styles/         # CSS styles
│   ├── types/          # TypeScript type definitions
│   ├── utils/          # Utility functions
│   ├── App.tsx         # Main app component with routing
│   └── main.tsx        # Application entry point
├── public/             # Static assets
├── .env.example        # Environment variable template
├── index.html          # HTML entry point
├── package.json        # Dependencies and scripts
├── tsconfig.json       # TypeScript configuration
├── vite.config.ts      # Vite configuration
└── README.md          # This file
```

## Backend Requirements

The backend must provide:
1. Session management endpoints
2. Git worktree creation and management
3. Headless executor spawning and process management
4. WebSocket or SSE streaming for executor output
5. CORS configuration to allow requests from the frontend

Refer to the backend implementation documentation for more details.
