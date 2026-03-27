export interface ScanStatus {
  id: string;
  repo_url: string;
  status: 'PENDING' | 'CLONED' | 'COMPLETED' | 'FAILED';
  local_path?: string;
  result_json?: string;
  created_at: string;
}

export interface ScanResult {
  findings: Array<{
    location: string;
    finding: string;
    impact: number;
  }>;
}

class ApiClient {
  private baseUrl = '/api';

  async startScan(repoUrl: string): Promise<{ id: string }> {
    const response = await fetch(`${this.baseUrl}/scans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ repoUrl }),
    });
    if (!response.ok) throw new Error('Failed to start scan');
    return response.json();
  }

  async getScan(id: string): Promise<ScanStatus> {
    const response = await fetch(`${this.baseUrl}/scans/${id}`);
    if (!response.ok) throw new Error('Failed to fetch scan');
    return response.json();
  }

  async checkHealth(): Promise<boolean> {
    const response = await fetch(`${this.baseUrl}/health`);
    if (!response.ok) throw new Error('Health check failed');
    return true;
  }
}

export const apiClient = new ApiClient();
