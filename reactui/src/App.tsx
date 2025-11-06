import { useState } from 'react'
import ConfigPanel, { ExecutorConfig } from './components/ConfigPanel'
import SessionUploader from './components/SessionUploader'
import JobCreator from './components/JobCreator'
import JobControls from './components/JobControls'
import EventViewer from './components/EventViewer'

function App() {
  const [config, setConfig] = useState<ExecutorConfig | null>(null)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [jobId, setJobId] = useState<string | null>(null)
  const [globalError, setGlobalError] = useState<string | null>(null)
  const [manualSessionId, setManualSessionId] = useState<string>('')

  const handleConfigChange = (newConfig: ExecutorConfig) => {
    setConfig(newConfig)
    setGlobalError(null)
    setSessionId(null)
    setJobId(null)
  }

  const handleSessionUpload = (uploadedSessionId: string) => {
    setSessionId(uploadedSessionId)
    setJobId(null)
    setGlobalError(null)
  }

  const handleManualSessionSubmit = () => {
    if (manualSessionId.trim()) {
      setSessionId(manualSessionId.trim())
      setJobId(null)
      setGlobalError(null)
      setManualSessionId('')
    }
  }

  const handleJobCreated = (createdJobId: string) => {
    setJobId(createdJobId)
    setGlobalError(null)
  }

  const handleClearSession = () => {
    setSessionId(null)
    setJobId(null)
    setGlobalError(null)
  }

  const handleClearJob = () => {
    setJobId(null)
    setGlobalError(null)
  }

  const getStepStatus = (step: number): 'complete' | 'active' | 'pending' => {
    if (step === 1) return config ? 'complete' : 'active'
    if (step === 2) return sessionId ? 'complete' : config ? 'active' : 'pending'
    if (step === 3) return jobId ? 'complete' : sessionId ? 'active' : 'pending'
    if (step === 4) return jobId ? 'active' : 'pending'
    return 'pending'
  }

  const getStepColor = (status: 'complete' | 'active' | 'pending'): string => {
    switch (status) {
      case 'complete':
        return 'bg-green-500 text-white'
      case 'active':
        return 'bg-blue-500 text-white'
      case 'pending':
        return 'bg-gray-600 text-gray-400'
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 to-slate-800">
      <div className="container mx-auto px-4 py-8">
        <h1 className="text-4xl font-bold text-white mb-8">
          Brokk MOP Executor UI
        </h1>

        {globalError && (
          <div className="mb-6 p-4 rounded-lg bg-red-900/30 border-2 border-red-700 text-red-200">
            <div className="flex items-start gap-3">
              <div className="flex-shrink-0">
                <svg
                  className="w-6 h-6 text-red-400"
                  fill="none"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth="2"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                </svg>
              </div>
              <div className="flex-1">
                <h3 className="font-bold text-lg mb-1">Error</h3>
                <p className="text-sm whitespace-pre-wrap">{globalError}</p>
              </div>
              <button
                onClick={() => setGlobalError(null)}
                className="flex-shrink-0 text-red-400 hover:text-red-300 transition-colors"
              >
                <svg className="w-5 h-5" fill="none" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" viewBox="0 0 24 24" stroke="currentColor">
                  <path d="M6 18L18 6M6 6l12 12"></path>
                </svg>
              </button>
            </div>
          </div>
        )}

        <div className="mb-8 flex items-center justify-center gap-4">
          <div className="flex items-center gap-2">
            <div className={`w-10 h-10 rounded-full flex items-center justify-center font-bold ${getStepColor(getStepStatus(1))}`}>
              1
            </div>
            <span className="text-white font-medium">Configure</span>
          </div>
          <div className="w-16 h-1 bg-gray-600"></div>
          <div className="flex items-center gap-2">
            <div className={`w-10 h-10 rounded-full flex items-center justify-center font-bold ${getStepColor(getStepStatus(2))}`}>
              2
            </div>
            <span className="text-white font-medium">Session</span>
          </div>
          <div className="w-16 h-1 bg-gray-600"></div>
          <div className="flex items-center gap-2">
            <div className={`w-10 h-10 rounded-full flex items-center justify-center font-bold ${getStepColor(getStepStatus(3))}`}>
              3
            </div>
            <span className="text-white font-medium">Create Job</span>
          </div>
          <div className="w-16 h-1 bg-gray-600"></div>
          <div className="flex items-center gap-2">
            <div className={`w-10 h-10 rounded-full flex items-center justify-center font-bold ${getStepColor(getStepStatus(4))}`}>
              4
            </div>
            <span className="text-white font-medium">Monitor</span>
          </div>
        </div>
        
        <div className="grid gap-8">
          <ConfigPanel onConfigChange={handleConfigChange} />
          
          {config && !sessionId && (
            <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
              <h2 className="text-2xl font-bold mb-6">Step 2: Session Setup</h2>
              <p className="text-gray-300 mb-6">
                Upload a new session ZIP file or enter an existing session ID to continue.
              </p>
              
              <div className="space-y-6">
                <SessionUploader config={config} onSessionUpload={handleSessionUpload} />
                
                <div className="relative">
                  <div className="absolute inset-0 flex items-center">
                    <div className="w-full border-t border-slate-600"></div>
                  </div>
                  <div className="relative flex justify-center text-sm">
                    <span className="px-4 bg-slate-700 text-gray-400">OR</span>
                  </div>
                </div>

                <div>
                  <label htmlFor="manual-session-id" className="block text-sm font-medium mb-2">
                    Use Existing Session ID
                  </label>
                  <div className="flex gap-2">
                    <input
                      id="manual-session-id"
                      type="text"
                      placeholder="Enter session ID"
                      value={manualSessionId}
                      onChange={(e) => setManualSessionId(e.target.value)}
                      onKeyPress={(e) => {
                        if (e.key === 'Enter') {
                          handleManualSessionSubmit()
                        }
                      }}
                      className="flex-1 px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white placeholder-gray-400 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400"
                    />
                    <button
                      onClick={handleManualSessionSubmit}
                      disabled={!manualSessionId.trim()}
                      className="bg-blue-500 hover:bg-blue-600 disabled:bg-gray-600 disabled:cursor-not-allowed text-white font-bold py-2 px-6 rounded transition-colors"
                    >
                      Use Session
                    </button>
                  </div>
                  <p className="text-xs text-gray-400 mt-1">
                    If you already have a session ID from a previous upload, enter it here
                  </p>
                </div>
              </div>
            </div>
          )}
          
          {sessionId && (
            <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-2xl font-bold">Current Session</h2>
                <button
                  onClick={handleClearSession}
                  className="text-sm text-red-400 hover:text-red-300 transition-colors"
                >
                  Change Session
                </button>
              </div>
              <div className="bg-slate-600/50 p-4 rounded">
                <p className="text-green-400 font-medium mb-2">
                  ✓ Session ready for job creation
                </p>
                <p className="text-sm text-gray-400">
                  Session ID: <code className="bg-slate-700 px-2 py-1 rounded font-mono text-xs break-all">{sessionId}</code>
                </p>
              </div>
            </div>
          )}

          {config && sessionId && !jobId && (
            <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
              <h2 className="text-2xl font-bold mb-6">Step 3: Create Job</h2>
              <JobCreator config={config} sessionId={sessionId} onJobCreated={handleJobCreated} />
            </div>
          )}

          {config && jobId && (
            <>
              <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
                <div className="flex items-center justify-between mb-4">
                  <h2 className="text-2xl font-bold">Step 4: Monitor Job</h2>
                  <button
                    onClick={handleClearJob}
                    className="text-sm text-blue-400 hover:text-blue-300 transition-colors"
                  >
                    Create New Job
                  </button>
                </div>
                <p className="text-gray-300">
                  Track your job status and view real-time events below.
                </p>
              </div>

              <JobControls config={config} jobId={jobId} />
              <EventViewer config={config} jobId={jobId} />
            </>
          )}
        </div>
      </div>
    </div>
  )
}

export default App
