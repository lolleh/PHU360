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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an analyst-written SQL statement may run in the indicator sandbox.
 * <p>
 * This is a deliberately small, readable allowlist rather than a general SQL parser. It is not
 * trying to understand the query; it is trying to establish that the statement only reads, only
 * touches one row set, and cannot be used to reach a second statement or a system schema. A parser
 * would be both larger and easier to get wrong, and the caller has already restricted who can get
 * this far by requiring the {@code Run Raw Report SQL} privilege.
 * <p>
 * The rules, in order:
 * <ol>
 * <li>Comments are refused outright. They are the standard way to hide a second statement from a
 * keyword scan, and an analyst writing a readable query does not need them here.</li>
 * <li>Exactly one statement. A single trailing semicolon is tolerated; any other semicolon is
 * not.</li>
 * <li>Must begin with {@code SELECT} or {@code WITH}, so a {@code WITH} CTE cannot smuggle a
 * leading data-modifying clause.</li>
 * <li>No data-modifying, session-changing or file-reading keyword anywhere.</li>
 * <li>No reference to a system schema, so {@code information_schema}, {@code mysql} and friends
 * cannot be used to read privilege tables or credentials.</li>
 * <li>No locking read, which would hold a row lock for the life of the request.</li>
 * </ol>
 * Anything the caller gets past this is still wrapped by {@link #wrap} so that the row cap cannot
 * be removed, and still executed inside a read-only transaction.
 *
 * @see ReportSourceRegistry for the declarative path, which does not need any of this
 */
public final class RawQueryGuard {
	
	/**
	 * Keywords that change data, change session state, read the filesystem, or reach into the server's
	 * own metadata. Matched as whole words so that a column called {@code updates} or a table called
	 * {@code create_date} is not caught by accident.
	 */
	private static final Set<String> FORBIDDEN = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
	    // writing data
	    "insert", "update", "delete", "replace", "merge", "upsert", "truncate", "rename",
	    // schema and definitions
	    "drop", "alter", "create", "grant", "revoke", "comment",
	    // transactions, locks and session state
	    "commit", "rollback", "savepoint", "lock", "unlock", "begin", "start", "set", "reset", "use", "call", "do",
	    "handler", "prepare", "execute", "deallocate", "signal", "resignal",
	    // reading files or the server
	    "load", "outfile", "dumpfile", "sleep", "benchmark", "shutdown",
	    // metadata and other schemas
	    "information_schema", "mysql", "performance_schema", "sys", "pg_catalog", "dual")));
	
	/** Multi-word phrases that must not appear, checked before the single-word scan. */
	private static final Pattern FORBIDDEN_PHRASES = Pattern
	        .compile("\\b(?:for\\s+update|lock\\s+in\\s+share\\s+mode|into\\s+outfile|into\\s+dumpfile"
	                + "|load\\s+data\\s+infile|load_file\\s*\\(|group_concat\\s*\\(|benchmark\\s*\\("
	                + "|sleep\\s*\\(|union\\s*\\(\\s*select)",
	            Pattern.CASE_INSENSITIVE);
	
	private static final Pattern SELECT_START = Pattern.compile("^\\s*(?:select|with)\\b", Pattern.CASE_INSENSITIVE);
	
	private static final Pattern COMMENT = Pattern.compile("/\\*|\\*/|--|#");
	
	private static final Pattern WORD = Pattern.compile("[a-z_][a-z0-9_]*");
	
	/** Upper bound on statement length, so a pathological paste cannot reach the driver. */
	public static final int MAX_LENGTH = 20000;
	
	private RawQueryGuard() {
	}
	
	/**
	 * @param sql the analyst's statement
	 * @return null when the statement may run, otherwise a message safe to show the analyst
	 */
	public static String reject(String sql) {
		if (sql == null || sql.trim().isEmpty()) {
			return "Enter a SELECT statement.";
		}
		if (sql.length() > MAX_LENGTH) {
			return "Statement is too long; the limit is " + MAX_LENGTH + " characters.";
		}
		if (COMMENT.matcher(sql).find()) {
			return "Comments are not allowed. Remove --, # and /* */ and try again.";
		}
		String statement = stripTrailingSemicolon(sql);
		if (statement.indexOf(';') >= 0) {
			return "Only one statement may be run at a time.";
		}
		if (!SELECT_START.matcher(statement).find()) {
			return "A query must start with SELECT or WITH.";
		}
		String reason = FORBIDDEN_PHRASES.matcher(statement).find() ? "a construct that is not allowed here" : null;
		if (reason == null) {
			Matcher words = WORD.matcher(statement.toLowerCase(Locale.ROOT));
			while (words.find()) {
				if (FORBIDDEN.contains(words.group())) {
					reason = "the keyword " + words.group().toUpperCase(Locale.ROOT) + " is not allowed";
					break;
				}
			}
		}
		if (reason != null) {
			return "This query was refused because it contains " + reason + ". The sandbox runs read-only "
			        + "single statements only.";
		}
		return null;
	}
	
	/**
	 * Wraps an accepted statement so the row cap cannot be removed by the analyst writing their own
	 * LIMIT. Deriving the cap from the inner query is what makes this a limit rather than a suggestion.
	 *
	 * @param sql a statement {@link #reject} has accepted
	 * @param limit the maximum rows to return
	 * @return the statement to execute
	 */
	public static String wrap(String sql, int limit) {
		return "SELECT * FROM (" + stripTrailingSemicolon(sql.trim()) + ") cr_raw_preview LIMIT " + Math.max(1, limit);
	}
	
	/**
	 * Removes one trailing semicolon, because writers add one out of habit. Only one: {@code SELECT
	 * 1;;} would otherwise be reduced to {@code SELECT 1} and accepted, and the whole point of counting
	 * semicolons is that anything after the first one is a statement this scan never looked at.
	 */
	private static String stripTrailingSemicolon(String sql) {
		String trimmed = sql.trim();
		if (trimmed.endsWith(";")) {
			return trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}
	
	/**
	 * @return the forbidden keyword set, for tests and for documenting the policy in the UI
	 */
	public static Set<String> forbiddenKeywords() {
		return FORBIDDEN;
	}
	
	/**
	 * @return the keywords the sandbox treats as safe leading words
	 */
	public static List<String> allowedStarts() {
		return Arrays.asList("SELECT", "WITH");
	}
}
