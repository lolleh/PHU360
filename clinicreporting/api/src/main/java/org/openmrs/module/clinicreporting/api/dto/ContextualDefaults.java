/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dto;

import java.util.Locale;

import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.ClinicReportingConstants;

/**
 * Reads the module's tunable defaults from Global Properties, falling back to the constants when no
 * authenticated context is available (for example during unit tests that exercise filter validation
 * directly).
 * <p>
 * Public because the services and DAOs sit outside the {@code dto} package and still have to honour
 * the same settings.
 */
public final class ContextualDefaults {
	
	private ContextualDefaults() {
		// static utility class
	}
	
	/** Fallback when no authenticated context is available. */
	public static final String DEFAULT_LOCALE = "en";
	
	public static int maxPageSize() {
		return intProperty(ClinicReportingConstants.GP_MAX_PAGE_SIZE, ClinicReportingConstants.MAX_PAGE_SIZE);
	}
	
	public static int defaultPageSize() {
		return intProperty(ClinicReportingConstants.GP_DEFAULT_PAGE_SIZE, ClinicReportingConstants.DEFAULT_PAGE_SIZE);
	}
	
	/**
	 * @return the period reports bucket by when the caller does not ask for one
	 */
	public static Period defaultGroupBy() {
		try {
			String value = Context.getAdministrationService()
			        .getGlobalProperty(ClinicReportingConstants.GP_DEFAULT_GROUP_BY);
			if (value == null || value.trim().isEmpty()) {
				return Period.MONTH;
			}
			return Period.valueOf(value.trim().toUpperCase());
		}
		catch (RuntimeException e) {
			return Period.MONTH;
		}
	}
	
	/**
	 * The locale concept names are resolved in.
	 * <p>
	 * A concept carries one name per locale and a deployment ships many of them, so reports have to
	 * choose a name rather than accept whichever sorts first.
	 *
	 * @return the authenticated user's locale, or {@code en} when there is no context
	 */
	public static String preferredLocale() {
		try {
			Locale locale = Context.getLocale();
			return locale == null ? DEFAULT_LOCALE : locale.toString();
		}
		catch (RuntimeException e) {
			return DEFAULT_LOCALE;
		}
	}
	
	private static int intProperty(String key, int fallback) {
		try {
			String value = Context.getAdministrationService().getGlobalProperty(key);
			if (value == null || value.trim().isEmpty()) {
				return fallback;
			}
			return Integer.parseInt(value.trim());
		}
		catch (RuntimeException e) {
			// NumberFormatException extends IllegalArgumentException extends RuntimeException, so
			// this single catch covers a missing context, an unavailable global property service
			// and a non-numeric value. Falling back beats failing a report over a bad setting.
			return fallback;
		}
	}
}
