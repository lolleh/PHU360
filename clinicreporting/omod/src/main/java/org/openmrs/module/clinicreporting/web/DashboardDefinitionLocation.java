/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web;

import java.io.File;

import javax.servlet.ServletContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.clinicreporting.api.dashboard.ProgramIndicatorCatalog;
import org.springframework.web.context.ServletContextAware;

/**
 * Tells the indicator catalogue where the webapp keeps this module's configuration.
 * <p>
 * The catalogue lives in the module's API so it can be tested without a servlet container, and the
 * API has no way to know a webapp's layout. This bean bridges the two: the page context constructs
 * it once at start-up and it hands the module configuration directory to the catalogue.
 * <p>
 * It implements {@link ServletContextAware} rather than taking a {@code ServletContext} as a
 * constructor argument, because there is no {@code servletContext} bean in this context to
 * reference. Spring calls the setter itself, which is the supported way for a bean to reach the
 * context.
 * <p>
 * A directory that does not exist is not an error. The catalogue then falls back to the definitions
 * the module ships, which is the right behaviour for a fresh install and for the many servers where
 * the configuration directory is not writable.
 */
public class DashboardDefinitionLocation implements ServletContextAware {
	
	private static final Log log = LogFactory.getLog(DashboardDefinitionLocation.class);
	
	/**
	 * Where OpenMRS serves a module's editable configuration from. Under the webapp rather than in the
	 * module directory, because the module directory is replaced on every upgrade and an
	 * administrator's edits have to survive that.
	 */
	public static final String DEFAULT_RELATIVE_PATH = "/WEB-INF/module-config/clinicreporting";
	
	private final String relativePath;
	
	private ServletContext servletContext;
	
	private volatile File resolved;
	
	public DashboardDefinitionLocation() {
		this(DEFAULT_RELATIVE_PATH);
	}
	
	public DashboardDefinitionLocation(String relativePath) {
		this.relativePath = relativePath;
	}
	
	@Override
	public void setServletContext(ServletContext servletContext) {
		this.servletContext = servletContext;
		this.resolved = resolve(servletContext, relativePath);
		if (resolved != null) {
			ProgramIndicatorCatalog.setConfigurationDirectory(resolved);
			log.info("Programme dashboard definitions will be read from " + resolved.getAbsolutePath());
		}
	}
	
	private static File resolve(ServletContext servletContext, String relativePath) {
		if (servletContext == null || relativePath == null) {
			return null;
		}
		String realPath = servletContext.getRealPath(relativePath);
		if (realPath == null) {
			// A servlet container is allowed to refuse real paths. Carry on with the bundled definitions.
			log.info("The servlet context would not resolve " + relativePath
			        + "; programme dashboards will use the definitions this module ships");
			return null;
		}
		return new File(realPath);
	}
	
	/**
	 * @return the directory the catalogue was pointed at, or null when the container would not resolve
	 *         the path
	 */
	public File getResolvedDirectory() {
		return resolved;
	}
	
	/**
	 * @return the path as configured, for the start-up message and for tests
	 */
	public String getRelativePath() {
		return relativePath;
	}
	
	/**
	 * @return the servlet context this bean was given, for tests that assert on the wiring
	 */
	public ServletContext getServletContext() {
		return servletContext;
	}
}
