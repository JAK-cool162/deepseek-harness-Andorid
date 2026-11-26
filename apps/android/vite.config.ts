import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const src = (rel: string): string => fileURLToPath(new URL(rel, import.meta.url))

/**
 * Builds only the Android bootstrap and its host Worker. The shell page, its
 * chunks, and the VFS image come from `apps/web`'s preview build; the asset
 * assembly script joins both into the APK's `www/` directory.
 */
export default defineConfig({
  base: './',
  build: {
    // The bootstrap holds the page at top-level await.
    target: 'es2022',
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    rollupOptions: {
      input: { bootstrap: src('./web/bootstrap.ts') },
      output: { entryFileNames: 'android/[name]-[hash].js', chunkFileNames: 'android/[name]-[hash].js' },
    },
  },
  worker: {
    rollupOptions: { output: { entryFileNames: 'android/[name]-[hash].js' } },
  },
})
