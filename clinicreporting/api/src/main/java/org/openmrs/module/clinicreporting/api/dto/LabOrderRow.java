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

import java.util.Date;

/**
 * A lab order together with its result, when one has been recorded.
 */
public class LabOrderRow {
	
	private Integer orderId;
	
	private String orderUuid;
	
	private String orderNumber;
	
	private String accessionNumber;
	
	private Integer patientId;
	
	private String patientUuid;
	
	private String display;
	
	private String identifier;
	
	private String gender;
	
	private Integer ageInMonths;
	
	private String orderTypeName;
	
	private String conceptName;
	
	private String conceptUuid;
	
	private Date orderDate;
	
	private Date resultDate;
	
	private String resultValue;
	
	private String resultStatus;
	
	private Double turnaroundHours;
	
	public Integer getOrderId() {
		return orderId;
	}
	
	public void setOrderId(Integer orderId) {
		this.orderId = orderId;
	}
	
	public String getOrderUuid() {
		return orderUuid;
	}
	
	public void setOrderUuid(String orderUuid) {
		this.orderUuid = orderUuid;
	}
	
	/**
	 * @return the order number the lab recorded for this request, which is what staff quote when
	 *         chasing a result
	 */
	public String getOrderNumber() {
		return orderNumber;
	}
	
	public void setOrderNumber(String orderNumber) {
		this.orderNumber = orderNumber;
	}
	
	/**
	 * @return the lab's own accession number, when the order carries one
	 */
	public String getAccessionNumber() {
		return accessionNumber;
	}
	
	public void setAccessionNumber(String accessionNumber) {
		this.accessionNumber = accessionNumber;
	}
	
	public Integer getPatientId() {
		return patientId;
	}
	
	public void setPatientId(Integer patientId) {
		this.patientId = patientId;
	}
	
	public String getPatientUuid() {
		return patientUuid;
	}
	
	public void setPatientUuid(String patientUuid) {
		this.patientUuid = patientUuid;
	}
	
	public String getDisplay() {
		return display;
	}
	
	public void setDisplay(String display) {
		this.display = display;
	}
	
	public String getIdentifier() {
		return identifier;
	}
	
	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}
	
	public String getGender() {
		return gender;
	}
	
	public void setGender(String gender) {
		this.gender = gender;
	}
	
	public Integer getAgeInMonths() {
		return ageInMonths;
	}
	
	public void setAgeInMonths(Integer ageInMonths) {
		this.ageInMonths = ageInMonths;
	}
	
	public String getOrderTypeName() {
		return orderTypeName;
	}
	
	public void setOrderTypeName(String orderTypeName) {
		this.orderTypeName = orderTypeName;
	}
	
	public String getConceptName() {
		return conceptName;
	}
	
	public void setConceptName(String conceptName) {
		this.conceptName = conceptName;
	}
	
	public String getConceptUuid() {
		return conceptUuid;
	}
	
	public void setConceptUuid(String conceptUuid) {
		this.conceptUuid = conceptUuid;
	}
	
	public Date getOrderDate() {
		return orderDate;
	}
	
	public void setOrderDate(Date orderDate) {
		this.orderDate = orderDate;
	}
	
	public Date getResultDate() {
		return resultDate;
	}
	
	public void setResultDate(Date resultDate) {
		this.resultDate = resultDate;
	}
	
	public String getResultValue() {
		return resultValue;
	}
	
	public void setResultValue(String resultValue) {
		this.resultValue = resultValue;
	}
	
	public String getResultStatus() {
		return resultStatus;
	}
	
	public void setResultStatus(String resultStatus) {
		this.resultStatus = resultStatus;
	}
	
	public Double getTurnaroundHours() {
		return turnaroundHours;
	}
	
	public void setTurnaroundHours(Double turnaroundHours) {
		this.turnaroundHours = turnaroundHours;
	}
}
