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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;

/**
 * Computes the indicators and sections a report's {@link ReportLayout} declares.
 * <p>
 * Where {@link IndicatorFacts} answers "how much of each shape of encounter is there", this answers
 * the questions a particular register exists to answer - how many live births, what share were low
 * birth weight, which delivery type. The two are separate because they read different tables: the
 * other class reads {@code encounter} and {@code conditions}, this one reads {@code obs} and the
 * concept dictionary.
 * <p>
 * Concepts and answers are named in the mapping and resolved here, so a mapping written against one
 * installation's concept ids works on another. A concept the running database does not have is
 * dropped rather than counted as zero, and the caller is told how many were dropped so a register
 * whose concepts were never exported reports an honest empty page instead of a wall of zeros that
 * looks like real epidemiological data.
 * <p>
 * Every figure is an aggregate count. Nothing here selects patient-level rows out of the database.
 */
public final class ReportIndicators {
	
	private ReportIndicators() {
	}
	
	/**
	 * Names this deployment uses for affirmative and negative answers.
	 * <p>
	 * Two spellings, because OpenMRS dictionaries carry both and a data package may load either. They
	 * resolve to the same two concepts on a stock installation, so {@link #yesIds} de-duplicates rather
	 * than binding the same id twice.
	 */
	private static final String[] YES_ANSWERS = { "Yes", "True" };
	
	/**
	 * Resolves concept and answer names to ids, and ids back to names. Kept as an interface so this
	 * class needs no session of its own to be tested.
	 */
	public interface Concepts {
		
		Integer conceptId(String name);
		
		String conceptName(int id);
	}
	
	/**
	 * The upper bound in days of each age band, in the order the labels are written below. The last is
	 * unbounded so an encounter filed against a paediatric encounter type that belongs to a
	 * six-year-old shows up rather than disappearing between bands.
	 */
	private static final int[] AGE_BAND_MAX_DAYS = { 28, 89, 179, 269, 364, 729, 1824, Integer.MAX_VALUE };
	
	/**
	 * Spelled out rather than derived from the bounds above: the bands change unit at twelve months, so
	 * a generated "start - end" label would read "1-1 years" for the twelve-to-twenty-three-month band.
	 */
	private static final String[] AGE_BAND_LABELS = { "0-28 days", "1-2 months", "3-5 months", "6-8 months", "9-11 months",
	        "1-2 years", "2-4 years", "5 years and over" };
	
	/** The payload the dashboard renders: one entry per declared indicator and section. */
	public static Map<String, Object> compute(ReportLayout layout, Date from, Date toExclusive, Set<Integer> locationIds,
	        ReportFilter filter, Concepts concepts) {
		Map<String, Object> out = new LinkedHashMap<String, Object>();
		List<String> missing = new ArrayList<String>();
		List<Map<String, Object>> indicators = new ArrayList<Map<String, Object>>();
		List<Map<String, Object>> sections = new ArrayList<Map<String, Object>>();
		
		for (ReportLayout.Indicator spec : layout.getIndicators()) {
			if (isEncounterSetKind(spec.getKind())) {
				// Answered from the encounter set itself, so it needs no concept
				// and cannot be lost to a missing data package.
				indicators.add(indicator(spec.getKey(), spec.getLabel(), spec.getKind(),
				    encounterSetValue(spec.getKind(), from, toExclusive, locationIds, filter)));
				continue;
			}
			Integer conceptId = resolve(spec.getConcept(), concepts, missing);
			if (conceptId == null) {
				continue;
			}
			// Every answer the indicator names has to resolve, or the figure would
			// silently be computed over a smaller set than the mapping asked for:
			// "stages 4 to 8" quietly becoming "stages 4 to 7" is a rate that is
			// wrong without anything on the page saying so.
			List<Integer> answerIds = new ArrayList<Integer>();
			for (String answer : spec.getAnswers()) {
				Integer answerId = resolve(answer, concepts, missing);
				if (answerId == null) {
					answerIds = null;
					break;
				}
				answerIds.add(answerId);
			}
			if (answerIds == null) {
				continue;
			}
			indicators.add(indicator(spec.getKey(), spec.getLabel(), spec.getKind(),
			    value(spec, conceptId, answerIds, from, toExclusive, locationIds, filter)));
		}
		
		for (ReportLayout.Section spec : layout.getSections()) {
			List<Map<String, Object>> rows;
			int declared = 0;
			if (ReportLayout.KIND_CONDITION_BREAKDOWN.equals(spec.getKind())) {
				rows = conditions(layout, concepts, missing, from, toExclusive, locationIds, filter, spec.getLimit());
				declared = layout.getConditionConcepts().size();
			} else if (ReportLayout.KIND_AGE_BREAKDOWN.equals(spec.getKind())) {
				rows = ageBands(from, toExclusive, locationIds, filter);
			} else {
				Integer conceptId = resolve(spec.getConcept(), concepts, missing);
				if (conceptId == null) {
					continue;
				}
				rows = codedBreakdown(conceptId, from, toExclusive, locationIds, filter, spec.getLimit(), concepts);
			}
			sections.add(section(spec.getKey(), spec.getLabel(), spec.getKind(), rows, declared));
		}
		
		out.put("indicators", indicators);
		out.put("sections", sections);
		if (!missing.isEmpty()) {
			// Said plainly rather than left as zeros: a register whose concepts
			// this database does not have is a missing data package, not a
			// population with no deliveries.
			out.put("conceptsMissing", missing);
		}
		return out;
	}
	
	/** True for the kinds answered from the encounter set rather than from obs. */
	private static boolean isEncounterSetKind(String kind) {
		return ReportLayout.KIND_ENCOUNTER_COUNT.equals(kind) || ReportLayout.KIND_DISTINCT_PATIENTS.equals(kind)
		        || ReportLayout.KIND_MEAN_AGE_MONTHS.equals(kind);
	}
	
	/**
	 * The figures that need no obs concept.
	 * <p>
	 * Mean age is computed at the encounter rather than today: a register read in the month it was
	 * written has to answer "how old were the children we saw", and a child who has since turned five
	 * was not five when seen. A patient with no recorded birthdate contributes nothing to the mean
	 * rather than counting as zero months, which would read as a newborn.
	 */
	private static double encounterSetValue(String kind, Date from, Date toExclusive, Set<Integer> locationIds,
	        ReportFilter filter) {
		if (ReportLayout.KIND_DISTINCT_PATIENTS.equals(kind)) {
			return count(encounterSql("COUNT(DISTINCT e.patient_id)", false, from, toExclusive, locationIds, filter), from,
			    toExclusive, locationIds, filter);
		}
		if (ReportLayout.KIND_MEAN_AGE_MONTHS.equals(kind)) {
			String sql = encounterSql("AVG(TIMESTAMPDIFF(MONTH, p.birthdate, e.encounter_datetime))", true, from,
			    toExclusive, locationIds, filter);
			List<Object[]> rows = query(sql, from, toExclusive, locationIds, filter);
			if (rows.isEmpty() || rows.get(0)[0] == null) {
				// No encounter had a birthdate, so there is no mean. Zero is a
				// real answer for a count but not for a mean, and the page says
				// as much rather than reporting a mean age of 0 months.
				return Double.NaN;
			}
			return round(((Number) rows.get(0)[0]).doubleValue());
		}
		return count(encounterSql("COUNT(e.encounter_id)", false, from, toExclusive, locationIds, filter), from, toExclusive,
		    locationIds, filter);
	}
	
	/**
	 * The figure for one indicator, by kind.
	 * <p>
	 * A rate divides by the encounters that answered the question, not by all encounters the report
	 * selects: a delivery record that left the field blank is missing data, and scoring it as a "no"
	 * would drag every rate down by however much of the register nobody filled in. That makes the
	 * denominator the number of encounters carrying the concept with any answer.
	 */
	private static double value(ReportLayout.Indicator spec, Integer conceptId, List<Integer> answerIds, Date from,
	        Date toExclusive, Set<Integer> locationIds, ReportFilter filter) {
		if (ReportLayout.KIND_CODED_RATE.equals(spec.getKind())
		        || ReportLayout.KIND_CODED_RATE_ANY_OF.equals(spec.getKind())) {
			long answered = coded(conceptId, null, from, toExclusive, locationIds, filter);
			if (answered == 0) {
				return 0d;
			}
			long hit = codedAnyOf(conceptId, answerIds, from, toExclusive, locationIds, filter);
			long good = spec.isInverted() ? answered - hit : hit;
			// Rounded to a whole percent: the dashboard shows whole numbers, and
			// carrying a fraction that is then truncated would show a different
			// figure from the one the arithmetic produced.
			return Math.round((100.0d * good) / answered);
		}
		if (ReportLayout.KIND_NUMERIC_MEAN.equals(spec.getKind())) {
			return numericMean(conceptId, from, toExclusive, locationIds, filter);
		}
		if (ReportLayout.KIND_NUMERIC.equals(spec.getKind())) {
			return coded(conceptId, null, from, toExclusive, locationIds, filter);
		}
		return codedAnyOf(conceptId, answerIds, from, toExclusive, locationIds, filter);
	}
	
	/**
	 * The mean of the numeric values recorded for a concept, to one decimal place.
	 * <p>
	 * Null when nothing numeric was recorded, rather than zero: a mean birth weight of 0.0kg is a claim
	 * about the babies, and the absence of weights is a claim about the register.
	 */
	private static double numericMean(Integer conceptId, Date from, Date toExclusive, Set<Integer> locationIds,
	        ReportFilter filter) {
		String sql = "SELECT AVG(o.value_numeric) FROM obs o JOIN encounter e ON e.encounter_id = o.encounter_id"
		        + obsWhere(conceptId, null, from, toExclusive, locationIds, filter);
		List<Object[]> rows = query(sql, from, toExclusive, locationIds, filter, conceptId, null);
		if (rows.isEmpty() || rows.get(0)[0] == null) {
			return Double.NaN;
		}
		return round(((Number) rows.get(0)[0]).doubleValue());
	}
	
	/**
	 * Encounters carrying {@code conceptId}, with no restriction on the answer.
	 * <p>
	 * Counted per encounter rather than per obs: a register can record the same concept more than once
	 * on one encounter (a re-entered row, a multi-select split across answers), and a KPI that read
	 * "cases" would double-count the woman.
	 */
	private static long coded(Integer conceptId, Integer answerId, Date from, Date toExclusive, Set<Integer> locationIds,
	        ReportFilter filter) {
		String sql = "SELECT COUNT(DISTINCT o.encounter_id) FROM obs o JOIN encounter e"
		        + " ON e.encounter_id = o.encounter_id"
		        + obsWhere(conceptId, answerId, from, toExclusive, locationIds, filter);
		List<Object[]> rows = query(sql, from, toExclusive, locationIds, filter, conceptId, answerId);
		return rows.isEmpty() || rows.get(0)[0] == null ? 0L : ((Number) rows.get(0)[0]).longValue();
	}
	
	/**
	 * Encounters carrying {@code conceptId} with any one of {@code answerIds}.
	 * <p>
	 * One encounter counts once however many of the answers it carries, so "ANC visit four and also
	 * visit five" is one woman who reached four contacts, not two.
	 */
	private static long codedAnyOf(Integer conceptId, List<Integer> answerIds, Date from, Date toExclusive,
	        Set<Integer> locationIds, ReportFilter filter) {
		if (answerIds == null || answerIds.isEmpty()) {
			return 0L;
		}
		if (answerIds.size() == 1) {
			return coded(conceptId, answerIds.get(0), from, toExclusive, locationIds, filter);
		}
		String sql = "SELECT COUNT(DISTINCT o.encounter_id) FROM obs o JOIN encounter e"
		        + " ON e.encounter_id = o.encounter_id" + obsWhere(conceptId, null, from, toExclusive, locationIds, filter)
		        + " AND o.value_coded IN (:ansIds)";
		Session sess = IndicatorFacts.session();
		try {
			NativeQuery q = sess.createNativeQuery(sql);
			q.setParameter("cid", conceptId);
			q.setParameterList("ansIds", answerIds);
			q.setParameter("from", from);
			q.setParameter("to", toExclusive);
			if (hasLocations(locationIds)) {
				q.setParameterList("locIds", locationIds);
			}
			if (filter != null) {
				filter.bind(sql, q);
			}
			List<?> raw = q.list();
			if (raw.isEmpty()) {
				return 0L;
			}
			Object r = raw.get(0);
			Object first = r instanceof Object[] ? ((Object[]) r)[0] : r;
			return first == null ? 0L : ((Number) first).longValue();
		}
		finally {
			sess.close();
		}
	}
	
	/** The obs half of a statement: the concept, its answer, the period and the scope. */
	private static String obsWhere(Integer conceptId, Integer answerId, Date from, Date toExclusive,
	        Set<Integer> locationIds, ReportFilter filter) {
		StringBuilder sql = new StringBuilder(" WHERE o.voided = 0 AND o.concept_id = :cid");
		if (answerId != null) {
			sql.append(" AND o.value_coded = :ans");
		}
		sql.append(" AND e.voided = 0 AND e.encounter_datetime >= :from AND e.encounter_datetime < :to");
		if (hasLocations(locationIds)) {
			sql.append(" AND e.location_id IN (:locIds)");
		}
		if (filter != null) {
			sql.append(filter.sqlFragment());
		}
		return sql.toString();
	}
	
	/**
	 * The encounter half of a statement, optionally joined to the person.
	 * <p>
	 * Joined to {@code person}, not {@code patient}, because the birthdate an age is read from is a
	 * person attribute; {@code patient} carries only the identifiers linking the person to everything
	 * else.
	 */
	private static String encounterSql(String aggregate, boolean joinPerson, Date from, Date toExclusive,
	        Set<Integer> locationIds, ReportFilter filter) {
		StringBuilder sql = new StringBuilder("SELECT ").append(aggregate).append(" FROM encounter e");
		if (joinPerson) {
			sql.append(" JOIN person p ON p.person_id = e.patient_id");
		}
		sql.append(" WHERE e.voided = 0 AND e.encounter_datetime >= :from AND e.encounter_datetime < :to");
		if (hasLocations(locationIds)) {
			sql.append(" AND e.location_id IN (:locIds)");
		}
		if (filter != null) {
			sql.append(filter.sqlFragment());
		}
		return sql.toString();
	}
	
	/** One bar per coded answer to a concept, most frequent first. */
	private static List<Map<String, Object>> codedBreakdown(Integer conceptId, Date from, Date toExclusive,
	        Set<Integer> locationIds, ReportFilter filter, int limit, Concepts concepts) {
		StringBuilder sql = new StringBuilder("SELECT o.value_coded, COUNT(DISTINCT o.encounter_id) n FROM obs o"
		        + " JOIN encounter e ON e.encounter_id = o.encounter_id"
		        + obsWhere(conceptId, null, from, toExclusive, locationIds, filter) + " AND o.value_coded IS NOT NULL");
		sql.append(" GROUP BY o.value_coded ORDER BY n DESC");
		List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		for (Object[] r : query(sql.toString(), from, toExclusive, locationIds, filter, conceptId, null)) {
			Map<String, Object> row = new LinkedHashMap<String, Object>();
			row.put("label", concepts.conceptName(((Number) r[0]).intValue()));
			row.put("count", ((Number) r[1]).longValue());
			rows.add(row);
		}
		return limit(rows, limit);
	}
	
	/**
	 * One bar per condition the report's layout declares, counted where the answer was affirmative.
	 * <p>
	 * This is the shape a treatment register needs: it has no single question, it has a hundred
	 * possible conditions, and the useful answer is which of them are being seen.
	 * <p>
	 * Condition-only encounters are left out, so the bars do not need to sum to the register's visit
	 * count and a patient recorded with two conditions is counted in both bars - which is what a
	 * morbidity summary means.
	 */
	private static List<Map<String, Object>> conditions(ReportLayout layout, Concepts concepts, List<String> missing,
	        Date from, Date toExclusive, Set<Integer> locationIds, ReportFilter filter, int limit) {
		List<Integer> conceptIds = new ArrayList<Integer>();
		Set<Integer> seen = new LinkedHashSet<Integer>();
		Map<Integer, String> labels = new LinkedHashMap<Integer, String>();
		List<Integer> tickedIds = new ArrayList<Integer>();
		List<Integer> recordedIds = new ArrayList<Integer>();
		List<Integer> answerIds = new ArrayList<Integer>();
		List<Integer> conditionAnswerIds = new ArrayList<Integer>();
		for (ReportLayout.Condition condition : layout.getConditionConcepts()) {
			Integer id = concepts.conceptId(condition.getName());
			// A condition this database does not have contributes no bar, and the
			// caller is told about it: an empty chart and a chart of conditions
			// nobody has are different findings.
			if (id == null) {
				if (!missing.contains(condition.getName())) {
					missing.add(condition.getName());
				}
				continue;
			}
			// The condition is the bar, so the first mapping of a concept decides
			// how it is counted - two kinds for one concept would double it.
			if (!seen.add(id)) {
				continue;
			}
			conceptIds.add(id);
			if (condition.isRecorded()) {
				recordedIds.add(id);
			} else if (condition.isAnswer()) {
				answerIds.add(id);
				for (String name : condition.getAnswers()) {
					Integer answerId = concepts.conceptId(name);
					if (answerId == null) {
						if (!missing.contains(name)) {
							missing.add(name);
						}
					} else {
						conditionAnswerIds.add(answerId);
					}
				}
			} else {
				tickedIds.add(id);
			}
			if (condition.getLabel() != null) {
				labels.put(id, condition.getLabel());
			}
		}
		if (conceptIds.isEmpty()) {
			return new ArrayList<Map<String, Object>>();
		}
		List<Integer> yesIds = yesIds(concepts);
		// A register that only ticks needs the dictionary's affirmative answer
		// concepts; one whose conditions are counts or named answers does not, and
		// on a database with no "Yes" concept at all that is not a reason to
		// return nothing.
		// A condition counts however the register recorded it: as this concept
		// answered yes, as this concept as the answer to the question it belongs
		// to, as one of a named set of answers to its own question, or - where the
		// register writes a number down, as the IMNCI register does for the drugs
		// it counts - as any value above zero. A treatment register that has a
		// checkbox per condition records the first, one that asks "which method?"
		// and offers the methods as answers records the second, one that asks
		// "any disability?" with its own printed marks records the third, and one
		// that asks "how many?" records the fourth. A breakdown that understood
		// only one of them would come back short against real data, with nothing
		// to say why. The condition is selected rather than the concept, so the
		// second shape is labelled with the method that was recorded and not with
		// the question.
		String sql = "SELECT CASE WHEN o.concept_id IN (:cids) THEN o.concept_id ELSE o.value_coded END cid,"
		        + " COUNT(DISTINCT o.encounter_id) n FROM obs o"
		        + " JOIN encounter e ON e.encounter_id = o.encounter_id WHERE o.voided = 0" + " AND e.voided = 0";
		List<String> ors = new ArrayList<String>();
		if (!tickedIds.isEmpty() && !yesIds.isEmpty()) {
			ors.add("o.concept_id IN (:ticked) AND o.value_coded IN (:yesIds)");
		}
		if (!tickedIds.isEmpty()) {
			// Only a tick box can be recorded as the answer to the question it
			// belongs to; a numeric concept never is.
			ors.add("o.value_coded IN (:ticked)");
		}
		if (!recordedIds.isEmpty()) {
			ors.add("o.concept_id IN (:recorded) AND o.value_numeric > 0");
		}
		if (!answerIds.isEmpty() && !conditionAnswerIds.isEmpty()) {
			ors.add("o.concept_id IN (:answerIds) AND o.value_coded IN (:conditionAnswerIds)");
		}
		if (ors.isEmpty()) {
			return new ArrayList<Map<String, Object>>();
		}
		StringBuilder where = new StringBuilder();
		for (String or : ors) {
			where.append(where.length() == 0 ? " AND (" : " OR ");
			where.append(or);
		}
		where.append(')');
		sql += where;
		sql += " AND e.encounter_datetime >= :from AND e.encounter_datetime < :to";
		if (hasLocations(locationIds)) {
			sql += " AND e.location_id IN (:locIds)";
		}
		if (filter != null) {
			sql += filter.sqlFragment();
		}
		sql += " GROUP BY cid ORDER BY n DESC";
		
		Session sess = IndicatorFacts.session();
		try {
			NativeQuery q = sess.createNativeQuery(sql);
			q.setParameterList("cids", conceptIds);
			q.setParameterList("ticked", tickedIds);
			q.setParameterList("recorded", recordedIds);
			q.setParameterList("answerIds", answerIds);
			q.setParameterList("conditionAnswerIds", conditionAnswerIds);
			q.setParameterList("yesIds", yesIds);
			q.setParameter("from", from);
			q.setParameter("to", toExclusive);
			if (hasLocations(locationIds)) {
				q.setParameterList("locIds", locationIds);
			}
			if (filter != null) {
				filter.bind(sql, q);
			}
			List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
			for (Object r : q.list()) {
				Object[] row = r instanceof Object[] ? (Object[]) r : new Object[] { r };
				int id = ((Number) row[0]).intValue();
				Map<String, Object> m = new LinkedHashMap<String, Object>();
				String label = labels.get(id);
				m.put("label", label != null ? label : concepts.conceptName(id));
				m.put("count", ((Number) row[1]).longValue());
				rows.add(m);
			}
			return limit(rows, limit);
		}
		finally {
			sess.close();
		}
	}
	
	/**
	 * A bar per under-five age band.
	 * <p>
	 * Every band is emitted, including the empty ones, because a register with no children in the 3-5
	 * month band should show that band as zero rather than leaving the reader to infer it from a gap.
	 * <p>
	 * Banded in days, not months. The neonatal band is 0-28 days, and a child of 29 days already reads
	 * as a month old under {@code TIMESTAMPDIFF(MONTH)}, which would file them as a newborn.
	 * <p>
	 * The bands are selected in SQL rather than bucketed from an age per row, because a child seen
	 * twice in the period can cross a band boundary between the two visits, and counting each visit
	 * would let the chart total more children than the "children seen" figure beside it. The bounds
	 * come from {@link #AGE_BAND_MAX_DAYS} so the query and the labels cannot drift apart.
	 */
	private static List<Map<String, Object>> ageBands(Date from, Date toExclusive, Set<Integer> locationIds,
	        ReportFilter filter) {
		long[] counts = new long[AGE_BAND_MAX_DAYS.length];
		String ageInDays = "TIMESTAMPDIFF(DAY, p.birthdate, e.encounter_datetime)";
		StringBuilder sql = new StringBuilder("SELECT CASE");
		for (int i = 0; i < AGE_BAND_MAX_DAYS.length; i++) {
			if (i == AGE_BAND_MAX_DAYS.length - 1) {
				sql.append(" ELSE ").append(i);
			} else {
				sql.append(" WHEN ").append(ageInDays).append(" <= ").append(AGE_BAND_MAX_DAYS[i]).append(" THEN ")
				        .append(i);
			}
		}
		sql.append(" END band, COUNT(DISTINCT e.patient_id) n FROM encounter e"
		        + " JOIN person p ON p.person_id = e.patient_id WHERE e.voided = 0 AND p.birthdate IS NOT NULL"
		        + " AND e.encounter_datetime >= :from AND e.encounter_datetime < :to");
		if (hasLocations(locationIds)) {
			sql.append(" AND e.location_id IN (:locIds)");
		}
		if (filter != null) {
			sql.append(filter.sqlFragment());
		}
		// A birthdate after the encounter is a data-entry error. Excluded rather
		// than filed under "0-28 days", where it would read as a real newborn.
		sql.append(" AND ").append(ageInDays).append(" >= 0 GROUP BY band");
		for (Object[] r : query(sql.toString(), from, toExclusive, locationIds, filter)) {
			counts[((Number) r[0]).intValue()] += ((Number) r[1]).longValue();
		}
		List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		for (int i = 0; i < AGE_BAND_MAX_DAYS.length; i++) {
			Map<String, Object> m = new LinkedHashMap<String, Object>();
			m.put("label", AGE_BAND_LABELS[i]);
			m.put("count", Long.valueOf(counts[i]));
			rows.add(m);
		}
		return rows;
	}
	
	private static List<Map<String, Object>> limit(List<Map<String, Object>> rows, int limit) {
		if (limit <= 0 || rows.size() <= limit) {
			return rows;
		}
		return new ArrayList<Map<String, Object>>(rows.subList(0, limit));
	}
	
	private static Map<String, Object> indicator(String key, String label, String kind, double value) {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		m.put("key", key);
		m.put("label", label);
		m.put("kind", kind);
		// null is JSON's "no value" here: it is what a mean over zero recorded
		// values is, and the dashboard renders it as the same dash it uses before
		// any data has loaded. A 0 would read as a real figure.
		m.put("value", Double.isNaN(value) ? (Object) null : (Object) Double.valueOf(value));
		return m;
	}
	
	private static Map<String, Object> section(String key, String label, String kind, List<Map<String, Object>> rows,
	        int declared) {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		m.put("key", key);
		m.put("label", label);
		m.put("kind", kind);
		m.put("rows", rows);
		if (declared > 0) {
			// How many conditions the layout declares, against how many came back
			// with data. Without it a 15-bar chart looks like the register has
			// fifteen conditions, which is the cap, not the register.
			m.put("declaredConditions", Integer.valueOf(declared));
		}
		return m;
	}
	
	private static Integer resolve(String name, Concepts concepts, List<String> missing) {
		if (name == null) {
			return null;
		}
		Integer id = concepts.conceptId(name);
		if (id == null && !missing.contains(name)) {
			missing.add(name);
		}
		return id;
	}
	
	private static long count(String sql, Date from, Date toExclusive, Set<Integer> locationIds, ReportFilter filter) {
		List<Object[]> rows = query(sql, from, toExclusive, locationIds, filter);
		return rows.isEmpty() || rows.get(0)[0] == null ? 0L : ((Number) rows.get(0)[0]).longValue();
	}
	
	private static double round(double v) {
		return Math.round(v * 10.0d) / 10.0d;
	}
	
	private static boolean hasLocations(Set<Integer> locationIds) {
		return locationIds != null && !locationIds.isEmpty();
	}
	
	private static List<Object[]> query(String sql, Date from, Date toExclusive, Set<Integer> locationIds,
	        ReportFilter filter) {
		return query(sql, from, toExclusive, locationIds, filter, null, null);
	}
	
	/**
	 * Runs {@code sql}, binding the period, scope, filter and - for the obs statements - the concept
	 * and its answer.
	 * <p>
	 * The obs parameters are bound here rather than at each call site because they are carried by the
	 * SQL that {@link #obsWhere} built: a statement mentioning {@code :ans} without binding it fails at
	 * execution, and one binding a parameter the SQL does not mention fails just as hard.
	 */
	private static List<Object[]> query(String sql, Date from, Date toExclusive, Set<Integer> locationIds,
	        ReportFilter filter, Integer conceptId, Integer answerId) {
		Session sess = IndicatorFacts.session();
		try {
			NativeQuery q = sess.createNativeQuery(sql);
			q.setParameter("from", from);
			q.setParameter("to", toExclusive);
			if (hasLocations(locationIds)) {
				q.setParameterList("locIds", locationIds);
			}
			if (conceptId != null) {
				q.setParameter("cid", conceptId);
			}
			if (answerId != null) {
				q.setParameter("ans", answerId);
			}
			if (filter != null) {
				filter.bind(sql, q);
			}
			List<?> raw = q.list();
			List<Object[]> out = new ArrayList<Object[]>(raw.size());
			for (Object r : raw) {
				// A single-column aggregate comes back as the scalar itself, not
				// as a one-element array, so normalise before indexing.
				out.add(r instanceof Object[] ? (Object[]) r : new Object[] { r });
			}
			return out;
		}
		finally {
			sess.close();
		}
	}
	
	/** The affirmative answer concept ids this deployment uses, without repeats. */
	public static List<Integer> yesIds(Concepts concepts) {
		Set<Integer> ids = new LinkedHashSet<Integer>();
		for (String name : YES_ANSWERS) {
			Integer id = concepts.conceptId(name);
			if (id != null) {
				ids.add(id);
			}
		}
		return new ArrayList<Integer>(ids);
	}
}
