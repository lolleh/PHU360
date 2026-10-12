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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.openmrs.module.clinicreporting.api.dao.AbstractReportDao;
import org.openmrs.module.clinicreporting.api.dao.DataExportDao;
import org.openmrs.module.clinicreporting.api.model.DataExport;

/**
 * Default {@link DataExportDao}.
 * <p>
 * Reads run through Hibernate native queries and the {@link AbstractReportDao} helpers, so boolean
 * and timestamp columns bind portably across the databases the module supports. Inserts and updates
 * go through plain JDBC on the same session: they touch one row, they need a generated key on
 * insert, and doing them in Hibernate would mean guessing a column count over a list that has to be
 * sparse - every nullable column only appears when a value exists.
 * <p>
 * Following the module's portability rule, booleans are written and compared as {@code false}/
 * {@code true} in SQL text and bound as booleans, never as {@code 0}/{@code 1}.
 */
public class HibernateDataExportDao extends AbstractReportDao implements DataExportDao {
	
	private static final String SELECT = "SELECT data_export_id, uuid, name, description, source_table, definition,"
	        + " row_limit, creator, date_created, changed_by, date_changed, retired, date_retired, retired_by,"
	        + " retire_reason FROM clinicreporting_data_export";
	
	private static final String SELECT_BY_ID = SELECT + " WHERE data_export_id = :dataExportId";
	
	private static final String SELECT_BY_UUID = SELECT + " WHERE uuid = :uuid";
	
	private static final String SELECT_BY_NAME = SELECT + " WHERE name = :name AND retired = false";
	
	private static final String INSERT = "INSERT INTO clinicreporting_data_export (uuid, name, description,"
	        + " source_table, definition, row_limit, creator, date_created, changed_by, date_changed, retired,"
	        + " date_retired, retired_by, retire_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
	
	private static final String UPDATE = "UPDATE clinicreporting_data_export SET name = ?, description = ?,"
	        + " source_table = ?, definition = ?, row_limit = ?, changed_by = ?, date_changed = ?, retired = ?,"
	        + " date_retired = ?, retired_by = ?, retire_reason = ? WHERE data_export_id = ?";
	
	private static final String SELECT_ID_BY_UUID = "SELECT data_export_id FROM clinicreporting_data_export WHERE uuid = ?";
	
	@Override
	public List<DataExport> getAll(boolean includeRetired) {
		StringBuilder sql = new StringBuilder(SELECT);
		if (!includeRetired) {
			sql.append(" WHERE retired = false");
		}
		sql.append(" ORDER BY retired, name");
		return listRows(sql.toString(), null, this::map);
	}
	
	@Override
	public DataExport getById(Integer id) {
		if (id == null) {
			return null;
		}
		java.util.Map<String, Object> params = new java.util.LinkedHashMap<String, Object>();
		params.put("dataExportId", id);
		return singleRow(SELECT_BY_ID, params, this::map);
	}
	
	@Override
	public DataExport getByUuid(String uuid) {
		if (uuid == null || uuid.trim().isEmpty()) {
			return null;
		}
		java.util.Map<String, Object> params = new java.util.LinkedHashMap<String, Object>();
		params.put("uuid", uuid.trim());
		return singleRow(SELECT_BY_UUID, params, this::map);
	}
	
	@Override
	public DataExport getByName(String name) {
		if (name == null || name.trim().isEmpty()) {
			return null;
		}
		java.util.Map<String, Object> params = new java.util.LinkedHashMap<String, Object>();
		params.put("name", name.trim());
		return singleRow(SELECT_BY_NAME, params, this::map);
	}
	
	@Override
	public long count(boolean includeRetired) {
		String sql = includeRetired ? "SELECT COUNT(*) FROM clinicreporting_data_export"
		        : "SELECT COUNT(*) FROM clinicreporting_data_export WHERE retired = false";
		return count(sql, null);
	}
	
	@Override
	public int insert(DataExport export) {
		return session().doReturningWork(connection -> {
			PreparedStatement statement = null;
			try {
				statement = connection.prepareStatement(INSERT);
				statement.setString(1, export.getUuid());
				statement.setString(2, export.getName());
				statement.setObject(3, export.getDescription());
				statement.setString(4, export.getSourceTable());
				statement.setObject(5, export.definitionJson());
				statement.setObject(6,
				    export.getRowLimit() == null ? Integer.valueOf(DataExport.DEFAULT_ROW_LIMIT) : export.getRowLimit());
				statement.setObject(7, export.getCreator());
				statement.setObject(8, timestamp(export.getDateCreated()));
				statement.setObject(9, export.getChangedBy());
				statement.setObject(10, timestamp(export.getDateChanged()));
				statement.setObject(11, Boolean.valueOf(export.isRetired()));
				statement.setObject(12, timestamp(export.getDateRetired()));
				statement.setObject(13, export.getRetiredBy());
				statement.setObject(14, export.getRetireReason());
				statement.executeUpdate();
			}
			finally {
				closeQuietly(statement);
			}
			// A re-select by uuid is portable where getGeneratedKeys is not (some drivers only return
			// generated keys for identity columns, and the row is in front of us anyway).
			try (PreparedStatement lookup = connection.prepareStatement(SELECT_ID_BY_UUID)) {
				lookup.setString(1, export.getUuid());
				try (ResultSet rs = lookup.executeQuery()) {
					if (rs.next()) {
						return Integer.valueOf(rs.getInt(1));
					}
					throw new IllegalStateException("Inserted export row could not be read back.");
				}
			}
		});
	}
	
	@Override
	public void update(DataExport export) {
		if (export.getId() == null) {
			throw new IllegalArgumentException("Cannot update an export that has not been stored.");
		}
		session().doReturningWork(connection -> {
			PreparedStatement statement = null;
			try {
				statement = connection.prepareStatement(UPDATE);
				statement.setString(1, export.getName());
				statement.setObject(2, export.getDescription());
				statement.setString(3, export.getSourceTable());
				statement.setObject(4, export.definitionJson());
				statement.setObject(5,
				    export.getRowLimit() == null ? Integer.valueOf(DataExport.DEFAULT_ROW_LIMIT) : export.getRowLimit());
				statement.setObject(6, export.getChangedBy());
				statement.setObject(7, timestamp(export.getDateChanged()));
				statement.setObject(8, Boolean.valueOf(export.isRetired()));
				statement.setObject(9, timestamp(export.getDateRetired()));
				statement.setObject(10, export.getRetiredBy());
				statement.setObject(11, export.getRetireReason());
				statement.setObject(12, export.getId());
				statement.executeUpdate();
			}
			finally {
				closeQuietly(statement);
			}
			return null;
		});
	}
	
	// ---------------------------------------------------------------- row mapping
	
	private DataExport map(Object[] row) {
		DataExport export = new DataExport();
		export.setId(asInteger(row[0]));
		export.setUuid(asString(row[1]));
		export.setName(asString(row[2]));
		export.setDescription(asString(row[3]));
		export.setSourceTable(asString(row[4]));
		applyDefinitionQuietly(export, asString(row[5]));
		export.setRowLimit(asInteger(row[6]));
		export.setCreator(asInteger(row[7]));
		export.setDateCreated(asDate(row[8]));
		export.setChangedBy(asInteger(row[9]));
		export.setDateChanged(asDate(row[10]));
		export.setRetired(Boolean.TRUE.equals(row[11]));
		export.setDateRetired(asDate(row[12]));
		export.setRetiredBy(asInteger(row[13]));
		export.setRetireReason(asString(row[14]));
		return export;
	}
	
	/**
	 * A stored definition has to load even when a future version changed its shape. A document that
	 * cannot be parsed is worse than useless - it would be silently ignored and export an empty set of
	 * columns - so it is refused loudly here, naming the row.
	 */
	private void applyDefinitionQuietly(DataExport export, String json) {
		try {
			export.applyDefinition(json);
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("Export \"" + export.getName() + "\" holds an unreadable definition.", e);
		}
	}
	
	/**
	 * @return the value to bind to a timestamp column, or null
	 */
	private static java.sql.Timestamp timestamp(Date date) {
		return date == null ? null : new java.sql.Timestamp(date.getTime());
	}
	
	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		}
		catch (SQLException e) {
			// nothing useful to do while closing
		}
		catch (Exception e) {
			// nothing useful to do while closing
		}
	}
}
