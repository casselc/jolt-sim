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
        presentation: null,
        presentationError: null,
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
        presentation: null,
        presentationError: null,
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
        presentation: null,
        presentationError: null,
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

test("renders inert topology action descriptors through the retained command path", async ({page}, testInfo) => {
  const commandCalls = [];
  const presentation = {
    version: 1,
    kind: "example.kind/topology",
    sourceKind: "example/network",
    summary: "Example network",
    fields: [],
    graph: {
      directed: true,
      nodes: [
        {id: "a", label: "A", status: "example.status/ready", fields: [],
         actions: [
           {id: "inspect", label: "Inspect node A",
            commandCanonicalEdn: "[:jolt.sim.value/map [[[:jolt.sim.value/keyword nil \"op\"] [:jolt.sim.value/keyword nil \"inspect\"]] [[:jolt.sim.value/keyword nil \"target\"] [:jolt.sim.value/string \"a\"]]]]", enabled: true},
           {id: "stop", label: "Stop node A",
            commandCanonicalEdn: "[:jolt.sim.value/vector [[:jolt.sim.value/keyword nil \"stop\"] [:jolt.sim.value/string \"a\"]]]", enabled: false}
         ]},
        {id: "b", label: "B", status: null, fields: [], actions: []}
      ],
      edges: [
        {id: "a--b", from: "a", to: "b", label: "link", status: null,
         fields: [],
         actions: [
           {id: "partition", label: "Partition link",
            commandCanonicalEdn: "[:jolt.sim.value/map [[[:jolt.sim.value/keyword nil \"link\"] [:jolt.sim.value/vector [[:jolt.sim.value/string \"a\"] [:jolt.sim.value/string \"b\"]]]] [[:jolt.sim.value/keyword nil \"op\"] [:jolt.sim.value/keyword nil \"partition\"]]]]", enabled: true}
         ]}
      ]
    },
    sourceEdn: "{:kind :example/network}"
  };
  const completed = (sequence, nextSequence) => ({
    version: 1,
    outcome: "completed",
    committed: true,
    sequence,
    coordinate: coordinate("ready", nextSequence),
    receiptEdn: `{:status :completed :sequence ${sequence} :value :ok}`,
    frameEdn: `{:status :ready :next-sequence ${nextSequence}}`,
    frameError: null,
    presentation,
    presentationError: null,
    truncated: false
  });

  await page.route("**/api/retained-command", async (route) => {
    const body = route.request().postDataJSON();
    commandCalls.push(body);
    if (body.commandEdn === "{:op :topology}") {
      await route.fulfill({
        status: 200, contentType: "application/json",
        body: JSON.stringify(completed("4", "5"))
      });
      return;
    }
    if (body.commandCanonicalEdn === presentation.graph.nodes[0].actions[0].commandCanonicalEdn) {
      await route.fulfill({
        status: 200, contentType: "application/json",
        body: JSON.stringify(completed("5", "6"))
      });
      return;
    }
    if (body.commandCanonicalEdn === presentation.graph.edges[0].actions[0].commandCanonicalEdn) {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          version: 1,
          outcome: "transport-error",
          error: "retained-transport-error",
          reason: "receipt-deadline",
          status: "uncertain",
          sequence: "6",
          uncertainSequence: "6"
        })
      });
      return;
    }
    throw new Error(`unexpected retained command: ${JSON.stringify(body)}`);
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("retained-command").fill("{:op :topology}");
  await page.getByTestId("retained-send").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("acknowledged with outcome completed");
  await expect(page.getByTestId("retained-presentation")).toBeVisible();

  // Buttons render generically from the descriptors: no route, handler, or
  // label is known to Ripple beyond the closed action shape.
  const nodeA = page.locator('[data-node-detail-id="a"]');
  const nodeB = page.locator('[data-node-detail-id="b"]');
  const edge = page.locator('[data-edge-detail-id="a--b"]');
  await expect(nodeA.getByTestId("retained-topology-action")).toHaveCount(2);
  await expect(nodeB.getByTestId("retained-topology-action")).toHaveCount(0);
  await expect(edge.getByTestId("retained-topology-action")).toHaveCount(1);
  const inspectAction = nodeA.locator('[data-action-id="inspect"]');
  const stopAction = nodeA.locator('[data-action-id="stop"]');
  const partitionAction = edge.locator('[data-action-id="partition"]');
  await expect(inspectAction).toHaveText("Inspect node A");
  await expect(inspectAction).toBeEnabled();
  await expect(stopAction).toBeDisabled();
  await expect(partitionAction).toBeEnabled();

  // An enabled descriptor echoes its exact server-issued command EDN once
  // through the existing retained-command path.
  await nodeA.locator("summary").click();
  await inspectAction.click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("acknowledged with outcome completed");
  expect(commandCalls).toEqual([
    {version: 1, commandEdn: "{:op :topology}"},
    {version: 1, commandCanonicalEdn: presentation.graph.nodes[0].actions[0].commandCanonicalEdn}
  ]);
  await expect(nodeA.locator('[data-action-id="inspect"]')).toBeEnabled();

  // Descriptor buttons obey the retained surface's uncertain disablement:
  // after an ambiguous transport outcome no action can be resent implicitly.
  await edge.locator("summary").click();
  await partitionAction.click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("uncertain transport outcome at sequence 6");
  expect(commandCalls).toHaveLength(3);
  await expect(nodeA.locator('[data-action-id="inspect"]')).toBeDisabled();
  await expect(edge.locator('[data-action-id="partition"]')).toBeDisabled();
  await expect(page.getByTestId("retained-send")).toBeDisabled();
  await expect(page.getByTestId("retained-reconcile")).toBeEnabled();

  await page.screenshot({
    path: testInfo.outputPath("ripple-topology-actions.png"),
    fullPage: true
  });
});

test("disables topology actions when a shared REPL advances the retained coordinate", async ({page}) => {
  let commandCalls = 0;
  const commandCanonicalEdn =
    "[:jolt.sim.value/map [[[:jolt.sim.value/keyword nil \"op\"] [:jolt.sim.value/keyword nil \"inspect\"]]]]";
  await page.route("**/api/retained-command", async (route) => {
    commandCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        outcome: "completed",
        committed: true,
        sequence: "4",
        coordinate: coordinate("ready", "5"),
        receiptEdn: "{:status :completed :sequence 4 :value :ok}",
        frameEdn: "{:status :ready :next-sequence 5}",
        frameError: null,
        presentation: {
          version: 1,
          kind: "example.kind/topology",
          sourceKind: "example/network",
          summary: "Example network",
          fields: [],
          graph: {
            directed: false,
            nodes: [{
              id: "a", label: "A", status: null, fields: [],
              actions: [{id: "inspect", label: "Inspect",
                         commandCanonicalEdn, enabled: true}]
            }],
            edges: []
          },
          sourceEdn: "{:kind :example/network}"
        },
        presentationError: null,
        truncated: false
      })
    });
  });
  await page.route("**/api/retained-frame", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        version: 1,
        status: "ok",
        coordinate: coordinate("ready", "6"),
        frameEdn: "{:status :ready :next-sequence 6}"
      })
    });
  });

  await page.goto("/");
  await page.locator("#capability").fill(capabilityToken);
  await page.getByTestId("retained-command").fill(":topology");
  await page.getByTestId("retained-send").click();
  const action = page.locator('[data-action-id="inspect"]');
  await expect(action).toBeEnabled();

  // This frame represents a command issued from the persistent REPL. The old
  // presentation remains inspectable, but its action is no longer admitted.
  await page.getByTestId("retained-refresh").click();
  await expect(page.getByTestId("retained-status"))
    .toContainText("next command sequence is 6");
  await expect(action).toBeDisabled();
  await action.click({force: true});
  expect(commandCalls).toBe(1);
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
    presentation: null,
    presentationError: null,
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
