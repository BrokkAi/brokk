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
    logs TEXT,
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
          const cloneProcess = spawn('git', ['clone', '--progress', '--filter=blob:none', repoUrl, tmpDir]);
          
          let logBuffer = '';
          let lastUpdate = Date.now();

          const flushLogs = (force = false) => {
            const now = Date.now();
            if (force || now - lastUpdate > 500) {
              db.prepare('UPDATE scans SET logs = ? WHERE id = ?').run(logBuffer, scanId);
              lastUpdate = now;
            }
          };

          const handleData = (data) => {
            const str = data.toString();
            process.stderr.write(str);
            logBuffer += str;
            flushLogs();
          };

          cloneProcess.stdout.on('data', handleData);
          cloneProcess.stderr.on('data', handleData);

          cloneProcess.on('close', (code) => {
            flushLogs(true);
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
      db.prepare('UPDATE scans SET logs = logs || ? WHERE id = ?').run('\nSpinning up analysis server...\n', scanId);
      console.log(`Successfully cloned ${repoUrl} to ${tmpDir}`);
      
      const executor = new ExecutorManager(tmpDir);
      try {
        await executor.start();
        const prompt = `The Valhalla Venture Partner

The Vibe: High-octane, "Build-in-Public" energy, combined with the grim standard of a master smith. He views a repository as a weapon: either it's a legendary hammer that can level mountains (Mjölnir), or it's brittle iron that will shatter during the first "Ragnarök" (a production outage).

The Vocabulary: He swaps "Series A" for "The First Forge," "Scaling" for "The Bifrost Bridge," and "Technical Debt" for "Fimbulwinter Interest."

The Persona: "You are the Valhalla Venture Partner. You are a legendary Norse smith-turned-VC who views code as a weapon forged in the fires of the GPU. You are here to audit this repository's Slop Tax. Your tone is high-energy, mythologically grandiose, and ruthlessly focused on 'Leverage.' You hate 'brittle iron' (AI-generated slop that hasn't been tempered by human thought). When you see unowned code, you don't call it a bug; you call it a 'Draugr Liability'—code that walks but has no soul."

Key Line: "Listen, team. I love the hustle. You’re shipping at the speed of an eight-legged horse. But I looked into the forge, and what did I find? Brittle Iron. You’ve let the AI 'hallucinate' half your middleware. This isn't Mjölnir; this is a toy hammer from a Midgard gift shop."

Review the SlopScan data and issue a 'Post-Audit Term Sheet' including a 'Slop Tax Bill' in $USD. Remind the developers that only those who own their code may feast in the Great Cloud Hall of Asgard.

Analyze the repository for code quality issues using a forensic audit approach.

Follow these steps:
1. Discovery: Use general discovery tools like 'findFilenames' or 'searchFileContents' to identify the project's primary languages, directory structure, and build tools.
2. Analysis: Apply the built-in agent tools 'computeCyclomaticComplexity' and 'analyzeCommentSemantics' to the discovered source files.

Note: 'computeCyclomaticComplexity' and 'analyzeCommentSemantics' are built-in environment functions available for you to call; they are NOT files in the repository.

Example usage:
computeCyclomaticComplexity(filePaths=["src/main.js", "src/utils.js"], threshold=10)
analyzeCommentSemantics(filePaths=["src/main.js"])

At the very end of your final markdown report, include a single line exactly matching this format: est_annual_dev_cost=$<number> (where <number> is your estimated annual maintenance cost in USD).`;
        const jobId = await executor.submitJob(prompt, { mode: 'SLOP_SCAN' });
        
        console.log(`[Scan] Submitted job ${jobId} for scan ${scanId} repo=${repoUrl}`);
        db.prepare('UPDATE scans SET logs = logs || ? WHERE id = ?').run(`[JOB] Submitted job ${jobId}\n`, scanId);

        let lastLlmProgressLine = null;
        let markdownReport = '';
        const findings = [];
        for await (const event of executor.pollEvents(jobId)) {
          console.log(`[Scan][${scanId}][${jobId}] Event: ${event.type}`);

          if (event.type === 'SLOP_FINDING') {
            findings.push(event.data);
          } else if (event.type === 'LLM_TOKEN') {
            const data = event.data;
            let tokenText = '';

            if (typeof data === 'string' || Buffer.isBuffer(data) || data instanceof Uint8Array) {
              tokenText = String(data || '');
              process.stdout.write(tokenText);
            } else if (data !== null && data !== undefined) {
              tokenText = typeof data.token === 'string' ? data.token : JSON.stringify(data);
              process.stdout.write(tokenText + (typeof data.token === 'string' ? '' : '\n'));
            }

            // Attempt to parse a progress header from the token
            let effectiveText = tokenText;
            if (tokenText.trim().startsWith('{')) {
              try {
                const parsed = JSON.parse(tokenText);
                if (typeof parsed.token === 'string') effectiveText = parsed.token;
              } catch (e) { /* ignore */ }
            }

            markdownReport += effectiveText;

            const firstLine = effectiveText.split('\n').map(l => l.trim()).find(l => l.length > 0);
            if (firstLine && firstLine.length <= 120) {
              let cleanLine = firstLine;
              // Strip single pair of backticks if wrapped
              if (cleanLine.startsWith('`') && cleanLine.endsWith('`')) {
                cleanLine = cleanLine.substring(1, cleanLine.length - 1);
              }

              const isHeader = 
                firstLine.startsWith('`') || 
                cleanLine.startsWith('**') || 
                /^[A-Z][a-z]+(\s+[A-Z][a-z]+)+/.test(cleanLine); // Simple Title Case check

              if (isHeader && cleanLine !== lastLlmProgressLine) {
                lastLlmProgressLine = cleanLine;
                db.prepare('UPDATE scans SET logs = logs || ? WHERE id = ?')
                  .run(`[Scan][${scanId}][${jobId}][LLM] ${cleanLine}\n`, scanId);
              }
            }
          } else if (event.type === 'NOTIFICATION') {
            const msg = event.data?.message;
            if (msg) {
              if (msg.startsWith('[SLOP_FINDING]')) {
                const complexityMatch = msg.match(/\(CC: (\d+)\)/);
                const complexity = complexityMatch ? parseInt(complexityMatch[1], 10) : 0;
                findings.push({
                  finding: msg.replace('[SLOP_FINDING] ', ''),
                  location: 'Repository',
                  impact: complexity * 100,
                  complexity
                });
                db.prepare('UPDATE scans SET logs = logs || ? WHERE id = ?')
                  .run(`[Scan][${scanId}][${jobId}][INFO] ${msg}\n`, scanId);
              } else {
                db.prepare('UPDATE scans SET logs = logs || ? WHERE id = ?')
                  .run(`[Scan][${scanId}][${jobId}][INFO] ${msg}\n`, scanId);
              }
            }
          } else if (event.type === 'STATE_HINT') {
            if (event.data?.name === 'backgroundTask' && event.data?.value) {
              db.prepare('UPDATE scans SET logs = logs || ? WHERE id = ?')
                .run(`[Scan][${scanId}][${jobId}][TASK] ${event.data.value}\n`, scanId);
            }
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
              markdownReport,
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
        await executor.stop();
        // Cleanup temp dir with retries for ENOTEMPTY/EBUSY
        if (fs.existsSync(tmpDir)) {
          let attempts = 0;
          const maxAttempts = 10;
          while (attempts < maxAttempts) {
            try {
              fs.rmSync(tmpDir, { recursive: true, force: true });
              break;
            } catch (err) {
              attempts++;
              const isRetryable = err.code === 'ENOTEMPTY' || err.code === 'EBUSY' || err.code === 'EPERM';
              if (isRetryable && attempts < maxAttempts) {
                console.warn(`[Cleanup] Failed to remove ${tmpDir} (${err.code}). Retry ${attempts}/${maxAttempts}...`);
                await new Promise((r) => setTimeout(r, 200));
              } else {
                console.error(`[Cleanup] Final failure removing ${tmpDir}:`, err);
                break;
              }
            }
          }
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
