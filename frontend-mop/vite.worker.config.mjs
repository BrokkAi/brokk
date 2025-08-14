import {defineConfig} from 'vite'

export default defineConfig({
    resolve: {
        // Worker bundles get worker-specific resolve conditions
        conditions: ['worker', 'import', 'default']
    },
    build: {
        sourcemap: true,
        lib: {
            entry: 'src/worker/markdown.worker.ts',
            name: 'MarkdownWorker',
            formats: ['es']
        },
        outDir: 'public',
        emptyOutDir: true,
        manifest: true
    }
})
