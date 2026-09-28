package org.openmrs.module.phu360reporting.report;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.query.NativeQuery;

/**
 * The SQL restriction a REPORT TYPE selection puts on the encounter set.
 *
 * <p>Encounter type names are resolved to ids once, by {@link #resolve}, and
 * the restriction is then expressed as a fragment that can be appended to any
 * query aliasing the encounter table as {@code e} - which is how the same
 * selection applies consistently to the KPI counts, the trend, and every
 * breakdown.
 */
public final class ReportFilter {

    private final List<Integer> encounterTypeIds = new ArrayList<Integer>();
    private final List<Integer> obsConceptIds = new ArrayList<Integer>();
    private final int obsValueId;

    private ReportFilter(List<Integer> encounterTypeIds, List<Integer> obsConceptIds, int obsValueId) {
        this.encounterTypeIds.addAll(encounterTypeIds);
        this.obsConceptIds.addAll(obsConceptIds);
        this.obsValueId = obsValueId;
    }

    /**
     * Resolves a catalog entry against this database.
     *
     * <p>Encounter type names and obs concept ids are both resolved here, so a
     * report whose concepts this database does not have ends up without that
     * part of its restriction rather than filtering on ids that match nothing.
     *
     * @return the filter, or null when the report places no restriction on the
     *         encounter set ("All Encounter").
     */
    public static ReportFilter resolve(ReportCatalog.Report report, NameResolver resolver) {
        List<Integer> ids = new ArrayList<Integer>();
        for (String name : report.getEncounterTypeNames()) {
            Integer id = resolver.encounterTypeId(name);
            if (id != null) {
                ids.add(id);
            }
        }
        List<Integer> concepts = new ArrayList<Integer>();
        for (int id : report.getObsConceptIds()) {
            if (resolver.conceptExists(id)) {
                concepts.add(id);
            }
        }
        if (ids.isEmpty() && concepts.isEmpty()) {
            return null;
        }
        return new ReportFilter(ids, concepts, report.getObsValueId());
    }

    /**
     * True when a report is defined by obs concepts and this database has none
     * of them. Such a report cannot filter on its defining data, so it is
     * reported as unmapped instead of as a confident zero.
     */
    public static boolean obsConceptsMissing(ReportCatalog.Report report, NameResolver resolver) {
        if (report.getObsConceptIds().length == 0) {
            return false;
        }
        for (int id : report.getObsConceptIds()) {
            if (resolver.conceptExists(id)) {
                return false;
            }
        }
        return true;
    }

    /** Resolves names and ids against the database; kept as an interface so this
     *  class needs no session. */
    public interface NameResolver {
        Integer encounterTypeId(String name);

        boolean conceptExists(int conceptId);
    }

    /** ANDed onto a query whose encounter table is aliased {@code e}. */
    public String sqlFragment() {
        StringBuilder sb = new StringBuilder();
        if (!encounterTypeIds.isEmpty()) {
            sb.append(" AND e.encounter_type IN (:repEtIds)");
        }
        if (!obsConceptIds.isEmpty()) {
            sb.append(" AND EXISTS (SELECT 1 FROM obs o WHERE o.encounter_id = e.encounter_id AND o.voided = 0");
            sb.append(" AND o.concept_id IN (:repConIds)");
            if (obsValueId > 0) {
                sb.append(" AND o.value_coded = :repVal");
            }
            sb.append(")");
        }
        return sb.toString();
    }

    public void bind(NativeQuery q) {
        if (!encounterTypeIds.isEmpty()) {
            q.setParameterList("repEtIds", encounterTypeIds);
        }
        if (!obsConceptIds.isEmpty()) {
            q.setParameterList("repConIds", obsConceptIds);
            if (obsValueId > 0) {
                q.setParameter("repVal", obsValueId);
            }
        }
    }
}
