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
 * One cleaning or mapping step applied to a single column of a preview.
 * <p>
 * Steps are values rather than a class hierarchy because they arrive from an HTML form as a type
 * name plus a few text fields, and because the whole set has to be enumerable so the UI can offer
 * the valid choices. Each type knows how to render itself as a SQL expression over the expression
 * built by the previous step; see {@link TransformRequest#build()}.
 * <p>
 * Every value a step contributes to SQL is bound as a parameter by the builder. A step never
 * contributes SQL text of its own beyond a fixed template chosen by its type, so there is no path
 * from step configuration to SQL injection.
 */
public final class TransformStep {
	
	/** The cleaning and mapping operations the sandbox offers. */
	public enum Type {
		
		/** Removes leading and trailing whitespace. */
		TRIM("Trim whitespace"),
		/** Upper-cases text. */
		UPPER("Upper case"),
		/** Lower-cases text. */
		LOWER("Lower case"),
		/** Replaces a value that is null, empty or whitespace with a label. */
		BLANK_TO("Blank to"),
		/** Maps exact values onto labels, e.g. M to Male. Pairs arrive as {@code from=to} lines. */
		RECODE("Recode values"),
		/** Buckets a number into labelled ranges, e.g. under 5, 5 to 14, 15 and over. */
		BUCKET("Bucket ranges"),
		/** Rounds a number to a number of decimal places. */
		ROUND("Round to"),
		/** Keeps only the year of a date. */
		YEAR("Year of date"),
		/** Keeps only the month of a date as a number. */
		MONTH("Month of date"),
		/** Renames the output column, which is how a source field becomes an indicator name. */
		RENAME("Rename output column");
		
		private final String label;
		
		Type(String label) {
			this.label = label;
		}
		
		public String getLabel() {
			return label;
		}
		
		/**
		 * @param name a value submitted by the form
		 * @return the matching type, or null when the name is absent or unknown. An unknown type is a
		 *         refusal, not a fallback to a default.
		 */
		public static Type parse(String name) {
			if (name == null) {
				return null;
			}
			String trimmed = name.trim();
			for (Type type : values()) {
				if (type.name().equalsIgnoreCase(trimmed)) {
					return type;
				}
			}
			return null;
		}
	}
	
	private final Type type;
	
	private final String column;
	
	/** Replacement label for {@link Type#BLANK_TO}, decimal places for {@link Type#ROUND}. */
	private final String argument;
	
	/**
	 * Ordered pairs for {@link Type#RECODE} and {@link Type#BUCKET}, already parsed. For RECODE a pair
	 * is {@code from -> to}. For BUCKET it is {@code upperBound|label}, where an upper bound of
	 * {@code *} is the final catch-all.
	 */
	private final String[] pairs;
	
	public TransformStep(Type type, String column, String argument, String[] pairs) {
		this.type = type;
		this.column = column;
		this.argument = argument;
		this.pairs = pairs == null ? new String[0] : pairs.clone();
	}
	
	public Type getType() {
		return type;
	}
	
	/** @return the source column this step applies to */
	public String getColumn() {
		return column;
	}
	
	public String getArgument() {
		return argument;
	}
	
	public String[] getPairs() {
		return pairs.clone();
	}
	
	/**
	 * @return a short human-readable summary for the "current pipeline" list in the UI
	 */
	public String describe() {
		switch (type) {
			case BLANK_TO:
				return type.getLabel() + ": \"" + argument + "\"";
			case ROUND:
				return type.getLabel() + ": " + argument + " dp";
			case RENAME:
				return type.getLabel() + ": " + argument;
			case RECODE:
				return type.getLabel() + ": " + pairs.length + " rule(s)";
			case BUCKET:
				return type.getLabel() + ": " + pairs.length + " band(s)";
			default:
				return type.getLabel();
		}
	}
	
	@Override
	public String toString() {
		return type + "(" + column + ")";
	}
}
