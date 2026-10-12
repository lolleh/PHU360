-- Minimal schema covering only the columns the report queries touch.
-- Types mirror the OpenMRS Liquibase snapshot so dialect-sensitive behaviour
-- (BOOLEAN columns, datetime truncation) is exercised as it would be in
-- production. Deliberately no foreign keys: the reports only ever read.
CREATE TABLE patient (
  patient_id INT PRIMARY KEY AUTO_INCREMENT,
  voided BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE person (
  person_id INT PRIMARY KEY AUTO_INCREMENT,
  uuid CHAR(38) NOT NULL UNIQUE,
  gender VARCHAR(1),
  birthdate DATETIME,
  birthdate_estimated BOOLEAN,
  dead BOOLEAN NOT NULL DEFAULT FALSE,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE person_name (
  person_name_id INT PRIMARY KEY AUTO_INCREMENT,
  person_id INT NOT NULL,
  preferred BOOLEAN DEFAULT FALSE,
  prefix VARCHAR(50),
  given_name VARCHAR(50),
  middle_name VARCHAR(50),
  family_name VARCHAR(50),
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE patient_identifier (
  patient_identifier_id INT PRIMARY KEY AUTO_INCREMENT,
  patient_id INT NOT NULL,
  identifier VARCHAR(255),
  preferred BOOLEAN DEFAULT FALSE,
  location_id INT,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE location (
  location_id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255),
  parent_location INT,
  retired BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE encounter_type (
  encounter_type_id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255),
  uuid CHAR(38) NOT NULL UNIQUE,
  retired BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE encounter (
  encounter_id INT PRIMARY KEY AUTO_INCREMENT,
  patient_id INT NOT NULL,
  encounter_type INT,
  encounter_datetime DATETIME,
  location_id INT,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE concept (
  concept_id INT PRIMARY KEY AUTO_INCREMENT,
  short_name VARCHAR(255),
  uuid CHAR(38) NOT NULL UNIQUE,
  -- Concept is the one dictionary table that is retired, not voided: there is no
  -- concept.voided column, so the dashboards must filter on retired being NULL.
  retired BOOLEAN
);

CREATE TABLE concept_name (
  concept_name_id INT PRIMARY KEY AUTO_INCREMENT,
  concept_id INT NOT NULL,
  name VARCHAR(255),
  locale VARCHAR(50),
  locale_preferred BOOLEAN NOT NULL DEFAULT FALSE,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE obs (
  obs_id INT PRIMARY KEY AUTO_INCREMENT,
  person_id INT NOT NULL,
  concept_id INT,
  order_id INT,
  obs_datetime DATETIME,
  location_id INT,
  value_coded_name_id INT,
  value_text TEXT,
  value_complex VARCHAR(255),
  value_numeric DOUBLE,
  status VARCHAR(32),
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE concept_set (
  concept_set_id INT PRIMARY KEY AUTO_INCREMENT,
  concept_id INT NOT NULL,
  concept_set INT NOT NULL,
  sort_weight DOUBLE,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE order_type (
  order_type_id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255),
  uuid CHAR(38) NOT NULL UNIQUE,
  retired BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE orders (
  order_id INT PRIMARY KEY AUTO_INCREMENT,
  patient_id INT NOT NULL,
  encounter_id INT,
  order_type_id INT,
  concept_id INT,
  order_number VARCHAR(50),
  accession_number VARCHAR(255),
  uuid CHAR(38),
  date_activated DATETIME,
  date_created DATETIME NOT NULL,
  voided BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE drug_order (
  order_id INT PRIMARY KEY AUTO_INCREMENT,
  drug_inventory_id INT,
  dose DOUBLE,
  quantity INT,
  as_needed BOOLEAN,
  drug_non_coded VARCHAR(255)
);

CREATE TABLE encounter_diagnosis (
  diagnosis_id INT PRIMARY KEY AUTO_INCREMENT,
  encounter_id INT,
  patient_id INT,
  condition_id INT,
  diagnosis_coded INT,
  diagnosis_non_coded TEXT,
  diagnosis_coded_name INT,
  certainty VARCHAR(255),
  dx_rank INT,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE program (
  program_id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50),
  uuid CHAR(38) NOT NULL UNIQUE,
  retired BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE patient_program (
  patient_program_id INT PRIMARY KEY AUTO_INCREMENT,
  patient_id INT NOT NULL,
  program_id INT,
  date_enrolled DATETIME,
  date_completed DATETIME,
  location_id INT,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);

CREATE TABLE cohort (
  cohort_id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255),
  description VARCHAR(1000),
  uuid CHAR(38) NOT NULL UNIQUE,
  voided BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE cohort_member (
  cohort_member_id INT PRIMARY KEY AUTO_INCREMENT,
  cohort_id INT NOT NULL,
  patient_id INT NOT NULL,
  start_date DATETIME,
  end_date DATETIME,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  date_created DATETIME NOT NULL
);
