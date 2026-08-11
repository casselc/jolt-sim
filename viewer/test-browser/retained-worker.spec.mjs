import {expect, test} from "@playwright/test";

const capabilityToken = "ripple-browser-test-capability-0123456789abcdef";

const coordinate = (status, nextSequence, uncertainSequence = null) => ({
  protocol: 1,
  status,
  nextSequence,
  uncertainSequence
});

test("controls a retained worker without retrying an uncertain command", async ({page}, testInfo) => {
  let commandCalls = 0;
  let reconcileCalls = 0;
  let terminateCalls = 0;

  await page.route("**/api/retained-frame", async (route) => {
    expect(route.request().method()).toBe("GET");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        status: "ok",
        coordinate: coordinate("ready", "4"),
        frameEdn: "{:status :ready :next-sequence 4 :worker {:alive? true}}"
      })
    });
  });

  await page.route("**/api/retained-command", async (route) => {
    commandCalls += 1;
    expect(route.request().postDataJSON()).toEqual({
      version: 1,
      commandEdn: "{:op :inspect}"
    });
    if (commandCalls === 1) {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          version: 1,
          outcome: "transport-error",
          error: "retained-transport-error",
          reason: "receipt-deadline",
          status: "uncertain",
          sequence: "4",
          uncertainSequence: "4"
        })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        outcome: "failed",
        committed: true,
        sequence: "5",
        coordinate: coordinate("ready", "6"),
        receiptEdn: "{:status :failed :sequence 5 :error {:reason :ack-mismatch}}",
        frameEdn: "{:status :ready :next-sequence 6}",
        frameError: null,
        truncated: false
      })
    });
  });

  await page.route("**/api/retained-reconcile", async (route) => {
    reconcileCalls += 1;
    expect(route.request().postDataJSON()).toEqual({version: 1});
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        outcome: "completed",
        committed: true,
        sequence: "4",
        coordinate: coordinate("ready", "5"),
        receiptEdn: "{:status :completed :sequence 4 :value {:rows []}}",
        frameEdn: "{:status :ready :next-sequence 5}",
        frameError: null,
        truncated: false
      })
    });
  });

  await page.route("**/api/retained-terminate", async (route) => {
    terminateCalls += 1;
    expect(route.request().postDataJSON()).toEqual({version: 1});
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        status: "ok",
        outcome: "terminated",
        coordinate: coordinate("terminated", "6"),
        frameEdn: "{:status :terminated :next-sequence 6}"
      })
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("retained-refresh").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("Worker is ready; next command sequence is 4.");
  await expect(page.getByTestId("retained-frame")).toContainText(
    ":next-sequence 4"
  );

  await page.getByTestId("retained-send").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("uncertain transport outcome at sequence 4");
  expect(commandCalls).toBe(1);
  await expect(page.getByTestId("retained-send")).toBeDisabled();
  await expect(page.getByTestId("retained-reconcile")).toBeEnabled();

  await page.getByTestId("retained-reconcile").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("acknowledged with outcome completed");
  expect(reconcileCalls).toBe(1);
  expect(commandCalls).toBe(1);
  await expect(page.getByTestId("retained-send")).toBeEnabled();
  await expect(page.getByTestId("retained-receipt"))
    .toContainText(":sequence 4");

  await page.getByTestId("retained-send").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("Application failure was acknowledged definitively");
  expect(commandCalls).toBe(2);
  await expect(page.getByTestId("retained-receipt"))
    .toContainText(":ack-mismatch");
  await expect(page.getByTestId("retained-send")).toBeEnabled();

  await page.getByTestId("retained-terminate").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("acknowledged with outcome terminated");
  await expect(page.getByTestId("retained-frame"))
    .toContainText(":status :terminated");
  await expect(page.getByTestId("retained-send")).toBeDisabled();
  await expect(page.getByTestId("retained-terminate")).toBeDisabled();
  expect(terminateCalls).toBe(1);

  await page.screenshot({
    path: testInfo.outputPath("ripple-retained-worker-controls.png"),
    fullPage: true
  });
});

test("distinguishes a prepublication failure from a reconcilable command", async ({page}) => {
  await page.route("**/api/retained-command", async (route) => {
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        outcome: "transport-error",
        error: "retained-transport-error",
        reason: "publication-failed",
        status: "failed",
        sequence: "0",
        uncertainSequence: null
      })
    });
  });
  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("retained-send").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("failed before admission (publication-failed)");
  await expect(page.getByTestId("retained-reconcile")).toBeDisabled();
  await expect(page.getByTestId("retained-send")).toBeDisabled();
});

test("does not send commands to a known exited or failed worker", async ({page}) => {
  let status = "exited";
  await page.route("**/api/retained-frame", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        status: "ok",
        coordinate: coordinate(status, "4"),
        frameEdn: `{:status :${status} :next-sequence 4}`
      })
    });
  });
  for (status of ["exited", "failed"]) {
    await page.goto("/");
    await page.locator("#capability").fill(capabilityToken);
    await page.getByTestId("retained-refresh").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText(`Worker is ${status}`);
    await expect(page.getByTestId("retained-send")).toBeDisabled();
    await expect(page.getByTestId("retained-reconcile")).toBeDisabled();
    await expect(page.getByTestId("retained-terminate")).toBeEnabled();
  }
});

test("keeps a definite receipt when a REPL caller advances the fresh frame", async ({page}) => {
  const laterCoordinates = [
    coordinate("ready", "2"),
    coordinate("uncertain", "2", "2")
  ];
  let index = 0;
  await page.route("**/api/retained-command", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        outcome: "completed",
        committed: true,
        sequence: "0",
        coordinate: laterCoordinates[index],
        receiptEdn: "{:status :completed :sequence 0 :value :accepted}",
        frameEdn: `{:status :${laterCoordinates[index].status} :next-sequence 2}`,
        frameError: null,
        truncated: false
      })
    });
  });
  for (index = 0; index < laterCoordinates.length; index += 1) {
    await page.goto("/");
    await page.locator("#capability").fill(capabilityToken);
    await page.getByTestId("retained-send").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText("acknowledged with outcome completed");
    await expect(page.getByTestId("retained-receipt"))
      .toContainText(":sequence 0");
    if (laterCoordinates[index].uncertainSequence === null) {
      await expect(page.getByTestId("retained-send")).toBeEnabled();
    } else {
      await expect(page.getByTestId("retained-send")).toBeDisabled();
      await expect(page.getByTestId("retained-reconcile")).toBeEnabled();
    }
  }
});

test("rejects incompatible retained acknowledgments and blocks resend", async ({page}) => {
  const base = {
    version: 1,
    outcome: "completed",
    committed: true,
    sequence: "0",
    coordinate: coordinate("ready", "1"),
    receiptEdn: "{:status :completed :sequence 0 :value :ok}",
    frameEdn: "{:status :ready :next-sequence 1}",
    frameError: null,
    truncated: false
  };
  const malformed = [
    {...base, coordinate: {...base.coordinate, protocol: 2}},
    {...base, coordinate: {...base.coordinate, status: "bogus"}},
    {...base, coordinate: {...base.coordinate, uncertainSequence: "0"}},
    {...base, truncated: true}
  ];
  let index = 0;
  await page.route("**/api/retained-command", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(malformed[index])
    });
  });
  for (index = 0; index < malformed.length; index += 1) {
    await page.goto("/");
    await page.locator("#capability").fill(capabilityToken);
    await page.getByTestId("retained-send").click();
    await expect(page.getByTestId("retained-status"))
      .toContainText("returned no recognizable acknowledgment");
    await expect(page.getByTestId("retained-send")).toBeDisabled();
  }
});
