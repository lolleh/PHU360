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

import java.util.Collections;
import java.util.List;

/**
 * A single page of report rows plus the metadata a client needs to page through the rest.
 *
 * @param <T> the row type
 */
public class PagedResult<T> {
	
	private final List<T> results;
	
	private final long totalCount;
	
	private final int page;
	
	private final int size;
	
	public PagedResult(List<T> results, long totalCount, int page, int size) {
		this.results = results == null ? Collections.<T> emptyList() : results;
		this.totalCount = totalCount;
		this.page = page;
		this.size = size;
	}
	
	public static <T> PagedResult<T> empty(ReportFilter filter) {
		return new PagedResult<T>(Collections.<T> emptyList(), 0L, filter.getPage(), filter.getSize());
	}
	
	public List<T> getResults() {
		return results;
	}
	
	public long getTotalCount() {
		return totalCount;
	}
	
	public int getPage() {
		return page;
	}
	
	public int getSize() {
		return size;
	}
	
	public int getTotalPages() {
		return size <= 0 ? 0 : (int) ((totalCount + size - 1) / size);
	}
	
	public boolean isHasMore() {
		return (long) page * size < totalCount;
	}
}
