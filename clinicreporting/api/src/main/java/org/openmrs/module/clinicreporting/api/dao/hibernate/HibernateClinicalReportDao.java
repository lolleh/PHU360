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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.clinicreporting.api.dao.AbstractReportDao;
import org.openmrs.module.clinicreporting.api.dao.ClinicalReportDao;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.ClinicalReportFilter;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * Clinical and register aggregate queries against the core OpenMRS schema.
 */
public class HibernateClinicalReportDao extends AbstractReportDao implements ClinicalReportDao {
	
	/**
	 * Day truncation via {@code CAST(... AS DATE)} is the one date expression used here, because MySQL,
	 * MariaDB, PostgreSQL and H2 all support it. Coarser bucketing happens in Java, in the service
	 * layer.
	 */
	private static final String DAY = "CAST(%s AS DATE)";
	
	@Override
	public Map<String, TimeSeriesPoint> encountersPerDay(ClinicalReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder("SELECT ").append(String.format(DAY, "e.encounter_datetime"))
		        .append(" AS bucket, COUNT(*) FROM encounter e ")
		        .append(" WHERE e.voided = false AND e.encounter_datetime >= :from AND e.encounter_datetime <= :to ");
		appendEncounterCriteria(sql, params, filter);
		sql.append(" GROUP BY ").append(String.format(DAY, "e.encounter_datetime")).append(" ORDER BY bucket ");
		
		return perDaySeries(sql.toString(), params);
	}
	
	@Override
	public Map<String, TimeSeriesPoint> observationsPerDay(ClinicalReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder("SELECT ").append(String.format(DAY, "o.obs_datetime"))
		        .append(" AS bucket, COUNT(*) FROM obs o ")
		        .append(" WHERE o.voided = false AND o.obs_datetime >= :from AND o.obs_datetime <= :to ");
		appendObservationCriteria(sql, params, filter);
		sql.append(" GROUP BY ").append(String.format(DAY, "o.obs_datetime")).append(" ORDER BY bucket ");
		
		return perDaySeries(sql.toString(), params);
	}
	
	@Override
	public List<BreakdownRow> countByEncounterType(ClinicalReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder(
		        "SELECT et.uuid, et.name, COUNT(*) FROM encounter e JOIN encounter_type et ON et.encounter_type_id = e.encounter_type ")
		        .append(
		            " WHERE e.voided = false AND et.retired = false AND e.encounter_datetime >= :from AND e.encounter_datetime <= :to ");
		appendEncounterCriteria(sql, params, filter);
		sql.append(" GROUP BY et.encounter_type_id, et.uuid, et.name ORDER BY COUNT(*) DESC, et.name ");
		return listRows(sql.toString(), params, row -> new BreakdownRow(asString(row[0]), asString(row[1]), asLong(row[2])));
	}
	
	@Override
	public List<BreakdownRow> countTopConcepts(ClinicalReportFilter filter, int limit) {
		Map<String, Object> params = new LinkedHashMap<>();
		String conceptName = conceptName("c");
		StringBuilder sql = new StringBuilder("SELECT c.uuid, c.short_name, ").append(conceptName)
		        .append(", COUNT(*) FROM obs o ").append(" JOIN concept c ON c.concept_id = o.concept_id ")
		        .append(" WHERE o.voided = false AND o.obs_datetime >= :from AND o.obs_datetime <= :to ");
		appendObservationCriteria(sql, params, filter);
		sql.append(" GROUP BY c.concept_id, c.uuid, c.short_name, ").append(conceptName).append(" ORDER BY COUNT(*) DESC ");
		bindConceptLocale(params);
		
		// The limit is applied by Hibernate rather than by a SQL LIMIT clause, because a bound
		// parameter inside LIMIT is not accepted by every supported database.
		List<Object[]> rows = paged(mapQuery(sql.toString(), params), 0, limit);
		List<BreakdownRow> breakdown = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			String label = asString(row[2]);
			if (label == null || label.isEmpty()) {
				label = asString(row[1]);
			}
			breakdown.add(new BreakdownRow(asString(row[0]), label, asLong(row[3])));
		}
		return breakdown;
	}
	
	@Override
	public List<BreakdownRow> countByProgram(ClinicalReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("from", filter.getDateFrom());
		params.put("to", filter.getDateTo());
		
		StringBuilder sql = new StringBuilder("SELECT prg.uuid, prg.name, COUNT(DISTINCT pp.patient_id) ")
		        .append("FROM patient_program pp JOIN program prg ON prg.program_id = pp.program_id ")
		        .append(" WHERE pp.voided = false AND prg.retired = false ")
		        .append("   AND (pp.date_enrolled IS NULL OR pp.date_enrolled <= :to) ")
		        .append("   AND (pp.date_completed IS NULL OR pp.date_completed >= :from) ");
		if (filter.getProgramUuid() != null) {
			sql.append(" AND prg.uuid = :programUuid ");
			params.put("programUuid", filter.getProgramUuid());
		}
		if (filter.getLocationId() != null) {
			Set<Integer> locations = locationWithDescendants(filter.getLocationId());
			sql.append(" AND pp.location_id IN ").append(inClause("loc", locations, params));
		}
		sql.append(" GROUP BY prg.program_id, prg.uuid, prg.name ORDER BY prg.name ");
		return listRows(sql.toString(), params, row -> new BreakdownRow(asString(row[0]), asString(row[1]), asLong(row[2])));
	}
	
	@Override
	public long countUniquePatientsWithEncounters(ClinicalReportFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT e.patient_id) FROM encounter e ")
		        .append(" WHERE e.voided = false AND e.encounter_datetime >= :from AND e.encounter_datetime <= :to ");
		appendEncounterCriteria(sql, params, filter);
		return count(sql.toString(), params);
	}
	
	// ---------------------------------------------------------------- helpers
	
	private Map<String, TimeSeriesPoint> perDaySeries(String sql, Map<String, Object> params) {
		List<Object[]> rows = listRows(sql, params, row -> row);
		Map<String, TimeSeriesPoint> series = new LinkedHashMap<>();
		for (Object[] row : rows) {
			Date day = asDate(row[0]);
			if (day == null) {
				continue;
			}
			series.put(new java.text.SimpleDateFormat("yyyy-MM-dd").format(day),
			    new TimeSeriesPoint(new java.text.SimpleDateFormat("yyyy-MM-dd").format(day), asLong(row[1])));
		}
		return series;
	}
	
	/**
	 * Applies the location, encounter type and program restrictions shared by the encounter queries.
	 */
	private void appendEncounterCriteria(StringBuilder sql, Map<String, Object> params, ClinicalReportFilter filter) {
		if (params.get("from") == null) {
			params.put("from", filter.getDateFrom());
			params.put("to", filter.getDateTo());
		}
		if (filter.getEncounterTypeUuid() != null) {
			sql.append(
			    " AND e.encounter_type IN (SELECT etx.encounter_type_id FROM encounter_type etx WHERE etx.uuid = :encounterTypeUuid) ");
			params.put("encounterTypeUuid", filter.getEncounterTypeUuid());
		}
		if (filter.getLocationId() != null) {
			Set<Integer> locations = locationWithDescendants(filter.getLocationId());
			sql.append(" AND e.location_id IN ").append(inClause("loc", locations, params));
		}
		if (filter.getProgramUuid() != null) {
			sql.append(
			    " AND EXISTS (SELECT 1 FROM patient_program ppx JOIN program prgx ON prgx.program_id = ppx.program_id ")
			        .append("   WHERE ppx.patient_id = e.patient_id AND ppx.voided = false AND prgx.uuid = :programUuid ")
			        .append("     AND (ppx.date_completed IS NULL OR ppx.date_completed >= :from) ")
			        .append("     AND (ppx.date_enrolled IS NULL OR ppx.date_enrolled <= :to)) ");
			params.put("programUuid", filter.getProgramUuid());
		}
	}
	
	/**
	 * Applies the location, concept and program restrictions shared by the observation queries. A
	 * concept uuid may name either a single concept or a concept set, in which case members of that set
	 * are included.
	 */
	private void appendObservationCriteria(StringBuilder sql, Map<String, Object> params, ClinicalReportFilter filter) {
		if (params.get("from") == null) {
			params.put("from", filter.getDateFrom());
			params.put("to", filter.getDateTo());
		}
		if (filter.getConceptUuid() != null) {
			// concept_set records membership as concept_set = the id of the concept acting as the
			// set, not the set's own row id, so the set's uuid has to be resolved to a concept_id
			// before it can be compared.
			sql.append(" AND (o.concept_id IN (SELECT cc.concept_id FROM concept cc WHERE cc.uuid = :conceptUuid) ").append(
			    "   OR o.concept_id IN (SELECT cs.concept_id FROM concept_set cs ").append(
			        "     WHERE cs.concept_set = (SELECT cset.concept_id FROM concept cset WHERE cset.uuid = :conceptUuid))) ");
			params.put("conceptUuid", filter.getConceptUuid());
		}
		if (filter.getLocationId() != null) {
			Set<Integer> locations = locationWithDescendants(filter.getLocationId());
			sql.append(" AND o.location_id IN ").append(inClause("loc", locations, params));
		}
		if (filter.getProgramUuid() != null) {
			sql.append(
			    " AND EXISTS (SELECT 1 FROM patient_program ppx JOIN program prgx ON prgx.program_id = ppx.program_id ")
			        .append("   WHERE ppx.patient_id = o.person_id AND ppx.voided = false AND prgx.uuid = :programUuid ")
			        .append("     AND (ppx.date_completed IS NULL OR ppx.date_completed >= :from) ")
			        .append("     AND (ppx.date_enrolled IS NULL OR ppx.date_enrolled <= :to)) ");
			params.put("programUuid", filter.getProgramUuid());
		}
	}
}
