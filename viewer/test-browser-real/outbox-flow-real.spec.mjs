import {expect, test} from "@playwright/test";

const capabilityToken = "outbox-flow-browser-token-0000000001";

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

const expectNextSequence = async (page, sequence) => {
  await page.getByTestId("retained-refresh").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText(`Worker is ready; next command sequence is ${sequence}.`);
};

const prepareExact = async (page, input) => {
  await page.getByTestId("command-cell-input").fill(input);
  await page.getByTestId("command-cell-prepare").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("prepare completed with outcome prepared");
  await expect(page.getByTestId("command-cell-choice")).toHaveCount(1);
};

const commitOnlyChoice = async (page) => {
  await page.getByTestId("command-cell-choice").click();
  await expect(page.getByTestId("command-cell-status"))
    .toContainText("step committed with outcome ready");
  await expect(page.getByTestId("command-cell-choice")).toHaveCount(0);
};

const stopWorker = async (page) => {
  await page.getByTestId("retained-command").fill("{:op :stop}");
  await page.getByTestId("retained-send").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("acknowledged with outcome completed");
};

test("commits real Outbox submit and delivery independently through Command Cells",
  async ({page}, testInfo) => {
    // No route mocks: browser -> generic Command Cell HTTP adapter -> retained
    // child -> the unchanged real HTTP, SQLite, TCP, and bencode app.
    await page.goto("/");
    await page.locator("#capability").fill(capabilityToken);
    await expectNextSequence(page, 0);

    await page.getByTestId("command-cell-load").click();
    await expect(page.getByTestId("command-cell-select").locator("option"))
      .toHaveCount(2);
    await expect(page.getByTestId("command-cell-status"))
      .toContainText("revision 0");

    // Preparation stays on the pure Command Cell side of the boundary: the
    // child has not seen a command until the one issued branch is clicked.
    await selectCommandCell(page, "example.outbox/submit");
    await prepareExact(
      page,
      "{:op :submit :command {:request-id \"req-1\" :entity-id \"entity-a\" " +
        ":payload [0 127 128 255]}}"
    );
    await expectNextSequence(page, 0);

    await commitOnlyChoice(page);
    const submitResult = page.getByTestId("command-cell-result");
    await expect(submitResult).toContainText("http-status");
    await expect(submitResult).toContainText("201");
    await expect(submitResult).toContainText("row-status");
    await expect(submitResult).toContainText("pending");
    await expect(submitResult).toContainText("receiver-requests");
    const submitWire = JSON.parse(await submitResult.textContent());
    expect(submitWire.resultEdn).toMatch(/:receiver-requests 0(?:\s|,|}|$)/);
    await expectNextSequence(page, 1);
    await openCommandCellResult(page);
    await page.locator("#command-cell-panel").screenshot({
      path: testInfo.outputPath("ripple-real-outbox-command-cell-pending.png")
    });

    // A definite prior Command Cell result may be replaced. Delivery is a
    // separately declared cell, so its prepare is also I/O-free and exposes
    // its own exact branch instead of inferring a follow-up application call.
    await selectCommandCell(page, "example.outbox/deliver");
    await prepareExact(page, "{:op :deliver}");
    await expectNextSequence(page, 1);

    await commitOnlyChoice(page);
    const deliveryResult = page.getByTestId("command-cell-result");
    await expect(deliveryResult).toContainText("row-status");
    await expect(deliveryResult).toContainText("delivered");
    await expect(deliveryResult).toContainText("receiver-requests");
    const deliveryWire = JSON.parse(await deliveryResult.textContent());
    expect(deliveryWire.resultEdn).toMatch(/:receiver-requests 1(?:\s|,|}|$)/);
    await expectNextSequence(page, 2);
    await openCommandCellResult(page);
    await page.locator("#command-cell-panel").screenshot({
      path: testInfo.outputPath("ripple-real-outbox-command-cell-delivered.png")
    });

    // The generic workbench retains the same three updatable evidence IDs for
    // prepare, commit, and projected receipt; no Outbox-specific UI is needed.
    await page.getByTestId("workbench-refresh").click();
    const evidence = page.locator("article.workbench-item")
      .filter({hasText: "command-cell/outbox-live-"});
    await expect(evidence).toHaveCount(3);
    await expect(evidence.filter({hasText: "/prepare"})).toHaveCount(1);
    await expect(evidence.filter({hasText: "/commit"})).toHaveCount(1);
    await expect(evidence.filter({hasText: "/projected-receipt"}))
      .toHaveCount(1);

    // Preserve the explicit retained lifecycle proof: stopping the worker is
    // not delegated to command-cell admission or browser cleanup.
    await stopWorker(page);
    await expect(async () => {
      await page.getByTestId("retained-refresh").click();
      await expect(page.getByTestId("retained-status"))
        .toContainText("Worker is exited", {timeout: 1000});
    }).toPass({timeout: 20000});
    await expect(page.getByTestId("retained-send")).toBeDisabled();
  });
