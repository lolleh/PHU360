package org.openmrs.module.phu360reporting.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.phu360reporting.report.IndicatorReport;
import org.openmrs.module.phu360reporting.report.JsonWriter;

/**
 * Module servlet, mounted by config.xml at
 *   /openmrs/moduleServlet/phu360reporting/reportApi
 *
 * Endpoints (action query parameter):
 *   action=filters  -> health centers + encounter types for the filter bar
 *   action=counts   -> indicator dashboard JSON for the selected date range
 *                      / health center / encounter type
 */
public class ReportApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        send(resp, 200, "application/json", JsonWriter.json(handle(req)));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        send(resp, 200, "application/json", JsonWriter.json(handle(req)));
    }

    private Map<String, Object> handle(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!Context.isAuthenticated()) {
            out.put("error", "unauthorized");
            return out;
        }
        String action = req.getParameter("action");
        if (action == null) {
            action = "counts";
        }
        try {
            if ("filters".equals(action)) {
                return filters(req);
            } else if ("counts".equals(action)) {
                return counts(req);
            } else if ("ping".equals(action)) {
                out.put("ok", true);
                out.put("module", "phu360reporting");
                return out;
            } else {
                out.put("error", "unknown action: " + action);
                return out;
            }
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", e.getMessage() == null ? String.valueOf(e.getClass().getSimpleName()) : e.getMessage());
            return err;
        }
    }

    private Map<String, Object> filters(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Session sess = openSession();
        try {
            List<Map<String, Object>> locations = new ArrayList<Map<String, Object>>();
            List<Map<String, Object>> types = new ArrayList<Map<String, Object>>();
            /* Only the district health centers belong in the filter bar: the facility
               roots (parent_location IS NULL) named "CHC", which for this deployment are
               Falaba CHC, Mongo Bendugu CHC and Sinkunia CHC. Departments, wards and
               pharmacies hang off these roots and are covered by subtree() when one is
               selected. Widen the match (or switch to a location tag) to offer more. */
            List<Object[]> lrows = sess.createNativeQuery(
                "SELECT l.location_id, l.uuid, l.name FROM location l "
                + "WHERE l.retired=0 AND l.parent_location IS NULL AND UPPER(l.name) LIKE '%CHC' "
                + "ORDER BY l.name").list();
            for (Object[] r : lrows) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", ((Number) r[0]).intValue());
                m.put("uuid", String.valueOf(r[1]));
                m.put("name", String.valueOf(r[2]));
                locations.add(m);
            }
            List<Object[]> trows = sess.createNativeQuery(
                "SELECT encounter_type_id, uuid, name FROM encounter_type WHERE retired=0 ORDER BY name").list();
            for (Object[] r : trows) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", ((Number) r[0]).intValue());
                m.put("uuid", String.valueOf(r[1]));
                m.put("name", String.valueOf(r[2]));
                types.add(m);
            }
            out.put("locations", locations);
            out.put("encounterTypes", types);
        } finally {
            sess.close();
        }
        return out;
    }

    private Map<String, Object> counts(HttpServletRequest req) {
        java.util.Date from = parse(req.getParameter("from"));
        java.util.Date to = parse(req.getParameter("to"));
        if (from == null || to == null) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.MILLISECOND, 0);
            to = cal.getTime();
            cal.add(java.util.Calendar.MONTH, -11);
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
            from = cal.getTime();
        }
        if (from.after(to)) {
            java.util.Date tmp = from;
            from = to;
            to = tmp;
        }
        // make 'to' an exclusive upper bound (include the whole 'to' day)
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(to);
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        java.util.Date toExclusive = cal.getTime();

        Integer locId = null;
        String locUuid = req.getParameter("location");
        java.util.Set<Integer> locIds = null;
        if (locUuid != null && locUuid.length() > 0) {
            locId = resolveId("location", locUuid);
            if (locId != null) {
                locIds = subtree(locId);
            }
        }
        Integer etId = null;
        String etUuid = req.getParameter("encounterType");
        if (etUuid != null && etUuid.length() > 0) {
            etId = resolveId("encounter_type", etUuid);
        }

        IndicatorReport report = new IndicatorReport(from, toExclusive, locIds, etId);
        Map<String, Object> body = report.build();
        body.put("nominalTo", fmt(to));
        body.put("locationName", locUuid != null && locId != null ? nameFor("location", locId) : "All health centers");
        body.put("encounterTypeName", etUuid != null && etId != null ? nameFor("encounter_type", etId) : "All encounter types");
        return body;
    }

    private Integer resolveId(String table, String uuid) {
        Session sess = openSession();
        try {
            List rows = sess.createNativeQuery(
                "SELECT " + table + "_id FROM " + table + " WHERE uuid = :uuid AND retired=0")
                .setParameter("uuid", uuid).list();
            if (rows.isEmpty()) {
                return null;
            }
            Object o = rows.get(0);
            return ((Number) ((o instanceof Object[]) ? ((Object[]) o)[0] : o)).intValue();
        } finally {
            sess.close();
        }
    }

    /** All non-retired locations in the subtree rooted at rootId (self included). */
    private java.util.Set<Integer> subtree(int rootId) {
        java.util.Set<Integer> ids = new java.util.HashSet<Integer>();
        java.util.Map<Integer, java.util.List<Integer>> children = new java.util.HashMap<Integer, java.util.List<Integer>>();
        Session sess = openSession();
        try {
            List rows = sess.createNativeQuery(
                "SELECT location_id, parent_location FROM location WHERE retired=0").list();
            for (Object row : rows) {
                Object[] arr = (Object[]) row;
                int id = ((Number) arr[0]).intValue();
                if (arr[1] == null) {
                    continue;
                }
                int parent = ((Number) arr[1]).intValue();
                java.util.List<Integer> kids = children.get(parent);
                if (kids == null) {
                    kids = new java.util.ArrayList<Integer>();
                    children.put(parent, kids);
                }
                kids.add(id);
            }
        } finally {
            sess.close();
        }
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<Integer>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            int id = queue.poll();
            if (!ids.add(id)) {
                continue;
            }
            for (Integer kid : children.getOrDefault(id, java.util.Collections.<Integer>emptyList())) {
                queue.add(kid);
            }
        }
        return ids;
    }

    private String nameFor(String table, int id) {
        Session sess = openSession();
        try {
            List rows = sess.createNativeQuery(
                "SELECT name FROM " + table + " WHERE " + table + "_id = :id").setParameter("id", id).list();
            if (rows.isEmpty()) {
                return null;
            }
            Object o = rows.get(0);
            return String.valueOf(o instanceof Object[] ? ((Object[]) o)[0] : o);
        } finally {
            sess.close();
        }
    }

    private Session openSession() {
        SessionFactory sf = Context.getRegisteredComponent("sessionFactory", SessionFactory.class);
        return sf.openSession();
    }

    private java.util.Date parse(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(s);
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    private String fmt(java.util.Date d) {
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    private void send(HttpServletResponse resp, int status, String type, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType(type);
        resp.setCharacterEncoding("UTF-8");
        PrintWriter w = resp.getWriter();
        w.write(body);
        w.flush();
    }
}