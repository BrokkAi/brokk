import { useState, useEffect, useRef } from 'react';
import { ExecutorConfig } from './ConfigPanel';

interface EventViewerProps {
  config: ExecutorConfig;
  jobId: string;
}

interface JobEvent {
  seq: number;
  timestamp: string;
  type: string;
  data?: Record<string, unknown>;
}

interface EventsResponse {
  events: JobEvent[];
}

const getEventTypeBadgeColor = (type: string): string => {
  switch (type.toUpperCase()) {
    case 'STARTED':
      return 'bg-blue-500/20 text-blue-300 border-blue-600';
    case 'COMPLETED':
      return 'bg-green-500/20 text-green-300 border-green-600';
    case 'FAILED':
      return 'bg-red-500/20 text-red-300 border-red-600';
    case 'CANCELLED':
      return 'bg-yellow-500/20 text-yellow-300 border-yellow-600';
    case 'PROGRESS':
      return 'bg-purple-500/20 text-purple-300 border-purple-600';
    case 'LOG':
      return 'bg-gray-500/20 text-gray-300 border-gray-600';
    default:
      return 'bg-slate-500/20 text-slate-300 border-slate-600';
  }
};

const formatTimestamp = (timestamp: string): string => {
  try {
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch {
    return timestamp;
  }
};

export function EventViewer({ config, jobId }: EventViewerProps) {
  const [events, setEvents] = useState<JobEvent[]>([]);
  const [latestSeq, setLatestSeq] = useState<number>(0);
  const [isPolling, setIsPolling] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const eventsListRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to newest events
  useEffect(() => {
    if (eventsListRef.current) {
      eventsListRef.current.scrollTop = eventsListRef.current.scrollHeight;
    }
  }, [events]);

  // Poll for events every 2 seconds
  useEffect(() => {
    if (!isPolling) {
      return;
    }

    const pollEvents = async () => {
      try {
        const afterParam = latestSeq > 0 ? `?after=${latestSeq}` : '';
        const response = await fetch(
          `${config.executorUrl}/v1/jobs/${jobId}/events${afterParam}`,
          {
            method: 'GET',
            headers: {
              'Authorization': `Bearer ${config.bearerToken}`,
              'Content-Type': 'application/json',
            },
          }
        );

        if (!response.ok) {
          if (response.status === 404) {
            setError('Job not found');
          } else {
            setError(`Failed to fetch events: ${response.status}`);
          }
          return;
        }

        const data: EventsResponse = await response.json();

        if (data.events && data.events.length > 0) {
          setEvents((prevEvents) => [...prevEvents, ...data.events]);
          setLatestSeq(data.events[data.events.length - 1].seq);
          setError(null);
        }
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : 'Unknown error';
        setError(`Connection error: ${errorMessage}`);
      }
    };

    const pollInterval = setInterval(pollEvents, 2000);
    return () => clearInterval(pollInterval);
  }, [config, jobId, latestSeq, isPolling]);

  const handleTogglePolling = () => {
    setIsPolling(!isPolling);
  };

  const handleClearEvents = () => {
    setEvents([]);
    setLatestSeq(0);
    setError(null);
  };

  return (
    <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold">Job Events</h2>
        <div className="flex items-center gap-2">
          <button
            onClick={handleTogglePolling}
            className={`px-3 py-1 rounded text-sm font-medium transition-colors ${
              isPolling
                ? 'bg-green-500/20 text-green-300 border border-green-600 hover:bg-green-500/30'
                : 'bg-yellow-500/20 text-yellow-300 border border-yellow-600 hover:bg-yellow-500/30'
            }`}
          >
            {isPolling ? '⏸ Pause' : '▶ Resume'}
          </button>
          <button
            onClick={handleClearEvents}
            className="px-3 py-1 rounded text-sm font-medium bg-red-500/20 text-red-300 border border-red-600 hover:bg-red-500/30 transition-colors"
          >
            Clear
          </button>
        </div>
      </div>

      {/* Error Message */}
      {error && (
        <div className="mb-4 p-3 rounded bg-red-900/20 border border-red-700 text-red-300 text-sm">
          {error}
        </div>
      )}

      {/* Events List */}
      <div
        ref={eventsListRef}
        className="bg-slate-800 rounded border border-slate-600 overflow-y-auto max-h-96 space-y-2 p-3"
      >
        {events.length === 0 ? (
          <div className="flex items-center justify-center h-32 text-gray-400">
            <p className="text-center">
              {isPolling ? 'Waiting for events...' : 'Polling paused'}
            </p>
          </div>
        ) : (
          events.map((event) => (
            <div
              key={`${event.seq}-${event.timestamp}`}
              className="bg-slate-700 border border-slate-600 rounded p-3 text-sm space-y-2"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-gray-400 text-xs font-mono">
                  {formatTimestamp(event.timestamp)}
                </span>
                <span
                  className={`px-2 py-1 rounded text-xs font-semibold border ${getEventTypeBadgeColor(
                    event.type
                  )}`}
                >
                  {event.type}
                </span>
              </div>
              {event.data && Object.keys(event.data).length > 0 && (
                <div className="bg-slate-600/50 rounded p-2 text-gray-300 text-xs font-mono overflow-x-auto max-h-24 overflow-y-auto">
                  <pre>{JSON.stringify(event.data, null, 2)}</pre>
                </div>
              )}
            </div>
          ))
        )}
      </div>

      {/* Event Count */}
      <div className="mt-4 text-xs text-gray-400">
        Total events: <span className="font-semibold text-white">{events.length}</span>
        {latestSeq > 0 && <span className="ml-2">• Latest seq: {latestSeq}</span>}
      </div>
    </div>
  );
}

export default EventViewer;
