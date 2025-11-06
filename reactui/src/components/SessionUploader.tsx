import { useState, useRef } from 'react';
import { ExecutorConfig } from './ConfigPanel';

interface SessionUploaderProps {
  config: ExecutorConfig | null;
  onSessionUpload?: (sessionId: string) => void;
}

interface UploadStatus {
  state: 'idle' | 'uploading' | 'success' | 'error';
  sessionId?: string;
  message: string;
}

export function SessionUploader({ config, onSessionUpload }: SessionUploaderProps) {
  const [uploadStatus, setUploadStatus] = useState<UploadStatus>({
    state: 'idle',
    message: 'Ready to upload',
  });
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) {
      return;
    }

    if (!file.name.endsWith('.zip')) {
      setUploadStatus({
        state: 'error',
        message: 'Please select a ZIP file',
      });
      return;
    }

    if (!config?.executorUrl || !config?.bearerToken) {
      setUploadStatus({
        state: 'error',
        message: 'Executor configuration required',
      });
      return;
    }

    await uploadSession(file, config);
  };

  const uploadSession = async (file: File, config: ExecutorConfig) => {
    setUploadStatus({
      state: 'uploading',
      message: 'Uploading session...',
    });

    try {
      const response = await fetch(`${config.executorUrl}/v1/session`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${config.bearerToken}`,
          'Content-Type': 'application/zip',
        },
        body: file,
      });

      if (!response.ok) {
        let errorMessage = `Upload failed: ${response.status}`;
        try {
          const errorData = await response.json();
          errorMessage = errorData.message || errorMessage;
        } catch {
          // If response is not JSON, use status-based message
        }
        setUploadStatus({
          state: 'error',
          message: errorMessage,
        });
        return;
      }

      const data = await response.json();
      const sessionId = data.sessionId || data.id;

      if (!sessionId) {
        setUploadStatus({
          state: 'error',
          message: 'Server did not return a session ID',
        });
        return;
      }

      setUploadStatus({
        state: 'success',
        sessionId,
        message: `Session uploaded successfully`,
      });

      onSessionUpload?.(sessionId);

      // Reset file input
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error occurred';
      setUploadStatus({
        state: 'error',
        message: `Connection error: ${errorMessage}`,
      });
    }
  };

  const getStatusColor = () => {
    switch (uploadStatus.state) {
      case 'success':
        return 'text-green-400';
      case 'uploading':
        return 'text-yellow-400';
      case 'error':
        return 'text-red-400';
      case 'idle':
      default:
        return 'text-gray-400';
    }
  };

  const getStatusBgColor = () => {
    switch (uploadStatus.state) {
      case 'success':
        return 'bg-green-900/20 border-green-700';
      case 'uploading':
        return 'bg-yellow-900/20 border-yellow-700';
      case 'error':
        return 'bg-red-900/20 border-red-700';
      case 'idle':
      default:
        return 'bg-gray-900/20 border-gray-700';
    }
  };

  const getStatusIndicatorColor = () => {
    switch (uploadStatus.state) {
      case 'success':
        return 'bg-green-500';
      case 'uploading':
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
      <h2 className="text-2xl font-bold mb-6">Upload Session</h2>

      {/* Upload Status */}
      <div className={`mb-6 p-4 rounded-lg border ${getStatusBgColor()}`}>
        <div className="flex items-center gap-3">
          <div className={`w-3 h-3 rounded-full ${getStatusIndicatorColor()}`} />
          <div>
            <p className={`font-semibold ${getStatusColor()}`}>
              {uploadStatus.message}
            </p>
            {uploadStatus.sessionId && (
              <p className="text-sm text-gray-300 mt-1 break-all">
                Session ID: <code className="bg-slate-600 px-2 py-1 rounded">{uploadStatus.sessionId}</code>
              </p>
            )}
          </div>
        </div>
      </div>

      {/* File Upload Input */}
      <div className="space-y-4">
        <div>
          <label htmlFor="session-zip" className="block text-sm font-medium mb-2">
            Session ZIP File
          </label>
          <input
            ref={fileInputRef}
            id="session-zip"
            type="file"
            accept=".zip"
            onChange={handleFileSelect}
            disabled={uploadStatus.state === 'uploading' || !isConfigured}
            className="w-full px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white cursor-pointer file:mr-4 file:py-2 file:px-4 file:rounded file:border-0 file:text-sm file:font-semibold file:bg-blue-500 file:text-white hover:file:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed"
          />
          <p className="text-xs text-gray-400 mt-1">
            {isConfigured
              ? 'Select a ZIP file containing your session data'
              : 'Configure executor connection first'}
          </p>
        </div>

        {uploadStatus.state === 'success' && (
          <button
            onClick={() => {
              setUploadStatus({ state: 'idle', message: 'Ready to upload' });
              if (fileInputRef.current) {
                fileInputRef.current.value = '';
              }
            }}
            className="w-full bg-blue-500 hover:bg-blue-600 text-white font-bold py-2 px-4 rounded transition-colors"
          >
            Upload Another Session
          </button>
        )}
      </div>
    </div>
  );
}

export default SessionUploader;
