<%@ include file="/WEB-INF/template/include.jsp" %>
<openmrs:require privilege="View Reports" otherwise="/login.htm" redirect="/module/clinicreporting/programs.htm"/>
<%@ include file="/WEB-INF/template/header.jsp" %>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/styles/reports.css" />

<div class="cr-app">
<%@ include file="chrome.jsp" %>

<div class="cr-page-head">
	<div>
		<h1><spring:message code="clinicreporting.programs.title"/></h1>
		<p class="cr-card-sub"><spring:message code="clinicreporting.programs.subtitle"/></p>
	</div>
	<a class="cr-btn" href="${pageContext.request.contextPath}/module/clinicreporting/reports.htm"><spring:message code="clinicreporting.nav.clinical"/></a>
</div>

<c:if test="${not empty error}">
	<div class="cr-notice cr-notice-danger">
		<spring:message code="${error}"/>
		<c:if test="${not empty definitionDetail}">
			<div class="cr-notice-detail"><c:out value="${definitionDetail}"/></div>
		</c:if>
	</div>
</c:if>

<section class="cr-kpis cr-programs-kpis">
	<c:forEach var="program" items="${programs}">
		<c:url var="programUrl" value="/module/clinicreporting/dashboard.htm">
			<c:param name="program" value="${program.uuid}"/>
		</c:url>
		<a class="cr-card cr-program-tile" href="${programUrl}">
			<div class="cr-program-badge">${program.badge}</div>
			<div class="cr-program-tile-body">
				<div class="cr-program-name">${program.name}</div>
				<div class="cr-program-desc">${program.description}</div>
				<div class="cr-program-meta">
					<c:choose>
						<c:when test="${program.builtin}"><spring:message code="clinicreporting.programs.reportTile"/></c:when>
						<c:otherwise><spring:message code="clinicreporting.programs.indicatorCount" arguments="${program.indicators.size()}"/></c:otherwise>
					</c:choose>
				</div>
			</div>
			<span class="cr-program-go" aria-hidden="true">&rsaquo;</span>
		</a>
	</c:forEach>
</section>

<c:if test="${empty programs}">
	<section class="cr-card">
		<p class="cr-muted"><spring:message code="clinicreporting.programs.none"/></p>
	</section>
</c:if>

<c:if test="${not empty definitionSource}">
	<p class="cr-muted cr-source"><spring:message code="clinicreporting.programs.definitionsFrom" arguments="${definitionSource}"/></p>
</c:if>

<%@ include file="chrome-end.jsp" %>
</div>

<openmrs:htmlInclude file="/moduleResources/clinicreporting/js/reports.js" />

<%@ include file="/WEB-INF/template/footer.jsp" %>
