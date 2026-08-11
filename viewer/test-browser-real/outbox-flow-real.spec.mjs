import {expect, test} from "@playwright/test";

const capabilityToken = "outbox-flow-browser-token-0000000001";

const refreshFlow = async (page) => {
  await page.locator("#session-refresh").click();
  await expect(page.locator("#session-step-status"))
    .toContainText("branch choice");
};

const stopWorker = async (page) => {
  await page.locator("#retained-command").fill("{:op :stop}");
  await page.locator("#retained-send").click();
  await expect(page.locator("#retained-status"))
    .toContainText("acknowledged with outcome completed");
};

test("shares one real Outbox flow between Ripple and its Jolt REPL",
  async ({page}, testInfo) => {
    // No route mocks: browser -> Ripple -> flow/effect bridge -> retained child
    // -> real HTTP, SQLite, TCP, and bencode application boundaries.
    await page.goto("/");
    await page.locator("#capability").fill(capabilityToken);

    await page.locator("#retained-refresh").click();
    await expect(page.locator("#retained-status"))
      .toContainText("Worker is ready; next command sequence is 0.");

    await refreshFlow(page);
    const submitChoice = page.locator("#session-choices button");
    await expect(submitChoice).toHaveCount(1);
    await expect(submitChoice).toContainText("run 0");
    await submitChoice.click();
    await expect(page.locator("#session-step-status"))
      .toContainText("Committed");
    await expect(page.locator("#session-effect"))
      .toContainText(":status :pending");
    await page.getByTestId("workbench-refresh").click();
    const recordedStep = page.locator('article[data-item-id="simulation-step"]');
    await expect(recordedStep).toBeVisible();
    await expect(recordedStep.getByTestId("workbench-presentation"))
      .toContainText("Raw value");
    const sourceBefore = await recordedStep.locator("pre").textContent();
    await recordedStep.getByTestId("workbench-kind")
      .selectOption("example.outbox/effect-result");
    await expect(recordedStep.getByTestId("workbench-presentation"))
      .toContainText("Outbox flow result");
    await expect(recordedStep.getByTestId("workbench-presentation"))
      .toContainText("pending");
    await expect(recordedStep.locator("pre")).toHaveText(sourceBefore);
    await page.screenshot({
      path: testInfo.outputPath("ripple-real-outbox-pending.png"),
      fullPage: true
    });

    await page.locator("#eval-form").fill(
      "(do " +
      "(require '[outbox-workbench.flow-ripple-main :as wb] :reload) " +
      "(require '[jolt.sim.flow-effect-session :as effect] :reload) " +
      "(let [bridge (wb/active-bridge) " +
      "branch (:branch (first (effect/branches bridge)))] " +
      "(effect/step! bridge branch)))"
    );
    await page.locator("#eval-submit").click();
    await expect(page.locator("#eval-status"))
      .toContainText("completed in user");
    await expect(page.locator('[aria-label="Evaluation exception"]'))
      .toHaveCount(0);

    await refreshFlow(page);
    await expect(page.locator("#session-choices button")).toHaveCount(0);
    await expect(page.locator("#session-effect"))
      .toContainText(":status :delivered");
    await expect(page.locator("#session-effect"))
      .toContainText(":receiver-requests {:count 1");
    await page.screenshot({
      path: testInfo.outputPath("ripple-real-outbox-delivered.png"),
      fullPage: true
    });

    // The worker remains independently commandable after the flow completes.
    // Stop it explicitly so the retained journal proves terminal publication,
    // exit zero, and process reaping rather than relying on Playwright cleanup.
    await stopWorker(page);
    await expect(async () => {
      await page.locator("#retained-refresh").click();
      await expect(page.locator("#retained-status"))
        .toContainText("Worker is exited", {timeout: 1000});
    }).toPass({timeout: 20000});
  });
