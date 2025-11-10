import { useState, useEffect, useRef } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import './App.css';

const KeyValueDisplay = ({ data }) => {
  // Primitive types
  if (data === null || data === undefined || typeof data !== 'object') {
    return <pre>{String(data)}</pre>;
  }

  // Arrays: show as a vertical list
  if (Array.isArray(data)) {
    if (data.length === 0) return <div className="kv-value">(empty)</div>;
    return (
      <div className="kv-list">
        {data.map((item, idx) => (
          <div key={idx} className="kv-list-item">
            {typeof item === 'object' && item !== null ? (
              <div className="key-value-display nested">
                <KeyValueDisplay data={item} />
              </div>
            ) : (
              <pre>{String(item)}</pre>
            )}
          </div>
        ))}
      </div>
    );
  }

  // Objects: render recursively as key-value grid
  const entries = Object.entries(data);
  if (entries.length === 0) return <div className="kv-value">(empty)</div>;

  return (
    <div className="key-value-display">
      {entries.map(([key, value]) => (
      <div key={`row-${key}`} className="kv-row">
      <div className="kv-key">{key}</div>
      <div className="kv-value">
      {value !== null && typeof value === 'object' ? (
      <div className="key-value-display nested">
      <KeyValueDisplay data={value} />
      </div>
      ) : (
      <span>{String(value)}</span>
      )}
      </div>
      </div>
      ))}
    </div>
  );
};

function App() {
  const [message, setMessage] = useState('');
  const [events, setEvents] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [currentJobId, setCurrentJobId] = useState(null);
  const [status, setStatus] = useState('Connecting...');
  const [progressMessage, setProgressMessage] = useState('');
  const [tokenBuffer, setTokenBuffer] = useState('');
  const [totalCost, setTotalCost] = useState(0);
  const [totalTokens, setTotalTokens] = useState(0);
  const [requestCount, setRequestCount] = useState(0);
  const eventsEndRef = useRef(null);
  const pollingRef = useRef(null);

  // Detect and hide context baseline messages
  function isContextBaselineEvent(ev) {
    const type = (ev?.type || '').toLowerCase();
    const data = ev?.data;

    // Direct type matches that commonly represent baseline/system context
    if (type === 'context_baseline' || type === 'baseline_context' || type === 'system_prompt') {
      return true;
    }

    // System events explicitly marked as baseline
    if (type === 'system' && data && (data.source === 'baseline' || data.label === 'context_baseline' || data.context === 'baseline')) {
      return true;
    }

    // Message-like events where role is system and it is flagged as baseline
    if ((type === 'message' || type === 'assistant_message' || type === 'system') &&
        data &&
        data.role === 'system' &&
        (data.isBaseline === true ||
         data.baseline === true ||
         (data.tags && (data.tags.context === 'baseline' || data.tags.baseline === true)))) {
      return true;
    }

    // Heuristic: text that starts with "Context baseline" (or bracketed variant)
    const msg = typeof data === 'string' ? data : (data && (data.message || data.text || ''));
    if (typeof msg === 'string' && /^\s*\[?context baseline\b/i.test(msg)) {
      return true;
    }

    return false;
  }

  useEffect(() => {
    checkHealth();
  }, []);

  useEffect(() => {
    eventsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [events]);

  useEffect(() => {
    if (currentJobId) {
      startPolling(currentJobId);
    }
    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
      }
    };
  }, [currentJobId]);

  async function checkHealth() {
    try {
      const response = await fetch('/api/health');
      const data = await response.json();
      if (data.executorReady && data.sessionId) {
        setStatus('Ready');
      } else {
        setStatus('Initializing...');
        setTimeout(checkHealth, 1000);
      }
    } catch (err) {
      setStatus('Error connecting');
      setTimeout(checkHealth, 2000);
    }
  }

  async function handleSubmit(e) {
    e.preventDefault();
    if (!message.trim() || isLoading) return;

    setIsLoading(true);
    setEvents([]);
    setTokenBuffer('');
    setProgressMessage('Processing...');
    
    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: message.trim() })
      });

      if (!response.ok) {
        const error = await response.json();
        setEvents([{ type: 'ERROR', data: { title: 'Request Failed', message: error.message || 'Failed to submit job' } }]);
        setIsLoading(false);
        setProgressMessage('');
        return;
      }

      const data = await response.json();
      setCurrentJobId(data.jobId);
      setMessage('');
    } catch (err) {
      setEvents([{ type: 'ERROR', data: { title: 'Network Error', message: err.message } }]);
      setIsLoading(false);
      setProgressMessage('');
    }
  }

  async function startPolling(jobId) {
    let after = 0;
    
    const poll = async () => {
      try {
        const eventsResponse = await fetch(`/api/jobs/${jobId}/events?after=${after}`);
        if (eventsResponse.ok) {
          const eventsData = await eventsResponse.json();
          if (eventsData.events && eventsData.events.length > 0) {
            const newEvents = [];
            
            for (const event of eventsData.events) {
              const eventType = event.type?.toLowerCase() || '';
              
              // Handle NOTIFICATION for job started
              if (eventType === 'notification') {
                const notifLevel = event.data?.level?.toLowerCase();
                const notifMsg = event.data?.message || '';
                if (notifLevel === 'info' && notifMsg.includes('Job started')) {
                  setProgressMessage(notifMsg);
                }
                // Skip adding to events
                continue;
              }

              // Hide context baseline messages
              if (isContextBaselineEvent(event)) {
                continue;
              }
              
              // Handle LLM_TOKEN/LM_TOKEN: render tokens as markdown content
              // - push every token event to the stream (renderer shows only token content as Markdown)
              // - also reflect the latest token in the spinner for live progress
              if (eventType === 'lm_token' || eventType === 'llm_token') {
                const token = event.data?.token;
                if (typeof token === 'string' && token.length > 0) {
                  setProgressMessage(token);
                  setTokenBuffer(prev => prev + token);
                }
                continue;
              }
              
              // Handle STATE_HINT for re-enabling input
              if (eventType === 'state_hint') {
                if (event.data?.name === 'actionButtonsEnabled' && event.data?.value === true) {
                  setIsLoading(false);
                  setProgressMessage('');
                }
                // Don't display state hints
                continue;
              }
              
              // Handle BALANCE_CHECK to accumulate costs
              if (eventType === 'balance_check' || eventType === 'cost') {
                const cost = parseFloat(event.data?.cost) || 0;
                const tokens = parseInt(event.data?.tokens) || 0;
                setTotalCost(prev => prev + cost);
                setTotalTokens(prev => prev + tokens);
                // Don't display balance check events
                continue;
              }
              
              // Add all other events to display
              newEvents.push(event);
            }
            
            if (newEvents.length > 0) {
              setEvents(prev => [...prev, ...newEvents]);
            }
            
            after = eventsData.nextAfter;
          }
        }

        const statusResponse = await fetch(`/api/jobs/${jobId}`);
        if (statusResponse.ok) {
          const statusData = await statusResponse.json();
          if (statusData.state === 'completed' || statusData.state === 'failed') {
            setIsLoading(false);
            setCurrentJobId(null);
            setProgressMessage('');
            setRequestCount(prev => prev + 1);
            if (pollingRef.current) {
              clearInterval(pollingRef.current);
              pollingRef.current = null;
            }
          }
        }
      } catch (err) {
        console.error('Polling error:', err);
      }
    };

    await poll();
    pollingRef.current = setInterval(poll, 500);
  }

  function renderEvent(event, index) {
    const eventType = event.type || 'unknown';
    const eventData = event.data;

    // Hide context baseline messages at render time as a safeguard
    if (isContextBaselineEvent(event)) {
      return null;
    }

    // Handle LLM_TOKEN or LM_TOKEN - render tokens as markdown
    if (eventType.toLowerCase() === 'lm_token' || eventType.toLowerCase() === 'llm_token') {
      const token = eventData?.token?.trim();
      if (token) {
        const cleanHtml = DOMPurify.sanitize(marked.parse(token));
        return (
          <div key={index} className="event-content-markdown" dangerouslySetInnerHTML={{ __html: cleanHtml }} />
        );
      }
      return null;
    }

    // Handle ERROR events with title and message
    if (eventType === 'ERROR' || eventType.toLowerCase() === 'error') {
      const title = eventData?.title || 'Error';
      const errorMsg = typeof eventData === 'string' 
        ? eventData 
        : eventData?.message || eventData?.error || JSON.stringify(eventData);
      return (
        <div key={index} className="event event-error">
          <div className="event-type">❌ {title}</div>
          <div className="event-content">
            {errorMsg.split('\n').map((line, i) => (
              <div key={i}>{line || '\u00A0'}</div>
            ))}
          </div>
        </div>
      );
    }

    // Handle tool calls
    if (eventType === 'tool_call' || eventType === 'function_call') {
      const toolName = eventData?.name || eventData?.function?.name || 'unknown';
      const toolArgs = eventData?.arguments || eventData?.function?.arguments || {};
      return (
        <div key={index} className="event event-tool">
          <div className="event-type">🔧 Tool Call: {toolName}</div>
          <div className="event-content">
            <KeyValueDisplay data={toolArgs} />
          </div>
        </div>
      );
    }

    // Handle tool results
    if (eventType === 'tool_result' || eventType === 'function_result') {
      const result = typeof eventData === 'string' ? eventData : eventData?.result || JSON.stringify(eventData);
      return (
        <div key={index} className="event event-tool-result">
          <div className="event-type">✓ Tool Result</div>
          <div className="event-content">
            {result.split('\n').slice(0, 20).map((line, i) => (
              <div key={i}>{line || '\u00A0'}</div>
            ))}
            {result.split('\n').length > 20 && <div className="event-truncated">... ({result.split('\n').length - 20} more lines)</div>}
          </div>
        </div>
      );
    }

    // Handle confirmation requests
    if (eventType === 'CONFIRM_REQUEST' || eventType === 'confirm_request') {
      const msg = eventData?.message || '';
      const title = eventData?.title || '';
      return (
        <div key={index} className="event event-confirm">
          <div className="event-type">⚠️ Confirmation Requested</div>
          <div className="event-content">
            {title && <div><strong>{title}</strong></div>}
            {msg && <div>{msg}</div>}
            <div className="event-note">→ Auto-approved in headless mode</div>
          </div>
        </div>
      );
    }

    // Handle message events
    if (eventType === 'message' || eventType === 'response' || eventType === 'assistant_message') {
      let content = '';
      if (typeof eventData === 'string') {
        content = eventData;
      } else if (eventData?.content && Array.isArray(eventData.content)) {
        content = eventData.content
          .filter(block => block.type === 'text')
          .map(block => block.text)
          .join('\n');
      } else if (eventData?.content) {
        content = String(eventData.content);
      } else if (eventData?.text) {
        content = String(eventData.text);
      } else {
        return (
          <div key={index} className="event event-message">
            <div className="event-type">{eventType.replace('_', ' ')}</div>
            <div className="event-content">
              <KeyValueDisplay data={eventData} />
            </div>
          </div>
        );
      }

      return (
        <div key={index} className="event event-message">
          <div className="event-type">{eventType.replace('_', ' ')}</div>
          <div className="event-content">
            {content.split('\n').map((line, i) => (
              <div key={i}>{line || '\u00A0'}</div>
            ))}
          </div>
        </div>
      );
    }

    // Handle info/status events
    if (eventType === 'status' || eventType === 'info' || eventType === 'system') {
      const msg = typeof eventData === 'string' ? eventData : eventData?.message || JSON.stringify(eventData);
      return (
        <div key={index} className="event event-info">
          <div className="event-type">ℹ️ {eventType}</div>
          <div className="event-content">{msg}</div>
        </div>
      );
    }

    // Default: show key-value display for unknown types
    return (
      <div key={index} className="event event-default">
        <div className="event-type">{eventType}</div>
        <div className="event-content">
          <KeyValueDisplay data={eventData} />
        </div>
      </div>
    );
  }

  return (
    <div className="App">
      <header className="App-header">
        <h1>Brokk Chat</h1>
        <div className="status">Status: {status}</div>
        <div className="stats">
          Requests: {requestCount} | Cost: ${totalCost.toFixed(4)} | Tokens: {totalTokens}
        </div>
      </header>
      
      <main className="App-main">
        <div className="events-container">
          {events.length === 0 && !isLoading && (
            <div className="placeholder">Ask a question to get started...</div>
          )}
          {events.map((event, index) => renderEvent(event, index))}
          {tokenBuffer && (
            <div className="event event-message">
              <div className="event-type">LLM Output</div>
              <div
                className="event-content event-content-markdown"
                dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(marked.parse(tokenBuffer)) }}
              />
            </div>
          )}
          {isLoading && events.length === 0 && (
            <div className="loading">Processing...</div>
          )}
          <div ref={eventsEndRef} />
        </div>

        {isLoading && progressMessage && (
          <div className="progress-indicator">
            <div className="spinner"></div>
            <span>{progressMessage}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="input-form">
          <input
            type="text"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder="Ask a question..."
            disabled={isLoading || status !== 'Ready'}
            className="message-input"
          />
          <button 
            type="submit" 
            disabled={isLoading || !message.trim() || status !== 'Ready'}
            className="submit-button"
          >
            {isLoading ? 'Processing...' : 'Send'}
          </button>
        </form>
      </main>
    </div>
  );
}

export default App;
