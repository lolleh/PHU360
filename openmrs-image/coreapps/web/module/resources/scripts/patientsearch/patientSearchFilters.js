/*
 * Patient search column filters (Gender, Reg Facility)
 *
 * Adds a funnel button to the Gender and Reg Facility column headers of the
 * home page patient search results table
 * (coreapps PatientSearchWidget -> #patient-search-results-table). Each button
 * opens a dropdown of the values actually present in the current result set and
 * filters the table to an exact match on that column.
 *
 * Why an enhancement layer instead of a change to patientSearchWidget.js:
 *   - the widget builds its columns from server config (findPatientColumnConfig
 *     in pih-config-sierraLeone-falaba.json), so column positions are not known
 *     here; we resolve them from the rendered header text;
 *   - the widget hands us no public API for column filters, but the table is a
 *     DataTables instance reachable from the DOM, so we drive it directly.
 *
 * Why filtering is done by rewriting settings.aiDisplay rather than through
 * $.fn.dataTable.ext.search / fnFilter, based on what the bundled DataTables
 * actually does (uicommons 3.2.0 ships DataTables 1.9.4, and the widget creates
 * it with bFilter:false):
 *   - this build's custom-filter array is ext.afnFiltering, not ext.search;
 *   - the filter pipeline (oApi._fnFilterComplete) is only entered through
 *     _fnReDraw, and _fnReDraw resets aiDisplay from aiDisplayMaster, so a
 *     registered filter would be discarded on every redraw the widget makes;
 *   - fnFilter(s, col, ...) is likewise never re-applied.
 * Rewriting aiDisplay from an aoPreDrawCallback is the approach that survives
 * the widget's own redraws (paging, new searches, row selection) and needs no
 * global extension registration.
 *
 * Exact comparison is deliberate: DataTables' substring match would break gender
 * filtering outright, since "male" also matches "Female".
 *
 * Loaded by the patched coreapps patientSearchWidget.gsp.
 */
(function (jq) {
    'use strict';

    if (!jq) {
        return;
    }

    var TABLE_ID = 'patient-search-results-table';
    var FORM_ID = 'patient-search-form';
    var RESULT_DIV = 'patient-search-results';

    var FAVICON = '<svg class="psf-icon" viewBox="0 0 16 16" focusable="false" ' +
        'aria-hidden="true"><path d="M1.6 2h12.8L9.2 7.6v4.9l-2.4 1.5V7.6z"/></svg>';

    // Resolved against the rendered header text, so renaming a column via
    // findPatientColumnConfig or a message bundle keeps working.
    var spec = [
        { key: 'gender', title: 'Gender', header: /^(gender|sex)$/i },
        { key: 'facility', title: 'Reg Facility', header: /^(reg(istered)?\.?\s*facility|facility)$/i }
    ];

    var state = {
        dt: null,
        columns: null,        // key -> column index, resolved once per attach
        filters: {},          // key -> {index: <col>, key: <normalized value>}
        applied: false,       // true while we own settings.aiDisplay
        hooksInstalled: false,
        eventsHooked: false,
        observer: null,
        menu: null,
        menuBtn: null,
        menuKey: null,
        attachTimer: null,
        bootTries: 0
    };

    /* ---------------------------------------------------------------- utils */

    function norm(value) {
        if (value === null || value === undefined) {
            return '';
        }
        return String(value)
            .replace(/<[^>]*>/g, ' ')
            .replace(/&nbsp;/gi, ' ')
            .replace(/&amp;/gi, '&')
            .replace(/&[a-z]+;/gi, ' ')
            .replace(/\s+/g, ' ')
            .replace(/^\s+|\s+$/g, '');
    }

    function normKey(value) {
        return norm(value).toLowerCase();
    }

    /* ----------------------------------------------------------- table access */

    function api() {
        if (state.dt) {
            return state.dt;
        }
        var $table = jq('#' + TABLE_ID);
        if (!$table.length || !jq.fn || !jq.fn.dataTable) {
            return null;
        }
        try {
            state.dt = $table.DataTable();
        } catch (e) {
            return null;
        }
        return state.dt;
    }

    // DataTables 1.9 exposes fnSettings(); 1.10 renamed it to settings().
    function settingsOf(table) {
        if (!table) {
            return null;
        }
        return typeof table.fnSettings === 'function' ? table.fnSettings() : table.settings()[0];
    }

    function headerTitles() {
        return jq('#' + TABLE_ID + ' thead th').map(function () {
            // Drop our own funnel button before reading the label, otherwise the
            // button's title text leaks into the header and breaks matching.
            return norm(jq(this).clone().children('.psf').remove().end().text());
        }).get();
    }

    function resolveColumns() {
        var titles = headerTitles();
        var found = {};
        for (var i = 0; i < titles.length; i++) {
            for (var j = 0; j < spec.length; j++) {
                var item = spec[j];
                if (found[item.key] === undefined && titles[i] && item.header.test(titles[i])) {
                    found[item.key] = i;
                }
            }
        }
        return found;
    }

    // Distinct, non-empty values for a column across every row DataTables knows
    // about (not just the current page), preserving display casing.
    function distinctValues(index) {
        var settings = settingsOf(api());
        if (!settings) {
            return [];
        }
        var rows = settings.aoData || [];
        var seen = {};
        var values = [];
        for (var i = 0; i < rows.length; i++) {
            var cell = (rows[i]._aData || [])[index];
            var key = normKey(cell);
            if (!key || seen[key]) {
                continue;
            }
            seen[key] = true;
            values.push({ key: key, label: norm(cell) });
        }
        values.sort(function (a, b) {
            return a.label < b.label ? -1 : (a.label > b.label ? 1 : 0);
        });
        return values;
    }

    /* --------------------------------------------------------------- filtering */

    function activeFilters() {
        var out = [];
        for (var key in state.filters) {
            if (Object.prototype.hasOwnProperty.call(state.filters, key) && state.filters[key]) {
                out.push(state.filters[key]);
            }
        }
        return out;
    }

    // Rebuild the visible row set from the full data set, keeping only rows that
    // match every active filter exactly. Called from aoPreDrawCallback, i.e.
    // before the draw builds any row, so the draw itself renders the narrowed
    // table -- no second draw and no flash of unfiltered rows.
    function reapplyFilters() {
        var table = api();
        var settings = settingsOf(table);
        if (!settings) {
            return;
        }
        var filters = activeFilters();
        if (!filters.length) {
            // Hand the table back to its own state when nothing is filtered,
            // unless we are the ones who narrowed it.
            if (state.applied) {
                settings.aiDisplay = (settings.aiDisplayMaster || []).slice();
                settings._iDisplayEnd = displayEnd(table, settings);
                state.applied = false;
            }
            return;
        }

        var master = settings.aiDisplayMaster || [];
        var keep = [];
        for (var i = 0; i < master.length; i++) {
            var cells = (settings.aoData[master[i]] || {})._aData || [];
            var ok = true;
            for (var f = 0; f < filters.length; f++) {
                if (normKey(cells[filters[f].index]) !== filters[f].key) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                keep.push(master[i]);
            }
        }
        settings.aiDisplay = keep;
        settings._iDisplayEnd = displayEnd(table, settings);
        state.applied = true;
    }

    // _fnDraw builds its rows from settings._iDisplayStart..settings._iDisplayEnd
    // but only recalculates the end itself on the very first draw, so a filter
    // applied later has to update it. DataTables' own _fnCalculateEnd is used
    // when available, since paging maths here has to stay identical to the
    // table's.
    function displayEnd(table, settings) {
        if (table.oApi && typeof table.oApi._fnCalculateEnd === 'function') {
            table.oApi._fnCalculateEnd(settings);
            return settings._iDisplayEnd;
        }
        var count = settings.aiDisplay.length;
        if (!settings.iDisplayLength || settings.iDisplayLength === -1) {
            return count;
        }
        return Math.min(count, settings._iDisplayStart + settings.iDisplayLength);
    }

    function applyFilter(key, index, valueKey) {
        if (valueKey) {
            state.filters[key] = { index: index, key: valueKey };
        } else {
            delete state.filters[key];
        }
        redraw(true);
    }

    // Redraw so the aoPreDrawCallback re-applies the filters. With fromTop the
    // view jumps back to the first page, as it does for any new filter.
    function redraw(fromTop) {
        var table = api();
        if (!table) {
            attach();
            return;
        }
        var settings = settingsOf(table);
        if (settings && fromTop) {
            settings._iDisplayStart = 0;
        }
        table.fnDraw();
    }

    function isActive(key) {
        return !!state.filters[key];
    }

    function activeValue(key) {
        return state.filters[key] ? state.filters[key].key : '';
    }

    /* ------------------------------------------------------------------- menu */

    function closeMenu() {
        if (state.menu) {
            state.menu.remove();
            state.menu = null;
        }
        if (state.menuBtn) {
            state.menuBtn.attr('aria-expanded', 'false');
        }
        state.menuBtn = null;
        state.menuKey = null;
        jq(document).off('.psf');
        jq(window).off('.psf');
    }

    // The results table scrolls horizontally, so the button that was clicked can
    // sit outside the visible area. The menu is therefore clamped to the
    // viewport itself and merely prefers to sit under its button.
    function positionMenu(menu, button) {
        var rect = button[0].getBoundingClientRect();
        var $menu = jq(menu);
        var width = $menu.outerWidth();
        var height = $menu.outerHeight();
        var viewportW = jq(window).width();
        var viewportH = jq(window).height();

        var left = rect.left;
        if (left + width > viewportW - 8) {
            left = rect.right - width;
        }
        left = clamp(left, 8, viewportW - width - 8);

        var top = rect.bottom + 2;
        if (top + height > viewportH - 8) {
            // Not enough room below: flip above the button when we can.
            var above = rect.top - height - 2;
            top = above > 8 ? above : viewportH - height - 8;
        }
        top = clamp(top, 8, viewportH - height - 8);

        $menu.css({ left: left, top: top });
    }

    function clamp(value, min, max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    function openMenu(item, index, button) {
        closeMenu();
        state.menuBtn = button.attr('aria-expanded', 'true');
        state.menuKey = item.key;

        var values = distinctValues(index);
        var selected = activeValue(item.key);

        // Keep an active selection visible even if the new result set no longer
        // contains it, so the button state and the shown filter always agree.
        var stillListed = false;
        for (var v = 0; v < values.length; v++) {
            if (values[v].key === selected) {
                stillListed = true;
                break;
            }
        }
        if (selected && !stillListed) {
            values = values.concat([{ key: selected, label: selected }]);
        }

        var menu = jq('<div class="psf-menu" role="dialog"></div>');
        menu.append(jq('<div class="psf-menu-title"></div>').text('Filter by ' + item.title));

        var list = jq('<ul class="psf-menu-list"></ul>');
        [{ key: '', label: 'All' }].concat(values).forEach(function (option) {
            var buttonEl = jq('<button type="button" class="psf-opt"></button>')
                .text(option.label)
                .attr('data-value', option.key);
            if (option.key === selected) {
                buttonEl.addClass('is-on').attr('aria-pressed', 'true');
            }
            jq('<li></li>').append(buttonEl).appendTo(list);
        });
        menu.append(list);
        menu.append(jq('<button type="button" class="psf-menu-clear"></button>')
            .text('Clear filter')
            .prop('disabled', !selected));

        menu.on('click', 'button.psf-opt', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var value = jq(this).attr('data-value');
            closeMenu();
            applyFilter(item.key, index, value);
        });
        menu.on('click', 'button.psf-menu-clear', function (e) {
            e.preventDefault();
            e.stopPropagation();
            closeMenu();
            applyFilter(item.key, index, '');
        });

        jq(document.body).append(menu);
        state.menu = menu;
        positionMenu(menu, button);

        jq(document).on('click.psf', function (e) {
            if (state.menu && !state.menu[0].contains(e.target) && !button.is(e.target)) {
                closeMenu();
            }
        });
        jq(document).on('keydown.psf', function (e) {
            if (e.key === 'Escape' || e.keyCode === 27) {
                closeMenu();
            }
        });
        jq(window).on('resize.psf scroll.psf', function () {
            if (state.menu && state.menuBtn && document.body.contains(state.menuBtn[0])) {
                positionMenu(state.menu, state.menuBtn);
            } else {
                closeMenu();
            }
        });
    }

    /* ------------------------------------------------------------- attachment */

    function buildButton(item) {
        var active = isActive(item.key);
        var button = jq('<button type="button" class="psf-toggle"></button>')
            .attr('aria-haspopup', 'true')
            .attr('aria-expanded', 'false')
            .attr('aria-label', 'Filter by ' + item.title)
            .attr('title', 'Filter by ' + item.title)
            .attr('data-psf-key', item.key)
            .attr('data-psf-value', active ? activeValue(item.key) : '')
            .html(FAVICON);

        if (active) {
            button.addClass('is-active');
        }

        button.on('click', function (e) {
            // The header may be a DataTables sort target; a filter click must
            // never bubble up into a sort or row-selection handler.
            e.preventDefault();
            e.stopPropagation();
            var wasOpen = jq(this).attr('aria-expanded') === 'true';
            var index = state.columns ? state.columns[item.key] : undefined;
            closeMenu();
            if (!wasOpen && index !== undefined) {
                openMenu(item, index, jq(this));
            }
        });

        return jq('<span class="psf"></span>').append(button);
    }

    function attach() {
        var table = api();
        if (!table) {
            return;
        }
        installDataHooks();
        state.columns = resolveColumns();

        var $headers = jq('#' + TABLE_ID + ' thead th');
        spec.forEach(function (item) {
            var index = state.columns[item.key];
            if (index === undefined || !$headers.eq(index).length) {
                return;
            }
            var $th = $headers.eq(index);
            $th.children('.psf').remove();
            var button = buildButton(item);
            $th.append(button);
            // Keep an open dropdown attached to its (rebuilt) button.
            if (state.menu && state.menuKey === item.key) {
                state.menuBtn = button.find('.psf-toggle').attr('aria-expanded', 'true');
                positionMenu(state.menu, state.menuBtn);
            }
        });
    }

    // Redraws are frequent (every page of results), so coalesce bursts of DOM
    // mutations into a single attach.
    function scheduleAttach() {
        if (state.attachTimer) {
            return;
        }
        state.attachTimer = setTimeout(function () {
            state.attachTimer = null;
            attach();
        }, 0);
    }

    /* ------------------------------------------------------------------- boot */

    // DataTables 1.9 copies fnDrawCallback/fnPreDrawCallback into the
    // aoDrawCallback/aoPreDrawCallback arrays once, at init, and later reads
    // only those arrays. Overriding settings.fnDrawCallback therefore does
    // nothing, so both hooks are registered as array entries instead.
    function installDataHooks() {
        var table = api();
        var settings = settingsOf(table);
        if (!settings || state.hooksInstalled || settings.psfHooksInstalled) {
            return;
        }
        state.hooksInstalled = true;
        settings.psfHooksInstalled = true;

        settings.aoPreDrawCallback = settings.aoPreDrawCallback || [];
        settings.aoPreDrawCallback.push({
            sName: 'user',
            fn: function (oSettings) {
                // Runs before any row is built: narrow the row set first.
                reapplyFilters();
            }
        });

        settings.aoDrawCallback = settings.aoDrawCallback || [];
        settings.aoDrawCallback.push({
            sName: 'user',
            fn: function (oSettings) {
                // Header markup is rebuilt during the draw, so re-add our
                // buttons once it is done.
                scheduleAttach();
            }
        });
    }

    function hookWidgetEvents() {
        var $form = jq('#' + FORM_ID);
        if (!$form.length || state.eventsHooked) {
            return;
        }
        state.eventsHooked = true;
        $form.on('search:clear', function () {
            // Clearing the search resets the column filters with it, so the next
            // result set starts unfiltered.
            state.filters = {};
            redraw(true);
        });
        $form.on('search:start search:placeholder search:identifiers search:enable', function () {
            scheduleAttach();
        });
        // The widget's own clear button calls clearSearch() directly instead of
        // triggering search:clear, so listen to it as well.
        jq('#patient-search-clear-button').on('click', function () {
            state.filters = {};
        });
    }

    function watchForTable() {
        var host = jq('#' + RESULT_DIV);
        if (!host.length || state.observer || typeof MutationObserver === 'undefined') {
            return;
        }
        state.observer = new MutationObserver(function () {
            scheduleAttach();
        });
        state.observer.observe(host[0], { childList: true, subtree: true });
    }

    function pollForTable() {
        if (state.bootTries++ > 40) {
            return;
        }
        if (jq('#' + TABLE_ID).length) {
            attach();
            return;
        }
        setTimeout(pollForTable, 250);
    }

    function boot() {
        if (!jq.fn || !jq.fn.dataTable) {
            jq(window).one('load', boot);
            return;
        }
        hookWidgetEvents();
        watchForTable();
        attach();
        pollForTable();
    }

    if (document.readyState === 'complete') {
        boot();
    } else {
        jq(boot);
    }
})(window.jQuery || window.jq);
