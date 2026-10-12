/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao;

import java.util.List;

import org.openmrs.module.clinicreporting.api.model.DataExport;

/**
 * Persists {@link DataExport} definitions as rows of the one table that holds them.
 * <p>
 * The body of an export is stored as its definition JSON - a description of a query, never a
 * statement - and read back through {@code applyDefinition}, which is how a definition saved today,
 * in a version after this one, or by a hand-written row all stay subject to exactly the validation
 * the live form is subject to.
 */
public interface DataExportDao {
	
	/**
	 * @param includeRetired whether to also return exports that have been retired
	 * @return the stored exports, ordered by name
	 */
	List<DataExport> getAll(boolean includeRetired);
	
	/**
	 * @param id the primary key
	 * @return the export, or null when there is none
	 */
	DataExport getById(Integer id);
	
	/**
	 * @param uuid the export's uuid
	 * @return the export, or null when there is none
	 */
	DataExport getByUuid(String uuid);
	
	/**
	 * @param name the export name
	 * @return the active export with that name, or null when there is none
	 */
	DataExport getByName(String name);
	
	/**
	 * @param includeRetired whether to count retired exports too
	 * @return how many exports are stored
	 */
	long count(boolean includeRetired);
	
	/**
	 * Inserts a new export row. The caller must have supplied the uuid, the audit fields
	 * ({@code creator}, {@code dateCreated}) and the definition JSON.
	 *
	 * @param export the export to insert
	 * @return the id it was given
	 */
	int insert(DataExport export);
	
	/**
	 * Writes the editable fields and the audit fields ({@code changedBy}, {@code dateChanged}) of an
	 * existing row. The primary key, the uuid, the creator and the creation date are never rewritten.
	 *
	 * @param export the export whose row is rewritten
	 */
	void update(DataExport export);
}
