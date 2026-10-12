/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.clinicreporting.api.transform.TransformFilter;
import org.openmrs.module.clinicreporting.api.transform.TransformFilter.Operator;

/**
 * The data exports a fresh install starts with, so the page is useful before anyone has built
 * anything: one entry per name on the reports list this workspace replaces.
 * <p>
 * Each definition reads one table and takes a column subset from it. There are no joins, because
 * the sandbox is a single-table sandbox, and a definition that joined two tables would be a
 * definition that could be pointed at a withheld one. Two exports may therefore read the same table
 * with different columns and different row conditions - that is what makes them different reports
 * rather than two names for the same dump.
 * <p>
 * Three of the names on that list describe user, role and login data. Those tables are deliberately
 * absent from the allowlist, so they are {@link #defaults() declared} here for completeness and
 * then skipped by the seeder with a log line, rather than quietly given a source they must never
 * have.
 */
public final class DataExportDefaults {
	
	private static final Log LOG = LogFactory.getLog(DataExportDefaults.class);
	
	/**
	 * The global property written once the defaults have been offered. A marker rather than a row
	 * count, so deleting every export is respected instead of being undone on the next page load.
	 */
	public static final String SEEDED_PROPERTY = "clinicreporting.exports.seeded";
	
	private DataExportDefaults() {
	}
	
	/**
	 * Every export this module ships, including the three that have no source to read from.
	 *
	 * @return the definitions, in the order they should appear
	 */
	public static List<DataExport> defaults() {
		List<DataExport> defaults = new ArrayList<DataExport>();
		
		// -- Patients, visits and encounters --------------------------------------
		defaults.add(export("Registrations",
		    "Patients registered on this server: one row per registration with who created it, when, and whether it has been voided.",
		    "patient", "patient_id,creator,date_created,changed_by,date_changed,voided,voided_by,date_voided,void_reason",
		    hideVoided()));
		
		defaults.add(export("Check-In Data",
		    "Visits opened at check-in, with their start and stop times and the location they were opened at.", "visit",
		    "visit_id,patient_id,visit_type_id,date_started,date_stopped,location_id,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("Outpatient Data",
		    "Every recorded encounter: type, patient, form, place and time. Narrow it with an encounter type or location filter once you know this server's ids.",
		    "encounter",
		    "encounter_id,encounter_type,patient_id,location_id,form_id,encounter_datetime,visit_id,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("Hospitalizations Data",
		    "Visits with their admission and discharge times, indication and location. Set date_stopped to is blank to list patients still admitted.",
		    "visit",
		    "visit_id,patient_id,visit_type_id,date_started,date_stopped,indication_concept_id,location_id,creator,date_created,changed_by,date_changed,voided,void_reason",
		    hideVoided()));
		
		defaults.add(export("Delivery Form",
		    "Encounters submitted through a form rather than typed directly. Set form_id to the delivery form's id to keep only deliveries.",
		    "encounter",
		    "encounter_id,patient_id,encounter_type,form_id,encounter_datetime,location_id,visit_id,creator,date_created,voided",
		    new TransformFilter("form_id", Operator.IS_NOT_NULL, null)));
		
		defaults.add(export("Surgery Data",
		    "Procedure and surgery encounters. Narrow to the surgical encounter type or form once you know this server's ids.",
		    "encounter",
		    "encounter_id,patient_id,encounter_type,form_id,encounter_datetime,location_id,visit_id,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("All Patients Export",
		    "Demographics for every person on this server: sex, birthdate, death. Identifier values are held in a separate table and are not part of this export.",
		    "person",
		    "person_id,gender,birthdate,birthdate_estimated,dead,death_date,cause_of_death,creator,date_created,changed_by,date_changed,voided",
		    hideVoided()));
		
		// -- Clinical rows ---------------------------------------------------------
		defaults.add(export("Diagnoses Data",
		    "Diagnoses ascribed to encounters, coded or spelled out, with their certainty and rank.", "encounter_diagnosis",
		    "diagnosis_id,encounter_id,patient_id,condition_id,diagnosis_coded,diagnosis_non_coded,diagnosis_coded_name,certainty,dx_rank,creator,date_created,changed_by,date_changed,voided",
		    hideVoided()));
		
		defaults.add(export("Vitals Data",
		    "Numeric and dated observation values with their concept and timestamp. Filter by concept to keep one vital.",
		    "obs",
		    "obs_id,person_id,encounter_id,concept_id,obs_datetime,value_numeric,value_modifier,value_datetime,location_id,status,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("Lab Results Export",
		    "Laboratory observations, coded or numeric, with the order they came from. Result comments live in free text and are not part of this export.",
		    "obs",
		    "obs_id,person_id,encounter_id,order_id,concept_id,obs_datetime,value_numeric,value_coded,value_datetime,value_modifier,status,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("Lab Orders",
		    "Orders taken on this server, one row per order. Set order_type_id to the laboratory order type to keep only lab work.",
		    "orders",
		    "order_id,order_type_id,patient_id,encounter_id,concept_id,date_activated,order_action,urgency,order_number,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("Allergies Data", "Recorded allergies, the allergen that was identified, its severity and type.",
		    "allergy",
		    "allergy_id,patient_id,encounter_id,severity_concept_id,coded_allergen,non_coded_allergen,allergen_type,creator,date_created,voided,uuid",
		    hideVoided()));
		
		defaults.add(export("Triage Notes",
		    "Clinical notes as metadata: which note, on whom, its type and priority. The text of a note is withheld, so this lists notes rather than their content.",
		    "note", "note_id,note_type,patient_id,obs_id,encounter_id,priority,parent,creator,date_created,uuid"));
		
		// -- Programs and cohorts --------------------------------------------------
		defaults.add(export("NCD Data",
		    "Conditions recorded on this server with their clinical and verification status, onset and end dates.",
		    "conditions",
		    "condition_id,patient_id,encounter_id,condition_coded,condition_non_coded,condition_coded_name,clinical_status,verification_status,onset_date,end_date,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("NCD Patient Data",
		    "Programme enrolments, one row per patient enrolled. Set program_id to the NCD programme to keep only its patients.",
		    "patient_program",
		    "patient_program_id,patient_id,program_id,location_id,date_enrolled,date_completed,outcome_concept_id,creator,date_created,changed_by,date_changed,voided,void_reason",
		    hideVoided()));
		
		defaults.add(export("NCD Encounter Data",
		    "Contacts recorded at the clinic, one row per encounter. Filter by encounter type or location once you know this server's ids.",
		    "encounter",
		    "encounter_id,patient_id,encounter_type,encounter_datetime,location_id,form_id,visit_id,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("NCD Program Data (SL)",
		    "Programme definitions on this server, including the programme an NCD programme is configured as. Use its program_id in the patient exports.",
		    "program", "program_id,concept_id,name,description,creator,date_created,retired,uuid", hideRetired()));
		
		defaults.add(export("Mental Health Data",
		    "Screening scores and symptom observations, numeric or coded, with the encounter they were taken at.", "obs",
		    "obs_id,person_id,encounter_id,concept_id,obs_datetime,value_numeric,value_coded,value_modifier,status,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("Mental Health Program Data",
		    "Enrolments in a mental health programme, with the dates enrolled and completed and the recorded outcome.",
		    "patient_program",
		    "patient_program_id,patient_id,program_id,location_id,date_enrolled,date_completed,outcome_concept_id,creator,date_created,changed_by,date_changed,voided,void_reason",
		    hideVoided()));
		
		defaults.add(export("Mental Health Case Registration",
		    "Programme states, one row per state a case has been in, with the dates it started and ended.", "patient_state",
		    "patient_state_id,patient_program_id,state,start_date,end_date,encounter_id,creator,date_created,voided,voided_by,date_voided,uuid",
		    hideVoided()));
		
		// -- Handovers --------------------------------------------------------------
		defaults.add(export("Morning Maternal Handoff",
		    "Visits still open at handover time, that is those with no discharge time recorded. Add a location or visit type filter for the maternity ward.",
		    "visit",
		    "visit_id,patient_id,visit_type_id,date_started,date_stopped,location_id,indication_concept_id,creator,date_created,voided",
		    new TransformFilter("date_stopped", Operator.IS_NULL, null)));
		
		defaults.add(export("Evening Maternal Handoff",
		    "Encounters recorded during the working day, for handing over at the change of shift.", "encounter",
		    "encounter_id,patient_id,encounter_type,encounter_datetime,location_id,form_id,creator,date_created,voided",
		    hideVoided()));
		
		defaults.add(export("NICU Discharge List",
		    "Discharged visits, that is those with a discharge time recorded. Set the visit type or location to keep the NICU.",
		    "visit", "visit_id,patient_id,visit_type_id,date_started,date_stopped,location_id,creator,date_created,voided",
		    new TransformFilter("date_stopped", Operator.IS_NOT_NULL, null)));
		
		// -- Names on the list with no source to read from -------------------------
		defaults.add(unavailable("Users Data", "users",
		    "the user table is withheld: a user row joins to a person, and from there to any patient"));
		defaults.add(unavailable("User Roles", "user_role", "role assignments are withheld with the user table"));
		defaults.add(unavailable("User Logins", "authentication_event_log",
		    "login records are withheld with the user table; audit through obs, encounter and orders provenance instead"));
		
		return defaults;
	}
	
	/**
	 * @return only the definitions that name a source this server is actually allowed to read
	 */
	public static List<DataExport> seedable() {
		List<DataExport> seedable = new ArrayList<DataExport>();
		for (DataExport export : defaults()) {
			if (export.source() != null) {
				seedable.add(export);
			} else {
				LOG.info("Not seeding data export \"" + export.getName() + "\": " + export.getSourceTable()
				        + " is not on the allowlist (" + export.getDescription() + ").");
			}
		}
		return seedable;
	}
	
	/**
	 * The definitions on the shipped list that have no source to read from, in shipped order.
	 * <p>
	 * They are never seeded and never offered to run, but the Data Exports page lists them by name
	 * alongside the runnable exports so the workspace still advertises every export a fresh install
	 * ships, rather than dropping the three names silently.
	 *
	 * @return the shipped definitions whose table the allowlist refuses
	 */
	public static List<DataExport> unavailable() {
		List<DataExport> unavailable = new ArrayList<DataExport>();
		for (DataExport export : defaults()) {
			if (export.source() == null) {
				unavailable.add(export);
			}
		}
		return unavailable;
	}
	
	private static DataExport export(String name, String description, String table, String columns,
	        TransformFilter... filters) {
		DataExport export = new DataExport();
		export.setName(name);
		export.setDescription(description);
		export.setSourceTable(table);
		export.setColumns(Arrays.asList(columns.split(",")));
		export.setFilters(new ArrayList<TransformFilter>(Arrays.asList(filters)));
		return export;
	}
	
	/**
	 * A definition for a table the allowlist refuses. It is never seeded; it exists so the list of
	 * names the workspace ships with stays complete, and so the reason each one is missing is written
	 * down next to the name rather than left to someone rediscovering it.
	 */
	private static DataExport unavailable(String name, String table, String reason) {
		DataExport export = new DataExport();
		export.setName(name);
		export.setSourceTable(table);
		export.setDescription("Not available: " + reason + ".");
		return export;
	}
	
	/**
	 * Keeps voided rows out. Voided means "the person who recorded this has withdrawn it", which is
	 * almost never what a report wants to count, and it is one filter a reader can remove to see them.
	 */
	private static TransformFilter hideVoided() {
		return new TransformFilter("voided", Operator.EQ, "false");
	}
	
	/**
	 * Keeps retired metadata out, for the same reason on the tables that retire rather than void.
	 */
	private static TransformFilter hideRetired() {
		return new TransformFilter("retired", Operator.EQ, "false");
	}
	
	/**
	 * @param definition a default definition
	 * @return a copy the caller may edit without affecting the shipped one
	 */
	public static DataExport copyOf(DataExport definition) {
		DataExport copy = new DataExport();
		copy.setName(definition.getName());
		copy.setDescription(definition.getDescription());
		copy.setSourceTable(definition.getSourceTable());
		copy.setRowLimit(definition.getRowLimit());
		copy.setColumns(new ArrayList<String>(definition.getColumns()));
		copy.setSteps(new ArrayList<org.openmrs.module.clinicreporting.api.transform.TransformStep>(definition.getSteps()));
		copy.setFilters(new ArrayList<TransformFilter>(definition.getFilters()));
		return copy;
	}
	
	/**
	 * @param name an export name as it appears on the reports list
	 * @return the definition shipped for that name, or null when this module ships none
	 */
	public static DataExport named(String name) {
		if (StringUtils.isBlank(name)) {
			return null;
		}
		for (DataExport definition : defaults()) {
			if (name.trim().equalsIgnoreCase(definition.getName())) {
				return definition;
			}
		}
		return null;
	}
}
