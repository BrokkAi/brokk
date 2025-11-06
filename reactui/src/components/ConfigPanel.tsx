import { useState, useEffect } from 'react';

interface ConfigPanelProps {
  onConfigChange?: (config: ExecutorConfig) => void;
}

export interface ExecutorConfig {
  executorUrl: string;
  bearerToken: string;
}

interface ConnectionStatus {
  status: 'connecting' | 'connected' | 'disconnected' | 'error';
  message: string;
}

const STORAGE_KEY_URL = 'executor_url';
const STORAGE_KEY_TOKEN = 'executor_token';

export function ConfigPanel({ onConfigChange }: ConfigPanelProps) {
  const [executorUrl, setExecutorUrl] = useState<string>('');
  const [bearerToken, setBearerToken] = useState<string>('');
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>({
    status: 'disconnected',
    message: 'Not configured',
  });
  const [isCheckingConnection, setIsCheckingConnection] = useState(false);

  // Load configuration from localStorage on mount
  useEffect(() => {
    const savedUrl = localStorage.getItem(STORAGE_KEY_URL) || '';
    const savedToken = localStorage.getItem(STORAGE_KEY_TOKEN) || '';
    setExecutorUrl(savedUrl);
    setBearerToken(savedToken);
  }, []);

  // Check connection when URL or token changes
  useEffect(() => {
    if (!executorUrl || !bearerToken) {
      setConnectionStatus({
        status: 'disconnected',
        message: 'Not configured',
      });
      return;
    }

    checkConnection();
  }, [executorUrl, bearerToken]);

  const checkConnection = async () => {
    if (!executorUrl || !bearerToken) {
      return;
    }

    setIsCheckingConnection(true);
    setConnectionStatus({
      status: 'connecting',
      message: 'Checking connection...',
    });

    try {
      const response = await fetch(`${executorUrl}/health`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${bearerToken}`,
          'Content-Type': 'application/json',
        },
      });

      if (response.ok) {
        setConnectionStatus({
          status: 'connected',
          message: 'Connected to executor',
        });
        onConfigChange?.({ executorUrl, bearerToken });
      } else if (response.status === 401) {
        setConnectionStatus({
          status: 'error',
          message: 'Authentication failed: Invalid bearer token',
        });
      } else {
        setConnectionStatus({
          status: 'error',
          message: `Server error: ${response.status}`,
        });
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      setConnectionStatus({
        status: 'error',
        message: `Connection failed: ${errorMessage}`,
      });
    } finally {
      setIsCheckingConnection(false);
    }
  };

  const handleUrlChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newUrl = e.target.value;
    setExecutorUrl(newUrl);
    localStorage.setItem(STORAGE_KEY_URL, newUrl);
  };

  const handleTokenChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newToken = e.target.value;
    setBearerToken(newToken);
    localStorage.setItem(STORAGE_KEY_TOKEN, newToken);
  };

  const getStatusColor = () => {
    switch (connectionStatus.status) {
      case 'connected':
        return 'text-green-400';
      case 'connecting':
        return 'text-yellow-400';
      case 'error':
        return 'text-red-400';
      case 'disconnected':
      default:
        return 'text-gray-400';
    }
  };

  const getStatusBgColor = () => {
    switch (connectionStatus.status) {
      case 'connected':
        return 'bg-green-900/20 border-green-700';
      case 'connecting':
        return 'bg-yellow-900/20 border-yellow-700';
      case 'error':
        return 'bg-red-900/20 border-red-700';
      case 'disconnected':
      default:
        return 'bg-gray-900/20 border-gray-700';
    }
  };

  const getStatusIndicatorColor = () => {
    switch (connectionStatus.status) {
      case 'connected':
        return 'bg-green-500';
      case 'connecting':
        return 'bg-yellow-500 animate-pulse';
      case 'error':
        return 'bg-red-500';
      case 'disconnected':
      default:
        return 'bg-gray-500';
    }
  };

  return (
    <div className="bg-slate-700 rounded-lg shadow-lg p-6 text-white">
      <h2 className="text-2xl font-bold mb-6">Executor Configuration</h2>

      {/* Connection Status */}
      <div className={`mb-6 p-4 rounded-lg border ${getStatusBgColor()}`}>
        <div className="flex items-center gap-3">
          <div className={`w-3 h-3 rounded-full ${getStatusIndicatorColor()}`} />
          <div>
            <p className={`font-semibold ${getStatusColor()}`}>
              {connectionStatus.message}
            </p>
          </div>
        </div>
      </div>

      {/* Configuration Form */}
      <div className="space-y-4">
        {/* Executor URL Input */}
        <div>
          <label htmlFor="executor-url" className="block text-sm font-medium mb-2">
            Executor URL
          </label>
          <input
            id="executor-url"
            type="text"
            placeholder="http://localhost:8080"
            value={executorUrl}
            onChange={handleUrlChange}
            className="w-full px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white placeholder-gray-400 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400"
          />
          <p className="text-xs text-gray-400 mt-1">
            Base URL of the Headless Executor API
          </p>
        </div>

        {/* Bearer Token Input */}
        <div>
          <label htmlFor="bearer-token" className="block text-sm font-medium mb-2">
            Bearer Token
          </label>
          <input
            id="bearer-token"
            type="password"
            placeholder="Enter your bearer token"
            value={bearerToken}
            onChange={handleTokenChange}
            className="w-full px-4 py-2 bg-slate-600 border border-slate-500 rounded text-white placeholder-gray-400 focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400"
          />
          <p className="text-xs text-gray-400 mt-1">
            Authentication token for executor API requests
          </p>
        </div>

        {/* Manual Test Button */}
        <button
          onClick={checkConnection}
          disabled={isCheckingConnection || !executorUrl || !bearerToken}
          className="w-full mt-6 bg-blue-500 hover:bg-blue-600 disabled:bg-gray-600 disabled:cursor-not-allowed text-white font-bold py-2 px-4 rounded transition-colors"
        >
          {isCheckingConnection ? 'Testing Connection...' : 'Test Connection'}
        </button>
      </div>
    </div>
  );
}

export default ConfigPanel;
