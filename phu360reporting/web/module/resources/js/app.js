(function () {
  "use strict";

  var API = window.location.pathname.split("/moduleResources/")[0] + "/moduleServlet/phu360reporting/reportApi";

  var PALETTE = ["#2563eb", "#0f8a3d", "#f6be00", "#323372", "#3ab4b1", "#e8a13c", "#0f5149", "#5b5c8e", "#0077ff", "#3e3e3e", "#8e6a53", "#9999b9"];

  var state = { from: null, to: null, location: "", encounterType: "" };

  var charts = {};
  var dataCache = null;

  function $(id) { return document.getElementById(id); }

  function fmtDate(d) {
    var m = d.getMonth() + 1;
    var day = d.getDate();
    return d.getFullYear() + "-" + (m < 10 ? "0" + m : m) + "-" + (day < 10 ? "0" + day : day);
  }

  function defaultRange() {
    var to = new Date();
    var from = new Date();
    from.setDate(1);
    from.setMonth(from.getMonth() - 11);
    return { from: fmtDate(from), to: fmtDate(to) };
  }

  function fetchJson(url, onOk, onErr) {
    fetch(url, { credentials: "same-origin" })
      .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
      .then(function (j) {
        if (j && j.error) { throw new Error(j.error); }
        onOk(j);
      })
      .catch(function (e) { onErr(e); });
  }

  /* ---------- filters ---------- */

  function loadFilters() {
    fetchJson(API + "?action=filters", function (j) {
      var loc = $("fLocation");
      (j.locations || []).forEach(function (l) {
        var o = document.createElement("option");
        o.value = l.uuid;
        o.textContent = l.name;
        loc.appendChild(o);
      });
      var et = $("fEncType");
      (j.encounterTypes || []).forEach(function (t) {
        var o = document.createElement("option");
        o.value = t.uuid;
        o.textContent = t.name;
        et.appendChild(o);
      });
    }, function (e) { console.error("filters failed", e); setSummary("Failed to load filters"); });
  }

  function collectState() {
    state.from = $("fFrom").value;
    state.to = $("fTo").value;
    state.location = $("fLocation").value;
    state.encounterType = $("fEncType").value;
  }

  function setSummary() {
    var parts = [];
    if (state.from && state.to) {
      parts.push(state.from + " \u2192 " + state.to);
    }
    parts.push(state.location ? $("fLocation").selectedOptions[0].textContent : "All health centers");
    parts.push(state.encounterType ? $("fEncType").selectedOptions[0].textContent : "All encounter types");
    $("filterSummary").textContent = parts.join(" \u00B7 ");
  }

  /* ---------- render ---------- */

  function render(data) {
    dataCache = data;
    setSummary();
    renderKpis(data.kpis);
    renderTrend(data.monthly);
    renderDonut("chartType", data.byType || []);
    renderLocation(data);
    renderAge(data.age || []);
    renderSex(data.sex || []);
    renderTable(data.byType || []);
  }

  function renderKpis(kpis) {
    (kpis.items || []).forEach(function (k) {
      var card = document.querySelector('.kpi[data-kpi="' + k.key + '"]');
      if (!card) return;
      $("kpi-" + k.key).textContent = Number(k.value).toLocaleString();
      var dEl = $("delta-" + k.key);
      var d = Number(k.delta);
      if (d === 0) {
        dEl.textContent = "no change";
        dEl.className = "kpi-delta";
      } else {
        var up = d > 0;
        dEl.textContent = (up ? "\u25B4 " : "\u25BE ") + d.toFixed(1) + "% vs prior period";
        dEl.className = "kpi-delta " + (up ? "up" : "down");
      }
    });
  }

  function chartBase() {
    return {
      responsive: true,
      maintainAspectRatio: false,
      animation: { duration: 350 },
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { labels: { color: "#4a4235", boxWidth: 12, font: { size: 11 } } },
        tooltip: {
          backgroundColor: "rgba(62,54,42,0.92)",
          titleFont: { size: 12, weight: "600" },
          bodyFont: { size: 12 },
          padding: 10,
          cornerRadius: 8,
          displayColors: true,
          callbacks: {
            label: function (item) {
              var v = item.parsed && item.parsed.x !== undefined ? item.parsed.x : item.parsed.y;
              return " " + item.dataset.label + ": " + Number(v).toLocaleString();
            }
          }
        }
      }
    };
  }

  /* ---------- chart helpers ---------- */

  function destroyChart(key) {
    if (charts[key]) {
      charts[key].destroy();
      delete charts[key];
    }
  }

  /* A chart with no data renders an empty placeholder box rather than a
     misleading "No data" slice. */
  function setChartEmpty(canvasId, isEmpty) {
    var box = $(canvasId).parentNode;
    if (box && box.classList) box.classList.toggle("is-empty", !!isEmpty);
  }

  function sumRows(rows) {
    return (rows || []).reduce(function (s, r) { return s + (Number(r.count) || 0); }, 0);
  }

  /* Draws the series total in the middle of a doughnut. */
  var centerTotalPlugin = {
    id: "centerTotal",
    afterDraw: function (chart) {
      if (chart.config.type !== "doughnut") return;
      var meta = chart.getDatasetMeta(0);
      var area = chart.chartArea;
      if (!meta || !meta.data.length || !area) return;
      var values = chart.data.datasets[0].data;
      var total = values.reduce(function (s, v) { return s + (Number(v) || 0); }, 0);
      var cx = (area.left + area.right) / 2;
      var cy = (area.top + area.bottom) / 2;
      var ctx = chart.ctx;
      ctx.save();
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillStyle = "#3e362a";
      ctx.font = "700 17px " + Chart.defaults.font.family;
      ctx.fillText(Number(total).toLocaleString(), cx, cy - 7);
      ctx.fillStyle = "#857c6c";
      ctx.font = "600 9.5px " + Chart.defaults.font.family;
      ctx.fillText("TOTAL", cx, cy + 11);
      ctx.restore();
    }
  };

  function renderTrend(monthly) {
    var ctx = $("chartTrend");
    destroyChart("trend");
    setChartEmpty("chartTrend", !(monthly || []).length);
    if (!(monthly || []).length) return;
    charts.trend = new Chart(ctx, {
      type: "line",
      data: {
        labels: (monthly || []).map(function (m) { return m.ym; }),
        datasets: [
          { label: "Encounters", data: (monthly || []).map(function (m) { return m.encounters; }),
            borderColor: "#2563eb", backgroundColor: "rgba(37,99,235,0.12)", fill: true, tension: 0.35, borderWidth: 2, pointRadius: 3, pointHoverRadius: 6 },
          { label: "Patients seen", data: (monthly || []).map(function (m) { return m.patients; }),
            borderColor: "#0f8a3d", backgroundColor: "rgba(15,138,61,0.12)", fill: true, tension: 0.35, borderWidth: 2, pointRadius: 3, pointHoverRadius: 6 }
        ]
      },
      options: Object.assign(chartBase(), {
        scales: {
          x: { grid: { display: false }, ticks: { color: "#4a4235", maxRotation: 0, autoSkipPadding: 12 } },
          y: { beginAtZero: true, grid: { color: "#d9d2c4" }, ticks: { color: "#4a4235", callback: function (v) { return Number(v).toLocaleString(); } } }
        }
      })
    });
  }

  function renderDonut(canvasId, rows) {
    var ctx = $(canvasId);
    destroyChart(canvasId);
    var labels = (rows || []).map(function (r) { return r.label; });
    var values = (rows || []).map(function (r) { return r.count; });
    setChartEmpty(canvasId, !sumRows(rows));
    if (!sumRows(rows)) return;
    charts[canvasId] = new Chart(ctx, {
      type: "doughnut",
      data: {
        labels: labels,
        datasets: [{ data: values, backgroundColor: PALETTE, borderWidth: 2, borderColor: "#ffffff" }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: "64%",
        animation: { duration: 350 },
        plugins: {
          legend: { position: "bottom", labels: { color: "#4a4235", boxWidth: 12, font: { size: 11 }, padding: 8 } },
          tooltip: {
            backgroundColor: "rgba(62,54,42,0.92)", padding: 10, cornerRadius: 8,
            callbacks: {
              label: function (item) {
                var total = values.reduce(function (s, v) { return s + (Number(v) || 0); }, 0);
                var pct = total ? (item.parsed / total * 100).toFixed(1) : "0.0";
                return " " + item.label + ": " + Number(item.parsed).toLocaleString() + " (" + pct + "%)";
              }
            }
          }
        }
      }
    });
  }

  function renderLocation(data) {
    var ctx = $("chartLocation");
    destroyChart("chartLocation");

    var filtered = !!state.location;
    var rows = data.byLocation || [];
    var title = filtered ? "Encounters at selected center" : "Encounters by health center";
    var sub = filtered
      ? "Volume recorded at the selected health center"
      : "Top health centers by encounter volume";
    $("chartLocTitle").textContent = title;
    $("chartLocSub").textContent = sub;

    setChartEmpty("chartLocation", !sumRows(rows));
    if (!sumRows(rows)) return;

    charts.chartLocation = new Chart(ctx, {
      type: "bar",
      data: {
        labels: rows.map(function (r) { return r.label; }),
        datasets: [{
          label: "Encounters",
          data: rows.map(function (r) { return r.count; }),
          backgroundColor: rows.map(function () { return filtered ? "#2563eb" : "#0f8a3d"; }),
          borderRadius: 5,
          maxBarThickness: 22
        }]
      },
      options: Object.assign(chartBase(), {
        indexAxis: "y",
        plugins: { legend: { display: false } },
        scales: {
          x: { beginAtZero: true, grid: { color: "#d9d2c4" }, ticks: { color: "#4a4235", callback: function (v) { return Number(v).toLocaleString(); } } },
          y: { grid: { display: false }, ticks: { color: "#4a4235", autoSkip: false, crossAlign: "far" } }
        }
      })
    });
  }

  function renderAge(rows) {
    var ctx = $("chartAge");
    destroyChart("chartAge");
    var order = ["0-4", "5-9", "10-14", "15-19", "20-24", "25-34", "35-49", "50+", "Unknown"];
    var by = {};
    (rows || []).forEach(function (r) { by[r.label] = r.count; });
    var labels = order.filter(function (g) { return by[g] !== undefined; });
    setChartEmpty("chartAge", !sumRows(rows));
    if (!labels.length) return;
    charts.chartAge = new Chart(ctx, {
      type: "bar",
      data: {
        labels: labels,
        datasets: [{ label: "Patients", data: labels.map(function (g) { return by[g] || 0; }),
          backgroundColor: PALETTE, borderRadius: 5, maxBarThickness: 44 }]
      },
      options: Object.assign(chartBase(), {
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { display: false }, ticks: { color: "#4a4235" } },
          y: { beginAtZero: true, grid: { color: "#d9d2c4" }, ticks: { color: "#4a4235", precision: 0, callback: function (v) { return Number(v).toLocaleString(); } } }
        }
      })
    });
  }

  function renderSex(rows) {
    renderDonut("chartSex", rows || []);
  }

  function renderTable(rows) {
    var tbody = document.querySelector("#typeTable tbody");
    tbody.innerHTML = "";
    var total = (rows || []).reduce(function (s, r) { return s + r.count; }, 0);
    if (!total) {
      var empty = document.createElement("tr");
      var emptyCell = document.createElement("td");
      emptyCell.colSpan = 3;
      emptyCell.textContent = "No encounters in the selected period.";
      emptyCell.className = "empty-row";
      empty.appendChild(emptyCell);
      tbody.appendChild(empty);
      return;
    }
    (rows || []).forEach(function (r) {
      var tr = document.createElement("tr");
      var share = r.count / total * 100;
      tr.appendChild(td(r.label));
      tr.appendChild(td(Number(r.count).toLocaleString(), "num"));
      tr.appendChild(td(share.toFixed(1) + "%", "num share"));
      tr.querySelector(".share").innerHTML =
        '<span class="share-bar"><i style="width:' + share.toFixed(1) + '%"></i></span>';
      tbody.appendChild(tr);
    });

    var foot = document.createElement("tr");
    foot.className = "total-row";
    foot.appendChild(td("Total", "total-label"));
    foot.appendChild(td(Number(total).toLocaleString(), "num total-value"));
    foot.appendChild(td("100%", "num total-value"));
    tbody.appendChild(foot);
  }

  function td(text, cls) {
    var cell = document.createElement("td");
    if (cls) cell.className = cls;
    cell.textContent = text;
    return cell;
  }

  /* ---------- data loading ---------- */

  function loadCounts() {
    setLoading(true);
    var url = API + "?action=counts";
    if (state.from) url += "&from=" + state.from;
    if (state.to) url += "&to=" + state.to;
    if (state.location) url += "&location=" + state.location;
    if (state.encounterType) url += "&encounterType=" + state.encounterType;
    fetchJson(url, function (j) { render(j); setLoading(false); },
      function (e) { setLoading(false); setSummary(); console.error(e); setSummary("Data failed to load: " + e.message); });
  }

  function setLoading(on) {
    document.querySelectorAll(".kpis, .charts, .table-card").forEach(function (el) {
      el.classList.toggle("loading", on);
    });
  }

  /* ---------- CSV export ---------- */

  function exportCsv() {
    var rows = (dataCache && dataCache.byType) || [];
    var lines = ["report,value"];
    lines.push("period," + state.from + " to " + state.to);
    lines.push("health center," + ($("fLocation").selectedOptions[0] ? $("fLocation").selectedOptions[0].textContent : "All"));
    lines.push("encounter type," + ($("fEncType").selectedOptions[0] ? $("fEncType").selectedOptions[0].textContent : "All"));
    lines.push("");
    lines.push("encounter type,encounters,share %");
    var total = rows.reduce(function (s, r) { return s + r.count; }, 0);
    rows.forEach(function (r) {
      lines.push(r.label + "," + r.count + "," + (total ? (r.count / total * 100).toFixed(1) : "0"));
    });
    var blob = new Blob(["\uFEFF" + lines.join("\n")], { type: "text/csv;charset=utf-8" });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "phu360reporting-" + (state.from || "all") + "_" + (state.to || "all") + ".csv";
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 200);
  }

  /* ---------- init ---------- */

  function init() {
    Chart.register(centerTotalPlugin);
    Chart.defaults.font.family = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
    var d = defaultRange();
    $("fFrom").value = d.from;
    $("fTo").value = d.to;
    state.from = d.from;
    state.to = d.to;

    loadFilters();

    $("btnApply").addEventListener("click", function () {
      collectState();
      if (!state.from || !state.to) { setSummary("Select a period"); return; }
      loadCounts();
    });
    $("btnReset").addEventListener("click", function () {
      $("fLocation").value = "";
      $("fEncType").value = "";
    });
    $("btnCsv").addEventListener("click", exportCsv);

    loadCounts();
  }

  document.addEventListener("DOMContentLoaded", init);
})();