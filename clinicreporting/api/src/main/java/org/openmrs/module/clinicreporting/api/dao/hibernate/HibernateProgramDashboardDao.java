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
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.clinicreporting.api.dao.AbstractReportDao;
import org.openmrs.module.clinicreporting.api.dao.ProgramDashboardDao;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.CrossTabRow;
import org.openmrs.module.clinicreporting.api.dto.Period;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * Programme dashboard queries written against the core OpenMRS schema.
 * <p>
 * Three things every method here depends on:
 * <ul>
 * <li><b>The programme is always the entry point.</b> Figures are found through
 * {@code patient_program}, so a dashboard counts the programme's own patients. There is no query in
 * this class that can count the whole clinic.</li>
 * <li><b>Patients, not enrolment rows.</b> A patient enrolled twice, or enrolled and re-enrolled,
 * counts once. Enrolment tables accumulate rows on re-enrolment, so counting rows would report a
 * programme that re-enrols people as having more patients than it has.</li>
 * <li><b>Dates are compared as timestamps and bucketed in Java.</b> Ranges are half-open at the
 * second, not truncated in SQL, so a range that ends mid-day does not lose that day's
 * observations.</li>
 * </ul>
 */
public class HibernateProgramDashboardDao extends AbstractReportDao implements ProgramDashboardDao {
	
	/**
	 * The patient set: everyone holding an unvoided enrolment in the programme that overlaps the range.
	 * Written once and reused because getting it subtly different between a tile and its chart is how a
	 * dashboard ends up showing a total that does not match the breakdown under it.
	 * <p>
	 * An enrolment with no {@code date_enrolled} is treated as having started before the range and an
	 * enrolment with no {@code date_completed} as still running, which is what those columns mean when
	 * they are empty.
	 */
	private static final String ENROLLED_IN_RANGE = "EXISTS (SELECT 1 FROM patient_program pp "
	        + "JOIN program pr ON pr.program_id = pp.program_id "
	        + "WHERE pp.patient_id = %s AND pp.voided = false AND pr.retired = false AND pr.uuid = :programUuid "
	        + "AND (pp.date_completed IS NULL OR pp.date_completed >= :from) "
	        + "AND (pp.date_enrolled IS NULL OR pp.date_enrolled <= :to))";
	
	/** {@code patient} plus {@code person}, the smallest base for a patient-level count. */
	private static final String PATIENT_BASE = " FROM patient p JOIN person pe ON pe.person_id = p.patient_id AND pe.voided = false ";
	
	// ---------------------------------------------------------------- programmes
	
	@Override
	public boolean programExists(String programUuid) {
		if (programUuid == null) {
			return false;
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("uuid", programUuid.toLowerCase(Locale.ROOT));
		return count("SELECT COUNT(*) FROM program WHERE retired = false AND uuid = :uuid", params) > 0;
	}
	
	@Override
	public Set<String> existingProgramUuids(Collection<String> programUuids) {
		Set<String> found = new LinkedHashSet<>();
		if (programUuids == null || programUuids.isEmpty()) {
			return found;
		}
		List<String> lower = new ArrayList<>(programUuids.size());
		for (String uuid : programUuids) {
			if (uuid != null && !uuid.trim().isEmpty()) {
				lower.add(uuid.trim().toLowerCase(Locale.ROOT));
			}
		}
		if (lower.isEmpty()) {
			return found;
		}
		Map<String, Object> params = new LinkedHashMap<>();
		String sql = "SELECT uuid FROM program WHERE retired = false AND uuid IN " + inClause("uuid", lower, params);
		for (Object[] row : listRows(sql, params, r -> r)) {
			found.add(asString(row[0]));
		}
		return found;
	}
	
	@Override
	public List<String> allProgramUuids() {
		Map<String, Object> params = new LinkedHashMap<>();
		String sql = "SELECT uuid FROM program WHERE retired = false AND uuid IS NOT NULL ORDER BY name, uuid";
		List<String> uuids = new ArrayList<>();
		for (Object[] row : listRows(sql, params, r -> r)) {
			uuids.add(asString(row[0]));
		}
		return uuids;
	}
	
	// ---------------------------------------------------------------- enrolment counts
	
	@Override
	public long countActiveEnrollments(String programUuid, Date from, Date to) {
		Map<String, Object> params = range(programUuid, from, to);
		return count("SELECT COUNT(DISTINCT p.patient_id)" + PATIENT_BASE + " WHERE p.voided = false AND "
		        + String.format(ENROLLED_IN_RANGE, "p.patient_id"),
		    params);
	}
	
	@Override
	public long countNewEnrollments(String programUuid, Date from, Date to) {
		Map<String, Object> params = range(programUuid, from, to);
		// date_enrolled <= :to alone would count anyone ever enrolled, so the lower bound matters:
		// without it "new in this period" would quietly be "new ever".
		String enrolled = "EXISTS (SELECT 1 FROM patient_program pp JOIN program pr ON pr.program_id = pp.program_id "
		        + "WHERE pp.patient_id = p.patient_id AND pp.voided = false AND pr.retired = false "
		        + "AND pr.uuid = :programUuid AND pp.date_enrolled IS NOT NULL "
		        + "AND pp.date_enrolled >= :from AND pp.date_enrolled <= :to)";
		return count("SELECT COUNT(DISTINCT p.patient_id)" + PATIENT_BASE + " WHERE p.voided = false AND " + enrolled,
		    params);
	}
	
	@Override
	public long countCompletedEnrollments(String programUuid, Date from, Date to) {
		Map<String, Object> params = range(programUuid, from, to);
		String completed = "EXISTS (SELECT 1 FROM patient_program pp JOIN program pr ON pr.program_id = pp.program_id "
		        + "WHERE pp.patient_id = p.patient_id AND pp.voided = false AND pr.retired = false "
		        + "AND pr.uuid = :programUuid AND pp.date_completed IS NOT NULL "
		        + "AND pp.date_completed >= :from AND pp.date_completed <= :to)";
		return count("SELECT COUNT(DISTINCT p.patient_id)" + PATIENT_BASE + " WHERE p.voided = false AND " + completed,
		    params);
	}
	
	@Override
	public long countEncounters(String programUuid, Date from, Date to) {
		Map<String, Object> params = range(programUuid, from, to);
		String sql = "SELECT COUNT(*) FROM encounter e JOIN patient p ON p.patient_id = e.patient_id "
		        + "WHERE e.voided = false AND p.voided = false AND e.encounter_datetime >= :from "
		        + "AND e.encounter_datetime <= :to AND " + String.format(ENROLLED_IN_RANGE, "e.patient_id");
		return count(sql, params);
	}
	
	@Override
	public List<BreakdownRow> enrolledByGender(String programUuid, Date from, Date to) {
		Map<String, Object> params = range(programUuid, from, to);
		String sql = "SELECT COALESCE(pe.gender, 'U') AS gender, COUNT(DISTINCT p.patient_id)" + PATIENT_BASE
		        + " WHERE p.voided = false AND " + String.format(ENROLLED_IN_RANGE, "p.patient_id")
		        + " GROUP BY COALESCE(pe.gender, 'U') ORDER BY gender";
		return listRows(sql, params,
		    row -> new BreakdownRow(asString(row[0]), genderLabel(asString(row[0])), asLong(row[1])));
	}
	
	@Override
	public List<BreakdownRow> enrolledByAgeBand(String programUuid, Date from, Date to) {
		Map<String, Object> params = range(programUuid, from, to);
		String sql = "SELECT pe.birthdate" + PATIENT_BASE + " WHERE p.voided = false AND "
		        + String.format(ENROLLED_IN_RANGE, "p.patient_id");
		
		Date reference = to;
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String band : AGE_BANDS) {
			counts.put(band, Long.valueOf(0L));
		}
		for (Object[] row : listRows(sql, params, r -> r)) {
			String band = ageBand(monthsBetween(asDate(row[0]), reference));
			Long current = counts.get(band);
			counts.put(band, current == null ? Long.valueOf(1L) : Long.valueOf(current + 1L));
		}
		
		List<BreakdownRow> rows = new ArrayList<>(AGE_BANDS.length);
		for (String band : AGE_BANDS) {
			rows.add(new BreakdownRow(band, band, counts.get(band).longValue()));
		}
		return rows;
	}
	
	@Override
	public List<TimeSeriesPoint> enrollmentsByMonth(String programUuid, Date from, Date to) {
		Map<String, Object> params = range(programUuid, from, to);
		String sql = "SELECT pp.patient_id, pp.date_enrolled FROM patient_program pp JOIN program pr ON pr.program_id = pp.program_id "
		        + "JOIN patient p ON p.patient_id = pp.patient_id "
		        + "WHERE pp.voided = false AND p.voided = false AND pr.retired = false AND pr.uuid = :programUuid "
		        + "AND pp.date_enrolled IS NOT NULL AND pp.date_enrolled >= :from AND pp.date_enrolled <= :to";
		
		// Every month in the range appears, including the empty ones: a gap in an enrolment series is
		// itself the finding, and a line that silently skips a month reads as continuity.
		Map<String, TimeSeriesPoint> series = Period.MONTH.expand(from, to);
		
		// Patients are counted, not enrolment rows, so the trend adds up to the same figure as the
		// "new enrolments" tile. A patient re-enrolled twice in one month is one patient, and a chart
		// that disagreed with the tile beside it would be the more visible of the two errors.
		Map<String, Set<Integer>> patientsByPeriod = new LinkedHashMap<>();
		for (Object[] row : listRows(sql, params, r -> r)) {
			Integer patientId = asInteger(row[0]);
			Date enrolled = asDate(row[1]);
			if (patientId == null || enrolled == null) {
				continue;
			}
			String label = Period.MONTH.label(enrolled);
			TimeSeriesPoint point = series.get(label);
			if (point == null) {
				point = new TimeSeriesPoint(label, 0L);
				series.put(label, point);
			}
			Set<Integer> patients = patientsByPeriod.get(label);
			if (patients == null) {
				patients = new LinkedHashSet<>();
				patientsByPeriod.put(label, patients);
			}
			patients.add(patientId);
		}
		for (Map.Entry<String, Set<Integer>> entry : patientsByPeriod.entrySet()) {
			TimeSeriesPoint point = series.get(entry.getKey());
			if (point != null) {
				point.setCount(entry.getValue().size());
			}
		}
		return new ArrayList<>(series.values());
	}
	
	// ---------------------------------------------------------------- clinical measures
	
	@Override
	public long countPatientsWithObservations(String programUuid, Date from, Date to, Collection<Integer> conceptIds) {
		Map<String, Object> params = range(programUuid, from, to);
		String sql = "SELECT COUNT(DISTINCT o.person_id) FROM obs o "
		        + "WHERE o.voided = false AND o.obs_datetime >= :from AND o.obs_datetime <= :to " + "AND o.concept_id IN "
		        + inClause("c", conceptIds, params) + " AND " + String.format(ENROLLED_IN_RANGE, "o.person_id");
		return count(sql, params);
	}
	
	@Override
	public long countObservations(String programUuid, Date from, Date to, Collection<Integer> conceptIds) {
		Map<String, Object> params = range(programUuid, from, to);
		String sql = "SELECT COUNT(*) FROM obs o "
		        + "WHERE o.voided = false AND o.obs_datetime >= :from AND o.obs_datetime <= :to " + "AND o.concept_id IN "
		        + inClause("c", conceptIds, params) + " AND " + String.format(ENROLLED_IN_RANGE, "o.person_id");
		return count(sql, params);
	}
	
	@Override
	public long countObservationsBelow(String programUuid, Date from, Date to, Collection<Integer> conceptIds,
	        double valueBelow) {
		Map<String, Object> params = range(programUuid, from, to);
		params.put("valueBelow", Double.valueOf(valueBelow));
		String sql = "SELECT COUNT(*) FROM obs o "
		        + "WHERE o.voided = false AND o.value_numeric IS NOT NULL AND o.value_numeric < :valueBelow "
		        + "AND o.obs_datetime >= :from AND o.obs_datetime <= :to " + "AND o.concept_id IN "
		        + inClause("c", conceptIds, params) + " AND " + String.format(ENROLLED_IN_RANGE, "o.person_id");
		return count(sql, params);
	}
	
	@Override
	public Double averageObservationValue(String programUuid, Date from, Date to, Collection<Integer> conceptIds) {
		Map<String, Object> params = range(programUuid, from, to);
		String sql = "SELECT AVG(o.value_numeric) FROM obs o " + "WHERE o.voided = false AND o.value_numeric IS NOT NULL "
		        + "AND o.obs_datetime >= :from AND o.obs_datetime <= :to " + "AND o.concept_id IN "
		        + inClause("c", conceptIds, params) + " AND " + String.format(ENROLLED_IN_RANGE, "o.person_id");
		List<?> rows = mapQuery(sql, params).list();
		if (rows.isEmpty()) {
			return null;
		}
		return asDouble(toArray(rows.get(0))[0]);
	}
	
	/**
	 * The most frequent answers recorded for the given concepts, as a share of everything recorded.
	 * <p>
	 * A coded answer is named; anything else is labelled by what it holds, because there is no name to
	 * show for it. The list is capped, since a concept answered with free text or a measurement has as
	 * many distinct answers as it has observations, and a chart of several thousand one-off values
	 * would answer no question. Coded questions, which is what this is for, sit far below the cap.
	 */
	private static final int MAX_DISTRIBUTION_ROWS = 25;
	
	@Override
	public List<BreakdownRow> observationValueDistribution(String programUuid, Date from, Date to,
	        Collection<Integer> conceptIds) {
		Map<String, Object> params = range(programUuid, from, to);
		
		// The answer to a coded question is in obs.value_coded_name_id, which points at the answer itself
		// rather than at a concept, so the name is read straight off concept_name. The raw columns come
		// back unaggregated alongside it because they are mutually exclusive: whichever one is set is the
		// answer, and deciding that in SQL would need a CAST that is not portable.
		String sql = "SELECT cn.name, o.value_numeric, o.value_text, o.value_complex, COUNT(*) AS answer_count "
		        + "FROM obs o LEFT JOIN concept_name cn ON cn.concept_name_id = o.value_coded_name_id "
		        + "WHERE o.voided = false AND o.obs_datetime >= :from AND o.obs_datetime <= :to " + "AND o.concept_id IN "
		        + inClause("c", conceptIds, params) + " AND " + String.format(ENROLLED_IN_RANGE, "o.person_id")
		        + " GROUP BY cn.name, o.value_numeric, o.value_text, o.value_complex "
		        + "ORDER BY answer_count DESC, cn.name LIMIT " + MAX_DISTRIBUTION_ROWS;
		
		List<BreakdownRow> rows = new ArrayList<>();
		Map<String, Long> merged = new LinkedHashMap<>();
		for (Object[] row : listRows(sql, params, r -> r)) {
			String label = answerLabel(asString(row[0]), asDouble(row[1]), asString(row[2]), asString(row[3]));
			long count = asLong(row[4]);
			// Two rows can share a label when, say, the same number was recorded once as a numeric and
			// once inside a coded answer; merging keeps the share of that answer honest.
			Long existing = merged.get(label);
			merged.put(label, existing == null ? Long.valueOf(count) : Long.valueOf(existing + count));
		}
		for (Map.Entry<String, Long> entry : merged.entrySet()) {
			rows.add(new BreakdownRow(entry.getKey(), entry.getKey(), entry.getValue().longValue()));
		}
		return rows;
	}
	
	/**
	 * @return a readable label for whichever answer column the observation actually filled in
	 */
	private static String answerLabel(String codedName, Double numeric, String text, String complex) {
		if (codedName != null && !codedName.trim().isEmpty()) {
			return codedName.trim();
		}
		if (numeric != null) {
			// A measurement is shown to one decimal place: a raw 6.283185307179586 is not a figure a
			// clinician can use.
			return String.format(Locale.ROOT, "%.1f", numeric);
		}
		if (text != null && !text.trim().isEmpty()) {
			return text.trim();
		}
		if (complex != null && !complex.trim().isEmpty()) {
			return complex.trim();
		}
		return "No answer recorded";
	}
	
	@Override
	public List<CrossTabRow> countDiagnosesByTreatment(String programUuid, Date from, Date to, int limit) {
		return diagnosesByTreatment(range(programUuid, from, to), limit, true);
	}
	
	@Override
	public List<CrossTabRow> countDiagnosesByTreatmentAcrossClinic(Date from, Date to, int limit) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("from", from);
		params.put("to", to);
		return diagnosesByTreatment(params, limit, false);
	}
	
	/**
	 * Builds and runs the diagnoses-by-treatment cross-tab.
	 * <p>
	 * The diagnosis and the treatment are joined through the encounter they share, so a treatment
	 * counts toward a diagnosis only when it was ordered on the same visit. When {@code scoped} is set
	 * the encounters are further restricted to the programme's own patients, so the matrix lines up
	 * with the tiles above it; the clinic-wide matrix deliberately drops that restriction.
	 *
	 * @param params the query parameters, already carrying {@code from} and {@code to} (and
	 *            {@code programUuid} when scoped)
	 * @param limit the most pairs to return, most frequent first
	 * @param scoped true to restrict to the programme's patients, false for the whole clinic
	 */
	private List<CrossTabRow> diagnosesByTreatment(Map<String, Object> params, int limit, boolean scoped) {
		// encounter_diagnosis arrived in OpenMRS 2.2 and is only present on servers that record coded
		// diagnoses; drug_order is present wherever the concept dictionary is. Rather than emit SQL
		// against a table that may not exist, report nothing, which is the truthful answer.
		if (!tableExists("encounter_diagnosis") || !tableExists("drug_order")) {
			return new ArrayList<>();
		}
		
		String diagnosisName = conceptName("dc");
		String treatmentName = conceptName("tc");
		// Every column is aliased because two of them are called uuid and two more are name; Hibernate
		// rejects a native query whose auto-discovered result aliases collide.
		StringBuilder sql = new StringBuilder("SELECT dc.uuid AS diagnosis_uuid, ").append(diagnosisName)
		        .append(" AS diagnosis_name, dc.short_name AS diagnosis_short, tc.uuid AS treatment_uuid, ")
		        .append(treatmentName).append(" AS treatment_name, tc.short_name AS treatment_short, ")
		        .append("COUNT(*) AS pair_count ").append(" FROM encounter_diagnosis ed ")
		        .append(" JOIN encounter e ON e.encounter_id = ed.encounter_id AND e.voided = false ")
		        .append(" JOIN concept dc ON dc.concept_id = ed.diagnosis_coded ")
		        .append(" JOIN orders o ON o.encounter_id = ed.encounter_id AND o.voided = false ")
		        .append(" JOIN drug_order dor ON dor.order_id = o.order_id ")
		        .append(" JOIN concept tc ON tc.concept_id = o.concept_id ")
		        .append(" WHERE ed.voided = false AND e.encounter_datetime >= :from AND e.encounter_datetime <= :to ");
		if (scoped) {
			sql.append(" AND ").append(String.format(ENROLLED_IN_RANGE, "e.patient_id")).append(' ');
		}
		sql.append(" GROUP BY dc.concept_id, dc.uuid, dc.short_name, ").append(diagnosisName)
		        .append(", tc.concept_id, tc.uuid, tc.short_name, ").append(treatmentName)
		        .append(" ORDER BY COUNT(*) DESC, dc.short_name, tc.short_name ");
		bindConceptLocale(params);
		
		List<CrossTabRow> rows = new ArrayList<>();
		for (Object[] row : paged(mapQuery(sql.toString(), params), 0, limit)) {
			String diagnosisLabel = asString(row[1]);
			if (diagnosisLabel == null || diagnosisLabel.isEmpty()) {
				diagnosisLabel = asString(row[2]);
			}
			String treatmentLabel = asString(row[4]);
			if (treatmentLabel == null || treatmentLabel.isEmpty()) {
				treatmentLabel = asString(row[5]);
			}
			rows.add(new CrossTabRow(asString(row[0]), diagnosisLabel, asString(row[3]), treatmentLabel, asLong(row[6])));
		}
		return rows;
	}
	
	// ---------------------------------------------------------------- concepts
	
	@Override
	public Set<Integer> resolveConceptIds(Collection<String> uuids, Collection<String> names) {
		Set<Integer> ids = new LinkedHashSet<>();
		if (uuids != null && !uuids.isEmpty()) {
			Map<String, Object> params = new LinkedHashMap<>();
			List<String> wanted = new ArrayList<>();
			for (String uuid : uuids) {
				if (uuid != null && !uuid.trim().isEmpty()) {
					wanted.add(uuid.trim().toLowerCase(Locale.ROOT));
				}
			}
			if (!wanted.isEmpty()) {
				String sql = "SELECT concept_id FROM concept WHERE retired IS NULL AND uuid IN "
				        + inClause("u", wanted, params);
				for (Object[] row : listRows(sql, params, r -> r)) {
					addIfNotNull(ids, asInteger(row[0]));
				}
			}
		}
		if (names != null && !names.isEmpty()) {
			ids.addAll(lookupConceptIdsByName(names));
		}
		return ids;
	}
	
	@Override
	public Set<String> unresolvedConceptNames(Collection<String> names) {
		Set<String> missing = new LinkedHashSet<>();
		if (names == null || names.isEmpty()) {
			return missing;
		}
		for (String name : names) {
			if (name == null || name.trim().isEmpty()) {
				continue;
			}
			if (!hasConceptNamed(name.trim())) {
				missing.add(name.trim());
			}
		}
		return missing;
	}
	
	/**
	 * Concept names are matched case-insensitively and against any locale, because a definition file is
	 * written once and then read on a server whose dictionary may be in French.
	 */
	private Set<Integer> lookupConceptIdsByName(Collection<String> names) {
		Set<Integer> ids = new LinkedHashSet<>();
		for (String name : names) {
			if (name == null || name.trim().isEmpty()) {
				continue;
			}
			Map<String, Object> params = new LinkedHashMap<>();
			params.put("name", name.trim().toLowerCase(Locale.ROOT));
			String sql = "SELECT c.concept_id FROM concept c JOIN concept_name cn ON cn.concept_id = c.concept_id "
			        + "WHERE c.retired IS NULL AND cn.voided = false AND LOWER(cn.name) = :name";
			for (Object[] row : listRows(sql, params, r -> r)) {
				addIfNotNull(ids, asInteger(row[0]));
			}
		}
		return ids;
	}
	
	private boolean hasConceptNamed(String name) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("name", name.toLowerCase(Locale.ROOT));
		return count("SELECT COUNT(*) FROM concept c JOIN concept_name cn ON cn.concept_id = c.concept_id "
		        + "WHERE c.retired IS NULL AND cn.voided = false AND LOWER(cn.name) = :name",
		    params) > 0;
	}
	
	// ---------------------------------------------------------------- helpers
	
	private Map<String, Object> range(String programUuid, Date from, Date to) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("programUuid", programUuid == null ? "" : programUuid.trim().toLowerCase(Locale.ROOT));
		params.put("from", from);
		params.put("to", to);
		return params;
	}
	
	private static void addIfNotNull(Set<Integer> ids, Integer value) {
		if (value != null) {
			ids.add(value);
		}
	}
	
	/**
	 * OpenMRS stores {@code M}, {@code F} and {@code U}. The stored letter is kept as the row key, so
	 * filtering and totals still line up with the database, and only the label is expanded.
	 */
	private static String genderLabel(String gender) {
		if ("M".equals(gender)) {
			return "Male";
		}
		if ("F".equals(gender)) {
			return "Female";
		}
		return "Not recorded";
	}
	
}
