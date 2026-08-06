(() => {
  "use strict";

  const file = document.getElementById("case-file");
  const kind = document.getElementById("kind");
  const capability = document.getElementById("capability");
  const inspect = document.getElementById("inspect");
  const replay = document.getElementById("replay");
  const status = document.getElementById("status");
  const report = document.getElementById("report");
  const activity = document.getElementById("activity");
  const outcome = document.getElementById("outcome");
  const sessionRefresh = document.getElementById("session-refresh");
  const sessionReset = document.getElementById("session-reset");
  const sessionStatus = document.getElementById("session-status");
  const sessionFrame = document.getElementById("session-frame");
  const sessionChoicesList = document.getElementById("session-choices");
  const sessionStepStatus = document.getElementById("session-step-status");
  const sessionStepRetryRow = document.getElementById("session-step-retry-row");
  const sessionStepRetry = document.getElementById("session-step-retry");
  let documentText = null;
  let busy = false;
  let sessionCursor = "0";
  let sessionStepEnabled = false;
  let sessionChoices = [];
  let lastStepStatus = null;
  // Set only for an ambiguous step outcome (network failure or an
  // unrecognized response): the exact serialized request bytes, so Retry
  // can resend byte-identical and never synthesize a new coordinate.
  let pendingRetry = null;

  const canonicalUnsignedDecimal = (value) =>
    typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value);

  const canonicalSignedDecimal = (value) =>
    canonicalUnsignedDecimal(value) ||
    (typeof value === "string" && /^-[1-9][0-9]*$/.test(value));

  const exactKeys = (value, expected) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return false;
    const actual = Object.keys(value).sort();
    const wanted = expected.slice().sort();
    return actual.length === wanted.length &&
      actual.every((key, index) => key === wanted[index]);
  };

  const validChoice = (choice, revision) =>
    exactKeys(choice, ["revision", "kind", "value", "label"]) &&
    choice.revision === revision &&
    canonicalUnsignedDecimal(choice.revision) &&
    (choice.kind === "run" || choice.kind === "advance") &&
    (choice.kind === "run"
      ? canonicalUnsignedDecimal(choice.value)
      : canonicalSignedDecimal(choice.value)) &&
    typeof choice.label === "string";

  const safeStepErrors = new Set([
    "400:invalid-session-step",
    "400:invalid-session-cursor",
    "403:forbidden",
    "404:session-step-unavailable",
    "409:session-step-rejected",
    "413:request-too-large",
    "415:expected-application-json",
    "429:session-step-busy",
    "429:viewer-busy"
  ]);

  const enhanceTraceReport = () => {
    const doc = report.contentDocument;
    if (!doc) return;
    const filter = doc.getElementById("event-filter");
    const rows = Array.from(doc.querySelectorAll("#event-table tbody tr"));
    if (!filter || rows.length === 0) return;
    if (filter.dataset.rippleTraceFilterBound === "true") return;

    // Report-authored scripts stay disabled by the iframe sandbox. The
    // trusted outer Ripple shell owns this small read-only interaction.
    filter.removeAttribute("oninput");
    filter.setAttribute("aria-controls", "event-table");
    filter.setAttribute("aria-describedby", "event-filter-status");
    const status = doc.getElementById("event-filter-status") ||
      doc.createElement("span");
    if (!status.id) {
      status.id = "event-filter-status";
      status.setAttribute("role", "status");
      status.setAttribute("aria-live", "polite");
      filter.insertAdjacentElement("afterend", status);
    }

    const searchableRows = rows.map((row) => {
      const cells = row.querySelectorAll("td");
      const semantic = [cells[0], cells[1], cells[2], cells[3], cells[4],
        row.querySelector(".event-summary"),
        row.querySelector(".event-fields"),
        row.querySelector("details code.edn")];
      return {
        row,
        haystack: semantic.filter(Boolean)
          .map((element) => element.textContent)
          .join(" ")
          .toLowerCase()
      };
    });

    const applyFilter = () => {
      const query = filter.value.trim().toLowerCase();
      searchableRows.forEach(({row, haystack}) => {
        row.hidden = Boolean(query) && !haystack.includes(query);
      });
      const visible = rows.filter((row) => !row.hidden).length;
      status.textContent = `${visible} of ${rows.length} events visible.`;
    };
    filter.addEventListener("input", applyFilter);
    filter.dataset.rippleTraceFilterBound = "true";
    applyFilter();
  };

  const enhanceExperimentReport = () => {
    const doc = report.contentDocument;
    if (!doc) return;
    const filter = doc.getElementById("topology-filter");
    const mode = doc.getElementById("topology-mode");
    const pack = doc.getElementById("topology-pack");
    const clear = doc.getElementById("topology-clear");
    const jump = doc.getElementById("topology-jump");
    const topologyStatus = doc.getElementById("topology-status");
    if (!filter || !mode || !pack || !clear || !jump || !topologyStatus) return;

    const nodes = Array.from(doc.querySelectorAll(".topology-node"));
    const edges = Array.from(doc.querySelectorAll(".topology-edge"));
    const rows = Array.from(doc.querySelectorAll(".plan-row[data-entity-type]"));
    let selected = null;

    const entityRow = (type, id) => rows.find((row) =>
      row.dataset.entityType === type && row.dataset.entityId === id);

    const clearSelectionClasses = () => {
      nodes.concat(edges).forEach((element) => {
        element.classList.remove("is-selected", "is-related", "is-dimmed");
      });
      rows.forEach((row) => row.classList.remove("is-selected"));
    };

    const selectEntity = (type, id) => {
      clearSelectionClasses();
      selected = {type, id};
      const relatedNodes = new Set();
      const relatedEdges = new Set();
      if (type === "node") {
        relatedNodes.add(id);
        edges.forEach((edge) => {
          if (edge.dataset.fromNode === id || edge.dataset.toNode === id) {
            relatedEdges.add(edge.dataset.connection);
            relatedNodes.add(edge.dataset.fromNode);
            relatedNodes.add(edge.dataset.toNode);
          }
        });
      } else {
        relatedEdges.add(id);
        const edge = edges.find((candidate) => candidate.dataset.connection === id);
        if (edge) {
          relatedNodes.add(edge.dataset.fromNode);
          relatedNodes.add(edge.dataset.toNode);
        }
      }
      nodes.forEach((node) => {
        const own = type === "node" && node.dataset.node === id;
        node.classList.toggle("is-selected", own);
        node.classList.toggle("is-related", !own && relatedNodes.has(node.dataset.node));
        node.classList.toggle("is-dimmed", !relatedNodes.has(node.dataset.node));
      });
      edges.forEach((edge) => {
        const own = type === "connection" && edge.dataset.connection === id;
        edge.classList.toggle("is-selected", own);
        edge.classList.toggle("is-related", !own && relatedEdges.has(edge.dataset.connection));
        edge.classList.toggle("is-dimmed", !relatedEdges.has(edge.dataset.connection));
      });
      const row = entityRow(type, id);
      if (row) row.classList.add("is-selected");
      jump.disabled = !row;
      topologyStatus.textContent = `Selected ${type} ${id}.`;
    };

    const matches = (element, query) =>
      !query || (element.dataset.search || "").toLowerCase().includes(query);

    const applyFilters = () => {
      const query = filter.value.trim().toLowerCase();
      const selectedMode = mode.value;
      const selectedPack = pack.value;
      const filtering = Boolean(query || selectedMode || selectedPack);
      const matchingNodeIds = new Set(nodes
        .filter((node) => query && matches(node, query))
        .map((node) => node.dataset.node));
      const connectedNodeIds = new Set(edges.flatMap((edge) =>
        [edge.dataset.fromNode, edge.dataset.toNode]));
      const visibleNodeIds = new Set();
      edges.forEach((edge) => {
        const visible = (!selectedMode || edge.dataset.mode === selectedMode) &&
          (!selectedPack || edge.dataset.pack === selectedPack) &&
          (!query || matches(edge, query) ||
            matchingNodeIds.has(edge.dataset.fromNode) ||
            matchingNodeIds.has(edge.dataset.toNode));
        edge.hidden = !visible;
        if (visible) {
          visibleNodeIds.add(edge.dataset.fromNode);
          visibleNodeIds.add(edge.dataset.toNode);
        }
      });
      nodes.forEach((node) => {
        if (!filtering || matchingNodeIds.has(node.dataset.node) ||
            (!query && !connectedNodeIds.has(node.dataset.node))) {
          visibleNodeIds.add(node.dataset.node);
        }
      });
      nodes.forEach((node) => {
        node.hidden = filtering && !visibleNodeIds.has(node.dataset.node);
      });
      rows.forEach((row) => {
        row.hidden = row.dataset.entityType === "node"
          ? nodes.some((node) => node.dataset.node === row.dataset.entityId && node.hidden)
          : edges.some((edge) => edge.dataset.connection === row.dataset.entityId && edge.hidden);
      });
      if (selected) {
        const element = selected.type === "node"
          ? nodes.find((node) => node.dataset.node === selected.id)
          : edges.find((edge) => edge.dataset.connection === selected.id);
        if (!element || element.hidden) {
          selected = null;
          clearSelectionClasses();
          jump.disabled = true;
        }
      }
      const visibleNodes = nodes.filter((node) => !node.hidden).length;
      const visibleEdges = edges.filter((edge) => !edge.hidden).length;
      topologyStatus.textContent = selected
        ? `Selected ${selected.type} ${selected.id}; ${visibleNodes} rendered nodes, ${visibleEdges} rendered connections visible.`
        : `${visibleNodes} rendered nodes, ${visibleEdges} rendered connections visible.`;
    };

    const bindSelectable = (element, type, id) => {
      element.addEventListener("click", () => selectEntity(type, id));
      element.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          selectEntity(type, id);
        }
      });
    };
    nodes.forEach((node) => bindSelectable(node, "node", node.dataset.node));
    edges.forEach((edge) => bindSelectable(edge, "connection", edge.dataset.connection));
    filter.addEventListener("input", applyFilters);
    mode.addEventListener("change", applyFilters);
    pack.addEventListener("change", applyFilters);
    clear.addEventListener("click", () => {
      filter.value = "";
      mode.value = "";
      pack.value = "";
      selected = null;
      clearSelectionClasses();
      jump.disabled = true;
      applyFilters();
    });
    jump.addEventListener("click", () => {
      if (!selected) return;
      const row = entityRow(selected.type, selected.id);
      if (row) {
        row.scrollIntoView({behavior: "smooth", block: "center"});
        row.focus({preventScroll: true});
      }
    });
    applyFilters();
  };

  const enhanceReport = () => {
    enhanceTraceReport();
    enhanceExperimentReport();
  };

  const renderProgress = (progress) => {
    const stdoutText = (progress.stdout && progress.stdout.text) || "";
    const stderrText = (progress.stderr && progress.stderr.text) || "";
    const lines = [`status: ${progress.status}`];
    if (progress.stdout || progress.stderr) {
      lines.push("", "--- stdout ---", stdoutText, "", "--- stderr ---", stderrText);
    }
    activity.textContent = lines.join("\n");
  };

  const activeProgressStatus = (value) =>
    value === "starting" || value === "worker-ready" || value === "running";

  let polling = false;
  let pollTimer = null;
  let pollGeneration = 0;

  const stopPolling = () => {
    polling = false;
    pollGeneration += 1;
    if (pollTimer !== null) {
      clearTimeout(pollTimer);
      pollTimer = null;
    }
  };

  const pollProgressOnce = async (generation, authoritative = false) => {
    const response = await fetch("/api/replay-progress", {
      method: "GET",
      headers: { "X-Jolt-Sim-Capability": capability.value },
      cache: "no-store",
      credentials: "omit"
    });
    if (!response.ok) return;
    const progress = await response.json();
    if (generation === pollGeneration &&
        (authoritative || activeProgressStatus(progress.status))) {
      renderProgress(progress);
    }
  };

  const startPolling = () => {
    if (polling) return;
    polling = true;
    const generation = ++pollGeneration;
    const tick = async () => {
      if (!polling || generation !== pollGeneration) return;
      try {
        await pollProgressOnce(generation);
      } catch (error) {
        // Best-effort progress display; a transient poll failure does not
        // stop the authoritative replay request below.
      }
      if (polling && generation === pollGeneration) {
        pollTimer = setTimeout(tick, 500);
      }
    };
    tick();
  };

  const updateButtons = () => {
    // The document kind is always chosen explicitly; the server never guesses
    // a schema from the uploaded bytes. Replay is a Case/Outcome-only path,
    // so declared trace and experiment-plan kinds keep replay disabled.
    const ready = typeof documentText === "string" &&
                  kind.value.length > 0 &&
                  capability.value.length > 0;
    file.disabled = busy;
    kind.disabled = busy;
    capability.disabled = busy;
    inspect.disabled = busy || !ready;
    replay.disabled = busy || !ready || kind.value !== "case-outcome";
    // An unresolved ambiguous step outcome blocks a fresh frame read too, so
    // the only available action stays Retry until it resolves or Reset runs.
    sessionRefresh.disabled = busy || capability.value.length === 0 || pendingRetry !== null;
    sessionReset.disabled = busy;
    syncSessionChoiceUI();
  };

  const renderChoices = () => {
    sessionChoicesList.textContent = "";
    if (sessionChoices.length === 0) {
      if (pendingRetry === null) {
        const empty = document.createElement("li");
        empty.className = "muted";
        empty.textContent = "No branch choices available.";
        sessionChoicesList.appendChild(empty);
      }
      return;
    }
    sessionChoices.forEach((choice) => {
      const item = document.createElement("li");
      const button = document.createElement("button");
      button.type = "button";
      button.textContent =
        `${choice.label} — ${choice.kind} ${choice.value} (revision ${choice.revision})`;
      button.disabled = busy || !sessionStepEnabled ||
        capability.value.length === 0 || pendingRetry !== null;
      button.addEventListener("click", () => submitChoice(choice));
      item.appendChild(button);
      sessionChoicesList.appendChild(item);
    });
  };

  const syncSessionChoiceUI = () => {
    sessionStepRetryRow.hidden = pendingRetry === null;
    sessionStepRetry.disabled = busy;
    renderChoices();
  };

  const request = async (path) => {
    const response = await fetch(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/edn",
        "X-Jolt-Sim-Capability": capability.value,
        "X-Jolt-Sim-Document-Kind": kind.value
      },
      body: documentText,
      cache: "no-store",
      credentials: "omit"
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`${response.status} ${text}`);
    return text;
  };

  file.addEventListener("change", async () => {
    documentText = null;
    report.removeAttribute("srcdoc");
    outcome.textContent = "No replay has run.";
    stopPolling();
    activity.textContent = "Idle.";
    const selected = file.files && file.files[0];
    if (!selected) {
      status.textContent = "";
      updateButtons();
      return;
    }
    try {
      documentText = await selected.text();
      status.textContent = `Loaded ${selected.name} (${selected.size} bytes); not sent yet.`;
    } catch (error) {
      status.textContent = `Could not read file: ${error.message}`;
    }
    updateButtons();
  });

  capability.addEventListener("input", updateButtons);
  kind.addEventListener("change", updateButtons);

  const refreshSessionFrame = async () => {
    if (busy) return;
    busy = true;
    updateButtons();
    sessionStatus.textContent = "Reading one coherent session frame...";
    sessionFrame.textContent = "Refreshing; no current session frame.";
    try {
      const response = await fetch("/api/session-frame", {
        method: "GET",
        headers: {
          "Accept": "application/json",
          "X-Jolt-Sim-Capability": capability.value,
          "X-Jolt-Sim-Journal-Cursor": sessionCursor
        },
        cache: "no-store",
        credentials: "omit"
      });
      const body = await response.json().catch(() => null);
      if (!response.ok ||
          !exactKeys(body, ["version", "revision", "nextCursor", "stepEnabled",
            "frameEdn", "choices"]) ||
          body.version !== 1 ||
          !canonicalUnsignedDecimal(body.revision) ||
          !canonicalUnsignedDecimal(body.nextCursor) ||
          typeof body.stepEnabled !== "boolean" ||
          typeof body.frameEdn !== "string" ||
          !Array.isArray(body.choices) ||
          !body.choices.every((choice) => validChoice(choice, body.revision))) {
        throw new Error(`${response.status} ${body ? JSON.stringify(body) : "unparseable response"}`);
      }
      sessionCursor = body.nextCursor;
      sessionFrame.textContent = body.frameEdn;
      sessionStepEnabled = body.stepEnabled === true;
      sessionChoices = Array.isArray(body.choices) ? body.choices : [];
      const availability = sessionChoices.length === 0
        ? `No branch choices available at revision ${body.revision}.`
        : sessionStepEnabled
          ? `${sessionChoices.length} branch choice(s) available at revision ${body.revision}.`
          : `${sessionChoices.length} branch choice(s) shown for a read-only session ` +
            `(revision ${body.revision}); controls are disabled.`;
      sessionStepStatus.textContent = lastStepStatus
        ? `${lastStepStatus} ${availability}`
        : availability;
      sessionStatus.textContent = `Coherent frame loaded; journal cursor is ${sessionCursor}.`;
    } catch (error) {
      sessionFrame.textContent = "No current session frame; the last refresh failed.";
      sessionStatus.textContent = `Session refresh failed: ${error.message}`;
      sessionStepEnabled = false;
      sessionChoices = [];
    } finally {
      busy = false;
      updateButtons();
    }
  };

  sessionRefresh.addEventListener("click", refreshSessionFrame);

  const submitStepRequest = (bodyText) => fetch("/api/session-step", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json",
      "X-Jolt-Sim-Capability": capability.value
    },
    body: bodyText,
    cache: "no-store",
    credentials: "omit"
  });

  const performStep = async (bodyText, isRetry) => {
    busy = true;
    updateButtons();
    sessionStepStatus.textContent = isRetry
      ? "Retrying the identical branch-choice command..."
      : "Sending branch-choice command...";
    let outcome = "ambiguous";
    let receipt = null;
    const command = JSON.parse(bodyText).branch;
    try {
      let response;
      try {
        response = await submitStepRequest(bodyText);
      } catch (networkError) {
        sessionStepStatus.textContent =
          `Network failure before any server acknowledgment; not confirmed committed. ` +
          `(${networkError.message}) Retry sends the identical command bytes.`;
        return;
      }
      receipt = await response.json().catch(() => null);
      const jsonResponse = (response.headers.get("Content-Type") || "")
        .toLowerCase().startsWith("application/json");
      const exactCoordinate = receipt &&
        receipt.revision === command.revision &&
        receipt.kind === command.kind &&
        receipt.value === command.value;
      const committedReceipt = jsonResponse && response.ok &&
        exactKeys(receipt, ["version", "outcome", "committed", "revision", "kind",
          "value", "receiptEdn"]) &&
        receipt.version === 1 && receipt.outcome === "committed" &&
        receipt.committed === true && exactCoordinate &&
        typeof receipt.receiptEdn === "string";
      const staleReceipt = jsonResponse && response.status === 409 &&
        exactKeys(receipt, ["version", "outcome", "committed", "revision", "kind",
          "value", "receiptEdn"]) &&
        receipt.version === 1 && receipt.outcome === "stale" &&
        receipt.committed === false && exactCoordinate &&
        typeof receipt.receiptEdn === "string";
      const rejectedReceipt = jsonResponse &&
        exactKeys(receipt, ["version", "outcome", "committed", "error"]) &&
        receipt.version === 1 && receipt.outcome === "error" &&
        receipt.committed === false && typeof receipt.error === "string" &&
        safeStepErrors.has(`${response.status}:${receipt.error}`);
      if (committedReceipt) {
        outcome = "committed";
        lastStepStatus =
          `Committed revision ${receipt.revision} (${receipt.kind} ${receipt.value}); ` +
          `server acknowledged the exact command.`;
        sessionStepStatus.textContent = `${lastStepStatus} Refreshing session frame.`;
      } else if (staleReceipt) {
        outcome = "stale";
        lastStepStatus =
          `Not committed (${receipt.outcome}); refresh the session for current choices.`;
        sessionStepStatus.textContent = lastStepStatus;
      } else if (rejectedReceipt) {
        outcome = "rejected";
        lastStepStatus =
          `Not committed (${receipt.error}); refresh after correcting the request or capability.`;
        sessionStepStatus.textContent = lastStepStatus;
      } else {
        sessionStepStatus.textContent =
          `Unacknowledged: server returned ${response.status} without a recognizable receipt; ` +
          `not confirmed committed. Retry sends the identical command bytes.`;
      }
    } finally {
      pendingRetry = outcome === "ambiguous" ? bodyText : null;
      sessionChoices = [];
      busy = false;
      updateButtons();
    }
    if (outcome === "committed") {
      await refreshSessionFrame();
    }
  };

  const submitChoice = (choice) => {
    if (busy || pendingRetry !== null) return;
    const bodyText = JSON.stringify({
      version: 1,
      cursor: sessionCursor,
      branch: {
        revision: String(choice.revision),
        kind: choice.kind,
        value: String(choice.value)
      }
    });
    return performStep(bodyText, false);
  };

  sessionStepRetry.addEventListener("click", () => {
    if (busy || pendingRetry === null) return;
    performStep(pendingRetry, true);
  });

  sessionReset.addEventListener("click", () => {
    sessionCursor = "0";
    sessionFrame.textContent = "No current session frame; refresh from cursor zero.";
    sessionStatus.textContent = "Journal cursor reset; refresh to read from the beginning.";
    sessionStepEnabled = false;
    sessionChoices = [];
    lastStepStatus = null;
    pendingRetry = null;
    sessionStepStatus.textContent = "No branch choice sent.";
    updateButtons();
  });

  inspect.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    updateButtons();
    status.textContent = "Validating and rendering retained evidence...";
    try {
      report.srcdoc = await request("/api/render");
      status.textContent = "Validated report rendered.";
    } catch (error) {
      status.textContent = `Inspect failed: ${error.message}`;
    } finally {
      busy = false;
      updateButtons();
    }
  });

  report.addEventListener("load", enhanceReport);

  replay.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    updateButtons();
    status.textContent = "Running one fresh-process replay...";
    activity.textContent = "status: starting";
    // Initiate the authoritative replay POST before polling. Non-final polls
    // additionally ignore idle/terminal snapshots, since separate HTTP
    // connections do not guarantee server arrival order.
    const replayRequest = request("/api/replay");
    startPolling();
    try {
      outcome.textContent = await replayRequest;
      status.textContent = "Fresh replay completed; raw outcome preserved below.";
    } catch (error) {
      outcome.textContent = "No replay outcome returned.";
      status.textContent = `Replay failed: ${error.message}`;
    } finally {
      stopPolling();
      try {
        await pollProgressOnce(pollGeneration, true);
      } catch (error) {
        // Best-effort final snapshot fetch.
      }
      busy = false;
      updateButtons();
    }
  });
})();
