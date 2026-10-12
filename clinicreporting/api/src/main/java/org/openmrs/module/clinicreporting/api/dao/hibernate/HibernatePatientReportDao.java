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
import org.openmrs.module.clinicreporting.api.dao.PatientReportDao;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;

/**
 * Patient listing queries written against the core OpenMRS schema.
 */
public class HibernatePatientReportDao extends AbstractReportDao implements PatientReportDao {
	
	/**
	 * Resolves one identifier per patient, preferring the one flagged preferred and falling back to the
	 * highest value otherwise. Taking a plain {@code MAX(identifier)} would silently return an
	 * alphabetically last identifier rather than the preferred one, which is the value OpenMRS itself
	 * treats as the patient's identifier. The location is taken from the same expression so that the
	 * identifier and the location shown always come from the same identifier row.
	 */
	private static final String PREFERRED_IDENTIFIER_JOIN = " LEFT JOIN (SELECT i.patient_id, "
	        + "     COALESCE(MAX(CASE WHEN i.preferred THEN i.identifier END), MAX(i.identifier)) AS identifier, "
	        + "     COALESCE(MAX(CASE WHEN i.preferred THEN i.location_id END), MAX(i.location_id)) AS location_id "
	        + "   FROM patient_identifier i WHERE i.voided = false GROUP BY i.patient_id) pic ON pic.patient_id = p.patient_id ";
	
	/**
	 * Full FROM for the patient listing.
	 * <p>
	 * Name and identifier are resolved through derived tables that pick exactly one row per patient, so
	 * a patient with several names or identifiers cannot duplicate results or inflate counts.
	 * {@code CASE WHEN <boolean column>} is used instead of {@code preferred = 1} because PostgreSQL
	 * will not compare a boolean to an integer.
	 * <p>
	 * The aliases here ({@code pnc_n}, {@code pic}, {@code pnc}) must match the ones used by
	 * {@link #buildAgeAgnosticWhere} and by {@link #PATIENT_BASE}, since the filter conditions are
	 * shared between the listing and the aggregate queries.
	 */
	private static final String PATIENT_JOINS = " FROM patient p "
	        + " JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false " + " LEFT JOIN (SELECT n.person_id, "
	        + "     COALESCE(MAX(CASE WHEN n.preferred THEN n.person_name_id END), MAX(n.person_name_id)) AS person_name_id "
	        + "   FROM person_name n WHERE n.voided = false GROUP BY n.person_id) pnc ON pnc.person_id = pe.person_id "
	        + " LEFT JOIN person_name pnc_n ON pnc_n.person_name_id = pnc.person_name_id " + PREFERRED_IDENTIFIER_JOIN
	        + " LEFT JOIN (SELECT pp.patient_id, MAX(pp.patient_program_id) AS patient_program_id "
	        + "   FROM patient_program pp WHERE pp.voided = false "
	        + "     AND (pp.date_completed IS NULL OR pp.date_completed >= :progFrom) "
	        + "     AND (pp.date_enrolled IS NULL OR pp.date_enrolled <= :progTo) "
	        + "   GROUP BY pp.patient_id) pps ON pps.patient_id = p.patient_id "
	        + " LEFT JOIN patient_program pp2 ON pp2.patient_program_id = pps.patient_program_id "
	        + " LEFT JOIN program prg ON prg.program_id = pp2.program_id AND prg.retired = false "
	        + " LEFT JOIN (SELECT e.patient_id, MAX(e.encounter_datetime) AS last_encounter_datetime "
	        + "   FROM encounter e WHERE e.voided = false GROUP BY e.patient_id) lv ON lv.patient_id = p.patient_id "
	        + " LEFT JOIN location loc ON loc.location_id = pic.location_id ";
	
	/**
	 * Minimal FROM for aggregate queries, so that counting never depends on the listing's joins and can
	 * therefore never disagree with the number of distinct patients.
	 */
	private static final String PATIENT_BASE = " FROM patient p JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false "
	        + " LEFT JOIN (SELECT i.patient_id, "
	        + "     COALESCE(MAX(CASE WHEN i.preferred THEN i.identifier END), MAX(i.identifier)) AS identifier "
	        + "   FROM patient_identifier i WHERE i.voided = false GROUP BY i.patient_id) pic ON pic.patient_id = p.patient_id "
	        + " LEFT JOIN (SELECT n.person_id, COALESCE(MAX(CASE WHEN n.preferred THEN n.person_name_id END), MAX(n.person_name_id)) AS person_name_id "
	        + "   FROM person_name n WHERE n.voided = false GROUP BY n.person_id) pnc ON pnc.person_id = pe.person_id "
	        + " LEFT JOIN person_name pnc_n ON pnc_n.person_name_id = pnc.person_name_id ";
	
	@Override
	public PagedResult<PatientSummaryRow> findPatients(PatientReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		String where = buildWhere(filter, params, true);
		
		// Every column is aliased: three of them are called "name", and Hibernate rejects a
		// native query whose discovered result aliases collide.
		String sql = "SELECT p.patient_id AS patient_id, pe.uuid AS patient_uuid, "
		        + " pnc_n.given_name AS given_name, pnc_n.middle_name AS middle_name, "
		        + " pnc_n.family_name AS family_name, pic.identifier AS identifier, "
		        + " pe.gender AS gender, pe.birthdate AS birthdate, loc.name AS location_name, "
		        + " prg.name AS program_name, pp2.date_enrolled AS enrollment_date, "
		        + " lv.last_encounter_datetime AS last_visit " + PATIENT_JOINS + where
		        + " ORDER BY pnc_n.family_name, pnc_n.given_name, p.patient_id";
		
		@SuppressWarnings("unchecked")
		List<Object[]> rows = paged(mapQuery(sql, params), filter.getOffset(), filter.getSize());
		
		List<PatientSummaryRow> results = new ArrayList<>(rows.size());
		Date ageReference = filter.getDateTo();
		for (Object[] row : rows) {
			PatientSummaryRow summary = new PatientSummaryRow();
			summary.setPatientId(asInteger(row[0]));
			summary.setPatientUuid(asString(row[1]));
			summary.setDisplay(displayName(asString(row[2]), asString(row[3]), asString(row[4])));
			summary.setIdentifier(asString(row[5]));
			summary.setGender(asString(row[6]));
			Date birthdate = asDate(row[7]);
			summary.setBirthdate(birthdate);
			summary.setAgeInMonths(monthsBetween(birthdate, ageReference));
			summary.setLocationName(asString(row[8]));
			summary.setProgramName(asString(row[9]));
			summary.setEnrollmentDate(asDate(row[10]));
			summary.setLastVisit(asDate(row[11]));
			results.add(summary);
		}
		return new PagedResult<>(results, countPatients(filter), filter.getPage(), filter.getSize());
	}
	
	@Override
	public long countPatients(PatientReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		String where = buildWhere(filter, params, false);
		return count("SELECT COUNT(*)" + PATIENT_BASE + where, params);
	}
	
	@Override
	public List<BreakdownRow> countByGender(PatientReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		String where = buildWhere(filter, params, false);
		String sql = "SELECT COALESCE(pe.gender, 'U') AS gender, COUNT(*)" + PATIENT_BASE + where
		        + " GROUP BY COALESCE(pe.gender, 'U') ORDER BY gender";
		return listRows(sql, params, row -> new BreakdownRow(asString(row[0]), asString(row[0]), asLong(row[1])));
	}
	
	@Override
	public List<BreakdownRow> countByAgeBand(PatientReportFilter filter) {
		// Age is derived from birthdate in Java so the module does not depend on any
		// database-specific date arithmetic, and unknown birthdates get their own band.
		Map<String, Object> params = new LinkedHashMap<>();
		String where = buildAgeAgnosticWhere(filter, params);
		String sql = "SELECT pe.birthdate" + PATIENT_BASE + where;
		
		Date reference = filter.getDateTo();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String band : AGE_BANDS) {
			counts.put(band, 0L);
		}
		for (Object[] row : listRows(sql, params, r -> r)) {
			Integer months = monthsBetween(asDate(row[0]), reference);
			String band = ageBand(months);
			Long current = counts.get(band);
			counts.put(band, current == null ? 1L : current + 1L);
		}
		
		List<BreakdownRow> rows = new ArrayList<>(counts.size());
		for (String band : AGE_BANDS) {
			rows.add(new BreakdownRow(band, band, counts.get(band)));
		}
		return rows;
	}
	
	// ---------------------------------------------------------------- filter assembly
	
	/**
	 * @param withProgramJoin whether the FROM clause being used contains the patient_program join,
	 *            which is the only place {@code :progFrom} and {@code :progTo} appear. A parameter that
	 *            is bound but not present in the SQL is an error, so the caller has to say which FROM
	 *            it paired this WHERE with.
	 */
	private String buildWhere(PatientReportFilter filter, Map<String, Object> params, boolean withProgramJoin) {
		if (withProgramJoin) {
			params.put("progFrom", filter.getDateFrom());
			params.put("progTo", filter.getDateTo());
		}
		return buildAgeAgnosticWhere(filter, params);
	}
	
	/**
	 * The conditions that are independent of the age band breakdown.
	 */
	private String buildAgeAgnosticWhere(PatientReportFilter filter, Map<String, Object> params) {
		StringBuilder sql = new StringBuilder(" WHERE p.voided = false ");
		
		if (filter.getGender() != null) {
			sql.append(" AND pe.gender = :gender ");
			params.put("gender", filter.getGender());
		}
		if (filter.getQ() != null) {
			sql.append(" AND (LOWER(pnc_n.given_name) LIKE :q ESCAPE '!' ")
			        .append("   OR LOWER(pnc_n.family_name) LIKE :q ESCAPE '!' ")
			        .append("   OR LOWER(COALESCE(pic.identifier, '')) LIKE :q ESCAPE '!') ");
			params.put("q", likePattern(filter.getQ()));
		}
		if (filter.getProgramUuid() != null) {
			sql.append(
			    " AND EXISTS (SELECT 1 FROM patient_program ppf JOIN program prgf ON prgf.program_id = ppf.program_id ")
			        .append("   WHERE ppf.patient_id = p.patient_id AND ppf.voided = false AND prgf.uuid = :programUuid ")
			        .append("     AND (ppf.date_completed IS NULL OR ppf.date_completed >= :from) ")
			        .append("     AND (ppf.date_enrolled IS NULL OR ppf.date_enrolled <= :to)) ");
			params.put("programUuid", filter.getProgramUuid());
			params.put("from", filter.getDateFrom());
			params.put("to", filter.getDateTo());
		}
		if (filter.getCohortUuid() != null) {
			// cohort and cohort_member are core OpenMRS tables, so this works without the
			// optional cohort module. Membership dates are honoured so a cohort can be
			// scoped to the reporting period rather than only ever "now".
			sql.append(" AND EXISTS (SELECT 1 FROM cohort_member cm JOIN cohort co ON co.cohort_id = cm.cohort_id ")
			        .append("   WHERE cm.patient_id = p.patient_id AND cm.voided = false AND co.voided = false ")
			        .append("     AND co.uuid = :cohortUuid ")
			        .append("     AND (cm.start_date IS NULL OR cm.start_date <= :to) ")
			        .append("     AND (cm.end_date IS NULL OR cm.end_date >= :from)) ");
			params.put("cohortUuid", filter.getCohortUuid());
			params.put("from", filter.getDateFrom());
			params.put("to", filter.getDateTo());
		}
		if (filter.getLocationId() != null) {
			Set<Integer> locations = locationWithDescendants(filter.getLocationId());
			sql.append(" AND EXISTS (SELECT 1 FROM encounter el WHERE el.patient_id = p.patient_id ")
			        .append("   AND el.voided = false AND el.location_id IN ").append(inClause("loc", locations, params))
			        .append("   AND el.encounter_datetime >= :from AND el.encounter_datetime <= :to) ");
			params.put("from", filter.getDateFrom());
			params.put("to", filter.getDateTo());
		}
		if (Boolean.TRUE.equals(filter.getSeenInRange())) {
			sql.append(" AND EXISTS (SELECT 1 FROM encounter es WHERE es.patient_id = p.patient_id ").append(
			    "   AND es.voided = false AND es.encounter_datetime >= :from AND es.encounter_datetime <= :to) ");
			params.put("from", filter.getDateFrom());
			params.put("to", filter.getDateTo());
		}
		applyAgeCutoffs(filter, sql, params);
		return sql.toString();
	}
	
	/**
	 * Age constraints are expressed as birthdate cutoffs computed in Java, which keeps the SQL free of
	 * database-specific month arithmetic. Patients with no recorded birthdate are excluded whenever an
	 * age constraint is present, and are reported separately in the "Unknown" age band.
	 */
	private void applyAgeCutoffs(PatientReportFilter filter, StringBuilder sql, Map<String, Object> params) {
		Date reference = filter.getDateTo();
		if (filter.getMinAgeMonths() != null) {
			sql.append(" AND pe.birthdate IS NOT NULL AND pe.birthdate <= :bornAtOrBefore ");
			params.put("bornAtOrBefore", monthsBefore(reference, filter.getMinAgeMonths()));
		}
		if (filter.getMaxAgeMonths() != null) {
			sql.append(" AND pe.birthdate IS NOT NULL AND pe.birthdate > :bornAfter ");
			params.put("bornAfter", monthsBefore(reference, filter.getMaxAgeMonths()));
		}
	}
	
	// ---------------------------------------------------------------- helpers
	
	/**
	 * Escapes the LIKE wildcards in user input. The escape character is {@code !} rather than a
	 * backslash because backslash escaping in string literals is not portable across MySQL and
	 * PostgreSQL.
	 */
	private static String likePattern(String value) {
		String lowered = value.toLowerCase().trim();
		StringBuilder escaped = new StringBuilder(lowered.length() + 2);
		escaped.append('%');
		for (int i = 0; i < lowered.length(); i++) {
			char c = lowered.charAt(i);
			if (c == '!' || c == '%' || c == '_') {
				escaped.append('!');
			}
			escaped.append(c);
		}
		return escaped.append('%').toString();
	}
	
	private static Date monthsBefore(Date reference, int months) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(reference);
		calendar.add(Calendar.MONTH, -months);
		return calendar.getTime();
	}
	
	/**
	 * Mirrors the shape of {@code Person.getDisplay()} without depending on the Person object, so a
	 * listing of thousands of rows does not instantiate thousands of domain objects.
	 */
	static String displayName(String given, String middle, String family) {
		StringBuilder name = new StringBuilder();
		appendPart(name, given);
		appendPart(name, middle);
		appendPart(name, family);
		String result = name.toString().trim();
		return result.isEmpty() ? "Preferred name missing" : result;
	}
	
	private static void appendPart(StringBuilder target, String part) {
		if (part == null || part.trim().isEmpty()) {
			return;
		}
		if (target.length() > 0) {
			target.append(' ');
		}
		target.append(part.trim());
	}
}
