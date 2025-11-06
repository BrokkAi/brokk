import { useState } from 'react'

function App() {
  const [count, setCount] = useState(0)

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 to-slate-800">
      <div className="container mx-auto px-4 py-8">
        <h1 className="text-4xl font-bold text-white mb-8">
          Brokk MOP Executor UI
        </h1>
        
        <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
          <p className="text-lg mb-4">
            React + TypeScript + Vite + Tailwind CSS scaffolding complete!
          </p>
          
          <button
            onClick={() => setCount((count) => count + 1)}
            className="bg-blue-500 hover:bg-blue-600 text-white font-bold py-2 px-4 rounded"
          >
            count is {count}
          </button>
          
          <p className="mt-4 text-sm text-slate-300">
            Edit <code className="bg-slate-800 px-2 py-1 rounded">src/App.tsx</code> and save to test HMR
          </p>
        </div>
      </div>
    </div>
  )
}

export default App
