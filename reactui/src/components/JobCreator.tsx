import { useState } from 'react';
import { ExecutorConfig } from './ConfigPanel';

interface JobCreatorProps {
  config: ExecutorConfig | null;
  sessionId: string;
  onJobCreated?: (jobId: string) => void;
}

interface JobCreationStatus {
  state: 'idle' | 'submitting' | 'success' | 'error';
  jobId?: string;
  message: string;
}

type JobMode = 'ASK' | 'CODE' | 'ARCHITECT';

interface JobRequest {
  command: string;
  mode: JobMode;
  models: {
    planner: string;
    code: string;
  };
  autoCommit: boolean;
  autoCompress: boolean;
}

const generateIdempotencyKey = (): string => {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
};

export function JobCreator({ config, sessionId }: JobCreatorProps) {
  const [command, setCommand] = useState<string>('');
  const [mode, setMode] = useState<JobMode>('CODE');
  const [plannerModel, setPlannerModel] = useState<string>('claude-sonnet-4-5');
  const [codeModel, setCodeModel] = useState<string>('claude-haiku-4-5');
  const [autoCommit, setAutoCommit] = useState<boolean>(true);
  const [autoCompress, setAutoCompress] = useState<boolean>(true);
  const [creationStatus, setCreationStatus] = useState<JobCreationStatus>({
    state: 'idle',
    message: 'Ready to create job',
  });
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!command.trim()) {
      setCreationStatus({
        state: 'error',
        message: 'Task command cannot be empty',
      });
      return;
    }

    if (!config?.executorUrl || !config?.bearerToken) {
      setCreationStatus({
        state: 'error',
        message: 'Executor configuration required',
      });
      return;
    }

    await submitJob();
  };

  const submitJob = async () => {
    setIsSubmitting(true);
    setCreationStatus({
      state: 'submitting',
      message: 'Creating job...',
    });

    try {
      const jobRequest: JobRequest = {
        command: command.trim(),
        mode,
        models: {
          planner: plannerModel,
          code: codeModel,
        },
        autoCommit,
        autoCompress,
      };

      const idempotencyKey = generateIdempotencyKey();

      const response = await fetch(`${config!.executorUrl}/v1/jobs`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${config!.bearerToken}`,
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey,
        },
        body: JSON.stringify(jobRequest),
      });

      if (!response.ok) {
        let errorMessage = `Job creation failed: ${response.status}`;
        try {
          const errorData = await response.json();
          errorMessage = errorData.message || errorMessage;
        } catch {
          // If response is not JSON, use status-based message
        }
        setCreationStatus({
          state: 'error',
          message: errorMessage,
        });
        setIsSubmitting(false);
        return;
      }

      const data = await response.json();
      const jobId = data.jobId || data.id;

      if (!jobId) {
        setCreationStatus({
          state: 'error',
          message: 'Server did not return a job ID',
        });
        setIsSubmitting(false);
        return;
      }

      setCreationStatus({
        state: 'success',
        jobId,
        message: `Job created successfully`,
      });

      onJobCreated?.(jobId);

      // Reset form
      setCommand('');
      setMode('CODE');
      setPlannerModel('claude-sonnet-4-5');
      setCodeModel('claude-haiku-4-5');
      setAutoCommit(true);
      setAutoCompress(true);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error occurred';
      setCreationStatus({
        state: 'error',
        message: `Connection error: ${errorMessage}`,
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  const getStatusColor = () => {
    switch (creationStatus.state) {
      case 'success':
        return 'text-green-400';
      case 'submitting':
        return 'text-yellow-400';
      case 'error':
        return 'text-red-400';
      case 'idle':
      default:
        return 'text-gray-400';
    }
  };

  const getStatusBgColor = () => {
    switch (creationStatus.state) {
      case 'success':
        return 'bg-green-900/20 border-green-700';
      case 'submitting':
        return 'bg-yellow-900/20 border-yellow-700';
      case 'error':
        return 'bg-red-900/20 border-red-700';
      case 'idle':
      default:
        return 'bg-gray-900/20 border-gray-700';
    }
  };

  const getStatusIndicatorColor = () => {
    switch (creationStatus.state) {
      case 'success':
        return 'bg-green-500';
      case 'submitting':
        return 'bg-yellow-500 animate-pulse';
      case 'error':
        return 'bg-red-500';
      case 'idle':
      default:
        return 'bg-gray-500';
    }
  };

  const isConfigured = config?.executorUrl && config?.bearerToken;

  return (
    <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
      <h2 className="text-2xl font-bold mb-6">Create Job</h2>

      {/* Job Creation Status */}
      <div className={`mb-6 p-4 rounded-lg border ${getStatusBgColor()}`}>
        <div className="flex items-center gap-3">
          <div className={`w-3 h-3 rounded-full ${getStatusIndicatorColor()}`} />
          <div>
            <p className={`font-semibold ${getStatusColor()}`}>
              {creationStatus.message}
            </p>
            {creationStatus.jobId && (
              <p className="text-sm text-gray-300 mt-1 break-all">
                Job ID: <code className="bg-slate-600 px-2 py-1 rounded">{creationStatus.jobId}</code>
              </p>
            )}
          </div>
        </div>
      </div>

      {/* Job Creation Form */}
      <form onSubmit={handleSubmit} className="space-y-4">
        {/* Task Command Input */}
        <div>
          <label htmlFor="task-command" className="block text-sm font-medium mb-2">
            Task Command
          </label>
          <textarea
            id="task-command"
            placeholder="Enter your task command or description"
            value={command}
            onChange={(e) => setCommand(e.target.value)}
            disabled={isSubmitting || !isConfigured}
            rows={4}
            className="w-full px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white placeholder-gray-400 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400 disabled:opacity-50 disabled:cursor-not-allowed resize-none"
          />
          <p className="text-xs text-gray-400 mt-1">
            Describe the task you want the executor to perform
          </p>
        </div>

        {/* Mode Selector */}
        <div>
          <label htmlFor="job-mode" className="block text-sm font-medium mb-2">
            Mode
          </label>
          <select
            id="job-mode"
            value={mode}
            onChange={(e) => setMode(e.target.value as JobMode)}
            disabled={isSubmitting || !isConfigured}
            className="w-full px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <option value="ASK">ASK</option>
            <option value="CODE">CODE</option>
            <option value="ARCHITECT">ARCHITECT</option>
          </select>
          <p className="text-xs text-gray-400 mt-1">
            Select the execution mode for this job
          </p>
        </div>

        {/* Models Grid */}
        <div className="grid grid-cols-2 gap-4">
          {/* Planner Model */}
          <div>
            <label htmlFor="planner-model" className="block text-sm font-medium mb-2">
              Planner Model
            </label>
            <input
              id="planner-model"
              type="text"
              placeholder="claude-sonnet-4-5"
              value={plannerModel}
              onChange={(e) => setPlannerModel(e.target.value)}
              disabled={isSubmitting || !isConfigured}
              className="w-full px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white placeholder-gray-400 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400 disabled:opacity-50 disabled:cursor-not-allowed"
            />
            <p className="text-xs text-gray-400 mt-1">
              Model for planning tasks
            </p>
          </div>

          {/* Code Model */}
          <div>
            <label htmlFor="code-model" className="block text-sm font-medium mb-2">
              Code Model
            </label>
            <input
              id="code-model"
              type="text"
              placeholder="claude-haiku-4-5"
              value={codeModel}
              onChange={(e) => setCodeModel(e.target.value)}
              disabled={isSubmitting || !isConfigured}
              className="w-full px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white placeholder-gray-400 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400 disabled:opacity-50 disabled:cursor-not-allowed"
            />
            <p className="text-xs text-gray-400 mt-1">
              Model for code generation
            </p>
          </div>
        </div>

        {/* Checkboxes */}
        <div className="space-y-3 bg-slate-600/50 p-4 rounded">
          <label className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={autoCommit}
              onChange={(e) => setAutoCommit(e.target.checked)}
              disabled={isSubmitting || !isConfigured}
              className="w-4 h-4 accent-blue-500 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            />
            <span className="text-sm">Auto-commit changes</span>
          </label>

          <label className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={autoCompress}
              onChange={(e) => setAutoCompress(e.target.checked)}
              disabled={isSubmitting || !isConfigured}
              className="w-4 h-4 accent-blue-500 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            />
            <span className="text-sm">Auto-compress output</span>
          </label>
        </div>

        {/* Submit Button */}
        <button
          type="submit"
          disabled={isSubmitting || !isConfigured || !command.trim()}
          className="w-full bg-blue-500 hover:bg-blue-600 disabled:bg-gray-600 disabled:cursor-not-allowed text-white font-bold py-2 px-4 rounded transition-colors"
        >
          {isSubmitting ? 'Creating Job...' : 'Create Job'}
        </button>
      </form>

      {!isConfigured && (
        <p className="text-xs text-yellow-400 mt-4 text-center">
          ⚠️ Configure executor connection and upload a session first
        </p>
      )}
    </div>
  );
}

export default JobCreator;
