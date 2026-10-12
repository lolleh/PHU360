/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.exception.ReportException;

/**
 * Maps report failures onto stable HTTP status codes and a consistent JSON error body.
 * <p>
 * The distinction that matters to a client is between "this request was wrong" and "this server
 * cannot answer that at all": the first is a 400 and retrying is pointless, the second is a 501 and
 * a different deployment or configuration might work.
 */
@RestControllerAdvice(basePackages = "org.openmrs.module.clinicreporting.web.controller")
public class RestExceptionHandler {
	
	@ExceptionHandler(InvalidReportFilterException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleInvalidFilter(InvalidReportFilterException e) {
		return error("invalid_filter", e.getMessage(), null);
	}
	
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleIllegalArgument(IllegalArgumentException e) {
		return error("invalid_request", e.getMessage(), null);
	}
	
	/**
	 * The caller lacks the read privilege. Handled ahead of {@link ReportException}, which this
	 * exception extends, so a refusal is reported as a refusal rather than as a server fault.
	 */
	@ExceptionHandler(BaseReportController.InsufficientPrivilegeException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public Map<String, Object> handleInsufficientPrivilege(BaseReportController.InsufficientPrivilegeException e) {
		return error("insufficient_privilege", e.getMessage(), null);
	}
	
	@ExceptionHandler(ReportException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public Map<String, Object> handleReport(ReportException e) {
		return error("report_failed", e.getMessage(), null);
	}
	
	/**
	 * Catch-all. The message is deliberately generic because an unexpected exception message can
	 * contain identifiers or SQL fragments that should not reach a client; the detail is in the server
	 * log.
	 */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public Map<String, Object> handleUnexpected(Exception e) {
		return error("report_failed", "The report could not be produced. See the server log for details.", null);
	}
	
	private static Map<String, Object> error(String code, String message, String dataSource) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code);
		error.put("message", message == null ? "The report could not be produced." : message);
		if (dataSource != null) {
			error.put("dataSource", dataSource);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		return body;
	}
}
