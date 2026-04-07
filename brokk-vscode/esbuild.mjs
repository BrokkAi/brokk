import * as esbuild from "esbuild";

const watch = process.argv.includes("--watch");

/** @type {esbuild.BuildOptions} */
const extensionOpts = {
  entryPoints: ["src/extension.ts"],
  bundle: true,
  outfile: "out/extension.js",
  external: ["vscode"],
  format: "cjs",
  platform: "node",
  target: "node18",
  sourcemap: true,
  minify: !watch,
};

/** @type {esbuild.BuildOptions} */
const webviewOpts = {
  entryPoints: ["src/webview/panel.js"],
  bundle: true,
  outdir: "media",
  format: "iife",
  platform: "browser",
  target: "es2020",
  sourcemap: false,
  minify: !watch,
};

/** @type {esbuild.BuildOptions} */
const workerOpts = {
  entryPoints: ["src/webview/markdown-worker.ts"],
  bundle: true,
  outdir: "media",
  format: "iife",
  platform: "browser",
  target: "es2020",
  sourcemap: false,
  minify: !watch,
};

if (watch) {
  const [extCtx, webCtx, wrkCtx] = await Promise.all([
    esbuild.context(extensionOpts),
    esbuild.context(webviewOpts),
    esbuild.context(workerOpts),
  ]);
  await Promise.all([extCtx.watch(), webCtx.watch(), wrkCtx.watch()]);
  console.log("Watching for changes...");
} else {
  await Promise.all([
    esbuild.build(extensionOpts),
    esbuild.build(webviewOpts),
    esbuild.build(workerOpts),
  ]);
  console.log("Build complete.");
}
