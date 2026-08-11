import {expect, test} from "@playwright/test";

const capabilityToken = "ripple-browser-test-capability-0123456789abcdef";

const effect = (status, {closed = false, uncertainSequence = null} = {}) => ({
  status,
  closed,
  workerOwnership: "borrowed",
  stepEnabled: status === "ready" && !closed,
  reconcileEnabled: status === "uncertain" && !closed,
  closeEnabled: !closed,
  uncertainSequence
});

const frame = (status, revision, cursor, choices, uncertainSequence = null) => {
  const coordinate = effect(status, {uncertainSequence});
  return {
    version: 2,
    revision: String(revision),
    nextCursor: String(cursor),
    stepEnabled: coordinate.stepEnabled,
    frameEdn: `{:revision ${revision} :status :running}`,
    choices,
    effect: coordinate,
    effectEdn: `{:status :${status} :worker {:ownership :borrowed}}`
  };
};

test("chooses a flow branch and reconciles uncertainty without resending", async ({page}, testInfo) => {
  const choice = {revision: "0", kind: "run", value: "0", label: "run 0"};
  let frameCalls = 0;
  let stepCalls = 0;
  let reconcileCalls = 0;

  await page.route("**/api/session-frame", async (route) => {
    frameCalls += 1;
    expect(route.request().method()).toBe("GET");
    const body = frameCalls === 1
      ? frame("ready", 0, 0, [choice])
      : frame("uncertain", 1, 1, [], "0");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body)
    });
  });

  await page.route("**/api/session-step", async (route) => {
    stepCalls += 1;
    expect(route.request().postDataJSON()).toEqual({
      version: 1,
      cursor: "0",
      branch: {revision: "0", kind: "run", value: "0"}
    });
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 2,
        outcome: "committed",
        committed: true,
        revision: "0",
        kind: "run",
        value: "0",
        receiptEdn: "{:committed? true :revision 1}",
        effect: effect("uncertain", {uncertainSequence: "0"}),
        effectEdn: "{:status :uncertain :effects {:pending {:sequence 0}}}",
        truncated: false
      })
    });
  });

  await page.route("**/api/session-effect-reconcile", async (route) => {
    reconcileCalls += 1;
    expect(route.request().postDataJSON()).toEqual({version: 1});
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 2,
        operation: "effect-reconcile",
        outcome: "settled",
        flowCommitted: true,
        effect: effect("ready"),
        effectEdn: "{:status :ready :effects {:pending nil}}",
        truncated: false
      })
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.locator("#session-refresh").click();
  const branch = page.locator("#session-choices button");
  await expect(branch).toHaveCount(1);
  await expect(branch).toContainText("run 0");

  await branch.click();
  await expect(page.locator("#session-step-status"))
    .toContainText("the flow committed, but effect sequence 0 is uncertain");
  await expect(page.locator("#session-effect-reconcile")).toBeEnabled();
  await expect(page.locator("#session-choices button")).toHaveCount(0);
  expect(stepCalls).toBe(1);

  await page.locator("#session-effect-reconcile").click();
  await expect(page.locator("#session-step-status"))
    .toContainText("Effect reconciliation completed with outcome settled");
  await expect(page.locator("#session-effect"))
    .toContainText(":status :ready");
  expect(reconcileCalls).toBe(1);
  expect(stepCalls).toBe(1);

  await page.screenshot({
    path: testInfo.outputPath("ripple-flow-effect-reconciled.png"),
    fullPage: true
  });
});
