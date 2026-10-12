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

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

/**
 * Time buckets that report time series can be aggregated into.
 */
public enum Period {
	
	DAY,
	
	WEEK,
	
	MONTH,
	
	QUARTER,
	
	YEAR;
	
	/**
	 * Resolves a caller supplied period name, case-insensitively.
	 *
	 * @param name the name to resolve, may be null
	 * @param fallback returned when {@code name} is null or blank
	 * @return the resolved period
	 * @throws IllegalArgumentException if {@code name} is not a known period
	 */
	public static Period parse(String name, Period fallback) {
		if (name == null || name.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Period.valueOf(name.trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw new InvalidReportFilterException(
			        "groupBy must be one of DAY, WEEK, MONTH, QUARTER or YEAR, but was '" + name + "'");
		}
	}
	
	/**
	 * @param name the requested period, may be null or blank
	 * @return the requested period, or the server's configured default
	 */
	public static Period parse(String name) {
		return parse(name, ContextualDefaults.defaultGroupBy());
	}
	
	/**
	 * Truncates a timestamp to the start of its bucket.
	 *
	 * @param date the date to truncate
	 * @return the first instant of the containing bucket
	 */
	public Date truncate(Date date) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		
		switch (this) {
			case DAY:
				break;
			case WEEK:
				// Calendar counts Sunday as day 1, which matches ISO ordering closely enough
				// for reporting purposes and keeps the behaviour locale-independent.
				calendar.set(Calendar.DAY_OF_WEEK, calendar.getFirstDayOfWeek());
				break;
			case MONTH:
				calendar.set(Calendar.DAY_OF_MONTH, 1);
				break;
			case QUARTER:
				calendar.set(Calendar.DAY_OF_MONTH, 1);
				calendar.set(Calendar.MONTH, calendar.get(Calendar.MONTH) / 3 * 3);
				break;
			case YEAR:
				calendar.set(Calendar.DAY_OF_MONTH, 1);
				calendar.set(Calendar.MONTH, Calendar.JANUARY);
				break;
		}
		return calendar.getTime();
	}
	
	/**
	 * The canonical label used as the series key, for example {@code 2026-03} for a month and
	 * {@code 2026-03-09} for a day.
	 *
	 * @param date a date already passed through {@link #truncate(Date)}
	 * @return the bucket label
	 */
	public String label(Date date) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		String pattern;
		switch (this) {
			case DAY:
				pattern = "yyyy-MM-dd";
				break;
			case WEEK:
				pattern = "yyyy-'W'ww";
				break;
			case QUARTER:
				return calendar.get(Calendar.YEAR) + "-Q" + ((calendar.get(Calendar.MONTH) / 3) + 1);
			case YEAR:
				pattern = "yyyy";
				break;
			case MONTH:
			default:
				pattern = "yyyy-MM";
				break;
		}
		return new SimpleDateFormat(pattern).format(date);
	}
	
	/**
	 * Expands a validated range into every bucket label it contains, in order. The database only
	 * returns buckets that have data, so this is what turns a sparse result into a dense series
	 * suitable for charting (missing periods report zero instead of vanishing).
	 *
	 * @param from start of the range, inclusive
	 * @param to end of the range, inclusive
	 * @return ordered map of bucket label to zero-valued series point
	 */
	public Map<String, TimeSeriesPoint> expand(Date from, Date to) {
		Map<String, TimeSeriesPoint> series = new LinkedHashMap<String, TimeSeriesPoint>();
		Date cursor = truncate(from);
		Date limit = to;
		// Guard against a pathological range producing an unbounded loop.
		int guard = 0;
		while (cursor.getTime() <= limit.getTime() && guard++ < 20000) {
			series.put(label(cursor), new TimeSeriesPoint(label(cursor), 0L));
			Date next = advance(cursor);
			cursor = next;
		}
		return series;
	}
	
	private Date advance(Date bucketStart) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(bucketStart);
		switch (this) {
			case DAY:
				calendar.add(Calendar.DAY_OF_MONTH, 1);
				break;
			case WEEK:
				calendar.add(Calendar.WEEK_OF_YEAR, 1);
				break;
			case MONTH:
				calendar.add(Calendar.MONTH, 1);
				break;
			case QUARTER:
				calendar.add(Calendar.MONTH, 3);
				break;
			case YEAR:
			default:
				calendar.add(Calendar.YEAR, 1);
				break;
		}
		return calendar.getTime();
	}
}
