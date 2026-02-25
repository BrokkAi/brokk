import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import PlayCircleOutlineRoundedIcon from '@mui/icons-material/PlayCircleOutlineRounded';
import PauseCircleOutlineRoundedIcon from '@mui/icons-material/PauseCircleOutlineRounded';
import type { AgentManagerState, ConversationThread } from '../shared/types';
import { getAgentManagerApi } from './api';

const emptyState: AgentManagerState = { activeThreadId: null, threads: [] };
const agentManagerApi = getAgentManagerApi();

export const App = (): JSX.Element => {
  const [state, setState] = useState<AgentManagerState>(emptyState);
  const [threadSeedPrompt, setThreadSeedPrompt] = useState('');
  const [prompt, setPrompt] = useState('');

  useEffect(() => {
    void agentManagerApi.getState().then(setState);
    const unsubscribe = agentManagerApi.onState(setState);
    return () => unsubscribe();
  }, []);

  const activeThread = useMemo(
    () => state.threads.find(thread => thread.id === state.activeThreadId) ?? null,
    [state]
  );

  const createThread = async (): Promise<void> => {
    if (!threadSeedPrompt.trim()) {
      return;
    }
    await agentManagerApi.createThread(threadSeedPrompt.trim());
    setThreadSeedPrompt('');
    setPrompt('');
  };

  const submitPrompt = async (): Promise<void> => {
    if (!activeThread || !prompt.trim()) {
      return;
    }
    await agentManagerApi.submitPrompt({ threadId: activeThread.id, prompt: prompt.trim() });
    setPrompt('');
  };

  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '320px 1fr', minHeight: '100vh' }}>
      <Paper square sx={{ p: 2, borderRight: '1px solid', borderColor: 'divider' }}>
        <Stack spacing={2}>
          <Typography variant="h5">Agent Threads</Typography>
          <Typography variant="body2" color="text.secondary">
            New threads auto-create a session and worktree based on a prompt summary.
          </Typography>
          <TextField
            label="Start a new conversation"
            multiline
            minRows={2}
            value={threadSeedPrompt}
            onChange={event => setThreadSeedPrompt(event.target.value)}
          />
          <Button variant="contained" onClick={() => void createThread()}>
            Create Thread
          </Button>
          <Divider />
          <List dense disablePadding>
            {state.threads.map(thread => (
              <ListItemButton
                key={thread.id}
                selected={thread.id === state.activeThreadId}
                onClick={() => {
                  void agentManagerApi.switchThread(thread.id);
                }}
              >
                <ListItemText
                  primary={thread.title}
                  secondary={`${thread.sessionName} / ${thread.worktreeName}`}
                  primaryTypographyProps={{ noWrap: true }}
                  secondaryTypographyProps={{ noWrap: true }}
                />
              </ListItemButton>
            ))}
          </List>
        </Stack>
      </Paper>
      <Box sx={{ p: 3 }}>
        {!activeThread && <Alert severity="info">Create a thread to launch and manage executors.</Alert>}
        {activeThread && <ThreadPanel thread={activeThread} prompt={prompt} setPrompt={setPrompt} submitPrompt={submitPrompt} />}
      </Box>
    </Box>
  );
};

const ThreadPanel = ({
  thread,
  prompt,
  setPrompt,
  submitPrompt
}: {
  thread: ConversationThread;
  prompt: string;
  setPrompt: (prompt: string) => void;
  submitPrompt: () => Promise<void>;
}): JSX.Element => (
  <Stack spacing={2}>
    <Stack direction="row" spacing={1} alignItems="center">
      <Typography variant="h4">{thread.title}</Typography>
      <Chip
        icon={thread.executorStatus === 'running' ? <PlayCircleOutlineRoundedIcon /> : <PauseCircleOutlineRoundedIcon />}
        label={thread.executorStatus === 'running' ? 'Executor running' : 'Executor stopped'}
        color={thread.executorStatus === 'running' ? 'success' : 'default'}
      />
    </Stack>
    <Typography variant="body2" color="text.secondary">
      Session: {thread.sessionName}
    </Typography>
    <Typography variant="body2" color="text.secondary">
      Worktree: {thread.worktreeName}
    </Typography>
    <Paper variant="outlined" sx={{ p: 2, maxHeight: '55vh', overflow: 'auto' }}>
      <Stack spacing={1}>
        {thread.outputs.map((entry, index) => (
          <Typography key={`${entry.timestamp}-${index}`} variant="body2">
            [{new Date(entry.timestamp).toLocaleTimeString()}] {entry.message}
          </Typography>
        ))}
      </Stack>
    </Paper>
    <TextField
      label="Send prompt to this session"
      multiline
      minRows={3}
      value={prompt}
      onChange={event => setPrompt(event.target.value)}
    />
    <Button variant="contained" onClick={() => void submitPrompt()}>
      Dispatch to headless executor
    </Button>
  </Stack>
);
