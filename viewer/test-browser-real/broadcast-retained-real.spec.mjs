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
  while (steps < maximum) {
    const ready = await readyNodeIds(page);
    if (ready.length === 0) return steps;
    await clickTopologyAction(page, "node", ready[0], "step");
    steps += 1;
  }
  throw new Error(`Ripple Broadcast drain exceeded ${maximum} visible steps`);
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

    const bootstrapped = await sendRetained(page, "{:op :bootstrap}");
    expect(bootstrapped).toContain(":sequence 1");
    expect(bootstrapped).toContain(":enqueued 7");
    expect(bootstrapped).toContain(":ready-mailboxes [\"n1\" \"n2\" \"n3\"]");
    await expect(page.locator('[data-node-id="n1"]'))
      .toContainText("jolt.sim.status/ready");

    const dropped = await clickTopologyAction(
      page, "edge", "n2--n3", "drop"
    );
    expect(dropped).toContain(":sequence 2");
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
    expect(stale).toContain(":sequence 3");
    expect(stale).toContain(":status :failed");

    const afterStale = await sendRetained(page, "{:op :inspect}");
    expect(afterStale).toContain(":sequence 4");
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
      .toContainText("next command sequence is 6");

    const afterReplStep = await sendRetained(page, "{:op :inspect}");
    expect(afterReplStep).toContain(":sequence 6");
    expect(afterReplStep).toContain(":step-count 1");
    expect(afterReplStep).toContain(":last-step");
    expect(afterReplStep).toContain(":node-id \"n2\"");
    await expect(page.locator('[data-node-id="n2"]'))
      .toContainText("jolt.sim.status/ready");

    const partitionSteps = await drainReadyMailboxes(page);
    expect(partitionSteps).toBeGreaterThan(0);
    const partitioned = await sendRetained(page, "{:op :inspect}");
    expect(partitioned).toContain('"n1" {:messages [42]');
    expect(partitioned).toContain('"n2" {:messages [42]');
    expect(partitioned).toContain('"n3" {:messages []');
    expect(partitioned).toContain(":dropped-total 1");
    await page.locator("svg.retained-topology").screenshot({
      path: testInfo.outputPath("ripple-real-broadcast-partition.png")
    });
    const partitionedEdge = page.locator('[data-edge-detail-id="n2--n3"]');
    await partitionedEdge.evaluate((element) => { element.open = true; });
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
    const retrySteps = await drainReadyMailboxes(page);
    expect(retrySteps).toBeGreaterThan(0);
    const converged = await sendRetained(page, "{:op :inspect}");
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
