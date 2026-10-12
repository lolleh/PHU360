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

import java.util.Calendar;
import java.util.Date;

import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

/**
 * Common filter state shared by every report in this module.
 * <p>
 * Subclasses add report specific criteria. Instances are always normalised by {@link #validate()}
 * before a DAO sees them, so DAOs can rely on non-null dates and a sane page window.
 */
public class ReportFilter {
	
	private Date dateFrom;
	
	private Date dateTo;
	
	private Integer locationId;
	
	private int page = 1;
	
	private int size = ClinicReportingConstants.DEFAULT_PAGE_SIZE;
	
	/** Number of days the requested range spans, after normalisation. */
	private long rangeDays;
	
	public Date getDateFrom() {
		return dateFrom;
	}
	
	public void setDateFrom(Date dateFrom) {
		this.dateFrom = dateFrom;
	}
	
	public Date getDateTo() {
		return dateTo;
	}
	
	public void setDateTo(Date dateTo) {
		this.dateTo = dateTo;
	}
	
	public Integer getLocationId() {
		return locationId;
	}
	
	public void setLocationId(Integer locationId) {
		this.locationId = locationId;
	}
	
	public int getPage() {
		return page;
	}
	
	public void setPage(int page) {
		this.page = page;
	}
	
	public int getSize() {
		return size;
	}
	
	public void setSize(int size) {
		this.size = size;
	}
	
	public long getRangeDays() {
		return rangeDays;
	}
	
	public int getOffset() {
		return (page - 1) * size;
	}
	
	/**
	 * Validates and normalises this filter. <b>Must</b> be called before use.
	 *
	 * @throws InvalidReportFilterException if the range is inverted, unbounded or too wide, or if the
	 *             page window is not a positive in-range value
	 */
	public ReportFilter validate() {
		if (dateFrom == null) {
			throw new InvalidReportFilterException("dateFrom is required");
		}
		if (dateTo == null) {
			throw new InvalidReportFilterException("dateTo is required");
		}
		if (dateTo.before(dateFrom)) {
			throw new InvalidReportFilterException("dateTo must not be earlier than dateFrom");
		}
		
		// Make the range inclusive of the whole end day, so a single-day request behaves.
		Calendar endOfDay = Calendar.getInstance();
		endOfDay.setTime(dateTo);
		endOfDay.set(Calendar.HOUR_OF_DAY, 23);
		endOfDay.set(Calendar.MINUTE, 59);
		endOfDay.set(Calendar.SECOND, 59);
		endOfDay.set(Calendar.MILLISECOND, 999);
		this.dateTo = endOfDay.getTime();
		
		Calendar startOfDay = Calendar.getInstance();
		startOfDay.setTime(dateFrom);
		startOfDay.set(Calendar.HOUR_OF_DAY, 0);
		startOfDay.set(Calendar.MINUTE, 0);
		startOfDay.set(Calendar.SECOND, 0);
		startOfDay.set(Calendar.MILLISECOND, 0);
		this.dateFrom = startOfDay.getTime();
		
		this.rangeDays = (this.dateTo.getTime() - this.dateFrom.getTime()) / 86400000L;
		if (this.rangeDays > ClinicReportingConstants.MAX_RANGE_DAYS) {
			throw new InvalidReportFilterException("Date range of " + this.rangeDays + " days exceeds the maximum of "
			        + ClinicReportingConstants.MAX_RANGE_DAYS + " days");
		}
		
		if (page < 1) {
			throw new InvalidReportFilterException("page must be greater than or equal to 1");
		}
		int maxSize = ContextualDefaults.maxPageSize();
		if (size < 1) {
			throw new InvalidReportFilterException("size must be greater than or equal to 1");
		}
		if (size > maxSize) {
			throw new InvalidReportFilterException("size must not exceed " + maxSize);
		}
		return this;
	}
}
