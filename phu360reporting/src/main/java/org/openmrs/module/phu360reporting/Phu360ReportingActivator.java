package org.openmrs.module.phu360reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.openmrs.module.phu360reporting.report.IndicatorRefresher;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.appframework.domain.AppDescriptor;
import org.openmrs.module.appframework.domain.Extension;
import org.openmrs.module.appframework.service.AppFrameworkService;

/**
 * Starts the reporting module: trims the Program Dashboards page down to the two
 * PHU360 dashboards, and keeps the per-report-type indicator tables up to date.
 */
public class Phu360ReportingActivator extends BaseModuleActivator {

    private final Log log = LogFactory.getLog(getClass());

    /** The extension point pihcore hangs the per-program dashboard links off. */
    private static final String PROGRAM_SUMMARY_LIST_APPS = "pih.app.programSummaryList.apps";

    /**
     * pihcore's CustomAppLoaderUtil.addToProgramSummaryListPage() creates one
     * "<program app id>.appLink" extension for every Program, pointed at
     * pih.app.programSummaryList.apps. The program app ids themselves end in
     * ".programSummary.dashboard", so those extension ids end in
     * ".programSummary.dashboard.appLink" - the AYFS / Family Planning /
     * Gynecology / PMTCT / ... tiles, none of which PHU360 uses.
     *
     * The extensions belong to each per-program app, not to the
     * pih.app.programSummaryList app, so every app has to be visited.
     */
    private static final String PROGRAM_DASHBOARD_LINK_SUFFIX = ".programSummary.dashboard.appLink";

    private ScheduledExecutorService refresher;

    @Override
    public void started() {
        log.info("PHU360 Reporting module started");
        removeProgramDashboardTiles();
        startIndicatorRefresh();
    }

    @Override
    public void stopped() {
        stopIndicatorRefresh();
        log.info("PHU360 Reporting module stopped");
    }

    /**
     * Brings the indicator tables up to date now, then every
     * {@link IndicatorRefresher#REFRESH_INTERVAL_MINUTES} minutes.
     *
     * <p>OpenMRS's {@code scheduler} module is not installed, so this runs on the
     * module's own timer rather than through {@code SchedulerService}. The first
     * refresh is done on a background thread: it walks a rolling window of daily
     * facts for every report type, and doing that inline would hold up startup for
     * every other module behind this one in the queue.
     */
    private void startIndicatorRefresh() {
        stopIndicatorRefresh();
        refresher = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "phu360reporting-refresh");
                // A daemon thread, so a missed shutdown cannot hold up the JVM.
                t.setDaemon(true);
                return t;
            }
        });
        final long period = IndicatorRefresher.REFRESH_INTERVAL_MINUTES;
        refresher.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    IndicatorRefresher.refreshAll();
                }
                catch (RuntimeException e) {
                    // Never let one run kill the schedule.
                    log.error("PHU360: indicator refresh failed", e);
                }
            }
        }, 0L, period, TimeUnit.MINUTES);
        log.info("PHU360: indicator tables refreshing every " + period + " minutes");
    }

    private void stopIndicatorRefresh() {
        if (refresher == null) {
            return;
        }
        refresher.shutdownNow();
        refresher = null;
    }

    /**
     * Drops the per-program dashboard links from the Program Dashboards page,
     * leaving only the two dashboards in
     * configuration/appframework/program_dashboards_extension.json.
     *
     * This cannot be done from config: pihcore builds those extensions in Java
     * during its own startup (CustomAppLoaderUtil.addToProgramSummaryListPage)
     * and the appframework has no way to unregister an extension that a module
     * added in code. Apps live only in memory - there is no app/extension table
     * - so editing the descriptors is enough to change what the page renders:
     * AppFrameworkServiceImpl.getAllEnabledExtensions() walks every app's
     * getExtensions() and keeps the ones matching the requested extension point.
     *
     * pihcore is a required module (module/config.xml), so its activator has
     * finished registering apps before this runs. The retry is only a safety net
     * in case a future pihcore defers registration to a background thread.
     */
    private void removeProgramDashboardTiles() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                AppFrameworkService service = Context.getService(AppFrameworkService.class);
                List<AppDescriptor> apps = service.getAllApps();
                if (apps == null || apps.isEmpty()) {
                    log.warn("PHU360: no apps registered yet (attempt " + attempt + ")");
                }
                else {
                    int removed = 0;
                    for (AppDescriptor app : apps) {
                        List<Extension> extensions = app.getExtensions();
                        if (extensions == null || extensions.isEmpty()) {
                            continue;
                        }
                        List<Extension> kept = new ArrayList<Extension>();
                        for (Extension extension : extensions) {
                            if (isProgramDashboardLink(extension)) {
                                removed++;
                            }
                            else {
                                kept.add(extension);
                            }
                        }
                        if (kept.size() != extensions.size()) {
                            app.setExtensions(kept);
                        }
                    }
                    if (removed > 0) {
                        log.info("PHU360: removed " + removed + " per-program dashboard link(s) from "
                                + PROGRAM_SUMMARY_LIST_APPS + " across " + apps.size() + " app(s)");
                    }
                    else {
                        log.info("PHU360: no per-program dashboard links found across " + apps.size() + " app(s)");
                    }
                    return;
                }
            }
            catch (Exception e) {
                log.warn("PHU360: could not trim " + PROGRAM_SUMMARY_LIST_APPS + " (attempt " + attempt + ")", e);
            }
            try {
                Thread.sleep(2000L);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.error("PHU360: " + PROGRAM_SUMMARY_LIST_APPS
                + " still shows the per-program dashboards after 5 attempts");
    }

    private boolean isProgramDashboardLink(Extension extension) {
        if (!PROGRAM_SUMMARY_LIST_APPS.equals(extension.getExtensionPointId())) {
            return false;
        }
        String id = extension.getId();
        return id != null && id.endsWith(PROGRAM_DASHBOARD_LINK_SUFFIX);
    }
}
