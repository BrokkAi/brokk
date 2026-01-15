<script lang="ts">
  import CodeBlockShell from "./CodeBlockShell.svelte";

  let { children, ...rest } = $props();

  const toolHeadline = rest["data-tool-headline"] as string | undefined;
  const collapseDefault = rest["data-collapse-default"] === "true";
  const languageLabel = (rest["data-language"] as string | undefined) || "Code";
</script>

<CodeBlockShell {toolHeadline} {collapseDefault} {languageLabel}>
  <svelte:fragment slot="default" let:preId let:registerPre let:handleWheel>
    <pre id={preId} use:registerPre on:wheel|passive={handleWheel} {...rest}>
{@render children?.()}
    </pre>
  </svelte:fragment>
</CodeBlockShell>
