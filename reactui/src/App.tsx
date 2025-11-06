import { useState } from 'react'
import ConfigPanel, { ExecutorConfig } from './components/ConfigPanel'

function App() {
  const [config, setConfig] = useState<ExecutorConfig | null>(null)

  const handleConfigChange = (newConfig: ExecutorConfig) => {
    setConfig(newConfig)
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
            <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
              <h2 className="text-2xl font-bold mb-4">Status</h2>
              <p className="text-green-400">
                ✓ Configuration loaded and connection established
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export default App
