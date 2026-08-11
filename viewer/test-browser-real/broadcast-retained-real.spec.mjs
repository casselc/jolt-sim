import {expect, test} from "@playwright/test";

const capabilityToken = "broadcast-browser-test-token-000000001";

const sendRetained = async (page, command) => {
  await page.getByTestId("retained-command").fill(command);
  await page.getByTestId("retained-send").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("acknowledged with outcome completed");
  return page.getByTestId("retained-receipt").textContent();
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
      .toHaveText("jolt.sim.status/partitioned");
    await expect(page.locator('[data-node-detail-id="n2"]'))
      .toContainText("Mailbox count");
    await expect(page.locator(
      '[data-node-detail-id="n2"] [data-field-label="Messages"] + dd'
    )).toHaveText("0");
    await expect(page.locator(
      '[data-edge-detail-id="n2--n3"] ' +
      '[data-field-label="Dropped envelopes"] + dd'
    )).toHaveText("0");

    const bootstrapped = await sendRetained(page, "{:op :bootstrap}");
    expect(bootstrapped).toContain(":sequence 1");
    expect(bootstrapped).toContain(":enqueued 7");
    expect(bootstrapped).toContain(":ready-mailboxes [\"n1\" \"n2\" \"n3\"]");
    await expect(page.locator('[data-node-id="n1"]'))
      .toContainText("jolt.sim.status/ready");
    await expect(page.locator(
      '[data-node-detail-id="n1"] [data-field-label="Mailbox count"] + dd'
    )).not.toHaveText("0");

    await page.getByTestId("eval-form").fill(
      "(do (require '[maelstrom-broadcast-workbench.main :as wb] :reload) " +
      "(wb/step! \"n2\"))"
    );
    await page.getByTestId("eval-submit").click();
    await expect(page.getByTestId("eval-status"))
      .toContainText("completed in user");
    await expect(page.locator('[aria-label="Evaluation exception"]')).toHaveCount(0);

    await page.getByTestId("retained-refresh").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText("next command sequence is 3");

    const afterReplStep = await sendRetained(page, "{:op :inspect}");
    expect(afterReplStep).toContain(":sequence 3");
    expect(afterReplStep).toContain(":step-count 1");
    expect(afterReplStep).toContain(":last-step");
    expect(afterReplStep).toContain(":node-id \"n2\"");
    await expect(page.locator('[data-node-id="n2"]'))
      .toContainText("jolt.sim.status/ready");

    const healed = await sendRetained(page, "{:op :heal}");
    expect(healed).toContain(":sequence 4");
    expect(healed).toContain(":operation :heal");
    await expect(page.locator('[data-edge-id="n2--n3"] title'))
      .toHaveText("jolt.sim.status/healed");

    const stopped = await sendRetained(page, "{:op :stop}");
    expect(stopped).toContain(":sequence 5");
    expect(stopped).toContain(":owner? true");
    expect(stopped).toContain(":status :stopped");
    await expect.poll(async () => {
      await page.getByTestId("retained-refresh").click();
      return page.getByTestId("retained-status").textContent();
    }).toContain("Worker is exited");
    await expect(page.getByTestId("retained-send")).toBeDisabled();

    await page.screenshot({
      path: testInfo.outputPath("ripple-real-broadcast-shared-repl.png"),
      fullPage: true
    });
  });
