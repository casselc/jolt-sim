import {expect, test} from "@playwright/test";
import path from "node:path";
import {fileURLToPath} from "node:url";

const testDir = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(testDir, "fixtures", "activity-case-outcome.edn");
const capabilityToken = "ripple-browser-test-capability-0123456789abcdef";
const semanticKind = "jolt.sim.kind/browser-test-activity";

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
  await page.locator("#load-run-presets").click();

  await expect(page.locator("#run-preset")).toHaveValue(
    "jolt.sim.preset/outbox-cancel-before-ack-v1"
  );
  await expect(page.getByTestId("run-preset-status")).toContainText(
    "is ready. Run new uses server-owned coordinates."
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
  expect(JSON.parse(runBody)).toEqual({
    version: 1,
    presetId: "jolt.sim.preset/outbox-cancel-before-ack-v1"
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

test("distinguishes the two trusted presets by selection", async ({page}, testInfo) => {
  const outboxId = "jolt.sim.preset/outbox-cancel-before-ack-v1";
  const echoId = "jolt.sim.preset/maelstrom-echo-roundtrip-v1";
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

  // The closed catalog offers exactly the two trusted examples as distinct
  // selectable identities; the existing outbox preset remains the default.
  const options = await page.locator("#run-preset option").evaluateAll(
    (items) => items.map((item) => ({value: item.value, text: item.textContent}))
  );
  expect(options).toEqual([
    {value: outboxId, text: "Outbox: cancel before acknowledgment (hermetic)"},
    {value: echoId, text: `${echoLabel} (hermetic)`}
  ]);
  await expect(page.locator("#run-preset")).toHaveValue(outboxId);
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(4);
  await expect(page.frameLocator("#report").locator(".topology-edge"))
    .toHaveCount(3);

  // Selecting the Echo preset renders its own inert two-endpoint topology.
  await page.locator("#run-preset").selectOption(echoId);
  await expect(page.locator("#run-preset")).toHaveValue(echoId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${echoLabel} is ready. Run new uses server-owned coordinates.`
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
  await page.locator("#run-preset").selectOption(outboxId);
  await expect(page.frameLocator("#report").locator(".topology-node"))
    .toHaveCount(4);
  await expect(page.frameLocator("#report").locator("body"))
    .toContainText("cancel-before-ack-v1");

  // Running the selected Echo preset posts its exact trusted preset ID.
  await page.locator("#run-preset").selectOption(echoId);
  await expect(page.getByTestId("run-preset-status")).toContainText(
    `${echoLabel} is ready.`
  );
  await page.getByTestId("run-new").click();
  await observed;
  await expect(page.getByTestId("run-new")).toBeDisabled();
  expect(JSON.parse(runBody)).toEqual({version: 1, presetId: echoId});
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

test("reports an empty trusted preset catalog without inventing an error", async ({page}) => {
  await page.route("**/api/run-presets", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({version: 1, presets: []})
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
    "Outbox: cancel before acknowledgment did not complete; retained evidence remains available."
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
