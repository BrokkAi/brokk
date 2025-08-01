import {defineConfig} from 'vite'
import {svelte} from '@sveltejs/vite-plugin-svelte'
import sveltePreprocess from 'svelte-preprocess'
import fs from 'fs'
import path from 'path'

export default defineConfig(({command}) => {
    let workerUrl = '/markdown.worker.mjs'; // Fallback for dev if manifest isn't ready
    try {
        const manifestPath = path.resolve(__dirname, 'public/.vite/manifest.json');
        if (fs.existsSync(manifestPath)) {
            const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8'));
            const workerEntry = Object.values(manifest).find(entry => entry.isEntry && entry.src === 'src/worker/markdown.worker.ts');
            if (workerEntry) {
                workerUrl = `/${workerEntry.file}`;
            }
        }
    } catch (error) {
        console.warn('Could not load worker manifest, using fallback URL:', error.message);
    }

    return {
        base: './', // Use relative URLs for assets to work correctly in JavaFX WebView
        plugins: [
            svelte({
                preprocess: sveltePreprocess({typescript: true})
            })
        ],
        resolve: {
            // Main thread bundle: keep browser-first to get DOM-aware builds
            conditions: ['browser', 'import', 'default'],
        },
        build: {
            sourcemap: false,
            outDir: '../app/src/main/resources/mop-web',
            emptyOutDir: true,
            cssCodeSplit: false
        },
        define: {
            '__WORKER_URL__': JSON.stringify(workerUrl)
        },
        // Only for `vite dev`
        server: {
            port: 5173,
            // Open /dev.html instead of /
            open: command === 'serve' ? '/dev.html' : undefined
        }
    };
})
