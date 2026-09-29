package org.openmrs.module.phu360reporting.report;

/**
 * One indicator measurement: a value for a single day, a single health center
 * (or {@link #ALL_LOCATIONS}) and a single dimension.
 *
 * <p>Facts are the unit the reporting tables store. The dashboard needs the same
 * numbers whether they come from a live query or from the tables, so both paths
 * produce facts and a {@link FactSink} turns them into whatever the caller
 * wants - a JSON body, or table rows.
 *
 * <p>Storing one row per day rather than one row per requested range is what
 * makes the tables reusable: any date range the user picks is a SUM over the
 * days it covers, so a range that does not line up with a month boundary is
 * still exact instead of being rounded to the nearest stored period.
 */
public final class Fact {

    /**
     * The location_id stored for a fact that covers every health center.
     *
     * <p>It is stored rather than summed up from the per-center rows because
     * two of the KPIs are counted per patient (new registrations, conditions
     * recorded): a patient seen at two centers on the same day is one
     * registration, and summing the per-center rows would report two.
     */
    public static final int ALL_LOCATIONS = -1;

    private final java.util.Date day;
    private final int locationId;
    private final String dimension;
    private final String dimensionValue;
    private final String key;
    private final String label;
    private final long value;

    public Fact(java.util.Date day, int locationId, String dimension, String dimensionValue, String key, String label,
            long value) {
        this.day = day;
        this.locationId = locationId;
        this.dimension = dimension;
        this.dimensionValue = dimensionValue;
        this.key = key;
        this.label = label;
        this.value = value;
    }

    /** Midnight of the day this fact covers. */
    public java.util.Date getDay() {
        return day;
    }

    /** The health center, or {@link #ALL_LOCATIONS}. */
    public int getLocationId() {
        return locationId;
    }

    /** kpi, type, sex or age. */
    public String getDimension() {
        return dimension;
    }

    /** The label within the dimension, or null for the KPI dimension. */
    public String getDimensionValue() {
        return dimensionValue;
    }

    /** The stable identifier the dashboard and the tables agree on. */
    public String getKey() {
        return key;
    }

    /** The human-readable label to render. */
    public String getLabel() {
        return label;
    }

    public long getValue() {
        return value;
    }
}
