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

    private ReportFilter(List<Integer> encounterTypeIds, int[] obsConceptIds, int obsValueId) {
        this.encounterTypeIds.addAll(encounterTypeIds);
        for (int id : obsConceptIds) {
            this.obsConceptIds.add(id);
        }
        this.obsValueId = obsValueId;
    }

    /**
     * Resolves a catalog entry against this database.
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
        boolean hasObs = report.getObsConceptIds().length > 0;
        if (ids.isEmpty() && !hasObs) {
            return null;
        }
        return new ReportFilter(ids, report.getObsConceptIds(), report.getObsValueId());
    }

    /** Resolves names to ids; kept as an interface so this class needs no session. */
    public interface NameResolver {
        Integer encounterTypeId(String name);
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
