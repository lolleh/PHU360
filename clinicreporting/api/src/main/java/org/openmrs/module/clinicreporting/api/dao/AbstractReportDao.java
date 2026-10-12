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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.clinicreporting.api.dto.ContextualDefaults;

/**
 * Shared plumbing for the reporting DAOs.
 * <p>
 * The reports are written as native SQL against the core OpenMRS schema. Three portability rules
 * are enforced here and relied upon throughout:
 * <ul>
 * <li>Booleans are compared against {@code false}/{@code true}, never {@code 0}/{@code 1}, because
 * OpenMRS declares them as {@code BOOLEAN} and PostgreSQL rejects integer comparison.</li>
 * <li>Date arithmetic and date bucketing happen in Java, never in SQL, because truncation functions
 * differ per database. The only SQL date expression used is {@code CAST(x AS DATE)}, which MySQL,
 * MariaDB, PostgreSQL and H2 all support.</li>
 * <li>Every value reaching SQL travels as a bound parameter, including list members, which are
 * expanded to individually named parameters.</li>
 * </ul>
 */
public abstract class AbstractReportDao {
	
	private DbSessionFactory sessionFactory;
	
	/**
	 * Table to column names, filled in one pass. Report queries only ever need to know what the schema
	 * looks like, and that answer does not change while the server is running.
	 */
	private static final Map<String, Set<String>> TABLE_COLUMNS = new ConcurrentHashMap<String, Set<String>>();
	
	private static final Object METADATA_LOCK = new Object();
	
	/** Whether {@link #TABLE_COLUMNS} has been filled from a pass over the database metadata. */
	private static volatile boolean SCHEMA_LOADED = false;
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	protected DbSessionFactory getSessionFactory() {
		return sessionFactory;
	}
	
	protected Session session() {
		return sessionFactory.getHibernateSessionFactory().getCurrentSession();
	}
	
	/**
	 * Maps one row of a native query result onto a report object.
	 *
	 * @param <T> the produced type
	 */
	public interface RowMapper<T> {
		
		T mapRow(Object[] row);
	}
	
	// ---------------------------------------------------------------- parameters
	
	/**
	 * Builds an {@code IN (...)} fragment, binding each element as its own named parameter. Hibernate's
	 * native-query list binding is avoided deliberately so behaviour is identical on every supported
	 * database.
	 *
	 * @param prefix parameter name prefix, unique within {@code params}
	 * @param values the values to bind
	 * @param params receives the bound parameters
	 * @return the SQL fragment, e.g. {@code (:loc0,:loc1)}
	 */
	protected static String inClause(String prefix, Collection<?> values, Map<String, Object> params) {
		if (values == null || values.isEmpty()) {
			// An empty IN list must still produce valid SQL. "1 = 0" is false and cannot be
			// optimised away into a match-all.
			return "(1 = 0)";
		}
		StringBuilder sql = new StringBuilder("(");
		int index = 0;
		for (Object value : values) {
			if (index > 0) {
				sql.append(',');
			}
			String name = prefix + index++;
			sql.append(':').append(name);
			params.put(name, value);
		}
		return sql.append(')').toString();
	}
	
	protected static void putIfNotNull(Map<String, Object> params, String name, Object value) {
		if (value != null) {
			params.put(name, value);
		}
	}
	
	// ---------------------------------------------------------------- concept names
	
	/**
	 * SQL expression resolving the display name of a concept.
	 * <p>
	 * A concept holds one name per locale, and a deployment routinely ships a dozen or more. The name
	 * therefore has to be chosen deliberately: the name in the user's locale first, then English, then
	 * the concept's short name. Taking {@code MIN(name)} across all locales - the tempting shortcut -
	 * resolves the <em>language</em> alphabetically instead, and labels a pulse rate with its Haitian
	 * Creole name on a Sierra Leone server that also carries French, Spanish and English.
	 * <p>
	 * This is a correlated subquery rather than a window function because MySQL 5.7, which supported
	 * deployments still run, has none. {@code LIMIT 1} is understood by every database the module
	 * supports, including H2 and PostgreSQL.
	 *
	 * @param concept the alias of the concept table, or of a derived table carrying {@code concept_id}
	 * @return an expression yielding the display name
	 */
	protected static String conceptName(String concept) {
		return "COALESCE((SELECT cn.name FROM concept_name cn WHERE cn.concept_id = " + concept
		        + ".concept_id AND cn.voided = false ORDER BY CASE WHEN cn.locale = :nameLocale THEN 0 "
		        + "WHEN cn.locale = '" + ContextualDefaults.DEFAULT_LOCALE + "' THEN 1 ELSE 2 END, "
		        + "cn.locale_preferred DESC, cn.concept_name_id ASC LIMIT 1), " + concept + ".short_name)";
	}
	
	/**
	 * Binds the locale {@link #conceptName(String)} resolves names in. Every query that selects a
	 * concept name has to call this, or Hibernate will fail on the unbound {@code :nameLocale}.
	 */
	protected void bindConceptLocale(Map<String, Object> params) {
		putIfNotNull(params, "nameLocale", ContextualDefaults.preferredLocale());
	}
	
	// ---------------------------------------------------------------- execution
	
	/**
	 * Runs a native query and maps every row.
	 *
	 * @param sql the native SQL
	 * @param params bound parameters
	 * @param mapper row mapper
	 * @param <T> the produced type
	 * @return the mapped rows, never null
	 */
	protected <T> List<T> listRows(String sql, Map<String, Object> params, RowMapper<T> mapper) {
		return mapQuery(sql, params).list().stream().map(AbstractReportDao::toArray).map(mapper::mapRow)
		        .collect(java.util.stream.Collectors.toList());
	}
	
	/**
	 * Runs a native query and maps a single aggregate row.
	 *
	 * @param sql the native SQL
	 * @param params bound parameters
	 * @param mapper row mapper
	 * @param <T> the produced type
	 * @return the mapped row, or null when the query returned nothing
	 */
	protected <T> T singleRow(String sql, Map<String, Object> params, RowMapper<T> mapper) {
		List<?> rows = mapQuery(sql, params).list();
		if (rows.isEmpty()) {
			return null;
		}
		return mapper.mapRow(toArray(rows.get(0)));
	}
	
	/**
	 * Binds parameters and paging onto a native query.
	 */
	protected Query bind(Query<?> query, Map<String, Object> params) {
		if (params != null) {
			for (Map.Entry<String, Object> entry : params.entrySet()) {
				query.setParameter(entry.getKey(), entry.getValue());
			}
		}
		return query;
	}
	
	/**
	 * Applies zero-based offset and row limit. Hibernate renders dialect-appropriate LIMIT/OFFSET,
	 * which keeps this portable across MySQL, PostgreSQL and H2.
	 */
	protected List<Object[]> paged(Query<?> query, int offset, int size) {
		query.setFirstResult(Math.max(0, offset));
		if (size > 0) {
			query.setMaxResults(size);
		}
		// Native queries return a bare Object for a single column, so every row is normalised
		// to Object[] here rather than being trusted to the caller.
		List<Object[]> rows = new ArrayList<>();
		for (Object row : query.list()) {
			rows.add(toArray(row));
		}
		return rows;
	}
	
	@SuppressWarnings("unchecked")
	protected Query<?> mapQuery(String sql, Map<String, Object> params) {
		Query<?> query = session().createNativeQuery(sql);
		return bind(query, params);
	}
	
	/**
	 * @return the single scalar result of a count query, as a long
	 */
	protected long count(String sql, Map<String, Object> params) {
		List<?> results = mapQuery(sql, params).list();
		if (results.isEmpty()) {
			return 0L;
		}
		return asLong(toArray(results.get(0))[0]);
	}
	
	/**
	 * Normalises a scalar result to an {@code Object[]}. A native query returns a bare {@code Object}
	 * for a single column and an {@code Object[]} for several, and the shape also varies with the
	 * driver, so callers should never have to care.
	 */
	protected static Object[] toArray(Object row) {
		if (row instanceof Object[]) {
			return (Object[]) row;
		}
		return new Object[] { row };
	}
	
	// ---------------------------------------------------------------- value coercion
	
	protected static long asLong(Object value) {
		if (value == null) {
			return 0L;
		}
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		String text = value.toString().trim();
		return text.isEmpty() ? 0L : (long) Double.parseDouble(text);
	}
	
	protected static Integer asInteger(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : Integer.valueOf((int) Double.parseDouble(text));
	}
	
	protected static Double asDouble(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : Double.valueOf(text);
	}
	
	protected static String asString(Object value) {
		return value == null ? null : value.toString();
	}
	
	/**
	 * Coerces a temporal column to {@link Date}. Drivers return {@code java.sql.Date},
	 * {@code Timestamp} or (on some Postgres paths) a string, so all three are handled.
	 */
	protected static Date asDate(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Date) {
			return new Date(((Date) value).getTime());
		}
		if (value instanceof Number) {
			return new Date(((Number) value).longValue());
		}
		String text = value.toString().trim();
		if (text.isEmpty()) {
			return null;
		}
		// Postgres renders a bare "date" column as YYYY-MM-DD.
		if (text.length() == 10 && text.charAt(4) == '-') {
			return java.sql.Date.valueOf(text);
		}
		throw new IllegalArgumentException("Unrecognised date value: " + text);
	}
	
	// ---------------------------------------------------------------- schema introspection
	
	/**
	 * @param table the table name, matched case-insensitively
	 * @return true when the table is present in this server's database
	 */
	protected boolean tableExists(String table) {
		return columnsOf(table) != null;
	}
	
	/**
	 * @param table the table name
	 * @param column the column name
	 * @return true when both exist
	 */
	protected boolean columnExists(String table, String column) {
		Set<String> columns = columnsOf(table);
		return columns != null && columns.contains(column.toLowerCase());
	}
	
	/**
	 * Reads the column names of a table from the cached schema. Matching is case-insensitive because
	 * PostgreSQL folds unquoted identifiers to lower case while MySQL preserves them.
	 * <p>
	 * The whole schema is read into the cache on first use rather than a table at a time, because the
	 * JDBC metadata call that lists columns cannot cheaply be narrowed to one table: asking for a
	 * single table scans every table anyway. Reading it per table would repeat that scan once for each
	 * of the tables a page happens to mention.
	 *
	 * @param table the table name
	 * @return lower-cased column names, or null when the table does not exist
	 */
	protected Set<String> columnsOf(String table) {
		if (table == null) {
			return null;
		}
		loadSchemaIfNeeded();
		return TABLE_COLUMNS.get(table.toLowerCase());
	}
	
	/**
	 * Drops the cached schema metadata. Only needed by tests and by a server that reloads its schema
	 * underneath a running module, which OpenMRS does not do.
	 */
	public static void clearMetadataCache() {
		synchronized (METADATA_LOCK) {
			TABLE_COLUMNS.clear();
			SCHEMA_LOADED = false;
		}
	}
	
	private void loadSchemaIfNeeded() {
		if (SCHEMA_LOADED) {
			return;
		}
		synchronized (METADATA_LOCK) {
			// A concurrent first caller may have loaded it while this one waited for the lock.
			if (SCHEMA_LOADED) {
				return;
			}
			readSchema();
			SCHEMA_LOADED = true;
		}
	}
	
	/**
	 * Reads every table's columns in a single pass. The metadata call is given no schema filter so the
	 * lookup works whatever schema the deployment happens to use.
	 */
	private void readSchema() {
		Map<String, Set<String>> schema = session().doReturningWork(connection -> {
			Map<String, Set<String>> found = new LinkedHashMap<String, Set<String>>();
			ResultSet rs = null;
			try {
				rs = connection.getMetaData().getColumns(null, null, null, null);
				while (rs.next()) {
					String table = rs.getString("TABLE_NAME");
					String column = rs.getString("COLUMN_NAME");
					if (table == null || column == null) {
						continue;
					}
					String key = table.toLowerCase();
					Set<String> columns = found.get(key);
					if (columns == null) {
						columns = new LinkedHashSet<String>();
						found.put(key, columns);
					}
					columns.add(column.toLowerCase());
				}
			}
			catch (SQLException e) {
				throw new IllegalStateException("Unable to read database metadata", e);
			}
			finally {
				closeQuietly(rs);
			}
			return found;
		});
		TABLE_COLUMNS.putAll(schema);
	}
	
	private static void closeQuietly(ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			}
			catch (SQLException ignored) {
				// nothing useful to do while closing metadata
			}
		}
	}
	
	// ---------------------------------------------------------------- ages
	
	/**
	 * Age bands used by every breakdown that reports one.
	 * <p>
	 * Held here rather than in the DAO that happens to need them so that the patients register and the
	 * programme dashboards band ages identically. A dashboard whose "under 5s" did not mean what the
	 * register's "under 5s" meant would be worse than having no band at all.
	 */
	protected static final String[] AGE_BANDS = { "<1y", "1-4y", "5-14y", "15-49y", "50+y", "Unknown" };
	
	/**
	 * @param birthdate the recorded birthdate, or null
	 * @param reference the date the age is measured at
	 * @return whole months between the two, or null when the birthdate is unknown or in the future
	 */
	protected static Integer monthsBetween(Date birthdate, Date reference) {
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
		return months < 0 ? null : Integer.valueOf(months);
	}
	
	/**
	 * @param ageInMonths an age in months, or null for an unrecorded birthdate
	 * @return the band that age falls in, never null
	 */
	protected static String ageBand(Integer ageInMonths) {
		if (ageInMonths == null) {
			return "Unknown";
		}
		if (ageInMonths < 12) {
			return "<1y";
		}
		if (ageInMonths < 60) {
			return "1-4y";
		}
		if (ageInMonths < 180) {
			return "5-14y";
		}
		if (ageInMonths < 600) {
			return "15-49y";
		}
		return "50+y";
	}
	
	// ---------------------------------------------------------------- locations
	
	/**
	 * Expands a location into itself plus every descendant, so that selecting a district includes the
	 * facilities underneath it. Recursive SQL is not portable across the supported databases, and the
	 * location table is small enough to walk in memory.
	 *
	 * @param locationId the selected location
	 * @return the location id and the ids of all its descendants
	 */
	protected Set<Integer> locationWithDescendants(Integer locationId) {
		List<Object[]> rows = listRows("SELECT location_id, parent_location FROM location WHERE retired = false", null,
		    row -> row);
		
		Map<Integer, List<Integer>> childrenByParent = new LinkedHashMap<>();
		for (Object[] row : rows) {
			Integer id = asInteger(row[0]);
			Integer parent = asInteger(row[1]);
			if (id != null) {
				List<Integer> children = childrenByParent.get(parent);
				if (children == null) {
					children = new ArrayList<>();
					childrenByParent.put(parent, children);
				}
				children.add(id);
			}
		}
		
		Set<Integer> result = new LinkedHashSet<>();
		List<Integer> pending = new ArrayList<>();
		pending.add(locationId);
		while (!pending.isEmpty()) {
			Integer current = pending.remove(pending.size() - 1);
			if (current == null || !result.add(current)) {
				continue;
			}
			List<Integer> children = childrenByParent.get(current);
			if (children != null) {
				pending.addAll(children);
			}
		}
		return result;
	}
	
	protected static Set<Integer> emptyIdSet() {
		return Collections.emptySet();
	}
}
