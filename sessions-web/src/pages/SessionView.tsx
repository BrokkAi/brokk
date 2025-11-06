import { useState, useEffect, useRef } from 'react'
import { useParams, Link } from 'react-router-dom'
import '../styles/SessionView.css'

interface Session {
  id: string
  name: string
  branch: string
  worktreePath: string
  port: number
}

interface OutputLine {
  timestamp: string
  text: string
  type: 'stdout' | 'stderr' | 'system'
}

interface MergeResponse {
  status: string
  mode: string
  defaultBranch: string
  sessionBranch: string
  fastForward: boolean
  conflicts: boolean
  message: string
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || ''

function SessionView() {
  const { id } = useParams<{ id: string }>()
  const [session, setSession] = useState<Session | null>(null)
  const [output, setOutput] = useState<OutputLine[]>([])
  const [prompt, setPrompt] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [merging, setMerging] = useState(false)
  const [mergeMode, setMergeMode] = useState<'merge' | 'squash' | 'rebase'>('merge')
  const [showMergeDialog, setShowMergeDialog] = useState(false)
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
      const data: Session = await response.json()
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
      try {
        const line = JSON.parse(event.data) as OutputLine
        if (line && line.timestamp && line.text && line.type) {
          setOutput((prev) => [...prev, line])
          return
        }
        setOutput((prev) => [
          ...prev,
          { timestamp: new Date().toISOString(), text: String(event.data), type: 'stdout' },
        ])
      } catch {
        setOutput((prev) => [
          ...prev,
          { timestamp: new Date().toISOString(), text: String(event.data), type: 'stdout' },
        ])
      }
    }

    eventSource.onerror = () => {
      setError('Lost connection to session stream')
      eventSource.close()
    }

    setOutput((prev) => [
      ...prev,
      { timestamp: new Date().toISOString(), text: 'Connected to session stream', type: 'system' },
    ])
  }

  const sendPrompt = async () => {
    if (!prompt.trim() || !id) return

    try {
      setSending(true)
      setError(null)

      const response = await fetch(`${API_BASE}/api/sessions/${id}/prompt`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: prompt.trim() }),
      })

      if (!response.ok) {
        const t = await response.text().catch(() => '')
        throw new Error(t || 'Failed to send prompt')
      }

      const result = await response.json()

      setOutput((prev) => [
        ...prev,
        { timestamp: new Date().toISOString(), text: `> ${prompt.trim()}`, type: 'system' },
        {
          timestamp: new Date().toISOString(),
          text: `Job started: ${result.jobId}${result.status ? ` (${result.status})` : ''}`,
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

  const handleMerge = async (closeSession: boolean) => {
    if (!id) return

    try {
      setMerging(true)
      setError(null)

      const response = await fetch(`${API_BASE}/api/sessions/${id}/merge`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: mergeMode, close: closeSession }),
      })

      if (!response.ok) {
        const errorData = await response.json().catch(() => null)
        throw new Error(errorData?.message || 'Failed to merge session')
      }

      const result: MergeResponse = await response.json()

      setOutput((prev) => [
        ...prev,
        { timestamp: new Date().toISOString(), text: `Merge ${result.status}: ${result.message}`, type: result.conflicts ? 'stderr' : 'system' },
        {
          timestamp: new Date().toISOString(),
          text: `Mode: ${result.mode} | ${result.sessionBranch} → ${result.defaultBranch}`,
          type: 'system',
        },
      ])

      setShowMergeDialog(false)

      if (closeSession && result.status === 'merged') {
        setOutput((prev) => [
          ...prev,
          { timestamp: new Date().toISOString(), text: 'Session closed. Redirecting to sessions list...', type: 'system' },
        ])
        setTimeout(() => { window.location.href = '/' }, 2000)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to merge session')
    } finally {
      setMerging(false)
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
          <span className="status-badge status-active">active</span>
        </div>
        <div className="session-meta">
          <span className="branch-name">Branch: {session.branch}</span>
          <button onClick={() => setShowMergeDialog(true)} className="btn-secondary" disabled={merging}>
            Merge to Main
          </button>
        </div>
      </div>

      {error && <div className="error-message">{error}</div>}

      <div className="output-container">
        <div className="output-header">
          <h3>Output</h3>
          <button onClick={() => setOutput([])} className="btn-secondary btn-small">Clear</button>
        </div>
        <div className="output-lines">
          {output.length === 0 ? (
            <div className="empty-output">No output yet. Send a prompt to get started.</div>
          ) : (
            output.map((line, index) => (
              <div key={index} className={`output-line output-${line.type}`}>
                <span className="timestamp">{new Date(line.timestamp).toLocaleTimeString()}</span>
                <span className="text">{line.text}</span>
              </div>
            ))
          )}
          <div ref={outputEndRef} />
        </div>
      </div>

      <div className="prompt-container">
        <div className="prompt-header"><h3>Send Prompt (Ask Mode)</h3></div>
        <div className="prompt-input-group">
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && e.ctrlKey) sendPrompt() }}
            placeholder="Enter your prompt... (Ctrl+Enter to send)"
            disabled={sending}
            rows={4}
            className="prompt-textarea"
          />
          <button onClick={sendPrompt} disabled={sending || !prompt.trim()} className="btn-primary">
            {sending ? 'Sending...' : 'Send (Ctrl+Enter)'}
          </button>
        </div>
        <div className="prompt-info">
          Mode: <strong>Ask</strong> | Planner: <strong>gpt-5</strong> | Code: <strong>gpt-5-mini</strong>
        </div>
      </div>

      {showMergeDialog && (
        <div className="modal-overlay" onClick={() => setShowMergeDialog(false)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3>Merge Session</h3>
              <button className="modal-close" onClick={() => setShowMergeDialog(false)}>×</button>
            </div>
            <div className="modal-body">
              <p>Merge <strong>{session.branch}</strong> into the default branch.</p>
              <div className="form-group">
                <label htmlFor="merge-mode">Merge Mode:</label>
                <select
                  id="merge-mode"
                  value={mergeMode}
                  onChange={(e) => setMergeMode(e.target.value as 'merge' | 'squash' | 'rebase')}
                  disabled={merging}
                  className="merge-mode-select"
                >
                  <option value="merge">Merge (preserve commits)</option>
                  <option value="squash">Squash (single commit)</option>
                  <option value="rebase">Rebase (replay commits)</option>
                </select>
              </div>
            </div>
            <div className="modal-footer">
              <button onClick={() => handleMerge(false)} disabled={merging} className="btn-secondary">
                {merging ? 'Merging...' : 'Merge Only'}
              </button>
              <button onClick={() => handleMerge(true)} disabled={merging} className="btn-primary">
                {merging ? 'Merging...' : 'Merge & Close Session'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default SessionView
