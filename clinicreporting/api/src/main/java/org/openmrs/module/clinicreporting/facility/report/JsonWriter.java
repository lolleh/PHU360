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

import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON serializer for the shapes the API returns (String, Number, Boolean,
 * List, Map). Values already rendered as strings are emitted verbatim.
 */
public final class JsonWriter {
	
	private JsonWriter() {
	}
	
	public static String json(Object o) {
		StringBuilder sb = new StringBuilder();
		write(sb, o);
		return sb.toString();
	}
	
	@SuppressWarnings("unchecked")
	private static void write(StringBuilder sb, Object o) {
		if (o == null) {
			sb.append("null");
		} else if (o instanceof String) {
			quote(sb, (String) o);
		} else if (o instanceof Number || o instanceof Boolean) {
			sb.append(o);
		} else if (o instanceof List) {
			sb.append('[');
			List<Object> list = (List<Object>) o;
			for (int i = 0; i < list.size(); i++) {
				if (i > 0)
					sb.append(',');
				write(sb, list.get(i));
			}
			sb.append(']');
		} else if (o instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
				if (!first)
					sb.append(',');
				first = false;
				quote(sb, String.valueOf(e.getKey()));
				sb.append(':');
				write(sb, e.getValue());
			}
			sb.append('}');
		} else {
			quote(sb, String.valueOf(o));
		}
	}
	
	private static void quote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}
}
