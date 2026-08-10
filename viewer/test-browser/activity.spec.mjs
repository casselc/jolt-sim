import {expect, test} from "@playwright/test";
import path from "node:path";
import {fileURLToPath} from "node:url";

const testDir = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(testDir, "fixtures", "activity-case-outcome.edn");
const officialRunFixture = path.join(
  testDir, "fixtures", "official-maelstrom-run.edn"
);
const capabilityToken = "ripple-browser-test-capability-0123456789abcdef";
const semanticKind = "jolt.sim.kind/browser-test-activity";
const outboxPresetId = "jolt.sim.preset/outbox-cancel-before-ack-v1";
const outboxRegimeId = "jolt.sim.regime/outbox-cancel-before-ack-canonical";
const echoPresetId = "jolt.sim.preset/maelstrom-echo-roundtrip-v1";
const echoRegimeId = "jolt.sim.regime/maelstrom-echo-canonical";
const outboxLabPresetId =
  "jolt.sim.preset/outbox-first-poll-regime-lab-v1";
const receiverFirstNoEintr =
  "jolt.example.outbox.regime/receiver-first-no-eintr";
const httpFirstEintr1 =
  "jolt.example.outbox.regime/http-first-poll-eintr-1";
const broadcastPresetId =
  "jolt.sim.preset/maelstrom-broadcast-partition-heal-v1";
const broadcastHealthyRegimeId =
  "jolt.sim.regime/maelstrom-broadcast-healthy";
const broadcastPartitionRegimeId =
  "jolt.sim.regime/maelstrom-broadcast-partition-heal";
const jsonIdempotencyPresetId =
  "jolt.sim.preset/outbox-json-idempotency-lab-v1";
const jsonExactReplayRegimeId =
  "jolt.sim.regime/outbox-json-exact-replay";
const jsonConflictRegimeId =
  "jolt.sim.regime/outbox-json-conflict";

function expectRunCatalogV2(catalog) {
  expect(Object.keys(catalog).sort()).toEqual(["presets", "version"]);
  expect(catalog.version).toBe(2);
  expect(catalog.presets).toHaveLength(5);
  for (const preset of catalog.presets) {
    expect(Object.keys(preset).sort()).toEqual(
      ["id", "label", "planEdn", "profileId", "regimes"]);
    expect(preset.regimes.length).toBeGreaterThan(0);
    for (const regime of preset.regimes) {
      expect(Object.keys(regime).sort()).toEqual(
        ["id", "label", "scope", "summary"]);
      expect(regime.scope.length).toBeGreaterThan(0);
    }
  }
  const objectKeys = [];
  const visit = (value) => {
    if (Array.isArray(value)) {
      value.forEach(visit);
    } else if (value !== null && typeof value === "object") {
      for (const [key, child] of Object.entries(value)) {
        objectKeys.push(key);
        visit(child);
      }
    }
  };
  visit(catalog);
  expect(objectKeys).not.toContain("input");
  expect(objectKeys).not.toContain("coordinates");
  expect(objectKeys).not.toContain("scenario");
  expect(objectKeys).not.toContain("schedule");
  expect(catalog.presets.find(({id}) => id === outboxPresetId).regimes)
    .toHaveLength(1);
  expect(catalog.presets.find(({id}) => id === echoPresetId).regimes)
    .toHaveLength(1);
  expect(catalog.presets.find(({id}) => id === outboxLabPresetId).regimes)
    .toHaveLength(10);
  expect(catalog.presets.find(({id}) => id === broadcastPresetId).regimes)
    .toHaveLength(2);
  expect(catalog.presets.find(({id}) => id === jsonIdempotencyPresetId).regimes)
    .toHaveLength(2);
}

async function expectActivityRows(page, start, end) {
  const rows = page.locator('[data-testid="activity-row"]');
  const expectedSequences = Array.from(
    {length: end - start + 1},
    (_, offset) => String(start + offset)
  );

  await expect(rows).toHaveCount(expectedSequences.length);
  await expect.poll(() => rows.evaluateAll((items) =>
    items.map((item) => item.dataset.sequence)
  )).toEqual(expectedSequences);

  const observed = await rows.evaluateAll((items) => items.map((item) => ({
    sequence: item.dataset.sequence,
    kind: item.dataset.kind,
    text: item.textContent,
    raw: item.querySelector("details code")?.textContent || ""
  })));

  expect(new Set(observed.map(({sequence}) => sequence)).size)
    .toBe(expectedSequences.length);
  for (const row of observed) {
    const sequence = Number(row.sequence);
    expect(row.kind).toBe(semanticKind);
    expect(row.text).toContain(`Browser activity event ${sequence}`);
    expect(row.text).toContain(semanticKind);
    expect(row.raw).toContain(":jolt.sim.browser-test/activity");
    expect(row.raw).toContain(`:ordinal ${sequence}`);
  }
}

async function loadAndReplay(page) {
  await page.goto("/");
  await page.locator("#kind").selectOption("case-outcome");
  await page.locator("#case-file").setInputFiles(fixture);
  await expect(page.locator("#status")).toContainText(
    "Loaded activity-case-outcome.edn"
  );
  await page.locator("#capability").fill(capabilityToken);
  await expect(page.locator("#replay")).toBeEnabled();
  await page.locator("#replay").click();
  await expect(page.locator("#status")).toHaveText(
    "Fresh replay completed; raw outcome preserved below."
  );
  await expect(page.getByTestId("activity-page-status")).toHaveText(
    "Showing events 0–31 of 40."
  );
}

test("uses Ripple as a persistent Jolt REPL without automatic retries", async ({page}, testInfo) => {
  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  const form = page.getByTestId("eval-form");
  const submit = page.getByTestId("eval-submit");

  await form.fill("(def ripple-browser-answer 42)");
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page.getByTestId("eval-status")).toContainText(
    "Evaluation #0 completed in user."
  );

  await form.fill("[ripple-browser-answer *1]");
  await submit.click();
  await expect(page.getByTestId("eval-status")).toContainText(
    "Evaluation #1 completed in user."
  );
  const entries = page.locator(".eval-entry");
  await expect(entries).toHaveCount(2);
  await expect(entries.nth(1)).toContainText(
    "[42 \"#<class clojure.lang.Var>\"]"
  );
  await expect(entries.nth(1)).toContainText("bounded by Ripple");

  await form.fill("(throw (ex-info \"browser-boom\" {:ripple true}))");
  await submit.click();
  await expect(page.getByTestId("eval-status")).toContainText(
    "Evaluation #2 completed with an exception in user."
  );
  await expect(entries).toHaveCount(3);
  await expect(entries.nth(2).locator('[aria-label="Evaluation exception"]'))
    .toContainText("browser-boom");
  await page.screenshot({
    path: testInfo.outputPath("ripple-persistent-eval-session.png"),
    fullPage: true
  });
});

test("inspects retained official Maelstrom evidence without replay", async ({page}) => {
  await page.goto("/");
  await page.locator("#kind").selectOption("official-maelstrom-run");
  await page.locator("#case-file").setInputFiles(officialRunFixture);
  await page.locator("#capability").fill(capabilityToken);
  await expect(page.locator("#inspect")).toBeEnabled();
  await expect(page.locator("#replay")).toBeDisabled();
  await page.locator("#inspect").click();
  await expect(page.locator("#status")).toHaveText(
    "Validated report rendered."
  );
  const report = page.frameLocator("#report");
  await expect(report.locator("h1")).toHaveText(
    "jolt-sim official Maelstrom run report"
  );
  await expect(report.locator("body")).toContainText(":echo");
  await expect(report.locator('[data-kind="jolt.sim.kind/maelstrom-operation"]'))
    .toHaveCount(2);
});

test("runs one trusted canonical application preset and renders its topology", async ({page}, testInfo) => {
  let releaseRun;
  const release = new Promise((resolve) => { releaseRun = resolve; });
  let observeRun;
  const observed = new Promise((resolve) => { observeRun = resolve; });
  let runBody = null;

  await page.route("**/api/run", async (route) => {
    runBody = route.request().postData();
    observeRun();
    await release;
    await route.continue();
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await expect(page.locator("#load-run-presets")).toBeEnabled();
  const catalogResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/run-presets");
  await page.locator("#load-run-presets").click();
  expectRunCatalogV2(await (await catalogResponse).json());

  await expect(page.locator("#run-preset")).toHaveValue(
    outboxPresetId
  );
  await expect(page.getByTestId("run-preset-status")).toContainText(
    "is ready. Run new uses the selected server-owned regime."
  );
  await expect(page.getByTestId("run-regime")).toHaveValue(outboxRegimeId);
  await expect(page.getByTestId("run-regime-summary")).toHaveText(
    "Run the trusted cancel-before-ack example with its fixed server-owned " +
    "coordinates. Scope: jolt.example.outbox/cancellation."
  );
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(4);
  await expect(page.frameLocator("#report").locator(".topology-edge"))
    .toHaveCount(3);
  await expect(page.frameLocator("#report").locator("body"))
    .toContainText("cancel-before-ack-v1");

  await page.getByTestId("run-new").click();
  await observed;
  await expect(page.getByTestId("run-new")).toBeDisabled();
  await expect(page.locator("#run-preset")).toBeDisabled();
  await expect(page.getByTestId("run-regime")).toBeDisabled();
  expect(JSON.parse(runBody)).toEqual({
    version: 2,
    presetId: outboxPresetId,
    regimeId: outboxRegimeId
  });
  releaseRun();

  await expect(page.locator("#status")).toHaveText(
    "Outbox: cancel before acknowledgment completed; raw outcome and retained activity are available."
  );
  await expect(page.locator("#activity")).toContainText("status: completed");
  await expect(page.getByTestId("activity-page-status")).toHaveText(
    "Showing events 0–31 of 40."
  );
  await expectActivityRows(page, 0, 31);
  await expect(page.locator("#outcome")).not.toContainText(":artifact-dir");
  await expect(page.locator("html")).not.toContainText("activity.journal");
  await page.screenshot({
    path: testInfo.outputPath("run-new-canonical-outbox.png"),
    fullPage: true
  });
});

test("distinguishes trusted presets and their simulation regimes", async ({page}, testInfo) => {
  const echoLabel = "Maelstrom Echo: init and echo round trip";
  let releaseRun;
  const release = new Promise((resolve) => { releaseRun = resolve; });
  let observeRun;
  const observed = new Promise((resolve) => { observeRun = resolve; });
  let runBody = null;

  await page.route("**/api/run", async (route) => {
    runBody = route.request().postData();
    observeRun();
    await release;
    await route.continue();
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#load-run-presets").click();
  await expect(page.locator("#run-preset option")).toHaveCount(5);

  // The closed catalog offers exactly the five trusted examples as distinct
  // selectable identities; the existing outbox preset remains the default.
  const options = await page.locator("#run-preset option").evaluateAll(
    (items) => items.map((item) => ({value: item.value, text: item.textContent}))
  );
  expect(options).toEqual([
    {value: outboxPresetId,
      text: "Outbox: cancel before acknowledgment (hermetic)"},
    {value: echoPresetId, text: `${echoLabel} (hermetic)`},
    {value: outboxLabPresetId,
      text: "Outbox: poll admission and EINTR regime lab (hermetic)"},
    {value: broadcastPresetId,
      text: "Maelstrom Broadcast: healthy line and partition/heal (hermetic)"},
    {value: jsonIdempotencyPresetId,
      text: "Outbox: JSON idempotency replay/conflict lab (hermetic)"}
  ]);
  await expect(page.locator("#run-preset")).toHaveValue(outboxPresetId);
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(4);
  await expect(page.frameLocator("#report").locator(".topology-edge"))
    .toHaveCount(3);

  // Selecting the Echo preset renders its own inert two-endpoint topology.
  await page.locator("#run-preset").selectOption(echoPresetId);
  await expect(page.locator("#run-preset")).toHaveValue(echoPresetId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${echoLabel} is ready. Run new uses the selected server-owned regime.`
  );
  await expect(page.getByTestId("run-regime").locator("option"))
    .toHaveCount(1);
  await expect(page.getByTestId("run-regime")).toHaveValue(echoRegimeId);
  await expect(page.getByTestId("run-regime-summary")).toContainText(
    "Scope: jolt.maelstrom.echo/roundtrip."
  );
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(2);
  await expect(page.frameLocator("#report").locator(".topology-edge"))
    .toHaveCount(2);
  await expect(page.frameLocator("#report").locator("body"))
    .toContainText("jolt.maelstrom.echo/roundtrip-v1");
  await expect(page.frameLocator("#report").locator("body"))
    .not.toContainText("cancel-before-ack");

  // Switching back restores the outbox projection: selection is real.
  await page.locator("#run-preset").selectOption(outboxPresetId);
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(4);
  await expect(page.frameLocator("#report").locator("body"))
    .toContainText("cancel-before-ack-v1");

  // The regime lab exposes all ten application-owned choices and updates its
  // explanatory projection without revealing their trusted input maps.
  await page.locator("#run-preset").selectOption(outboxLabPresetId);
  await expect(page.getByTestId("run-regime").locator("option"))
    .toHaveCount(10);
  await expect(page.getByTestId("run-regime")).toHaveValue(receiverFirstNoEintr);
  await page.getByTestId("run-regime").selectOption(httpFirstEintr1);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    "Outbox: poll admission and EINTR regime lab is ready with HTTP poll " +
    "first; EINTR at poll 1."
  );
  await expect(page.getByTestId("run-regime-summary")).toContainText(
    "Admit the HTTP reactor's first poll before the receiver reactor's first poll"
  );
  await expect(page.getByTestId("run-regime-summary")).toContainText(
    "jolt.example.outbox/first-poll-admission, " +
    "jolt.example.outbox/modeled-poll-eintr"
  );
  await page.screenshot({
    path: testInfo.outputPath("outbox-regime-lab-selection.png"),
    fullPage: true
  });

  // Running the selected Echo preset posts only its exact trusted pair of IDs.
  await page.locator("#run-preset").selectOption(echoPresetId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${echoLabel} is ready.`
  );
  await page.getByTestId("run-new").click();
  await observed;
  await expect(page.getByTestId("run-new")).toBeDisabled();
  await expect(page.locator("#run-preset")).toBeDisabled();
  await expect(page.getByTestId("run-regime")).toBeDisabled();
  expect(JSON.parse(runBody)).toEqual({
    version: 2,
    presetId: echoPresetId,
    regimeId: echoRegimeId
  });
  releaseRun();

  await expect(page.locator("#status")).toHaveText(
    `${echoLabel} completed; raw outcome and retained activity are available.`
  );
  await expect(page.locator("#outcome")).not.toContainText(":artifact-dir");
  await page.screenshot({
    path: testInfo.outputPath("run-new-maelstrom-echo.png"),
    fullPage: true
  });
});

test("selects and submits one trusted Broadcast regime with its exact line topology", async ({page}, testInfo) => {
  const broadcastLabel = "Maelstrom Broadcast: healthy line and partition/heal";
  let releaseRun;
  const release = new Promise((resolve) => { releaseRun = resolve; });
  let observeRun;
  const observed = new Promise((resolve) => { observeRun = resolve; });
  let runBody = null;

  await page.route("**/api/run", async (route) => {
    runBody = route.request().postData();
    observeRun();
    await release;
    await route.continue();
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#load-run-presets").click();
  await expect(page.locator("#run-preset option")).toHaveCount(5);

  // Selecting the Broadcast preset renders its inert four-node line
  // topology: client, n1, n2, n3 joined by the ten truthful directed
  // request/reply connections of the n1-n2-n3 line; there is no n1-n3 edge.
  await page.locator("#run-preset").selectOption(broadcastPresetId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${broadcastLabel} is ready. Run new uses the selected server-owned regime.`
  );
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(4);
  await expect(page.frameLocator("#report").locator(".topology-edge"))
    .toHaveCount(10);
  const topologyNodes = await page.frameLocator("#report")
    .locator(".topology-node")
    .evaluateAll((items) => items.map((item) => item.dataset.node).sort());
  expect(topologyNodes).toEqual([":client", ":n1", ":n2", ":n3"]);
  const topologyEdges = await page.frameLocator("#report")
    .locator(".topology-edge")
    .evaluateAll((items) => items.map((item) => ({
      connection: item.dataset.connection,
      from: item.dataset.fromNode,
      to: item.dataset.toNode
    })).sort((left, right) => left.connection.localeCompare(right.connection)));
  expect(topologyEdges).toEqual([
    {connection: ":client-to-n1-requests", from: ":client", to: ":n1"},
    {connection: ":client-to-n2-requests", from: ":client", to: ":n2"},
    {connection: ":client-to-n3-requests", from: ":client", to: ":n3"},
    {connection: ":n1-to-client-replies", from: ":n1", to: ":client"},
    {connection: ":n1-to-n2-requests", from: ":n1", to: ":n2"},
    {connection: ":n2-to-client-replies", from: ":n2", to: ":client"},
    {connection: ":n2-to-n1-replies", from: ":n2", to: ":n1"},
    {connection: ":n2-to-n3-requests", from: ":n2", to: ":n3"},
    {connection: ":n3-to-client-replies", from: ":n3", to: ":client"},
    {connection: ":n3-to-n2-replies", from: ":n3", to: ":n2"}
  ]);
  expect(topologyEdges.some(({from, to}) =>
    (from === ":n1" && to === ":n3") ||
    (from === ":n3" && to === ":n1"))).toBe(false);
  await expect(page.frameLocator("#report").locator("body"))
    .toContainText("jolt.maelstrom.broadcast/partition-heal-v1");

  // The preset owns exactly two server-owned regime choices and selecting the
  // partition regime changes only the server-owned coordinate pair.
  await expect(page.getByTestId("run-regime").locator("option"))
    .toHaveCount(2);
  await expect(page.getByTestId("run-regime"))
    .toHaveValue(broadcastHealthyRegimeId);
  await expect(page.getByTestId("run-regime-summary")).toContainText(
    "Scope: jolt.maelstrom.broadcast/link-partition-selection."
  );
  await page.getByTestId("run-regime").selectOption(broadcastPartitionRegimeId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${broadcastLabel} is ready with Partition n2-n3, heal, retry.`
  );

  // Submitting posts only the exact trusted pair of IDs: no scenario, input,
  // schedule, path, or environment coordinate ever leaves the browser. This
  // deterministic browser fixture returns generic activity; the separate
  // real-worker E2E proves actual Broadcast execution.
  await page.getByTestId("run-new").click();
  await observed;
  await expect(page.getByTestId("run-new")).toBeDisabled();
  await expect(page.locator("#run-preset")).toBeDisabled();
  await expect(page.getByTestId("run-regime")).toBeDisabled();
  expect(JSON.parse(runBody)).toEqual({
    version: 2,
    presetId: broadcastPresetId,
    regimeId: broadcastPartitionRegimeId
  });
  releaseRun();

  await expect(page.locator("#status")).toHaveText(
    `${broadcastLabel} completed; raw outcome and retained activity are available.`
  );
  await expect(page.locator("#activity")).toContainText("status: completed");
  await expect(page.getByTestId("activity-page-status")).toHaveText(
    "Showing events 0–31 of 40."
  );
  await expectActivityRows(page, 0, 31);
  await expect(page.locator("#outcome")).not.toContainText(":artifact-dir");
  await page.screenshot({
    path: testInfo.outputPath("select-maelstrom-broadcast-partition.png"),
    fullPage: true
  });
});

test("selects and submits the trusted JSON conflict regime with its exact topology", async ({page}, testInfo) => {
  const jsonLabel = "Outbox: JSON idempotency replay/conflict lab";
  let releaseRun;
  const release = new Promise((resolve) => { releaseRun = resolve; });
  let observeRun;
  const observed = new Promise((resolve) => { observeRun = resolve; });
  let runBody = null;

  await page.route("**/api/run", async (route) => {
    runBody = route.request().postData();
    observeRun();
    await release;
    await route.continue();
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  const catalogResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname === "/api/run-presets");
  await page.locator("#load-run-presets").click();
  expectRunCatalogV2(await (await catalogResponse).json());
  await expect(page.locator("#run-preset option")).toHaveCount(5);

  // Selecting the JSON idempotency lab renders its inert four-node ordinary
  // outbox topology: client, app, receiver, and sqlite joined by the three
  // truthful command/delivery/store connections.
  await page.locator("#run-preset").selectOption(jsonIdempotencyPresetId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${jsonLabel} is ready. Run new uses the selected server-owned regime.`
  );
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(4);
  await expect(page.frameLocator("#report").locator(".topology-edge"))
    .toHaveCount(3);
  const topologyNodes = await page.frameLocator("#report")
    .locator(".topology-node")
    .evaluateAll((items) => items.map((item) => item.dataset.node).sort());
  expect(topologyNodes).toEqual([":app", ":client", ":receiver", ":sqlite"]);
  const topologyEdges = await page.frameLocator("#report")
    .locator(".topology-edge")
    .evaluateAll((items) => items.map((item) => ({
      connection: item.dataset.connection,
      from: item.dataset.fromNode,
      to: item.dataset.toNode
    })).sort((left, right) => left.connection.localeCompare(right.connection)));
  expect(topologyEdges).toEqual([
    {connection: ":command", from: ":client", to: ":app"},
    {connection: ":delivery", from: ":app", to: ":receiver"},
    {connection: ":store", from: ":app", to: ":sqlite"}
  ]);
  await expect(page.frameLocator("#report").locator("body"))
    .toContainText("outbox.experiment/json-idempotency-lab-v1");
  await expect(page.frameLocator("#report").locator("body"))
    .not.toContainText("cancel-before-ack");

  // The preset owns exactly two server-owned regime choices and selecting the
  // conflict regime changes only the server-owned coordinate pair.
  await expect(page.getByTestId("run-regime").locator("option"))
    .toHaveCount(2);
  await expect(page.getByTestId("run-regime"))
    .toHaveValue(jsonExactReplayRegimeId);
  await expect(page.getByTestId("run-regime-summary")).toContainText(
    "Scope: jolt.example.outbox/request-id-reuse."
  );
  await page.getByTestId("run-regime").selectOption(jsonConflictRegimeId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${jsonLabel} is ready with Conflicting reuse of the request id.`
  );

  // Submitting posts only the exact trusted pair of IDs: no scenario, input,
  // schedule, path, or environment coordinate ever leaves the browser. This
  // deterministic browser fixture returns generic activity; the separate
  // real-worker E2E proves actual replay/conflict execution.
  await page.getByTestId("run-new").click();
  await observed;
  await expect(page.getByTestId("run-new")).toBeDisabled();
  await expect(page.locator("#run-preset")).toBeDisabled();
  await expect(page.getByTestId("run-regime")).toBeDisabled();
  expect(JSON.parse(runBody)).toEqual({
    version: 2,
    presetId: jsonIdempotencyPresetId,
    regimeId: jsonConflictRegimeId
  });
  releaseRun();

  await expect(page.locator("#status")).toHaveText(
    `${jsonLabel} completed; raw outcome and retained activity are available.`
  );
  await expect(page.locator("#activity")).toContainText("status: completed");
  await expect(page.locator("#outcome")).not.toContainText(":artifact-dir");
  await page.screenshot({
    path: testInfo.outputPath("run-new-outbox-json-conflict.png"),
    fullPage: true
  });
});

test("reports an empty trusted preset catalog without inventing an error", async ({page}) => {
  await page.route("**/api/run-presets", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({version: 2, presets: []})
    });
  });
  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#load-run-presets").click();
  await expect(page.getByTestId("run-preset-status")).toHaveText(
    "No trusted runnable examples are configured."
  );
  await expect(page.getByTestId("run-new")).toBeDisabled();
});

test("preserves a producer-attributed commit when its automatic refresh finds a restart", async ({page}) => {
  const instanceA = "ripple-browser-session-instance-A";
  const instanceB = "ripple-browser-session-instance-B";
  const instanceC = "ripple-browser-session-instance-C";
  const frameCursors = [];
  const stepInstances = [];
  let frameCall = 0;
  let stepCall = 0;

  const frame = (revision, nextCursor, label) => ({
    version: 1,
    revision: String(revision),
    nextCursor: String(nextCursor),
    stepEnabled: true,
    frameEdn: `{:fixture ${label} :revision ${revision}}`,
    choices: [{
      revision: String(revision),
      kind: "run",
      value: "2",
      label: `run ${label}`
    }]
  });

  await page.route("**/api/session-frame", async (route) => {
    frameCall += 1;
    frameCursors.push(await route.request().headerValue(
      "X-Jolt-Sim-Journal-Cursor"));
    const [instance, body] = frameCall === 1
      ? [instanceA, frame(0, 1, "A0")]
      : frameCall === 2
        // The acknowledged A command is followed immediately by an automatic
        // refresh whose old cursor reaches restarted producer B.
        ? [instanceB, frame(0, 1, "B-stale-cursor")]
        : frameCall === 3
          ? [instanceB, frame(0, 1, "B0")]
          : [instanceC, frame(0, 1, "C0")];
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: {"X-Jolt-Sim-Session-Instance": instance},
      body: JSON.stringify(body)
    });
  });

  await page.route("**/api/session-step", async (route) => {
    stepCall += 1;
    stepInstances.push(await route.request().headerValue(
      "X-Jolt-Sim-Session-Instance"));
    const command = JSON.parse(route.request().postData()).branch;
    if (stepCall === 1) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        headers: {"X-Jolt-Sim-Session-Instance": instanceA},
        body: JSON.stringify({
          version: 1,
          outcome: "committed",
          committed: true,
          revision: command.revision,
          kind: command.kind,
          value: command.value,
          receiptEdn: "{:version 1 :status :committed}"
        })
      });
      return;
    }
    await route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        outcome: "error",
        committed: false,
        error: "session-step-rejected"
      })
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#session-refresh").click();
  await expect(page.locator("#session-choices button")).toHaveCount(1);
  await page.locator("#session-choices button").click();
  await expect(page.locator("#session-status")).toContainText(
    "Session producer changed; local cursor, choices, and retry state were reset"
  );
  await expect(page.locator("#session-step-status")).toContainText(
    `Committed on session producer ${instanceA}`
  );
  await expect(page.locator("#session-step-status")).toContainText(
    "The producer then changed; its frame was discarded"
  );
  expect(stepInstances).toEqual([instanceA]);

  // The automatic B response is deliberately discarded, but the recognized
  // A receipt above remains visible and authoritative.
  await expect(page.locator("#session-choices button")).toHaveCount(0);
  await page.locator("#session-refresh").click();
  await expect(page.locator("#session-frame")).toContainText(":fixture B0");
  await page.locator("#session-choices button").click();
  await expect(page.locator("#session-step-status")).toContainText(
    "Not committed"
  );
  expect(stepInstances).toEqual([instanceA, instanceB]);

  // User reset forgets the cached epoch as well as coordinates. One C frame
  // is therefore enough to establish the new producer and offer its choice.
  await page.locator("#session-reset").click();
  await page.locator("#session-refresh").click();
  await expect(page.locator("#session-frame")).toContainText(":fixture C0");
  await expect(page.locator("#session-choices button")).toHaveCount(1);
  await page.locator("#session-choices button").click();
  await expect(page.locator("#session-step-status")).toContainText(
    "Not committed"
  );
  expect(stepInstances).toEqual([instanceA, instanceB, instanceC]);
  expect(frameCursors).toEqual(["0", "1", "0", "0"]);
});

test("does not attribute a receipt without the pinned producer epoch", async ({page}) => {
  const instanceA = "ripple-browser-session-instance-A";
  const instanceB = "ripple-browser-session-instance-B";
  let stepCall = 0;

  await page.route("**/api/session-frame", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: {"X-Jolt-Sim-Session-Instance": instanceA},
      body: JSON.stringify({
        version: 1,
        revision: "0",
        nextCursor: "1",
        stepEnabled: true,
        frameEdn: "{:fixture :epoch-receipt-control :revision 0}",
        choices: [{revision: "0", kind: "run", value: "2", label: "run 2"}]
      })
    });
  });

  await page.route("**/api/session-step", async (route) => {
    stepCall += 1;
    const command = JSON.parse(route.request().postData()).branch;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      // The first response omits the producer epoch; the explicit retry lies
      // about its source by claiming a replacement epoch. Neither is an
      // authoritative acknowledgment of A's pinned command.
      headers: stepCall === 1
        ? {}
        : {"X-Jolt-Sim-Session-Instance": instanceB},
      body: JSON.stringify({
        version: 1,
        outcome: "committed",
        committed: true,
        revision: command.revision,
        kind: command.kind,
        value: command.value,
        receiptEdn: "{:version 1 :status :committed}"
      })
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#session-refresh").click();
  await page.locator("#session-choices button").click();
  await expect(page.locator("#session-step-status")).toContainText(
    "without a recognizable receipt"
  );
  await expect(page.locator("#session-step-status")).not.toContainText(
    "Committed on"
  );
  await expect(page.locator("#session-step-retry-row")).toBeVisible();

  await page.locator("#session-step-retry").click();
  await expect(page.locator("#session-step-status")).toContainText(
    "without a recognizable receipt"
  );
  await expect(page.locator("#session-step-status")).not.toContainText(
    "Committed on"
  );
  await expect(page.locator("#session-step-retry-row")).toBeVisible();
  expect(stepCall).toBe(2);
});

test("retries an ambiguous command byte-identically on A before reconciling restarted B", async ({page}) => {
  const instanceA = "ripple-browser-session-instance-A";
  const instanceB = "ripple-browser-session-instance-B";
  const frameCursors = [];
  const stepBodies = [];
  const stepInstances = [];
  let frameCall = 0;
  let stepCall = 0;

  const frame = (label) => ({
    version: 1,
    revision: "0",
    nextCursor: "1",
    stepEnabled: true,
    frameEdn: `{:fixture ${label} :revision 0}`,
    choices: [{revision: "0", kind: "run", value: "2", label: `run ${label}`}]
  });

  await page.route("**/api/session-frame", async (route) => {
    frameCall += 1;
    frameCursors.push(await route.request().headerValue(
      "X-Jolt-Sim-Journal-Cursor"));
    const instance = frameCall === 1 ? instanceA : instanceB;
    const body = frameCall === 1
      ? frame("A0")
      : frameCall === 2
        ? frame("B-stale-cursor")
        : frame("B0");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: {"X-Jolt-Sim-Session-Instance": instance},
      body: JSON.stringify(body)
    });
  });

  await page.route("**/api/session-step", async (route) => {
    stepCall += 1;
    stepBodies.push(route.request().postData());
    stepInstances.push(await route.request().headerValue(
      "X-Jolt-Sim-Session-Instance"));
    if (stepCall === 1) {
      await route.abort("connectionreset");
      return;
    }
    await route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        outcome: "error",
        committed: false,
        error: "session-instance-mismatch"
      })
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#session-refresh").click();
  await page.locator("#session-choices button").click();
  await expect(page.locator("#session-step-status")).toContainText(
    "Network failure without receiving a server acknowledgment"
  );
  await expect(page.locator("#session-step-retry-row")).toBeVisible();

  await page.locator("#session-step-retry").click();
  await expect(page.locator("#session-step-status")).toContainText(
    "explicit retry was rejected (session-instance-mismatch)"
  );
  await expect(page.locator("#session-step-status")).toContainText(
    "original command outcome remains unknown"
  );
  await expect(page.locator("#session-step-status")).toContainText(
    "Reconcile or inspect the journal"
  );
  await expect(page.locator("#session-step-retry-row")).toBeHidden();
  expect(stepBodies).toHaveLength(2);
  expect(stepBodies[1]).toBe(stepBodies[0]);
  expect(stepInstances).toEqual([instanceA, instanceA]);

  // Only an explicit frame refresh may discover B. Its first response was
  // requested with A's cursor and is discarded; the next starts at zero.
  await page.locator("#session-refresh").click();
  await expect(page.locator("#session-status")).toContainText(
    "Session producer changed; local cursor, choices, and retry state were reset"
  );
  await expect(page.locator("#session-choices button")).toHaveCount(0);
  await page.locator("#session-refresh").click();
  await expect(page.locator("#session-frame")).toContainText(":fixture B0");
  await expect(page.locator("#session-choices button")).toHaveCount(1);
  expect(frameCursors).toEqual(["0", "1", "0"]);
});

test("rejects an unsafe session producer header without offering choices", async ({page}) => {
  await page.route("**/api/session-frame", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: {"X-Jolt-Sim-Session-Instance": "too-short"},
      body: JSON.stringify({
        version: 1,
        revision: "0",
        nextCursor: "1",
        stepEnabled: true,
        frameEdn: "{:must-not-be-installed true}",
        choices: [{revision: "0", kind: "run", value: "2", label: "unsafe"}]
      })
    });
  });
  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#session-refresh").click();
  await expect(page.locator("#session-status")).toContainText(
    "invalid session producer instance header"
  );
  await expect(page.locator("#session-frame")).not.toContainText(
    "must-not-be-installed"
  );
  await expect(page.locator("#session-choices button")).toHaveCount(0);
});

test("never labels a non-completed process outcome as completed", async ({page}) => {
  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#load-run-presets").click();
  await expect(page.getByTestId("run-new")).toBeEnabled();
  await page.route("**/api/replay-progress", async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    if (body.status === "completed") body.status = "failed";
    await route.fulfill({response, json: body});
  });
  await page.getByTestId("run-new").click();
  await expect(page.locator("#status")).toContainText(
    "failed: terminal process status failed; raw outcome preserved below."
  );
  await expect(page.getByTestId("run-preset-status")).toHaveText(
    "Outbox: cancel before acknowledgment / Canonical cancellation path " +
    "did not complete; retained evidence remains available."
  );
  await expect(page.locator("#status")).not.toContainText(" completed;");
});

test("replays retained semantic activity through the real Ripple handler", async ({page}, testInfo) => {
  const observedApi = [];
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (!url.pathname.startsWith("/api/")) return;
    observedApi.push((async () => ({
      url: response.url(),
      requestHeaders: await response.request().allHeaders(),
      requestBody: response.request().postData() || "",
      responseHeaders: await response.allHeaders(),
      responseBody: await response.text()
    }))());
  });

  await loadAndReplay(page);
  await expect(page.locator("#activity")).toContainText("status: completed");
  await expectActivityRows(page, 0, 31);
  await expect(page.getByTestId("activity-previous")).toBeDisabled();
  await expect(page.getByTestId("activity-next")).toBeEnabled();
  await page.screenshot({
    path: testInfo.outputPath("activity-events-0-31.png"),
    fullPage: true
  });

  await page.getByTestId("activity-next").click();
  await expect(page.getByTestId("activity-page-status")).toHaveText(
    "Showing events 32–39 of 40."
  );
  await expectActivityRows(page, 32, 39);
  await expect(page.getByTestId("activity-previous")).toBeEnabled();
  await expect(page.getByTestId("activity-next")).toBeDisabled();
  await page.screenshot({
    path: testInfo.outputPath("activity-events-32-39.png"),
    fullPage: true
  });

  await page.getByTestId("activity-previous").click();
  await expect(page.getByTestId("activity-page-status")).toHaveText(
    "Showing events 0–31 of 40."
  );
  await expectActivityRows(page, 0, 31);

  const forbidden = [
    /:artifact-dir/i,
    /activity\.journal/i,
    /jolt-sim-ripple-browser-activity-/i,
    /\/tmp\//i
  ];
  const dom = await page.locator("html").evaluate((element) => element.innerHTML);
  for (const pattern of forbidden) expect(dom).not.toMatch(pattern);

  const exchanges = await Promise.all(observedApi);
  expect(exchanges.length).toBeGreaterThan(1);
  for (const exchange of exchanges) {
    const wire = JSON.stringify(exchange);
    for (const pattern of forbidden) expect(wire).not.toMatch(pattern);
  }
});

test("rejects a page whose body coordinate disagrees with the request", async ({page}) => {
  await loadAndReplay(page);
  await page.route("**/api/replay-progress", async (route) => {
    const cursor = await route.request().headerValue(
      "X-Jolt-Sim-Activity-Cursor");
    const response = await route.fetch();
    if (cursor !== "32") {
      await route.fulfill({response});
      return;
    }
    const body = await response.json();
    body.activity.cursor = 31;
    await route.fulfill({response, json: body});
  });

  await page.getByTestId("activity-next").click();
  await expect(page.getByTestId("activity-page-status")).toContainText(
    "Could not load next activity page: malformed retained activity response"
  );
  await expectActivityRows(page, 0, 31);
});

test("can skip an oversized activity projection using its continuation", async ({page}) => {
  await loadAndReplay(page);
  let replaced = false;
  await page.route("**/api/replay-progress", async (route) => {
    const cursor = await route.request().headerValue(
      "X-Jolt-Sim-Activity-Cursor");
    const response = await route.fetch();
    if (cursor !== "32" || replaced) {
      await route.fulfill({response});
      return;
    }
    replaced = true;
    const body = await response.json();
    body.activity = {
      version: 1,
      status: "too-large",
      limit: 1024,
      actual: 2048,
      cursor: 32,
      nextCursor: 40
    };
    await route.fulfill({
      response,
      json: body,
      headers: {
        ...response.headers(),
        "x-jolt-sim-activity-next-cursor": "40"
      }
    });
  });

  await page.getByTestId("activity-next").click();
  await expect(page.getByTestId("activity-page-status")).toContainText(
    "Activity page 32 is too-large"
  );
  await expect(page.getByTestId("activity-next")).toBeEnabled();
  await page.getByTestId("activity-next").click();
  await expect(page.getByTestId("activity-page-status")).toHaveText(
    "No events at cursor 40; 40 accepted."
  );
  await expect(page.getByTestId("activity-next")).toBeDisabled();
});

test("discards a delayed page after the selected document is reset", async ({page}) => {
  await loadAndReplay(page);
  let releaseRoute;
  const release = new Promise((resolve) => { releaseRoute = resolve; });
  let requestObserved;
  const observed = new Promise((resolve) => { requestObserved = resolve; });
  await page.route("**/api/replay-progress", async (route) => {
    const cursor = await route.request().headerValue(
      "X-Jolt-Sim-Activity-Cursor");
    if (cursor !== "32") {
      await route.continue();
      return;
    }
    requestObserved();
    await release;
    const response = await route.fetch();
    await route.fulfill({response});
  });

  const delayedRequestFinished = page.waitForEvent("requestfinished", {
    predicate: (request) =>
      new URL(request.url()).pathname === "/api/replay-progress" &&
      request.headers()["x-jolt-sim-activity-cursor"] === "32"
  });
  await page.getByTestId("activity-next").click();
  await observed;
  await page.locator("#case-file").setInputFiles([]);
  await expect(page.locator("#activity-panel")).toBeHidden();
  releaseRoute();
  await delayedRequestFinished;
  // requestfinished proves the delayed body arrived. Two animation frames
  // give the fetch/json promise continuation and resulting DOM task a
  // deterministic chance to run without relying on an arbitrary wall delay.
  await page.evaluate(() => new Promise((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(page.locator("#activity-panel")).toBeHidden();
  await expect(page.locator("#activity")).toHaveText("Idle.");
});
