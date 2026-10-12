/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.util;

import java.util.List;

/**
 * Turns export rows into the comma-separated text a spreadsheet opens.
 * <p>
 * Parsing rules follow RFC 4180: a field is quoted when it contains a comma, a quote, a line break
 * or lead/trail whitespace, and any quote inside a quoted field is doubled. A field is never
 * truncated - that is the difference between an export and a preview - so a value that the preview
 * elided comes out whole.
 * <p>
 * One deliberate deviation protects whoever opens the file: a field that begins with a character a
 * spreadsheet would treat as a formula - {@code =}, {@code +}, {@code @} or a control character -
 * is prefixed with a single quote. A text string like {@code =B5+1} is then data, not an
 * expression. The prefix is the standard, low-cost mitigation and it only touches fields that would
 * otherwise be interpreted.
 */
public final class Csv {
	
	private static final String CRLF = "\r\n";
	
	private Csv() {
	}
	
	/**
	 * @param headers the column headings
	 * @param rows the cells, one entry per row in column order
	 * @return the CSV document, including a trailing newline
	 */
	public static String format(List<String> headers, List<List<String>> rows) {
		StringBuilder out = new StringBuilder();
		if (headers != null && !headers.isEmpty()) {
			out.append(line(headers));
		}
		if (rows != null) {
			for (List<String> row : rows) {
				if (row == null) {
					continue;
				}
				out.append(line(row));
			}
		}
		return out.toString();
	}
	
	private static String line(List<String> cells) {
		StringBuilder line = new StringBuilder();
		for (int index = 0; index < cells.size(); index++) {
			if (index > 0) {
				line.append(',');
			}
			line.append(cell(cells.get(index)));
		}
		return line.append(CRLF).toString();
	}
	
	private static String cell(String value) {
		if (value == null) {
			return "";
		}
		if (formulaHeaded(value)) {
			return quoted("'" + value);
		}
		return needsQuoting(value) ? quoted(value) : value;
	}
	
	private static boolean needsQuoting(String value) {
		return value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
		        || value.startsWith(" ") || value.endsWith(" ");
	}
	
	private static boolean formulaHeaded(String value) {
		char first = value.charAt(0);
		return first == '=' || first == '+' || first == '@' || Character.isISOControl(first);
	}
	
	private static String quoted(String value) {
		return '"' + value.replace("\"", "\"\"") + '"';
	}
}
