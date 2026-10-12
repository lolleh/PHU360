/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.transform;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The set of tables and columns the indicator sandbox is allowed to read.
 * <p>
 * This class is the security boundary for the sandbox, so it is an allowlist written out in full
 * rather than a blocklist of things to avoid. A table that is not named here does not exist as far
 * as the sandbox is concerned, which means a table added to the server by a future module, by an
 * upgrade, or by a DBA is unreachable until somebody deliberately adds it.
 * <p>
 * What is deliberately absent:
 * <ul>
 * <li>Account and credential tables: {@code users}, {@code user_password}, {@code user_role},
 * {@code user_property}, {@code login_failure_count}, {@code authentication_event_log}. These are
 * withheld entirely rather than trimmed to safe columns, because joining {@code users.user_id} to
 * {@code person.person_id} re-identifies the clinician behind a patient's record.</li>
 * <li>Direct identifiers: {@code person_name}, {@code person_address}, {@code patient_identifier},
 * and {@code provider.name} and {@code provider.identifier}. A report can legitimately need to know
 * a patient's age band or sex, and reach those through {@code person} plus {@code patient}; it has
 * no business reading names or addresses, and an indicator built on a clinician's name is a staff
 * directory rather than a report.</li>
 * <li>Free-text columns: {@code obs.comments}, {@code note.text},
 * {@code patient_appointment.comments}, {@code patient_appointment.tele_health_video_link} and
 * {@code queue_entry.priority_comment}. Text typed by a clinician about a patient is exactly where
 * a name or an address ends up that the tables above were withheld to hide, and no indicator is
 * built on a comment.</li>
 * <li>Everything not named below, including bed management, legacy EMR module and other tables this
 * distribution carries unused.</li>
 * </ul>
 * Note that this protects against <em>reading</em> identity and account data. It does not stop an
 * administrator from counting rows in {@code patient} or joining {@code person} to {@code obs};
 * that is inherent to reporting on clinical data and is why the sandbox is behind its own
 * privilege.
 * <p>
 * The column lists are written against the schema this module was developed on, which is not
 * schema-identical to every OpenMRS distribution: some carry {@code obs.value_text} where others
 * carry {@code obs_value}. Rather than let the picker offer a column the database does not have,
 * {@link Source#narrowed} intersects each list with what the server actually reports, which can
 * only remove columns and so can never widen the sandbox.
 *
 * @see RawQueryGuard for the equivalent rules applied to the raw SQL escape hatch
 */
public final class ReportSourceRegistry {
	
	/**
	 * An allowlisted table: a machine name to select and query, a label for the picker, and the subset
	 * of columns exposed. A column absent from {@link #columns} cannot be selected even though the
	 * database has it, which is how a single sensitive column is held back from an otherwise useful
	 * table.
	 */
	public static final class Source {
		
		private final String table;
		
		private final String label;
		
		private final String category;
		
		private final Set<String> columns;
		
		Source(String table, String label, String category, String columns) {
			this(table, label, category, parse(columns));
		}
		
		private Source(String table, String label, String category, Set<String> parsed) {
			this.table = table;
			this.label = label;
			this.category = category;
			this.columns = Collections.unmodifiableSet(parsed);
		}
		
		private static Set<String> parse(String columns) {
			Set<String> parsed = new LinkedHashSet<String>();
			for (String column : columns.split(",")) {
				String trimmed = column.trim();
				if (!trimmed.isEmpty()) {
					parsed.add(trimmed);
				}
			}
			return parsed;
		}
		
		public String getTable() {
			return table;
		}
		
		public String getLabel() {
			return label;
		}
		
		public String getCategory() {
			return category;
		}
		
		public Set<String> getColumns() {
			return columns;
		}
		
		/**
		 * @param column a column name exactly as the caller received it
		 * @return true when the sandbox may select this column from this table
		 */
		public boolean allows(String column) {
			return column != null && columns.contains(column);
		}
		
		/**
		 * Narrows this source to the columns a database actually has.
		 * <p>
		 * The allowlist is written against one schema, and OpenMRS core tables are not identical across
		 * versions and distributions: {@code obs} exposes {@code value_text} in some and {@code
		 * obs_value} in others. Rather than let the picker offer a column that would fail, the service
		 * intersects the two lists. Narrowing can only remove what the database does not have, so it can
		 * never widen what the sandbox may read.
		 *
		 * @param available the lower-cased column names this database reports, or null when the table does
		 *            not exist here
		 * @return the narrowed source, or null when the table is absent
		 */
		public Source narrowed(Set<String> available) {
			if (available == null || available.isEmpty()) {
				return null;
			}
			Set<String> availableLower = new LinkedHashSet<String>();
			for (String c : available) {
				if (c != null) {
					availableLower.add(c.toLowerCase());
				}
			}
			Set<String> kept = new LinkedHashSet<String>();
			for (String column : columns) {
				if (availableLower.contains(column.toLowerCase())) {
					kept.add(column);
				}
			}
			if (kept.isEmpty()) {
				return null;
			}
			return new Source(table, label, category, kept);
		}
		
		@Override
		public String toString() {
			return table;
		}
	}
	
	private static final Map<String, Source> SOURCES;
	
	static {
		Map<String, Source> sources = new LinkedHashMap<String, Source>();
		
		// -- Encounters and visits -------------------------------------------------
		add(sources, "encounter", "Encounter", "Encounters",
		    "encounter_id,encounter_type,patient_id,location_id,form_id,encounter_datetime,visit_id,"
		            + "creator,date_created,changed_by,date_changed,voided,voided_by,date_voided," + "void_reason");
		add(sources, "encounter_type", "Encounter type", "Encounters",
		    "encounter_type_id,name,description,creator,date_created,retired,uuid");
		add(sources, "visit", "Visit", "Encounters",
		    "visit_id,patient_id,visit_type_id,date_started,date_stopped,indication_concept_id,"
		            + "location_id,creator,date_created,changed_by,date_changed,voided,voided_by,"
		            + "date_voided,void_reason");
		add(sources, "visit_type", "Visit type", "Encounters",
		    "visit_type_id,name,description,creator,date_created,retired,uuid");
		add(sources, "location", "Location", "Encounters",
		    "location_id,name,description,county_district,address1,address2,city_village,"
		            + "state_province,postal_code,country,latitude,longitude,parent_location,"
		            + "creator,date_created,retired");
		add(sources, "care_setting", "Care setting", "Encounters",
		    "care_setting_id,name,description,care_setting_type,creator,date_created,retired");
		add(sources, "encounter_role", "Encounter role", "Encounters",
		    "encounter_role_id,name,description,creator,date_created,changed_by,date_changed,retired,"
		            + "retired_by,date_retired,retire_reason,uuid");
		add(sources, "form", "Form", "Encounters",
		    "form_id,name,version,build,published,description,encounter_type,creator,date_created,"
		            + "changed_by,date_changed,retired,retired_by,date_retired,retired_reason,uuid");
		add(sources, "form_resource", "Form resource", "Encounters",
		    "form_resource_id,form_id,name,value_reference,datatype,datatype_config,preferred_handler,"
		            + "handler_config,uuid,date_changed,changed_by");
		add(sources, "visit_attribute", "Visit attribute", "Encounters",
		    "visit_attribute_id,visit_id,attribute_type_id,value_reference,uuid,creator,date_created,"
		            + "changed_by,date_changed,voided,voided_by,date_voided,void_reason");
		add(sources, "visit_attribute_type", "Visit attribute type", "Encounters",
		    "visit_attribute_type_id,name,description,datatype,datatype_config,preferred_handler,"
		            + "handler_config,min_occurs,max_occurs,creator,date_created,changed_by,date_changed,"
		            + "retired,retired_by,date_retired,retire_reason,uuid");
		
		// -- Facilities and locations ------------------------------------------------
		add(sources, "location_attribute", "Location attribute", "Facilities and locations",
		    "location_attribute_id,location_id,attribute_type_id,value_reference,uuid,creator,date_created,"
		            + "changed_by,date_changed,voided,voided_by,date_voided,void_reason");
		add(sources, "location_attribute_type", "Location attribute type", "Facilities and locations",
		    "location_attribute_type_id,name,description,datatype,datatype_config,preferred_handler,"
		            + "handler_config,min_occurs,max_occurs,creator,date_created,changed_by,date_changed,"
		            + "retired,retired_by,date_retired,retire_reason,uuid");
		add(sources, "location_tag", "Location tag", "Facilities and locations",
		    "location_tag_id,name,description,creator,date_created,changed_by,date_changed,retired,"
		            + "retired_by,date_retired,retire_reason,uuid");
		add(sources, "location_tag_map", "Location tag assignment", "Facilities and locations",
		    "location_id,location_tag_id");
		add(sources, "address_hierarchy_entry", "Location hierarchy entry", "Facilities and locations",
		    "address_hierarchy_entry_id,name,level_id,parent_id,user_generated_id,latitude,longitude," + "elevation,uuid");
		add(sources, "address_hierarchy_level", "Location hierarchy level", "Facilities and locations",
		    "address_hierarchy_level_id,name,parent_level_id,address_field,uuid,required");
		
		// -- Observations, diagnoses and conditions ---------------------------------
		// obs.comments is withheld: free text is where a clinician's own note about a patient ends up
		// carrying a name or an address that person_name and person_address were withheld to hide.
		add(sources, "obs", "Observation", "Clinical data",
		    "obs_id,person_id,concept_id,encounter_id,order_id,obs_datetime,location_id,"
		            + "accession_number,value_group_id,value_coded,value_coded_name_id,value_drug,"
		            + "value_datetime,value_numeric,value_modifier,value_text,value_complex,"
		            + "creator,date_created,voided,voided_by,date_voided,void_reason,previous_version,"
		            + "form_namespace_and_path,status,interpretation");
		add(sources, "obs_reference_range", "Observation reference range", "Clinical data",
		    "obs_reference_range_id,obs_id,hi_normal,low_normal");
		add(sources, "conditions", "Condition", "Clinical data",
		    "condition_id,patient_id,encounter_id,condition_coded,condition_non_coded,"
		            + "condition_coded_name,clinical_status,verification_status,onset_date,end_date,"
		            + "additional_detail,creator,date_created,changed_by,date_changed,voided,voided_by,"
		            + "date_voided,void_reason,previous_version");
		add(sources, "encounter_diagnosis", "Encounter diagnosis", "Clinical data",
		    "diagnosis_id,encounter_id,patient_id,condition_id,diagnosis_coded,diagnosis_non_coded,"
		            + "diagnosis_coded_name,certainty,dx_rank,creator,date_created,changed_by,"
		            + "date_changed,voided,voided_by,date_voided,void_reason");
		add(sources, "allergy", "Allergy", "Clinical data",
		    "allergy_id,patient_id,encounter_id,severity_concept_id,coded_allergen,"
		            + "non_coded_allergen,allergen_type,creator,date_created,voided,uuid");
		// note.text is withheld for the same reason as obs.comments.
		add(sources, "note", "Clinical note (metadata only)", "Clinical data",
		    "note_id,note_type,patient_id,obs_id,encounter_id,priority,parent,creator,date_created," + "uuid");
		
		// -- Patients (demographics only) ------------------------------------------
		add(sources, "patient", "Patient", "Patients",
		    "patient_id,creator,date_created,changed_by,date_changed,voided,voided_by,date_voided," + "void_reason");
		add(sources, "person", "Person demographics", "Patients",
		    "person_id,gender,birthdate,birthdate_estimated,birthtime,dead,death_date,"
		            + "deathdate_estimated,cause_of_death,cause_of_death_non_coded,creator,"
		            + "date_created,changed_by,date_changed,voided,voided_by,date_voided,void_reason");
		// patient_identifier_type and person_attribute_type are metadata about the identifier and
		// attribute sets the server holds, not the values themselves: the identifier tables and the
		// attribute values that carry a phone number or next of kin stay withheld.
		add(sources, "patient_identifier_type", "Patient identifier type", "Patients",
		    "patient_identifier_type_id,name,description,format,check_digit,required,format_description,"
		            + "validator,location_behavior,uniqueness_behavior,creator,date_created,"
		            + "changed_by,date_changed,retired,retired_by,date_retired,retire_reason,uuid");
		add(sources, "person_attribute_type", "Person attribute type", "Patients",
		    "person_attribute_type_id,name,description,format,foreign_key,searchable,edit_privilege,"
		            + "sort_weight,creator,date_created,changed_by,date_changed,retired,retired_by,"
		            + "date_retired,retire_reason,uuid");
		add(sources, "relationship", "Relationship", "Patients",
		    "relationship_id,person_a,relationship,person_b,start_date,end_date,creator,date_created,"
		            + "date_changed,changed_by,voided,voided_by,date_voided,void_reason,uuid");
		add(sources, "relationship_type", "Relationship type", "Patients",
		    "relationship_type_id,a_is_to_b,b_is_to_a,preferred,weight,description,creator,date_created,"
		            + "retired,retired_by,date_retired,retire_reason,uuid,date_changed,changed_by");
		
		// -- Cohorts -----------------------------------------------------------------
		add(sources, "cohort", "Cohort", "Cohorts",
		    "cohort_id,name,description,creator,date_created,changed_by,date_changed,voided,"
		            + "voided_by,date_voided,void_reason,uuid");
		add(sources, "cohort_member", "Cohort member", "Cohorts",
		    "cohort_id,patient_id,cohort_member_id,start_date,end_date,creator,date_created,voided,"
		            + "voided_by,date_voided,void_reason,uuid");
		
		// -- Orders, prescriptions and laboratory ----------------------------------
		add(sources, "orders", "Order", "Orders and laboratory",
		    "order_id,order_type_id,patient_id,encounter_id,concept_id,orderer,instructions,"
		            + "date_activated,auto_expire_date,date_stopped,scheduled_date,order_reason,"
		            + "order_action,comment_to_fulfiller,order_number,previous_order_id,urgency,"
		            + "accession_number,care_setting,order_group_id,creator,date_created,voided,"
		            + "voided_by,date_voided,void_reason,uuid");
		add(sources, "order_type", "Order type", "Orders and laboratory",
		    "order_type_id,name,description,java_class_name,creator,date_created,retired,uuid");
		add(sources, "order_frequency", "Order frequency", "Orders and laboratory",
		    "order_frequency_id,concept_id,frequency_per_day,creator,date_created,retired,uuid");
		add(sources, "test_order", "Laboratory order (result fields)", "Orders and laboratory",
		    "order_id,specimen_source,laterality,frequency,number_of_repeats,location," + "clinical_history");
		add(sources, "drug_order", "Drug order", "Orders and laboratory",
		    "order_id,drug_inventory_id,dose,dose_units,quantity,quantity_units,num_refills,"
		            + "duration,duration_units,route,frequency,brand_name,dispense_as_written,"
		            + "as_needed,drug_non_coded");
		add(sources, "drug", "Drug", "Orders and laboratory",
		    "drug_id,concept_id,name,combination,dosage_form,route,strength,maximum_daily_dose,"
		            + "retired,creator,date_created,changed_by,date_changed");
		add(sources, "drug_ingredient", "Drug ingredient", "Orders and laboratory", "drug_id,ingredient_id,strength,units");
		add(sources, "drug_reference_map", "Drug reference map", "Orders and laboratory",
		    "drug_reference_map_id,drug_id,term_id,concept_map_type,creator,date_created,changed_by,"
		            + "date_changed,retired,retired_by,date_retired,retire_reason,uuid");
		add(sources, "medication_dispense", "Dispensed medication", "Orders and laboratory",
		    "medication_dispense_id,patient_id,encounter_id,concept,drug_id,drug_order_id,location_id,"
		            + "status,status_reason,type,quantity,quantity_units,dose,dose_units,route,"
		            + "frequency,as_needed,was_substituted,substitution_type,date_prepared,"
		            + "date_handed_over,creator,date_created,voided,uuid");
		add(sources, "order_group", "Order group", "Orders and laboratory",
		    "order_group_id,order_set_id,patient_id,encounter_id,order_group_reason,parent_order_group,"
		            + "previous_order_group,creator,date_created,changed_by,date_changed,voided,"
		            + "voided_by,date_voided,void_reason,uuid");
		add(sources, "order_set", "Order set", "Orders and laboratory",
		    "order_set_id,operator,name,description,category,creator,date_created,changed_by,"
		            + "date_changed,retired,retired_by,date_retired,retire_reason,uuid");
		add(sources, "order_set_member", "Order set member", "Orders and laboratory",
		    "order_set_member_id,order_set_id,concept_id,order_type,order_template,order_template_type,"
		            + "sequence_number,creator,date_created,changed_by,date_changed,retired,retired_by,"
		            + "date_retired,retire_reason,uuid");
		
		// -- Providers --------------------------------------------------------------
		// provider.name and provider.identifier are withheld: they are the staff member's own name and
		// registration number, and an indicator built on them becomes a staff directory.
		add(sources, "provider", "Provider (identifier withheld)", "Providers",
		    "provider_id,person_id,provider_role_id,retired,creator,date_created");
		add(sources, "provider_role", "Provider role", "Providers",
		    "provider_role_id,name,description,creator,date_created,retired");
		add(sources, "encounter_provider", "Encounter provider", "Providers",
		    "encounter_provider_id,encounter_id,provider_id,encounter_role_id,creator,date_created," + "voided,uuid");
		
		// -- Reference data ---------------------------------------------------------
		add(sources, "concept", "Concept", "Reference data",
		    "concept_id,retired,short_name,description,form_text,datatype_id,class_id,is_set,"
		            + "version,creator,date_created,changed_by,date_changed,date_retired," + "retire_reason,uuid");
		add(sources, "concept_name", "Concept name", "Reference data",
		    "concept_name_id,concept_id,name,locale,locale_preferred,concept_name_type,creator,"
		            + "date_created,voided,uuid");
		add(sources, "concept_description", "Concept description", "Reference data",
		    "concept_description_id,concept_id,description,locale,creator,date_created,changed_by," + "date_changed");
		add(sources, "concept_answer", "Concept answer", "Reference data",
		    "concept_answer_id,concept_id,answer_concept,answer_drug,sort_weight,creator," + "date_created");
		add(sources, "concept_numeric", "Concept numeric", "Reference data",
		    "concept_id,hi_normal,low_normal,units,allow_decimal,display_precision");
		add(sources, "concept_set", "Concept set membership", "Reference data",
		    "concept_set_id,concept_id,concept_set,sort_weight,creator,date_created");
		add(sources, "concept_class", "Concept class", "Reference data",
		    "concept_class_id,name,description,creator,date_created,retired");
		add(sources, "concept_datatype", "Concept datatype", "Reference data",
		    "concept_datatype_id,name,hl7_abbreviation,description,creator,date_created,retired");
		add(sources, "concept_reference_range", "Concept reference range", "Reference data",
		    "concept_reference_range_id,concept_id,hi_normal,low_normal");
		add(sources, "concept_reference_source", "Concept reference source", "Reference data",
		    "concept_source_id,name,description,hl7_code,creator,date_created,changed_by,date_changed,"
		            + "retired,retired_by,date_retired,retire_reason,uuid,unique_id");
		add(sources, "concept_reference_term", "Concept reference term", "Reference data",
		    "concept_reference_term_id,concept_source_id,name,code,version,description,creator,"
		            + "date_created,date_changed,changed_by,retired,retired_by,date_retired," + "retire_reason,uuid");
		add(sources, "concept_reference_map", "Concept reference map", "Reference data",
		    "concept_map_id,concept_reference_term_id,concept_map_type_id,concept_id,creator,date_created,"
		            + "changed_by,date_changed,uuid");
		add(sources, "concept_map_type", "Concept map type", "Reference data",
		    "concept_map_type_id,name,description,is_hidden,creator,date_created,changed_by,date_changed,"
		            + "retired,retired_by,date_retired,retire_reason,uuid");
		add(sources, "idgen_identifier_source", "Identifier source", "Reference data",
		    "id,name,description,identifier_type,creator,date_created,changed_by,date_changed,retired,"
		            + "retired_by,date_retired,retire_reason,uuid");
		
		// -- Programs ---------------------------------------------------------------
		add(sources, "program", "Program", "Programs",
		    "program_id,concept_id,name,description,creator,date_created,retired,uuid");
		add(sources, "program_workflow", "Program workflow", "Programs",
		    "program_workflow_id,program_id,concept_id,creator,date_created,retired,uuid");
		add(sources, "program_workflow_state", "Program workflow state", "Programs",
		    "program_workflow_state_id,program_workflow_id,concept_id,initial,terminal,creator,"
		            + "date_created,retired,uuid");
		add(sources, "patient_program", "Patient program enrolment", "Programs",
		    "patient_program_id,patient_id,program_id,location_id,date_enrolled,date_completed,"
		            + "outcome_concept_id,creator,date_created,changed_by,date_changed,voided,"
		            + "voided_by,date_voided,void_reason,uuid");
		add(sources, "patient_state", "Patient program state", "Programs",
		    "patient_state_id,patient_program_id,state,start_date,end_date,form_namespace_and_path,"
		            + "encounter_id,creator,date_created,changed_by,date_changed,voided,voided_by,"
		            + "date_voided,void_reason,uuid");
		add(sources, "program_attribute_type", "Program attribute type", "Programs",
		    "program_attribute_type_id,name,description,datatype,datatype_config,preferred_handler,"
		            + "handler_config,min_occurs,max_occurs,creator,date_created,changed_by,date_changed,"
		            + "retired,retired_by,date_retired,retire_reason,uuid");
		add(sources, "patient_program_attribute", "Patient program attribute", "Programs",
		    "patient_program_attribute_id,patient_program_id,attribute_type_id,value_reference,uuid,"
		            + "creator,date_created,changed_by,date_changed,voided,voided_by,date_voided," + "void_reason");
		
		// -- Appointments ------------------------------------------------------------
		// patient_appointment.comments and tele_health_video_link are withheld: free text about an
		// appointment is where a name or a phone number ends up.
		add(sources, "appointment_service", "Appointment service", "Appointments",
		    "appointment_service_id,name,description,start_time,end_time,location_id,speciality_id,"
		            + "max_appointments_limit,duration_mins,color,initial_appointment_status,date_created,"
		            + "creator,date_changed,changed_by,voided,voided_by,date_voided,void_reason,uuid");
		add(sources, "appointment_service_type", "Appointment service type", "Appointments",
		    "appointment_service_type_id,appointment_service_id,name,duration_mins,date_created,creator,"
		            + "date_changed,changed_by,voided,voided_by,date_voided,void_reason,uuid");
		add(sources, "appointment_speciality", "Appointment speciality", "Appointments",
		    "speciality_id,name,date_created,creator,date_changed,changed_by,voided,uuid");
		add(sources, "patient_appointment", "Patient appointment", "Appointments",
		    "patient_appointment_id,provider_id,appointment_number,patient_id,start_date_time,"
		            + "end_date_time,appointment_service_id,appointment_service_type_id,status,"
		            + "location_id,appointment_kind,related_appointment_id,priority,"
		            + "date_appointment_scheduled,date_created,creator,date_changed,changed_by,voided,"
		            + "voided_by,date_voided,void_reason,uuid");
		
		// -- Queues ------------------------------------------------------------------
		// queue_entry.priority_comment is free text and is withheld like the other comment columns.
		add(sources, "queue", "Queue", "Queues",
		    "queue_id,name,description,location_id,service,priority_concept_set,status_concept_set,"
		            + "creator,date_created,changed_by,date_changed,retired,retired_by,date_retired,"
		            + "retire_reason,uuid");
		add(sources, "queue_entry", "Queue entry", "Queues",
		    "queue_entry_id,queue_id,patient_id,priority,status,sort_weight,location_waiting_for,"
		            + "provider_waiting_for,started_at,ended_at,queue_coming_from,visit_id,creator,"
		            + "date_created,changed_by,date_changed,voided,voided_by,date_voided,void_reason,uuid");
		
		// -- Note on user tables ---------------------------------------------------
		// users and user_role are not listed, not even partially. A sandbox that could read
		// users.user_id could join it to person.person_id and from there to any patient's obs,
		// which re-identifies the clinician behind a diagnosis even though person_name is
		// withheld. Provenance is still auditable: obs, encounter and orders all carry creator and
		// date_created themselves.
		
		SOURCES = Collections.unmodifiableMap(sources);
	}
	
	private ReportSourceRegistry() {
	}
	
	private static void add(Map<String, Source> sources, String table, String label, String category, String columns) {
		sources.put(table, new Source(table, label, category, columns));
	}
	
	/**
	 * @return every allowlisted source, in the order they should be offered
	 */
	public static Map<String, Source> all() {
		return SOURCES;
	}
	
	/**
	 * @param table a table name exactly as the caller received it
	 * @return the allowlisted source, or null when the table is not available to the sandbox. Callers
	 *         must treat null as "refuse"; there is no permissive fallback.
	 */
	public static Source find(String table) {
		if (table == null) {
			return null;
		}
		return SOURCES.get(table);
	}
	
	/**
	 * @param table an allowlisted table
	 * @param column a column name
	 * @return true when the sandbox may select this column from this table
	 */
	public static boolean allowsColumn(String table, String column) {
		Source source = find(table);
		return source != null && source.allows(column);
	}
	
	/**
	 * Guards against a table name being used to smuggle SQL. Every identifier the sandbox emits is
	 * checked with this before it reaches a statement.
	 *
	 * @param identifier a candidate table or column name
	 * @return true when the name is a plain unquoted SQL identifier
	 */
	public static boolean isPlainIdentifier(String identifier) {
		if (identifier == null || identifier.isEmpty() || identifier.length() > 64) {
			return false;
		}
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
			if (!ok) {
				return false;
			}
		}
		// A leading digit is not a legal unquoted identifier.
		char first = identifier.charAt(0);
		return !Character.isDigit(first);
	}
}
