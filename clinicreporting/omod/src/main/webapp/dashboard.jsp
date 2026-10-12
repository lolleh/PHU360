<%@ include file="/WEB-INF/template/include.jsp" %>
<openmrs:require privilege="View Reports" otherwise="/login.htm" redirect="/module/clinicreporting/programs.htm"/>
<%@ include file="/WEB-INF/template/header.jsp" %>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/styles/reports.css" />

<div class="cr-app">
<%@ include file="chrome.jsp" %>

<c:if test="${not empty error}">
	<div class="cr-notice cr-notice-danger">
		<spring:message code="${error}"/>
		<c:if test="${not empty definitionDetail}">
			<div class="cr-notice-detail"><c:out value="${definitionDetail}"/></div>
		</c:if>
	</div>
</c:if>

<c:if test="${not empty matrixError}">
	<div class="cr-notice cr-notice-danger"><spring:message code="${matrixError}"/></div>
</c:if>

<c:choose>
<c:when test="${empty program}">
	<section class="cr-card">
		<p class="cr-muted">
			<spring:message code="clinicreporting.programs.unknownProgrammeDetail"/>
			<c:if test="${not empty requestedProgramme}">
				<br><code><c:out value="${requestedProgramme}"/></code>
			</c:if>
		</p>
		<p><a class="cr-btn" href="${pageContext.request.contextPath}/module/clinicreporting/programs.htm"><spring:message code="clinicreporting.programs.backToList"/></a></p>
	</section>
</c:when>
<c:otherwise>

<div class="cr-page-head">
	<div>
		<div class="cr-program-badge cr-program-badge-lg">${program.badge}</div>
		<h1>${program.name}</h1>
		<p class="cr-card-sub">${program.description}</p>
	</div>
	<a class="cr-btn" href="${pageContext.request.contextPath}/module/clinicreporting/programs.htm"><spring:message code="clinicreporting.programs.backToList"/></a>
</div>

<section class="cr-card cr-filters">
	<form method="get" action="${pageContext.request.contextPath}/module/clinicreporting/dashboard.htm">
		<input type="hidden" name="program" value="${programUuid}"/>
		<div class="cr-filter-grid">
			<label><spring:message code="clinicreporting.reports.dateFrom"/>
				<input type="date" name="dateFrom" value="${dateFrom}"/>
			</label>
			<label><spring:message code="clinicreporting.reports.dateTo"/>
				<input type="date" name="dateTo" value="${dateTo}"/>
			</label>
		</div>
		<div class="cr-filter-actions">
			<button class="cr-btn cr-btn-primary" type="submit"><spring:message code="clinicreporting.common.run"/></button>
			<span class="cr-filter-summary">
				<c:choose>
					<c:when test="${builtin}"><spring:message code="clinicreporting.programs.range" arguments="${dateFrom},${dateTo}"/></c:when>
					<c:otherwise><spring:message code="clinicreporting.programs.rangeSummary" arguments="${dateFrom},${dateTo},${previousDateFrom},${previousDateTo}"/></c:otherwise>
				</c:choose>
			</span>
		</div>
	</form>
</section>

<c:forEach var="result" items="${results}">

	<section class="cr-card cr-indicator">
		<div class="cr-indicator-head">
			<div>
				<h2><c:out value="${result.definition.label}"/></h2>
				<c:if test="${not empty result.definition.description}">
					<p class="cr-card-sub"><c:out value="${result.definition.description}"/></p>
				</c:if>
			</div>
			<div class="cr-indicator-flags">
				<c:if test="${not empty result.definition.target}">
					<span class="cr-indicator-target is-${result.targetState}">
						<spring:message code="clinicreporting.programs.targetOf" arguments="${result.definition.target}"/>
					</span>
				</c:if>
				<c:if test="${not result.available}">
					<span class="cr-indicator-target is-unavailable"><spring:message code="clinicreporting.programs.notAvailableShort"/></span>
				</c:if>
			</div>
		</div>

		<c:choose>
		<c:when test="${not result.available}">
			<div class="cr-unavailable">
				<strong><spring:message code="clinicreporting.programs.notAvailable"/></strong>
				<span><c:out value="${result.unavailableReason}"/></span>
			</div>
		</c:when>

		<c:when test="${not empty result.breakdown}">
			<div class="cr-indicator-body">
				<div class="cr-chart-box" data-chart="${result.definition.chartKind}" data-source="table-${result.definition.domId}">
					<canvas></canvas>
					<div class="cr-chart-empty"><c:out value="${result.definition.label}"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
				</div>
				<div class="cr-indicator-table">
					<div class="cr-table-actions">
						<button type="button" class="cr-btn-export"
						        data-export="table-${result.definition.domId}"
						        data-export-name="clinicreporting-${program.badge}-${result.definition.domId}.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
					</div>
					<div class="cr-table-wrap">
						<table class="cr-table" id="table-${result.definition.domId}" data-share="true">
							<thead>
								<tr>
									<th><spring:message code="clinicreporting.reports.category"/></th>
									<th class="num"><spring:message code="clinicreporting.programs.count"/></th>
								</tr>
							</thead>
							<tbody>
								<c:forEach var="row" items="${result.breakdown}">
									<tr>
										<td><c:out value="${row.label}"/></td>
										<td class="num cr-share">${row.count}</td>
									</tr>
								</c:forEach>
							</tbody>
						</table>
					</div>
				</div>
			</div>
		</c:when>

		<c:when test="${not empty result.series}">
			<div class="cr-figure-compact">
				<c:if test="${result.changeDisplay != '-'}">
					<span class="cr-change is-${result.changeDirection}">
						<spring:message code="clinicreporting.programs.vsPrevious" arguments="${result.changeDisplay}"/>
					</span>
				</c:if>
			</div>
			<div class="cr-chart-box cr-chart-box-lg" data-chart="line" data-source="table-${result.definition.domId}">
				<canvas></canvas>
				<div class="cr-chart-empty"><c:out value="${result.definition.label}"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
			<div class="cr-table-actions">
				<button type="button" class="cr-btn-export"
				        data-export="table-${result.definition.domId}"
				        data-export-name="clinicreporting-${program.badge}-${result.definition.domId}.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
			</div>
			<div class="cr-table-wrap cr-table-scroll">
				<table class="cr-table" id="table-${result.definition.domId}">
					<thead>
						<tr>
							<th><spring:message code="clinicreporting.common.period"/></th>
							<th class="num"><spring:message code="clinicreporting.programs.count"/></th>
						</tr>
					</thead>
					<tbody>
						<c:forEach var="point" items="${result.series}">
							<tr>
								<td>${point.period}</td>
								<td class="num">${point.count}</td>
							</tr>
						</c:forEach>
					</tbody>
				</table>
			</div>
		</c:when>

		<c:otherwise>
			<div class="cr-figure">
				<div class="cr-figure-value">
					<c:choose>
					<c:when test="${not empty result.percentDisplay}">${result.percentDisplay}</c:when>
					<c:when test="${not empty result.wholeValue}">${result.wholeValue}</c:when>
					<c:otherwise><spring:message code="clinicreporting.common.noData"/></c:otherwise>
					</c:choose>
				</div>
				<c:if test="${not empty result.numerator}">
					<div class="cr-figure-sub">
						<spring:message code="clinicreporting.programs.ofTotal" arguments="${result.numerator},${result.denominator}"/>
					</div>
				</c:if>
				<c:if test="${not empty result.targetProgress}">
					<div class="cr-progress is-${result.targetState}">
						<i style="width: ${result.targetProgress}%"></i>
					</div>
				</c:if>
				<c:if test="${result.changeDisplay != '-'}">
					<span class="cr-change is-${result.changeDirection}">
						<spring:message code="clinicreporting.programs.vsPrevious" arguments="${result.changeDisplay}"/>
					</span>
				</c:if>
			</div>
		</c:otherwise>
		</c:choose>
	</section>

</c:forEach>

<c:if test="${not empty diagnosisTreatments}">
	<section class="cr-card cr-indicator">
		<div class="cr-indicator-body">
			<div class="cr-chart-box cr-chart-box-tall" data-chart="matrix" data-source="table-diagnosis-treatment">
				<canvas></canvas>
				<div class="cr-chart-empty"><spring:message code="clinicreporting.programs.diagnosesByTreatment"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
			<div class="cr-indicator-table">
				<div class="cr-table-actions">
					<button type="button" class="cr-btn-export"
					        data-export="table-diagnosis-treatment"
					        data-export-name="clinicreporting-${program.badge}-diagnoses-by-treatment.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
				</div>
				<div class="cr-table-wrap">
					<table class="cr-table cr-matrix" id="table-diagnosis-treatment">
						<thead>
							<tr>
								<th><spring:message code="clinicreporting.programs.diagnosis"/></th>
								<c:forEach var="column" items="${diagnosisTreatments.columns}">
									<th class="num">${column.label}</th>
								</c:forEach>
								<th class="num"><spring:message code="clinicreporting.programs.total"/></th>
							</tr>
						</thead>
						<tbody>
							<c:choose>
							<c:when test="${empty diagnosisTreatments.rows}">
								<tr><td colspan="99" class="cr-empty-row"><spring:message code="clinicreporting.common.noRows"/></td></tr>
							</c:when>
							<c:otherwise>
								<c:forEach var="row" items="${diagnosisTreatments.rows}" varStatus="rowStatus">
									<tr>
										<td>${row.label}</td>
										<c:forEach var="column" items="${diagnosisTreatments.columns}" varStatus="columnStatus">
											<td class="num">${diagnosisTreatments.cells[rowStatus.index][columnStatus.index]}</td>
										</c:forEach>
										<td class="num cr-total-label">${row.count}</td>
									</tr>
								</c:forEach>
							</c:otherwise>
							</c:choose>
						</tbody>
					</table>
				</div>
			</div>
		</div>
	</section>
</c:if>

<c:if test="${not empty definitionSource}">
	<p class="cr-muted cr-source"><spring:message code="clinicreporting.programs.definitionsFrom" arguments="${definitionSource}"/></p>
</c:if>

</c:otherwise>
</c:choose>

<%@ include file="chrome-end.jsp" %>
</div>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/chart.umd.min.js" />
<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/reports.js" />

<%@ include file="/WEB-INF/template/footer.jsp" %>
