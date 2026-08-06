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
  let documentText = null;
  let busy = false;

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
