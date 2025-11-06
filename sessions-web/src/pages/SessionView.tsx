import { useState, useEffect, useRef } from 'react'
import { useParams, Link } from 'react-router-dom'
import '../styles/SessionView.css'

interface Session {
  id: string
  name: string
  branch: string
  status: 'active' | 'idle' | 'error'
}

interface OutputLine {
  timestamp: string
  text: string
  type: 'stdout' | 'stderr' | 'system'
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

function SessionView() {
  const { id } = useParams<{ id: string }>()
  const [session, setSession] = useState<Session | null>(null)
  const [output, setOutput] = useState<OutputLine[]>([])
  const [prompt, setPrompt] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const outputEndRef = useRef<HTMLDivElement>(null)
  const eventSourceRef = useRef<EventSource | null>(null)

  useEffect(() => {
    if (!id) return

    loadSession()
    connectStream()

    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close()
      }
    }
  }, [id])

  useEffect(() => {
    outputEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [output])

  const loadSession = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/sessions/${id}`)
      if (!response.ok) {
        throw new Error('Failed to load session')
      }
      const data = await response.json()
      setSession(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
    }
  }

  const connectStream = () => {
    if (!id) return

    const eventSource = new EventSource(`${API_BASE}/api/sessions/${id}/stream`)
    eventSourceRef.current = eventSource

    eventSource.onmessage = (event) => {
      const line: OutputLine = JSON.parse(event.data)
      setOutput((prev) => [...prev, line])
    }

    eventSource.onerror = () => {
      setError('Lost connection to session stream')
      eventSource.close()
    }

    setOutput((prev) => [
      ...prev,
      {
        timestamp: new Date().toISOString(),
        text: 'Connected to session stream',
        type: 'system',
      },
    ])
  }

  const sendPrompt = async () => {
    if (!prompt.trim() || !id) return

    try {
      setSending(true)
      setError(null)

      const response = await fetch(`${API_BASE}/api/sessions/${id}/prompt`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          prompt: prompt.trim(),
          mode: 'ask',
          plannerModel: 'gpt-4o',
          codeModel: 'gpt-4o-mini',
        }),
      })

      if (!response.ok) {
        throw new Error('Failed to send prompt')
      }

      setOutput((prev) => [
        ...prev,
        {
          timestamp: new Date().toISOString(),
          text: `> ${prompt.trim()}`,
          type: 'system',
        },
      ])

      setPrompt('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to send prompt')
    } finally {
      setSending(false)
    }
  }

  if (!session) {
    return (
      <div className="session-view">
        <div className="loading">Loading session...</div>
      </div>
    )
  }

  return (
    <div className="session-view">
      <div className="session-header">
        <div className="session-title">
          <Link to="/" className="back-link">← Back</Link>
          <h2>{session.name}</h2>
          <span className={`status-badge status-${session.status}`}>
            {session.status}
          </span>
        </div>
        <div className="session-meta">
          <span className="branch-name">Branch: {session.branch}</span>
        </div>
      </div>

      {error && (
        <div className="error-message">
          {error}
        </div>
      )}

      <div className="output-container">
        <div className="output-header">
          <h3>Output</h3>
          <button
            onClick={() => setOutput([])}
            className="btn-secondary btn-small"
          >
            Clear
          </button>
        </div>
        <div className="output-lines">
          {output.length === 0 ? (
            <div className="empty-output">No output yet. Send a prompt to get started.</div>
          ) : (
            output.map((line, index) => (
              <div key={index} className={`output-line output-${line.type}`}>
                <span className="timestamp">
                  {new Date(line.timestamp).toLocaleTimeString()}
                </span>
                <span className="text">{line.text}</span>
              </div>
            ))
          )}
          <div ref={outputEndRef} />
        </div>
      </div>

      <div className="prompt-container">
        <div className="prompt-header">
          <h3>Send Prompt (Ask Mode)</h3>
        </div>
        <div className="prompt-input-group">
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && e.ctrlKey) {
                sendPrompt()
              }
            }}
            placeholder="Enter your prompt... (Ctrl+Enter to send)"
            disabled={sending}
            rows={4}
            className="prompt-textarea"
          />
          <button
            onClick={sendPrompt}
            disabled={sending || !prompt.trim()}
            className="btn-primary"
          >
            {sending ? 'Sending...' : 'Send (Ctrl+Enter)'}
          </button>
        </div>
        <div className="prompt-info">
          Using planner: <strong>gpt-4o</strong> | code: <strong>gpt-4o-mini</strong>
        </div>
      </div>
    </div>
  )
}

export default SessionView
