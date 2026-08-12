import {expect, test} from "@playwright/test";

const capabilityToken = "broadcast-browser-test-token-000000001";

const sendRetained = async (page, command, outcome = "completed") => {
  await page.getByTestId("retained-command").fill(command);
  await page.getByTestId("retained-send").click();
  if (outcome === "failed") {
    await expect(page.getByTestId("retained-status"))
      .toContainText("Application failure was acknowledged definitively");
  } else {
    await expect(page.getByTestId("retained-status"))
      .toContainText(`acknowledged with outcome ${outcome}`);
  }
  return page.getByTestId("retained-receipt").textContent();
};

const clickTopologyAction = async (page, kind, entityId, actionId) => {
  const details = page.locator(`[data-${kind}-detail-id="${entityId}"]`);
  await details.evaluate((element) => { element.open = true; });
  const action = details.locator(`[data-action-id="${actionId}"]`);
  await expect(action).toBeEnabled();
  await action.click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("acknowledged with outcome completed");
  return page.getByTestId("retained-receipt").textContent();
};

const readyNodeIds = (page) => page.locator("[data-node-detail-id]").evaluateAll(
  (details) => details
    .filter((detail) =>
      detail.querySelector(":scope > summary")?.textContent
        .includes("jolt.sim.status/ready"))
    .map((detail) => detail.dataset.nodeDetailId)
);

const drainReadyMailboxes = async (page, maximum = 32) => {
  let steps = 0;
  let lastReceipt = null;
  while (steps < maximum) {
    const ready = await readyNodeIds(page);
    if (ready.length === 0) return {steps, lastReceipt};
    lastReceipt = await clickTopologyAction(page, "node", ready[0], "step");
    steps += 1;
  }
  throw new Error(`Ripple Broadcast drain exceeded ${maximum} visible steps`);
};

const selectCommandCell = async (page, displayId) => {
  const option = page.getByTestId("command-cell-select")
    .locator("option").filter({hasText: displayId});
  await expect(option).toHaveCount(1);
  const handle = await option.getAttribute("value");
  expect(handle).not.toBeNull();
  await page.getByTestId("command-cell-select").selectOption(handle);
};

const openCommandCellResult = async (page) => {
  await page.getByTestId("command-cell-result").evaluate(
    (result) => { result.closest("details").open = true; }
  );
};

test("controls the real retained Broadcast worker from Ripple and its shared REPL",
  async ({page}, testInfo) => {
    // This acceptance intentionally installs no page.route mocks. Every browser
    // request reaches the real workbench, retained supervisor, and child.
    await page.goto("/");
    await page.locator("#capability").fill(capabilityToken);

    await page.getByTestId("retained-refresh").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText("Worker is ready; next command sequence is 0.");

    const initial = await sendRetained(page, "{:op :inspect}");
    expect(initial).toContain(":sequence 0");
    expect(initial).toContain(":status :created");
    expect(initial).toContain(":ready-mailboxes []");
    await expect(page.getByTestId("retained-presentation")).toBeVisible();
    await expect(page.locator('[data-node-id]')).toHaveCount(3);
    await expect(page.locator('[data-edge-id]')).toHaveCount(2);
    await expect(page.locator('[data-edge-id="n2--n3"] title'))
      .toHaveText("jolt.sim.status/connected");
    await expect(page.locator('[data-node-detail-id="n2"]'))
      .toContainText("Mailbox count");
    await expect(page.locator(
      '[data-node-detail-id="n2"] [data-field-label="Messages"] + dd'
    )).toHaveText("0");
    await expect(page.locator(
      '[data-edge-detail-id="n2--n3"] ' +
      '[data-field-label="Dropped envelopes"] + dd'
    )).toHaveText("0");

    await expect(page.locator(
      '[data-edge-detail-id="n2--n3"] [data-action-id="drop"]'
    )).toBeDisabled();

    await page.getByTestId("command-cell-load").click();
    await expect(page.getByTestId("command-cell-select").locator("option"))
      .toHaveCount(8);
    await expect(page.getByTestId("command-cell-status"))
      .toContainText("revision 0");
    await selectCommandCell(page, "example.broadcast/bootstrap");
    await page.getByTestId("command-cell-input").fill("{:op :bootstrap}");
    await page.getByTestId("command-cell-prepare").click();
    await expect(page.getByTestId("command-cell-status"))
      .toContainText("prepare completed with outcome prepared");
    await expect(page.getByTestId("command-cell-choice")).toHaveCount(1);

    // Catalog reads, preparation, and successor preview are pure with respect
    // to the retained worker. The initial inspect consumed sequence 0; no
    // command-cell operation has consumed sequence 1 yet.
    await page.getByTestId("retained-refresh").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText("Worker is ready; next command sequence is 1.");
    await openCommandCellResult(page);
    await page.locator("#command-cell-panel").screenshot({
      path: testInfo.outputPath("ripple-real-broadcast-command-cell-prepared.png")
    });

    await page.getByTestId("command-cell-choice").click();
    await expect(page.getByTestId("command-cell-status"))
      .toContainText("step committed with outcome ready");
    await expect(page.getByTestId("command-cell-result"))
      .toContainText("bootstrap");
    await expect(page.getByTestId("command-cell-result"))
      .toContainText("running");
    await expect(page.getByTestId("command-cell-choice")).toHaveCount(0);

    await page.getByTestId("retained-refresh").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText("Worker is ready; next command sequence is 2.");

    // Command Cell and retained-value presentation are independent generic
    // surfaces. Read one explicit application snapshot to refresh the existing
    // topology view; this is observation after the definite bootstrap, not a
    // retry or a second bootstrap.
    const afterBootstrap = await sendRetained(page, "{:op :inspect}");
    expect(afterBootstrap).toContain(":sequence 2");
    expect(afterBootstrap).toContain(":status :running");
    expect(afterBootstrap)
      .toContain(":ready-mailboxes [\"n1\" \"n2\" \"n3\"]");
    await expect(page.locator('[data-node-id="n1"]'))
      .toContainText("jolt.sim.status/ready");
    await expect(page.locator(
      '[data-node-detail-id="n2"] [data-field-label="Mailbox count"] + dd'
    )).not.toHaveText("0");
    await openCommandCellResult(page);
    await page.locator("#command-cell-panel").screenshot({
      path: testInfo.outputPath("ripple-real-broadcast-command-cell-running.png")
    });

    await page.getByTestId("workbench-refresh").click();
    await expect(page.locator("article.workbench-item")).toHaveCount(3);
    const commandEvidence = page.locator("article.workbench-item")
      .filter({hasText: "command-cell/broadcast-live-"});
    await expect(commandEvidence).toHaveCount(3);
    await expect(commandEvidence.filter({hasText: "/prepare"})).toHaveCount(1);
    await expect(commandEvidence.filter({hasText: "/commit"})).toHaveCount(1);
    await expect(commandEvidence.filter({hasText: "/projected-receipt"}))
      .toHaveCount(1);

    const dropped = await clickTopologyAction(
      page, "edge", "n2--n3", "drop"
    );
    expect(dropped).toContain(":sequence 3");
    expect(dropped).toContain(":operation :set-connection-regime");
    expect(dropped).toContain(":regime-revision 1");
    await expect(page.locator('[data-edge-id="n2--n3"] title'))
      .toHaveText("jolt.sim.status/partitioned");
    await expect(page.locator('[data-edge-id="n1--n2"] title'))
      .toHaveText("jolt.sim.status/connected");

    // Replay the application revision carried by the old rendered action.
    // This is a definite application rejection, not transport uncertainty.
    const stale = await sendRetained(
      page,
      "{:op :set-connection-regime :connection [\"n2\" \"n3\"] " +
        ":expected-revision 0 :regime :drop}",
      "failed"
    );
    expect(stale).toContain(":sequence 4");
    expect(stale).toContain(":status :failed");

    const afterStale = await sendRetained(page, "{:op :inspect}");
    expect(afterStale).toContain(":sequence 5");
    expect(afterStale).toContain(":regime-revision 1");
    expect(afterStale).toContain("[\"n2\" \"n3\"] :drop");

    await expect(page.locator(
      '[data-node-detail-id="n1"] [data-field-label="Mailbox count"] + dd'
    )).not.toHaveText("0");

    await page.getByTestId("eval-form").fill(
      "(do " +
      "(require '[maelstrom-broadcast-workbench.main :as wb] :reload) " +
      "(require '[jolt.sim.flow-effect-session :as effect] :reload) " +
      "(let [session (wb/command-session {:op :step :node-id \"n2\"}) " +
      "branch (:branch (first (effect/branches session)))] " +
      "(effect/step! session branch)))"
    );
    await page.getByTestId("eval-submit").click();
    await expect(page.getByTestId("eval-status"))
      .toContainText("completed in user");
    await expect(page.locator('[aria-label="Evaluation exception"]')).toHaveCount(0);

    await page.getByTestId("retained-refresh").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText("next command sequence is 7");

    const afterReplStep = await sendRetained(page, "{:op :inspect}");
    expect(afterReplStep).toContain(":sequence 7");
    expect(afterReplStep).toContain(":step-count 1");
    expect(afterReplStep).toContain(":last-step");
    expect(afterReplStep).toContain(":node-id \"n2\"");
    await expect(page.locator('[data-node-id="n2"]'))
      .toContainText("jolt.sim.status/ready");

    const {steps: partitionSteps, lastReceipt: partitioned}
      = await drainReadyMailboxes(page);
    expect(partitionSteps).toBeGreaterThan(0);
    expect(partitioned).not.toBeNull();
    expect(partitioned).toContain('"n1" {:messages [42]');
    expect(partitioned).toContain('"n2" {:messages [42]');
    expect(partitioned).toContain('"n3" {:messages []');
    expect(partitioned).toContain(":dropped-total 1");
    // Keep the application evidence visible in the documentation capture.
    // The graph alone shows topology/status but not the per-node message
    // counts that distinguish the partitioned state.
    await Promise.all(["n1", "n2", "n3"].map((nodeId) =>
      page.locator(`[data-node-detail-id="${nodeId}"]`)
        .evaluate((element) => { element.open = true; })
    ));
    const partitionedEdge = page.locator('[data-edge-detail-id="n2--n3"]');
    await partitionedEdge.evaluate((element) => { element.open = true; });
    const topologyGraph = page.locator("svg.retained-topology");
    const topologyDetails = page.locator(".retained-topology-details");
    const [graphBox, detailsBox] = await Promise.all([
      topologyGraph.boundingBox(), topologyDetails.boundingBox()
    ]);
    expect(graphBox).not.toBeNull();
    expect(detailsBox).not.toBeNull();
    const topologyX = Math.min(graphBox.x, detailsBox.x);
    const topologyRight = Math.max(
      graphBox.x + graphBox.width, detailsBox.x + detailsBox.width
    );
    await page.screenshot({
      path: testInfo.outputPath("ripple-real-broadcast-partition.png"),
      clip: {
        x: topologyX,
        y: graphBox.y,
        width: topologyRight - topologyX,
        height: detailsBox.y + detailsBox.height - graphBox.y
      }
    });
    await partitionedEdge.screenshot({
      path: testInfo.outputPath("ripple-real-broadcast-edge-actions.png")
    });

    const restored = await clickTopologyAction(
      page, "edge", "n2--n3", "restore"
    );
    expect(restored).toContain(":operation :set-connection-regime");
    expect(restored).toContain(":regime-revision 2");
    await expect(page.locator('[data-edge-id="n2--n3"] title'))
      .toHaveText("jolt.sim.status/connected");
    expect(await readyNodeIds(page)).toEqual([]);

    const retried = await sendRetained(page, "{:op :retry}");
    expect(retried).toContain(":operation :retry");
    const {steps: retrySteps, lastReceipt: converged}
      = await drainReadyMailboxes(page);
    expect(retrySteps).toBeGreaterThan(0);
    expect(converged).not.toBeNull();
    expect(converged).toContain('"n1" {:messages [42]');
    expect(converged).toContain('"n2" {:messages [42]');
    expect(converged).toContain('"n3" {:messages [42]');
    await page.locator("svg.retained-topology").screenshot({
      path: testInfo.outputPath("ripple-real-broadcast-converged.png")
    });

    await sendRetained(page, "{:op :read}");
    const readStep = await clickTopologyAction(page, "node", "n3", "step");
    expect(readStep).toContain(":messages [42]");

    const stopped = await sendRetained(page, "{:op :stop}");
    expect(stopped).toContain(":owner? true");
    expect(stopped).toContain(":status :stopped");
    await expect(async () => {
      await Promise.all([
        page.waitForResponse((response) =>
          response.url().endsWith("/api/retained-frame")),
        page.getByTestId("retained-refresh").click()
      ]);
      await expect(page.getByTestId("retained-status"))
        .toContainText("Worker is exited", {timeout: 1000});
    }).toPass({timeout: 20000});
    await expect(page.getByTestId("retained-send")).toBeDisabled();

  });
