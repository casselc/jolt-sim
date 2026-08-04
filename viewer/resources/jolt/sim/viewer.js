(() => {
  "use strict";

  const file = document.getElementById("case-file");
  const capability = document.getElementById("capability");
  const inspect = document.getElementById("inspect");
  const replay = document.getElementById("replay");
  const status = document.getElementById("status");
  const report = document.getElementById("report");
  const outcome = document.getElementById("outcome");
  let documentText = null;

  const updateButtons = () => {
    const ready = typeof documentText === "string" && capability.value.length > 0;
    inspect.disabled = !ready;
    replay.disabled = !ready;
  };

  const request = async (path) => {
    const response = await fetch(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/edn",
        "X-Jolt-Sim-Capability": capability.value
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

  inspect.addEventListener("click", async () => {
    inspect.disabled = true;
    replay.disabled = true;
    status.textContent = "Validating and rendering retained evidence...";
    try {
      report.srcdoc = await request("/api/render");
      status.textContent = "Validated report rendered.";
    } catch (error) {
      status.textContent = `Inspect failed: ${error.message}`;
    } finally {
      updateButtons();
    }
  });

  replay.addEventListener("click", async () => {
    inspect.disabled = true;
    replay.disabled = true;
    status.textContent = "Running one fresh-process replay...";
    try {
      outcome.textContent = await request("/api/replay");
      status.textContent = "Fresh replay completed; raw outcome preserved below.";
    } catch (error) {
      outcome.textContent = "No replay outcome returned.";
      status.textContent = `Replay failed: ${error.message}`;
    } finally {
      updateButtons();
    }
  });
})();
