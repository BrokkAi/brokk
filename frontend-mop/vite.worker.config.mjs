import { defineConfig } from 'vite'

export default defineConfig({
  resolve: {
    // Worker bundles get worker-specific resolve conditions
    conditions: ['worker', 'import', 'default']
  },
  build: {
    lib: {
      entry: 'src/markdown.worker.ts',
      name: 'MarkdownWorker',
      fileName: 'markdown.worker',
      formats: ['es']
    },
    outDir: 'public',
    emptyOutDir: false
  }
})
