<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Config } from "../lib/api";
  import { agentsStore } from "../lib/stores/agents.svelte";

  let repoPath = $state("");
  let defaultAgentId = $state("");
  let loading = $state(false);
  let saving = $state(false);
  let error: string | null = $state(null);
  let saved = $state(false);

  onMount(async () => {
    loading = true;
    try {
      const [cfg] = await Promise.all([api.getConfig(), agentsStore.refresh()]);
      repoPath = cfg.repo_path ?? "";
      defaultAgentId = cfg.default_agent_id ?? "";
    } catch (e) {
      error = `${e}`;
    } finally {
      loading = false;
    }
  });

  async function save() {
    saving = true;
    error = null;
    saved = false;
    try {
      const config: Config = {
        repo_path: repoPath.trim() === "" ? null : repoPath.trim(),
        default_agent_id:
          defaultAgentId.trim() === "" ? null : defaultAgentId.trim(),
      };
      await api.setConfig(config);
      saved = true;
    } catch (e) {
      error = `${e}`;
    } finally {
      saving = false;
    }
  }
</script>

<section class="settings">
  <h2>Settings</h2>

  {#if loading}
    <p class="muted">Loading…</p>
  {:else}
    <form onsubmit={(e) => { e.preventDefault(); save(); }}>
      <label>
        <span>Repository path</span>
        <input
          type="text"
          bind:value={repoPath}
          placeholder="/Users/you/code/your-repo"
          autocomplete="off"
        />
        <span class="hint">
          Absolute path to the repo the ACP session will run against. v1
          runs in this directory directly (no worktree).
        </span>
      </label>

      <label>
        <span>Default agent</span>
        <select bind:value={defaultAgentId}>
          <option value="">(none)</option>
          {#each agentsStore.list as agent (agent.id)}
            <option value={agent.id}>{agent.name} ({agent.id})</option>
          {/each}
        </select>
        <span class="hint">
          Pre-selected on the Session page. Add agents on the Agents tab.
        </span>
      </label>

      <div class="actions">
        <button type="submit" disabled={saving}>
          {saving ? "Saving…" : "Save"}
        </button>
        {#if saved}<span class="ok">Saved.</span>{/if}
        {#if error}<span class="err">{error}</span>{/if}
      </div>
    </form>
  {/if}
</section>

<style>
  .settings {
    max-width: 640px;
  }
  h2 {
    margin-top: 0;
  }
  form {
    display: flex;
    flex-direction: column;
    gap: 1.25rem;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    font-size: 0.95rem;
  }
  label > span:first-child {
    font-weight: 600;
  }
  input,
  select {
    padding: 0.5rem 0.6rem;
    background: #1c2027;
    border: 1px solid #2b313a;
    color: inherit;
    border-radius: 4px;
    font-size: 0.95rem;
  }
  .hint {
    color: var(--muted);
    font-size: 0.8rem;
  }
  .actions {
    display: flex;
    align-items: center;
    gap: 1rem;
  }
  button {
    background: var(--accent);
    color: white;
    border: none;
    padding: 0.5rem 1rem;
    border-radius: 4px;
    cursor: pointer;
  }
  button[disabled] {
    opacity: 0.6;
    cursor: progress;
  }
  .ok {
    color: #2ea043;
  }
  .err {
    color: #f85149;
  }
</style>
