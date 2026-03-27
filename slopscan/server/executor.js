import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { config } from './config.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export class ExecutorManager {
  constructor(workspaceDir) {
    this.workspaceDir = workspaceDir;
    this.authToken = randomUUID();
    this.process = null;
    this.baseUrl = null;
  }

  async start() {
    // Determine JAR path
    const libsDir = path.join(__dirname, '../../app/build/libs');
    let jarPath = path.join(libsDir, 'jdeploy-prelaunch.jar');

    if (!fs.existsSync(jarPath)) {
      // Fallback: look for latest brokk-*.jar
      if (fs.existsSync(libsDir)) {
        const files = fs.readdirSync(libsDir)
          .filter(f => f.startsWith('brokk-') && f.endsWith('.jar'))
          .map(f => ({ name: f, path: path.join(libsDir, f) }))
          .map(f => ({ ...f, mtime: fs.statSync(f.path).mtimeMs }))
          .sort((a, b) => b.mtime - a.mtime);

        if (files.length > 0) {
          jarPath = files[0].path;
        }
      }
    }

    if (!fs.existsSync(jarPath)) {
      throw new Error(
        `Executor JAR not found. Checked for jdeploy-prelaunch.jar and brokk-*.jar in ${libsDir}. ` +
        `Please build the project with './gradlew :app:shadowJar'.`
      );
    }

    const command = 'java';
    const args = [
      '-cp',
      jarPath,
      'ai.brokk.executor.HeadlessExecutorMain',
      '--exec-id',
      randomUUID(),
      '--listen-addr',
      '127.0.0.1:0',
      '--auth-token',
      this.authToken,
      '--workspace-dir',
      this.workspaceDir,
      '-Dbrokk.tui=true',
    ];

    if (config.BROKK_API_KEY) {
      args.push('--brokk-api-key', config.BROKK_API_KEY);
    }

    return new Promise((resolve, reject) => {
      console.log(`Starting executor: ${command} ${args.join(' ')}`);
      this.process = spawn(command, args, { cwd: this.workspaceDir });

      let output = '';
      const onData = (data) => {
        process.stdout.write(data);
        const line = data.toString();
        output += line;
        const match = line.match(/Executor listening on http:\/\/127.0.0.1:(\d+)/);
        if (match) {
          const port = match[1];
          this.baseUrl = `http://127.0.0.1:${port}`;
          // Keep the listener active so logs continue to flow to process.stdout
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
    const maxRetries = 15;
    const retryDelay = 2000;

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      const response = await fetch(`${this.baseUrl}/v1/jobs`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${this.authToken}`,
          'Content-Type': 'application/json',
          'Idempotency-Key': randomUUID(),
        },
        body: JSON.stringify({
          taskInput,
          plannerModel: config.BROKK_PLANNER_MODEL,
          tags: { mode: 'SEARCH', ...tags },
          autoCompress: true,
        }),
      });

      if (response.ok) {
        const data = await response.json();
        return data.jobId;
      }

      const text = await response.text();
      let isNotReady = false;
      try {
        const errorData = JSON.parse(text);
        if (response.status === 503 && errorData.code === 'NOT_READY') {
          isNotReady = true;
        }
      } catch (e) {
        // Not JSON or doesn't match expected error format
      }

      if (isNotReady && attempt < maxRetries) {
        console.log(`Executor not ready (attempt ${attempt}/${maxRetries}). Retrying in ${retryDelay}ms...`);
        await new Promise((resolve) => setTimeout(resolve, retryDelay));
        continue;
      }

      throw new Error(`Failed to submit job: ${response.statusText} - ${text}`);
    }
  }

  async *pollEvents(jobId) {
    let afterSeq = -1;
    const terminalStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

    const fetchWithRetry = async (url, options, maxRetries = 5, retryDelay = 2000) => {
      for (let attempt = 1; attempt <= maxRetries; attempt++) {
        try {
          const resp = await fetch(url, options);
          if (resp.ok) return resp;
          throw new Error(`HTTP ${resp.status}: ${resp.statusText}`);
        } catch (err) {
          if (attempt === maxRetries) throw err;
          console.warn(`[ExecutorManager] Fetch failed (attempt ${attempt}/${maxRetries}): ${err.message}. Retrying in ${retryDelay}ms...`);
          await new Promise(r => setTimeout(r, retryDelay));
        }
      }
    };

    while (true) {
      const resp = await fetchWithRetry(`${this.baseUrl}/v1/jobs/${jobId}/events?after=${afterSeq}&limit=100`, {
        headers: { 'Authorization': `Bearer ${this.authToken}` }
      });
      const { events, nextAfter } = await resp.json();

      for (const event of events) {
        yield event;
      }
      afterSeq = nextAfter;

      const statusResp = await fetchWithRetry(`${this.baseUrl}/v1/jobs/${jobId}`, {
        headers: { 'Authorization': `Bearer ${this.authToken}` }
      });
      const status = await statusResp.json();
      
      if (terminalStates.has(status.state)) {
        break;
      }

      await new Promise(r => setTimeout(r, events.length > 0 ? 50 : 500));
    }
  }

  async checkHealth() {
    if (!this.process || !this.baseUrl) {
      return { status: 'error', reason: 'Executor not started' };
    }

    try {
      const response = await fetch(`${this.baseUrl}/health/live`, {
        signal: AbortSignal.timeout(2000),
      });
      if (response.ok) {
        return { status: 'ok' };
      }
      return { status: 'error', reason: `Executor returned status ${response.status}` };
    } catch (err) {
      return { status: 'error', reason: `Failed to reach executor: ${err.message}` };
    }
  }

  stop() {
    if (this.process) {
      this.process.kill();
      this.process = null;
      this.baseUrl = null;
    }
  }
}
