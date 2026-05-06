<script lang="ts">
  // Placeholder root component. The kanban, issue view, and agent picker
  // land in subsequent commits.
  let placeholder = "Brokk Foreman is alive.";
</script>

<main>
  <h1>Brokk Foreman</h1>
  <p>{placeholder}</p>
  <p class="muted">
    Cross-platform issue manager and ACP client. Skeleton commit — kanban,
    issue view, and agent picker arrive next.
  </p>
</main>

<style>
  main {
    max-width: 720px;
    margin: 4rem auto;
    padding: 2rem;
  }
  h1 {
    margin-top: 0;
  }
  .muted {
    color: var(--muted);
  }
</style>
