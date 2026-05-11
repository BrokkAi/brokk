<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import type { UnlistenFn } from "@tauri-apps/api/event";
  import { api } from "../lib/api";
  import { agentsStore } from "../lib/stores/agents.svelte";
  import { sessionStore } from "../lib/stores/session.svelte";
  import {
    onAcpUpdate,
    onPermissionRequest,
    onSessionStatus,
    onStderr,
  } from "../lib/events";

  let selectedAgentId = $state("");
  let prompt = $state("");
  let sending = $state(false);
  let actionError: string | null = $state(null);

  let unlisteners: UnlistenFn[] = [];

  // Bind event listeners + load agents + preselect the configured default.
  onMount(async () => {
    unlisteners.push(
      await onAcpUpdate((u) => sessionStore.pushUpdate(u)),
      await onSessionStatus((s) => sessionStore.setStatus(s)),
      await onPermissionRequest((p) => sessionStore.setPermission(p)),
      await onStderr((s) => sessionStore.pushStderr(s)),
    );
    await agentsStore.refresh();
    try {
      const cfg = await api.getConfig();
      if (cfg.default_agent_id) {
        selectedAgentId = cfg.default_agent_id;
      } else if (agentsStore.list.length > 0) {
        selectedAgentId = agentsStore.list[0]!.id;
      }
    } catch {
      // Settings not configured yet — leave selection blank.
    }
  });

  onDestroy(() => {
    for (const un of unlisteners) un();
  });

  async function start() {
    actionError = null;
    if (!selectedAgentId) {
      actionError = "Pick an agent first.";
      return;
    }
    try {
      await api.startSession(selectedAgentId);
    } catch (e) {
      actionError = `${e}`;
    }
  }

  async function stop() {
    actionError = null;
    try {
      await api.stopSession();
    } catch (e) {
      actionError = `${e}`;
    }
  }

  async function cancel() {
    actionError = null;
    try {
      await api.cancelSession();
    } catch (e) {
      actionError = `${e}`;
    }
  }

  async function send() {
    if (prompt.trim() === "") return;
    sending = true;
    actionError = null;
    try {
      await api.sendPrompt(prompt);
      prompt = "";
    } catch (e) {
      actionError = `${e}`;
    } finally {
      sending = false;
    }
  }

  async function respondPermission(accept: boolean) {
    const pending = sessionStore.pendingPermission;
    if (!pending) return;
    try {
      // For v1 we forward "accept" by picking the first option id when
      // available; cancel sends `accept = false`.
      const opts = pending.options as Array<{ option_id?: string }> | undefined;
      const firstOption =
        Array.isArray(opts) && opts.length > 0 ? opts[0]?.option_id : undefined;
      await api.respondPermission(pending.request_id, accept, firstOption);
    } catch (e) {
      actionError = `${e}`;
    } finally {
      sessionStore.setPermission(null);
    }
  }

  let statusLabel = $derived.by(() => {
    const s = sessionStore.status;
    if (!s) return "No session";
    switch (s.kind) {
      case "starting":
        return `Starting ${s.agent_id}…`;
      case "ready":
        return `Ready (${s.agent_id} / ${s.session_id})`;
      case "auth_required":
        return `Auth required (${s.auth_kind})`;
      case "stopped":
        return `Stopped: ${s.reason}`;
      case "failed":
        return `Failed: ${s.error}`;
    }
  });

  let isRunning = $derived(
    sessionStore.status?.kind === "ready" ||
      sessionStore.status?.kind === "starting",
  );
</script>

<section class="session">
  <header>
    <h2>Session</h2>
    <span class="status">{statusLabel}</span>
  </header>

  <div class="controls">
    <label>
      <span>Agent</span>
      <select bind:value={selectedAgentId} disabled={isRunning}>
        <option value="">(pick an agent)</option>
        {#each agentsStore.list as agent (agent.id)}
          <option value={agent.id}>{agent.name} ({agent.id})</option>
        {/each}
      </select>
    </label>
    {#if !isRunning}
      <button type="button" onclick={start}>Start</button>
    {:else}
      <button type="button" onclick={cancel}>Cancel turn</button>
      <button type="button" onclick={stop}>Stop session</button>
    {/if}
  </div>

  {#if actionError}<p class="err">{actionError}</p>{/if}

  <div class="prompt-row">
    <textarea
      bind:value={prompt}
      placeholder="Ask the agent…"
      rows="3"
      disabled={!isRunning || sending}
    ></textarea>
    <button
      type="button"
      onclick={send}
      disabled={!isRunning || sending || prompt.trim() === ""}
    >
      {sending ? "Sending…" : "Send"}
    </button>
  </div>

  <h3>Session updates</h3>
  {#if sessionStore.updates.length === 0}
    <p class="muted">Streaming updates from the agent appear here.</p>
  {:else}
    <ol class="updates">
      {#each sessionStore.updates as u, i (i)}
        <li><pre>{JSON.stringify(u.update, null, 2)}</pre></li>
      {/each}
    </ol>
  {/if}

  {#if sessionStore.stderrTail.length > 0}
    <details>
      <summary>Agent stderr (last {sessionStore.stderrTail.length} lines)</summary>
      <pre class="stderr">{sessionStore.stderrTail.join("\n")}</pre>
    </details>
  {/if}

  {#if sessionStore.pendingPermission}
    <div class="modal-backdrop">
      <div class="modal">
        <h3>Permission requested</h3>
        <pre>{JSON.stringify(sessionStore.pendingPermission.tool_call, null, 2)}</pre>
        <p class="muted">
          Allow this tool call? "Allow" forwards the first option in the
          agent's permission menu; "Deny" cancels the request.
        </p>
        <div class="modal-actions">
          <button type="button" onclick={() => respondPermission(false)}>Deny</button>
          <button type="button" onclick={() => respondPermission(true)}>Allow</button>
        </div>
      </div>
    </div>
  {/if}
</section>

<style>
  .session {
    max-width: 980px;
    display: flex;
    flex-direction: column;
    gap: 1rem;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  h2 {
    margin: 0;
  }
  .status {
    color: var(--muted);
    font-size: 0.9rem;
  }
  .controls {
    display: flex;
    align-items: end;
    gap: 0.7rem;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    font-size: 0.9rem;
    flex: 1;
  }
  select,
  textarea {
    padding: 0.4rem 0.6rem;
    background: #1c2027;
    border: 1px solid #2b313a;
    color: inherit;
    border-radius: 4px;
    font: inherit;
  }
  textarea {
    resize: vertical;
  }
  button {
    background: var(--accent);
    color: white;
    border: none;
    padding: 0.5rem 0.9rem;
    border-radius: 4px;
    cursor: pointer;
  }
  button[disabled] {
    opacity: 0.6;
    cursor: not-allowed;
  }
  .prompt-row {
    display: flex;
    gap: 0.5rem;
    align-items: stretch;
  }
  .prompt-row textarea {
    flex: 1;
  }
  .updates {
    list-style: none;
    padding: 0;
    margin: 0;
    max-height: 50vh;
    overflow-y: auto;
    border: 1px solid #2b313a;
    border-radius: 4px;
  }
  .updates li {
    border-bottom: 1px solid #2b313a;
  }
  .updates li:last-child {
    border-bottom: none;
  }
  pre {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.82rem;
    margin: 0;
    padding: 0.5rem 0.7rem;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .stderr {
    background: #14181f;
    padding: 0.5rem 0.7rem;
    border-radius: 4px;
    color: var(--muted);
  }
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 10;
  }
  .modal {
    background: #14181f;
    border: 1px solid #2b313a;
    border-radius: 6px;
    padding: 1.2rem;
    max-width: 560px;
    width: 92%;
  }
  .modal h3 {
    margin-top: 0;
  }
  .modal pre {
    background: #0e1116;
    border-radius: 4px;
    padding: 0.5rem;
    max-height: 240px;
    overflow: auto;
  }
  .modal-actions {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
    margin-top: 0.8rem;
  }
  .err {
    color: #f85149;
  }
  .muted {
    color: var(--muted);
  }
</style>
