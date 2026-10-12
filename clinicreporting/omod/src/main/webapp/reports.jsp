<%@ include file="/WEB-INF/template/include.jsp" %>
<openmrs:require privilege="View Reports" otherwise="/login.htm" redirect="/module/clinicreporting/reports.htm"/>
<%@ include file="/WEB-INF/template/header.jsp" %>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/styles/reports.css" />

<div class="cr-app">
<%@ include file="chrome.jsp" %>

<c:if test="${not empty error}">
	<div class="cr-notice cr-notice-danger"><spring:message code="${error}"/></div>
</c:if>

<section class="cr-card cr-filters">
	<form method="get" action="${pageContext.request.contextPath}/module/clinicreporting/reports.htm">
		<div class="cr-filter-grid">
			<label><spring:message code="clinicreporting.reports.dateFrom"/>
				<input type="date" name="dateFrom" value="${dateFrom}"/>
			</label>
			<label><spring:message code="clinicreporting.reports.dateTo"/>
				<input type="date" name="dateTo" value="${dateTo}"/>
			</label>
		</div>
		<div class="cr-filter-actions">
			<button class="cr-btn cr-btn-primary" type="submit"><spring:message code="clinicreporting.reports.run"/></button>
			<span class="cr-filter-summary">${dateFrom} &ndash; ${dateTo}</span>
		</div>
	</form>
</section>

<c:if test="${not empty encounters}">

	<section class="cr-kpis">
		<div class="cr-card cr-kpi">
			<div class="cr-kpi-title"><spring:message code="clinicreporting.reports.encounters"/></div>
			<div class="cr-kpi-value">${encounterTotal}</div>
		</div>
		<div class="cr-card cr-kpi" data-kpi="patients">
			<div class="cr-kpi-title"><spring:message code="clinicreporting.reports.patients"/></div>
			<div class="cr-kpi-value">${distinctPatients}</div>
		</div>
	<c:if test="${not empty turnaround and turnaround.completedOrders > 0}">
			<div class="cr-card cr-kpi" data-kpi="turnaround">
				<div class="cr-kpi-title"><spring:message code="clinicreporting.reports.completedOrders"/></div>
				<div class="cr-kpi-value">${turnaround.completedOrders}</div>
			</div>
			<div class="cr-card cr-kpi" data-kpi="turnaround">
				<div class="cr-kpi-title"><spring:message code="clinicreporting.reports.medianTurnaround"/></div>
				<div class="cr-kpi-value">${turnaround.medianHours}<span class="cr-kpi-unit">h</span></div>
			</div>
		</c:if>
	</section>

	<section class="cr-charts">

		<div class="cr-card cr-chart-card cr-span2">
			<h2><spring:message code="clinicreporting.reports.encountersOverTime"/></h2>
			<p class="cr-card-sub"><spring:message code="clinicreporting.reports.trendSub"/></p>
			<div class="cr-chart-box cr-chart-box-lg" data-chart="line" data-source="table-encounters">
				<canvas></canvas>
				<div class="cr-chart-empty"><spring:message code="clinicreporting.reports.encountersOverTime"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
		</div>

		<div class="cr-card cr-chart-card">
			<h2><spring:message code="clinicreporting.reports.byEncounterType"/></h2>
			<p class="cr-card-sub"><spring:message code="clinicreporting.reports.byEncounterTypeSub"/></p>
			<div class="cr-chart-box" data-chart="doughnut" data-source="table-encounter-type">
				<canvas></canvas>
				<div class="cr-chart-empty"><spring:message code="clinicreporting.reports.byEncounterType"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
		</div>

		<div class="cr-card cr-chart-card cr-span2">
			<h2><spring:message code="clinicreporting.reports.topConcepts"/></h2>
			<p class="cr-card-sub"><spring:message code="clinicreporting.reports.topConceptsSub"/></p>
			<div class="cr-chart-box cr-chart-box-tall" data-chart="hbar" data-source="table-concepts">
				<canvas></canvas>
				<div class="cr-chart-empty"><spring:message code="clinicreporting.reports.topConcepts"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
		</div>

		<div class="cr-card cr-chart-card">
			<h2><spring:message code="clinicreporting.reports.labResults"/></h2>
			<p class="cr-card-sub"><spring:message code="clinicreporting.reports.labResultsSub"/></p>
			<div class="cr-chart-box" data-chart="doughnut" data-source="table-result-status">
				<canvas></canvas>
				<div class="cr-chart-empty"><spring:message code="clinicreporting.reports.labResults"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
		</div>

	</section>

	<section class="cr-card cr-table-card">
		<div class="cr-table-head">
			<h2><spring:message code="clinicreporting.reports.encountersOverTime"/></h2>
			<button type="button" class="cr-btn-export"
			        data-export="table-encounters" data-export-name="clinic-reports-encounters.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
		</div>
		<div class="cr-table-wrap">
			<table class="cr-table" id="table-encounters">
				<thead>
					<tr>
						<th><spring:message code="clinicreporting.reports.period"/></th>
						<th class="num"><spring:message code="clinicreporting.reports.encounters"/></th>
					</tr>
				</thead>
				<tbody>
					<c:forEach var="point" items="${encounters}">
						<tr>
							<td>${point.period}</td>
							<td class="num">${point.count}</td>
						</tr>
					</c:forEach>
				</tbody>
			</table>
		</div>
	</section>

	<section class="cr-card cr-table-card">
		<div class="cr-table-head">
			<h2><spring:message code="clinicreporting.reports.byEncounterType"/></h2>
			<button type="button" class="cr-btn-export"
			        data-export="table-encounter-type" data-export-name="clinic-reports-encounter-type.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
		</div>
		<div class="cr-table-wrap">
			<table class="cr-table" id="table-encounter-type" data-share="true">
				<thead>
					<tr>
						<th><spring:message code="clinicreporting.reports.type"/></th>
						<th class="num"><spring:message code="clinicreporting.reports.encounters"/></th>
					</tr>
				</thead>
				<tbody>
					<c:choose>
					<c:when test="${empty byEncounterType}">
						<tr><td colspan="2" class="cr-empty-row"><spring:message code="clinicreporting.common.noRows"/></td></tr>
					</c:when>
					<c:otherwise>
						<c:forEach var="row" items="${byEncounterType}">
							<tr>
								<td>${row.label}</td>
								<td class="num cr-share">${row.count}</td>
							</tr>
						</c:forEach>
						<tr class="cr-total-row">
							<td class="cr-total-label"><spring:message code="clinicreporting.reports.total"/></td>
							<td class="num cr-total-label">${encounterTotal}</td>
						</tr>
					</c:otherwise>
					</c:choose>
				</tbody>
			</table>
		</div>
	</section>

	<section class="cr-card cr-table-card">
		<div class="cr-table-head">
			<h2><spring:message code="clinicreporting.reports.topConcepts"/></h2>
			<button type="button" class="cr-btn-export"
			        data-export="table-concepts" data-export-name="clinic-reports-concepts.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
		</div>
		<div class="cr-table-wrap">
			<table class="cr-table" id="table-concepts" data-share="true">
				<thead>
					<tr>
						<th><spring:message code="clinicreporting.reports.concept"/></th>
						<th class="num"><spring:message code="clinicreporting.reports.recorded"/></th>
					</tr>
				</thead>
				<tbody>
					<c:choose>
					<c:when test="${empty topConcepts}">
						<tr><td colspan="2" class="cr-empty-row"><spring:message code="clinicreporting.common.noRows"/></td></tr>
					</c:when>
					<c:otherwise>
						<c:forEach var="row" items="${topConcepts}">
							<tr>
								<td>${row.label}</td>
								<td class="num cr-share">${row.count}</td>
							</tr>
						</c:forEach>
					</c:otherwise>
					</c:choose>
				</tbody>
			</table>
		</div>
	</section>

	<section class="cr-card cr-table-card">
		<div class="cr-table-head">
			<h2><spring:message code="clinicreporting.reports.labResults"/></h2>
			<button type="button" class="cr-btn-export"
			        data-export="table-result-status" data-export-name="clinic-reports-lab-status.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
		</div>
		<div class="cr-table-wrap">
			<table class="cr-table" id="table-result-status" data-share="true">
				<thead>
					<tr>
						<th><spring:message code="clinicreporting.reports.status"/></th>
						<th class="num"><spring:message code="clinicreporting.reports.results"/></th>
					</tr>
				</thead>
				<tbody>
					<c:choose>
					<c:when test="${empty byResultStatus}">
						<tr><td colspan="2" class="cr-empty-row"><spring:message code="clinicreporting.common.noRows"/></td></tr>
					</c:when>
					<c:otherwise>
						<c:forEach var="row" items="${byResultStatus}">
							<tr>
								<td>${row.label}</td>
								<td class="num cr-share">${row.count}</td>
							</tr>
						</c:forEach>
					</c:otherwise>
					</c:choose>
				</tbody>
			</table>
		</div>
		<c:if test="${not empty byTest}">
			<div class="cr-table-head">
				<h2><spring:message code="clinicreporting.reports.byTest"/></h2>
				<button type="button" class="cr-btn-export"
				        data-export="table-tests" data-export-name="clinic-reports-tests.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
			</div>
			<div class="cr-table-wrap">
				<table class="cr-table" id="table-tests" data-share="true">
					<thead>
						<tr>
							<th><spring:message code="clinicreporting.reports.concept"/></th>
							<th class="num"><spring:message code="clinicreporting.reports.recorded"/></th>
						</tr>
					</thead>
					<tbody>
						<c:forEach var="row" items="${byTest}">
							<tr>
								<td>${row.label}</td>
								<td class="num cr-share">${row.count}</td>
							</tr>
						</c:forEach>
					</tbody>
				</table>
			</div>
		</c:if>
	</section>

	<c:if test="${not empty turnaround and turnaround.completedOrders > 0}">
		<p class="cr-footnote">
			<spring:message code="clinicreporting.reports.turnaroundFootnote"
			                arguments="${turnaround.completedOrders},${turnaround.medianHours},${turnaround.p90Hours},${turnaround.minHours},${turnaround.maxHours}"/>
		</p>
	</c:if>

</c:if>

<%@ include file="chrome-end.jsp" %>
</div>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/chart.umd.min.js" />
<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/reports.js" />

<%@ include file="/WEB-INF/template/footer.jsp" %>
