import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import '../styles/SessionsPage.css'

interface Session {
  id: string
  name: string
  branch: string
  createdAt: string
  status: 'active' | 'idle' | 'error'
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

function SessionsPage() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [newSessionName, setNewSessionName] = useState('')
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    loadSessions()
  }, [])

  const loadSessions = async () => {
    try {
      setLoading(true)
      const response = await fetch(`${API_BASE}/api/sessions`)
      if (!response.ok) {
        throw new Error('Failed to load sessions')
      }
      const data = await response.json()
      setSessions(data)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }

  const createSession = async () => {
    if (!newSessionName.trim()) {
      setError('Session name is required')
      return
    }

    try {
      setCreating(true)
      setError(null)
      const response = await fetch(`${API_BASE}/api/sessions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          name: newSessionName.trim(),
        }),
      })

      if (!response.ok) {
        throw new Error('Failed to create session')
      }

      setNewSessionName('')
      await loadSessions()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create session')
    } finally {
      setCreating(false)
    }
  }

  const deleteSession = async (id: string) => {
    if (!confirm('Are you sure you want to delete this session?')) {
      return
    }

    try {
      const response = await fetch(`${API_BASE}/api/sessions/${id}`, {
        method: 'DELETE',
      })

      if (!response.ok) {
        throw new Error('Failed to delete session')
      }

      await loadSessions()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete session')
    }
  }

  if (loading) {
    return (
      <div className="sessions-page">
        <div className="loading">Loading sessions...</div>
      </div>
    )
  }

  return (
    <div className="sessions-page">
      <div className="page-header">
        <h2>Sessions</h2>
        <button onClick={loadSessions} className="btn-secondary" disabled={loading}>
          Refresh
        </button>
      </div>

      {error && (
        <div className="error-message">
          {error}
        </div>
      )}

      <div className="create-session">
        <h3>Create New Session</h3>
        <div className="create-form">
          <input
            type="text"
            placeholder="Session name"
            value={newSessionName}
            onChange={(e) => setNewSessionName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && createSession()}
            disabled={creating}
            className="session-name-input"
          />
          <button
            onClick={createSession}
            disabled={creating || !newSessionName.trim()}
            className="btn-primary"
          >
            {creating ? 'Creating...' : 'Create Session'}
          </button>
        </div>
      </div>

      <div className="sessions-list">
        <h3>Active Sessions ({sessions.length})</h3>
        {sessions.length === 0 ? (
          <div className="empty-state">
            No sessions yet. Create one to get started.
          </div>
        ) : (
          <div className="sessions-grid">
            {sessions.map((session) => (
              <div key={session.id} className="session-card">
                <div className="session-header">
                  <h4>{session.name}</h4>
                  <span className={`status-badge status-${session.status}`}>
                    {session.status}
                  </span>
                </div>
                <div className="session-info">
                  <div className="info-row">
                    <span className="label">Branch:</span>
                    <span className="value">{session.branch}</span>
                  </div>
                  <div className="info-row">
                    <span className="label">Created:</span>
                    <span className="value">
                      {new Date(session.createdAt).toLocaleString()}
                    </span>
                  </div>
                </div>
                <div className="session-actions">
                  <Link
                    to={`/session/${session.id}`}
                    className="btn-primary"
                  >
                    Open
                  </Link>
                  <button
                    onClick={() => deleteSession(session.id)}
                    className="btn-danger"
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

export default SessionsPage
