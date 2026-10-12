/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.hibernate.Session;
import org.hibernate.context.internal.ThreadLocalSessionContext;
import org.hibernate.SessionFactory;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

/**
 * Runs the report DAOs' native SQL against a real H2 database.
 * <p>
 * The reports are written as native SQL, which means the compiler cannot check them. This harness
 * gives them somewhere to actually execute: an in-memory database whose columns mirror the OpenMRS
 * 2.8 Liquibase snapshot, and a mapping-free Hibernate session factory so only the SQL is under
 * test.
 */
public abstract class NativeSqlTestBase {
	
	/** Per-run database name, so parallel test classes do not share state. */
	private static final String DB_NAME = "reporttest" + System.nanoTime();
	
	private static SessionFactory sessionFactory;
	
	private static String jdbcUrl;
	
	private static Session session;
	
	private static Transaction transaction;
	
	@BeforeClass
	public static void startDatabase() throws Exception {
		jdbcUrl = "jdbc:h2:mem:" + DB_NAME + ";DB_CLOSE_DELAY=-1";
		createSchema();
		sessionFactory = new Configuration().setProperty("hibernate.connection.driver_class", "org.h2.Driver")
		        .setProperty("hibernate.connection.url", jdbcUrl).setProperty("hibernate.connection.username", "sa")
		        .setProperty("hibernate.connection.password", "")
		        .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
		        // Nothing is mapped: every report query is native SQL, so Hibernate only ever
		        // needs to carry a connection and run statements.
		        .setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false")
		        // Lets the session opened below be handed to the DAOs the same way OpenMRS's
		        // Spring transaction manager hands them out, via getCurrentSession().
		        .setProperty("hibernate.current_session_context_class", "thread").buildSessionFactory();
	}
	
	/**
	 * Opens a session and transaction for each test. OpenMRS supplies both through its Spring
	 * transaction manager, so the harness has to do the same before a report query will run.
	 */
	@Before
	public void beginTransaction() {
		session = sessionFactory.openSession();
		transaction = session.beginTransaction();
		ThreadLocalSessionContext.bind(session);
	}
	
	@After
	public void endTransaction() {
		if (transaction != null) {
			if (transaction.isActive()) {
				transaction.rollback();
			}
			transaction = null;
		}
		if (session != null) {
			ThreadLocalSessionContext.unbind(sessionFactory);
			if (session.isOpen()) {
				session.close();
			}
			session = null;
		}
	}
	
	@AfterClass
	public static void stopDatabase() throws Exception {
		if (sessionFactory != null) {
			sessionFactory.close();
		}
		AbstractReportDao.clearMetadataCache();
		try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
			Statement statement = connection.createStatement();
			statement.execute("SHUTDOWN");
		}
		catch (Exception ignored) {
			// The in-memory database disappears with the JVM anyway.
		}
	}
	
	protected static SessionFactory sessionFactory() {
		return sessionFactory;
	}
	
	/**
	 * Binds a DAO to this harness.
	 * <p>
	 * In production a DAO's session comes from {@code sessionFactory.getCurrentSession()}, supplied by
	 * OpenMRS's Spring transaction manager. There is no Spring here, so the session is supplied
	 * directly. Only that one method is overridden, which leaves the SQL, parameter binding and row
	 * mapping - the things these tests exist to check - running exactly as they ship.
	 */
	protected <T extends AbstractReportDao> T dao(T dao) {
		dao.setSessionFactory(new DbSessionFactory(sessionFactory));
		return dao;
	}
	
	/**
	 * Runs a statement outside the DAO layer, for arranging fixtures.
	 */
	protected static void execute(String... statements) throws Exception {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
		        Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}
	
	private static void createSchema() throws Exception {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
		        Statement statement = connection.createStatement()) {
			InputStream schema = NativeSqlTestBase.class.getResourceAsStream("/report-schema.sql");
			if (schema == null) {
				throw new IllegalStateException("report-schema.sql is missing from the test classpath");
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(schema, StandardCharsets.UTF_8))) {
				StringBuilder batch = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (trimmed.isEmpty() || trimmed.startsWith("--")) {
						continue;
					}
					batch.append(line).append('\n');
					if (trimmed.endsWith(";")) {
						statement.execute(batch.toString());
						batch.setLength(0);
					}
				}
			}
		}
	}
}
