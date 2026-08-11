import {expect, test} from "@playwright/test";

const capabilityToken = "ripple-browser-test-capability-0123456789abcdef";
const fingerprint = {
  algorithm: "jolt.sim.fingerprint/crc32c-v1",
  bytes: "116",
  crc32c: "314159"
};

const presentation = (kind, summary, label, valueEdn) => ({
  version: 1,
  kind,
  sourceKind: null,
  summary,
  fields: [{label, valueEdn}],
  graph: null,
  sourceEdn: "[1 2 3]"
});

const workbenchItem = (itemId, selectedKind) => ({
  itemId,
  sourceRevision: "0",
  sourceKind: null,
  schemaId: "example/orders",
  sourceFingerprint: fingerprint,
  selection: {
    kind: selectedKind,
    source: selectedKind === "kind/count" ? "exact-override" : "raw",
    ruleId: null
  },
  presentation: selectedKind === "kind/count"
    ? presentation("kind/count", "Counted values", "Count", "3")
    : presentation("jolt.sim.kind/raw-value", "Raw value", "Value", "[1 2 3]"),
  presentationError: null,
  sourceEdn: "[1 2 3]"
});

const frame = (selectedKind, items = [workbenchItem("orders", selectedKind)]) => ({
  version: 1,
  status: "ok",
  revision: selectedKind === "kind/count" ? "2" : "1",
  availableKinds: ["jolt.sim.kind/raw-value", "kind/count"],
  itemCount: String(items.length),
  currentItemCount: String(items.length),
  omittedItemCount: "0",
  journalCount: String(items.length + (selectedKind === "kind/count" ? 1 : 0)),
  items
});

test("changes one source-bound presentation without changing its evidence", async ({page}, testInfo) => {
  let selectedKind = "jolt.sim.kind/raw-value";
  let writes = 0;

  await page.route("**/api/workbench-frame", async (route) => {
    expect(route.request().method()).toBe("GET");
    expect(route.request().headers()["x-jolt-sim-capability"]).toBe(capabilityToken);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(frame(selectedKind))
    });
  });

  await page.route("**/api/workbench-item-kind", async (route) => {
    writes += 1;
    expect(route.request().postDataJSON()).toEqual({
      version: 1,
      itemId: "orders",
      sourceRevision: "0",
      sourceFingerprint: fingerprint,
      kind: "kind/count"
    });
    selectedKind = "kind/count";
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({version: 1, status: "committed", revision: "2"})
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("workbench-refresh").click();

  const item = page.locator('article[data-item-id="orders"]');
  await expect(item).toBeVisible();
  await expect(item.getByTestId("workbench-presentation")).toContainText("Raw value");
  await expect(item.locator("pre")).toContainText("[1 2 3]");

  await item.getByTestId("workbench-kind").selectOption("kind/count");
  await expect(page.getByTestId("workbench-status"))
    .toContainText("Loaded 1 of 1 current items at workbench revision 2");
  await expect(item.getByTestId("workbench-presentation")).toContainText("Counted values");
  await expect(item.getByTestId("workbench-presentation")).toContainText("3");
  await expect(item.locator("pre")).toContainText("[1 2 3]");
  expect(writes).toBe(1);

  await testInfo.attach("generic-workbench-items", {
    body: await page.screenshot({fullPage: true}),
    contentType: "image/png"
  });
});

test("reconciles a lost kind-change acknowledgment without resending", async ({page}) => {
  let selectedKind = "jolt.sim.kind/raw-value";
  let writes = 0;
  let reads = 0;
  await page.route("**/api/workbench-frame", async (route) => {
    reads += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(frame(selectedKind))
    });
  });
  await page.route("**/api/workbench-item-kind", async (route) => {
    writes += 1;
    selectedKind = "kind/count";
    await route.abort("connectionfailed");
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("workbench-refresh").click();
  await page.getByTestId("workbench-kind").selectOption("kind/count");

  await expect(page.getByTestId("workbench-status"))
    .toContainText("outcome was unknown");
  await expect(page.getByTestId("workbench-status"))
    .toContainText("now selects kind/count");
  await expect(page.getByTestId("workbench-presentation"))
    .toContainText("Counted values");
  expect(writes).toBe(1);
  expect(reads).toBe(2);
});

test("preserves a definite commit when its follow-up refresh fails", async ({page}) => {
  let selectedKind = "jolt.sim.kind/raw-value";
  let reads = 0;
  await page.route("**/api/workbench-frame", async (route) => {
    reads += 1;
    if (reads === 1) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(frame(selectedKind))
      });
    } else {
      await route.fulfill({status: 503, body: "unavailable"});
    }
  });
  await page.route("**/api/workbench-item-kind", async (route) => {
    selectedKind = "kind/count";
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({version: 1, status: "committed", revision: "2"})
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("workbench-refresh").click();
  await page.getByTestId("workbench-kind").selectOption("kind/count");

  await expect(page.getByTestId("workbench-status"))
    .toContainText("committed at workbench revision 2");
  await expect(page.getByTestId("workbench-status"))
    .toContainText("The commit is definite");
});

test("accepts the server-supported frame above the former 256-item client cap", async ({page}) => {
  const items = Array.from({length: 257}, (_, index) =>
    workbenchItem(`item-${String(index).padStart(3, "0")}`,
      "jolt.sim.kind/raw-value"));
  await page.route("**/api/workbench-frame", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(frame("jolt.sim.kind/raw-value", items))
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("workbench-refresh").click();
  await expect(page.locator("article.workbench-item")).toHaveCount(257);
  await expect(page.getByTestId("workbench-status"))
    .toContainText("Loaded 257 of 257 current items");
});

test("keeps immutable source visible when a presentation kind is unavailable", async ({page}) => {
  const unavailable = workbenchItem("missing-renderer", "kind/not-installed");
  unavailable.presentation = null;
  unavailable.presentationError = {
    type: "jolt.sim.presentation/invalid-kind-registry",
    reason: "unknown-kind"
  };
  await page.route("**/api/workbench-frame", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(frame("jolt.sim.kind/raw-value", [unavailable]))
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("workbench-refresh").click();
  const item = page.locator('article[data-item-id="missing-renderer"]');
  await expect(item.getByTestId("workbench-presentation"))
    .toContainText("Presentation unavailable: unknown-kind");
  await expect(item.locator("pre")).toContainText("[1 2 3]");
});
