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

/**
 * One row condition applied to a data export: a column, an operator, and a value to compare it
 * with.
 * <p>
 * Like {@link TransformStep} this is a value rather than a hierarchy, because it arrives from an
 * HTML form as a column name, an operator name and a text field, and because the whole set has to
 * be enumerable so the UI can offer the valid choices.
 * <p>
 * The operator is a fixed symbol chosen by {@link Operator} and the value never reaches the
 * statement as text: {@link TransformRequest#build()} binds it. A filter can therefore only narrow
 * what the allowlisted source already offers, never widen it.
 */
public final class TransformFilter {
	
	/**
	 * The comparisons a filter may make. The symbol is written by the builder rather than taken from
	 * caller input, so there is no operator a form could submit that reaches SQL.
	 */
	public enum Operator {
		
		/** Equals. */
		EQ("equals", "="),
		/** Does not equal. */
		NE("does not equal", "<>"),
		/** Greater than; for a date this reads as "after". */
		GT("is greater than / after", ">"),
		/** Greater than or equal; for a date this reads as "on or after". */
		GE("is greater than or equal to / on or after", ">="),
		/** Less than; for a date this reads as "before". */
		LT("is less than / before", "<"),
		/** Less than or equal; for a date this reads as "on or before". */
		LE("is less than or equal to / on or before", "<="),
		/** One of a comma-separated list. */
		IN("is one of", "IN"),
		/** The text contains the value, ignoring case. */
		CONTAINS("contains", "LIKE"),
		/** The text does not contain the value, ignoring case. */
		NOT_CONTAINS("does not contain", "NOT LIKE"),
		/** Null, empty or whitespace. */
		IS_NULL("is blank", "IS NULL"),
		/** Anything but null, empty or whitespace. */
		IS_NOT_NULL("is not blank", "IS NOT NULL");
		
		private final String label;
		
		private final String symbol;
		
		Operator(String label, String symbol) {
			this.label = label;
			this.symbol = symbol;
		}
		
		public String getLabel() {
			return label;
		}
		
		public String getSymbol() {
			return symbol;
		}
		
		/**
		 * @param name a value submitted by the form
		 * @return the matching operator, or null when the name is absent or unknown. An unknown operator is
		 *         a refusal, not a fallback to equality.
		 */
		public static Operator parse(String name) {
			if (name == null) {
				return null;
			}
			String trimmed = name.trim();
			for (Operator operator : values()) {
				if (operator.name().equalsIgnoreCase(trimmed) || operator.getSymbol().equals(trimmed)) {
					return operator;
				}
			}
			return null;
		}
	}
	
	/** Longest value a filter may carry, so one condition cannot smuggle a paragraph into a query. */
	public static final int MAX_VALUE_LENGTH = 200;
	
	private final String column;
	
	private final Operator operator;
	
	private final String value;
	
	public TransformFilter(String column, Operator operator, String value) {
		this.column = column;
		this.operator = operator;
		this.value = value;
	}
	
	public String getColumn() {
		return column;
	}
	
	public Operator getOperator() {
		return operator;
	}
	
	public String getValue() {
		return value;
	}
	
	/** @return true when this operator compares against a value at all */
	public boolean needsValue() {
		return operator != Operator.IS_NULL && operator != Operator.IS_NOT_NULL;
	}
	
	/**
	 * @return a short human-readable summary for the "current filters" list in the UI
	 */
	public String describe() {
		if (operator == null) {
			return column + " ?";
		}
		if (!needsValue()) {
			return column + " " + operator.getLabel();
		}
		return column + " " + operator.getLabel() + " \"" + (value == null ? "" : value) + "\"";
	}
	
	@Override
	public String toString() {
		return describe();
	}
}
