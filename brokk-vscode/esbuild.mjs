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

if (watch) {
  const [extCtx, webCtx] = await Promise.all([
    esbuild.context(extensionOpts),
    esbuild.context(webviewOpts),
  ]);
  await Promise.all([extCtx.watch(), webCtx.watch()]);
  console.log("Watching for changes...");
} else {
  await Promise.all([
    esbuild.build(extensionOpts),
    esbuild.build(webviewOpts),
  ]);
  console.log("Build complete.");
}
