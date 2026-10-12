/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao.hibernate;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.clinicreporting.api.dao.AbstractReportDao;
import org.openmrs.module.clinicreporting.api.dao.LabReportDao;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.LabOrderRow;
import org.openmrs.module.clinicreporting.api.dto.LabReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.dto.TurnaroundSummary;

/**
 * Lab result queries against the core OpenMRS schema.
 */
public class HibernateLabReportDao extends AbstractReportDao implements LabReportDao {
	
	private static final String ISO_DATE = "yyyy-MM-dd";
	
	/** Order date: the activation date when recorded, otherwise when the order row was created. */
	private static final String ORDER_DATE = "COALESCE(o.date_activated, o.date_created)";
	
	/** Display name of the ordered concept, chosen by locale rather than alphabetically. */
	private static final String TEST_NAME = conceptName("c");
	
	private static final String NAME_JOINS = " LEFT JOIN (SELECT n.person_id, COALESCE(MAX(CASE WHEN n.preferred THEN n.person_name_id END), "
	        + "   MAX(n.person_name_id)) AS person_name_id FROM person_name n WHERE n.voided = false GROUP BY n.person_id) pns "
	        + "   ON pns.person_id = pe.person_id " + " LEFT JOIN person_name pn ON pn.person_name_id = pns.person_name_id "
	        + " LEFT JOIN (SELECT i.patient_id, COALESCE(MAX(CASE WHEN i.preferred THEN i.patient_identifier_id END), "
	        + "   MAX(i.patient_identifier_id)) AS patient_identifier_id FROM patient_identifier i WHERE i.voided = false "
	        + "   GROUP BY i.patient_id) pis ON pis.patient_id = p.patient_id "
	        + " LEFT JOIN patient_identifier pi ON pi.patient_identifier_id = pis.patient_identifier_id ";
	
	/**
	 * {@code concept} is joined on the left rather than required, so an order that names no test still
	 * appears in the listing with a blank name instead of vanishing from the report.
	 */
	private static final String ORDER_FROM = " FROM orders o "
	        + " JOIN patient p ON p.patient_id = o.patient_id AND p.voided = false "
	        + " JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false "
	        + " JOIN order_type ot ON ot.order_type_id = o.order_type_id "
	        + " LEFT JOIN concept c ON c.concept_id = o.concept_id " + NAME_JOINS;
	
	/**
	 * The result side of an order. Kept separate from {@link #ORDER_FROM} because queries that count
	 * orders rather than results must not join it: an order can carry several results for the same
	 * concept, which would inflate an order count.
	 */
	private static final String RESULT_JOINS = " LEFT JOIN obs ro ON ro.order_id = o.order_id AND ro.voided = false "
	        + "   AND ro.concept_id = c.concept_id "
	        + " LEFT JOIN concept_name cnv ON cnv.concept_name_id = ro.value_coded_name_id ";
	
	@Override
	public PagedResult<LabOrderRow> findOrders(LabReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder(
		        // Every column is aliased: several are called "uuid" or "name", and Hibernate
		        // rejects a native query whose discovered result aliases collide.
		        "SELECT o.order_id AS order_id, o.uuid AS order_uuid, o.order_number AS order_number, ")
		        .append("o.accession_number AS accession_number, p.patient_id AS patient_id, ")
		        .append("pe.uuid AS patient_uuid, pn.given_name AS given_name, pn.middle_name AS middle_name, ")
		        .append("pn.family_name AS family_name, pi.identifier AS identifier, pe.gender AS gender, ")
		        .append("pe.birthdate AS birthdate, ot.name AS order_type_name, c.uuid AS concept_uuid, ").append(TEST_NAME)
		        .append(" AS concept_name, ").append(ORDER_DATE).append(" AS order_date, ")
		        .append("ro.obs_datetime AS result_date, ro.status AS result_status, ")
		        .append("ro.value_text AS value_text, ro.value_numeric AS value_numeric, ")
		        .append("cnv.name AS value_coded_name ").append(ORDER_FROM).append(RESULT_JOINS);
		
		String where = buildWhere(filter, params, true);
		bindConceptLocale(params);
		sql.append(where).append(" ORDER BY order_date DESC, o.order_id DESC ");
		
		@SuppressWarnings("unchecked")
		List<Object[]> rows = paged(mapQuery(sql.toString(), params), filter.getOffset(), filter.getSize());
		
		List<LabOrderRow> results = new ArrayList<>(rows.size());
		Date reference = filter.getDateTo();
		for (Object[] row : rows) {
			LabOrderRow order = new LabOrderRow();
			order.setOrderId(asInteger(row[0]));
			order.setOrderUuid(asString(row[1]));
			order.setOrderNumber(asString(row[2]));
			order.setAccessionNumber(asString(row[3]));
			order.setPatientId(asInteger(row[4]));
			order.setPatientUuid(asString(row[5]));
			order.setDisplay(HibernatePatientReportDao.displayName(asString(row[6]), asString(row[7]), asString(row[8])));
			order.setIdentifier(asString(row[9]));
			order.setGender(asString(row[10]));
			order.setAgeInMonths(ageInMonths(asDate(row[11]), reference));
			order.setOrderTypeName(asString(row[12]));
			order.setConceptUuid(asString(row[13]));
			order.setConceptName(asString(row[14]));
			
			Date orderDate = asDate(row[15]);
			order.setOrderDate(orderDate);
			Date resultDate = asDate(row[16]);
			order.setResultDate(resultDate);
			order.setResultStatus(asString(row[17]));
			order.setResultValue(resultValue(asString(row[18]), asDouble(row[19]), asString(row[20])));
			order.setTurnaroundHours(hoursBetween(orderDate, resultDate));
			results.add(order);
		}
		return new PagedResult<>(results, countOrders(filter), filter.getPage(), filter.getSize());
	}
	
	@Override
	public Map<String, TimeSeriesPoint> ordersPerDay(LabReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		// No result join here, so the result-status and result-value criteria are not available.
		StringBuilder sql = new StringBuilder("SELECT CAST(").append(ORDER_DATE).append(" AS DATE) AS bucket, COUNT(*)")
		        .append(ORDER_FROM).append(buildWhere(filter, params, false)).append(" GROUP BY CAST(").append(ORDER_DATE)
		        .append(" AS DATE) ORDER BY bucket ");
		return perDaySeries(sql.toString(), params);
	}
	
	@Override
	public Map<String, TimeSeriesPoint> resultsPerDay(LabReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder("SELECT CAST(ro.obs_datetime AS DATE) AS bucket, COUNT(*)")
		        .append(" FROM obs ro JOIN orders o ON o.order_id = ro.order_id AND o.voided = false ")
		        .append(" JOIN patient p ON p.patient_id = o.patient_id AND p.voided = false ")
		        .append(" JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false ")
		        .append(" JOIN concept c ON c.concept_id = ro.concept_id ")
		        .append(" WHERE ro.voided = false AND ro.obs_datetime >= :from AND ro.obs_datetime <= :to ");
		appendResultCriteria(sql, params, filter, "ro", "o", "pe", "c");
		sql.append(" GROUP BY CAST(ro.obs_datetime AS DATE) ORDER BY bucket ");
		return perDaySeries(sql.toString(), params);
	}
	
	@Override
	public List<BreakdownRow> countByTest(LabReportFilter filter, int limit) {
		Map<String, Object> params = new LinkedHashMap<>();
		// Grouped by the ordered test rather than the observation concept, because an order
		// names the test that was requested.
		String testName = TEST_NAME;
		StringBuilder sql = new StringBuilder("SELECT c.uuid, ").append(testName).append(", c.short_name, COUNT(*)")
		        .append(" FROM obs ro JOIN orders o ON o.order_id = ro.order_id AND o.voided = false ")
		        .append(" JOIN patient p ON p.patient_id = o.patient_id AND p.voided = false ")
		        .append(" JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false ")
		        .append(" JOIN concept c ON c.concept_id = o.concept_id ").append(NAME_JOINS)
		        .append(" WHERE ro.voided = false AND ro.obs_datetime >= :from AND ro.obs_datetime <= :to ");
		appendResultCriteria(sql, params, filter, "ro", "o", "pe", "c");
		sql.append(" GROUP BY c.concept_id, c.uuid, c.short_name, ").append(testName)
		        .append(" ORDER BY COUNT(*) DESC, c.short_name ");
		bindConceptLocale(params);
		
		// The limit is applied by Hibernate rather than by a SQL LIMIT clause, because a bound
		// parameter inside LIMIT is not accepted by every supported database.
		List<BreakdownRow> breakdown = new ArrayList<>();
		for (Object[] row : paged(mapQuery(sql.toString(), params), 0, limit)) {
			String label = asString(row[1]);
			if (label == null || label.isEmpty()) {
				label = asString(row[2]);
			}
			breakdown.add(new BreakdownRow(asString(row[0]), label, asLong(row[3])));
		}
		return breakdown;
	}
	
	@Override
	public List<BreakdownRow> countByResultStatus(LabReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder("SELECT ro.status, COUNT(*)")
		        .append(" FROM obs ro JOIN orders o ON o.order_id = ro.order_id AND o.voided = false ")
		        .append(" JOIN patient p ON p.patient_id = o.patient_id AND p.voided = false ")
		        .append(" JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false ")
		        .append(" JOIN concept c ON c.concept_id = ro.concept_id ")
		        .append(" WHERE ro.voided = false AND ro.obs_datetime >= :from AND ro.obs_datetime <= :to ");
		appendResultCriteria(sql, params, filter, "ro", "o", "pe", "c");
		sql.append(" GROUP BY ro.status ORDER BY COUNT(*) DESC ");
		return listRows(sql.toString(), params, row -> {
			String status = asString(row[0]);
			return new BreakdownRow(status, status == null ? "Unknown" : status, asLong(row[1]));
		});
	}
	
	@Override
	public TurnaroundSummary turnaround(LabReportFilter filter, int maxSamples) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder("SELECT ").append(ORDER_DATE)
		        .append(" AS order_date, MIN(ro.obs_datetime) AS result_date")
		        .append(" FROM obs ro JOIN orders o ON o.order_id = ro.order_id AND o.voided = false ")
		        .append(" JOIN patient p ON p.patient_id = o.patient_id AND p.voided = false ")
		        .append(" JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false ")
		        .append(" JOIN concept c ON c.concept_id = ro.concept_id ")
		        .append(" WHERE ro.voided = false AND ro.obs_datetime >= :from AND ro.obs_datetime <= :to ")
		        .append("   AND ").append(ORDER_DATE).append(" IS NOT NULL ");
		appendResultCriteria(sql, params, filter, "ro", "o", "pe", "c");
		sql.append(" GROUP BY o.order_id, ").append(ORDER_DATE);
		
		// Sampled through Hibernate's paging rather than a SQL LIMIT clause, for the same
		// portability reason as the top-concepts query.
		List<Double> hours = new ArrayList<>();
		for (Object[] row : paged(mapQuery(sql.toString(), params), 0, maxSamples)) {
			Double sample = hoursBetween(asDate(row[0]), asDate(row[1]));
			if (sample != null) {
				hours.add(sample);
			}
		}
		return summarise(hours);
	}
	
	// ---------------------------------------------------------------- filters
	
	private long countOrders(LabReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		// One order can carry several results for the same concept, so distinct on the order
		// keeps the count equal to the number of orders, not the number of result rows.
		return count("SELECT COUNT(DISTINCT o.order_id)" + ORDER_FROM + RESULT_JOINS + buildWhere(filter, params, true),
		    params);
	}
	
	/**
	 * @param includeResultCriteria whether the {@code ro} result alias is in scope; when false the
	 *            result-specific criteria are omitted rather than emitting SQL that references an alias
	 *            the query does not have
	 */
	private String buildWhere(LabReportFilter filter, Map<String, Object> params, boolean includeResultCriteria) {
		params.put("from", filter.getDateFrom());
		params.put("to", filter.getDateTo());
		
		StringBuilder sql = new StringBuilder(" WHERE o.voided = false AND ").append(ORDER_DATE).append(" >= :from AND ")
		        .append(ORDER_DATE).append(" <= :to ");
		if (filter.getOrderTypeUuid() != null) {
			sql.append(" AND ot.uuid = :orderTypeUuid ");
			params.put("orderTypeUuid", filter.getOrderTypeUuid());
		}
		if (filter.getConceptUuid() != null) {
			sql.append(" AND c.uuid = :conceptUuid ");
			params.put("conceptUuid", filter.getConceptUuid());
		}
		if (filter.getGender() != null) {
			sql.append(" AND pe.gender = :gender ");
			params.put("gender", filter.getGender());
		}
		if (filter.getMinAgeMonths() != null) {
			sql.append(" AND pe.birthdate IS NOT NULL AND pe.birthdate <= :bornAtOrBefore ");
			params.put("bornAtOrBefore", monthsBefore(filter.getDateTo(), filter.getMinAgeMonths()));
		}
		if (filter.getMaxAgeMonths() != null) {
			sql.append(" AND pe.birthdate IS NOT NULL AND pe.birthdate > :bornAfter ");
			params.put("bornAfter", monthsBefore(filter.getDateTo(), filter.getMaxAgeMonths()));
		}
		if (includeResultCriteria) {
			if (filter.getObsStatus() != null) {
				sql.append(" AND ro.status = :obsStatus ");
				params.put("obsStatus", filter.getObsStatus());
			}
			if (filter.getResultValue() != null) {
				// Text results are matched with LIKE; numeric results are matched exactly, since
				// casting every result to text to support a LIKE would defeat the index. A coded
				// result stores no text at all, so it is matched through the name of its concept:
				// without that, searching for a coded value would silently return nothing even
				// though that value is what the row reports.
				sql.append(" AND (ro.value_text LIKE :resultText ESCAPE '!' ")
				        .append("   OR ro.value_complex LIKE :resultText ESCAPE '!' ")
				        .append("   OR ro.value_coded_name_id IN (SELECT rcn.concept_name_id FROM concept_name rcn ")
				        .append("     WHERE rcn.voided = false AND rcn.name LIKE :resultText ESCAPE '!') ");
				params.put("resultText", "%" + escapeLike(filter.getResultValue()) + "%");
				Double numeric = parseDouble(filter.getResultValue());
				if (numeric != null) {
					sql.append(
					    "   OR (ro.value_numeric IS NOT NULL AND CAST(ro.value_numeric AS DOUBLE) = :resultNumeric) ");
					params.put("resultNumeric", numeric);
				}
				sql.append(") ");
			}
		}
		if (filter.getLocationId() != null) {
			Set<Integer> locations = locationWithDescendants(filter.getLocationId());
			sql.append(" AND EXISTS (SELECT 1 FROM encounter el WHERE el.patient_id = p.patient_id ")
			        .append("   AND el.voided = false AND el.location_id IN ").append(inClause("loc", locations, params))
			        .append("   AND el.encounter_datetime >= :from AND el.encounter_datetime <= :to) ");
		}
		return sql.toString();
	}
	
	/**
	 * Restrictions for queries over recorded results. The aliases are passed in because the result and
	 * order tables are joined under different names in different queries.
	 */
	private void appendResultCriteria(StringBuilder sql, Map<String, Object> params, LabReportFilter filter,
	        String resultAlias, String orderAlias, String personAlias, String conceptAlias) {
		if (params.get("from") == null) {
			params.put("from", filter.getDateFrom());
			params.put("to", filter.getDateTo());
		}
		if (filter.getOrderTypeUuid() != null) {
			sql.append(" AND ").append(orderAlias).append(".order_type_id IN ")
			        .append("(SELECT otx.order_type_id FROM order_type otx WHERE otx.uuid = :orderTypeUuid) ");
			params.put("orderTypeUuid", filter.getOrderTypeUuid());
		}
		if (filter.getConceptUuid() != null) {
			sql.append(" AND ").append(conceptAlias).append(".uuid = :conceptUuid ");
			params.put("conceptUuid", filter.getConceptUuid());
		}
		if (filter.getGender() != null) {
			sql.append(" AND ").append(personAlias).append(".gender = :gender ");
			params.put("gender", filter.getGender());
		}
		if (filter.getObsStatus() != null) {
			sql.append(" AND ").append(resultAlias).append(".status = :obsStatus ");
			params.put("obsStatus", filter.getObsStatus());
		}
		if (filter.getResultValue() != null) {
			sql.append(" AND (").append(resultAlias).append(".value_text LIKE :resultText ESCAPE '!' OR ")
			        .append(resultAlias).append(".value_complex LIKE :resultText ESCAPE '!' OR ").append(resultAlias)
			        .append(".value_coded_name_id IN (SELECT rcn.concept_name_id FROM concept_name rcn ")
			        .append("   WHERE rcn.voided = false AND rcn.name LIKE :resultText ESCAPE '!') ");
			params.put("resultText", "%" + escapeLike(filter.getResultValue()) + "%");
			Double numeric = parseDouble(filter.getResultValue());
			if (numeric != null) {
				sql.append("   OR (").append(resultAlias).append(".value_numeric IS NOT NULL AND CAST(").append(resultAlias)
				        .append(".value_numeric AS DOUBLE) = :resultNumeric) ");
				params.put("resultNumeric", numeric);
			}
			sql.append(") ");
		}
		if (filter.getMinAgeMonths() != null) {
			sql.append(" AND ").append(personAlias).append(".birthdate IS NOT NULL AND ").append(personAlias)
			        .append(".birthdate <= :bornAtOrBefore ");
			params.put("bornAtOrBefore", monthsBefore(filter.getDateTo(), filter.getMinAgeMonths()));
		}
		if (filter.getMaxAgeMonths() != null) {
			sql.append(" AND ").append(personAlias).append(".birthdate IS NOT NULL AND ").append(personAlias)
			        .append(".birthdate > :bornAfter ");
			params.put("bornAfter", monthsBefore(filter.getDateTo(), filter.getMaxAgeMonths()));
		}
		if (filter.getLocationId() != null) {
			Set<Integer> locations = locationWithDescendants(filter.getLocationId());
			sql.append(" AND EXISTS (SELECT 1 FROM encounter el JOIN ").append(orderAlias)
			        .append(" ordx ON ordx.patient_id = el.patient_id ")
			        .append("   WHERE el.voided = false AND ordx.order_id = ").append(orderAlias).append(".order_id ")
			        .append("   AND el.location_id IN ").append(inClause("loc", locations, params))
			        .append("   AND el.encounter_datetime >= :from AND el.encounter_datetime <= :to) ");
		}
	}
	
	// ---------------------------------------------------------------- helpers
	
	private Map<String, TimeSeriesPoint> perDaySeries(String sql, Map<String, Object> params) {
		List<Object[]> rows = listRows(sql, params, row -> row);
		Map<String, TimeSeriesPoint> series = new LinkedHashMap<>();
		java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(ISO_DATE);
		for (Object[] row : rows) {
			Date day = asDate(row[0]);
			if (day == null) {
				continue;
			}
			String key = format.format(day);
			series.put(key, new TimeSeriesPoint(key, asLong(row[1])));
		}
		return series;
	}
	
	/**
	 * Renders an observation value the way a clinician would read it. Numeric values are returned
	 * unformatted rather than cast in SQL, because {@code CAST(x AS VARCHAR)} is not supported
	 * identically by every database.
	 */
	private static String resultValue(String text, Double numeric, String codedName) {
		if (text != null && !text.trim().isEmpty()) {
			return text;
		}
		if (codedName != null && !codedName.trim().isEmpty()) {
			return codedName;
		}
		if (numeric == null) {
			return null;
		}
		return numeric == Math.floor(numeric) && !Double.isInfinite(numeric) ? String.valueOf(numeric.longValue())
		        : String.valueOf(numeric);
	}
	
	private static Double hoursBetween(Date start, Date end) {
		if (start == null || end == null) {
			return null;
		}
		double millis = end.getTime() - start.getTime();
		// Results recorded before the order is a data quality problem; excluding it keeps the
		// averages meaningful instead of letting a handful of bad rows skew them.
		if (millis < 0) {
			return null;
		}
		return millis / 3600000d;
	}
	
	private static TurnaroundSummary summarise(List<Double> samples) {
		TurnaroundSummary summary = new TurnaroundSummary();
		summary.setCompletedOrders(samples.size());
		if (samples.isEmpty()) {
			return summary;
		}
		List<Double> sorted = new ArrayList<>(samples);
		java.util.Collections.sort(sorted);
		
		double total = 0d;
		for (Double value : sorted) {
			total += value;
		}
		summary.setAverageHours(round(total / sorted.size()));
		summary.setMedianHours(round(percentile(sorted, 50)));
		summary.setP90Hours(round(percentile(sorted, 90)));
		summary.setMinHours(round(sorted.get(0)));
		summary.setMaxHours(round(sorted.get(sorted.size() - 1)));
		return summary;
	}
	
	/**
	 * Nearest-rank percentile, interpolated when the rank falls between two samples.
	 * <p>
	 * Taking the nearest sample instead would report the median of two turnaround times as the shorter
	 * of the two, which understates turnaround by up to a factor of two and makes the figure unusable
	 * for judging whether results are returned promptly.
	 */
	private static double percentile(List<Double> sorted, int percentile) {
		if (sorted.isEmpty()) {
			return 0d;
		}
		if (sorted.size() == 1) {
			return sorted.get(0);
		}
		double position = percentile / 100d * (sorted.size() - 1);
		int lower = (int) Math.floor(position);
		int upper = (int) Math.ceil(position);
		if (lower == upper) {
			return sorted.get(lower);
		}
		double weight = position - lower;
		return sorted.get(lower) + weight * (sorted.get(upper) - sorted.get(lower));
	}
	
	private static Double round(double value) {
		return Math.round(value * 100d) / 100d;
	}
	
	private static Integer ageInMonths(Date birthdate, Date reference) {
		if (birthdate == null || reference == null) {
			return null;
		}
		Calendar birth = Calendar.getInstance();
		birth.setTime(birthdate);
		Calendar now = Calendar.getInstance();
		now.setTime(reference);
		int months = (now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)) * 12 + now.get(Calendar.MONTH)
		        - birth.get(Calendar.MONTH);
		if (now.get(Calendar.DAY_OF_MONTH) < birth.get(Calendar.DAY_OF_MONTH)) {
			months--;
		}
		return months < 0 ? null : months;
	}
	
	private static Date monthsBefore(Date reference, int months) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(reference);
		calendar.add(Calendar.MONTH, -months);
		return calendar.getTime();
	}
	
	private static String escapeLike(String value) {
		StringBuilder escaped = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '!' || c == '%' || c == '_') {
				escaped.append('!');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}
	
	private static Double parseDouble(String value) {
		try {
			return Double.valueOf(value.trim());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}
}
