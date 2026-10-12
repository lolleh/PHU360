-- EMR ID generator prefix guard (per-site deployment)
--
-- pihcore 2.2.0 hardcodes a "KGH Primary Identifier Source" (uuid
-- 809b23e3-7162-11eb-8aa6-0242ac110002, prefix 'KGH'yyMM) for all Sierra Leone
-- KGH-family sites and re-applies it on EVERY OpenMRS boot. This script
-- holds the generator on the correct site prefix. Run against the OpenMRS DB
-- for the site instance. The scheduler events below re-assert every 15s.
--
-- The OpenMRS db service must run mysqld with --event-scheduler=ON (docker-compose.yml
-- does this) for the events to fire.

SET @site_code := 'FAL'; -- Change to MON or SIN per site

CREATE EVENT IF NOT EXISTS emr_id_prefix_guard
  ON SCHEDULE EVERY 15 SECOND
  DO UPDATE idgen_seq_id_gen SET prefix = CONCAT('''', @site_code, '''yyMM')
     WHERE id = 1 AND prefix <> CONCAT('''', @site_code, '''yyMM');

CREATE EVENT IF NOT EXISTS emr_id_source_name_guard
  ON SCHEDULE EVERY 15 SECOND
  DO UPDATE idgen_identifier_source i JOIN idgen_seq_id_gen s ON s.id = i.id
     SET i.name = CONCAT(@site_code, ' Primary Identifier Source'),
         i.description = CONCAT('Primary Identifier Generator for ', @site_code)
     WHERE i.uuid = '809b23e3-7162-11eb-8aa6-0242ac110002'
       AND i.name <> CONCAT(@site_code, ' Primary Identifier Source');

-- Registration-facility guard: normalize the EMR identifier location up to its
-- top-level CHC (parent with parent_location IS NULL among the three CHCs) every 30s.
CREATE EVENT IF NOT EXISTS emr_id_reg_facility_guard
  ON SCHEDULE EVERY 30 SECOND
  DO UPDATE patient_identifier pi
       JOIN location child ON child.location_id = pi.location_id
       JOIN location root  ON root.location_id = child.parent_location
       LEFT JOIN location grand ON grand.location_id = root.parent_location
     SET pi.location_id = root.location_id
     WHERE pi.identifier_type = 5 AND pi.voided = 0
       AND child.parent_location IS NOT NULL
       AND root.parent_location IS NULL
       AND root.name IN ('Falaba CHC','Mongo Bendugu CHC','Sinkunia CHC');
