import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import sveltePreprocess from 'svelte-preprocess'

export default defineConfig(({ command }) => ({
  base: './', // Use relative URLs for assets to work correctly in JavaFX WebView
  plugins: [
    svelte({
      preprocess: sveltePreprocess({ typescript: true })
    })
  ],
  build: {
    outDir: '../src/main/resources/mop-web',
    emptyOutDir: true,
    cssCodeSplit: false
  },
  // Only for `vite dev`
  server: {
    port: 5173,
    // Open /dev.html instead of /
    open: command === 'serve' ? '/dev.html' : undefined
  }
}))
