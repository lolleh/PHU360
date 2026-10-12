/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dto;

/**
 * One cell of a two-dimensional aggregate: a row key/label, a column key/label and the count that
 * falls at their intersection.
 * <p>
 * A report that compares one dimension against another (diagnoses against treatments, say) is
 * returned by the DAO as a flat list of these, one per non-empty intersection. {@link CrossTab}
 * then lays them out.
 */
public class CrossTabRow {
	
	private String rowKey;
	
	private String rowLabel;
	
	private String columnKey;
	
	private String columnLabel;
	
	private long count;
	
	public CrossTabRow() {
	}
	
	public CrossTabRow(String rowKey, String rowLabel, String columnKey, String columnLabel, long count) {
		this.rowKey = rowKey;
		this.rowLabel = rowLabel;
		this.columnKey = columnKey;
		this.columnLabel = columnLabel;
		this.count = count;
	}
	
	public String getRowKey() {
		return rowKey;
	}
	
	public void setRowKey(String rowKey) {
		this.rowKey = rowKey;
	}
	
	public String getRowLabel() {
		return rowLabel;
	}
	
	public void setRowLabel(String rowLabel) {
		this.rowLabel = rowLabel;
	}
	
	public String getColumnKey() {
		return columnKey;
	}
	
	public void setColumnKey(String columnKey) {
		this.columnKey = columnKey;
	}
	
	public String getColumnLabel() {
		return columnLabel;
	}
	
	public void setColumnLabel(String columnLabel) {
		this.columnLabel = columnLabel;
	}
	
	public long getCount() {
		return count;
	}
	
	public void setCount(long count) {
		this.count = count;
	}
}
