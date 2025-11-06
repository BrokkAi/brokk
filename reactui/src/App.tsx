import { useState } from 'react'
import ConfigPanel, { ExecutorConfig } from './components/ConfigPanel'
import SessionUploader from './components/SessionUploader'

function App() {
  const [config, setConfig] = useState<ExecutorConfig | null>(null)
  const [sessionId, setSessionId] = useState<string | null>(null)

  const handleConfigChange = (newConfig: ExecutorConfig) => {
    setConfig(newConfig)
  }

  const handleSessionUpload = (uploadedSessionId: string) => {
    setSessionId(uploadedSessionId)
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 to-slate-800">
      <div className="container mx-auto px-4 py-8">
        <h1 className="text-4xl font-bold text-white mb-8">
          Brokk MOP Executor UI
        </h1>
        
        <div className="grid gap-8">
          <ConfigPanel onConfigChange={handleConfigChange} />
          
          {config && (
            <SessionUploader config={config} onSessionUpload={handleSessionUpload} />
          )}
          
          {sessionId && (
            <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
              <h2 className="text-2xl font-bold mb-4">Current Session</h2>
              <p className="text-green-400">
                ✓ Session ready for job creation
              </p>
              <p className="text-sm text-gray-400 mt-2 break-all">
                Session ID: <code className="bg-slate-600 px-2 py-1 rounded">{sessionId}</code>
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export default App
