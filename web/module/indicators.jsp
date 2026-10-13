<%@ include file="/WEB-INF/template/include.jsp" %>
<%@ include file="/WEB-INF/template/header.jsp" %>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/styles/reports.css" />

<%--
  The indicator sandbox. Two panels, each with its own submit button and its own preview:
  the declarative pipeline over an allowlisted table, and the raw SQL escape hatch for
  holders of the second privilege. They are stacked rather than tabbed so that switching
  between them never discards what has been typed into the other.

  Access control is enforced by the controller, not by this page. The controller checks
  "Manage Report Indicators" before it resolves a single source, and re-checks "Run Raw
  Report SQL" on every raw submission, so this file never has to be trusted to withhold
  anything: if it were served to somebody who should not see it, the page behind it would
  still have run nothing.
--%>

<div class="cr-app">
<%@ include file="chrome.jsp" %>

<c:choose>

<c:when test="${denied}">
	<div class="cr-notice">
		<h3><spring:message code="clinicreporting.indicators.deniedTitle"/></h3>
		<p><spring:message code="clinicreporting.indicators.deniedBody"/></p>
	</div>
</c:when>

<c:when test="${noSources}">
	<div class="cr-notice cr-notice-danger">
		<h3><spring:message code="clinicreporting.indicators.title"/></h3>
		<p><spring:message code="clinicreporting.indicators.noSources"/></p>
	</div>
</c:when>

<c:otherwise>

	<section class="cr-card cr-sb-intro">
		<h2><spring:message code="clinicreporting.indicators.title"/></h2>
		<p><spring:message code="clinicreporting.indicators.lead"/></p>
	</section>

	<section class="cr-card cr-filters cr-sb-card">
		<h2><spring:message code="clinicreporting.indicators.source"/></h2>
		<form method="get" action="${pageContext.request.contextPath}/module/clinicreporting/indicators.htm">
			<input type="hidden" name="mode" value="pipeline"/>
			<div class="cr-sb-source-row">
				<label class="cr-sb-field">
					<spring:message code="clinicreporting.indicators.source"/>
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
				<label class="cr-sb-field cr-sb-field-narrow">
					<spring:message code="clinicreporting.indicators.rowLimit"/>
					<input type="number" name="limit" min="1" max="${maxRows}" value="${limit}"/>
				</label>
				<div class="cr-filter-actions">
					<button class="cr-btn" type="submit" name="load" value="1"><spring:message code="clinicreporting.indicators.loadColumns"/></button>
					<button class="cr-btn cr-btn-primary" type="submit" name="run" value="1"><spring:message code="clinicreporting.indicators.preview"/></button>
				</div>
			</div>
			<p class="cr-sb-hint"><spring:message code="clinicreporting.indicators.sourceHint"/></p>

			<div class="cr-sb-columns">
				<h3><spring:message code="clinicreporting.indicators.columns"/></h3>
				<p class="cr-sb-hint"><spring:message code="clinicreporting.indicators.columnsHint"/> <spring:message code="clinicreporting.indicators.columnLimitHint" arguments="${maxColumns}"/></p>
				<div class="cr-sb-chip-grid">
					<c:forEach var="column" items="${source.columns}">
						<label class="cr-sb-chip">
							<input type="checkbox" name="columns" value="${column}"<c:if test="${selectedFlags[column]}"> checked</c:if>/>
							<span>${column}</span>
						</label>
					</c:forEach>
				</div>
			</div>

			<div class="cr-sb-steps">
				<h3><spring:message code="clinicreporting.indicators.steps"/></h3>
				<p class="cr-sb-hint"><spring:message code="clinicreporting.indicators.stepsHint"/> <spring:message code="clinicreporting.indicators.stepLimitHint" arguments="${stepRowsAvailable}"/></p>
				<div class="cr-sb-step-head">
					<span><spring:message code="clinicreporting.indicators.stepType"/></span>
					<span><spring:message code="clinicreporting.indicators.stepColumn"/></span>
					<span><spring:message code="clinicreporting.indicators.stepArgument"/></span>
					<span><spring:message code="clinicreporting.indicators.stepPairs"/></span>
				</div>
				<c:forEach var="row" items="${stepRows}">
					<div class="cr-sb-step">
						<select name="${row.typeField}">
							<option value=""></option>
							<c:if test="${row.unknownType}">
								<option value="__unknown__" selected><spring:message code="clinicreporting.indicators.stepUnrecognised"/></option>
							</c:if>
							<c:forEach var="type" items="${stepTypes}">
								<option value="${type}"<c:if test="${row.typeName == type}"> selected</c:if>>${type.label}</option>
							</c:forEach>
						</select>
						<select name="${row.columnField}">
							<option value=""></option>
							<c:forEach var="column" items="${source.columns}">
								<option value="${column}"<c:if test="${row.column == column}"> selected</c:if>>${column}</option>
							</c:forEach>
						</select>
						<input type="text" name="${row.argumentField}" value="${row.argument}"/>
						<textarea name="${row.pairsField}" rows="2" cols="40">${row.pairs}</textarea>
					</div>
				</c:forEach>
			</div>
		</form>
	</section>

	<c:if test="${rawAllowed}">
		<section class="cr-card cr-filters cr-sb-card">
			<h2><spring:message code="clinicreporting.indicators.rawTitle"/></h2>
			<p><spring:message code="clinicreporting.indicators.rawIntro"/></p>
			<form method="get" action="${pageContext.request.contextPath}/module/clinicreporting/indicators.htm">
				<input type="hidden" name="mode" value="raw"/>
				<textarea class="cr-sb-raw" name="rawSql" rows="6" cols="100">${rawSql}</textarea>
				<div class="cr-filter-actions">
					<label class="cr-sb-field cr-sb-field-narrow">
						<spring:message code="clinicreporting.indicators.rowLimit"/>
						<input type="number" name="limit" min="1" max="${maxRows}" value="${limit}"/>
					</label>
					<button class="cr-btn cr-btn-primary" type="submit" name="run" value="1"><spring:message code="clinicreporting.indicators.rawRun"/></button>
				</div>
			</form>
			<p class="cr-sb-hint"><spring:message code="clinicreporting.indicators.rawPolicy"/></p>
			<details class="cr-sb-policy">
				<summary><spring:message code="clinicreporting.indicators.rawPolicyKeywords"/></summary>
				<p class="cr-muted"><c:forEach var="keyword" items="${rawPolicy}">${keyword} </c:forEach></p>
			</details>
		</section>
	</c:if>

	<c:if test="${not rawAllowed}">
		<section class="cr-card cr-filters cr-sb-card cr-sb-muted-card">
			<h2><spring:message code="clinicreporting.indicators.rawTitle"/></h2>
			<p class="cr-muted"><spring:message code="clinicreporting.indicators.rawNotAllowed"/></p>
		</section>
	</c:if>

	<c:if test="${not empty formError}">
		<div class="cr-notice cr-notice-danger">
			<h3><spring:message code="clinicreporting.indicators.title"/></h3>
			<p><c:out value="${formError}"/></p>
		</div>
	</c:if>

	<c:if test="${not empty preview}">
		<section class="cr-card cr-sb-card">
			<div class="cr-table-head">
				<h2><spring:message code="clinicreporting.indicators.preview"/></h2>
				<button type="button" class="cr-btn-export"
				        data-export="table-preview" data-export-name="clinic-report-indicator-preview.csv"><spring:message code="clinicreporting.indicators.exportCsv"/></button>
			</div>
			<p class="cr-sb-summary">
				<spring:message code="clinicreporting.indicators.rowCount" arguments="${preview.rowCount},${preview.elapsedMillis}"/>
			</p>
			<c:if test="${preview.truncated}">
				<p class="cr-sb-hint"><spring:message code="clinicreporting.indicators.truncated"/></p>
			</c:if>

			<h3><spring:message code="clinicreporting.indicators.sql"/></h3>
			<p class="cr-sb-hint"><spring:message code="clinicreporting.indicators.sqlHint"/></p>
			<pre class="cr-sb-sql"><c:out value="${preview.sql}"/></pre>

			<c:choose>
			<c:when test="${preview.empty}">
				<p class="cr-muted"><spring:message code="clinicreporting.indicators.noRows"/></p>
			</c:when>
			<c:otherwise>
				<div class="cr-table-wrap cr-sb-preview-wrap">
					<table class="cr-table cr-sb-preview" id="table-preview">
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

</c:otherwise>
</c:choose>

<%@ include file="chrome-end.jsp" %>
</div>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/reports.js" />

<%@ include file="/WEB-INF/template/footer.jsp" %>
