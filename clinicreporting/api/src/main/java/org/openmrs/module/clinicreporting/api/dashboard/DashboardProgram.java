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
import java.util.List;
import java.util.Locale;

/**
 * A programme that has a dashboard: which OpenMRS programme it describes, how it is titled, and the
 * indicators shown on it.
 * <p>
 * The {@link #getUuid() uuid} is the OpenMRS programme's own UUID. That is what ties the dashboard
 * to the data: enrolments are found through {@code patient_program}, so a dashboard can only ever
 * show figures for a programme this server actually has.
 * <p>
 * A {@link #isBuiltin() built-in} dashboard is the exception: it names no programme and is computed
 * over the whole clinic. It carries a reserved UUID only so that a link can address it, and it is
 * built in code rather than read from the definition file.
 */
public class DashboardProgram {
	
	private final String uuid;
	
	private final String name;
	
	private final String shortName;
	
	private final String description;
	
	private final List<DashboardIndicator> indicators;
	
	/**
	 * The built-in report this dashboard is, when it is not backed by an OpenMRS programme. Null for an
	 * ordinary programme dashboard, which is every dashboard the definition file describes.
	 */
	private final String builtinType;
	
	DashboardProgram(String uuid, String name, String shortName, String description, List<DashboardIndicator> indicators) {
		this(uuid, name, shortName, description, indicators, null);
	}
	
	DashboardProgram(String uuid, String name, String shortName, String description, List<DashboardIndicator> indicators,
	    String builtinType) {
		this.uuid = uuid;
		this.name = name;
		this.shortName = shortName;
		this.description = description;
		this.indicators = Collections.unmodifiableList(new java.util.ArrayList<>(indicators));
		this.builtinType = builtinType;
	}
	
	/**
	 * @return the OpenMRS programme UUID
	 */
	public String getUuid() {
		return uuid;
	}
	
	/**
	 * @return the full programme name
	 */
	public String getName() {
		return name;
	}
	
	/**
	 * @return the abbreviation used on the programme picker, e.g. {@code PMTCT}
	 */
	public String getShortName() {
		return shortName;
	}
	
	/**
	 * @return a sentence describing what the programme covers, or null
	 */
	public String getDescription() {
		return description;
	}
	
	/**
	 * @return the indicators in the order the definition file listed them, never null
	 */
	public List<DashboardIndicator> getIndicators() {
		return indicators;
	}
	
	/**
	 * A built-in dashboard is not tied to an OpenMRS programme: it is computed over the whole clinic,
	 * so it does not depend on a programme this server has and never comes from the definition file.
	 *
	 * @return true when this dashboard is one the module builds itself
	 */
	public boolean isBuiltin() {
		return builtinType != null;
	}
	
	/**
	 * @return which built-in report this is, or null for an ordinary programme dashboard
	 */
	public String getBuiltinType() {
		return builtinType;
	}
	
	/**
	 * @return the initials to badge the programme with, e.g. {@code AYFS}
	 */
	public String getBadge() {
		if (shortName != null && !shortName.trim().isEmpty()) {
			return shortName.trim().toUpperCase(Locale.ROOT);
		}
		return "?";
	}
}
