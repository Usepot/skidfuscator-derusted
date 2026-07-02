(() => {
  const data = window.CLASS_WEB_DATA;
  if (!data) {
    document.body.innerHTML = "<main class=\"empty-state\">class-data.js is missing. Run scripts/generate-class-web.mjs.</main>";
    return;
  }

  const STATUS = {
    seed: { label: "Seed threaded", color: "#48a7f3" },
    aware: { label: "Thread aware", color: "#45c985" },
    review: { label: "Review", color: "#e7bd4a" },
    neutral: { label: "Neutral", color: "#7d778d" },
  };

  const EDGE_COLOR = {
    extends: "rgba(126, 205, 255, 0.38)",
    implements: "rgba(116, 224, 154, 0.30)",
    nested: "rgba(212, 163, 255, 0.26)",
    import: "rgba(180, 184, 205, 0.10)",
    reference: "rgba(180, 184, 205, 0.08)",
  };

  const state = {
    query: "",
    statuses: new Set(Object.keys(STATUS)),
    sources: new Set(["main", "test", "misc"]),
    restrictions: new Set(),
    packagePrefix: "",
    selectedId: null,
    hoveredId: null,
    paused: false,
    scale: 1,
    offsetX: 0,
    offsetY: 0,
    isPanning: false,
    draggingNode: null,
    lastPointer: null,
  };

  const canvas = document.getElementById("graphCanvas");
  const ctx = canvas.getContext("2d");
  const tooltip = document.getElementById("graphTooltip");
  const searchInput = document.getElementById("searchInput");
  const statusFilters = document.getElementById("statusFilters");
  const sourceFilters = document.getElementById("sourceFilters");
  const restrictionFilters = document.getElementById("restrictionFilters");
  const packageList = document.getElementById("packageList");
  const inspectorBody = document.getElementById("inspectorBody");
  const inspectorTitle = document.getElementById("inspectorTitle");
  const inspectorSubtitle = document.getElementById("inspectorSubtitle");
  const visibleSummary = document.getElementById("visibleSummary");
  const pauseButton = document.getElementById("pauseGraph");

  const nodes = data.classes.map((item, index) => ({
    ...item,
    index,
    x: 0,
    y: 0,
    vx: 0,
    vy: 0,
    visible: true,
    radius: radiusFor(item),
  }));
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const edges = data.edges
    .map((edge) => ({
      ...edge,
      sourceNode: nodeById.get(edge.source),
      targetNode: nodeById.get(edge.target),
    }))
    .filter((edge) => edge.sourceNode && edge.targetNode);

  const packageCounts = new Map();
  for (const node of nodes) {
    packageCounts.set(node.packageName, (packageCounts.get(node.packageName) ?? 0) + 1);
  }

  let visibleNodes = nodes;
  let visibleEdges = edges;
  let moduleCenters = new Map();
  let dpr = 1;

  function radiusFor(node) {
    const base = 3.8 + Math.sqrt(Math.max(1, node.methodCount)) * 0.8;
    const restrictionBoost = Math.min(3, (node.counts?.restricted ?? 0) * 0.18);
    return Math.min(14, base + restrictionBoost);
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function labelForStatus(status) {
    return STATUS[status]?.label ?? status;
  }

  function restrictionCount(node) {
    return node.counts?.restricted ?? 0;
  }

  function methodHasRestriction(method, key) {
    return method.evidence?.restrictions?.some((hit) => hit.key === key);
  }

  function nodeMatches(node) {
    if (!state.statuses.has(node.threadStatus)) return false;
    if (!state.sources.has(node.sourceSet)) return false;
    if (state.packagePrefix && !node.packageName.startsWith(state.packagePrefix)) return false;

    if (state.query) {
      const query = state.query.toLowerCase();
      const methodHit = node.methods.some((method) =>
        method.name.toLowerCase().includes(query) || method.signature.toLowerCase().includes(query)
      );
      const classHit = [
        node.id,
        node.simpleName,
        node.packageName,
        node.module,
        node.file,
      ].some((value) => String(value ?? "").toLowerCase().includes(query));
      if (!classHit && !methodHit) return false;
    }

    if (state.restrictions.size > 0) {
      const keys = new Set(node.restrictionTypes.map((entry) => entry.key));
      let matched = false;
      for (const key of state.restrictions) {
        if (keys.has(key)) {
          matched = true;
          break;
        }
      }
      if (!matched) return false;
    }

    return true;
  }

  function refreshVisible() {
    visibleNodes = [];
    for (const node of nodes) {
      node.visible = nodeMatches(node);
      if (node.visible) visibleNodes.push(node);
    }
    visibleEdges = edges.filter((edge) => edge.sourceNode.visible && edge.targetNode.visible);
    visibleSummary.textContent = `${visibleNodes.length.toLocaleString()} visible nodes, ${visibleEdges.length.toLocaleString()} visible links`;
    renderInspector();
  }

  function renderStats() {
    document.getElementById("statClasses").textContent = data.totals.classes.toLocaleString();
    document.getElementById("statMethods").textContent = data.totals.methods.toLocaleString();
    document.getElementById("statThreaded").textContent = (data.totals.seedThreadedMethods + data.totals.threadAwareMethods).toLocaleString();
    document.getElementById("statRestricted").textContent = data.totals.restrictedMethods.toLocaleString();
  }

  function renderStatusFilters() {
    statusFilters.innerHTML = Object.entries(STATUS).map(([key, meta]) => `
      <button class="chip" type="button" data-status="${key}" aria-pressed="${state.statuses.has(key)}">
        <i class="${key}" aria-hidden="true"></i>${meta.label}
      </button>
    `).join("");

    statusFilters.querySelectorAll("[data-status]").forEach((button) => {
      button.addEventListener("click", () => {
        const key = button.dataset.status;
        if (state.statuses.has(key) && state.statuses.size > 1) state.statuses.delete(key);
        else state.statuses.add(key);
        renderStatusFilters();
        refreshVisible();
      });
    });
  }

  function renderSourceFilters() {
    const sourceCounts = new Map();
    for (const node of nodes) {
      sourceCounts.set(node.sourceSet, (sourceCounts.get(node.sourceSet) ?? 0) + 1);
    }
    sourceFilters.innerHTML = [...sourceCounts.entries()].sort().map(([key, count]) => `
      <label class="source-check">
        <input type="checkbox" data-source="${key}" ${state.sources.has(key) ? "checked" : ""}>
        ${escapeHtml(key)} <span>${count}</span>
      </label>
    `).join("");

    sourceFilters.querySelectorAll("[data-source]").forEach((input) => {
      input.addEventListener("change", () => {
        const key = input.dataset.source;
        if (input.checked) state.sources.add(key);
        else if (state.sources.size > 1) state.sources.delete(key);
        else input.checked = true;
        refreshVisible();
      });
    });
  }

  function renderRestrictionFilters() {
    const entries = data.restrictionTypes.slice(0, 18);
    restrictionFilters.innerHTML = entries.map((entry) => `
      <button class="chip compact" type="button" data-restriction="${entry.key}" aria-pressed="${state.restrictions.has(entry.key)}">
        ${escapeHtml(entry.key)} <span>${entry.count}</span>
      </button>
    `).join("");

    restrictionFilters.querySelectorAll("[data-restriction]").forEach((button) => {
      button.addEventListener("click", () => {
        const key = button.dataset.restriction;
        if (state.restrictions.has(key)) state.restrictions.delete(key);
        else state.restrictions.add(key);
        renderRestrictionFilters();
        refreshVisible();
      });
    });
  }

  function renderPackages() {
    const packages = [...packageCounts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    packageList.innerHTML = [
      ["", "All packages", nodes.length],
      ...packages.slice(0, 180).map(([pkg, count]) => [pkg, pkg || "(default)", count]),
    ].map(([key, label, count]) => `
      <button class="package-item ${state.packagePrefix === key ? "active" : ""}" type="button" data-package="${escapeHtml(key)}">
        <span>${escapeHtml(label)}</span><span>${count}</span>
      </button>
    `).join("");

    packageList.querySelectorAll("[data-package]").forEach((button) => {
      button.addEventListener("click", () => {
        state.packagePrefix = button.dataset.package;
        renderPackages();
        refreshVisible();
        fitGraph();
      });
    });
  }

  function renderInspector() {
    const selected = nodeById.get(state.selectedId);
    if (!selected || !selected.visible) {
      state.selectedId = null;
      inspectorTitle.textContent = "Selection";
      inspectorSubtitle.textContent = "Static evidence dashboard";
      inspectorBody.innerHTML = renderOverview();
      wireHotItems();
      return;
    }

    inspectorTitle.textContent = selected.simpleName;
    inspectorSubtitle.textContent = selected.packageName || "(default package)";
    inspectorBody.innerHTML = renderClassDetail(selected);
  }

  function renderOverview() {
    const restricted = [...visibleNodes]
      .filter((node) => restrictionCount(node) > 0)
      .sort((a, b) => restrictionCount(b) - restrictionCount(a) || b.methodCount - a.methodCount)
      .slice(0, 10);
    const threaded = [...visibleNodes]
      .filter((node) => node.threadStatus === "seed" || node.threadStatus === "aware" || node.threadStatus === "review")
      .sort((a, b) => statusWeight(b.threadStatus) - statusWeight(a.threadStatus) || b.methodCount - a.methodCount)
      .slice(0, 10);

    return `
      <div class="detail-block">
        <dl class="detail-grid">
          <dt>Generated</dt><dd>${escapeHtml(new Date(data.generatedAt).toLocaleString())}</dd>
          <dt>Modules</dt><dd>${data.modules.length}</dd>
          <dt>Packages</dt><dd>${data.packages.length}</dd>
          <dt>Edges</dt><dd>${data.totals.edges.toLocaleString()}</dd>
        </dl>
      </div>
      <div class="section-label">Restriction Hotspots</div>
      <div class="hot-list">
        ${restricted.map((node) => hotItem(node, `${restrictionCount(node)} methods`)).join("") || "<div class=\"empty-state\">No restricted methods in view.</div>"}
      </div>
      <div class="section-label">Threading Evidence</div>
      <div class="hot-list">
        ${threaded.map((node) => hotItem(node, labelForStatus(node.threadStatus))).join("") || "<div class=\"empty-state\">No threading evidence in view.</div>"}
      </div>
    `;
  }

  function statusWeight(status) {
    return { review: 4, seed: 3, aware: 2, neutral: 1 }[status] ?? 0;
  }

  function hotItem(node, meta) {
    return `
      <button class="hot-item" type="button" data-select="${escapeHtml(node.id)}">
        <span>${escapeHtml(node.id)}</span><b>${escapeHtml(meta)}</b>
      </button>
    `;
  }

  function wireHotItems() {
    inspectorBody.querySelectorAll("[data-select]").forEach((button) => {
      button.addEventListener("click", () => {
        selectNode(button.dataset.select);
      });
    });
  }

  function renderClassDetail(node) {
    const methods = [...node.methods].sort((a, b) =>
      Number(b.restricted) - Number(a.restricted)
      || statusWeight(b.threadStatus) - statusWeight(a.threadStatus)
      || a.line - b.line
    );
    const restrictionTags = node.restrictionTypes
      .slice(0, 8)
      .map((entry) => `<span class="tag restricted">${escapeHtml(entry.key)} ${entry.count}</span>`)
      .join("");

    return `
      <div class="detail-block">
        <dl class="detail-grid">
          <dt>File</dt><dd>${escapeHtml(node.file)}:${node.line}</dd>
          <dt>Module</dt><dd>${escapeHtml(node.module)}</dd>
          <dt>Kind</dt><dd>${escapeHtml(node.kind)}</dd>
          <dt>Methods</dt><dd>${node.methodCount}</dd>
          <dt>Extends</dt><dd>${escapeHtml(node.extends.join(", ") || "none")}</dd>
          <dt>Implements</dt><dd>${escapeHtml(node.implements.join(", ") || "none")}</dd>
        </dl>
        <div class="status-line">
          <span class="tag ${node.threadStatus}">${labelForStatus(node.threadStatus)}</span>
          ${restrictionCount(node) ? `<span class="tag restricted">${restrictionCount(node)} restricted methods</span>` : ""}
          ${restrictionTags}
        </div>
      </div>
      <div class="section-label">Methods</div>
      <div class="method-list">
        ${methods.map(renderMethod).join("") || "<div class=\"empty-state\">No methods detected.</div>"}
      </div>
    `;
  }

  function renderMethod(method) {
    const tags = [
      `<span class="tag ${method.threadStatus}">${labelForStatus(method.threadStatus)}</span>`,
      method.restricted ? "<span class=\"tag restricted\">restricted</span>" : "",
      method.modifiers.slice(0, 4).map((modifier) => `<span class="tag">${escapeHtml(modifier)}</span>`).join(""),
    ].join("");

    const evidence = [
      ...evidenceRows("seed", method.evidence?.seed),
      ...evidenceRows("safe", method.evidence?.safe),
      ...evidenceRows("async", method.evidence?.async),
      ...evidenceRows("restriction", method.evidence?.restrictions),
    ].slice(0, 8).join("");

    return `
      <article class="method-row">
        <header>
          <div class="method-name">${escapeHtml(method.name)}</div>
          <div class="method-line">L${method.line}</div>
        </header>
        <div class="signature">${escapeHtml(method.signature)}</div>
        <div class="status-line">${tags}</div>
        ${evidence ? `<div class="evidence">${evidence}</div>` : ""}
      </article>
    `;
  }

  function evidenceRows(kind, hits = []) {
    return hits.map((hit) => `
      <div class="${methodHasRestriction({ evidence: { restrictions: hits } }, hit.key) ? "restricted" : ""}">
        <b>${escapeHtml(kind)} L${hit.line}</b>
        <span>${escapeHtml(hit.label)}: ${escapeHtml(hit.snippet || hit.match)}</span>
      </div>
    `);
  }

  function selectNode(id) {
    state.selectedId = id;
    const node = nodeById.get(id);
    if (node) {
      node.x = Number.isFinite(node.x) ? node.x : 0;
      node.y = Number.isFinite(node.y) ? node.y : 0;
    }
    renderInspector();
  }

  function resizeCanvas() {
    const rect = canvas.getBoundingClientRect();
    dpr = Math.max(1, Math.min(2, window.devicePixelRatio || 1));
    canvas.width = Math.round(rect.width * dpr);
    canvas.height = Math.round(rect.height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    computeModuleCenters();
  }

  function computeModuleCenters() {
    const rect = canvas.getBoundingClientRect();
    const modules = data.modules;
    const radius = Math.max(160, Math.min(rect.width, rect.height) * 0.34);
    moduleCenters = new Map();
    modules.forEach((module, index) => {
      const angle = (Math.PI * 2 * index) / Math.max(1, modules.length) - Math.PI / 2;
      moduleCenters.set(module, {
        x: rect.width / 2 + Math.cos(angle) * radius,
        y: rect.height / 2 + Math.sin(angle) * radius,
      });
    });
  }

  function initializeLayout() {
    resizeCanvas();
    for (const node of nodes) {
      const center = moduleCenters.get(node.module) ?? { x: 300, y: 300 };
      const hash = hashCode(node.id);
      const angle = (hash % 628) / 100;
      const distance = 24 + ((hash >>> 3) % 210);
      node.x = center.x + Math.cos(angle) * distance;
      node.y = center.y + Math.sin(angle) * distance;
      node.vx = 0;
      node.vy = 0;
    }
    fitGraph();
  }

  function hashCode(value) {
    let hash = 0;
    for (let i = 0; i < value.length; i += 1) {
      hash = ((hash << 5) - hash + value.charCodeAt(i)) | 0;
    }
    return Math.abs(hash);
  }

  function tick() {
    if (visibleNodes.length === 0) return;
    applyLinkForces();
    applyRepulsion();
    applyClusterForce();

    for (const node of visibleNodes) {
      if (state.draggingNode === node) continue;
      node.vx *= 0.82;
      node.vy *= 0.82;
      node.x += clamp(node.vx, -9, 9);
      node.y += clamp(node.vy, -9, 9);
    }
  }

  function applyLinkForces() {
    const maxEdges = Math.min(visibleEdges.length, 3200);
    for (let i = 0; i < maxEdges; i += 1) {
      const edge = visibleEdges[i];
      const a = edge.sourceNode;
      const b = edge.targetNode;
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const dist = Math.max(1, Math.hypot(dx, dy));
      const desired = edge.type === "extends" ? 72 : edge.type === "implements" ? 84 : edge.type === "nested" ? 58 : 120;
      const force = (dist - desired) * 0.0024 * Math.min(4, edge.weight);
      const fx = (dx / dist) * force;
      const fy = (dy / dist) * force;
      a.vx += fx;
      a.vy += fy;
      b.vx -= fx;
      b.vy -= fy;
    }
  }

  function applyRepulsion() {
    const cellSize = 88;
    const grid = new Map();
    for (const node of visibleNodes) {
      const key = `${Math.floor(node.x / cellSize)},${Math.floor(node.y / cellSize)}`;
      if (!grid.has(key)) grid.set(key, []);
      grid.get(key).push(node);
    }

    for (const node of visibleNodes) {
      const cx = Math.floor(node.x / cellSize);
      const cy = Math.floor(node.y / cellSize);
      for (let gx = cx - 1; gx <= cx + 1; gx += 1) {
        for (let gy = cy - 1; gy <= cy + 1; gy += 1) {
          const bucket = grid.get(`${gx},${gy}`);
          if (!bucket) continue;
          for (const other of bucket) {
            if (other.index <= node.index) continue;
            let dx = other.x - node.x;
            let dy = other.y - node.y;
            let dist = Math.hypot(dx, dy);
            if (dist < 0.01) {
              dx = 0.01;
              dy = 0.01;
              dist = 0.014;
            }
            const minDist = node.radius + other.radius + 16;
            if (dist < minDist) {
              const push = (minDist - dist) * 0.025;
              const fx = (dx / dist) * push;
              const fy = (dy / dist) * push;
              node.vx -= fx;
              node.vy -= fy;
              other.vx += fx;
              other.vy += fy;
            }
          }
        }
      }
    }
  }

  function applyClusterForce() {
    for (const node of visibleNodes) {
      const center = moduleCenters.get(node.module);
      if (!center) continue;
      node.vx += (center.x - node.x) * 0.0009;
      node.vy += (center.y - node.y) * 0.0009;
    }
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function draw() {
    const rect = canvas.getBoundingClientRect();
    ctx.clearRect(0, 0, rect.width, rect.height);
    ctx.save();
    ctx.translate(state.offsetX, state.offsetY);
    ctx.scale(state.scale, state.scale);

    drawEdges();
    drawNodes();

    ctx.restore();
  }

  function drawEdges() {
    ctx.lineCap = "round";
    const maxEdges = Math.min(visibleEdges.length, 5000);
    for (let i = maxEdges - 1; i >= 0; i -= 1) {
      const edge = visibleEdges[i];
      const a = edge.sourceNode;
      const b = edge.targetNode;
      const selected = state.selectedId && (edge.source === state.selectedId || edge.target === state.selectedId);
      ctx.strokeStyle = selected ? "rgba(235, 238, 255, 0.42)" : (EDGE_COLOR[edge.type] ?? EDGE_COLOR.reference);
      ctx.lineWidth = selected ? 1.6 / state.scale : Math.max(0.45, Math.min(1.6, edge.weight * 0.18)) / state.scale;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }
  }

  function drawNodes() {
    const showLabels = state.scale > 1.25 && visibleNodes.length < 220;
    const hovered = nodeById.get(state.hoveredId);
    const selected = nodeById.get(state.selectedId);
    for (const node of visibleNodes) {
      const r = node.radius;
      ctx.beginPath();
      ctx.fillStyle = STATUS[node.threadStatus]?.color ?? STATUS.neutral.color;
      ctx.arc(node.x, node.y, r, 0, Math.PI * 2);
      ctx.fill();

      if (restrictionCount(node) > 0) {
        ctx.strokeStyle = "#d859a9";
        ctx.lineWidth = 2.2 / state.scale;
        ctx.stroke();
      } else {
        ctx.strokeStyle = "rgba(255,255,255,0.18)";
        ctx.lineWidth = 1 / state.scale;
        ctx.stroke();
      }

      if (node === selected || node === hovered) {
        ctx.strokeStyle = node === selected ? "#f5f4ff" : "rgba(245,244,255,0.72)";
        ctx.lineWidth = (node === selected ? 3.2 : 2.3) / state.scale;
        ctx.beginPath();
        ctx.arc(node.x, node.y, r + 4 / state.scale, 0, Math.PI * 2);
        ctx.stroke();
      }
    }

    if (showLabels || selected || hovered) {
      ctx.font = `${11 / state.scale}px system-ui, -apple-system, sans-serif`;
      ctx.textBaseline = "middle";
      ctx.fillStyle = "rgba(242, 244, 255, 0.88)";
      const labelNodes = showLabels ? visibleNodes : [selected, hovered].filter(Boolean);
      for (const node of labelNodes) {
        ctx.fillText(node.simpleName, node.x + node.radius + 5 / state.scale, node.y);
      }
    }
  }

  function toWorld(event) {
    const rect = canvas.getBoundingClientRect();
    return {
      x: (event.clientX - rect.left - state.offsetX) / state.scale,
      y: (event.clientY - rect.top - state.offsetY) / state.scale,
      screenX: event.clientX - rect.left,
      screenY: event.clientY - rect.top,
    };
  }

  function findNodeAt(point) {
    let best = null;
    let bestDistance = Infinity;
    for (const node of visibleNodes) {
      const distance = Math.hypot(node.x - point.x, node.y - point.y);
      if (distance < node.radius + 7 / state.scale && distance < bestDistance) {
        best = node;
        bestDistance = distance;
      }
    }
    return best;
  }

  function updateTooltip(node, point) {
    if (!node) {
      tooltip.hidden = true;
      return;
    }
    tooltip.hidden = false;
    tooltip.style.left = `${Math.min(point.screenX + 14, canvas.clientWidth - 330)}px`;
    tooltip.style.top = `${Math.min(point.screenY + 14, canvas.clientHeight - 120)}px`;
    tooltip.innerHTML = `
      <b>${escapeHtml(node.simpleName)}</b>
      <span>${escapeHtml(node.packageName || "(default)")}</span><br>
      <span>${labelForStatus(node.threadStatus)} - ${node.methodCount} methods - ${restrictionCount(node)} restricted</span>
    `;
  }

  function fitGraph() {
    if (visibleNodes.length === 0) return;
    const rect = canvas.getBoundingClientRect();
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const node of visibleNodes) {
      minX = Math.min(minX, node.x - node.radius);
      minY = Math.min(minY, node.y - node.radius);
      maxX = Math.max(maxX, node.x + node.radius);
      maxY = Math.max(maxY, node.y + node.radius);
    }
    const width = Math.max(1, maxX - minX);
    const height = Math.max(1, maxY - minY);
    const scale = Math.min(1.8, Math.max(0.22, Math.min((rect.width - 60) / width, (rect.height - 60) / height)));
    state.scale = scale;
    state.offsetX = rect.width / 2 - ((minX + maxX) / 2) * scale;
    state.offsetY = rect.height / 2 - ((minY + maxY) / 2) * scale;
  }

  function animate() {
    if (!state.paused) {
      tick();
      if (visibleNodes.length < 500) tick();
    }
    draw();
    requestAnimationFrame(animate);
  }

  searchInput.addEventListener("input", () => {
    state.query = searchInput.value.trim();
    refreshVisible();
  });

  document.getElementById("clearRestrictions").addEventListener("click", () => {
    state.restrictions.clear();
    renderRestrictionFilters();
    refreshVisible();
  });

  document.getElementById("resetFilters").addEventListener("click", () => {
    state.query = "";
    searchInput.value = "";
    state.statuses = new Set(Object.keys(STATUS));
    state.sources = new Set(["main", "test", "misc"]);
    state.restrictions.clear();
    state.packagePrefix = "";
    state.selectedId = null;
    renderStatusFilters();
    renderSourceFilters();
    renderRestrictionFilters();
    renderPackages();
    refreshVisible();
    fitGraph();
  });

  document.getElementById("fitGraph").addEventListener("click", fitGraph);
  pauseButton.addEventListener("click", () => {
    state.paused = !state.paused;
    pauseButton.textContent = state.paused ? "Resume" : "Pause";
  });

  canvas.addEventListener("mousedown", (event) => {
    const point = toWorld(event);
    const node = findNodeAt(point);
    state.lastPointer = { x: event.clientX, y: event.clientY };
    if (node) {
      state.draggingNode = node;
      selectNode(node.id);
    } else {
      state.isPanning = true;
    }
  });

  window.addEventListener("mousemove", (event) => {
    const point = toWorld(event);
    if (state.draggingNode) {
      state.draggingNode.x = point.x;
      state.draggingNode.y = point.y;
      state.draggingNode.vx = 0;
      state.draggingNode.vy = 0;
      return;
    }
    if (state.isPanning && state.lastPointer) {
      state.offsetX += event.clientX - state.lastPointer.x;
      state.offsetY += event.clientY - state.lastPointer.y;
      state.lastPointer = { x: event.clientX, y: event.clientY };
      return;
    }
    const hovered = findNodeAt(point);
    state.hoveredId = hovered?.id ?? null;
    updateTooltip(hovered, point);
  });

  window.addEventListener("mouseup", () => {
    state.draggingNode = null;
    state.isPanning = false;
    state.lastPointer = null;
  });

  canvas.addEventListener("mouseleave", () => {
    state.hoveredId = null;
    tooltip.hidden = true;
  });

  canvas.addEventListener("wheel", (event) => {
    event.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const mx = event.clientX - rect.left;
    const my = event.clientY - rect.top;
    const before = {
      x: (mx - state.offsetX) / state.scale,
      y: (my - state.offsetY) / state.scale,
    };
    const factor = event.deltaY > 0 ? 0.88 : 1.14;
    state.scale = clamp(state.scale * factor, 0.18, 4.5);
    state.offsetX = mx - before.x * state.scale;
    state.offsetY = my - before.y * state.scale;
  }, { passive: false });

  window.addEventListener("resize", () => {
    resizeCanvas();
    fitGraph();
  });

  renderStats();
  renderStatusFilters();
  renderSourceFilters();
  renderRestrictionFilters();
  renderPackages();
  refreshVisible();
  initializeLayout();
  animate();
})();
