<%@ include file="/WEB-INF/template/include.jsp" %>
<openmrs:require privilege="View Reports" otherwise="/login.htm" redirect="/module/clinicreporting/patients.htm"/>
<%@ include file="/WEB-INF/template/header.jsp" %>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/styles/reports.css" />

<div class="cr-app">
<%@ include file="chrome.jsp" %>

<c:if test="${not empty error}">
	<div class="cr-notice cr-notice-danger"><spring:message code="${error}"/></div>
</c:if>

<section class="cr-card cr-filters">
	<form method="get" action="${pageContext.request.contextPath}/module/clinicreporting/patients.htm">
		<input type="hidden" name="run" value="1"/>
		<div class="cr-filter-grid">
			<label><spring:message code="clinicreporting.common.from"/>
				<input type="date" name="dateFrom" value="${dateFrom}"/>
			</label>
			<label><spring:message code="clinicreporting.common.to"/>
				<input type="date" name="dateTo" value="${dateTo}"/>
			</label>
			<label><spring:message code="clinicreporting.common.search"/>
				<input type="text" name="q" value="${q}" placeholder="<spring:message code="clinicreporting.patients.name"/>"/>
			</label>
			<label><spring:message code="clinicreporting.common.gender"/>
				<select name="gender">
					<option value=""><spring:message code="clinicreporting.patients.anyGender"/></option>
					<c:forEach var="option" items="${genders}">
						<option value="${option}"<c:if test="${option == gender}"> selected</c:if>>${option}</option>
					</c:forEach>
				</select>
				<span class="cr-hint"><spring:message code="clinicreporting.patients.genderLegend"/></span>
			</label>
			<label><spring:message code="clinicreporting.patients.minAgeMonths"/>
				<input type="number" name="minAgeMonths" min="0" value="${minAgeMonths}"/>
			</label>
			<label><spring:message code="clinicreporting.patients.maxAgeMonths"/>
				<input type="number" name="maxAgeMonths" min="0" value="${maxAgeMonths}"/>
			</label>
			<label class="cr-check">
				<input type="checkbox" name="seenInRange" value="true"<c:if test="${seenInRange == true}"> checked</c:if>/>
				<spring:message code="clinicreporting.patients.seenInRangeOnly"/>
			</label>
		</div>
		<div class="cr-filter-actions">
			<button class="cr-btn cr-btn-primary" type="submit"><spring:message code="clinicreporting.common.run"/></button>
			<span class="cr-filter-summary">${dateFrom} &ndash; ${dateTo}</span>
		</div>
	</form>
</section>

<c:if test="${not empty patients or totalCount > 0}">

	<section class="cr-kpis">
		<div class="cr-card cr-kpi" data-kpi="patients">
			<div class="cr-kpi-title"><spring:message code="clinicreporting.patients.total"/></div>
			<div class="cr-kpi-value">${totalCount}</div>
		</div>
	</section>

	<section class="cr-charts">

		<div class="cr-card cr-chart-card">
			<h2><spring:message code="clinicreporting.patients.byGender"/></h2>
			<p class="cr-card-sub"><spring:message code="clinicreporting.patients.byGenderSub"/></p>
			<div class="cr-chart-box" data-chart="doughnut" data-source="table-gender">
				<canvas></canvas>
				<div class="cr-chart-empty"><spring:message code="clinicreporting.patients.byGender"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
		</div>

		<div class="cr-card cr-chart-card cr-span2">
			<h2><spring:message code="clinicreporting.patients.byAgeBand"/></h2>
			<p class="cr-card-sub"><spring:message code="clinicreporting.patients.byAgeBandSub"/></p>
			<div class="cr-chart-box cr-chart-box-lg" data-chart="bar" data-source="table-age-band">
				<canvas></canvas>
				<div class="cr-chart-empty"><spring:message code="clinicreporting.patients.byAgeBand"/><br><span><spring:message code="clinicreporting.common.noData"/></span></div>
			</div>
		</div>

	</section>

	<section class="cr-card cr-table-card">
		<div class="cr-table-head">
			<h2><spring:message code="clinicreporting.patients.byGender"/></h2>
			<button type="button" class="cr-btn-export"
			        data-export="table-gender" data-export-name="clinic-reports-patients-by-gender.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
		</div>
		<div class="cr-table-wrap">
			<table class="cr-table" id="table-gender" data-share="true">
				<thead>
					<tr>
						<th><spring:message code="clinicreporting.common.gender"/></th>
						<th class="num"><spring:message code="clinicreporting.patients.total"/></th>
					</tr>
				</thead>
				<tbody>
					<c:forEach var="row" items="${byGender}">
						<tr>
							<td>${row.label}</td>
							<td class="num cr-share">${row.count}</td>
						</tr>
					</c:forEach>
					<c:if test="${not empty byGender}">
						<tr class="cr-total-row">
							<td class="cr-total-label"><spring:message code="clinicreporting.reports.total"/></td>
							<td class="num cr-total-label">${totalCount}</td>
						</tr>
					</c:if>
				</tbody>
			</table>
		</div>
	</section>

	<section class="cr-card cr-table-card">
		<div class="cr-table-head">
			<h2><spring:message code="clinicreporting.patients.byAgeBand"/></h2>
			<button type="button" class="cr-btn-export"
			        data-export="table-age-band" data-export-name="clinic-reports-patients-by-age.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
		</div>
		<div class="cr-table-wrap">
			<table class="cr-table" id="table-age-band" data-share="true">
				<thead>
					<tr>
						<th><spring:message code="clinicreporting.patients.age"/></th>
						<th class="num"><spring:message code="clinicreporting.patients.total"/></th>
					</tr>
				</thead>
				<tbody>
					<c:forEach var="row" items="${byAgeBand}">
						<tr>
							<td>${row.label}</td>
							<td class="num cr-share">${row.count}</td>
						</tr>
					</c:forEach>
					<c:if test="${not empty byAgeBand}">
						<tr class="cr-total-row">
							<td class="cr-total-label"><spring:message code="clinicreporting.reports.total"/></td>
							<td class="num cr-total-label">${totalCount}</td>
						</tr>
					</c:if>
				</tbody>
			</table>
		</div>
	</section>

	<section class="cr-card cr-table-card">
		<div class="cr-table-head">
			<h2><spring:message code="clinicreporting.patients.title"/></h2>
			<c:if test="${not empty patients}">
				<button type="button" class="cr-btn-export"
				        data-export="table-patients" data-export-name="clinic-reports-patients.csv"><spring:message code="clinicreporting.common.exportCsv"/></button>
			</c:if>
		</div>
		<c:choose>
		<c:when test="${empty patients}">
			<p class="cr-muted"><spring:message code="clinicreporting.patients.noResults"/></p>
		</c:when>
		<c:otherwise>
			<div class="cr-table-wrap">
				<table class="cr-table" id="table-patients">
					<thead>
						<tr>
							<th><spring:message code="clinicreporting.patients.name"/></th>
							<th><spring:message code="clinicreporting.patients.identifier"/></th>
							<th><spring:message code="clinicreporting.common.gender"/></th>
							<th><spring:message code="clinicreporting.patients.age"/></th>
							<th><spring:message code="clinicreporting.patients.birthdate"/></th>
							<th><spring:message code="clinicreporting.common.location"/></th>
							<th><spring:message code="clinicreporting.patients.program"/></th>
							<th><spring:message code="clinicreporting.patients.lastVisit"/></th>
						</tr>
					</thead>
					<tbody>
						<c:forEach var="row" items="${patients}">
							<tr>
								<td>${row.display}</td>
								<td>${row.identifier}</td>
								<td>${row.gender}</td>
								<td>${row.ageDisplay}</td>
								<td>${row.birthdateDisplay}</td>
								<td>${row.locationName}</td>
								<td>${row.programName}</td>
								<td>${row.lastVisitDisplay}</td>
							</tr>
						</c:forEach>
					</tbody>
				</table>
			</div>

			<div class="cr-pagination">
				<c:if test="${hasPrevious}">
					<c:url var="previousUrl" value="/module/clinicreporting/patients.htm">
						<c:param name="run" value="1"/>
						<c:param name="dateFrom" value="${dateFrom}"/>
						<c:param name="dateTo" value="${dateTo}"/>
						<c:param name="q" value="${q}"/>
						<c:param name="gender" value="${gender}"/>
						<c:param name="minAgeMonths" value="${minAgeMonths}"/>
						<c:param name="maxAgeMonths" value="${maxAgeMonths}"/>
						<c:param name="seenInRange" value="${seenInRange}"/>
						<c:param name="page" value="${previousPage}"/>
					</c:url>
					<a class="cr-btn" href="${previousUrl}"><spring:message code="clinicreporting.patients.previous"/></a>
				</c:if>

				<span class="cr-muted"><spring:message code="clinicreporting.patients.pageInfo" arguments="${page},${totalPages}"/></span>

				<c:if test="${hasNext}">
					<c:url var="nextUrl" value="/module/clinicreporting/patients.htm">
						<c:param name="run" value="1"/>
						<c:param name="dateFrom" value="${dateFrom}"/>
						<c:param name="dateTo" value="${dateTo}"/>
						<c:param name="q" value="${q}"/>
						<c:param name="gender" value="${gender}"/>
						<c:param name="minAgeMonths" value="${minAgeMonths}"/>
						<c:param name="maxAgeMonths" value="${maxAgeMonths}"/>
						<c:param name="seenInRange" value="${seenInRange}"/>
						<c:param name="page" value="${nextPage}"/>
					</c:url>
					<a class="cr-btn" href="${nextUrl}"><spring:message code="clinicreporting.patients.next"/></a>
				</c:if>
			</div>
		</c:otherwise>
		</c:choose>
	</section>

</c:if>

<%@ include file="chrome-end.jsp" %>
</div>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/chart.umd.min.js" />
<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/reports.js" />

<%@ include file="/WEB-INF/template/footer.jsp" %>
