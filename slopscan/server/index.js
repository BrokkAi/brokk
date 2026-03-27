import express from 'express';
import Database from 'better-sqlite3';
import { simpleGit } from 'simple-git';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const app = express();
const port = process.env.PORT || 3001;

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

      const git = simpleGit();
      await git.clone(repoUrl, tmpDir);

      db.prepare('UPDATE scans SET status = ? WHERE id = ?').run('CLONED', scanId);
      console.log(`Successfully cloned ${repoUrl} to ${tmpDir}`);
      
      // Future: Trigger analysis here
      
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

app.listen(port, () => {
  console.log(`SlopScan server listening at http://localhost:${port}`);
});
