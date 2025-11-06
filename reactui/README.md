# Brokk MOP Executor React UI

A modern React + TypeScript + Vite + Tailwind CSS web interface for the Brokk Headless Executor.

## Features

- ⚡ Fast development with Vite HMR (Hot Module Reloading)
- 🎨 Styled with Tailwind CSS for responsive design
- 📝 TypeScript for type safety
- 🔧 Configuration management (executor URL, bearer token)
- 📤 Session ZIP file upload
- ⚙️ Job creation with configurable mode and models
- 📊 Real-time job event display
- ❌ Job cancellation
- 💾 localStorage persistence for configuration

## Prerequisites

- Node.js 16+ 
- npm or yarn or pnpm

## Setup

### Install dependencies

```bash
cd reactui
npm install
```

### Development server

Start the development server with hot module reloading:

```bash
npm run dev
```

The UI will open automatically at `http://localhost:5174`.

### Build for production

```bash
npm run build
```

The optimized bundle will be generated in the `dist/` directory.

### Preview production build

```bash
npm run preview
```

## Project Structure

```
reactui/
├── src/
│   ├── main.tsx           # React entry point
│   ├── App.tsx            # Main App component
│   ├── index.css          # Tailwind CSS imports
│   └── vite-env.d.ts      # Vite type definitions
├── index.html             # HTML entry point
├── vite.config.ts         # Vite configuration
├── tsconfig.json          # TypeScript configuration
├── tailwind.config.js     # Tailwind CSS configuration
├── postcss.config.js      # PostCSS configuration
├── package.json           # Project dependencies and scripts
└── README.md              # This file
```

## Configuration

The UI stores configuration in browser localStorage:

- **Executor URL**: The base URL of the Headless Executor API
- **Bearer Token**: Authentication token for the executor API

## API Integration

The UI communicates with the Headless Executor via its REST API. Refer to the executor's API documentation for endpoint specifications and request/response formats.

## Development Tips

- Use React DevTools browser extension for component debugging
- Check browser console for API errors and logging
- Enable TypeScript strict mode for better type safety
- Leverage Tailwind CSS utility classes for rapid UI development

## Building and Deployment

For production deployment:

```bash
npm run build
```

This generates a fully optimized bundle in `dist/` that can be served by any static file server.

---

For more information about the Brokk Headless Executor, see the main project documentation.
