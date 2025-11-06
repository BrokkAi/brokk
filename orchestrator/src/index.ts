import express, { Request, Response } from 'express';

const app = express();
const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 9090;

app.use(express.json());

app.get('/health', (_req: Request, res: Response) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.listen(PORT, () => {
  console.log(`Orchestrator service listening on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/health`);
});
