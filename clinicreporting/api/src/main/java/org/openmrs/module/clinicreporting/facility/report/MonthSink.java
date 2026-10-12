/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.facility.report;

/**
 * Receives one point of the twelve-month trend.
 * <p>
 * The trend is fed separately from the {@link Fact} stream because it is the one series that is
 * bucketed by month rather than by the requested range: the window is the twelve months ending at
 * the range's end, whatever range was asked for.
 */
public interface MonthSink {
	
	void add(String yearMonth, long encounters, long patients);
}
