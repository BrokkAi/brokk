import { useState, useEffect } from 'react';
import { ExecutorConfig } from './ConfigPanel';

interface JobControlsProps {
  config: ExecutorConfig;
  jobId: string;
}

interface JobStatus {
  jobId: string;
  state: string;
  createdAt: string;
  updatedAt: string;
}

interface JobStatusResponse {
  job: JobStatus;
}

type JobState = 'running' | 'completed' | 'failed' | 'cancelled' | 'unknown';

interface ControlStatus {
  status: 'idle' | 'cancelling' | 'cancelled' | 'error';
  message: string;
}

const normalizeJobState = (state: string): JobState => {
  const normalized = state.toUpperCase();
  switch (normalized) {
    case 'RUNNING':
      return 'running';
    case 'COMPLETED':
      return 'completed';
    case 'FAILED':
      return 'failed';
    case 'CANCELLED':
      return 'cancelled';
    default:
      return 'unknown';
  }
};

const getStateBadgeColor = (state: JobState): string => {
  switch (state) {
    case 'running':
      return 'bg-blue-500/20 text-blue-300 border-blue-600';
    case 'completed':
      return 'bg-green-500/20 text-green-300 border-green-600';
    case 'failed':
      return 'bg-red-500/20 text-red-300 border-red-600';
    case 'cancelled':
      return 'bg-yellow-500/20 text-yellow-300 border-yellow-600';
    case 'unknown':
    default:
      return 'bg-gray-500/20 text-gray-300 border-gray-600';
  }
};

const getStateIndicatorColor = (state: JobState): string => {
  switch (state) {
    case 'running':
      return 'bg-blue-500 animate-pulse';
    case 'completed':
      return 'bg-green-500';
    case 'failed':
      return 'bg-red-500';
    case 'cancelled':
      return 'bg-yellow-500';
    case 'unknown':
    default:
      return 'bg-gray-500';
  }
};

const formatTimestamp = (timestamp: string): string => {
  try {
    const date = new Date(timestamp);
    return date.toLocaleString([], {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return timestamp;
  }
};

export function JobControls({ config, jobId }: JobControlsProps) {
  const [jobStatus, setJobStatus] = useState<JobStatus | null>(null);
  const [jobState, setJobState] = useState<JobState>('unknown');
  const [isLoadingStatus, setIsLoadingStatus] = useState(true);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [controlStatus, setControlStatus] = useState<ControlStatus>({
    status: 'idle',
    message: 'Ready',
  });
  const [isCancelling, setIsCancelling] = useState(false);

  useEffect(() => {
    const fetchJobStatus = async () => {
      try {
        const response = await fetch(`${config.executorUrl}/v1/jobs/${jobId}`, {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${config.bearerToken}`,
            'Content-Type': 'application/json',
          },
        });

        if (!response.ok) {
          if (response.status === 404) {
            setStatusError('Job not found');
          } else {
            setStatusError(`Failed to fetch job status: ${response.status}`);
          }
          setIsLoadingStatus(false);
          return;
        }

        const data: JobStatusResponse = await response.json();
        setJobStatus(data.job);
        setJobState(normalizeJobState(data.job.state));
        setStatusError(null);
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Unknown error';
        setStatusError(`Connection error: ${errorMessage}`);
      } finally {
        setIsLoadingStatus(false);
      }
    };

    setIsLoadingStatus(true);
    fetchJobStatus();

    const pollInterval = setInterval(fetchJobStatus, 5000);
    return () => clearInterval(pollInterval);
  }, [config, jobId]);

  const handleCancelJob = async () => {
    if (!config?.executorUrl || !config?.bearerToken) {
      setControlStatus({
        status: 'error',
        message: 'Executor configuration required',
      });
      return;
    }

    setIsCancelling(true);
    setControlStatus({
      status: 'cancelling',
      message: 'Cancelling job...',
    });

    try {
      const response = await fetch(`${config.executorUrl}/v1/jobs/${jobId}/cancel`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${config.bearerToken}`,
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        let errorMessage = `Cancellation failed: ${response.status}`;
        try {
          const errorData = await response.json();
          errorMessage = errorData.message || errorMessage;
        } catch {
          // If response is not JSON, use status-based message
        }
        setControlStatus({
          status: 'error',
          message: errorMessage,
        });
        setIsCancelling(false);
        return;
      }

      setControlStatus({
        status: 'cancelled',
        message: 'Job cancelled successfully',
      });

      setTimeout(() => {
        const fetchUpdatedStatus = async () => {
          try {
            const response = await fetch(`${config.executorUrl}/v1/jobs/${jobId}`, {
              method: 'GET',
              headers: {
                'Authorization': `Bearer ${config.bearerToken}`,
                'Content-Type': 'application/json',
              },
            });

            if (response.ok) {
              const data: JobStatusResponse = await response.json();
              setJobStatus(data.job);
              setJobState(normalizeJobState(data.job.state));
            }
          } catch {
            // Silently ignore errors on refresh
          }
        };

        fetchUpdatedStatus();
      }, 1000);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error occurred';
      setControlStatus({
        status: 'error',
        message: `Connection error: ${errorMessage}`,
      });
    } finally {
      setIsCancelling(false);
    }
  };

  const isJobActive = jobState === 'running';
  const isJobTerminated = jobState === 'completed' || jobState === 'failed' || jobState === 'cancelled';

  return (
    <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold">Job Controls</h2>
      </div>

      {statusError && (
        <div className="mb-4 p-3 rounded bg-red-900/20 border border-red-700 text-red-300 text-sm">
          {statusError}
        </div>
      )}

      {controlStatus.status !== 'idle' && (
        <div
          className={`mb-4 p-3 rounded text-sm ${
            controlStatus.status === 'cancelled'
              ? 'bg-green-900/20 border border-green-700 text-green-300'
              : controlStatus.status === 'error'
                ? 'bg-red-900/20 border border-red-700 text-red-300'
                : 'bg-yellow-900/20 border border-yellow-700 text-yellow-300'
          }`}
        >
          {controlStatus.message}
        </div>
      )}

      {isLoadingStatus ? (
        <div className="flex items-center justify-center p-6 text-gray-400">
          <p>Loading job status...</p>
        </div>
      ) : jobStatus ? (
        <div className="space-y-4">
          <div className="flex items-center gap-4">
            <div>
              <p className="text-sm font-medium text-gray-400 mb-2">Current State</p>
              <div className="flex items-center gap-2">
                <div className={`w-3 h-3 rounded-full ${getStateIndicatorColor(jobState)}`} />
                <span
                  className={`px-3 py-1 rounded font-semibold border text-sm ${getStateBadgeColor(
                    jobState
                  )}`}
                >
                  {jobState.toUpperCase()}
                </span>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4 bg-slate-600/50 p-4 rounded">
            <div>
              <p className="text-xs text-gray-400">Job ID</p>
              <p className="text-sm font-mono break-all">{jobStatus.jobId}</p>
            </div>
            <div>
              <p className="text-xs text-gray-400">Created</p>
              <p className="text-sm">{formatTimestamp(jobStatus.createdAt)}</p>
            </div>
            <div>
              <p className="text-xs text-gray-400">Updated</p>
              <p className="text-sm">{formatTimestamp(jobStatus.updatedAt)}</p>
            </div>
            <div>
              <p className="text-xs text-gray-400">Status</p>
              <p className="text-sm capitalize">{jobState}</p>
            </div>
          </div>

          <button
            onClick={handleCancelJob}
            disabled={isCancelling || !isJobActive}
            className={`w-full font-bold py-2 px-4 rounded transition-colors ${
              isJobActive
                ? 'bg-red-500 hover:bg-red-600 text-white'
                : 'bg-gray-600 cursor-not-allowed text-gray-400'
            }`}
          >
            {isCancelling ? 'Cancelling Job...' : 'Cancel Job'}
          </button>

          {isJobTerminated && (
            <p className="text-xs text-gray-400 text-center">
              Job has already completed or been cancelled
            </p>
          )}
        </div>
      ) : (
        <div className="text-center text-gray-400 py-6">
          <p>Unable to load job status</p>
        </div>
      )}
    </div>
  );
}

export default JobControls;
