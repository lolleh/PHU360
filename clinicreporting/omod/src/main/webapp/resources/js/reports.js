/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

/*
 * Presentation layer for the Clinic Reports pages, matching the PHU360 reporting
 * dashboard: charts, share bars and CSV export.
 *
 * Charts are built by reading the tables the server already rendered, rather than from a
 * JSON blob. The report tables are the authoritative output and a clinician may well read
 * them instead of the chart, so deriving one from the other guarantees the two can never
 * disagree, and it keeps labels out of a hand-built JSON string in the JSP.
 */
(function () {
	'use strict';

	var PALETTE = ['#2563eb', '#0f8a3d', '#f6be00', '#323372', '#3ab4b1', '#e8a13c',
		'#0f5149', '#5b5c8e', '#0077ff', '#3e3e3e', '#8e6a53', '#9999b9'];

	var FONT = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';
	var GRID = 'rgba(0,0,0,0.06)';
	var MUTED = '#4a4235';

	function ready(fn) {
		if (document.readyState === 'loading') {
			document.addEventListener('DOMContentLoaded', fn);
		} else {
			fn();
		}
	}

	/**
	 * Reads label/value pairs out of a rendered report table.
	 *
	 * The first cell is the label and the right-aligned cell is the count. Total and
	 * empty rows are skipped so a summary line never becomes a bar.
	 */
	function readTable(table) {
		var rows = [];
		var body = table.tBodies[0];
		if (!body) {
			return rows;
		}
		for (var i = 0; i < body.rows.length; i++) {
			var row = body.rows[i];
			if (row.className.indexOf('cr-total-row') !== -1) {
				continue;
			}
			var cells = row.cells;
			if (cells.length < 2) {
				continue;
			}
			var valueCell = cells[cells.length - 1];
			var label = (cells[0].textContent || '').trim();
			var value = parseFloat((valueCell.textContent || '').replace(/[^0-9.\-]/g, ''));
			if (label === '' || isNaN(value)) {
				continue;
			}
			rows.push({ label: label, value: value });
		}
		return rows;
	}

	function axisDefaults() {
		return {
			grid: { color: GRID, drawBorder: false },
			ticks: { color: MUTED, font: { family: FONT, size: 11 } }
		};
	}

	/** Puts the series total in the middle of a doughnut, the way PHU360 does. */
	var centerTotal = {
		id: 'crCenterTotal',
		afterDraw: function (chart) {
			if (!chart.config.type || chart.config.type !== 'doughnut') {
				return;
			}
			var meta = chart.getDatasetMeta(0);
			if (!meta || !meta.data || !meta.data.length) {
				return;
			}
			var total = chart.data.datasets[0].data.reduce(function (a, b) { return a + b; }, 0);
			var area = chart.chartArea;
			if (!area) {
				return;
			}
			var ctx = chart.ctx;
			var x = (area.left + area.right) / 2;
			var y = (area.top + area.bottom) / 2;
			ctx.save();
			ctx.textAlign = 'center';
			ctx.textBaseline = 'middle';
			ctx.fillStyle = MUTED;
			ctx.font = '700 12px ' + FONT;
			ctx.fillText(String(total), x, y);
			ctx.restore();
		}
	};

	function renderChart(box, rows, kind) {
		if (typeof window.Chart === 'undefined') {
			return;
		}
		var canvas = box.querySelector('canvas');
		if (!canvas) {
			return;
		}
		if (!rows.length) {
			box.classList.add('is-empty');
			return;
		}

		var labels = rows.map(function (r) { return r.label; });
		var values = rows.map(function (r) { return r.value; });
		var colors = rows.map(function (r, i) { return PALETTE[i % PALETTE.length]; });

		Chart.defaults.font.family = FONT;
		Chart.defaults.font.size = 11;
		Chart.defaults.color = MUTED;

		if (kind === 'doughnut') {
			new Chart(canvas, {
				type: 'doughnut',
				data: {
					labels: labels,
					datasets: [{
						data: values,
						backgroundColor: colors,
						borderColor: '#fff',
						borderWidth: 2
					}]
				},
				options: {
					responsive: true,
					maintainAspectRatio: false,
					animation: { duration: 350 },
					cutout: '58%',
					plugins: {
						legend: {
							position: 'bottom',
							labels: { boxWidth: 10, boxHeight: 10, padding: 10, font: { family: FONT, size: 11 } }
						},
						tooltip: {
							callbacks: {
								label: function (ctx) {
									var total = ctx.dataset.data.reduce(function (a, b) { return a + b; }, 0);
									var pct = total ? Math.round((ctx.parsed / total) * 100) : 0;
									return ' ' + ctx.label + ': ' + ctx.parsed + ' (' + pct + '%)';
								}
							}
						}
					}
				},
				plugins: [centerTotal]
			});
			return;
		}

		var horizontal = kind === 'hbar';
		new Chart(canvas, {
			type: 'bar',
			data: {
				labels: labels,
				datasets: [{
					label: '',
					data: values,
					backgroundColor: colors,
					borderRadius: 4,
					maxBarThickness: horizontal ? 22 : 46
				}]
			},
			options: {
				indexAxis: horizontal ? 'y' : 'x',
				responsive: true,
				maintainAspectRatio: false,
				animation: { duration: 350 },
				plugins: {
					legend: { display: false },
					tooltip: {
						callbacks: {
							label: function (ctx) { return ' ' + ctx.parsed.x + ' ' + (ctx.parsed.y || ''); }
						}
					}
				},
				scales: horizontal
					? { x: axisDefaults(), y: axisDefaults() }
					: { y: axisDefaults(), x: axisDefaults() }
			}
		});
	}

	/** A line for the time series, so a trend reads as a trend rather than as bars. */
	function renderLine(box, rows) {
		if (typeof window.Chart === 'undefined') {
			return;
		}
		var canvas = box.querySelector('canvas');
		if (!canvas) {
			return;
		}
		if (!rows.length) {
			box.classList.add('is-empty');
			return;
		}
		Chart.defaults.font.family = FONT;
		Chart.defaults.font.size = 11;
		Chart.defaults.color = MUTED;

		new Chart(canvas, {
			type: 'line',
			data: {
				labels: rows.map(function (r) { return r.label; }),
				datasets: [{
					label: '',
					data: rows.map(function (r) { return r.value; }),
					borderColor: PALETTE[0],
					backgroundColor: 'rgba(37, 99, 235, 0.12)',
					fill: true,
					tension: 0.3,
					pointRadius: 2,
					pointBackgroundColor: PALETTE[0],
					borderWidth: 2
				}]
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				animation: { duration: 350 },
				plugins: {
					legend: { display: false },
					tooltip: {
						callbacks: {
							label: function (ctx) { return ' ' + ctx.parsed.y; }
						}
					}
				},
				scales: { y: axisDefaults(), x: axisDefaults() }
			}
		});
	}

	/**
	 * Reads a cross-tabulated table: the first header cell and the first cell of each row name the
	 * row dimension, the last column is the row total, and everything between is a treatment column.
	 *
	 * @return {labels, rows} where rows are {label, values}, or null when the table has no header
	 */
	function readMatrix(table) {
		var head = table.tHead ? table.tHead.rows[0] : null;
		if (!head) {
			return null;
		}
		var labels = [];
		for (var c = 1; c < head.cells.length - 1; c++) {
			labels.push((head.cells[c].textContent || '').trim());
		}
		var rows = [];
		var body = table.tBodies[0];
		if (!body) {
			return { labels: labels, rows: rows };
		}
		for (var i = 0; i < body.rows.length; i++) {
			var cells = body.rows[i].cells;
			if (cells.length < 2) {
				continue;
			}
			var values = [];
			for (var j = 1; j < cells.length - 1; j++) {
				values.push(parseFloat((cells[j].textContent || '').replace(/[^0-9.\-]/g, '')) || 0);
			}
			rows.push({ label: (cells[0].textContent || '').trim(), values: values });
		}
		return { labels: labels, rows: rows };
	}

	/**
	 * A stacked horizontal bar, one bar per diagnosis and one colour per treatment, so the shape of
	 * which treatments go with which diagnoses reads at a glance.
	 */
	function renderMatrix(box, table) {
		if (typeof window.Chart === 'undefined') {
			return;
		}
		var canvas = box.querySelector('canvas');
		if (!canvas) {
			return;
		}
		var matrix = readMatrix(table);
		if (!matrix || !matrix.rows.length || !matrix.labels.length) {
			box.classList.add('is-empty');
			return;
		}

		Chart.defaults.font.family = FONT;
		Chart.defaults.font.size = 11;
		Chart.defaults.color = MUTED;

		var datasets = matrix.labels.map(function (label, index) {
			return {
				label: label,
				data: matrix.rows.map(function (row) { return row.values[index]; }),
				backgroundColor: PALETTE[index % PALETTE.length],
				borderRadius: 4,
				maxBarThickness: 22
			};
		});

		var x = axisDefaults();
		x.stacked = true;
		var y = axisDefaults();
		y.stacked = true;

		new Chart(canvas, {
			type: 'bar',
			data: {
				labels: matrix.rows.map(function (row) { return row.label; }),
				datasets: datasets
			},
			options: {
				indexAxis: 'y',
				responsive: true,
				maintainAspectRatio: false,
				animation: { duration: 350 },
				plugins: {
					legend: {
						position: 'bottom',
						labels: { boxWidth: 10, boxHeight: 10, padding: 10, font: { family: FONT, size: 11 } }
					},
					tooltip: {
						callbacks: {
							label: function (ctx) { return ' ' + ctx.dataset.label + ': ' + ctx.parsed.x; }
						}
					}
				},
				scales: { x: x, y: y }
			}
		});
	}

	/**
	 * Adds a proportional bar to any cell marked cr-share, so a breakdown table shows shape
	 * as well as numbers. Scaled against the largest row rather than the total, because on a
	 * long tail the largest row would otherwise squeeze everything else to a hairline.
	 */
	function addShareBars(table) {
		var rows = readTable(table);
		if (!rows.length) {
			return;
		}
		var max = rows.reduce(function (a, r) { return Math.max(a, r.value); }, 0);
		if (max <= 0) {
			return;
		}
		var body = table.tBodies[0];
		for (var i = 0; i < body.rows.length; i++) {
			var row = body.rows[i];
			var cell = row.querySelector('td.cr-share');
			if (!cell) {
				continue;
			}
			var value = parseFloat((row.cells[row.cells.length - 1].textContent || '').replace(/[^0-9.\-]/g, ''));
			if (isNaN(value)) {
				continue;
			}
			var pct = Math.max(2, Math.round((value / max) * 100));
			var bar = document.createElement('span');
			bar.className = 'cr-share-bar';
			var fill = document.createElement('i');
			fill.style.width = pct + '%';
			bar.appendChild(fill);
			cell.appendChild(bar);
		}
	}

	function toCsv(table) {
		var lines = [];
		var head = table.tHead ? table.tHead.rows[0] : null;
		if (head) {
			lines.push(Array.prototype.map.call(head.cells, function (c) {
				return '"' + (c.textContent || '').trim().replace(/"/g, '""') + '"';
			}).join(','));
		}
		var body = table.tBodies[0];
		for (var i = 0; i < body.rows.length; i++) {
			var cells = body.rows[i].cells;
			lines.push(Array.prototype.map.call(cells, function (c) {
				var text = (c.textContent || '').trim();
				// The share bar is a decorative child of its cell, not part of the value.
				var bar = c.querySelector('.cr-share-bar');
				if (bar) {
					text = text.replace(bar.textContent, '').trim();
				}
				return '"' + text.replace(/"/g, '""') + '"';
			}).join(','));
		}
		return lines.join('\r\n');
	}

	function downloadCsv(table, filename) {
		var blob = new Blob(['﻿' + toCsv(table)], { type: 'text/csv;charset=utf-8;' });
		var link = document.createElement('a');
		link.href = URL.createObjectURL(blob);
		link.download = filename;
		document.body.appendChild(link);
		link.click();
		document.body.removeChild(link);
		URL.revokeObjectURL(link.href);
	}

	/**
	 * Confirms a retire and collects the reason in one step: the prompt is the confirmation, so
	 * cancelling it cancels the retire and the reason is never a silent extra click. An empty
	 * reason is allowed, matching the stored field's this-is-optional contract.
	 */
	function retireExport(form, promptText) {
		var reason = window.prompt(promptText, '');
		if (reason === null) {
			return false;
		}
		var field = form.querySelector('input[name="reason"]');
		if (field) {
			field.value = reason;
		}
		return true;
	}

	// Inline onsubmit handlers can only reach a global, so the helper is published on window.
	window.crRetireExport = retireExport;

	/**
	 * Shows the builder panel for the chosen mode and hides the other. Both panels are rendered by
	 * the server and only one is shown, so choosing Raw SQL reveals the statement box at once without
	 * a round trip and without losing what has been typed in the pipeline.
	 */
	function syncExportMode(select) {
		var form = select.form;
		if (!form) {
			return;
		}
		var panels = form.querySelectorAll('[data-mode-panel]');
		for (var i = 0; i < panels.length; i++) {
			panels[i].style.display = panels[i].getAttribute('data-mode-panel') === select.value ? '' : 'none';
		}
	}

	window.crSyncExportMode = syncExportMode;

	function init() {
		var app = document.querySelector('.cr-app');
		if (!app) {
			return;
		}

		Array.prototype.forEach.call(app.querySelectorAll('.cr-chart-box[data-source]'), function (box) {
			var table = document.getElementById(box.getAttribute('data-source'));
			if (!table) {
				return;
			}
			var rows = readTable(table);
			var kind = box.getAttribute('data-chart') || 'bar';
			if (kind === 'matrix') {
				renderMatrix(box, table);
			} else if (kind === 'line') {
				renderLine(box, rows);
			} else {
				renderChart(box, rows, kind);
			}
		});

		Array.prototype.forEach.call(app.querySelectorAll('table.cr-table[data-share]'), function (table) {
			addShareBars(table);
		});

		Array.prototype.forEach.call(app.querySelectorAll('[data-export]'), function (button) {
			button.addEventListener('click', function () {
				var table = document.getElementById(button.getAttribute('data-export'));
				if (table) {
					downloadCsv(table, button.getAttribute('data-export-name') || 'report.csv');
				}
			});
		});

		// The saved-export list shows a preview of its rows and the toggle reveals the rest. The
		// rows themselves are rendered by the server, so this only flips the class the CSS keys on.
		Array.prototype.forEach.call(app.querySelectorAll('[data-exports-toggle]'), function (button) {
			var card = button.closest('.cr-card');
			var table = card ? card.querySelector('[data-exports-table]') : null;
			if (!table) {
				return;
			}
			button.addEventListener('click', function () {
				var expanded = table.classList.toggle('cr-exports-expanded');
				button.setAttribute('aria-expanded', expanded ? 'true' : 'false');
			});
		});
	}

	ready(init);
})();
