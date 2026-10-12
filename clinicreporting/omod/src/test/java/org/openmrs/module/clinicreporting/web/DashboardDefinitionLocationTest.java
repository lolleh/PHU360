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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.File;

import javax.servlet.ServletContext;

import org.junit.After;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dashboard.ProgramIndicatorCatalog;

/**
 * Tests the bridge that tells the indicator catalogue where the webapp keeps module configuration.
 * <p>
 * This exists because the wiring is easy to get wrong in a way no other test can see. The bean used
 * to ask for a {@code servletContext} bean by reference; there is no such bean in this context, so
 * the whole module web context failed to refresh at start-up and every page in the module went down
 * with it. The container was the only place that failure showed up.
 */
public class DashboardDefinitionLocationTest {
	
	@After
	public void forgetTheDirectory() {
		ProgramIndicatorCatalog.clearCache();
	}
	
	@Test
	public void setServletContext_shouldHandTheDirectoryToTheCatalogue() {
		DashboardDefinitionLocation location = new DashboardDefinitionLocation();
		location.setServletContext(contextAnsweringRealPaths("/served/openmrs"));
		// Spring calls the setter itself, so this is the whole contract: after it, the catalogue knows
		// where an administrator's copy of the definitions lives.
		assertEquals(new File("/served/openmrs" + DashboardDefinitionLocation.DEFAULT_RELATIVE_PATH),
		    location.getResolvedDirectory());
		assertSame(location.getResolvedDirectory(), currentCatalogueDirectory());
	}
	
	@Test
	public void setServletContext_shouldUseThePathItWasGiven() {
		DashboardDefinitionLocation location = new DashboardDefinitionLocation("/WEB-INF/other-config");
		location.setServletContext(contextAnsweringRealPaths("/served/openmrs"));
		assertEquals(new File("/served/openmrs/WEB-INF/other-config"), location.getResolvedDirectory());
		assertEquals("/WEB-INF/other-config", location.getRelativePath());
	}
	
	@Test
	public void setServletContext_shouldCarryOnWhenTheContainerRefusesARealPath() {
		DashboardDefinitionLocation location = new DashboardDefinitionLocation();
		ServletContext context = contextAnsweringRealPaths(null);
		location.setServletContext(context);
		// Some containers serve resources from a jar and answer null. That must fall back to the bundled
		// definitions rather than fail the context refresh.
		assertNull(location.getResolvedDirectory());
		assertNull(currentCatalogueDirectory());
	}
	
	@Test
	public void setServletContext_shouldSurviveBeingCalledWithNothing() {
		DashboardDefinitionLocation location = new DashboardDefinitionLocation();
		location.setServletContext(null);
		assertNull(location.getResolvedDirectory());
		assertNotNull(location.getRelativePath());
	}
	
	/**
	 * @return the directory the catalogue is currently pointed at, which it only exposes indirectly, so
	 *         this reads the effect back rather than the field
	 */
	private static File currentCatalogueDirectory() {
		ProgramIndicatorCatalog.load();
		for (java.lang.reflect.Field field : ProgramIndicatorCatalog.class.getDeclaredFields()) {
			if (field.getName().equals("configurationDirectory")) {
				field.setAccessible(true);
				try {
					return (File) field.get(null);
				}
				catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		throw new IllegalStateException("ProgramIndicatorCatalog has no configurationDirectory field");
	}
	
	/**
	 * A servlet context that answers {@code getRealPath} and nothing else.
	 * <p>
	 * A proxy rather than a stub class: {@code ServletContext} has dozens of methods, and the ones the
	 * bean does not call are not worth writing. A call the bean starts making that this does not answer
	 * returns null, which is the same as a container declining, so the tests keep describing the real
	 * fallback path.
	 */
	private static ServletContext contextAnsweringRealPaths(final String root) {
		return (ServletContext) java.lang.reflect.Proxy.newProxyInstance(
		    DashboardDefinitionLocationTest.class.getClassLoader(), new Class<?>[] { ServletContext.class },
		    new java.lang.reflect.InvocationHandler() {
			    
			    @Override
			    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
				    if ("getRealPath".equals(method.getName())) {
					    return root == null ? null : root + args[0];
				    }
				    if ("toString".equals(method.getName())) {
					    return "StubServletContext";
				    }
				    return null;
			    }
		    });
	}
}
