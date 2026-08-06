import {defineConfig, devices} from "@playwright/test";
import path from "node:path";
import {fileURLToPath} from "node:url";

const viewerDir = path.dirname(fileURLToPath(import.meta.url));
const repositoryDir = path.resolve(viewerDir, "..");
const artifactDir = path.join(repositoryDir, "target", "ripple-playwright");
const retainedServerDir = path.join(artifactDir, "retained", "server");
// One value must survive Playwright's independent config evaluation in the
// runner and worker processes. Local callers can override a conflicting port.
const port = Number(process.env.JOLT_SIM_BROWSER_PORT || "8791");
const baseURL = `http://127.0.0.1:${port}`;
const joltBin = process.env.JOLT_SIM_BIN || "joltc";
const quotedJoltBin = `'${joltBin.replaceAll("'", "'\\''")}'`;
const noProxy = [process.env.NO_PROXY, process.env.no_proxy, "127.0.0.1", "localhost"]
  .filter(Boolean)
  .join(",");

// Restricted development environments commonly inject an outbound HTTP
// proxy. Keep both Playwright's readiness probe and browser API traffic on the
// real loopback listener instead of mistaking a proxy denial for a busy port.
process.env.NO_PROXY = noProxy;
process.env.no_proxy = noProxy;

export default defineConfig({
  testDir: path.join(viewerDir, "test-browser"),
  outputDir: path.join(artifactDir, "test-results"),
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: {timeout: 10_000},
  reporter: [
    ["line"],
    ["html", {outputFolder: path.join(artifactDir, "report"), open: "never"}]
  ],
  use: {
    baseURL,
    ...devices["Desktop Chrome"],
    trace: "retain-on-failure",
    video: "retain-on-failure",
    screenshot: "only-on-failure"
  },
  webServer: {
    command: `${quotedJoltBin} -M:browser-test-server`,
    cwd: viewerDir,
    env: {
      ...process.env,
      NO_PROXY: noProxy,
      no_proxy: noProxy,
      JOLT_AOT_CACHE: process.env.JOLT_AOT_CACHE || "0",
      JOLT_SIM_BROWSER_ARTIFACT_DIR: retainedServerDir,
      JOLT_SIM_BROWSER_PORT: String(port)
    },
    url: baseURL,
    reuseExistingServer: false,
    stdout: "pipe",
    stderr: "pipe",
    timeout: 120_000
  }
});
