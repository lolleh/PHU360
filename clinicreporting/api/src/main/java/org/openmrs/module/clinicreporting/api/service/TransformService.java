/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.service;

import java.util.Map;

import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.transform.RawQueryGuard;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformPreview;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;

/**
 * Runs indicator sandbox previews.
 * <p>
 * Both entry points return a bounded sample and the statement behind it. Neither writes anything:
 * this is the preview phase, and the service deliberately has no save method so that nothing can
 * quietly start persisting transformations before the indicator format has been agreed.
 */
public interface TransformService {
	
	/**
	 * Builds and runs a declarative pipeline over an allowlisted table.
	 *
	 * @param request the source, columns and steps to preview
	 * @return the sample rows and the generated statement
	 * @throws InvalidReportFilterException when the request names something outside the allowlist, or
	 *             asks for more rows than {@link TransformRequest#MAX_ROWS}
	 */
	TransformPreview preview(TransformRequest request);
	
	/**
	 * Runs an analyst-written statement after {@link RawQueryGuard} has accepted it.
	 * <p>
	 * Callers must have checked the {@code Run Raw Report SQL} privilege before getting here; this
	 * method validates the statement but does not check privileges, because a service cannot see the
	 * current user's privileges reliably.
	 *
	 * @param sql the statement
	 * @param limit the maximum rows to return
	 * @return the sample rows and the statement as run
	 * @throws InvalidReportFilterException when the statement is refused
	 */
	TransformPreview previewRaw(String sql, int limit);
	
	/**
	 * @return the allowlisted sources the caller may offer, for the UI's table picker. Narrowed to the
	 *         columns this database actually has, so the picker cannot offer a column a query would
	 *         reject.
	 */
	Map<String, ReportSourceRegistry.Source> sources();
}
