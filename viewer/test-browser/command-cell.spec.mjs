import {test, expect} from "@playwright/test";

const capability = "browser-test-capability";
const cell = {
  handle: "0",
  id: "example.cell/command",
  effectKind: "example.effect/command",
  suggestedKind: "example.kind/result",
  descriptorEdn: "[:jolt.sim.value/map []]"
};
const choice = (id, branchEdn = "{:revision 0 :action [:run 0]}", revision = "1") => ({
  id: String(id), revision, branchEdn,
  previewEdn: `[:jolt.sim.value/string "preview-${id}"]`
});
const catalog = (extra = {}) => ({
  version: 1,
  evidenceStreamId: "browser-command-cell",
  cellCount: "1",
  cells: [cell],
  catalogEdn: "[:jolt.sim.value/map []]",
  ...extra
});
const frame = ({revision = "0", phase = null, choices = [], closed = false,
  evidenceStreamId = "browser-command-cell"} = {}) => ({
  version: 1,
  evidenceStreamId,
  revision,
  closed,
  activeCellDisplayId: phase === null ? null : cell.id,
  phase,
  branchCount: String(choices.length),
  choices,
  frameEdn: `[:jolt.sim.value/string "frame-${revision}"]`
});
const operation = (name, outcome, revision, {
  committed = ["step", "reconcile"].includes(name), choices = [],
  frameStatus = "available"
} = {}) => ({
  version: 1,
  operation: name,
  outcome,
  committed,
  evidenceStreamId: "browser-command-cell",
  revision,
  cellDisplayId: name === "close" ? null : cell.id,
  resultEdn: `[:jolt.sim.value/string "${name}-${outcome}"]`,
  frameStatus,
  frameEdn: frameStatus === "available"
    ? `[:jolt.sim.value/string "operation-frame-${revision}"]` : null,
  frameErrorEdn: null,
  branchCount: String(choices.length),
  choices,
  truncated: false
});
const coordinateAck = (name, outcome, revision, committed) => ({
  version: 1, operation: name, outcome, committed, revision,
  frameStatus: "unavailable", branchCount: "0", choices: [], truncated: true
});
const json = (route, body, status = 200) => route.fulfill({
  status, contentType: "application/json", body: JSON.stringify(body)
});

async function installCatalog(page, body = catalog()) {
  await page.route("**/api/command-cell-catalog", (route) => json(route, body));
}

async function openCells(page) {
  await page.goto("/");
  await page.getByLabel("Session capability").fill(capability);
  await page.getByTestId("command-cell-load").click();
}

test("prepares ordinary EDN and commits one exact server-issued branch", async ({page}, testInfo) => {
  await installCatalog(page);
  let frameRead = 0;
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    return json(route, frameRead === 1
      ? frame()
      : frameRead === 2
        ? frame({revision: "1", phase: "prepared", choices: [choice(0)]})
        : frame({revision: "2", phase: "definite"}));
  });
  let stepCalls = 0;
  await page.route("**/api/command-cell-prepare", async (route) => {
    expect(route.request().postDataJSON()).toEqual({
      version: 1, evidenceStreamId: "browser-command-cell",
      revision: "0", cellHandle: cell.handle,
      inputEdn: "{:op :inspect}"
    });
    return json(route, operation("prepare", "prepared", "1", {
      committed: false, choices: [choice(0)]
    }));
  });
  await page.route("**/api/command-cell-step", async (route) => {
    stepCalls += 1;
    expect(route.request().postDataJSON()).toEqual({
      version: 1, evidenceStreamId: "browser-command-cell",
      revision: "1", branchEdn: choice(0).branchEdn
    });
    return json(route, operation("step", "ready", "2"));
  });

  await openCells(page);
  await page.getByTestId("command-cell-input").fill("{:op :inspect}");
  await page.getByTestId("command-cell-prepare").click();
  await expect(page.getByTestId("command-cell-choice")).toHaveCount(1);
  await page.getByTestId("command-cell-choice").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("step committed with outcome ready");
  await expect(page.getByTestId("command-cell-choice")).toHaveCount(0);
  expect(stepCalls).toBe(1);
  await page.screenshot({
    path: testInfo.outputPath("ripple-generic-command-cell.png"), fullPage: true
  });
});

test("treats stale rejection as definite and requires a fresh coordinate", async ({page}) => {
  await installCatalog(page);
  let frameRead = 0;
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    return json(route, frameRead === 1
      ? frame({revision: "1", phase: "prepared", choices: [choice(0)]})
      : frame({revision: "2", phase: "definite"}));
  });
  await page.route("**/api/command-cell-step", (route) => json(route, {
    version: 1, outcome: "error", committed: false,
    error: "command-cell-rejected"
  }, 409));

  await openCells(page);
  await page.getByTestId("command-cell-choice").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("definitely rejected before commit");
  await expect(page.getByTestId("command-cell-choice")).toHaveCount(0);
  await page.getByTestId("command-cell-refresh").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("revision 2");
});

test("reconciles an uncertain commit without resending the step", async ({page}) => {
  await installCatalog(page);
  let frameRead = 0;
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    if (frameRead === 1) return json(route,
      frame({revision: "1", phase: "prepared", choices: [choice(0)]}));
    if (frameRead === 2) return json(route,
      frame({revision: "2", phase: "uncertain"}));
    return json(route, frame({revision: "2", phase: "definite"}));
  });
  let stepCalls = 0;
  let reconcileCalls = 0;
  await page.route("**/api/command-cell-step", (route) => {
    stepCalls += 1;
    return json(route, operation("step", "uncertain", "2"));
  });
  await page.route("**/api/command-cell-reconcile", (route) => {
    reconcileCalls += 1;
    expect(route.request().postDataJSON()).toEqual({
      version: 1, evidenceStreamId: "browser-command-cell"
    });
    return json(route, operation("reconcile", "ready", "2"));
  });

  await openCells(page);
  await page.getByTestId("command-cell-choice").click();
  await expect(page.getByTestId("command-cell-reconcile")).toBeEnabled();
  await expect(page.getByTestId("command-cell-prepare")).toBeDisabled();
  await page.getByTestId("command-cell-reconcile").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("reconcile committed with outcome ready");
  expect(stepCalls).toBe(1);
  expect(reconcileCalls).toBe(1);
});

test("invalidates a detached branch after an out-of-band revision advance", async ({page}) => {
  await installCatalog(page);
  const first = choice(0, "{:revision 0 :action [:run 0]}");
  const second = choice(0, "{:revision 1 :action [:run 1]}", "2");
  let frameRead = 0;
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    return json(route, frameRead === 1
      ? frame({revision: "1", phase: "prepared", choices: [first]})
      : frame({revision: "2", phase: "prepared", choices: [second]}));
  });
  let stepCalls = 0;
  await page.route("**/api/command-cell-step", (route) => {
    stepCalls += 1;
    return json(route, operation("step", "ready", "3"));
  });

  await openCells(page);
  const stale = await page.getByTestId("command-cell-choice").elementHandle();
  await page.getByTestId("command-cell-refresh").click();
  await stale.evaluate((element) => element.click());
  expect(stepCalls).toBe(0);
  await page.getByTestId("command-cell-choice").click();
  expect(stepCalls).toBe(1);
});

test("passes lossless canonical byte input through unchanged", async ({page}) => {
  await installCatalog(page);
  let frameRead = 0;
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    return json(route, frameRead === 1 ? frame() :
      frame({revision: "1", phase: "prepared"}));
  });
  const bytes = "[:jolt.sim.value/map [[[:jolt.sim.value/keyword nil \"payload\"] [:jolt.sim.value/bytes [0 255]]]]]";
  let submitted = null;
  await page.route("**/api/command-cell-prepare", async (route) => {
    submitted = route.request().postDataJSON();
    return json(route, operation("prepare", "prepared", "1", {committed: false}));
  });

  await openCells(page);
  await page.getByTestId("command-cell-input-mode").selectOption("canonical-value");
  await page.getByTestId("command-cell-input").fill(bytes);
  await page.getByTestId("command-cell-prepare").click();
  expect(submitted).toEqual({
    version: 1, evidenceStreamId: "browser-command-cell",
    revision: "0", cellHandle: cell.handle, inputCanonicalEdn: bytes
  });
  expect(Object.hasOwn(submitted, "inputEdn")).toBe(false);
});

test("closes command-cell admission without exposing another mutation", async ({page}) => {
  await installCatalog(page);
  await page.route("**/api/command-cell-frame", (route) => json(route, frame()));
  await page.route("**/api/command-cell-close", (route) =>
    json(route, coordinateAck("close", "closed", "1", false)));

  await openCells(page);
  await page.getByTestId("command-cell-close").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("borrowed worker was not terminated");
  await expect(page.getByTestId("command-cell-close")).toBeDisabled();
  await expect(page.getByTestId("command-cell-prepare")).toBeDisabled();
});

test("reports an unavailable capability and rejects unknown catalog wire", async ({page}) => {
  await page.route("**/api/command-cell-catalog", (route) => json(route, {
    version: 1, outcome: "error", error: "command-cell-unavailable"
  }, 404));
  await openCells(page);
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("404 malformed or unavailable command-cell catalog");
  await expect(page.getByTestId("command-cell-select")).toBeDisabled();

  await page.unroute("**/api/command-cell-catalog");
  await installCatalog(page, catalog({unexpected: true}));
  await page.getByTestId("command-cell-load").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("malformed or unavailable command-cell catalog");
});

test("keeps a definite commit distinct when its authoritative refresh fails", async ({page}) => {
  await installCatalog(page);
  let frameRead = 0;
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    if (frameRead === 1) return json(route,
      frame({revision: "1", phase: "prepared", choices: [choice(0)]}));
    return json(route, {version: 1, error: "read-failed"}, 500);
  });
  await page.route("**/api/command-cell-step", (route) =>
    json(route, operation("step", "ready", "2", {frameStatus: "unavailable"})));

  await openCells(page);
  await page.getByTestId("command-cell-choice").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("result is definite and must not be retried");
  await expect(page.getByTestId("command-cell-result"))
    .toContainText('"committed": true');
});

test("blocks resend after an ambiguous mutation response until refresh", async ({page}) => {
  await installCatalog(page);
  let frameRead = 0;
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    return json(route, frameRead === 1
      ? frame({revision: "1", phase: "prepared", choices: [choice(0)]})
      : frame({revision: "2", phase: "uncertain"}));
  });
  let stepCalls = 0;
  await page.route("**/api/command-cell-step", (route) => {
    stepCalls += 1;
    return json(route, {version: 1, error: "transport-lost"}, 500);
  });

  await openCells(page);
  await page.getByTestId("command-cell-choice").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("outcome is unknown");
  await expect(page.getByTestId("command-cell-prepare")).toBeDisabled();
  await expect(page.getByTestId("command-cell-choice")).toHaveCount(0);
  expect(stepCalls).toBe(1);
  await page.getByTestId("command-cell-refresh").click();
  await expect(page.getByTestId("command-cell-reconcile")).toBeEnabled();
  expect(stepCalls).toBe(1);
});

test("rejects coordinates from a different command-cell session epoch", async ({page}) => {
  await installCatalog(page);
  await page.route("**/api/command-cell-frame", (route) => json(route,
    frame({evidenceStreamId: "restarted-command-cell"})));

  await openCells(page);
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("evidence stream changed");
  await expect(page.getByTestId("command-cell-prepare")).toBeDisabled();
  await expect(page.getByTestId("command-cell-close")).toBeDisabled();
});

test("does not post a detached choice into a replacement same-revision epoch", async ({page}) => {
  let catalogRead = 0;
  await page.route("**/api/command-cell-catalog", (route) => {
    catalogRead += 1;
    return json(route, catalogRead === 1 ? catalog() :
      catalog({evidenceStreamId: "replacement-command-cell"}));
  });
  let frameRead = 0;
  const oldChoice = choice(0, "{:revision 0 :action [:run 0]}");
  const newChoice = choice(0, "{:revision 0 :action [:run 1]}");
  await page.route("**/api/command-cell-frame", (route) => {
    frameRead += 1;
    return json(route, frameRead === 1
      ? frame({revision: "1", phase: "prepared", choices: [oldChoice]})
      : frame({revision: "1", phase: "prepared", choices: [newChoice],
          evidenceStreamId: "replacement-command-cell"}));
  });
  let stepCalls = 0;
  await page.route("**/api/command-cell-step", (route) => {
    stepCalls += 1;
    expect(route.request().postDataJSON().evidenceStreamId)
      .toBe("replacement-command-cell");
    return json(route, {
      ...operation("step", "ready", "2"),
      evidenceStreamId: "replacement-command-cell"
    });
  });

  await openCells(page);
  const stale = await page.getByTestId("command-cell-choice").elementHandle();
  await page.getByTestId("command-cell-load").click();
  await stale.evaluate((element) => element.click());
  expect(stepCalls).toBe(0);
  await page.getByTestId("command-cell-choice").click();
  expect(stepCalls).toBe(1);
});

test("rejects unknown phases and overbound command-cell revisions", async ({page}) => {
  await installCatalog(page);
  await page.route("**/api/command-cell-frame", (route) => json(route,
    frame({phase: "application-specific"})));
  await openCells(page);
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("malformed command-cell frame response");
  await expect(page.getByTestId("command-cell-prepare")).toBeDisabled();

  await page.unroute("**/api/command-cell-frame");
  await page.route("**/api/command-cell-frame", (route) => json(route,
    frame({revision: "9223372036854775808"})));
  await page.getByTestId("command-cell-load").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("malformed command-cell frame response");
  await expect(page.getByTestId("command-cell-close")).toBeDisabled();
});
