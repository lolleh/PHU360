<%--
  Page chrome shared by the report pages: the PHU360 topbar carrying the report
  wordmark, the section switcher, and the opening of the centred content shell.
  chrome-end.jsp closes them again.

  The controllers put the current section in the "tab" model attribute, so each page marks
  itself active without duplicating the comparison logic. Links drop the current filters on
  purpose: a clinician switching from the clinical tab to the patient tab wants that section's
  defaults, not one section's filters applied to another register.

  The data exports tab is an administration tool rather than a report, so it is offered only to
  users who actually hold the privilege that opens it. Showing a tab that always
  answers "not available to your account" would train people to ignore the tab strip; the
  controller checks the privilege again, so this is presentation only.
--%>
<div class="cr-topbar">
	<div class="cr-brand">
		<h1><spring:message code="clinicreporting.chrome.title"/></h1>
		<div class="cr-subtitle"><spring:message code="clinicreporting.chrome.subtitle"/></div>
	</div>

	<nav class="cr-nav" aria-label="<spring:message code="clinicreporting.nav.label"/>">
		<a class="cr-tab<c:if test="${tab == 'clinical'}"> active</c:if>"
		   href="${pageContext.request.contextPath}/module/clinicreporting/reports.htm"><spring:message code="clinicreporting.nav.clinical"/></a>
		<a class="cr-tab<c:if test="${tab == 'patients'}"> active</c:if>"
		   href="${pageContext.request.contextPath}/module/clinicreporting/patients.htm"><spring:message code="clinicreporting.nav.patients"/></a>
		<a class="cr-tab<c:if test="${tab == 'programs'}"> active</c:if>"
		   href="${pageContext.request.contextPath}/module/clinicreporting/programs.htm"><spring:message code="clinicreporting.nav.programs"/></a>
		<c:if test="${showExportsTab}">
			<a class="cr-tab<c:if test="${tab == 'exports'}"> active</c:if>"
			   href="${pageContext.request.contextPath}/module/clinicreporting/exports.htm"><spring:message code="clinicreporting.nav.exports"/></a>
		</c:if>
	</nav>
</div>

<div class="cr-shell">
