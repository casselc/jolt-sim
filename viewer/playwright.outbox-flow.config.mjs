import {defineConfig, devices} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const viewerDir = path.dirname(fileURLToPath(import.meta.url));
const repositoryDir = path.resolve(viewerDir, "..");
const workbenchDir = path.join(repositoryDir, "examples", "outbox-workbench");
const artifactDir = path.join(
  repositoryDir, "target", "ripple-playwright", "outbox-flow"
);
const retainedArtifactDir = path.join(artifactDir, "retained-workers");
const port = Number(process.env.JOLT_SIM_OUTBOX_BROWSER_PORT || "8794");
const capabilityToken = "outbox-flow-browser-token-0000000001";
const childJolt = process.env.JOLT_SIM_BIN;
const parentJolt = process.env.JOLT_SIM_EVAL_BIN || childJolt;
const chezWrapper = process.env.JOLT_SIM_CHEZ_WRAPPER;

fs.mkdirSync(retainedArtifactDir, {recursive: true});

if (!childJolt || !parentJolt) {
  throw new Error(
    "JOLT_SIM_BIN is required; " +
    "JOLT_SIM_EVAL_BIN may select a distinct eval-capable parent"
  );
}

const shellQuote = (value) => `'${value.replaceAll("'", "'\\''")}'`;
const noProxy = [
  process.env.NO_PROXY, process.env.no_proxy, "127.0.0.1", "localhost"
].filter(Boolean).join(",");

process.env.NO_PROXY = noProxy;
process.env.no_proxy = noProxy;

export default defineConfig({
  testDir: path.join(viewerDir, "test-browser-real"),
  testMatch: "outbox-flow-real.spec.mjs",
  outputDir: path.join(artifactDir, "test-results"),
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: {timeout: 20_000},
  reporter: [
    ["line"],
    ["html", {outputFolder: path.join(artifactDir, "report"), open: "never"}]
  ],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    ...devices["Desktop Chrome"],
    trace: "on",
    video: "on",
    screenshot: "on"
  },
  webServer: {
    command: [
      ...(chezWrapper ? [shellQuote(chezWrapper)] : []),
      shellQuote(parentJolt),
      "-M:flow-ripple",
      "config/ripple-browser.edn"
    ].join(" "),
    cwd: workbenchDir,
    env: {
      ...process.env,
      NO_PROXY: noProxy,
      no_proxy: noProxy,
      JOLT_AOT_CACHE: "0",
      JOLT_SIM_VIEWER_TOKEN: capabilityToken,
      JOLT_SIM_VIEWER_PORT: String(port),
      JOLT_SIM_BIN: childJolt,
      JOLT_SIM_PROJECT_DIR:
        process.env.JOLT_SIM_PROJECT_DIR || repositoryDir,
      JOLT_SIM_RETAINED_ARTIFACT_DIR: retainedArtifactDir
    },
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    stdout: "pipe",
    stderr: "pipe",
    timeout: 120_000
  }
});
