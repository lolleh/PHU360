/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dashboard;

import java.util.Collections;
import java.util.Locale;

/**
 * The dashboards the module builds itself, as opposed to the programme dashboards an administrator
 * describes in {@code program-indicators.json}.
 * <p>
 * A built-in dashboard is not tied to an OpenMRS programme and is computed over the whole clinic,
 * so it cannot live in the definition file: the file describes programmes, and a programme is the
 * entry point every measure on an ordinary dashboard is scoped to. It still needs a UUID and a
 * title so the picker can list it and link to it like any other tile, which is what this enum
 * supplies.
 */
public enum BuiltinDashboard {
	
	/**
	 * Every coded diagnosis in the clinic against the drug treatments ordered on the same visit.
	 */
	DIAGNOSES_BY_TREATMENT(
	        "a0000000-0000-4000-8000-000000000001", // reserved, never a real programme
	        "Diagnoses by Treatment",
	        "DX",
	        "Every coded diagnosis recorded in the clinic, cross-tabulated against the drug treatments "
	                + "ordered on the same visit.",
	        "diagnosesByTreatment");
	
	private final DashboardProgram program;
	
	BuiltinDashboard(String uuid, String name, String shortName, String description, String builtinType) {
		this.program = new DashboardProgram(uuid, name, shortName, description, Collections.<DashboardIndicator> emptyList(),
		        builtinType);
	}
	
	/**
	 * @return the dashboard definition to show in the picker and open as a page
	 */
	public DashboardProgram toProgram() {
		return program;
	}
	
	/**
	 * @return the reserved UUID a link addresses this dashboard by
	 */
	public String getUuid() {
		return program.getUuid();
	}
	
	/**
	 * @param uuid a UUID, possibly a built-in one, possibly null
	 * @return the matching built-in dashboard, or null when the UUID is not one of ours
	 */
	public static BuiltinDashboard forUuid(String uuid) {
		if (uuid == null) {
			return null;
		}
		String wanted = uuid.trim().toLowerCase(Locale.ROOT);
		for (BuiltinDashboard candidate : values()) {
			if (candidate.getUuid().equals(wanted)) {
				return candidate;
			}
		}
		return null;
	}
}
