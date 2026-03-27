import express from 'express';
import Database from 'better-sqlite3';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { config } from './config.js';
import { ExecutorManager } from './executor.js';

const app = express();
const port = config.port;

// Initialize Database
const db = new Database('slopscan.db');
db.exec(`
  CREATE TABLE IF NOT EXISTS scans (
    id TEXT PRIMARY KEY,
    repo_url TEXT NOT NULL,
    status TEXT NOT NULL,
    local_path TEXT,
    result_json TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
  )
`);

app.use(express.json());

// Serve static files from the React app dist folder
const __dirname = path.dirname(new URL(import.meta.url).pathname);
app.use(express.static(path.join(__dirname, '../dist')));

app.get('/api/config', (req, res) => {
  res.json(config);
});

app.get('/api/health', async (req, res) => {
  // We check if any executor is active. 
  // Since ExecutorManager instances are currently per-scan, 
  // we'll return ok if the server itself is up for now, 
  // or add a more robust check if a global manager is introduced.
  res.json({ status: 'ok' });
});

app.post('/api/scans', async (req, res) => {
  const { repoUrl } = req.body;

  if (!repoUrl) {
    return res.status(400).json({ error: 'repoUrl is required' });
  }

  const scanId = randomUUID();
  const tmpDir = path.join(os.tmpdir(), 'slopscan', scanId);

  // Initial DB entry
  const insert = db.prepare('INSERT INTO scans (id, repo_url, status, local_path) VALUES (?, ?, ?, ?)');
  insert.run(scanId, repoUrl, 'PENDING', tmpDir);

  res.status(202).json({ id: scanId, status: 'PENDING' });

  // Background processing: Clone repo
  // Note: In a production app, this should be handled by a worker queue.
  (async () => {
    try {
      if (!fs.existsSync(tmpDir)) {
        fs.mkdirSync(tmpDir, { recursive: true });
      }

      console.log(`Cloning repository: ${repoUrl}`);
      console.log(`Target directory: ${tmpDir}`);

      try {
        await new Promise((resolve, reject) => {
          const cloneProcess = spawn('git', ['clone', '--progress', repoUrl, tmpDir]);
          cloneProcess.stdout.pipe(process.stdout);
          cloneProcess.stderr.pipe(process.stderr);

          cloneProcess.on('close', (code) => {
            if (code === 0) resolve();
            else reject(new Error(`Git clone failed with code ${code}`));
          });
          cloneProcess.on('error', reject);
        });
      } catch (cloneErr) {
        console.error(`Git clone failed for ${repoUrl} at ${tmpDir}:`, cloneErr);
        db.prepare('UPDATE scans SET status = ?, result_json = ? WHERE id = ?').run(
          'FAILED',
          JSON.stringify({
            error: 'Failed to clone repository. Please ensure the URL is correct and the repository is public.',
          }),
          scanId
        );
        return;
      }

      db.prepare('UPDATE scans SET status = ? WHERE id = ?').run('CLONED', scanId);
      console.log(`Successfully cloned ${repoUrl} to ${tmpDir}`);
      
      const executor = new ExecutorManager(tmpDir);
      try {
        await executor.start();
        const jobId = await executor.submitJob('Perform a SLOP_SCAN for code quality issues', { mode: 'SLOP_SCAN' });
        
        const findings = [];
        for await (const event of executor.pollEvents(jobId)) {
          if (event.type === 'SLOP_FINDING') {
            findings.push(event.data);
          }
        }

        // Calculate financial metrics based on findings and config
        // C_rem: Remediation Cost = (Sum of complexity / E_ignore) * C_day * M_multiplier
        // I_weekly: Weekly Innovation Leak = N_team * C_day * 5 * I_drift
        // R_bank: Bankrupt Risk = C_rem / (N_team * C_day * 250)

        const totalComplexity = findings.reduce((acc, f) => acc + (f.complexity || 0), 0);
        const cRem = (totalComplexity / config.SLOP_E_IGNORE) * config.SLOP_C_DAY * config.SLOP_M_MULTIPLIER;
        const iWeekly = config.SLOP_N_TEAM * config.SLOP_C_DAY * 5 * config.SLOP_I_DRIFT;
        const rBank = cRem / (config.SLOP_N_TEAM * config.SLOP_C_DAY * 250);

        db.prepare('UPDATE scans SET status = ?, result_json = ? WHERE id = ?')
          .run(
            'COMPLETED',
            JSON.stringify({
              findings,
              metrics: {
                cRem,
                iWeekly,
                rBank,
                totalComplexity,
              },
            }),
            scanId
          );
      } finally {
        executor.stop();
        // Cleanup temp dir
        if (fs.existsSync(tmpDir)) {
          fs.rmSync(tmpDir, { recursive: true, force: true });
        }
      }
      
    } catch (err) {
      console.error(`Failed to process scan ${scanId}:`, err);
      db.prepare('UPDATE scans SET status = ?, result_json = ? WHERE id = ?')
        .run('FAILED', JSON.stringify({ error: err.message }), scanId);
    }
  })();
});

app.get('/api/scans/:id', (req, res) => {
  const scan = db.prepare('SELECT * FROM scans WHERE id = ?').get(req.params.id);
  if (!scan) {
    return res.status(404).json({ error: 'Scan not found' });
  }
  res.json(scan);
});

// Handle React routing, return all requests to React app
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, '../dist/index.html'));
});

app.listen(port, () => {
  console.log(`SlopScan server listening at http://localhost:${port}`);
});
