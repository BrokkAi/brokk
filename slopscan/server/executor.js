import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import path from 'node:path';

export class ExecutorManager {
  constructor(workspaceDir) {
    this.workspaceDir = workspaceDir;
    this.authToken = randomUUID();
    this.process = null;
    this.baseUrl = null;
  }

  async start() {
    // Determine JAR path - looking for the prelaunch jar from package.json
    // In a real setup, this might be an environment variable or a relative path to the built app
    const jarPath = path.resolve('../../app/build/libs/jdeploy-prelaunch.jar');
    
    const args = [
      '-cp', jarPath,
      'ai.brokk.executor.HeadlessExecutorMain',
      '--exec-id', randomUUID(),
      '--listen-addr', '127.0.0.1:0',
      '--auth-token', this.authToken,
      '--workspace-dir', this.workspaceDir,
      '-Dbrokk.tui=true'
    ];

    return new Promise((resolve, reject) => {
      console.log(`Starting executor: java ${args.join(' ')}`);
      this.process = spawn('java', args, { cwd: this.workspaceDir });

      let output = '';
      const onData = (data) => {
        const line = data.toString();
        output += line;
        const match = line.match(/Executor listening on http:\/\/127.0.0.1:(\d+)/);
        if (match) {
          const port = match[1];
          this.baseUrl = `http://127.0.0.1:${port}`;
          this.process.stdout.off('data', onData);
          resolve(this.baseUrl);
        }
      };

      this.process.stdout.on('data', onData);
      this.process.stderr.on('data', (data) => console.error(`Executor Stderr: ${data}`));

      this.process.on('error', (err) => {
        reject(new Error(`Failed to start executor: ${err.message}`));
      });

      this.process.on('exit', (code) => {
        if (!this.baseUrl) {
          reject(new Error(`Executor exited with code ${code} before starting. Output: ${output}`));
        }
      });

      // Timeout after 30s
      setTimeout(() => {
        if (!this.baseUrl) {
          this.stop();
          reject(new Error('Timeout waiting for executor to start'));
        }
      }, 30000);
    });
  }

  async submitJob(taskInput, tags = {}) {
    const response = await fetch(`${this.baseUrl}/v1/jobs`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.authToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        taskInput,
        plannerModel: 'gpt-4o',
        tags: { ...tags, mode: 'SEARCH' },
        autoCompress: true
      })
    });

    if (!response.ok) {
      throw new Error(`Failed to submit job: ${response.statusText}`);
    }
    const data = await response.json();
    return data.jobId;
  }

  async *pollEvents(jobId) {
    let afterSeq = -1;
    const terminalStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

    while (true) {
      const resp = await fetch(`${this.baseUrl}/v1/jobs/${jobId}/events?after=${afterSeq}&limit=100`, {
        headers: { 'Authorization': `Bearer ${this.authToken}` }
      });
      const { events, nextAfter } = await resp.json();

      for (const event of events) {
        yield event;
      }
      afterSeq = nextAfter;

      const statusResp = await fetch(`${this.baseUrl}/v1/jobs/${jobId}`, {
        headers: { 'Authorization': `Bearer ${this.authToken}` }
      });
      const status = await statusResp.json();
      
      if (terminalStates.has(status.state)) {
        break;
      }

      await new Promise(r => setTimeout(r, events.length > 0 ? 50 : 500));
    }
  }

  stop() {
    if (this.process) {
      this.process.kill();
      this.process = null;
    }
  }
}
