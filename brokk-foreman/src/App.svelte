<script lang="ts">
  import Settings from "./routes/Settings.svelte";
  import Agents from "./routes/Agents.svelte";
  import Session from "./routes/Session.svelte";

  type Tab = "session" | "agents" | "settings";

  // Hash-routing keeps the chosen tab across reloads without pulling in
  // a router dep. v1 has 3 routes; a switch on a state variable is plenty.
  function tabFromHash(hash: string): Tab {
    const trimmed = hash.replace(/^#/, "");
    if (trimmed === "agents" || trimmed === "settings" || trimmed === "session") {
      return trimmed;
    }
    return "session";
  }

  let tab: Tab = $state(tabFromHash(window.location.hash));

  function go(next: Tab) {
    tab = next;
    window.location.hash = next;
  }

  window.addEventListener("hashchange", () => {
    tab = tabFromHash(window.location.hash);
  });
</script>

<div class="app">
  <nav>
    <span class="brand">brokk-foreman</span>
    <button class:active={tab === "session"} onclick={() => go("session")}>
      Session
    </button>
    <button class:active={tab === "agents"} onclick={() => go("agents")}>
      Agents
    </button>
    <button class:active={tab === "settings"} onclick={() => go("settings")}>
      Settings
    </button>
  </nav>

  <main>
    {#if tab === "session"}
      <Session />
    {:else if tab === "agents"}
      <Agents />
    {:else}
      <Settings />
    {/if}
  </main>
</div>

<style>
  .app {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }
  nav {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.7rem 1.2rem;
    border-bottom: 1px solid #2b313a;
    background: #14181f;
  }
  .brand {
    font-weight: 700;
    margin-right: 1rem;
    color: var(--accent);
  }
  nav button {
    background: transparent;
    color: inherit;
    border: 1px solid transparent;
    padding: 0.35rem 0.8rem;
    border-radius: 4px;
    cursor: pointer;
    font: inherit;
  }
  nav button.active {
    border-color: #2b313a;
    background: #1c2027;
  }
  nav button:hover {
    border-color: var(--accent);
  }
  main {
    padding: 1.5rem 2rem;
    flex: 1;
  }
</style>
