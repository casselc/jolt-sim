(() => {
  "use strict";

  const file = document.getElementById("case-file");
  const kind = document.getElementById("kind");
  const capability = document.getElementById("capability");
  const inspect = document.getElementById("inspect");
  const replay = document.getElementById("replay");
  const runPreset = document.getElementById("run-preset");
  const runRegime = document.getElementById("run-regime");
  const runRegimeSummary = document.getElementById("run-regime-summary");
  const loadRunPresets = document.getElementById("load-run-presets");
  const runNew = document.getElementById("run-new");
  const runPresetStatus = document.getElementById("run-preset-status");
  const status = document.getElementById("status");
  const report = document.getElementById("report");
  const activity = document.getElementById("activity");
  const activityPanel = document.getElementById("activity-panel");
  const activityEvents = document.getElementById("activity-events");
  const activityPrevious = document.getElementById("activity-previous");
  const activityNext = document.getElementById("activity-next");
  const activityPageStatus = document.getElementById("activity-page-status");
  const activityRecovery = document.getElementById("activity-recovery");
  const outcome = document.getElementById("outcome");
  const evalForm = document.getElementById("eval-form");
  const evalSubmit = document.getElementById("eval-submit");
  const evalStatus = document.getElementById("eval-status");
  const evalTranscript = document.getElementById("eval-transcript");
  const retainedCommand = document.getElementById("retained-command");
  const retainedRefresh = document.getElementById("retained-refresh");
  const retainedSend = document.getElementById("retained-send");
  const retainedReconcile = document.getElementById("retained-reconcile");
  const retainedTerminate = document.getElementById("retained-terminate");
  const retainedStatus = document.getElementById("retained-status");
  const retainedFrame = document.getElementById("retained-frame");
  const retainedReceipt = document.getElementById("retained-receipt");
  const sessionRefresh = document.getElementById("session-refresh");
  const sessionReset = document.getElementById("session-reset");
  const sessionStatus = document.getElementById("session-status");
  const sessionFrame = document.getElementById("session-frame");
  const sessionChoicesList = document.getElementById("session-choices");
  const sessionStepStatus = document.getElementById("session-step-status");
  const sessionStepRetryRow = document.getElementById("session-step-retry-row");
  const sessionStepRetry = document.getElementById("session-step-retry");
  const sessionInstanceHeader = "X-Jolt-Sim-Session-Instance";
  let documentText = null;
  let busy = false;
  let sessionCursor = "0";
  let sessionInstanceKnown = false;
  let sessionInstanceId = null;
  let sessionStepEnabled = false;
  let sessionChoices = [];
  let lastStepStatus = null;
  let lastStepCommitted = false;
  let lastStepUnknown = false;
  // Set only for an ambiguous step outcome (network failure or an
  // unrecognized response): the exact serialized request bytes and producer
  // epoch, so an explicitly requested Retry can resend the same command to the
  // same producer and never synthesize a new coordinate. A later stale or
  // rejected retry does not resolve whether the original attempt committed.
  let pendingRetry = null;
  let activityCursors = ["0"];
  let activityPageIndex = 0;
  let activityPage = null;
  let activityGeneration = 0;
  let activityNavigationBusy = false;
  let runPresets = [];
  let selectedRunPreset = null;
  let selectedRunRegime = null;
  let retainedBusy = false;
  let retainedUncertain = false;
  let retainedTerminationUnknown = false;
  let retainedCoordinateKnown = false;
  let retainedWorkerStatus = null;

  const canonicalUnsignedDecimal = (value) =>
    typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value);

  const canonicalSignedDecimal = (value) =>
    canonicalUnsignedDecimal(value) ||
    (typeof value === "string" && /^-[1-9][0-9]*$/.test(value));

  const boundedString = (value, maximumCharacters) =>
    typeof value === "string" &&
    Array.from(value).length <= maximumCharacters;

  const retainedStatusName = (value) =>
    typeof value === "string" && value.length > 0 && value.length <= 64 &&
    /^[A-Za-z][A-Za-z0-9_-]*$/.test(value);

  const retainedWorkerStatuses = new Set([
    "ready", "uncertain", "exited", "failed", "terminated"
  ]);

  const retainedKeywordText = (value) =>
    retainedStatusName(value) || namespacedIdentifier(value);

  const validRetainedFrameError = (value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return false;
    const allowed = new Set(["type", "phase", "reason", "status", "sequence"]);
    const keys = Object.keys(value);
    return keys.every((key) => allowed.has(key)) &&
      keys.includes("type") && keys.includes("phase") &&
      retainedKeywordText(value.type) && value.phase === "post-receipt" &&
      (!keys.includes("reason") || retainedKeywordText(value.reason)) &&
      (!keys.includes("status") || retainedKeywordText(value.status)) &&
      (!keys.includes("sequence") || canonicalUnsignedDecimal(value.sequence));
  };

  const validRetainedCoordinate = (value, partial = false) => {
    if (!exactKeys(value,
      ["protocol", "status", "nextSequence", "uncertainSequence"])) return false;
    const unknown = value.protocol === null && value.status === null;
    const identified = value.protocol === 1 && retainedWorkerStatuses.has(value.status);
    return (identified || (partial && unknown)) &&
      canonicalUnsignedDecimal(value.nextSequence) &&
      (value.uncertainSequence === null ||
        (canonicalUnsignedDecimal(value.uncertainSequence) &&
         value.uncertainSequence === value.nextSequence));
  };

  const validRetainedFrameResponse = (value) =>
    exactKeys(value, ["version", "status", "coordinate", "frameEdn"]) &&
    value.version === 1 && value.status === "ok" &&
    validRetainedCoordinate(value.coordinate) &&
    boundedString(value.frameEdn, 16 * 1024 * 1024);

  const validRetainedOutcomeResponse = (value) => {
    if (!exactKeys(value,
      ["version", "outcome", "committed", "sequence", "coordinate",
        "receiptEdn", "frameEdn", "frameError", "truncated"])) return false;
    if (value.version !== 1 ||
        !["completed", "failed"].includes(value.outcome) ||
        value.committed !== true || !canonicalUnsignedDecimal(value.sequence) ||
        !validRetainedCoordinate(value.coordinate, true)) {
      return false;
    }
    const receiptNext = BigInt(value.sequence) + 1n;
    const partialCoordinate = value.coordinate.protocol === null;
    const coherentCoordinate = partialCoordinate
      ? value.coordinate.uncertainSequence === null &&
        BigInt(value.coordinate.nextSequence) === receiptNext
      : BigInt(value.coordinate.nextSequence) >= receiptNext;
    if (!coherentCoordinate) return false;
    const fullFrame = value.truncated === false &&
      boundedString(value.receiptEdn, 16 * 1024 * 1024) &&
      boundedString(value.frameEdn, 16 * 1024 * 1024) &&
      value.frameError === null && validRetainedCoordinate(value.coordinate);
    const frameUnavailable = value.truncated === false &&
      boundedString(value.receiptEdn, 16 * 1024 * 1024) &&
      value.frameEdn === null && validRetainedFrameError(value.frameError) &&
      value.coordinate.protocol === null && value.coordinate.status === null;
    const truncated = value.truncated === true && value.receiptEdn === null &&
      value.frameEdn === null && value.frameError === null;
    return fullFrame || frameUnavailable || truncated;
  };

  const validRetainedTerminateResponse = (value) => {
    const normal = exactKeys(value,
      ["version", "status", "outcome", "coordinate", "frameEdn"]);
    const truncated = exactKeys(value,
      ["version", "status", "outcome", "coordinate", "frameEdn", "truncated"]);
    return (normal || truncated) &&
    value.version === 1 && value.status === "ok" &&
    value.outcome === "terminated" && validRetainedCoordinate(value.coordinate) &&
    (value.frameEdn === null || boundedString(value.frameEdn, 16 * 1024 * 1024)) &&
    (normal ? value.frameEdn !== null : value.truncated === true && value.frameEdn === null);
  };

  const validRetainedTransportResponse = (value) =>
    exactKeys(value,
      ["version", "outcome", "error", "reason", "status", "sequence",
        "uncertainSequence"]) &&
    value.version === 1 && value.outcome === "transport-error" &&
    value.error === "retained-transport-error" &&
    retainedStatusName(value.reason) &&
    (value.status === null || retainedWorkerStatuses.has(value.status)) &&
    (value.sequence === null || canonicalUnsignedDecimal(value.sequence)) &&
    (value.uncertainSequence === null ||
      (canonicalUnsignedDecimal(value.uncertainSequence) &&
       value.uncertainSequence === value.sequence));

  const retainedNotAdmittedStatuses = new Set([400, 403, 404, 413, 415, 429]);

  const validSessionInstanceId = (value) =>
    typeof value === "string" && value.length >= 16 && value.length <= 128 &&
    /^[A-Za-z0-9._~-]+$/.test(value);

  const exactKeys = (value, expected) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return false;
    const actual = Object.keys(value).sort();
    const wanted = expected.slice().sort();
    return actual.length === wanted.length &&
      actual.every((key, index) => key === wanted[index]);
  };

  const namespacedIdentifier = (value) =>
    typeof value === "string" &&
    /^[^\s/:]+(?:\.[^\s/:]+)*\/[^\s/]+$/.test(value);

  const validEvalEvent = (event) => {
    if (!event || typeof event !== "object" || Array.isArray(event)) return false;
    if (event.tag === "out" || event.tag === "err") {
      return exactKeys(event, ["tag", "text", "truncated"]) &&
        boundedString(event.text, 4096) &&
        typeof event.truncated === "boolean";
    }
    return event.tag === "ret" &&
      exactKeys(event, ["tag", "printedValue", "truncated", "printFailed",
        "exception", "namespace", "namespaceTruncated", "elapsedMs"]) &&
      boundedString(event.printedValue, 4096) &&
      typeof event.truncated === "boolean" &&
      typeof event.printFailed === "boolean" &&
      typeof event.exception === "boolean" &&
      boundedString(event.namespace, 1024) &&
      typeof event.namespaceTruncated === "boolean" &&
      Number.isInteger(event.elapsedMs);
  };

  const validEvalResponse = (value) =>
    exactKeys(value, ["version", "sequence", "namespace", "events"]) &&
    value.version === 1 && canonicalUnsignedDecimal(value.sequence) &&
    exactKeys(value.namespace,
      ["before", "beforeTruncated", "after", "afterTruncated"]) &&
    boundedString(value.namespace.before, 1024) &&
    boundedString(value.namespace.after, 1024) &&
    typeof value.namespace.beforeTruncated === "boolean" &&
    typeof value.namespace.afterTruncated === "boolean" &&
    Array.isArray(value.events) && value.events.length >= 1 &&
    value.events.length <= 3 && value.events.every(validEvalEvent) &&
    value.events[value.events.length - 1].tag === "ret";

  const validRunRegime = (regime) =>
    exactKeys(regime, ["id", "label", "summary", "scope"]) &&
    namespacedIdentifier(regime.id) &&
    typeof regime.label === "string" && regime.label.trim().length > 0 &&
    regime.label.length <= 128 &&
    typeof regime.summary === "string" && regime.summary.trim().length > 0 &&
    regime.summary.length <= 512 &&
    Array.isArray(regime.scope) && regime.scope.length > 0 &&
    regime.scope.length <= 16 &&
    regime.scope.every(namespacedIdentifier) &&
    new Set(regime.scope).size === regime.scope.length;

  const validRunPreset = (preset) =>
    exactKeys(preset, ["id", "label", "profileId", "planEdn", "regimes"]) &&
    namespacedIdentifier(preset.id) &&
    typeof preset.label === "string" && preset.label.trim().length > 0 &&
    typeof preset.profileId === "string" && preset.profileId.length > 0 &&
    typeof preset.planEdn === "string" && preset.planEdn.length > 0 &&
    Array.isArray(preset.regimes) && preset.regimes.length > 0 &&
    preset.regimes.length <= 32 && preset.regimes.every(validRunRegime) &&
    new Set(preset.regimes.map((regime) => regime.id)).size ===
      preset.regimes.length;

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
    "409:session-instance-mismatch",
    "409:session-source-restarted",
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

  const validActivityEvent = (event) =>
    exactKeys(event, ["sequence", "tag", "kind", "summary", "fields", "edn"]) &&
    Number.isSafeInteger(event.sequence) && event.sequence >= 0 &&
    typeof event.tag === "string" && typeof event.kind === "string" &&
    typeof event.summary === "string" && typeof event.edn === "string" &&
    Array.isArray(event.fields) && event.fields.every((field) =>
      exactKeys(field, ["label", "valueEdn"]) &&
      typeof field.label === "string" && typeof field.valueEdn === "string");

  const validNonnegativeInteger = (value) =>
    Number.isSafeInteger(value) && value >= 0;

  const validActivityRecovery = (recovery) =>
    exactKeys(recovery, ["status", "reason", "sequence", "lastGoodOffset",
      "rawTailBytes", "imageTruncated", "class"]) &&
    ["complete", "partial", "failed"].includes(recovery.status) &&
    (recovery.reason === null || typeof recovery.reason === "string") &&
    validNonnegativeInteger(recovery.sequence) && recovery.sequence <= 256 &&
    validNonnegativeInteger(recovery.lastGoodOffset) &&
    validNonnegativeInteger(recovery.rawTailBytes) &&
    typeof recovery.imageTruncated === "boolean" &&
    (recovery.class === null || typeof recovery.class === "string");

  const validActivityObserver = (observer) => {
    if (observer === null) return true;
    if (!exactKeys(observer, ["health", "failure", "sequence", "accepted",
      "capped", "durability", "closed"]) ||
        !["healthy", "failed"].includes(observer.health) ||
        !validNonnegativeInteger(observer.sequence) || observer.sequence > 256 ||
        !validNonnegativeInteger(observer.accepted) ||
        observer.accepted > observer.sequence ||
        typeof observer.capped !== "boolean" ||
        observer.durability !== "process-crash" ||
        typeof observer.closed !== "boolean") return false;
    if (observer.health === "healthy") return observer.failure === null;
    const failure = observer.failure;
    const allowedFailureKeys = new Set(["phase", "reason", "class",
      "payload-length", "max-payload", "count", "remaining",
      "consecutive-eintrs", "max-eintr-retries"]);
    return failure && typeof failure === "object" && !Array.isArray(failure) &&
      Object.keys(failure).length > 0 &&
      Object.keys(failure).every((key) => allowedFailureKeys.has(key)) &&
      typeof failure.phase === "string" && typeof failure.reason === "string" &&
      Object.entries(failure).every(([key, value]) =>
        key === "phase" || key === "reason" || key === "class"
          ? typeof value === "string"
          : validNonnegativeInteger(value));
  };

  const validActivityPage = (page, requestedCursor, nextCursorHeader) => {
    if (!page || page.version !== 1 || typeof page.status !== "string") return false;
    if (!canonicalUnsignedDecimal(requestedCursor) ||
        !canonicalUnsignedDecimal(nextCursorHeader) ||
        Number(requestedCursor) !== page.cursor ||
        Number(nextCursorHeader) !== page.nextCursor) return false;
    if (page.status === "unavailable") {
      return exactKeys(page, ["version", "status", "reason", "cursor", "nextCursor"]) &&
        typeof page.reason === "string" && validNonnegativeInteger(page.cursor) &&
        page.nextCursor === page.cursor;
    }
    if (page.status === "too-large") {
      return exactKeys(page, ["version", "status", "limit", "actual", "cursor", "nextCursor"]) &&
        validNonnegativeInteger(page.limit) && validNonnegativeInteger(page.actual) &&
        validNonnegativeInteger(page.cursor) && validNonnegativeInteger(page.nextCursor) &&
        page.actual > page.limit && page.nextCursor > page.cursor;
    }
    return page.status === "ok" &&
      exactKeys(page, ["version", "status", "cursor", "nextCursor", "acceptedCount",
        "remaining", "events", "recovery", "observer"]) &&
      [page.cursor, page.nextCursor, page.acceptedCount].every(validNonnegativeInteger) &&
      page.cursor <= page.nextCursor && page.nextCursor <= page.acceptedCount &&
      typeof page.remaining === "boolean" && Array.isArray(page.events) &&
      page.events.every((event, index) =>
        validActivityEvent(event) && event.sequence === page.cursor + index) &&
      page.nextCursor === page.cursor + page.events.length &&
      page.remaining === (page.nextCursor < page.acceptedCount) &&
      validActivityRecovery(page.recovery) &&
      page.recovery.sequence === page.acceptedCount &&
      validActivityObserver(page.observer);
  };

  const activityCanAdvance = () => activityPage &&
    ((activityPage.status === "ok" && activityPage.remaining &&
      activityPage.nextCursor > activityPage.cursor) ||
     (activityPage.status === "too-large" &&
      activityPage.nextCursor > activityPage.cursor));

  const syncActivityPageButtons = () => {
    activityPrevious.disabled = activityNavigationBusy || activityPageIndex === 0;
    activityNext.disabled = activityNavigationBusy || !activityCanAdvance();
  };

  const resetActivityPages = () => {
    activityGeneration += 1;
    activityNavigationBusy = false;
    activityCursors = ["0"];
    activityPageIndex = 0;
    activityPage = null;
    activityPanel.hidden = true;
    activityEvents.textContent = "";
    activityRecovery.textContent = "";
    activityPageStatus.textContent = "No retained activity page.";
    syncActivityPageButtons();
  };

  const renderActivityPage = (page) => {
    activityPage = page;
    activityPanel.hidden = false;
    activityEvents.textContent = "";
    if (page.status !== "ok") {
      activityPageStatus.textContent = `Activity page ${page.cursor} is ${page.status}: ${page.reason || "response too large"}.`;
      activityRecovery.textContent = "The replay outcome remains authoritative; retained activity is unavailable for this page.";
      syncActivityPageButtons();
      return;
    }
    page.events.forEach((event) => {
      const item = document.createElement("li");
      item.className = "activity-event";
      item.dataset.testid = "activity-row";
      item.dataset.sequence = String(event.sequence);
      item.dataset.kind = event.kind;
      const header = document.createElement("header");
      const sequence = document.createElement("strong");
      sequence.textContent = `#${event.sequence}`;
      const summary = document.createElement("span");
      summary.textContent = event.summary;
      const kindName = document.createElement("span");
      kindName.className = "activity-event-kind";
      kindName.textContent = event.kind;
      header.append(sequence, summary, kindName);
      item.appendChild(header);
      if (event.fields.length > 0) {
        const fields = document.createElement("dl");
        fields.className = "activity-event-fields";
        event.fields.forEach((field) => {
          const label = document.createElement("dt");
          label.textContent = field.label;
          const value = document.createElement("dd");
          value.textContent = field.valueEdn;
          fields.append(label, value);
        });
        item.appendChild(fields);
      }
      const details = document.createElement("details");
      const detailsLabel = document.createElement("summary");
      detailsLabel.textContent = "Raw canonical EDN";
      const raw = document.createElement("code");
      raw.textContent = event.edn;
      details.append(detailsLabel, raw);
      item.appendChild(details);
      activityEvents.appendChild(item);
    });
    const first = page.events.length > 0 ? page.events[0].sequence : page.cursor;
    const last = page.events.length > 0
      ? page.events[page.events.length - 1].sequence
      : page.cursor;
    activityPageStatus.textContent = page.events.length > 0
      ? `Showing events ${first}–${last} of ${page.acceptedCount}.`
      : `No events at cursor ${page.cursor}; ${page.acceptedCount} accepted.`;
    const recovery = page.recovery;
    activityRecovery.textContent =
      `Recovery ${recovery.status}; observer ${page.observer ? page.observer.health : "not reported"}.`;
    syncActivityPageButtons();
  };

  const renderProgress = (progress, requestedCursor = null,
                          nextCursorHeader = null) => {
    const stdoutText = (progress.stdout && progress.stdout.text) || "";
    const stderrText = (progress.stderr && progress.stderr.text) || "";
    const lines = [`status: ${progress.status}`];
    if (progress.stdout || progress.stderr) {
      lines.push("", "--- stdout ---", stdoutText, "", "--- stderr ---", stderrText);
    }
    activity.textContent = lines.join("\n");
    if (Object.prototype.hasOwnProperty.call(progress, "activity")) {
      if (!validActivityPage(progress.activity, requestedCursor,
                             nextCursorHeader)) {
        activityPanel.hidden = false;
        activityEvents.textContent = "";
        activityPageStatus.textContent = "Rejected malformed retained activity response.";
        activityPage = null;
        syncActivityPageButtons();
      } else {
        renderActivityPage(progress.activity);
      }
    }
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

  const pollProgressOnce = async (generation, authoritative = false,
                                  cursor = activityCursors[activityPageIndex]) => {
    const response = await fetch("/api/replay-progress", {
      method: "GET",
      headers: {
        "X-Jolt-Sim-Capability": capability.value,
        "X-Jolt-Sim-Activity-Cursor": cursor
      },
      cache: "no-store",
      credentials: "omit"
    });
    if (!response.ok) return;
    const progress = await response.json();
    const nextCursorHeader = response.headers.get(
      "X-Jolt-Sim-Activity-Next-Cursor");
    if (generation === pollGeneration &&
        (authoritative || activeProgressStatus(progress.status))) {
      renderProgress(progress, cursor, nextCursorHeader);
    }
    return progress;
  };

  const loadActivityPage = async (index, cursor) => {
    if (busy || activityNavigationBusy) return;
    const generation = activityGeneration;
    activityNavigationBusy = true;
    syncActivityPageButtons();
    try {
      const response = await fetch("/api/replay-progress", {
        method: "GET",
        headers: {
          "X-Jolt-Sim-Capability": capability.value,
          "X-Jolt-Sim-Activity-Cursor": cursor
        },
        cache: "no-store",
        credentials: "omit"
      });
      if (!response.ok) throw new Error(`${response.status} activity page rejected`);
      const progress = await response.json();
      const nextCursorHeader = response.headers.get(
        "X-Jolt-Sim-Activity-Next-Cursor");
      if (!progress.activity ||
          !validActivityPage(progress.activity, cursor, nextCursorHeader)) {
        throw new Error("malformed retained activity response");
      }
      if (generation !== activityGeneration) return;
      activityPageIndex = index;
      renderProgress(progress, cursor, nextCursorHeader);
    } finally {
      if (generation === activityGeneration) {
        activityNavigationBusy = false;
        syncActivityPageButtons();
      }
    }
  };

  activityPrevious.addEventListener("click", async () => {
    if (activityPageIndex === 0) return;
    try {
      await loadActivityPage(activityPageIndex - 1, activityCursors[activityPageIndex - 1]);
    } catch (error) {
      activityPageStatus.textContent = `Could not load previous activity page: ${error.message}`;
    }
  });

  activityNext.addEventListener("click", async () => {
    if (!activityCanAdvance()) return;
    const nextCursor = String(activityPage.nextCursor);
    const nextIndex = activityPageIndex + 1;
    activityCursors[nextIndex] = nextCursor;
    activityCursors.length = nextIndex + 1;
    try {
      await loadActivityPage(nextIndex, nextCursor);
    } catch (error) {
      activityPageStatus.textContent = `Could not load next activity page: ${error.message}`;
    }
  });

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
    capability.disabled = busy || retainedBusy;
    evalForm.disabled = busy;
    evalSubmit.disabled = busy || capability.value.length === 0 ||
      evalForm.value.trim().length === 0;
    inspect.disabled = busy || !ready;
    replay.disabled = busy || !ready || kind.value !== "case-outcome";
    loadRunPresets.disabled = busy || capability.value.length === 0;
    runPreset.disabled = busy || runPresets.length === 0;
    runRegime.disabled = busy || selectedRunPreset === null ||
      selectedRunPreset.regimes.length === 0;
    runNew.disabled = busy || capability.value.length === 0 ||
      selectedRunPreset === null || selectedRunRegime === null;
    // An unresolved ambiguous step outcome blocks a fresh frame read too, so
    // the only available action stays Retry until it resolves or Reset runs.
    sessionRefresh.disabled = busy || capability.value.length === 0 || pendingRetry !== null;
    sessionReset.disabled = busy;
    retainedCommand.disabled = retainedBusy;
    retainedRefresh.disabled = retainedBusy || capability.value.length === 0;
    retainedSend.disabled = retainedBusy || retainedUncertain || retainedTerminationUnknown ||
      capability.value.length === 0 || retainedCommand.value.trim().length === 0 ||
      (retainedCoordinateKnown && retainedWorkerStatus !== "ready");
    retainedReconcile.disabled = retainedBusy || !retainedUncertain ||
      capability.value.length === 0;
    retainedTerminate.disabled = retainedBusy || capability.value.length === 0 ||
      (retainedCoordinateKnown && retainedWorkerStatus === "terminated");
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

  const requestDocument = async (path, documentKind, body) => {
    const response = await fetch(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/edn",
        "X-Jolt-Sim-Capability": capability.value,
        "X-Jolt-Sim-Document-Kind": documentKind
      },
      body,
      cache: "no-store",
      credentials: "omit"
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`${response.status} ${text}`);
    return text;
  };

  const request = (path) => requestDocument(path, kind.value, documentText);

  file.addEventListener("change", async () => {
    documentText = null;
    report.removeAttribute("srcdoc");
    outcome.textContent = "No replay has run.";
    stopPolling();
    activity.textContent = "Idle.";
    resetActivityPages();
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

  const describeRunRegime = (regime) => {
    runRegimeSummary.textContent = regime === null
      ? "No simulation regime selected."
      : `${regime.summary} Scope: ${regime.scope.join(", ")}.`;
  };

  const selectRunRegime = (preset, regimeId) => {
    const regime = preset.regimes.find((candidate) => candidate.id === regimeId);
    selectedRunRegime = regime || null;
    describeRunRegime(selectedRunRegime);
    return selectedRunRegime;
  };

  const installRunPreset = async (preset) => {
    selectedRunPreset = preset;
    selectedRunRegime = null;
    runRegime.textContent = "";
    preset.regimes.forEach((regime) => {
      const option = document.createElement("option");
      option.value = regime.id;
      option.textContent = regime.label;
      runRegime.appendChild(option);
    });
    runRegime.value = preset.regimes[0].id;
    selectRunRegime(preset, preset.regimes[0].id);
    documentText = preset.planEdn;
    kind.value = "experiment-plan";
    file.value = "";
    stopPolling();
    resetActivityPages();
    activity.textContent = "Idle.";
    outcome.textContent = "No fresh run has completed.";
    report.srcdoc = await requestDocument(
      "/api/render", "experiment-plan", preset.planEdn);
    status.textContent =
      `Validated ${preset.label} topology rendered (${preset.profileId}).`;
    runPresetStatus.textContent =
      `${preset.label} is ready. Run new uses the selected server-owned regime.`;
  };

  const clearRunPresets = (message) => {
    runPresets = [];
    selectedRunPreset = null;
    selectedRunRegime = null;
    runPreset.textContent = "";
    const empty = document.createElement("option");
    empty.value = "";
    empty.textContent = "No runnable examples loaded.";
    runPreset.appendChild(empty);
    runRegime.textContent = "";
    const emptyRegime = document.createElement("option");
    emptyRegime.value = "";
    emptyRegime.textContent = "No simulation regimes loaded.";
    runRegime.appendChild(emptyRegime);
    describeRunRegime(null);
    runPresetStatus.textContent = message;
  };

  loadRunPresets.addEventListener("click", async () => {
    if (busy || capability.value.length === 0) return;
    busy = true;
    updateButtons();
    runPresetStatus.textContent = "Loading trusted runnable examples...";
    try {
      const response = await fetch("/api/run-presets", {
        method: "GET",
        headers: {
          "Accept": "application/json",
          "X-Jolt-Sim-Capability": capability.value
        },
        cache: "no-store",
        credentials: "omit"
      });
      const body = await response.json().catch(() => null);
      if (!response.ok || !exactKeys(body, ["version", "presets"]) ||
          body.version !== 2 || !Array.isArray(body.presets) ||
          !body.presets.every(validRunPreset) ||
          new Set(body.presets.map((preset) => preset.id)).size !==
            body.presets.length) {
        throw new Error(`${response.status} malformed runnable-example response`);
      }
      if (body.presets.length === 0) {
        clearRunPresets("No trusted runnable examples are configured.");
        return;
      }
      runPresets = body.presets;
      runPreset.textContent = "";
      runPresets.forEach((preset) => {
        const option = document.createElement("option");
        option.value = preset.id;
        option.textContent = `${preset.label} (${preset.profileId})`;
        runPreset.appendChild(option);
      });
      runPreset.value = runPresets[0].id;
      await installRunPreset(runPresets[0]);
    } catch (error) {
      clearRunPresets(`Could not load runnable examples: ${error.message}`);
    } finally {
      busy = false;
      updateButtons();
    }
  });

  runPreset.addEventListener("change", async () => {
    if (busy) return;
    const preset = runPresets.find((candidate) =>
      candidate.id === runPreset.value);
    if (!preset) {
      selectedRunPreset = null;
      selectedRunRegime = null;
      describeRunRegime(null);
      runPresetStatus.textContent = "Unknown runnable example selected.";
      updateButtons();
      return;
    }
    busy = true;
    updateButtons();
    try {
      await installRunPreset(preset);
    } catch (error) {
      selectedRunPreset = null;
      selectedRunRegime = null;
      describeRunRegime(null);
      runPresetStatus.textContent =
        `Could not render runnable example: ${error.message}`;
    } finally {
      busy = false;
      updateButtons();
    }
  });

  runRegime.addEventListener("change", () => {
    if (busy || selectedRunPreset === null) return;
    if (!selectRunRegime(selectedRunPreset, runRegime.value)) {
      runPresetStatus.textContent = "Unknown simulation regime selected.";
    } else {
      runPresetStatus.textContent =
        `${selectedRunPreset.label} is ready with ${selectedRunRegime.label}.`;
    }
    updateButtons();
  });

  capability.addEventListener("input", updateButtons);
  kind.addEventListener("change", updateButtons);
  evalForm.addEventListener("input", updateButtons);

  const appendEvalEntry = (form, receipt) => {
    const item = document.createElement("li");
    item.className = "eval-entry";
    const header = document.createElement("header");
    const coordinate = document.createElement("strong");
    coordinate.textContent = `#${receipt.sequence} ${receipt.namespace.after}`;
    const timing = document.createElement("span");
    const terminal = receipt.events[receipt.events.length - 1];
    timing.className = "muted";
    timing.textContent = `${terminal.elapsedMs} ms`;
    header.appendChild(coordinate);
    header.appendChild(timing);
    item.appendChild(header);
    const source = document.createElement("pre");
    source.textContent = form;
    source.setAttribute("aria-label", "Evaluated form");
    item.appendChild(source);
    receipt.events.forEach((event) => {
      const output = document.createElement("pre");
      output.dataset.evalTag = event.tag;
      output.textContent = event.tag === "ret" ? event.printedValue : event.text;
      if (event.truncated) output.textContent += "\n… bounded by Ripple";
      if (event.printFailed) output.textContent += "\n… value could not be printed";
      if (event.exception) output.setAttribute("aria-label", "Evaluation exception");
      item.appendChild(output);
    });
    evalTranscript.appendChild(item);
    while (evalTranscript.children.length > 100) {
      evalTranscript.removeChild(evalTranscript.firstElementChild);
    }
    item.scrollIntoView({behavior: "smooth", block: "nearest"});
  };

  const evaluateOnce = async () => {
    if (busy || capability.value.length === 0 ||
        evalForm.value.trim().length === 0) return;
    const form = evalForm.value;
    busy = true;
    updateButtons();
    evalStatus.textContent = "Evaluating one form exactly once...";
    try {
      let response;
      try {
        response = await fetch("/api/eval", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Jolt-Sim-Capability": capability.value
          },
          body: JSON.stringify({version: 1, form}),
          cache: "no-store",
          credentials: "omit"
        });
      } catch (networkError) {
        evalStatus.textContent =
          `Evaluation transport failed; outcome unknown and Ripple will not retry. (${networkError.message})`;
        return;
      }
      const body = await response.json().catch(() => null);
      if (!response.ok) {
        const definite = response.status >= 400 && response.status < 500;
        evalStatus.textContent = definite
          ? `Evaluation rejected before execution (${response.status}).`
          : `Evaluation failed without a recognizable receipt (${response.status}); outcome unknown and Ripple will not retry.`;
        return;
      }
      if (!validEvalResponse(body)) {
        evalStatus.textContent =
          "Evaluation returned an invalid receipt; outcome unknown and Ripple will not retry.";
        return;
      }
      appendEvalEntry(form, body);
      const terminal = body.events[body.events.length - 1];
      evalStatus.textContent = terminal.exception
        ? `Evaluation #${body.sequence} completed with an exception in ${body.namespace.after}.`
        : `Evaluation #${body.sequence} completed in ${body.namespace.after}.`;
    } finally {
      busy = false;
      updateButtons();
    }
  };

  evalSubmit.addEventListener("click", evaluateOnce);
  evalForm.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
      event.preventDefault();
      evaluateOnce();
    }
  });

  const clearSessionClientState = (forgetInstance,
                                   preserveStepStatus = false) => {
    sessionCursor = "0";
    sessionStepEnabled = false;
    sessionChoices = [];
    if (!preserveStepStatus || (!lastStepCommitted && !lastStepUnknown)) {
      lastStepStatus = null;
      lastStepCommitted = false;
      lastStepUnknown = false;
    }
    pendingRetry = null;
    if (forgetInstance) {
      sessionInstanceKnown = false;
      sessionInstanceId = null;
    }
  };

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
      const observedInstanceId = response.headers.get(sessionInstanceHeader);
      if (observedInstanceId !== null &&
          !validSessionInstanceId(observedInstanceId)) {
        clearSessionClientState(true);
        throw new Error("invalid session producer instance header");
      }
      // A configured producer supplies its epoch on every authenticated
      // frame response, including an error. A successful response without
      // the header is the compatible unversioned protocol. Do not interpret
      // an unauthenticated/headerless error as an epoch change.
      const observesInstance = observedInstanceId !== null || response.ok;
      if (observesInstance) {
        const instanceChanged = sessionInstanceKnown &&
          observedInstanceId !== sessionInstanceId;
        sessionInstanceKnown = true;
        sessionInstanceId = observedInstanceId;
        if (instanceChanged) {
          // A recognized receipt remains authoritative even if the automatic
          // post-commit frame refresh reaches a restarted producer. Preserve
          // that acknowledged status, already attributed to the producer that
          // returned it, while discarding every frame-derived coordinate.
          clearSessionClientState(false, true);
          sessionFrame.textContent =
            "No current session frame; the producer instance changed.";
          sessionStatus.textContent =
            "Session producer changed; local cursor, choices, and retry state were reset. Refresh from cursor zero.";
          sessionStepStatus.textContent = lastStepStatus
            ? `${lastStepStatus} The producer then changed; its frame was discarded. Refresh from cursor zero.`
            : "No branch choice sent.";
          return;
        }
      }
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

  const submitStepRequest = (bodyText, instanceId) => {
    const headers = {
      "Content-Type": "application/json",
      "Accept": "application/json",
      "X-Jolt-Sim-Capability": capability.value
    };
    if (instanceId !== null) headers[sessionInstanceHeader] = instanceId;
    return fetch("/api/session-step", {
      method: "POST",
      headers,
      body: bodyText,
      cache: "no-store",
      credentials: "omit"
    });
  };

  const performStep = async (bodyText, isRetry, instanceId) => {
    busy = true;
    if (!isRetry) {
      lastStepStatus = null;
      lastStepCommitted = false;
      lastStepUnknown = false;
    }
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
        response = await submitStepRequest(bodyText, instanceId);
      } catch (networkError) {
        sessionStepStatus.textContent =
          `Network failure without receiving a server acknowledgment; not confirmed committed. ` +
          `(${networkError.message}) Retry sends the identical command bytes.`;
        return;
      }
      receipt = await response.json().catch(() => null);
      const responseInstance = response.headers.get(sessionInstanceHeader);
      // A receipt is authoritative only when its response proves that it came
      // from the same producer epoch as the displayed branch coordinate. The
      // cached non-null instance was already validated while reading the
      // frame; exact equality therefore also enforces the closed syntax.
      const receiptInstanceMatches = instanceId === null
        ? responseInstance === null
        : responseInstance === instanceId;
      const jsonResponse = (response.headers.get("Content-Type") || "")
        .toLowerCase().startsWith("application/json");
      const exactCoordinate = receipt &&
        receipt.revision === command.revision &&
        receipt.kind === command.kind &&
        receipt.value === command.value;
      const committedReceipt = receiptInstanceMatches &&
        jsonResponse && response.ok &&
        exactKeys(receipt, ["version", "outcome", "committed", "revision", "kind",
          "value", "receiptEdn"]) &&
        receipt.version === 1 && receipt.outcome === "committed" &&
        receipt.committed === true && exactCoordinate &&
        typeof receipt.receiptEdn === "string";
      const staleReceipt = receiptInstanceMatches &&
        jsonResponse && response.status === 409 &&
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
        lastStepCommitted = true;
        lastStepUnknown = false;
        const producer = instanceId === null
          ? "the unversioned session producer"
          : `session producer ${instanceId}`;
        lastStepStatus =
          `Committed on ${producer}: revision ${receipt.revision} ` +
          `(${receipt.kind} ${receipt.value}); ` +
          `server acknowledged the exact command.`;
        sessionStepStatus.textContent = `${lastStepStatus} Refreshing session frame.`;
      } else if (staleReceipt) {
        outcome = isRetry ? "unresolved" : "stale";
        lastStepCommitted = false;
        lastStepUnknown = isRetry;
        lastStepStatus = isRetry
          ? `The explicit retry was stale; the original command outcome remains unknown. ` +
            `Reconcile or inspect the journal from the original cursor before proceeding.`
          : `Not committed (${receipt.outcome}); refresh the session for current choices.`;
        sessionStepStatus.textContent = lastStepStatus;
      } else if (rejectedReceipt) {
        outcome = isRetry ? "unresolved" : "rejected";
        lastStepCommitted = false;
        lastStepUnknown = isRetry;
        lastStepStatus = isRetry
          ? `The explicit retry was rejected (${receipt.error}); the original command outcome remains unknown. ` +
            `Reconcile or inspect the journal from the original cursor before proceeding.`
          : `Not committed (${receipt.error}); refresh after correcting the request or capability.`;
        sessionStepStatus.textContent = lastStepStatus;
      } else {
        sessionStepStatus.textContent =
          `Unacknowledged: server returned ${response.status} without a recognizable receipt; ` +
          `not confirmed committed. Retry sends the identical command bytes.`;
      }
    } finally {
      pendingRetry = outcome === "ambiguous" ? {bodyText, instanceId} : null;
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
    return performStep(bodyText, false, sessionInstanceId);
  };

  sessionStepRetry.addEventListener("click", () => {
    if (busy || pendingRetry === null) return;
    performStep(pendingRetry.bodyText, true, pendingRetry.instanceId);
  });

  sessionReset.addEventListener("click", () => {
    clearSessionClientState(true);
    sessionFrame.textContent = "No current session frame; refresh from cursor zero.";
    sessionStatus.textContent = "Journal cursor reset; refresh to read from the beginning.";
    sessionStepStatus.textContent = "No branch choice sent.";
    updateButtons();
  });

  const retainedHeaders = () => ({
    "Content-Type": "application/json",
    "Accept": "application/json",
    "X-Jolt-Sim-Capability": capability.value
  });

  const observeRetainedCoordinate = (coordinate) => {
    retainedUncertain = coordinate.uncertainSequence !== null;
    retainedTerminationUnknown = false;
    retainedCoordinateKnown = true;
    retainedWorkerStatus = coordinate.status;
  };

  const refreshRetainedFrame = async ({preserveStatus = false} = {}) => {
    if (retainedBusy) return;
    retainedBusy = true;
    updateButtons();
    if (!preserveStatus) {
      retainedStatus.textContent = "Reading retained worker coordinates...";
    }
    try {
      const response = await fetch("/api/retained-frame", {
        method: "GET",
        headers: {
          "Accept": "application/json",
          "X-Jolt-Sim-Capability": capability.value
        },
        cache: "no-store",
        credentials: "omit"
      });
      const body = await response.json().catch(() => null);
      if (!response.ok || !validRetainedFrameResponse(body)) {
        throw new Error(`${response.status} ${body ? JSON.stringify(body) : "unparseable response"}`);
      }
      observeRetainedCoordinate(body.coordinate);
      retainedFrame.textContent = body.frameEdn;
      if (!preserveStatus) {
        retainedStatus.textContent = retainedUncertain
          ? `Worker is ${body.coordinate.status}; command sequence ${body.coordinate.uncertainSequence} is uncertain.`
          : `Worker is ${body.coordinate.status}; next command sequence is ${body.coordinate.nextSequence}.`;
      }
    } catch (error) {
      retainedFrame.textContent =
        "No current retained worker frame; the last refresh failed.";
      if (!preserveStatus) {
        retainedStatus.textContent = `Retained worker refresh failed: ${error.message}`;
      }
    } finally {
      retainedBusy = false;
      updateButtons();
    }
  };

  const retainedControl = async (path, body, operation) => {
    if (retainedBusy) return;
    retainedBusy = true;
    updateButtons();
    retainedStatus.textContent = `${operation} in progress; Ripple will not retry it automatically...`;
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: retainedHeaders(),
        body: JSON.stringify(body),
        cache: "no-store",
        credentials: "omit"
      });
      const result = await response.json().catch(() => null);
      if (response.status === 503 && validRetainedTransportResponse(result)) {
        retainedUncertain = result.uncertainSequence !== null;
        retainedTerminationUnknown = operation === "Termination";
        retainedCoordinateKnown = result.status !== null;
        retainedWorkerStatus = result.status;
        retainedStatus.textContent = retainedUncertain
          ? `${operation} has an uncertain transport outcome at sequence ${result.uncertainSequence}; reconcile or terminate, but do not resend.`
          : `${operation} failed before admission (${result.reason}); no command was acknowledged.`;
        retainedReceipt.textContent = JSON.stringify(result, null, 2);
        return;
      }
      const definiteResult = operation === "Termination"
        ? validRetainedTerminateResponse(result)
        : validRetainedOutcomeResponse(result);
      if (!response.ok || !definiteResult) {
        const error = new Error(
          `${response.status} ${result ? JSON.stringify(result) : "unparseable response"}`
        );
        error.retainedNotAdmitted = retainedNotAdmittedStatuses.has(response.status);
        throw error;
      }
      observeRetainedCoordinate(result.coordinate);
      if (operation === "Termination") {
        retainedReceipt.textContent = "No application receipt; termination was acknowledged.";
      } else {
        retainedReceipt.textContent = result.receiptEdn === null
          ? result.truncated
            ? `Receipt ${result.sequence} committed, but its body exceeded the configured browser projection limit.`
            : "No application receipt body."
          : result.receiptEdn;
      }
      if (result.frameEdn) retainedFrame.textContent = result.frameEdn;
      if (!result.frameEdn && operation !== "Termination") {
        retainedFrame.textContent = result.truncated
          ? "The command receipt is definite, but the resulting frame exceeded the browser projection limit. Refresh to inspect it."
          : "The command receipt is definite, but its post-receipt frame was unavailable. Refresh to inspect current state.";
      }
      retainedStatus.textContent = result.outcome === "failed"
        ? "Application failure was acknowledged definitively; the command must not be retried."
        : `${operation} acknowledged with outcome ${result.outcome}.`;
      if (result.outcome === "terminated") {
        retainedFrame.textContent = result.frameEdn ||
          "The attached retained worker was terminated; its terminal frame exceeded the browser projection limit.";
      }
    } catch (error) {
      if (!error.retainedNotAdmitted) {
        if (operation === "Termination") {
          retainedTerminationUnknown = true;
        } else {
          retainedUncertain = true;
        }
      }
      retainedStatus.textContent = error.retainedNotAdmitted
        ? `${operation} was not admitted: ${error.message}.`
        : `${operation} returned no recognizable acknowledgment: ${error.message}. Do not retry automatically.`;
    } finally {
      retainedBusy = false;
      updateButtons();
    }
  };

  retainedRefresh.addEventListener("click", () => refreshRetainedFrame());
  retainedSend.addEventListener("click", () => retainedControl(
    "/api/retained-command",
    {version: 1, commandEdn: retainedCommand.value},
    "Retained command"
  ));
  retainedReconcile.addEventListener("click", () => retainedControl(
    "/api/retained-reconcile", {version: 1}, "Reconciliation"
  ));
  retainedTerminate.addEventListener("click", () => retainedControl(
    "/api/retained-terminate", {version: 1}, "Termination"
  ));
  retainedCommand.addEventListener("input", updateButtons);

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

  const performFreshRun = async ({startMessage, completedMessage,
                                  failedPrefix, requestRun}) => {
    if (busy) return;
    busy = true;
    updateButtons();
    status.textContent = startMessage;
    activity.textContent = "status: starting";
    resetActivityPages();
    // Initiate the authoritative run POST before polling. Non-final polls
    // additionally ignore idle/terminal snapshots, since separate HTTP
    // connections do not guarantee server arrival order.
    const runRequest = requestRun();
    startPolling();
    let requestReturned = false;
    let finalProgress = null;
    try {
      outcome.textContent = await runRequest;
      requestReturned = true;
    } catch (error) {
      outcome.textContent = "No fresh run outcome returned.";
      status.textContent = `${failedPrefix}: ${error.message}`;
    } finally {
      stopPolling();
      try {
        finalProgress = await pollProgressOnce(pollGeneration, true);
      } catch (error) {
        // Best-effort final snapshot fetch.
      }
      if (requestReturned) {
        if (finalProgress && finalProgress.status === "completed") {
          status.textContent = completedMessage;
        } else {
          const terminal = finalProgress && typeof finalProgress.status === "string"
            ? finalProgress.status
            : "unavailable";
          status.textContent =
            `${failedPrefix}: terminal process status ${terminal}; raw outcome preserved below.`;
        }
      }
      busy = false;
      updateButtons();
    }
    return requestReturned && finalProgress !== null &&
      finalProgress.status === "completed";
  };

  replay.addEventListener("click", () => performFreshRun({
    startMessage: "Running one fresh-process replay...",
    completedMessage: "Fresh replay completed; raw outcome preserved below.",
    failedPrefix: "Replay failed",
    requestRun: () => request("/api/replay")
  }));

  runNew.addEventListener("click", () => {
    if (busy || selectedRunPreset === null || selectedRunRegime === null) return;
    const preset = selectedRunPreset;
    const regime = selectedRunRegime;
    runPresetStatus.textContent =
      `Running ${preset.label} with ${regime.label}...`;
    return performFreshRun({
      startMessage: `Running ${preset.label} in one fresh process...`,
      completedMessage:
        `${preset.label} completed; raw outcome and retained activity are available.`,
      failedPrefix: `${preset.label} failed`,
      requestRun: async () => {
        const response = await fetch("/api/run", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Accept": "application/edn",
            "X-Jolt-Sim-Capability": capability.value
          },
          body: JSON.stringify({version: 2,
            presetId: preset.id, regimeId: regime.id}),
          cache: "no-store",
          credentials: "omit"
        });
        const text = await response.text();
        if (!response.ok) throw new Error(`${response.status} ${text}`);
        return text;
      }
    }).then((completed) => {
      runPresetStatus.textContent = selectedRunPreset === preset &&
        selectedRunRegime === regime
        ? (completed
          ? `${preset.label} / ${regime.label} is ready. Run new starts another fresh process.`
          : `${preset.label} / ${regime.label} did not complete; retained evidence remains available.`)
        : runPresetStatus.textContent;
    });
  });
})();
