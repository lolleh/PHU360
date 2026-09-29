package org.openmrs.module.phu360reporting.report;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.hibernate.Session;

/**
 * The location hierarchy queries the dashboard and the table refresher share.
 *
 * <p>Reading the tree straight out of the {@code location} table rather than
 * through the location service keeps the two callers in step: if they resolved
 * the subtree differently, a report narrowed to a health center would read a
 * table built from a different set of locations and quietly disagree with the
 * live query it replaced.
 */
public final class LocationHierarchy {

    private LocationHierarchy() {
    }

    /**
     * The health centers the dashboard's filter offers: the non-retired roots
     * whose name ends in CHC.
     */
    public static Set<Integer> healthCenters() {
        Set<Integer> ids = new TreeSet<Integer>();
        Session sess = IndicatorFacts.session();
        try {
            List<?> rows = sess
                    .createNativeQuery("SELECT location_id FROM location"
                            + " WHERE retired = 0 AND parent_location IS NULL AND UPPER(name) LIKE '%CHC'"
                            + " ORDER BY location_id")
                    .list();
            for (Object o : rows) {
                ids.add(Integer.valueOf(((Number) o).intValue()));
            }
        } finally {
            sess.close();
        }
        return ids;
    }

    /** All non-retired locations in the subtree rooted at {@code rootId}, itself included. */
    public static Set<Integer> subtree(int rootId) {
        Set<Integer> ids = new HashSet<Integer>();
        Map<Integer, List<Integer>> children = new HashMap<Integer, List<Integer>>();
        Session sess = IndicatorFacts.session();
        try {
            List<?> rows = sess.createNativeQuery("SELECT location_id, parent_location FROM location WHERE retired=0")
                    .list();
            for (Object row : rows) {
                Object[] arr = (Object[]) row;
                int id = ((Number) arr[0]).intValue();
                if (arr[1] == null) {
                    continue;
                }
                int parent = ((Number) arr[1]).intValue();
                List<Integer> kids = children.get(Integer.valueOf(parent));
                if (kids == null) {
                    kids = new ArrayList<Integer>();
                    children.put(Integer.valueOf(parent), kids);
                }
                kids.add(Integer.valueOf(id));
            }
        } finally {
            sess.close();
        }
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        queue.add(Integer.valueOf(rootId));
        while (!queue.isEmpty()) {
            Integer id = queue.removeFirst();
            if (!ids.add(id)) {
                continue;
            }
            List<Integer> kids = children.get(id);
            if (kids != null) {
                queue.addAll(kids);
            }
        }
        return ids;
    }
}
