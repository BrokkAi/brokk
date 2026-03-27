import { useState, useEffect, useCallback, useRef } from "react";
import { ExecutorClient, createExecutorClient, JobEvent } from "@/lib/executor-client";

interface UseExecutorResult {
  client: ExecutorClient;
  isConnected: boolean;
  submitScan: (repoPath: string) => Promise<string>;
  streamEvents: (jobId: string, onEvent: (event: JobEvent) => void) => () => void;
}

export function useExecutor(): UseExecutorResult {
  const clientRef = useRef<ExecutorClient | null>(null);
  const [isConnected, setIsConnected] = useState(false);

  useEffect(() => {
    clientRef.current = createExecutorClient();

    // Check connection on mount
    const checkConnection = async () => {
      if (clientRef.current) {
        const alive = await clientRef.current.healthCheck();
        setIsConnected(alive);
      }
    };

    void checkConnection();

    // Periodic health check
    const interval = setInterval(() => {
      void checkConnection();
    }, 30000);

    return () => clearInterval(interval);
  }, []);

  const submitScan = useCallback(async (repoPath: string): Promise<string> => {
    if (!clientRef.current) {
      throw new Error("Executor client not initialized");
    }

    const result = await clientRef.current.submitJob({
      taskInput: `Analyze the repository at ${repoPath} for code quality issues including cyclomatic complexity, comment quality, and ownership patterns.`,
      plannerModel: "gpt-4o",
      tags: { mode: "SEARCH" },
      autoCompress: true,
    });

    return result.jobId;
  }, []);

  const streamEvents = useCallback(
    (jobId: string, onEvent: (event: JobEvent) => void): (() => void) => {
      if (!clientRef.current) {
        throw new Error("Executor client not initialized");
      }

      let cancelled = false;
      const client = clientRef.current;

      const run = async () => {
        for await (const event of client.streamEvents(jobId)) {
          if (cancelled) break;
          onEvent(event);
        }
      };

      void run();

      return () => {
        cancelled = true;
      };
    },
    []
  );

  return {
    client: clientRef.current ?? createExecutorClient(),
    isConnected,
    submitScan,
    streamEvents,
  };
}
