<script lang="ts">
  import { onMount } from "svelte";
  import { api, type CustomAgentSpec, type RegistryAgent } from "../lib/api";
  import { agentsStore } from "../lib/stores/agents.svelte";

  let registry: RegistryAgent[] = $state([]);
  let registryLoading = $state(false);
  let registryError: string | null = $state(null);
  let installingId: string | null = $state(null);

  let showCustomForm = $state(false);
  let customForm = $state<CustomAgentSpec>({
    id: "",
    name: "",
    version: "",
    cmd: "",
    args: [],
    env: {},
  });
  let argsText = $state("");
  let envText = $state("");
  let customError: string | null = $state(null);

  onMount(async () => {
    await Promise.all([agentsStore.refresh(), refreshRegistry(false)]);
  });

  async function refreshRegistry(force: boolean) {
    registryLoading = true;
    registryError = null;
    try {
      registry = await api.fetchRegistry(force);
    } catch (e) {
      registryError = `${e}`;
    } finally {
      registryLoading = false;
    }
  }

  async function install(id: string) {
    installingId = id;
    try {
      await api.installAgent(id);
      await agentsStore.refresh();
    } catch (e) {
      registryError = `${e}`;
    } finally {
      installingId = null;
    }
  }

  async function uninstall(id: string) {
    try {
      await api.uninstallAgent(id);
      await agentsStore.refresh();
    } catch (e) {
      registryError = `${e}`;
    }
  }

  async function submitCustom(event: SubmitEvent) {
    event.preventDefault();
    customError = null;
    try {
      const args = argsText
        .split(/\s+/)
        .map((s) => s.trim())
        .filter((s) => s.length > 0);
      const env: Record<string, string> = {};
      for (const line of envText.split(/\n+/)) {
        const trimmed = line.trim();
        if (trimmed === "") continue;
        const eq = trimmed.indexOf("=");
        if (eq === -1) {
          customError = `Env line missing '=': ${trimmed}`;
          return;
        }
        env[trimmed.slice(0, eq)] = trimmed.slice(eq + 1);
      }
      const spec: CustomAgentSpec = {
        id: customForm.id.trim(),
        name: customForm.name.trim(),
        version: customForm.version?.trim() || undefined,
        cmd: customForm.cmd.trim(),
        args,
        env,
      };
      if (!spec.id || !spec.name || !spec.cmd) {
        customError = "id, name, and cmd are required";
        return;
      }
      await api.addCustomAgent(spec);
      showCustomForm = false;
      customForm = { id: "", name: "", version: "", cmd: "", args: [], env: {} };
      argsText = "";
      envText = "";
      await agentsStore.refresh();
    } catch (e) {
      customError = `${e}`;
    }
  }

  function isInstalled(registryId: string): boolean {
    return agentsStore.list.some(
      (a) => a.id === registryId && a.source === "registry",
    );
  }
</script>

<section class="agents">
  <header>
    <h2>Agents</h2>
    <div class="header-actions">
      <button type="button" onclick={() => refreshRegistry(true)} disabled={registryLoading}>
        Refresh registry
      </button>
      <button type="button" onclick={() => (showCustomForm = !showCustomForm)}>
        {showCustomForm ? "Cancel" : "Add custom agent"}
      </button>
    </div>
  </header>

  {#if showCustomForm}
    <form class="custom-form" onsubmit={submitCustom}>
      <h3>Custom agent</h3>
      <label>
        <span>ID</span>
        <input bind:value={customForm.id} placeholder="my-local-agent" />
      </label>
      <label>
        <span>Name</span>
        <input bind:value={customForm.name} placeholder="My Agent" />
      </label>
      <label>
        <span>Version (optional)</span>
        <input bind:value={customForm.version} placeholder="0.1" />
      </label>
      <label>
        <span>Command</span>
        <input bind:value={customForm.cmd} placeholder="/usr/local/bin/my-agent" />
      </label>
      <label>
        <span>Args (space-separated)</span>
        <input bind:value={argsText} placeholder="--stdio" />
      </label>
      <label>
        <span>Env (KEY=VALUE per line)</span>
        <textarea rows="3" bind:value={envText}></textarea>
      </label>
      <div class="actions">
        <button type="submit">Save</button>
        {#if customError}<span class="err">{customError}</span>{/if}
      </div>
    </form>
  {/if}

  <h3>Installed</h3>
  {#if agentsStore.loading}
    <p class="muted">Loading…</p>
  {:else if agentsStore.list.length === 0}
    <p class="muted">No agents yet. Install one from the registry below or add a custom one.</p>
  {:else}
    <table>
      <thead>
        <tr><th>Name</th><th>ID</th><th>Source</th><th>Version</th><th></th></tr>
      </thead>
      <tbody>
        {#each agentsStore.list as agent (agent.id)}
          <tr>
            <td>{agent.name}</td>
            <td><code>{agent.id}</code></td>
            <td>{agent.source}</td>
            <td>{agent.version ?? ""}</td>
            <td>
              <button type="button" onclick={() => uninstall(agent.id)}>Uninstall</button>
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}

  <h3>Registry</h3>
  {#if registryLoading}
    <p class="muted">Loading registry…</p>
  {:else if registryError}
    <p class="err">{registryError}</p>
  {:else}
    <table>
      <thead>
        <tr><th>Name</th><th>ID</th><th>Version</th><th>Description</th><th></th></tr>
      </thead>
      <tbody>
        {#each registry as agent (agent.id)}
          <tr>
            <td>{agent.name}</td>
            <td><code>{agent.id}</code></td>
            <td>{agent.version}</td>
            <td class="desc">{agent.description}</td>
            <td>
              {#if isInstalled(agent.id)}
                <span class="muted">Installed</span>
              {:else}
                <button type="button" onclick={() => install(agent.id)} disabled={installingId === agent.id}>
                  {installingId === agent.id ? "Installing…" : "Install"}
                </button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</section>

<style>
  .agents {
    max-width: 980px;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .header-actions {
    display: flex;
    gap: 0.5rem;
  }
  h2,
  h3 {
    margin-top: 1.5rem;
  }
  h2:first-child {
    margin-top: 0;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 0.5rem;
    font-size: 0.92rem;
  }
  th,
  td {
    text-align: left;
    padding: 0.4rem 0.6rem;
    border-bottom: 1px solid #2b313a;
    vertical-align: top;
  }
  th {
    color: var(--muted);
    font-weight: 600;
  }
  td.desc {
    color: var(--muted);
    max-width: 360px;
  }
  code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.85rem;
    color: var(--muted);
  }
  button {
    background: #1c2027;
    color: inherit;
    border: 1px solid #2b313a;
    padding: 0.3rem 0.7rem;
    border-radius: 4px;
    cursor: pointer;
  }
  button:hover {
    border-color: var(--accent);
  }
  button[disabled] {
    opacity: 0.6;
    cursor: progress;
  }
  .custom-form {
    display: flex;
    flex-direction: column;
    gap: 0.7rem;
    background: #14181f;
    padding: 1rem;
    border: 1px solid #2b313a;
    border-radius: 6px;
    margin-top: 1rem;
  }
  .custom-form h3 {
    margin: 0;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    font-size: 0.9rem;
  }
  input,
  textarea {
    padding: 0.4rem 0.5rem;
    background: #1c2027;
    border: 1px solid #2b313a;
    color: inherit;
    border-radius: 4px;
    font: inherit;
  }
  .actions {
    display: flex;
    gap: 0.7rem;
    align-items: center;
  }
  .err {
    color: #f85149;
  }
  .muted {
    color: var(--muted);
  }
</style>
