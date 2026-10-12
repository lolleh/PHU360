<%@ include file="/WEB-INF/template/include.jsp" %>
<%@ include file="/WEB-INF/template/header.jsp" %>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/styles/reports.css" />

<%--
  The Data Exports workspace: the list of saved exports above the builder that creates
  them. One POST submits the whole builder (name, columns, filters, raw statement) as
  either "save" or "preview", so switching between the two never discards what has been
  typed. The list runs an export on screen or serves the CSV download.

  Access control is enforced by the controller, not by this page. The controller checks
  "Manage Report Indicators" before it resolves a single source or renders a row, and
  re-checks "Run Raw Report SQL" on every raw save, preview, run and download. This file
  never has to be trusted to withhold anything.
--%>

<div class="cr-app">
<%@ include file="chrome.jsp" %>

<c:choose>

<c:when test="${denied}">
	<div class="cr-notice">
		<h3><spring:message code="clinicreporting.exports.deniedTitle"/></h3>
		<p><spring:message code="clinicreporting.exports.deniedBody"/></p>
	</div>
</c:when>

<c:when test="${noSources}">
	<div class="cr-notice cr-notice-danger">
		<h3><spring:message code="clinicreporting.exports.title"/></h3>
		<p><spring:message code="clinicreporting.exports.noSources"/></p>
	</div>
</c:when>

<c:otherwise>

	<section class="cr-card cr-sb-intro">
		<h2><spring:message code="clinicreporting.exports.title"/></h2>
		<p><spring:message code="clinicreporting.exports.lead"/></p>
	</section>

	<c:if test="${not empty flash}">
		<div class="cr-notice">
			<p><c:out value="${flash}"/></p>
		</div>
	</c:if>
	<c:if test="${not empty flashError}">
		<div class="cr-notice cr-notice-danger">
			<p><c:out value="${flashError}"/></p>
		</div>
	</c:if>

	<%--
		The builder. It sits above the list because the default view is the "create" entry
		point: an administrator opens the page and the form is already the first thing to do.
	--%>
	<c:if test="${building}">
	<section class="cr-card cr-filters cr-sb-card">
		<div class="cr-table-head">
			<h2>
				<c:choose>
					<c:when test="${not empty editingId}"><spring:message code="clinicreporting.exports.existing"/>: <c:out value="${exportName}"/></c:when>
					<c:otherwise><spring:message code="clinicreporting.exports.newExport"/></c:otherwise>
				</c:choose>
			</h2>
			<c:if test="${not empty editingId}">
				<a class="cr-btn cr-btn-export" href="${pageContext.request.contextPath}/module/clinicreporting/exports.htm"><spring:message code="clinicreporting.exports.newExport"/></a>
			</c:if>
		</div>

		<form method="post" action="${pageContext.request.contextPath}/module/clinicreporting/exports.htm">
			<c:if test="${not empty editingId}">
				<input type="hidden" name="id" value="${editingId}"/>
			</c:if>

			<div class="cr-sb-source-row">
				<label class="cr-sb-field cr-sb-name">
					<spring:message code="clinicreporting.exports.name"/>
					<input type="text" name="name" maxlength="120" value="<c:out value='${exportName}'/>"/>
				</label>
				<label class="cr-sb-field cr-sb-desc">
					<spring:message code="clinicreporting.exports.description"/>
					<input type="text" name="description" maxlength="500" value="<c:out value='${exportDescription}'/>"/>
				</label>
			</div>
			<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.nameHint"/></p>

			<div class="cr-sb-source-row">
				<label class="cr-sb-field">
					<spring:message code="clinicreporting.exports.mode"/>
					<select name="mode" onchange="crSyncExportMode(this)">
						<option value="pipeline"<c:if test="${activeMode == 'pipeline'}"> selected</c:if>><spring:message code="clinicreporting.exports.modePipeline"/></option>
						<option value="raw"<c:if test="${activeMode == 'raw'}"> selected</c:if>><spring:message code="clinicreporting.exports.modeRaw"/></option>
					</select>
				</label>
				<label class="cr-sb-field cr-sb-field-narrow">
					<spring:message code="clinicreporting.exports.rowLimit"/>
					<input type="number" name="rowLimit" min="1" max="${maxRows}" value="${rowLimit}"/>
				</label>
			</div>
			<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.rowLimitHint"/></p>

			<c:if test="${activeMode == 'pipeline'}">
				<div class="cr-sb-mode-panel" data-mode-panel="pipeline">
				<c:if test="${not empty source}">
					<label class="cr-sb-field">
						<spring:message code="clinicreporting.exports.source"/>
						<select name="table">
							<c:forEach var="group" items="${categories}">
								<optgroup label="${group.label}">
									<c:forEach var="name" items="${group.tables}">
										<option value="${name}"<c:if test="${name == table}"> selected</c:if>>${name}</option>
									</c:forEach>
								</optgroup>
							</c:forEach>
						</select>
					</label>
					<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.sourceHint"/></p>

					<div class="cr-sb-columns">
						<h3><spring:message code="clinicreporting.exports.columns"/></h3>
						<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.columnsHint"/> <spring:message code="clinicreporting.exports.columnLimitHint" arguments="${maxColumns}"/></p>
						<c:if test="${not empty withheldColumns}">
							<p class="cr-sb-hint"><c:forEach var="withheld" items="${withheldColumns}">[${withheld}] </c:forEach></p>
						</c:if>
						<div class="cr-sb-chip-grid">
							<c:forEach var="column" items="${source.columns}">
								<label class="cr-sb-chip">
									<input type="checkbox" name="columns" value="${column}"<c:if test="${selectedFlags[column]}"> checked</c:if>/>
									<span>${column}</span>
								</label>
							</c:forEach>
						</div>
					</div>

					<div class="cr-sb-filters">
						<h3><spring:message code="clinicreporting.exports.filters"/></h3>
						<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.filtersHint"/></p>
						<div class="cr-sb-filter-head">
							<span><spring:message code="clinicreporting.exports.filterColumn"/></span>
							<span><spring:message code="clinicreporting.exports.filterOp"/></span>
							<span><spring:message code="clinicreporting.exports.filterValue"/></span>
						</div>
						<c:forEach var="row" items="${filterRows}">
							<div class="cr-sb-filter">
								<select name="${row.columnField}">
									<option value=""></option>
									<c:forEach var="column" items="${source.columns}">
										<option value="${column}"<c:if test="${row.column == column}"> selected</c:if>>${column}</option>
									</c:forEach>
								</select>
								<select name="${row.operatorField}">
									<option value=""></option>
									<c:forEach var="operator" items="${operators}">
										<option value="${operator}"<c:if test="${row.operatorName == operator}"> selected</c:if>>${operator.label}</option>
									</c:forEach>
								</select>
								<input type="text" name="${row.valueField}" value="<c:out value='${row.value}'/>"/>
							</div>
						</c:forEach>
					</div>
				</c:if>
				</div>
			</c:if>

			<div class="cr-sb-mode-panel" data-mode-panel="raw"<c:if test="${activeMode != 'raw'}"> style="display:none"</c:if>>
				<c:if test="${rawAllowed}">
					<h3><spring:message code="clinicreporting.exports.rawTitle"/></h3>
					<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.rawIntro"/></p>
					<textarea class="cr-sb-raw" name="rawSql" rows="6" cols="100"><c:out value="${rawSql}"/></textarea>
					<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.rawPolicy"/></p>
					<details class="cr-sb-policy">
						<summary><spring:message code="clinicreporting.exports.rawPolicyKeywords"/></summary>
						<p class="cr-muted"><c:forEach var="keyword" items="${rawPolicy}">${keyword} </c:forEach></p>
					</details>
				</c:if>
				<c:if test="${not rawAllowed}">
					<p class="cr-muted"><spring:message code="clinicreporting.exports.rawNotAllowed"/></p>
				</c:if>
			</div>

			<c:if test="${not empty formError}">
				<div class="cr-notice cr-notice-danger">
					<h3><spring:message code="clinicreporting.exports.formErrored"/></h3>
					<p><c:out value="${formError}"/></p>
				</div>
			</c:if>

			<div class="cr-filter-actions">
				<button class="cr-btn" type="submit" name="action" value="preview"><spring:message code="clinicreporting.exports.preview"/></button>
				<button class="cr-btn cr-btn-primary" type="submit" name="action" value="save"><spring:message code="clinicreporting.exports.save"/></button>
			</div>
		</form>
	</section>
	</c:if>

	<c:if test="${not empty preview}">
		<section class="cr-card cr-sb-card">
			<div class="cr-table-head">
				<h2><spring:message code="clinicreporting.exports.preview"/></h2>
				<button type="button" class="cr-btn-export"
				        data-export="builder-preview" data-export-name="clinic-report-preview.csv"><spring:message code="clinicreporting.exports.exportCsv"/></button>
			</div>
			<p class="cr-sb-summary">
				<spring:message code="clinicreporting.exports.rowCount" arguments="${preview.rowCount},${preview.elapsedMillis}"/>
			</p>
			<c:if test="${preview.truncated}">
				<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.truncated"/></p>
			</c:if>

			<h3><spring:message code="clinicreporting.exports.sql"/></h3>
			<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.sqlHint"/></p>
			<pre class="cr-sb-sql"><c:out value="${preview.sql}"/></pre>

			<c:choose>
			<c:when test="${preview.emptyResult}">
				<p class="cr-muted"><spring:message code="clinicreporting.exports.noRows"/></p>
			</c:when>
			<c:otherwise>
				<div class="cr-table-wrap cr-sb-preview-wrap">
					<table class="cr-table cr-sb-preview" id="builder-preview">
						<thead>
							<tr>
								<c:forEach var="label" items="${preview.labels}">
									<th title="<c:out value="${label}"/>"><c:out value="${label}"/></th>
								</c:forEach>
							</tr>
						</thead>
						<tbody>
							<c:forEach var="row" items="${preview.rows}">
								<tr>
									<c:forEach var="cell" items="${row}">
										<td><c:out value="${cell}"/></td>
									</c:forEach>
								</tr>
							</c:forEach>
						</tbody>
					</table>
				</div>
			</c:otherwise>
			</c:choose>
		</section>
	</c:if>

	<section class="cr-card cr-sb-card">
		<%--
			The saved-export list opens on a short preview so a page carrying many exports still reaches
			the builder without scrolling past a long table. The remaining rows are rendered but held
			back, and the toggle reveals them; the noscript rule shows every row when scripting is
			unavailable, so a row is never unreachable.
		--%>
		<c:set var="exportListPreview" value="5"/>
		<div class="cr-table-head">
			<h2>
				<spring:message code="clinicreporting.exports.title"/>
				<span class="cr-collapsible-count"><spring:message code="clinicreporting.exports.listCount" arguments="${fn:length(exports) + fn:length(unavailableExports)}"/></span>
			</h2>
			<c:if test="${fn:length(exports) + fn:length(unavailableExports) > exportListPreview}">
				<button type="button" class="cr-btn cr-exports-toggle" data-exports-toggle aria-expanded="false">
					<span class="cr-exports-toggle-open"><spring:message code="clinicreporting.exports.showAll" arguments="${fn:length(exports) + fn:length(unavailableExports)}"/></span>
					<span class="cr-exports-toggle-close"><spring:message code="clinicreporting.exports.showFewer"/></span>
				</button>
			</c:if>
		</div>
		<noscript><style>.cr-app .cr-exports-table tbody tr.cr-export-overflow { display: table-row; }</style></noscript>

		<c:choose>
		<c:when test="${empty exports and empty unavailableExports}">
			<p class="cr-muted"><spring:message code="clinicreporting.exports.emptyList"/></p>
		</c:when>
		<c:otherwise>
			<div class="cr-table-wrap">
				<table class="cr-table cr-exports-table" data-exports-table>
					<thead>
						<tr>
							<th><spring:message code="clinicreporting.exports.name"/></th>
							<th><spring:message code="clinicreporting.exports.description"/></th>
							<th><spring:message code="clinicreporting.exports.source"/></th>
							<th class="num"><spring:message code="clinicreporting.exports.rowsColumn"/></th>
							<th> </th>
						</tr>
					</thead>
					<tbody>
						<c:forEach var="exp" items="${exports}" varStatus="exportRow">
							<tr<c:if test="${exportRow.index >= exportListPreview}"> class="cr-export-overflow"</c:if>>
								<td>
									<a href="${pageContext.request.contextPath}/module/clinicreporting/exports.htm?id=${exp.id}"><c:out value="${exp.name}"/></a>
									<div class="cr-muted cr-exports-setup">
										<spring:message code="clinicreporting.exports.columnsOf" arguments="${exp.columns.size()}"/>
										<c:if test="${not empty exp.filters}">, <spring:message code="clinicreporting.exports.filtersOf" arguments="${exp.filters.size()}"/></c:if>
									</div>
								</td>
								<td><c:out value="${exp.description}"/></td>
								<td>
									<c:choose>
										<c:when test="${exp.raw}"><spring:message code="clinicreporting.exports.modeRaw"/></c:when>
										<c:otherwise><c:out value="${exp.sourceTable}"/></c:otherwise>
									</c:choose>
								</td>
								<td class="num">${exp.rowLimit}</td>
								<td class="cr-exports-actions">
									<a class="cr-btn cr-btn-export" href="${pageContext.request.contextPath}/module/clinicreporting/exports.htm?action=run&amp;id=${exp.id}"><spring:message code="clinicreporting.exports.preview"/></a>
									<a class="cr-btn cr-btn-export" href="${pageContext.request.contextPath}/module/clinicreporting/exports.htm?action=csv&amp;id=${exp.id}"><spring:message code="clinicreporting.exports.csv"/></a>
									<a class="cr-btn cr-btn-export" href="${pageContext.request.contextPath}/module/clinicreporting/exports.htm?id=${exp.id}">Edit</a>
									<form method="post" action="${pageContext.request.contextPath}/module/clinicreporting/exports.htm" class="cr-exports-retire"
									      onsubmit="return crRetireExport(this, '<spring:message code="clinicreporting.exports.retireReasonPrompt" htmlEscape="false"/>');">
										<input type="hidden" name="id" value="${exp.id}"/>
										<input type="hidden" name="reason" value=""/>
										<button class="cr-btn cr-btn-export cr-btn-danger" type="submit" name="action" value="retire"><spring:message code="clinicreporting.exports.retire"/></button>
									</form>
								</td>
							</tr>
						</c:forEach>
						<%--
							The shipped names whose table is withheld. They are listed so the page carries the
							whole shipped list, but they are not links and carry no actions: there is no table to
							read from, so there is nothing to build, run or download.
						--%>
						<c:forEach var="exp" items="${unavailableExports}" varStatus="exportRow">
							<tr class="cr-exports-unavailable<c:if test="${fn:length(exports) + exportRow.index >= exportListPreview}"> cr-export-overflow</c:if>">
								<td><c:out value="${exp.name}"/></td>
								<td><c:out value="${exp.description}"/></td>
								<td><c:out value="${exp.sourceTable}"/></td>
								<td class="num"> </td>
								<td class="cr-exports-actions">
									<span class="cr-muted"><spring:message code="clinicreporting.exports.unavailable"/></span>
								</td>
							</tr>
						</c:forEach>
					</tbody>
				</table>
			</div>
		</c:otherwise>
		</c:choose>
	</section>

	<%--
		The retired exports. They stay out of the list above so that list reads as what can be
		run right now, and they appear here only to be brought back. Reactivating one clears the
		retirement and revives the report that mirrors it on the Reports page.
	--%>
	<c:if test="${not empty retiredExports}">
	<section class="cr-card cr-sb-card">
		<h2><spring:message code="clinicreporting.exports.retiredTitle"/></h2>
		<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.retiredLead"/></p>
		<div class="cr-table-wrap">
			<table class="cr-table cr-exports-table">
				<thead>
					<tr>
						<th><spring:message code="clinicreporting.exports.name"/></th>
						<th><spring:message code="clinicreporting.exports.description"/></th>
						<th><spring:message code="clinicreporting.exports.source"/></th>
						<th><spring:message code="clinicreporting.exports.retiredBecause"/></th>
						<th> </th>
					</tr>
				</thead>
				<tbody>
					<c:forEach var="exp" items="${retiredExports}">
						<tr>
							<td><c:out value="${exp.name}"/></td>
							<td><c:out value="${exp.description}"/></td>
							<td>
								<c:choose>
									<c:when test="${exp.raw}"><spring:message code="clinicreporting.exports.modeRaw"/></c:when>
									<c:otherwise><c:out value="${exp.sourceTable}"/></c:otherwise>
								</c:choose>
							</td>
							<td><c:out value="${exp.retireReason}"/></td>
							<td class="cr-exports-actions">
								<form method="post" action="${pageContext.request.contextPath}/module/clinicreporting/exports.htm" class="cr-exports-retire">
									<input type="hidden" name="id" value="${exp.id}"/>
									<button class="cr-btn cr-btn-export" type="submit" name="action" value="reactivate"><spring:message code="clinicreporting.exports.reactivate"/></button>
								</form>
							</td>
						</tr>
					</c:forEach>
				</tbody>
			</table>
		</div>
	</section>
	</c:if>

	<c:if test="${not empty runResult}">
		<section class="cr-card cr-sb-card">
			<div class="cr-table-head">
				<h2><c:out value="${runExport.name}"/></h2>
				<button type="button" class="cr-btn-export"
				        data-export="run-preview" data-export-name="clinic-report-run.csv"><spring:message code="clinicreporting.exports.exportCsv"/></button>
			</div>
			<p class="cr-sb-summary">
				<spring:message code="clinicreporting.exports.rowCount" arguments="${runResult.rowCount},${runResult.elapsedMillis}"/>
			</p>
			<c:if test="${runResult.truncated}">
				<p class="cr-sb-hint"><spring:message code="clinicreporting.exports.truncated"/></p>
			</c:if>

			<h3><spring:message code="clinicreporting.exports.sql"/></h3>
			<pre class="cr-sb-sql"><c:out value="${runResult.sql}"/></pre>

			<c:choose>
			<c:when test="${runResult.emptyResult}">
				<p class="cr-muted"><spring:message code="clinicreporting.exports.noRows"/></p>
			</c:when>
			<c:otherwise>
				<div class="cr-table-wrap cr-sb-preview-wrap">
					<table class="cr-table cr-sb-preview" id="run-preview">
						<thead>
							<tr>
								<c:forEach var="label" items="${runResult.labels}">
									<th title="<c:out value="${label}"/>"><c:out value="${label}"/></th>
								</c:forEach>
							</tr>
						</thead>
						<tbody>
							<c:forEach var="row" items="${runResult.rows}">
								<tr>
									<c:forEach var="cell" items="${row}">
										<td><c:out value="${cell}"/></td>
									</c:forEach>
								</tr>
							</c:forEach>
						</tbody>
					</table>
				</div>
			</c:otherwise>
			</c:choose>
		</section>
	</c:if>

</c:otherwise>
</c:choose>

<%@ include file="chrome-end.jsp" %>
</div>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/reports.js" />

<%@ include file="/WEB-INF/template/footer.jsp" %>