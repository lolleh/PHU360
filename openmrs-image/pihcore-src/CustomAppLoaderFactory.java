/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.commons.lang.StringUtils
 *  org.apache.commons.logging.Log
 *  org.apache.commons.logging.LogFactory
 *  org.codehaus.jackson.node.ArrayNode
 *  org.codehaus.jackson.node.ObjectNode
 *  org.openmrs.api.context.Context
 *  org.openmrs.module.ModuleFactory
 *  org.openmrs.module.appframework.domain.AppDescriptor
 *  org.openmrs.module.appframework.domain.AppTemplate
 *  org.openmrs.module.appframework.domain.Extension
 *  org.openmrs.module.appframework.factory.AppFrameworkFactory
 *  org.openmrs.module.appframework.feature.FeatureToggleProperties
 *  org.openmrs.module.pihcore.PihCoreUtil
 *  org.openmrs.module.pihcore.apploader.CustomAppLoaderUtil
 *  org.openmrs.module.pihcore.apploader.RequireUtil
 *  org.openmrs.module.pihcore.apploader.apps.GraphFactory
 *  org.openmrs.module.pihcore.apploader.apps.patientregistration.PatientRegistrationApp
 *  org.openmrs.module.pihcore.config.Config
 *  org.openmrs.module.pihcore.config.ConfigDescriptor$Country
 *  org.openmrs.module.pihcore.config.ConfigDescriptor$Specialty
 *  org.openmrs.module.reporting.config.ReportDescriptor
 *  org.openmrs.module.reporting.config.ReportLoader
 *  org.openmrs.ui.framework.WebConstants
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.stereotype.Component
 */
package org.openmrs.module.pihcore.apploader;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.openmrs.api.context.Context;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.appframework.domain.AppDescriptor;
import org.openmrs.module.appframework.domain.AppTemplate;
import org.openmrs.module.appframework.domain.Extension;
import org.openmrs.module.appframework.factory.AppFrameworkFactory;
import org.openmrs.module.appframework.feature.FeatureToggleProperties;
import org.openmrs.module.pihcore.PihCoreUtil;
import org.openmrs.module.pihcore.apploader.CustomAppLoaderUtil;
import org.openmrs.module.pihcore.apploader.RequireUtil;
import org.openmrs.module.pihcore.apploader.apps.GraphFactory;
import org.openmrs.module.pihcore.apploader.apps.patientregistration.PatientRegistrationApp;
import org.openmrs.module.pihcore.config.Config;
import org.openmrs.module.pihcore.config.ConfigDescriptor;
import org.openmrs.module.reporting.config.ReportDescriptor;
import org.openmrs.module.reporting.config.ReportLoader;
import org.openmrs.ui.framework.WebConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component(value="customAppLoaderFactory")
public class CustomAppLoaderFactory
implements AppFrameworkFactory {
    private final Log log = LogFactory.getLog(this.getClass());
    private Config config;
    private FeatureToggleProperties featureToggles;
    private PatientRegistrationApp patientRegistrationApp;
    private GraphFactory graphs;
    private List<AppDescriptor> apps = new ArrayList<AppDescriptor>();
    private List<Extension> extensions = new ArrayList<Extension>();
    private Boolean readyForRefresh = false;
    private String patientEncountersPageUrl = "";
    private String patientVisitsPageUrl = "";
    private String patientVisitsPageWithSpecificVisitUrl = "";
    private String labOrderListPage = "pihcore/router/labRouter.page";
    private String patientLabResultsPage = "pihcore/router/labRouter.page?patient={{patient.uuid}}";

    @Autowired
    public CustomAppLoaderFactory(Config config, FeatureToggleProperties featureToggles, PatientRegistrationApp patientRegistrationApp, GraphFactory graphs) {
        this.config = config;
        this.featureToggles = featureToggles;
        this.patientRegistrationApp = patientRegistrationApp;
        this.graphs = graphs;
    }

    public void reloadAllAppsAndExtensions() {
        this.setReadyForRefresh(true);
        this.apps = new ArrayList<AppDescriptor>();
        this.extensions = new ArrayList<Extension>();
        ModuleFactory.getStartedModuleById((String)"appframework").getModuleActivator().contextRefreshed();
    }

    public List<AppDescriptor> getAppDescriptors() throws IOException {
        if (this.readyForRefresh.booleanValue()) {
            this.loadAppsAndExtensions();
        }
        return this.apps;
    }

    public List<Extension> getExtensions() throws IOException {
        if (this.readyForRefresh.booleanValue()) {
            this.loadAppsAndExtensions();
        }
        return this.extensions;
    }

    public List<AppTemplate> getAppTemplates() throws IOException {
        return null;
    }

    private String addParametersToUrl(String url, Map<String, String> parameters) {
        String urlParams = null;
        if (StringUtils.isNotBlank((String)url) && parameters != null && parameters.size() > 0) {
            int separatorIndex = url.indexOf("?");
            StringBuilder sb = new StringBuilder().append(url.substring(0, separatorIndex)).append("?");
            for (String param : parameters.keySet()) {
                String value = parameters.get(param);
                sb.append(param).append("=").append(value).append("&");
            }
            sb.append(url.substring(separatorIndex + 1));
            urlParams = sb.toString();
        }
        return urlParams;
    }

    private void loadAppsAndExtensions() throws UnsupportedEncodingException {
        this.configureHeader(this.config);
        this.configureAuthenticationUi(this.config);
        this.setupDefaultEncounterTemplates();
        if (this.config.isComponentEnabled("visitNote").booleanValue()) {
            this.patientVisitsPageUrl = "/pihcore/visit/visit.page?patient={{patient.uuid}}#/visitList";
            this.patientVisitsPageWithSpecificVisitUrl = "/pihcore/visit/visit.page?patient={{patient.uuid}}&visit={{visit.uuid}}#/overview";
        } else {
            this.patientVisitsPageUrl = "/coreapps/patientdashboard/patientDashboard.page?patientId={{patient.patientId}}";
            this.patientVisitsPageWithSpecificVisitUrl = this.patientVisitsPageUrl + "&visitId={{visit.visitId}}";
        }
        this.patientEncountersPageUrl = "/pihcore/visit/visit.page?patient={{patient.uuid}}#/encounterList";
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"pihcore.encounterList", (String)"pihcore.encounterList", (String)"fas fa-fw fa-file", (String)"link", (String)"pihcore/patient/encounterList.page?patientId={{patient.uuid}}", null, null));
        if (this.config.isComponentEnabled("visitManagement").booleanValue()) {
            this.enableVisitManagement();
        }
        if (this.config.isComponentEnabled("activeVisits").booleanValue()) {
            this.enableActiveVisits();
        }
        if (this.config.isComponentEnabled("checkIn").booleanValue()) {
            this.enableCheckIn(this.config);
        }
        if (this.config.isComponentEnabled("mchTriage").booleanValue()) {
            this.enableMCHTriage(this.config);
        }
        if (this.config.isComponentEnabled("uhmVitals").booleanValue() || this.config.isComponentEnabled("vitals").booleanValue()) {
            this.enableVitals();
        }
        if (this.config.isComponentEnabled("consult").booleanValue()) {
            this.enableConsult();
        }
        if (this.config.isComponentEnabled("consultInitial").booleanValue()) {
            this.enableConsultInitial();
        }
        if (this.config.isComponentEnabled("nurseConsult").booleanValue()) {
            this.enableNurseConsult();
        }
        if (this.config.isComponentEnabled("edConsult").booleanValue()) {
            this.enableEDConsult();
        }
        if (this.config.isComponentEnabled("adt").booleanValue()) {
            this.enableADT();
        }
        if (this.config.isComponentEnabled("adtO3").booleanValue()) {
            this.enableADTO3();
        }
        if (this.config.isComponentEnabled("bedAdministration").booleanValue()) {
            this.enableBedAdministration();
        }
        if (this.config.isComponentEnabled("deathCertificate").booleanValue()) {
            this.enableDeathCertificate();
        }
        if (this.config.isComponentEnabled("radiology").booleanValue()) {
            this.enableRadiology();
        }
        if (this.config.isComponentEnabled("dispensing").booleanValue()) {
            this.enableDispensing();
        }
        if (this.config.isComponentEnabled("dispensingForm").booleanValue()) {
            this.enableDispensingForm();
        }
        if (this.config.isComponentEnabled("medicationDispensing").booleanValue()) {
            this.enableMedicationDispensing();
        }
        if (this.config.isComponentEnabled("medicationOrder").booleanValue()) {
            this.enableMedicationOrder();
        }
        if (this.config.isComponentEnabled("appointments").booleanValue()) {
            this.enableAppointments();
        }
        if (this.config.isComponentEnabled("surgery").booleanValue()) {
            this.enableSurgery();
        }
        if (this.config.isComponentEnabled("labResults").booleanValue()) {
            this.enableLabResults();
        }
        if (this.config.anyComponentEnabled(Arrays.asList("overviewReports", "monitoringReports", "dataExports")).booleanValue()) {
            this.enableReportsAndExports();
        }
        if (this.config.isComponentEnabled("archives").booleanValue()) {
            this.enableArchives();
        }
        if (this.config.isComponentEnabled("wristbands").booleanValue()) {
            this.enableWristbands();
        }
        if (this.config.isComponentEnabled("children").booleanValue()) {
            this.enableChildren();
        }
        if (this.config.isComponentEnabled("appointmentScheduling").booleanValue()) {
            this.enableAppointmentScheduling();
        }
        if (this.config.isComponentEnabled("systemAdministration").booleanValue()) {
            this.enableSystemAdministration();
        }
        if (this.config.isComponentEnabled("managePrinters").booleanValue()) {
            this.enableManagePrinters();
        }
        if (this.config.isComponentEnabled("myAccount").booleanValue()) {
            this.enableMyAccount();
        }
        if (this.config.isComponentEnabled("patientRegistration").booleanValue()) {
            this.enablePatientRegistration();
        }
        if (this.config.isComponentEnabled("lacollinePatientRegistrationEncounterTypes").booleanValue()) {
            this.registerLacollinePatientRegistrationEncounterTypes();
        }
        if (this.config.isComponentEnabled("clinicianDashboard").booleanValue()) {
            this.enableClinicianDashboard();
        }
        if (this.config.isComponentEnabled("allergies").booleanValue()) {
            this.enableAllergies();
        }
        if (this.config.isComponentEnabled("chartSearch").booleanValue()) {
            this.enableChartSearch();
        }
        if (this.config.isComponentEnabled("waitingForConsult").booleanValue()) {
            this.enableWaitingForConsult();
        }
        if (this.config.isComponentEnabled("primaryCare").booleanValue()) {
            this.enablePrimaryCare();
        }
        if (this.config.isComponentEnabled("familyMedicine").booleanValue()) {
            this.enableFamilyMedicine();
        }
        if (this.config.isComponentEnabled("edTriage").booleanValue()) {
            this.enableEDTriage();
        }
        if (this.config.isComponentEnabled("edTriageQueue").booleanValue()) {
            this.enableEDTriageQueue();
        }
        if (this.config.isComponentEnabled("biometricsFingerPrints").booleanValue()) {
            this.enableBiometrics(this.config);
        }
        if (this.config.isComponentEnabled("todaysVisits").booleanValue()) {
            this.enableTodaysVisits();
        }
        if (this.config.isComponentEnabled("pathologyTracking").booleanValue()) {
            this.enablePathologyTracking();
        }
        if (this.config.isComponentEnabled("labs").booleanValue()) {
            this.enableLabs();
        }
        if (this.config.isComponentEnabled("growthChart").booleanValue()) {
            this.enableGrowthChart();
        }
        if (this.config.isComponentEnabled("relationships").booleanValue()) {
            this.enableRelationships();
        }
        if (this.config.isComponentEnabled("providerRelationships").booleanValue()) {
            this.enableProviderRelationships();
        }
        if (this.config.isComponentEnabled("exportPatients").booleanValue()) {
            this.enableExportPatients();
        }
        if (this.config.isComponentEnabled("importPatients").booleanValue()) {
            this.enableImportPatients();
        }
        if (this.config.isComponentEnabled("patientDocuments").booleanValue()) {
            this.enablePatientDocuments();
        }
        if (this.config.isComponentEnabled("conditionList").booleanValue()) {
            this.enableConditionList();
        }
        if (this.config.isComponentEnabled("vct").booleanValue()) {
            this.enableVCT();
        }
        if (this.config.isComponentEnabled("socioEconomics").booleanValue()) {
            this.enableSocioEconomics();
        }
        if (this.config.isComponentEnabled("cohortBuilder").booleanValue()) {
            this.enableCohortBuilder();
        }
        if (this.config.isComponentEnabled("chemotherapy").booleanValue()) {
            this.enableChemotherapy();
        }
        if (this.config.isComponentEnabled("mchForms").booleanValue()) {
            this.enableMCHForms();
        }
        if (this.config.isComponentEnabled("j9").booleanValue()) {
            this.enableJ9();
        }
        if (this.config.isComponentEnabled("mchGAINMaternal").booleanValue()) {
            this.enableMCHGainMaternal();
        }
        if (this.config.isComponentEnabled("mchGAINNewborn").booleanValue()) {
            this.enableMCHGainNewborn();
        }
        if (this.config.isComponentEnabled("mcoeForms").booleanValue()) {
            this.enableMCOEForms();
        }
        if (this.config.isComponentEnabled("covid19").booleanValue()) {
            this.enableCovid19();
        }
        if (this.config.isComponentEnabled("covid19IntakeForm").booleanValue()) {
            this.enableCovid19IntakeForm();
        }
        if (this.config.isComponentEnabled("tb").booleanValue()) {
            this.enableTuberculosis();
        }
        if (this.config.isComponentEnabled("markPatientDead").booleanValue()) {
            this.enableMarkPatientDead();
        }
        if (this.config.isComponentEnabled("programs").booleanValue()) {
            this.enablePrograms(this.config);
        }
        if (this.config.isComponentEnabled("commentForm").booleanValue()) {
            this.enableCommentForm();
        }
        if (this.config.isComponentEnabled("spaPreview").booleanValue()) {
            this.enableSpaPreview();
        }
        if (this.config.isComponentEnabled("physicalRehab").booleanValue()) {
            this.enablePhysicalRehab();
        }
        if (this.config.isComponentEnabled("drugRehab").booleanValue()) {
            this.enableDrugRehab();
        }
        if (this.config.isComponentEnabled("prescription").booleanValue()) {
            this.enablePrescription();
        }
        if (StringUtils.isNotBlank((String)PihCoreUtil.getSystemOrRuntimeProperty((String)"haiti_hiv_emr_link_url"))) {
            this.enableHaitiHIVLink();
        }
        this.configureAdditionalExtensions(this.config);
        this.readyForRefresh = false;
    }

    private void configureHeader(Config config) {
        this.extensions.add(CustomAppLoaderUtil.header((String)"pih.header", (String)"/ms/uiframework/resource/file/configuration/pih/logo/logo.png"));
    }

    private void configureAuthenticationUi(Config config) {
        this.extensions.add(CustomAppLoaderUtil.configExtension((String)"pih.authenticationui.admin", (String)"org.openmrs.module.authenticationui.admin.config", (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"admin-page-url", "coreapps:systemadministration/systemAdministration", "manage-users-page-url", "pihapps:account/manageAccounts", "admin-edit-user-page-url", "pihapps:account/account", "required-privilege", "App: coreapps.systemAdministration", "phone-number-person-attribute-type", "14d4f066-15f5-102d-96e4-000c29c2a5d7", "default-location-user-property", ""})));
        this.extensions.add(CustomAppLoaderUtil.configExtension((String)"pih.authenticationui.login", (String)"org.openmrs.module.authenticationui.loginPage.config", (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"show-locations", false, "require-location", false, "welcome-message", config.getWelcomeMessage(), "warning-if-not-chrome", config.getBrowserWarning(), "allow-password-reset", true, "show-remember-me", true})));
    }

    private void setupDefaultEncounterTemplates() {
        this.extensions.add(CustomAppLoaderUtil.encounterTemplate((String)"defaultEncounterTemplate", (String)"coreapps", (String)"patientdashboard/encountertemplate/defaultEncounterTemplate"));
        this.extensions.add(CustomAppLoaderUtil.encounterTemplate((String)"noDetailsEncounterTemplate", (String)"coreapps", (String)"patientdashboard/encountertemplate/noDetailsEncounterTemplate"));
        this.extensions.add(CustomAppLoaderUtil.encounterTemplate((String)"edtriageEncounterTemplate", (String)"edtriageapp", (String)"edtriageEncounterTemplate"));
    }

    private void enableVisitManagement() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"coreapps.createVisit", (String)"coreapps.task.startVisit.label", (String)"fas fa-fw icon-check-in", (String)"script", (String)"visit.showQuickVisitCreationDialog({{patient.patientId}})", (String)"Task: coreapps.createVisit", (String)RequireUtil.and((String[])new String[]{RequireUtil.patientDoesNotHaveActiveVisitAtAnyLocation(), RequireUtil.patientNotDead()})));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"coreapps.createRetrospectiveVisit", (String)"coreapps.task.createRetrospectiveVisit.label", (String)"fas fa-fw fa-plus", (String)"script", (String)"visit.showRetrospectiveVisitCreationDialog()", (String)"Task: coreapps.createRetrospectiveVisit", null));
        this.extensions.add(CustomAppLoaderUtil.fragmentExtension((String)"coreapps.patientDashboard.includes", (String)"coreapps", (String)"patientdashboard/visitIncludes", null, (String)"patientDashboard.includeFragments", (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"patientVisitsPage", this.patientVisitsPageWithSpecificVisitUrl, "visitType", "f01c54cb-2225-471a-9cd5-d348552c337c"})));
    }

    private void enableActiveVisits() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"mirebalaisreports.activeVisitsList", (String)"coreapps.activeVisits.app.label", (String)"fas fa-fw icon-check-in", (String)"pihcore/reports/activeVisitsList.page?app=pih.app.activeVisits", (String)"App: coreapps.activeVisits", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"patientPageUrl", this.patientVisitsPageWithSpecificVisitUrl}))));
    }

    private void enableMCHTriage(Config config) {
        if (config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.mchtriage.visitAction", (String)"mirebalais.task.triage.label", (String)"fas fa-fw fa-ambulance", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"triage.xml")), (String)"Task: emr.triage", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"MCH Triage Location"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()}), RequireUtil.visitDoesNotHaveEncounterOfType((String)"41911448-71a1-43d7-bba8-dc86339850da")})));
            this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.mchTriageQueue", (String)"pih.app.mchTriageQueue", (String)"fas fa-fw fa-list-ul", (String)"spa/home/service-queues/queue-table-by-status/3113f164-68f0-11ee-ab8d-0242ac120002", (String)"", null), (String)RequireUtil.sessionLocationHasTag((String)"Queue Location")));
        }
    }

    private void enableCheckIn(Config config) {
        String checkInFormName = "checkin.xml";
        if (config.isCountry(ConfigDescriptor.Country.HAITI) && !config.isSite("HSN_SAINT_MARC")) {
            checkInFormName = "checkin_v3.5.xml";
        }
        if (config.isComponentEnabled("checkInHomepageApp").booleanValue()) {
            this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.findPatientTemplateApp((String)"mirebalais.liveCheckin", (String)"mirebalais.app.patientRegistration.checkin.label", (String)"fas fa-fw fa-paste", (String)"App: mirebalais.checkin", (String)"/pihcore/checkin/checkin.page?patientId={{patientId}}", null, (ArrayNode)config.getFindPatientColumnConfig()), (String)RequireUtil.or((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Check-In Location"), RequireUtil.sessionLocationHasTag((String)"Check-In Maternal Location")})));
        }
        Extension checkIn = CustomAppLoaderUtil.visitAction((String)"pih.checkin.visitAction", (String)"mirebalais.task.checkin.label", (String)"fas fa-fw icon-check-in", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)checkInFormName)), (String)"Task: mirebalais.checkinForm", (String)RequireUtil.sessionLocationHasTag((String)"Check-In Location"));
        this.extensions.add(checkIn);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)checkIn));
        this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"pih.checkin.registrationAction", (String)"mirebalais.task.checkin.label", (String)"fas fa-fw icon-check-in", (String)"link", (String)(CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)checkInFormName)) + CustomAppLoaderUtil.andCreateVisit()), (String)"Task: mirebalais.checkinForm", (String)RequireUtil.sessionLocationHasTag((String)"Check-In Location")));
        if (config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            Extension checkInMaternal = CustomAppLoaderUtil.visitAction((String)"pih.checkinMaternal.visitAction", (String)"mirebalais.checkin.title", (String)"fas fa-fw icon-check-in", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"checkinMaternal.xml")), (String)"Task: mirebalais.checkinForm", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Check-In Maternal Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"251c03fa-a9dc-4157-855f-b016f4fae9ab")}));
            this.extensions.add(checkInMaternal);
            this.extensions.add(CustomAppLoaderUtil.cloneAsPregnancyVisitAction((Extension)checkInMaternal));
            this.extensions.add(CustomAppLoaderUtil.cloneAsInfantVisitAction((Extension)checkInMaternal));
            Extension checkInMaternalLoc = CustomAppLoaderUtil.overallRegistrationAction((String)"pih.checkinMaternal.registrationAction", (String)"mirebalais.checkin.title", (String)"fas fa-fw icon-check-in", (String)"link", (String)(CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"checkinMaternal.xml")) + CustomAppLoaderUtil.andCreateVisit()), (String)"Task: mirebalais.checkinForm", (String)RequireUtil.sessionLocationHasTag((String)"Check-In Maternal Location"));
            this.extensions.add(checkInMaternalLoc);
        }
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"55a0d3ea-a4d7-4e88-8f01-5aceb2d3c61b", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw icon-check-in", (Boolean)true, (Boolean)true, (String)CustomAppLoaderUtil.editSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"checkin.xml")), null);
    }

    private void enableVitals() {
        if (this.config.isComponentEnabled("vitalsHomepageApp").booleanValue()) {
            if (this.config.isComponentEnabled("uhmVitals").booleanValue()) {
                this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.findPatientTemplateApp((String)"pih.uhm.app.vitals", (String)"mirebalais.outpatientVitals.title", (String)"fas fa-fw fa-heartbeat", (String)"App: mirebalais.outpatientVitals", (String)"/pihcore/outpatientvitals/patient.page?patientId={{patientId}}", null, (ArrayNode)this.config.getFindPatientColumnConfig()), (String)RequireUtil.sessionLocationHasTag((String)"Vitals Location")));
            } else {
                this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.vitals", (String)"pihcore.vitalsList.title", (String)"fas fa-fw fa-heartbeat", (String)"/pihcore/vitals/vitalsList.page", (String)"App: mirebalais.outpatientVitals", null), (String)RequireUtil.sessionLocationHasTag((String)"Vitals Location")));
            }
        }
        Extension vitalSigns = CustomAppLoaderUtil.visitAction((String)"pih.form.vitals", (String)"mirebalais.task.vitals.label", (String)"fas fa-fw fa-heartbeat", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"vitals.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Vitals Location"), RequireUtil.sessionLocationDoesNotHaveTag((String)"Vitals Inpatient Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterVitalsNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
        this.extensions.add(vitalSigns);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)vitalSigns));
        this.extensions.add(CustomAppLoaderUtil.cloneAsPregnancyVisitAction((Extension)vitalSigns));
        this.extensions.add(CustomAppLoaderUtil.cloneAsInfantVisitAction((Extension)vitalSigns));
        if (!this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            AppDescriptor mostRecentVitals = CustomAppLoaderUtil.app((String)"coreapps.mostRecentVitals", (String)"mirebalais.mostRecentVitals.label", (String)"fas fa-fw fa-heartbeat", null, (String)"App: mirebalais.outpatientVitals", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "mirebalais.mostRecentVitals.encounterDateLabel", "encounterTypeUuid", "4fb47712-34a6-40d2-8ed3-e153abbd25b7", "editable", Boolean.TRUE, "edit-provider", "htmlformentryui", "edit-fragment", "htmlform/editHtmlFormWithSimpleUi", "definitionUiResource", PihCoreUtil.getFormResource((String)"vitals.xml"), "returnUrl", "/" + WebConstants.CONTEXT_PATH + "/" + this.config.getDashboardUrl()}));
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardSecondColumn((AppDescriptor)mostRecentVitals, (String)"coreapps", (String)"encounter/mostRecentEncounter"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.vitals.summary", (String)"mirebalais.vitalsTrend.label", (String)"fas fa-fw fa-heartbeat", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-fw fa-heartbeat", "label", "mirebalais.vitalsTrend.label", "encounterTypes", "4fb47712-34a6-40d2-8ed3-e153abbd25b7,2fd151a2-fbef-43e3-b82d-c3f70f1d7333,6b1c3929-f23c-423f-8cad-64b17cf10b99,ac5ec970-31b7-4659-9141-284bfbc13c69,ce67f875-726f-4314-abd5-6bcaec10d273,41911448-71a1-43d7-bba8-dc86339850da,37f04ddf-9653-4a02-98b4-1c23734c2f15,093b6ffc-e55a-461a-85cc-c6acf7714a23,153d3182-c76f-4047-b7f2-d83cf967b206,7e42e652-89e7-4559-80fd-41f42826c98c,6444b8d4-407d-444d-aa15-d6dff204ed83,fb4318dd-cca9-4d20-bb31-0d4eba947d74,6f2df970-6e3e-4898-9a34-9d9e8955c3eb,b7a7c300-f7e5-4d38-a388-fc178ab02e78", "detailsUrl", this.patientVisitsPageUrl, "headers", "zl.date,mirebalais.vitals.systolic.bp.short.title,mirebalais.vitals.diastolic.bp.short.title,mirebalais.vitals.short.heartRate.title,mirebalais.vitals.respiratoryRate.short.title,mirebalais.vitals.short.temperature.title,mirebalais.vitals.weight.short.title", "concepts", "3ce934fa-26fe-102b-80cb-0017a47871b2,3ce93694-26fe-102b-80cb-0017a47871b2,3ce93824-26fe-102b-80cb-0017a47871b2,3ceb11f8-26fe-102b-80cb-0017a47871b2,3ce939d2-26fe-102b-80cb-0017a47871b2,3ce93b62-26fe-102b-80cb-0017a47871b2", "maxRecords", "5", "sortOrder", "desc"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
            Extension inpatientVitalsSigns = CustomAppLoaderUtil.visitAction((String)"pih.form.inpatient.vitals", (String)"pihcore.task.inpatient.vitals", (String)"fas fa-fw fa-heartbeat", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"inpatientVitals.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.patientAgeInDaysOlderThanAtVisitStart((int)41), RequireUtil.sessionLocationHasTag((String)"Vitals Inpatient Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterVitalsNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
            this.extensions.add(inpatientVitalsSigns);
        }
        if (this.config.isCountry(ConfigDescriptor.Country.HAITI)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.vitalsInpatient", (String)"pihcore.task.vitalsInpatient", (String)"fas fa-fw fa-heartbeat", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"vitalsInpatient.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Vitals Inpatient Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterVitalsNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
            Extension vitalsPregnancy = CustomAppLoaderUtil.visitAction((String)"pih.form.vitalsPregnancy", (String)"pihcore.task.vitalsPregnancy", (String)"fas fa-fw fa-heartbeat", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"vitalsPregnant.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Vitals ANC Location"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()}), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterVitalsNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
            this.extensions.add(vitalsPregnancy);
            this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)vitalsPregnancy));
        }
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"4fb47712-34a6-40d2-8ed3-e153abbd25b7", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-heartbeat", null, (Boolean)true, (String)CustomAppLoaderUtil.editSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"vitals.xml")), null);
    }

    private void enableConsult() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.consult", (String)"coreapps.clinic.consult.title", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"outpatientConsult.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
        this.extensions.add(CustomAppLoaderUtil.encounterTemplate((String)"consultEncounterTemplate", (String)"pihcore", (String)"patientdashboard/encountertemplate/consultEncounterTemplate"));
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"92fd09b4-5335-4f7e-9f63-b2a663fd09a6", (Extension)this.findExtensionById("consultEncounterTemplate"), (String)"fas fa-fw fa-stethoscope", null, (Boolean)true, null, null);
    }

    private void enableConsultInitial() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.consultInitial", (String)"ui.i18n.EncounterType.name.59e55ca8-f3de-4d1e-b54c-9b880405b853", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"outpatientConsultInitial.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.not((String)RequireUtil.patientHasPreviousEncounter((String)"59e55ca8-f3de-4d1e-b54c-9b880405b853"))})));
    }

    private void enableNurseConsult() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.nurseConsult", (String)"ui.i18n.EncounterType.name.e920911f-dee9-4b18-9a58-50e685806ea0", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"nurseConsult.xml")), null, (String)RequireUtil.sessionLocationHasTag((String)"Consult Note Location")));
    }

    private void enablePrescription() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.prescription", (String)"ui.i18n.EncounterType.name.03546c8c-0c2c-4a58-80a0-0e5d112464ac", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"prescription.xml")), null, (String)RequireUtil.sessionLocationHasTag((String)"Consult Note Location")));
    }

    private void enableEDConsult() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.edConsult", (String)"coreapps.ed.consult.title", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"edNote.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"ED Note Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterEDNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
    }

    private void enableADT() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.app.awaitingAdmission", (String)"coreapps.app.awaitingAdmission.label", (String)"fas fa-fw fa-list-ul", (String)"pihcore/adt/awaitingAdmission.page?app=coreapps.app.awaitingAdmission", (String)"App: coreapps.awaitingAdmission", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"patientPageUrl", this.config.getDashboardUrl()}))));
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"mirebalaisreports.inpatients", (String)"mirebalaisreports.app.inpatients.label", (String)"fas fa-fw fa-hospital", (String)"pihcore/adt/inpatientList.page", (String)"App: emr.inpatients", null), (String)RequireUtil.sessionLocationHasTag((String)"Inpatients App Location")));
        this.extensions.add(CustomAppLoaderUtil.awaitingAdmissionAction((String)"pih.form.admit", (String)"mirebalais.task.admit.label", (String)"fas fa-fw fa-hospital-symbol", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"admissionNote.xml") + "&returnProvider=pihcore&returnPage=adt/awaitingAdmission&returnLabel=coreapps.app.awaitingAdmission.label")), (String)"Task: emr.enterAdmissionNote", null));
        this.extensions.add(CustomAppLoaderUtil.awaitingAdmissionAction((String)"pih.form.deny", (String)"uicommons.cancel", (String)"fas fa-fw fa-user-minus", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"cancelAdmission.xml") + "&returnProvider=pihcore&returnPage=adt/awaitingAdmission")), (String)"Task: emr.enterAdmissionNote", null));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.admission", (String)"mirebalais.task.admit.label", (String)"fas fa-fw fa-hospital-symbol", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"admissionNote.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Admission Note Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterAdmissionNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"260566e1-c909-4d61-a96f-c1019291a09d", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-sign-in-alt", null, (Boolean)true, null, null);
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"edbb857b-e736-4296-9438-462b31f97ef9", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-ban", (Boolean)true, (Boolean)true, null, null);
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"436cfe33-6b81-40ef-a455-f134a9f7e580", (Extension)this.findExtensionById("noDetailsEncounterTemplate"), (String)"fas fa-fw fa-share", null, (Boolean)true, null, null);
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"b6631959-2105-49dd-b154-e1249e0fbcd7", (Extension)this.findExtensionById("noDetailsEncounterTemplate"), (String)"fas fa-fw fa-sign-out-alt", null, (Boolean)true, null, null);
    }

    private void enableADTO3() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.inpatientWard", (String)"pih.app.inpatientWard", (String)"fas fa-fw fa-hospital", (String)"spa/home/ward", (String)"App: emr.inpatients", null), (String)RequireUtil.sessionLocationHasTag((String)"Admission Location")));
    }

    private void enableBedAdministration() {
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.bedAdministration", (String)"pih.app.bedAdministration", (String)"fas fa-fw fa-bed", (String)"owa/bedmanagement/admissionLocations.html", (int)15, (String)"App: coreapps.systemAdministration", null)));
    }

    private void enableDeathCertificate() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"pih.haiti.deathCertificate", (String)"mirebalais.deathCertificate.death_certificate", (String)"fas fa-fw fa-times-circle", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLinkWithoutVisit((String)PihCoreUtil.getFormResource((String)"deathCertificate.xml")), (String)"Task: mirebalais.enterDeathCertificate", (String)"!patient.person.dead"));
        this.extensions.add(CustomAppLoaderUtil.fragmentExtension((String)"pih.header.deathCertificate", (String)"pihcore", (String)"deathcertificate/headerLink", (String)"Task: mirebalais.enterDeathCertificate", (String)"patientHeader.deathInfo", null));
    }

    private void enableHivProgramLocationWarning() {
        this.extensions.add(CustomAppLoaderUtil.fragmentExtension((String)"pih.header.hivProgramLocationWarning", (String)"pihcore", (String)"hiv/programLocationWarning", null, (String)"patientHeader.secondLineFragments", null));
    }

    private void enableRadiology() {
        this.extensions.add(CustomAppLoaderUtil.dashboardTab((String)"radiologyapp.tab", (String)"radiologyapp.radiology.label", (String)"Task: org.openmrs.module.radiologyapp.tab", (String)"radiologyapp", (String)"radiologyTab"));
        Extension orderXray = CustomAppLoaderUtil.visitAction((String)"radiologyapp.orderXray", (String)"radiologyapp.task.order.CR.label", (String)"fas fa-fw fa-x-ray", (String)"link", (String)"radiologyapp/orderRadiology.page?patientId={{patient.uuid}}&visitId={{visit.id}}&modality=CR", null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Order Radiology Study Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: org.openmrs.module.radiologyapp.orderXray"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: org.openmrs.module.radiologyapp.retroOrder")})}));
        this.extensions.add(orderXray);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)orderXray));
        Extension orderCT = CustomAppLoaderUtil.visitAction((String)"radiologyapp.orderCT", (String)"radiologyapp.task.order.CT.label", (String)"fas fa-fw fa-x-ray", (String)"link", (String)"radiologyapp/orderRadiology.page?patientId={{patient.uuid}}&visitId={{visit.id}}&modality=Ct", null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Order Radiology Study Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: org.openmrs.module.radiologyapp.orderCT"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: org.openmrs.module.radiologyapp.retroOrder")})}));
        this.extensions.add(orderCT);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)orderCT));
        Extension orderUS = CustomAppLoaderUtil.visitAction((String)"radiologyapp.orderUS", (String)"radiologyapp.task.order.US.label", (String)"fas fa-fw fa-x-ray", (String)"link", (String)"radiologyapp/orderRadiology.page?patientId={{patient.uuid}}&visitId={{visit.id}}&modality=US", null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Order Radiology Study Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: org.openmrs.module.radiologyapp.orderUS"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: org.openmrs.module.radiologyapp.retroOrder")})}));
        this.extensions.add(orderUS);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)orderUS));
        if (this.config.isComponentEnabled("clinicianDashboard").booleanValue()) {
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"radiology.orders.app", (String)"radiologyapp.app.orders", (String)"fas fa-fw fa-camera", (String)"null", (String)"Task: org.openmrs.module.radiologyapp.tab", null), (String)"radiologyapp", (String)"radiologyOrderSection"));
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"radiology.app", (String)"coreapps.clinicianfacing.radiology", (String)"fas fa-fw fa-camera", (String)"null", (String)"Task: org.openmrs.module.radiologyapp.tab", null), (String)"radiologyapp", (String)"radiologySection"));
        }
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"1b3d1e13-f0b1-4b83-86ea-b1b1e2fb4efa", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-x-ray");
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"5b1b4a4e-0084-4137-87db-dba76c784439", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-x-ray");
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"d5ca53a7-d3b5-44ac-9aa2-1491d2a4b4e9", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-x-ray");
    }

    private void enableDispensing() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"dispensing.app", (String)"dispensing.app.label", (String)"fas fa-fw fa-pills", (String)"dispensing/findPatient.page", (String)"App: dispensing.app.dispense", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"definitionUiResource", PihCoreUtil.getFormResource((String)"dispensing.xml")})), (String)RequireUtil.sessionLocationHasTag((String)"Dispensing Location")));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"dispensing.form", (String)"dispensing.app.label", (String)"fas fa-fw fa-pills", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"dispensing.xml")), (String)"Task: mirebalais.dispensing", (String)RequireUtil.sessionLocationHasTag((String)"Dispensing Location")));
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.dispensing.summary", (String)"mirebalais.dispensing.title", (String)"fas fa-fw fa-pills", (String)"dispensing/patient.page?patientId={{patient.uuid}}", null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"maxDatesToShow", 5, "detailsUrl", "pihcore/meds/dispensingSummary.page?patientId={{patient.uuid}}"})), (String)"pihcore", (String)"dashboardwidgets/medsDispensed"));
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"8ff50dea-18a1-4609-b4c9-3f8f2d611b84", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-pills", (Boolean)true, (Boolean)true, null, (String)"bad21515-fd04-4ff6-bfcd-78456d12f168");
    }

    private void enableDispensingForm() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"dispensing.app", (String)"dispensing.app.label", (String)"fas fa-fw fa-pills", (String)"dispensing/findPatient.page", (String)"App: dispensing.app.dispense", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"definitionUiResource", PihCoreUtil.getFormResource((String)"dispensing.xml")})), (String)RequireUtil.sessionLocationHasTag((String)"Dispensing Location")));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"dispensing.form", (String)"dispensing.app.label", (String)"fas fa-fw fa-pills", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"dispensing.xml")), (String)"Task: mirebalais.dispensing", (String)RequireUtil.sessionLocationHasTag((String)"Dispensing Location")));
    }

    private void enableMedicationDispensing() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"medicationDispensing.app", (String)"pih.app.medicationDispensing.title", (String)"fas fa-fw fa-pills", (String)"spa/dispensing", (String)"App: dispensing.app.dispense", null), (String)RequireUtil.sessionLocationHasTag((String)"Dispensing Location")));
        if (!this.config.isComponentEnabled("dispensing").booleanValue()) {
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.dispensing.summary", (String)"mirebalais.dispensing.title", (String)"fas fa-fw fa-pills", (String)"dispensing/patient.page?patientId={{patient.uuid}}", null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"maxDatesToShow", 5, "detailsUrl", "pihcore/meds/dispensingSummary.page?patientId={{patient.uuid}}"})), (String)"pihcore", (String)"dashboardwidgets/medsDispensed"));
        }
    }

    private void enableMedicationOrder() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.drugOrder", (String)"pih.task.orderMed", (String)"fas fa-fw fa-pills", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"drugOrder.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Drug Order Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
    }

    private void enableAppointments() {
        if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"appointments.app", (String)"pih.app.appointments.title", (String)"fas fa-fw fa-calendar-alt", (String)"spa/home/appointments", (String)"App: appointmentschedulingui.home", null), (String)RequireUtil.sessionLocationHasTag((String)"Appointment Location")));
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.appointements.summary", (String)"pih.app.patientAppointments.title", (String)"fas fa-fw fa-calendar-alt", null, null, null), (String)"pihcore", (String)"dashboardwidgets/appointments"));
        } else {
            this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"appointments.app", (String)"pih.app.appointments.title", (String)"fas fa-fw fa-calendar-alt", (String)"spa/home/appointments", null, null), (String)RequireUtil.sessionLocationHasTag((String)"Appointment Location")));
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.appointements.summary", (String)"pih.app.patientAppointments.title", (String)"fas fa-fw fa-calendar-alt", null, null, null), (String)"pihcore", (String)"dashboardwidgets/appointments"));
        }
    }

    private void enableSurgery() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.surgicalNote", (String)"mirebalais.task.surgicalOperativeNote.label", (String)"fas fa-fw fa-paste", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"surgicalPostOpNote.xml")), (String)"Task: emr.enterSurgicalNote", (String)RequireUtil.sessionLocationHasTag((String)"Surgery Note Location")));
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"c4941dee-7a9b-4c1c-aa6f-8193e9e5e4e5", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-paste", (Boolean)true, (Boolean)true, null, (String)"9b135b19-7ebe-4a51-aea2-69a53f9383af");
    }

    private void enableReportsAndExports() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"reportingui.reports", (String)"reportingui.reportsapp.home.title", (String)"fas fa-fw fa-chart-bar", (String)"reportingui/reportsapp/home.page", (String)"App: reportingui.reports", null)));
        HashSet<ReportCategory> enabledCategories = new HashSet<ReportCategory>();
        if (this.config.isComponentEnabled("overviewReports").booleanValue()) {
            enabledCategories.add(ReportCategory.OVERVIEW);
            enabledCategories.add(ReportCategory.DAILY);
            enabledCategories.add(ReportCategory.DATA_QUALITY);
        }
        if (this.config.isComponentEnabled("monitoringReports").booleanValue()) {
            enabledCategories.add(ReportCategory.MONITORING);
        }
        if (this.config.isComponentEnabled("dataExports").booleanValue()) {
            enabledCategories.add(ReportCategory.DATA_EXPORT);
        }
        for (ReportDescriptor reportDescriptor : ReportLoader.loadReportDescriptors()) {
            boolean matchesSite;
            Map reportConfig;
            if (reportDescriptor.getConfig() == null) continue;
            ReportCategory category = null;
            Object rptCategory = reportDescriptor.getConfig().get("category");
            if (rptCategory != null) {
                if ("dataExport".equalsIgnoreCase(rptCategory.toString())) {
                    rptCategory = "DATA_EXPORT";
                }
                category = ReportCategory.valueOf(rptCategory.toString());
            }
            List components = (reportConfig = reportDescriptor.getConfig()).containsKey("components") ? (List)reportConfig.get("components") : null;
            Integer order = reportConfig.containsKey("order") ? Integer.valueOf(reportConfig.get("order").toString()) : 9999;
            List countries = reportConfig.containsKey("countries") ? (List)reportConfig.get("countries") : null;
            List sites = reportConfig.containsKey("sites") ? (List)reportConfig.get("sites") : null;
            String pageProvider = reportConfig.containsKey("pageProvider") ? (String)reportConfig.get("pageProvider") : null;
            String pagePath = reportConfig.containsKey("pagePath") ? (String)reportConfig.get("pagePath") : null;
            String privilege = reportConfig.containsKey("privilege") ? (String)reportConfig.get("privilege") : null;
            boolean matchesComponent = components == null || this.config.anyComponentEnabled(components) != false;
            boolean matchesCountry = countries == null || countries.contains(this.config.getCountry().name());
            boolean bl = matchesSite = sites == null || sites.contains(this.config.getSite());
            if (!matchesComponent || !matchesCountry || !matchesSite) continue;
            if (category == ReportCategory.OVERVIEW && enabledCategories.contains((Object)ReportCategory.OVERVIEW)) {
                this.extensions.add(CustomAppLoaderUtil.report((String)("mirebalaisreports.overview." + reportDescriptor.getKey()), (String)reportDescriptor.getName(), (String)(pageProvider == null ? "reportingui" : pageProvider), (String)(pagePath == null ? "runReport" : pagePath), (String)reportDescriptor.getUuid(), (String)(privilege == null ? "App: reportingui.reports" : privilege), (String)"org.openmrs.module.reportingui.reports.overview", (int)order, (String)("mirebalaisreports-" + reportDescriptor.getKey() + "-link")));
                continue;
            }
            if (category == ReportCategory.DAILY && enabledCategories.contains((Object)ReportCategory.DAILY)) {
                this.extensions.add(CustomAppLoaderUtil.report((String)("mirebalaisreports.dailyReports." + reportDescriptor.getKey()), (String)reportDescriptor.getName(), (String)(pageProvider == null ? "pihcore" : pageProvider), (String)(pagePath == null ? "reports/dailyReport" : pagePath), (String)reportDescriptor.getUuid(), (String)(privilege == null ? "App: reportingui.reports" : privilege), (String)"org.openmrs.module.reportingui.reports.overview", (int)order, (String)("mirebalaisreports-" + reportDescriptor.getKey() + "-link")));
                continue;
            }
            if (category == ReportCategory.DATA_QUALITY && enabledCategories.contains((Object)ReportCategory.DATA_QUALITY)) {
                this.extensions.add(CustomAppLoaderUtil.report((String)("mirebalaisreports.dataQualityReports." + reportDescriptor.getKey()), (String)reportDescriptor.getName(), (String)(pageProvider == null ? "reportingui" : pageProvider), (String)(pagePath == null ? "runReport" : pagePath), (String)reportDescriptor.getUuid(), (String)(privilege == null ? "App: reportingui.reports" : privilege), (String)"org.openmrs.module.reportingui.reports.dataquality", (int)order, (String)("mirebalaisreports-" + reportDescriptor.getKey() + "-link")));
                continue;
            }
            if (category == ReportCategory.MONITORING && enabledCategories.contains((Object)ReportCategory.MONITORING)) {
                this.extensions.add(CustomAppLoaderUtil.report((String)("mirebalaisreports.monitoring." + reportDescriptor.getKey()), (String)reportDescriptor.getName(), (String)(pageProvider == null ? "reportingui" : pageProvider), (String)(pagePath == null ? "runReport" : pagePath), (String)reportDescriptor.getUuid(), (String)(privilege == null ? "App: reportingui.reports" : privilege), (String)"org.openmrs.module.reportingui.reports.monitoring", (int)order, (String)("mirebalaisreports-" + reportDescriptor.getKey() + "-link")));
                continue;
            }
            if (category != ReportCategory.DATA_EXPORT || !enabledCategories.contains((Object)ReportCategory.DATA_EXPORT)) continue;
            this.extensions.add(CustomAppLoaderUtil.report((String)("mirebalaisreports.dataExports." + reportDescriptor.getKey()), (String)reportDescriptor.getName(), (String)(pageProvider == null ? "reportingui" : pageProvider), (String)(pagePath == null ? "runReport" : pagePath), (String)reportDescriptor.getUuid(), (String)(privilege == null ? "App: mirebalaisreports.dataexports" : privilege), (String)"org.openmrs.module.reportingui.reports.dataexport", (int)order, (String)("mirebalaisreports-" + reportDescriptor.getKey() + "-link")));
        }
        if (enabledCategories.contains((Object)ReportCategory.OVERVIEW) && (this.config.isCountry(ConfigDescriptor.Country.LIBERIA) || this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE))) {
            this.extensions.add(CustomAppLoaderUtil.extension((String)"mirebalaisreports.overview.registrationsByAge", (String)"mirebalaisreports.registrationoverview.title", null, (String)"link", (String)"pihcore/reports/registrationsByAge.page", (String)"App: reportingui.reports", null, (String)"org.openmrs.module.reportingui.reports.overview", (int)1, (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"linkId", "mirebalaisreports-registrationoverview-link"})));
            this.extensions.add(CustomAppLoaderUtil.extension((String)"mirebalaisreports.overview.checkinsByAge", (String)"mirebalaisreports.checkinoverview.title", null, (String)"link", (String)"pihcore/reports/checkInsByAge.page", (String)"App: reportingui.reports", null, (String)"org.openmrs.module.reportingui.reports.overview", (int)1, (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"linkId", "mirebalaisreports-checkinoverview-link"})));
        }
        if (enabledCategories.contains((Object)ReportCategory.DATA_EXPORT)) {
            this.extensions.add(CustomAppLoaderUtil.extension((String)"reportingui.dataExports.adHoc", (String)"reportingui.adHocAnalysis.label", null, (String)"link", (String)"reportingui/adHocManage.page", (String)"App: reportingui.adHocAnalysis", null, (String)"org.openmrs.module.reportingui.reports.dataexport", (int)9999, null));
            CustomAppLoaderUtil.addFeatureToggleToExtension((Extension)this.findExtensionById("reportingui.dataExports.adHoc"), (String)"reporting_adHocAnalysis");
        }
    }

    private void enableArchives() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"paperrecord.app.archivesRoom", (String)"paperrecord.app.archivesRoom.label", (String)"fas fa-fw fa-folder-open", (String)"paperrecord/archivesRoom.page", (String)"App: emr.archivesRoom", null)));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"paperrecord.requestPaperRecord", (String)"paperrecord.task.requestPaperRecord.label", (String)"fas fa-fw fa-folder-open", (String)"script", (String)"showRequestChartDialog()", (String)"Task: emr.requestPaperRecord", null));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"paperrecord.printIdCardLabel", (String)"paperrecord.task.printIdCardLabel.label", (String)"fas fa-fw fa-print", (String)"script", (String)"printIdCardLabel()", (String)"Task: emr.printLabels", null));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"paperrecord.printPaperFormLabel", (String)"paperrecord.task.printPaperFormLabel.label", (String)"fas fa-fw fa-print", (String)"script", (String)"printPaperFormLabel()", (String)"Task: emr.printLabels", null));
        this.addPaperRecordActionsIncludesIfNeeded();
    }

    public void enableChildren() throws UnsupportedEncodingException {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"pih.patient.children", (String)"registration.patient.children.label", (String)"fas fa-fw fa-child", (String)"link", (String)("pihcore/children/children.page?patientId={{patient.patientId}}&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.config.getDashboardUrl()), (String)"App: registrationapp.registerPatient", (String)RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()})));
        this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"pih.registration.patient.children", (String)"registration.patient.children.label", (String)"fas fa-fw fa-child", (String)"link", (String)("pihcore/children/children.page?patientId={{patient.patientId}}&returnUrl=/" + WebConstants.CONTEXT_PATH + "/registrationapp/registrationSummary.page?patientId={{patient.uuid}}" + URLEncoder.encode("&appId=registrationapp.registerPatient", "UTF-8")), (String)"App: registrationapp.registerPatient", (String)RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()})));
    }

    public void enableWristbands() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"pih.wristband.print", (String)"mirebalais.printWristband", (String)"fas fa-fw fa-print", (String)"script", (String)"printWristband()", (String)"Task: emr.printWristband", null));
        this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"pih.registration.wristband.print", (String)"mirebalais.printWristband", (String)"fas fa-fw fa-print", (String)"script", (String)"printWristband()", (String)"Task: emr.printWristband", null));
        this.extensions.add(CustomAppLoaderUtil.fragmentExtension((String)"pih.wristband.patientDashboard.includes", (String)"pihcore", (String)"wristband/printWristband", null, (String)"patientDashboard.includeFragments", null));
    }

    private void enableAppointmentScheduling() {
        AppDescriptor apppointmentScheduling = CustomAppLoaderUtil.app((String)"appointmentschedulingui.app", (String)"appointmentschedulingui.scheduleAppointment.new.title", (String)"fas fa-fw fa-calendar-alt", (String)"appointmentschedulingui/home.page", (String)"App: appointmentschedulingui.home", null);
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)apppointmentScheduling, (String)RequireUtil.sessionLocationHasTag((String)"Appointment Location")));
        this.apps.add(CustomAppLoaderUtil.findPatientTemplateApp((String)"appointmentschedulingui.schedulingAppointmentApp", (String)"appointmentschedulingui.scheduleAppointment.buttonTitle", (String)"fas fa-fw fa-calendar-alt", (String)"Task: appointmentschedulingui.bookAppointments", (String)"/appointmentschedulingui/manageAppointments.page?patientId={{patientId}}&breadcrumbOverride={{breadcrumbOverride}}", (ArrayNode)CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"icon", "fas fa-fw fa-home", "link", "/index.htm"}), CustomAppLoaderUtil.objectNode((Object[])new Object[]{"label", "appointmentschedulingui.home.title", "link", "/appointmentschedulingui/home.page"}), CustomAppLoaderUtil.objectNode((Object[])new Object[]{"label", "appointmentschedulingui.scheduleAppointment.buttonTitle"})}), (ArrayNode)this.config.getFindPatientColumnConfig()));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"appointmentschedulingui.scheduleAppointment", (String)"appointmentschedulingui.scheduleAppointment.new.title", (String)"fas fa-fw fa-calendar-alt", (String)"link", (String)"appointmentschedulingui/manageAppointments.page?patientId={{patient.uuid}}", (String)"Task: appointmentschedulingui.bookAppointments", null));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"appointmentschedulingui.requestAppointment", (String)"appointmentschedulingui.requestAppointment.label", (String)"fas fa-fw fa-calendar-alt", (String)"link", (String)"appointmentschedulingui/requestAppointment.page?patientId={{patient.uuid}}", (String)"Task: appointmentschedulingui.requestAppointments", null));
        this.extensions.add(CustomAppLoaderUtil.dashboardTab((String)"appointmentschedulingui.tab", (String)"appointmentschedulingui.appointmentsTab.label", (String)"App: appointmentschedulingui.viewAppointments", (String)"appointmentschedulingui", (String)"appointmentsTab"));
        if (this.config.isComponentEnabled("clinicianDashboard").booleanValue()) {
            CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)apppointmentScheduling, (String)"appointmentschedulingui", (String)"miniPatientAppointments");
        }
    }

    private void enableSystemAdministration() {
        if (this.findAppById("coreapps.app.systemAdministration") == null) {
            this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.app.systemAdministration", (String)"coreapps.app.system.administration.label", (String)"fas fa-fw fa-cogs", (String)"coreapps/systemadministration/systemAdministration.page", (String)"App: coreapps.systemAdministration", null)));
        }
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"emr.account.manageAccounts", (String)"coreapps.task.accountManagement.label", (String)"fas fa-fw fa-book", (String)"pihapps/account/manageAccounts.page", (int)1, (String)"App: coreapps.systemAdministration", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.mergePatients", (String)"coreapps.mergePatientsLong", (String)"fas fa-users", (String)"coreapps/datamanagement/mergePatients.page?app=coreapps.mergePatients", (int)2, (String)"App: coreapps.systemAdministration", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"breadcrumbs", CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"icon", "fas fa-fw fa-home", "link", "/index.htm"}), CustomAppLoaderUtil.objectNode((Object[])new Object[]{"label", "coreapps.app.systemAdministration.label", "link", "/coreapps/systemadministration/systemAdministration.page"}), CustomAppLoaderUtil.objectNode((Object[])new Object[]{"label", "coreapps.mergePatientsLong"})}), "dashboardUrl", this.config.getAfterMergeUrl() != null ? this.config.getAfterMergeUrl() : this.config.getDashboardUrl()}))));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.featureToggles", (String)"pih.app.featureToggles.label", (String)"fas fa-fw fa-toggle-on", (String)"pihcore/toggles.page", (int)3, (String)"App: coreapps.systemAdministration", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.admin.legacyAdmin", (String)"emr.advancedFeatures", (String)"fas fa-fw fa-toolbox", (String)"admin/index.htm", (int)4, (String)"App: coreapps.systemAdministration", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.admin.configuration", (String)"pih.app.admin.configuration", (String)"fas fa-fw fa-search", (String)"pihcore/admin/configuration.page", (int)5, (String)"SQL Level Access", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.admin.statusData.view", (String)"pih.app.admin.statusData.view", (String)"fas fa-fw icon-flag", (String)"pihcore/admin/statusData.page", (int)6, (String)"App: coreapps.systemAdministration", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.admin.statusData.admin", (String)"pih.app.admin.statusData.admin", (String)"fas fa-fw fa-calculator", (String)"pihcore/admin/statusAdmin.page", (int)7, (String)"App: coreapps.systemAdministration", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.admin.email.admin", (String)"pih.app.admin.email.config", (String)"fas fa-fw fa-envelope", (String)"pihapps/admin/emailTest.page", (int)8, (String)"App: coreapps.systemAdministration", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.admin.activeUsers.admin", (String)"authenticationui.activeUsers.title", (String)"fas fa-fw fa-users", (String)"authenticationui/admin/activeUsers.page", (int)9, (String)"App: coreapps.systemAdministration", null)));
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.auditTrail", (String)"app.auditTrail.label", (String)"fas fa-fw fa-file-archive", (String)"spa/home/audit", (int)10, (String)"App: coreapps.systemAdministration", null)));
    }

    private void enableManagePrinters() {
        if (this.findAppById("coreapps.app.systemAdministration") == null) {
            this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.app.systemAdministration", (String)"coreapps.app.system.administration.label", (String)"fas fa-fw fa-cogs", (String)"coreapps/systemadministration/systemAdministration.page", (String)"App: coreapps.systemAdministration", null)));
        }
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"printer.printerAdministration", (String)"printer.administration", (String)"fas fa-fw fa-print", (String)"printer/printerAdministration.page", (int)12, (String)"App: coreapps.systemAdministration", null)));
    }

    private void enableMyAccount() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"emr.myAccount", (String)"authenticationui.myAccount.title", (String)"fas fa-fw fa-cog", (String)"authenticationui/account/userAccount.page", null, null)));
    }

    private void enablePatientRegistration() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)this.patientRegistrationApp.getAppDescriptor(this.config), (String)RequireUtil.sessionLocationHasTag((String)"Registration Location")));
        if (this.config.isCountry(ConfigDescriptor.Country.MEXICO) || this.config.isCountry(ConfigDescriptor.Country.HAITI) && ConfigDescriptor.Specialty.HIV.equals((Object)this.config.getSpecialty())) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"registrationapp.additionalIdentifiers", (String)"zl.registration.patient.additionalIdentifiers", (String)"fas fa-fw fa-user", null, (String)"App: registrationapp.registerPatient", null), (String)"registrationapp", (String)"summary/section", (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"sectionId", "patient-identification-section"})));
        }
        this.apps.add(CustomAppLoaderUtil.addToRegistrationSummaryContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.mostRecentRegistrationSummary", (String)"mirebalais.mostRecentRegistration.label", (String)"fas fa-fw fa-user", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "mirebalais.mostRecentRegistration.encounterDateLabel", "encounterTypeUuid", "873f968a-73a8-4f9c-ac78-9f4778b751b6", "definitionUiResource", PihCoreUtil.getFormResource((String)"patientRegistration-rs.xml"), "editable", true, "creatable", true})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
        if (this.config.isComponentEnabled("providerRelationships").booleanValue()) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.relationships.providers.registrationSummary", (String)"pihcore.providerRelationshipsDashboardWidget.label", (String)"fas fa-fw fa-users", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "relationships", "baseAppPath", "/registrationapp", "editable", "true", "editPrivilege", "Task: coreapps.editRelationships", "dashboardPage", "/registrationapp/registrationSummary.page?patientId={{patientUuid}}&appId=registrationapp.registerPatient", "providerPage", null, "includeRelationshipTypes", "eb567be2-fda1-4746-9d51-833de8a7e81f", "icon", "fas fa-fw fa-users", "label", "pihcore.providerRelationshipsDashboardWidget.label"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
        if (this.config.isComponentEnabled("relationships").booleanValue()) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.relationships.registrationSummary", (String)"pihcore.relationshipsDashboardWidget.label", (String)"fas fa-fw fa-users", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "relationships", "baseAppPath", "/registrationapp", "editable", "true", "editPrivilege", "Task: coreapps.editRelationships", "dashboardPage", "/registrationapp/registrationSummary.page?patientId={{patientUuid}}&appId=registrationapp.registerPatient", "providerPage", null, "includeRelationshipTypes", "C8CDE783-00EB-4FEA-9E4B-A2BDCAD8D33E,8d91a01c-c2cc-11de-8d13-0010c6dffd0f,8d91a210-c2cc-11de-8d13-0010c6dffd0f", "icon", "fas fa-fw fa-users", "label", "pihcore.relationshipsDashboardWidget.label"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.MEXICO) || this.config.isCountry(ConfigDescriptor.Country.HAITI)) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummaryContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.mostRecentRegistrationInsurance", (String)"zl.registration.patient.insurance.insuranceName.label", (String)"fas fa-fw fa-address-card", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "mirebalais.mostRecentRegistration.encounterDateLabel", "encounterTypeUuid", "873f968a-73a8-4f9c-ac78-9f4778b751b6", "definitionUiResource", PihCoreUtil.getFormResource((String)"patientRegistration-insurance.xml"), "editable", true})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
        }
        this.apps.add(CustomAppLoaderUtil.addToRegistrationSummaryContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.mostRecentRegistrationSocial", (String)"zl.registration.patient.social.label", (String)"fas fa-fw fa-user", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "mirebalais.mostRecentRegistration.encounterDateLabel", "encounterTypeUuid", "873f968a-73a8-4f9c-ac78-9f4778b751b6", "definitionUiResource", PihCoreUtil.getFormResource((String)"patientRegistration-social.xml"), "editable", true})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
        if (!this.config.isCountry(ConfigDescriptor.Country.LIBERIA)) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.mostRecentRegistrationContact", (String)"zl.registration.patient.contactPerson.label", (String)"fas fa-fw fa-phone", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "mirebalais.mostRecentRegistration.encounterDateLabel", "encounterTypeUuid", "873f968a-73a8-4f9c-ac78-9f4778b751b6", "definitionUiResource", PihCoreUtil.getFormResource((String)"patientRegistration-contact.xml"), "editable", true})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.HAITI) && this.config.isSite("CENTRAL")) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.mostRecentRegistrationHealthCenter", (String)"zl.registration.patient.healthCenter.header", (String)"fas fa-fw fa-hospital", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "mirebalais.mostRecentRegistration.encounterDateLabel", "encounterTypeUuid", "873f968a-73a8-4f9c-ac78-9f4778b751b6", "definitionUiResource", PihCoreUtil.getFormResource((String)"patientRegistration-healthCenter.xml"), "editable", true})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.mostRecentRegistrationLocalAddress", (String)"sl.registration.patient.localAddress.label", (String)"fas fa-fw fa-phone", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "mirebalais.mostRecentRegistration.encounterDateLabel", "encounterTypeUuid", "873f968a-73a8-4f9c-ac78-9f4778b751b6", "definitionUiResource", PihCoreUtil.getFormResource((String)"patientRegistration-localAddress.xml"), "editable", true})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
        }
        if (this.config.isComponentEnabled("checkIn").booleanValue()) {
            if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
                this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps,mostRecentCheckIn", (String)"pihcore.mostRecentCheckin.label", (String)"fas fa-fw fa-check", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "pihcore.mostRecentCheckin.encounterDateLabel", "encounterTypes", CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"55a0d3ea-a4d7-4e88-8f01-5aceb2d3c61b", PihCoreUtil.getFormResource((String)"checkin.xml"), "251c03fa-a9dc-4157-855f-b016f4fae9ab", PihCoreUtil.getFormResource((String)"checkinMaternal.xml")})}), "editable", true, "edit-provider", "htmlformentryui", "edit-fragment", "htmlform/editHtmlFormWithSimpleUi"})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
            } else {
                this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps,mostRecentCheckIn", (String)"pihcore.mostRecentCheckin.label", (String)"fas fa-fw fa-check", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterDateLabel", "pihcore.mostRecentCheckin.encounterDateLabel", "encounterTypeUuid", "55a0d3ea-a4d7-4e88-8f01-5aceb2d3c61b", "definitionUiResource", PihCoreUtil.getFormResource((String)"checkin.xml"), "editable", true, "edit-provider", "htmlformentryui", "edit-fragment", "htmlform/editHtmlFormWithSimpleUi"})), (String)"coreapps", (String)"encounter/mostRecentEncounter"));
            }
        }
        if (this.config.isComponentEnabled("idcardPrinting").booleanValue()) {
            this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"mirebalais.idCardPrintingStatus", (String)"zl.registration.patient.idcard.status", (String)"fas fa-fw fa-barcode", null, (String)"App: registrationapp.registerPatient", null), (String)"pihcore", (String)"patientRegistration/idCardStatus"));
        }
        this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"registrationapp.registerNewPatient", (String)"registrationapp.home", (String)"fas fa-fw fa-user", (String)"link", (String)"registrationapp/findPatient.page?appId=registrationapp.registerPatient", (String)"App: registrationapp.registerPatient", (String)RequireUtil.sessionLocationHasTag((String)"Registration Location")));
        this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"registrationapp.mergePatient", (String)"coreapps.mergePatientsShort", (String)"fas fa-fw fa-users", (String)"link", (String)"coreapps/datamanagement/mergePatients.page?app=coreapps.mergePatients&patient1={{patient.patientId}}", (String)"App: registrationapp.registerPatient", null));
        if (this.config.isComponentEnabled("clinicianDashboard").booleanValue()) {
            this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"coreapps.clinicianFacingPatientDashboardApp", (String)"registrationapp.clinicalDashboard", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)(this.config.getDashboardUrl() + "&appId=" + "registrationapp.registerPatient"), (String)"App: coreapps.patientDashboard", null));
            this.extensions.add(CustomAppLoaderUtil.overallAction((String)"registrationapp.registrationSummary.link", (String)"registrationapp.patient.registrationSummary", (String)"fas fa-fw fa-user", (String)"link", (String)"registrationapp/registrationSummary.page?patientId={{patient.patientId}}&appId=registrationapp.registerPatient", (String)"App: registrationapp.registerPatient", null));
        }
        if (this.config.isComponentEnabled("visitManagement").booleanValue()) {
            this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"coreapps.visitsDashboard", (String)"pihcore.visitDashboard", (String)"fas fa-fw fa-user", (String)"link", (String)this.patientVisitsPageUrl, (String)"App: coreapps.patientDashboard", null));
        }
        if (this.config.isComponentEnabled("archives").booleanValue()) {
            this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"registrationapp.printPaperFormLabel", (String)"paperrecord.task.printPaperFormLabel.label", (String)"fas fa-fw fa-print", (String)"script", (String)"printPaperFormLabel()", (String)"Task: emr.printLabels", null));
        }
        if (this.config.isComponentEnabled("idcardPrinting").booleanValue()) {
            this.extensions.add(CustomAppLoaderUtil.overallRegistrationAction((String)"mirebalais.printIdCard", (String)"zl.registration.patient.idcard.label", (String)"fas fa-fw fa-barcode", (String)"link", (String)"pihcore/patientRegistration/printIdCard.page?patientId={{patient.patientId}}", (String)"App: registrationapp.registerPatient", null));
        }
        this.addPaperRecordActionsIncludesIfNeeded();
    }

    private void enableClinicianDashboard() {
        this.apps.add(CustomAppLoaderUtil.app((String)"pih.app.clinicianDashboard", (String)"mirebalais.app.clinicianDashboard.label", (String)"fas fa-fw fa-medkit", (String)"coreapps/clinicianfacing/patient.page?app=pih.app.clinicianDashboard", (String)"App: coreapps.patientDashboard", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"visitUrl", this.patientVisitsPageWithSpecificVisitUrl, "visitsUrl", this.patientVisitsPageUrl})));
        AppDescriptor visitSummary = CustomAppLoaderUtil.app((String)"coreapps.clinicianfacing.visits", (String)"coreapps.clinicianfacing.visits", (String)"fas fa-fw fa-calendar-alt", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"visitType", "f01c54cb-2225-471a-9cd5-d348552c337c"}));
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)visitSummary, (String)"coreapps", (String)"clinicianfacing/visitsSection"));
        if (this.config.isComponentEnabled("homeVisitsOnClinicianDashboard").booleanValue()) {
            HashMap<String, String> visitParams = new HashMap<String, String>();
            visitParams.put("suppressActions", "true");
            visitParams.put("visitType", "90973824-1AE9-4E22-B2BB-9CBD56FB3238");
            AppDescriptor homeVisitsSummary = CustomAppLoaderUtil.app((String)"coreapps.home.visits", (String)"mirebalais.home.visits", (String)"fas fa-fw fa-calendar-alt", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"visitType", "90973824-1AE9-4E22-B2BB-9CBD56FB3238", "visitsUrl", this.addParametersToUrl(this.patientVisitsPageUrl, visitParams), "visitUrl", this.addParametersToUrl(this.patientVisitsPageWithSpecificVisitUrl, visitParams), "showVisitTypeOnPatientHeaderSection", true, "label", "mirebalais.home.visits"}));
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)homeVisitsSummary, (String)"coreapps", (String)"clinicianfacing/visitsSection"));
        }
        if (this.config.isComponentEnabled("bmiOnClinicianDashboard").booleanValue()) {
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)this.graphs.getBmiGraph("patientDashboard.firstColumnFragments"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
    }

    private void enableAllergies() {
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"allergyui.allergySummary", (String)"allergyui.allergies", (String)"fas fa-fw fa-allergies", null, null, null), (String)"allergyui", (String)"allergies"));
    }

    private void enableOncology() {
        this.configureBasicProgramDashboard("5bdbf9f6-690c-11e8-adc0-fa7ae01bbebc");
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"035fb8da-226a-420b-8d8b-3904f3bedb25", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-paste", (Boolean)true, (Boolean)true, null, (String)"4f10ad1a-ec49-48df-98c7-1391c6ac7f05");
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"f9cfdf8b-d086-4658-9b9d-45a62896da03", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-paste", (Boolean)true, (Boolean)true, null, (String)"4f10ad1a-ec49-48df-98c7-1391c6ac7f05");
    }

    public void enableChemotherapy() {
        Extension chemoOrdering = CustomAppLoaderUtil.overallAction((String)"oncology.orderChemo", (String)"pih.task.orderChemo", (String)"fas fa-fw fa-pills", (String)"link", (String)"owa/openmrs-owa-oncology/index.html?patientId={{patient.uuid}}/#physicianDashboard", (String)"Task: emr.enterConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Chemotherapy Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
        this.extensions.add(chemoOrdering);
        Extension chemoRecording = CustomAppLoaderUtil.visitAction((String)"oncology.recordChemo", (String)"pih.task.recordChemo", (String)"fas fa-fw fa-pills", (String)"link", (String)"owa/openmrs-owa-oncology/index.html?patientId={{patient.uuid}}&visitId={{visit.uuid}}/#nurseDashboard", (String)"Task: emr.enterConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Chemotherapy Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
        this.extensions.add(chemoRecording);
        this.extensions.add(CustomAppLoaderUtil.cloneAsOncologyOverallAction((Extension)chemoOrdering));
        this.extensions.add(CustomAppLoaderUtil.cloneAsOncologyVisitAction((Extension)chemoRecording));
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"828964fa-17eb-446e-aba4-e940b0f4be5b", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-retweet", (Boolean)true, (Boolean)true, null, (String)"4f10ad1a-ec49-48df-98c7-1391c6ac7f05");
    }

    private void enableLabResults() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"pih.form.labResults", (String)"pih.task.addPastLabResults.label", (String)"fas fa-fw fa-vial", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLinkWithoutVisit((String)PihCoreUtil.getFormResource((String)"labResults.xml")), (String)"Task: emr.enterLabResults", (String)RequireUtil.sessionLocationHasTag((String)"Lab Results Location")));
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.findPatientTemplateApp((String)"pih.app.addLabResults", (String)"pih.app.pastLabResults", (String)"fas fa-fw fa-vial", (String)"Task: emr.enterLabResults", (String)("/htmlformentryui/htmlform/enterHtmlFormWithSimpleUi.page?patientId={{patientId}}&definitionUiResource=" + PihCoreUtil.getFormResource((String)"labResults.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/coreapps/findpatient/findPatient.page?app=" + "pih.app.addLabResults"), null, (ArrayNode)this.config.getFindPatientColumnConfig()), (String)RequireUtil.sessionLocationHasTag((String)"Lab Results Location")));
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"4d77916a-0620-11e5-a6c0-1697f925ec7b", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-vial", (Boolean)true, (Boolean)true, (String)CustomAppLoaderUtil.editSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"labResults.xml")), (String)"4f10ad1a-ec49-48df-98c7-1391c6ac7f05");
    }

    private void enableNCDs() {
        this.configureBasicProgramDashboard("515796ec-bf3a-11e7-abc4-cec278b6b50a");
        if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.vitalsWithGlucose", (String)"pihcore.task.vitalsWithGlucose", (String)"fas fa-fw fa-heartbeat", (String)"link", (String)CustomAppLoaderUtil.enterSimpleHtmlFormLink((String)PihCoreUtil.getFormResource((String)"vitalsWithGlucose.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Vitals Glucose Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterVitalsNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
        }
    }

    private void enableEcho() {
    }

    private void enableMCHForms() {
        if (this.config.isCountry(ConfigDescriptor.Country.LIBERIA)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.ancIntake", (String)"ui.i18n.EncounterType.name.00e5e810-90ec-11e8-9eb6-529269fb1459", (String)"fas fa-fw fa-gift", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"anc-initial.xml")), (String)"Task: emr.enterMCH", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Maternal and Child Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"00e5e810-90ec-11e8-9eb6-529269fb1459"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"00e5e946-90ec-11e8-9eb6-529269fb1459"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale()})})));
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.ancFollowup", (String)"ui.i18n.EncounterType.name.00e5e946-90ec-11e8-9eb6-529269fb1459", (String)"fas fa-fw fa-gift", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"anc-followup.xml")), (String)"Task: emr.enterMCH", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Maternal and Child Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"00e5e810-90ec-11e8-9eb6-529269fb1459"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"00e5e946-90ec-11e8-9eb6-529269fb1459"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale()})})));
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.delivery", (String)"ui.i18n.EncounterType.name.00e5ebb2-90ec-11e8-9eb6-529269fb1459", (String)"fas fa-fw fa-gift", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"anc-delivery.xml")), (String)"Task: emr.enterMCH", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Maternal and Child Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"00e5ebb2-90ec-11e8-9eb6-529269fb1459"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale()})})));
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.peds", (String)"ui.i18n.EncounterType.name.fac9d9a2-d0bc-11ea-9995-3c6aa7c392cc", (String)"fas fa-fw fa-gift", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"peds.xml")), (String)"Task: emr.enterMCH", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Maternal and Child Location"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale()})})));
        }
    }

    private void enableMCHGainMaternal() {
        if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.gainDelivery", (String)"pih.task.maternalDeliveryRegister", (String)"fas fa-fw fa-baby", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"gainMaternalRegister.xml")), (String)"Task: emr.enterMCH", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Maternal and Child Location"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()})})));
        }
    }

    private void enableMCHGainNewborn() {
        if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.gainNewbornSCBU", (String)"pih.task.sbcuRegister", (String)"fas fa-fw fa-baby", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"gainNewbornRegister.xml")), (String)"Task: emr.enterMCH", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Maternal and Child Location"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()})})));
        }
    }

    private void enableMCOEForms() {
        if (this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE)) {
            Extension pacuFollowup = CustomAppLoaderUtil.visitAction((String)"pih.form.pacuFollowup", (String)"pih.task.pacuFollowup", (String)"fas fa-fw fa-clipboard-check", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"pacuFollowup.xml")), (String)"Task: emr.enterMCH", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"PACU Location"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()})}));
            this.extensions.add(pacuFollowup);
            this.extensions.add(CustomAppLoaderUtil.cloneAsPregnancyVisitAction((Extension)pacuFollowup));
        }
    }

    private void enableVaccinationOnly() {
        Extension vaccination = CustomAppLoaderUtil.visitAction((String)"pih.form.vaccination", (String)"ui.i18n.EncounterType.name.1e2a509c-7c9f-11e9-8f9e-2a86e4085a59", (String)"fas fa-fw fa-umbrella", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"vaccination-only.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterVax", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Vaccination Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"1e2a509c-7c9f-11e9-8f9e-2a86e4085a59")}));
        this.extensions.add(vaccination);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)vaccination));
        this.extensions.add(CustomAppLoaderUtil.cloneAsPregnancyVisitAction((Extension)vaccination));
        this.extensions.add(CustomAppLoaderUtil.cloneAsInfantVisitAction((Extension)vaccination));
    }

    private void enableMentalHealthForm() {
        if (this.config.isCountry(ConfigDescriptor.Country.LIBERIA)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.mentalHealthIntake", (String)"pih.task.mentalHealthIntake.label", (String)"fas fa-fw fa-user", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"mentalHealthIntake.xml")), (String)"Task: emr.enterMentalHealthNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Mental Health Location"), RequireUtil.not((String)RequireUtil.patientHasPreviousEncounter((String)"fccd53c2-f802-439b-a7a2-2d680bd8b81b")), RequireUtil.visitDoesNotHaveEncounterOfType((String)"fccd53c2-f802-439b-a7a2-2d680bd8b81b")})));
        }
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"a8584ab8-cc2a-11e5-9956-625662870761", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-user", (Boolean)true, (Boolean)true, null, (String)"4f10ad1a-ec49-48df-98c7-1391c6ac7f05");
    }

    private void enableEpilepsyForm() {
        if (this.config.isCountry(ConfigDescriptor.Country.LIBERIA)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.epilepsyIntake", (String)"pihcore.ncd.epilepsyIntake", (String)"fas fa-fw fa-brain", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"epilepsyIntake.xml")), (String)"Task: emr.enterMentalHealthNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Mental Health Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"74e06462-243e-4fad-8d7c-0bb3921322f1"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"7336a05e-4bd1-4e52-81c1-207697afc868")})));
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.epilepsy", (String)"pihcore.ncd.epilepsyFU", (String)"fas fa-fw fa-brain", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"epilepsy.xml")), (String)"Task: emr.enterMentalHealthNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Mental Health Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"7336a05e-4bd1-4e52-81c1-207697afc868"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"74e06462-243e-4fad-8d7c-0bb3921322f1")})));
        }
    }

    private void enableVCT() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.vct", (String)"pih.task.vct.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/vct.xml")), (String)"Task: emr.enterVCT", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.sessionLocationDoesNotHaveTag((String)"Oncology Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"616b66fe-f189-11e7-8c3f-9a214cf093ae")})));
    }

    private void enablePrEP() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.prepIntake", (String)"pih.task.prepIntake.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/prep-intake.xml")), (String)"Task: emr.enterVCT", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.sessionLocationDoesNotHaveTag((String)"Oncology Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"4fe5fc03-b42b-4bb2-8ed8-e3c7de8942fb"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"2fb72359-f6fe-4b37-bb11-9b4cb5fd6ca7")})));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.prepFollowup", (String)"pih.task.prepFollowup.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/prep-followup.xml")), (String)"Task: emr.enterVCT", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.sessionLocationDoesNotHaveTag((String)"Oncology Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"2fb72359-f6fe-4b37-bb11-9b4cb5fd6ca7"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"4fe5fc03-b42b-4bb2-8ed8-e3c7de8942fb")})));
    }

    private void enableSocioEconomics() {
        Extension socioEconomics = CustomAppLoaderUtil.visitAction((String)"pih.form.socioEconomics", (String)"pih.task.socioEcon.label", (String)"fas fa-fw fa-home", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"socio-econ.xml")), (String)"Task: emr.enterSocioEconomics", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"de844e58-11e1-11e8-b642-0ed5f89f718b")}));
        this.extensions.add(socioEconomics);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)socioEconomics));
    }

    private void enableChartSearch() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"chartsearch.overallAction", (String)"pihcore.chartSearch.label", (String)"fas fa-fw fa-search", (String)"link", (String)"chartsearch/chartsearch.page?patientId={{patient.patientId}}", (String)"Task: emr.enterConsultNote", null));
    }

    private void enableWaitingForConsult() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.waitingForConsult", (String)"pihcore.waitingForConsult.title", (String)"fas fa-fw fa-stethoscope", (String)"pihcore/visit/waitingForConsult.page", (String)"App: pihcore.waitingForConsult", null), (String)RequireUtil.sessionLocationHasTag((String)"Consult Note Location")));
    }

    private void enableTodaysVisits() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.todaysVisits", (String)"pihcore.todaysVisits.title", (String)"fas fa-fw icon-check-in", (String)"pihcore/visit/todaysVisits.page", (String)"App: pihcore.todaysVisits", null)));
    }

    private void enableEDTriage() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.findPatientTemplateApp((String)"edtriageapp.app.edTriage", (String)"edtriageapp.label", (String)"fas fa-fw fa-ambulance", (String)"App: edtriageapp.edtriage", (String)("/edtriageapp/edtriageEditPatient.page?patientId={{patientId}}&appId=edtriageapp.app.edTriage&dashboardUrl=" + this.config.getDashboardUrl()), null, (ArrayNode)this.config.getFindPatientColumnConfig()), (String)RequireUtil.sessionLocationHasTag((String)"ED Triage Location")));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"edtriageapp.edTriageNote", (String)"ui.i18n.EncounterType.name.74cef0a6-2801-11e6-b67b-9e71128cae77", (String)"fas fa-fw fa-ambulance", (String)"link", (String)"/edtriageapp/edtriageEditPatient.page?patientId={{patient.uuid}}&appId=edtriageapp.app.edTriage", null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"ED Triage Location"), RequireUtil.patientHasActiveVisit()})));
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"74cef0a6-2801-11e6-b67b-9e71128cae77", (Extension)this.findExtensionById("edtriageEncounterTemplate"), (String)"fas fa-fw fa-ambulance", (Boolean)false, (Boolean)true, (String)"edtriageapp/edtriageEditPatient.page?patientId={{patient.uuid}}&encounterId={{encounter.uuid}}&appId=edtriageapp.app.triageQueue&returnUrl={{returnUrl}}&breadcrumbOverride={{breadcrumbOverride}}&editable=true", null);
    }

    private void enableEDTriageQueue() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"edtriageapp.app.triageQueue", (String)"edtriageapp.queue.label", (String)"fas fa-fw fa-list-ol", (String)"/edtriageapp/edtriageViewQueue.page?appId=edtriageapp.app.triageQueue", (String)"App: edtriageapp.edtriage.queue", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"dashboardUrl", this.config.getDashboardUrl()})), (String)RequireUtil.sessionLocationHasTag((String)"ED Triage Location")));
    }

    private void enablePrimaryCare() {
        if (this.config.isCountry(ConfigDescriptor.Country.MEXICO)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.mexicoConsult", (String)"ui.i18n.EncounterType.name.aa61d509-6e76-4036-a65d-7813c0c3b752", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"consult.xml")), null, (String)RequireUtil.sessionLocationHasTag((String)"Consult Note Location")));
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.mexicoClinicalHistory", (String)"ui.i18n.EncounterType.name.0d16a7c9-07fb-43f6-8984-dd7787f26a5a", (String)"fas fa-fw fa-clipboard-list", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"clinicalHistory.xml")), null, (String)RequireUtil.sessionLocationHasTag((String)"Consult Note Location")));
        }
    }

    private void enableFamilyMedicine() {
        if (this.config.isCountry(ConfigDescriptor.Country.LIBERIA)) {
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.liberia.familyMed.initial", (String)"ui.i18n.EncounterType.name.1d1faf4f-a848-44c8-ac44-ada8aa5ae40c", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"family-medicine-intake.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"1d1faf4f-a848-44c8-ac44-ada8aa5ae40c"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"84d5e978-66d1-4a2c-b209-95d47c911dd8")})));
            this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.liberia.familyMed.followup", (String)"ui.i18n.EncounterType.name.84d5e978-66d1-4a2c-b209-95d47c911dd8", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"family-medicine-followup.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Consult Note Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"1d1faf4f-a848-44c8-ac44-ada8aa5ae40c"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"84d5e978-66d1-4a2c-b209-95d47c911dd8")})));
        }
    }

    private void enableHIV() {
        this.enableHIVProgram();
        this.enableHIVForms();
        this.enableHIVActions();
    }

    private void enableHIVProgram() {
        String programUuid = "b1cb1fc1-5190-4f7a-af08-48870975dafc";
        int firstColumnIndex = 0;
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.alerts", (String)"pih.app.hiv.status.title", (String)"fas fa-fw fa-exclamation-circle", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"configFile", "hiv/hivStatuses.yml"})), (String)"pihcore", (String)"dashboardwidgets/statusData", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hivTb.status", (String)"pih.app.hivTb.status.title", (String)"fas fa-fw fa-exclamation-circle", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"configFile", "hivTb/statuses.yml"})), (String)"pihcore", (String)"dashboardwidgets/statusData", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.encounters.intake", (String)"pih.hivIntake.ucase", (String)"fas fa-fw fa-ribbon", (String)this.patientVisitsPageUrl, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterTypes", CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterType", "c31d306a-40c4-11e7-a919-92ebcb67fe33", "url", this.patientVisitsPageWithSpecificVisitUrl})})})), (String)"pihcore", (String)"dashboardwidgets/encounters", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.encounters.followup", (String)"pih.hivFollowup.ucase", (String)"fas fa-fw fa-ribbon", (String)this.patientVisitsPageUrl, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterTypes", CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterType", "c31d3312-40c4-11e7-a919-92ebcb67fe33", "url", this.patientVisitsPageWithSpecificVisitUrl})}), "maxToDisplay", "3"})), (String)"pihcore", (String)"dashboardwidgets/encounters", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.pmtct.dispensing.followup", (String)"pih.pmtctFollowup.ucase", (String)"fas fa-fw fa-ribbon", (String)this.patientVisitsPageUrl, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterTypes", CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterType", "95e03e7d-9aeb-4a99-bd7a-94e8591ec2c5", "url", this.patientVisitsPageWithSpecificVisitUrl})}), "maxToDisplay", "3"})), (String)"pihcore", (String)"dashboardwidgets/encounters", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.dispensing.followup", (String)"pih.hivDispensing.ucase", (String)"fas fa-fw fa-ribbon", (String)this.patientVisitsPageUrl, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterTypes", CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterType", "cc1720c9-3e4c-4fa8-a7ec-40eeaad1958c", "url", this.patientVisitsPageWithSpecificVisitUrl})}), "maxToDisplay", "3"})), (String)"pihcore", (String)"dashboardwidgets/encounters", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.socioeconomic.followup", (String)"pih.socioeconomics.ucase", (String)"icon-home", (String)this.patientVisitsPageUrl, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterTypes", CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterType", "de844e58-11e1-11e8-b642-0ed5f89f718b", "url", this.patientVisitsPageWithSpecificVisitUrl})})})), (String)"pihcore", (String)"dashboardwidgets/encounters", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.weightGraph", (String)"pih.app.hivWeightGraph.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsgraph", "icon", "fas fa-fw fa-chart-bar", "label", "pih.app.hivWeightGraph.title", "conceptId", "3ce93b62-26fe-102b-80cb-0017a47871b2", "maxResults", "1000"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.lab.results", (String)"pihcore.lab.lab_results.caps", (String)"fas fa-vial", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterTypes", Context.getAdministrationService().getGlobalProperty("labworkflowowa.labResultsEncounterTypes"), "detailsUrl", this.patientLabResultsPage, "obsTypes", "LabSet,Test", "labCategoriesSet", "517d25f7-2e68-4da4-912b-76090fbfe0fd", "maxToDisplay", "5"})), (String)"pihcore", (String)"dashboardwidgets/labResults", (int)firstColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)("pih.app." + programUuid + ".patientProgramSummary"), (String)"coreapps.currentEnrollmentDashboardWidget.label", (String)"fas fa-fw fa-stethoscope", null, (String)"App: coreapps.patientDashboard", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "programstatus", "icon", "fas fa-fw fa-stethoscope", "label", "coreapps.currentEnrollmentDashboardWidget.label", "program", programUuid, "locationTag", "Program Location", "markPatientDeadOutcome", this.config.isComponentEnabled("markPatientDead") != false ? "3cdd446a-26fe-102b-80cb-0017a47871b2" : null, "dashboard", programUuid})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget", (int)1));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)("pih.app." + programUuid + ".patientProgramHistory"), (String)"coreapps.programHistoryDashboardWidget.label", (String)"fas fa-fw fa-stethoscope", null, (String)"App: coreapps.patientDashboard", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"icon", "fas fa-fw fa-stethoscope", "label", "coreapps.programHistoryDashboardWidget.label", "program", programUuid, "includeActive", false, "locationTag", "Program Location", "markPatientDeadOutcome", this.config.isComponentEnabled("markPatientDead") != false ? "3cdd446a-26fe-102b-80cb-0017a47871b2" : null, "dashboard", programUuid})), (String)"coreapps", (String)"program/programHistory", (int)2));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.conditionList.hiv", null, null, null, (String)"Task: Manage Condition Lists", null), (String)"coreapps", (String)"conditionlist/conditions", (int)firstColumnIndex++));
        int secondColumnIndex = 3;
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.fingerprint", (String)"pih.app.hiv.fingerprint.status.title", (String)"fas fa-fw fa-fingerprint", null, null, null), (String)"pihcore", (String)"dashboardwidgets/fingerprint", (int)secondColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.viralLoadHistory", (String)"pih.vlHistory.ucase", (String)"fas fa-fw fa-ribbon", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-fw fa-ribbon", "label", "pih.vlHistory.ucase", "encounterTypes", "4d77916a-0620-11e5-a6c0-1697f925ec7b,39C09928-0CAB-4DBA-8E48-39C631FA4286", "concepts", "1305AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA,3cd4a882-26fe-102b-80cb-0017a47871b2,53cb83ed-5d55-4b63-922f-d6b8fc67a5f8", "headers", "zl.date,pihcore.status,pih.app.hiv.viralLoadHistory.load,pih.app.hiv.viralLoadHistory.lld", "maxRecords", "5"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget", (int)secondColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.giv.pendingViralLoadTests", (String)"pih.pendingViralLoad.ucase", (String)"fas fa-fw fa-ribbon", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"orderables", "d760dcec-f2b3-46c5-a6af-7e83c2165fd1"})), (String)"pihcore", (String)"dashboardwidgets/pendingLabOrders", (int)secondColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.vlGraph", (String)"pih.app.hivvlGraph.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsgraph", "icon", "fas fa-fw fa-chart-bar", "label", "pih.app.hivvlGraph.title", "conceptId", "3cd4a882-26fe-102b-80cb-0017a47871b2", "type", "logarithmic", "maxResults", "1000"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget", (int)secondColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.adverse.effect", (String)"pihcore.adverse.reactions", (String)"fas fa-fw fa-allergies", (String)this.patientVisitsPageUrl, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-fw fa-allergies", "label", "pihcore.adverse.reactions", "detailsUrl", this.patientVisitsPageUrl, "encounterTypes", "c31d306a-40c4-11e7-a919-92ebcb67fe33,c31d3312-40c4-11e7-a919-92ebcb67fe33", "concepts", "3cd96052-26fe-102b-80cb-0017a47871b2,3cd964bc-26fe-102b-80cb-0017a47871b2", "headers", "zl.date,pihcore.reaction,pihcore.on.date"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget", (int)secondColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"allergyui.hivAllergySummary", (String)"allergyui.allergies", (String)"fas fa-fw fa-allergies", null, null, null), (String)"allergyui", (String)"allergies", (int)secondColumnIndex++));
        this.apps.add(CustomAppLoaderUtil.addToHivDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hiv.ois", (String)"pihcore.ois.caps", (String)"fas fa-fw fa-exclamation-triangle", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"concept", "PIH:DIAGNOSIS", "codedValueSet", "PIH:8566", "codedValueHeader", "pihcore.ois.infection", "maxMonths", "6", "detailsUrl", this.patientVisitsPageUrl})), (String)"pihcore", (String)"dashboardwidgets/mostRecentObsWithCodedValue", (int)secondColumnIndex++));
        this.configureBasicProgramSummaryApps(programUuid);
        this.configureBasicProgramExtensions(programUuid);
    }

    private void enableHIVForms() {
        this.enableHIVIntakeForm();
        Extension hivFollowup = CustomAppLoaderUtil.visitAction((String)"pih.form.hivZLAdultFollowup", (String)"pih.task.hivFollowup.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"hiv/hiv-followup.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"HIV Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"c31d306a-40c4-11e7-a919-92ebcb67fe33"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"c31d3312-40c4-11e7-a919-92ebcb67fe33"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterHIVConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
        this.extensions.add(hivFollowup);
        this.extensions.add(CustomAppLoaderUtil.cloneAsHivVisitAction((Extension)hivFollowup));
        Extension hivPsychosocial = CustomAppLoaderUtil.visitAction((String)"pih.form.hivPsychosocial", (String)"pihcore.hivPsychosocial.short", (String)"fas fa-fw fa-money-bill-alt", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"hiv/hiv-psychosocial.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"HIV Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"83081f7d-ffd7-4d43-9571-a86e1bc19d7f"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterHIVConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
        this.extensions.add(hivPsychosocial);
        this.extensions.add(CustomAppLoaderUtil.cloneAsHivVisitAction((Extension)hivPsychosocial));
        Extension hivDispensing = CustomAppLoaderUtil.visitAction((String)"pih.form.hivZLDispensing", (String)"pihcore.hivDispensing.short", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"hiv/hiv-dispensing.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"HIV Consult Location"), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterHIVConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
        this.extensions.add(hivDispensing);
        this.extensions.add(CustomAppLoaderUtil.cloneAsHivVisitAction((Extension)hivDispensing));
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.findPatientTemplateApp((String)"pih.app.hiv.dispensing", (String)"pihcore.hivDispensing.short", (String)"fas fa-fw fa-ribbon", (String)"App: dispensing.app.dispense", (String)("/htmlformentryui/htmlform/enterHtmlFormWithStandardUi.page?patientId={{patientId}}&definitionUiResource=" + PihCoreUtil.getFormResource((String)"hiv/hiv-dispensing.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/coreapps/findpatient/findPatient.page?app=" + "pih.app.hiv.dispensing" + "&returnLabel=pihcore.hivDispensing.short"), null, (ArrayNode)this.config.getFindPatientColumnConfig()), (String)RequireUtil.sessionLocationHasTag((String)"HIV Consult Location")));
        this.extensions.add(CustomAppLoaderUtil.cloneAsHivVisitAction((Extension)this.findExtensionById("pih.form.vitals")));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"pih.hiv.medication", (String)"pihcore.hivDispensing.short", (String)"fas fa-fw fa-ribbon", (String)"link", (String)("/htmlformentryui/htmlform/enterHtmlFormWithStandardUi.page?patientId={{patientId}}&definitionUiResource=" + PihCoreUtil.getFormResource((String)"hiv/hiv-dispensing.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.config.getDashboardUrl() + "&returnLabel=pihcore.hivDispensing.short"), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.sessionLocationHasTag((String)"HIV Consult Location")));
        this.extensions.add(CustomAppLoaderUtil.cloneAsHivOverallAction((Extension)this.findExtensionById("pih.hiv.medication")));
    }

    private void enableHIVIntakeForm() {
        Extension hivInitial = CustomAppLoaderUtil.visitAction((String)"pih.form.hivZLAdultInitial", (String)"pih.task.hivIntake.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"hiv/hiv-intake.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"HIV Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"c31d306a-40c4-11e7-a919-92ebcb67fe33"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"c31d3312-40c4-11e7-a919-92ebcb67fe33"), RequireUtil.not((String)RequireUtil.patientHasPreviousEncounter((String)"c31d306a-40c4-11e7-a919-92ebcb67fe33")), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterHIVConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})}));
        this.extensions.add(hivInitial);
        this.extensions.add(CustomAppLoaderUtil.cloneAsHivVisitAction((Extension)hivInitial));
    }

    private void enableHIVActions() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"pih.hiv.medicationList", (String)"pihcore.hivMedicationList.overallAction.label", (String)"fas fa-fw fa-capsules", (String)"link", (String)"pihcore/meds/drugOrders.page?patient={{patient.uuid}}", (String)"App: coreapps.patientDashboard", null));
        this.extensions.add(CustomAppLoaderUtil.cloneAsHivOverallAction((Extension)this.findExtensionById("pih.hiv.medicationList")));
    }

    private void enablePMTCTForms() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.pmtctInitial", (String)"pih.task.pmtctIntake.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"hiv/pmtct-intake.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"HIV Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"584124b9-1f10-4757-ba09-91fc9075af92"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"95e03e7d-9aeb-4a99-bd7a-94e8591ec2c5"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()}), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterHIVConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.pmtctFollowup", (String)"pih.task.pmtctFollowup.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"hiv/pmtct-followup.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"HIV Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"584124b9-1f10-4757-ba09-91fc9075af92"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"95e03e7d-9aeb-4a99-bd7a-94e8591ec2c5"), RequireUtil.and((String[])new String[]{RequireUtil.patientIsFemale(), RequireUtil.patientIsReproductiveAge()}), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterHIVConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
    }

    private void enableEIDForm() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.eidFollowup", (String)"pih.task.eidFollowup.label", (String)"fas fa-fw fa-baby", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)(PihCoreUtil.getFormResource((String)"hiv/eid-followup.xml") + "&returnUrl=/" + WebConstants.CONTEXT_PATH + "/" + this.patientVisitsPageWithSpecificVisitUrl)), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"HIV Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"0f070640-279e-4ec0-9e6c-6ef1f6567030"), RequireUtil.and((String[])new String[]{RequireUtil.patientAgeInMonthsLessThanAtVisitStart((int)24)}), RequireUtil.or((String[])new String[]{RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.enterHIVConsultNote"), RequireUtil.patientHasActiveVisit()}), RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNote"), RequireUtil.and((String[])new String[]{RequireUtil.userHasPrivilege((String)"Task: emr.retroConsultNoteThisProviderOnly"), RequireUtil.patientVisitWithinPastThirtyDays((Config)this.config)})})})));
    }

    private void enableCovid19() {
        this.enableCovid19IntakeForm();
    }

    private void enableCovid19IntakeForm() {
    }

    private void enableTuberculosis() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.tbInitial", (String)"ui.i18n.EncounterType.name.aa42cc6c-b9ee-4850-926c-dda4bb14d890", (String)"fas fa-fw fa-wind", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"tbIntake.xml")), (String)"Task: emr.enterConsultNote", (String)RequireUtil.sessionLocationHasTag((String)"Consult Note Location")));
    }

    private void enableOvc() {
        this.configureBasicProgramDashboard("e1b2f0b5-6d56-4500-8523-0ba71e75d897");
    }

    private void enableMarkPatientDead() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"coreapps.markPatientDied", (String)"coreapps.markPatientDead.label", (String)"icon-plus-sign-alt", (String)"link", (String)"coreapps/markPatientDead.page?patientId={{patient.uuid}}&defaultDead=true", (String)"Task: coreapps.markPatientDead", null));
    }

    private void enablePhysicalRehab() {
    }

    private void enableDrugRehab() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.drugRehab", (String)"pihcore.drugRehab", (String)"fas fa-fw fa-pills", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"drugRehab.xml")), (String)"Task: emr.enterMentalHealthNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.sessionLocationHasTag((String)"Mental Health Location"), RequireUtil.sessionLocationDoesNotHaveTag((String)"Oncology Consult Location"), RequireUtil.visitDoesNotHaveEncounterOfType((String)"dd1ebccb-700d-4e8b-bb61-f54e9763117c")})));
    }

    private void enableHIViSantePlus() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.hivAdultInitial", (String)"pih.task.hivIntakeISantePlus.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/iSantePlus/SaisiePremiereVisiteAdult.xml")), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.patientIsAdult()})));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.hivPedsInitial", (String)"pih.task.hivIntakeISantePlus.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/iSantePlus/SaisiePremiereVisitePediatrique.xml")), null, (String)RequireUtil.and((String[])new String[]{RequireUtil.patientIsChild()})));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.hivAdultFollowup", (String)"pih.task.hivFollowupISantePlus.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/iSantePlus/VisiteDeSuivi.xml")), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.patientIsAdult()})));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.hivPedsFollowup", (String)"pih.task.hivFollowupISantePlus.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/iSantePlus/VisiteDeSuiviPediatrique.xml")), (String)"Task: emr.enterHIVConsultNote", (String)RequireUtil.and((String[])new String[]{RequireUtil.patientIsChild()})));
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.hivAdherence", (String)"pih.task.hivAdherence.label", (String)"fas fa-fw fa-ribbon", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"hiv/iSantePlus/Adherence.xml")), (String)"Task: emr.enterHIVConsultNote", null));
    }

    private void enableANCProgram() {
        this.configureBasicProgramDashboard("d830a5c1-30a2-4943-93a0-f918772496ec");
    }

    private void enableAsthmaProgram() {
        this.configureBasicProgramDashboard("2639449c-8764-4003-be5f-dba522b4b680");
        this.apps.add(CustomAppLoaderUtil.addToAsthmaDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.asthma.symptomsObsTable", (String)"pih.app.asthma.symptomsObsTable.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-fw fa-list-alt", "label", "pih.app.asthma.symptomsObsTable.title", "concepts", "cc4681ee-95df-4400-9900-23193cdc6592,3672ee1d-ba8e-4748-8ccc-98d70035857b,f813d9fa-0842-4862-ae08-5ed30a068207,abab707b-0ca5-43dd-9b6d-57cb2348e8f8", "maxRecords", "40"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableAYFSProgram() {
        this.configureBasicProgramDashboard("3f257581-7e3a-4cf9-8a22-9a1dbd2a8c10");
    }

    private void enableDiabetesProgram() {
        this.configureBasicProgramDashboard("3f038507-f4bc-4877-ade0-96ce170fc8eb");
        this.apps.add(CustomAppLoaderUtil.addToDiabetesDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.abdominalCircumferenceGraph", (String)"pih.app.abdominalCircumference.graph.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsgraph", "label", "pih.app.abdominalCircumference.graph.title", "icon", "fas fa-fw fa-chart-bar", "conceptId", "163080AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "maxRecords", "4"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToDiabetesDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.diabetes.footExamObsTable", (String)"pih.app.footExamObsTable.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-fw fa-list-alt", "label", "pih.app.footExamObsTable.title", "concepts", "18ea04b9-239e-43b8-9508-f57949d60361", "maxRecords", "100"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToDiabetesDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.diabetes.urinaryAlbuminObsTable", (String)"pih.app.urinaryAlbuminObsTable.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-fw fa-list-alt", "label", "pih.app.urinaryAlbuminObsTable.title", "concepts", "3cd49d88-26fe-102b-80cb-0017a47871b2", "maxRecords", "10"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToDiabetesDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.alcTobacUse.summary", (String)"pih.app.patientSummary.title", (String)"fas fa-fw fa-user-md", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "latestobsforconceptlist", "icon", "fas fa-fw fa-user-md", "label", "pih.app.patientSummary.title", "concepts", "3cdbde18-26fe-102b-80cb-0017a47871b2,3ce503e4-26fe-102b-80cb-0017a47871b2"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToDiabetesDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.glucoseGraph", (String)"pih.app.glucose.graph.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsgraph", "label", "pih.app.glucose.graph.title", "icon", "fas fa-fw fa-chart-bar", "conceptId", "3cd4e194-26fe-102b-80cb-0017a47871b2", "maxRecords", "12"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToDiabetesDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.hba1cGraph", (String)"pih.app.hba1c.graph.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsgraph", "label", "pih.app.hba1c.graph.title", "icon", "fas fa-fw fa-chart-bar", "conceptId", "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "maxRecords", "4"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToDiabetesDashboardSecondColumn((AppDescriptor)this.graphs.getCholesterolGraph(".diabetes"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableEpilepsyProgram() {
        this.configureBasicProgramDashboard("69e6a46d-674e-4281-99a0-4004f293ee57");
        this.apps.add(CustomAppLoaderUtil.addToEpilepsyDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.epilepsy.summary", (String)"pih.app.patientSummary.title", (String)"fas fa-fw fa-user-md", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "latestobsforconceptlist", "icon", "fas fa-fw fa-user-md", "label", "pih.app.patientSummary.title", "concepts", "159517AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToEpilepsyDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.epilepsy.seizures", (String)"pih.app.epilepsy.seizureGraph", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsgraph", "label", "pih.app.epilepsy.seizureGraph", "icon", "fas fa-fw fa-chart-bar", "conceptId", "ba2e9e43-5a9d-423f-a33e-c34765785397", "maxResults", "30"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableFamilyPlanningProgram() {
        this.configureBasicProgramDashboard("bfc32f7d-6e2b-48c2-aa43-9d4f7c87a8e4");
    }

    private void enableGynecologyProgram() {
        this.configureBasicProgramDashboard("a932f5d2-9f2b-4eab-823d-8d6a561e9d8c");
    }

    private void enableHypertensionProgram() {
        this.configureBasicProgramDashboard("6959057e-9a5c-40ba-a878-292ba4fc35bc");
        this.apps.add(CustomAppLoaderUtil.addToHypertensionDashboardFirstColumn((AppDescriptor)this.graphs.getBloodPressureGraph(".htn"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToHypertensionDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.bloodPressure.obsTable", (String)"pih.app.bloodPressure.obsTable.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-fw fa-list-alt", "label", "pih.app.bloodPressure.obsTable.title", "concepts", "3ce934fa-26fe-102b-80cb-0017a47871b2,3ce93694-26fe-102b-80cb-0017a47871b2", "maxRecords", "100"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToHypertensionDashboardSecondColumn((AppDescriptor)this.graphs.getBmiGraph(".htn"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToHypertensionDashboardSecondColumn((AppDescriptor)this.graphs.getCholesterolGraph(".htn"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableInfantProgram() {
        this.configureBasicProgramDashboard("dc1b588a-9b94-45e4-8346-71b7c9a24845");
    }

    private void enableMalnutritionProgram() {
        this.configureBasicProgramDashboard("61e38de2-44f2-470e-99da-3e97e93d388f");
        this.apps.add(CustomAppLoaderUtil.addToMalnutritionDashboardSecondColumn((AppDescriptor)this.graphs.getBmiGraph(".malnutrition"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToMalnutritionDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.headCircumferenceGraph", (String)"pih.app.headCircumferenceGraph.title", (String)"fas fa-fw fa-chart-bar", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsgraph", "icon", "fas fa-fw fa-chart-bar", "conceptId", "3ceb96b4-26fe-102b-80cb-0017a47871b2", "maxResults", "12"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableMdrTbProgram() {
        this.configureBasicProgramDashboard("7d377413-4626-4342-b015-1ab229964cb4");
    }

    private void enableMentalHealthProgram() {
        this.configureBasicProgramDashboard("0e69c3ab-1ccb-430b-b0db-b9760319230f");
        if (this.config.isCountry(ConfigDescriptor.Country.MEXICO) || this.config.isCountry(ConfigDescriptor.Country.LIBERIA)) {
            this.apps.add(CustomAppLoaderUtil.addToMentalHealthDashboardSecondColumn((AppDescriptor)this.graphs.getPHQ9Graph("patientDashboard.secondColumnFragments"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.HAITI)) {
            this.apps.add(CustomAppLoaderUtil.addToMentalHealthDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"patientDashboard.secondColumnFragments", (String)"pih.app.mental.health.status.title", (String)"fas fa-fw fa-exclamation-circle", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"configFile", "mentalHealth/mentalHealthStatuses.yml"})), (String)"pihcore", (String)"dashboardwidgets/statusData"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.HAITI)) {
            this.apps.add(CustomAppLoaderUtil.addToMentalHealthDashboardSecondColumn((AppDescriptor)this.graphs.getWHODASGraph("patientDashboard.secondColumnFragments"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.HAITI)) {
            this.apps.add(CustomAppLoaderUtil.addToMentalHealthDashboardSecondColumn((AppDescriptor)this.graphs.getZLDSIGraph("patientDashboard.secondColumnFragments"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.HAITI) || this.config.isCountry(ConfigDescriptor.Country.LIBERIA)) {
            this.apps.add(CustomAppLoaderUtil.addToMentalHealthDashboardSecondColumn((AppDescriptor)this.graphs.getSeizureFrequencyGraph("patientDashboard.secondColumnFragments"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.HAITI)) {
            this.apps.add(CustomAppLoaderUtil.addToMentalHealthDashboardSecondColumn((AppDescriptor)this.graphs.getZLCGIraph("patientDashboard.secondColumnFragments"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
        if (this.config.isCountry(ConfigDescriptor.Country.MEXICO)) {
            this.apps.add(CustomAppLoaderUtil.addToMentalHealthDashboardSecondColumn((AppDescriptor)this.graphs.getGAD7Graph("patientDashboard.secondColumnFragments"), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
    }

    private void enableMCHProgram() {
        this.configureBasicProgramDashboard("41a2715e-8a14-11e8-9a94-a6cf71072f73");
    }

    private void enablePMTCTProgram() {
        this.configureBasicProgramDashboard("2e9a4c7d-8257-4e1d-ae8f-51b29e3e7cfa");
    }

    private void enablePregnancyProgram() {
        this.configureBasicProgramDashboard("6a5713c2-3fd5-46e7-8f25-36a0f7871e12");
    }

    private void enablePregnancyMentalHealthProgram() {
        this.configureBasicProgramDashboard("8d7f6e9c-d2c9-4a7f-9ddc-23a8a5e9fc92");
    }

    private void enableTBProgram() {
        this.configureBasicProgramDashboard("5682f919-a43b-4d4a-bcc3-ef0426162e23");
    }

    private void enableBiometrics(Config config) {
        this.extensions.add(CustomAppLoaderUtil.fragmentExtension((String)"biometrics.findPatient", (String)"registrationapp", (String)"biometrics/fingerprintSearch", null, (String)"coreapps.patientSearch.extension", (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"scanUrl", config.getBiometricsConfig().getScanUrl(), "devicesUrl", config.getBiometricsConfig().getDevicesUrl()})));
        this.apps.add(CustomAppLoaderUtil.addToRegistrationSummarySecondColumnContent((AppDescriptor)CustomAppLoaderUtil.app((String)"registrationapp.biometricsSummary", (String)"registrationapp.biometrics.summary", (String)"fas fa-fw fa-fingerprint", null, (String)"App: registrationapp.registerPatient", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"registrationAppId", "registrationapp.registerPatient", "icon", "fas fa-fw fa-fingerprint", "detailsUrl", "registrationapp/biometrics/editBiometrics.page?patientId={{patient.patientId}}&registrationAppId=registrationapp.registerPatient"})), (String)"pihcore", (String)"dashboardwidgets/fingerprint"));
    }

    private void enablePathologyTracking() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"labtracking.app.monitorOrders", (String)"labtrackingapp.app.label", (String)"fas fa-fw fa-microscope", (String)"/labtrackingapp/labtrackingViewQueue.page?appId=labtracking.app.monitorOrders", (String)"App: labtrackingapp.monitorOrders", null), (String)RequireUtil.sessionLocationHasTag((String)"Order Pathology Location")));
        Extension pathology = CustomAppLoaderUtil.visitAction((String)"labtrackingapp.orderLab", (String)"labtrackingapp.orderPathology.label", (String)"fas fa-fw fa-microscope", (String)"link", (String)"/labtrackingapp/labtrackingAddOrder.page?patientId={{patient.uuid}}&visitId={{visit.id}}", (String)"Task: labtrackingapp.placeOrders", (String)RequireUtil.sessionLocationHasTag((String)"Order Pathology Location"));
        this.extensions.add(pathology);
        this.extensions.add(CustomAppLoaderUtil.cloneAsMchVisitAction((Extension)pathology));
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"labtrackingapp.labSummary", (String)"labtrackingapp.pathology", (String)"fas fa-fw fa-microscope", null, (String)"Task: labtrackingapp.placeOrders", null), (String)"labtrackingapp", (String)"labtrackingPatientDashboard"));
    }

    private void enableLabs() throws UnsupportedEncodingException {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.labs.label", (String)"pih.app.labs.label", (String)"fas fa-vials", (String)this.labOrderListPage, (String)"App: labs", null), (String)RequireUtil.sessionLocationHasTag((String)"Labs Component Location")));
        this.apps.add(CustomAppLoaderUtil.findPatientTemplateApp((String)"pih.app.labs.ordering", (String)"pih.app.labs.ordering", (String)"icon", (String)"Task: orderentry.orderLabs", (String)("/pihapps/labs/labOrder.page?patient={{patientId}}&returnUrl=" + URLEncoder.encode("/" + WebConstants.CONTEXT_PATH + "/" + this.labOrderListPage, "UTF-8")), (ArrayNode)CustomAppLoaderUtil.arrayNode((ObjectNode[])new ObjectNode[]{CustomAppLoaderUtil.objectNode((Object[])new Object[]{"icon", "fas fa-fw fa-home", "link", "/index.htm"}), CustomAppLoaderUtil.objectNode((Object[])new Object[]{"label", "pih.app.labs.label", "link", "/" + this.labOrderListPage}), CustomAppLoaderUtil.objectNode((Object[])new Object[]{"label", "coreapps.findPatient.app.label"})}), (ArrayNode)this.config.getFindPatientColumnConfig()));
        Extension orderLabs = CustomAppLoaderUtil.overallAction((String)"orderentryowa.orderLabs", (String)"pihcore.orderLabs.overallAction.label", (String)"fas fa-fw fa-vials", (String)"link", (String)"/pihapps/labs/labOrders.page?patient={{patient.uuid}}", (String)"Task: orderentry.orderLabs", null);
        this.extensions.add(orderLabs);
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"labworkflowowa.viewLabs", (String)"pihcore.viewLabs.overallAction.label", (String)"fas fa-fw fa-stream", (String)"link", (String)this.patientLabResultsPage, (String)"Task: labworkflow.viewLabs", null));
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.lab.results", (String)"pihcore.lab.lab_results.caps", (String)"fas fa-vial", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"encounterTypes", Context.getAdministrationService().getGlobalProperty("labworkflowowa.labResultsEncounterTypes"), "detailsUrl", this.patientLabResultsPage, "obsTypes", "LabSet,Test", "labCategoriesSet", "517d25f7-2e68-4da4-912b-76090fbfe0fd", "maxToDisplay", "5"})), (String)"pihcore", (String)"dashboardwidgets/labResults"));
    }

    private void enableGrowthChart() {
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"growthchart.viewChart", (String)"pihcore.viewGrowthChart.overallAction.label", (String)"fas fa-fw fa-chart-line", (String)"link", (String)"growthchart/growthCharts.page?patientId={{patient.uuid}}", (String)"Task: growthchart.viewGrowthCharts", null));
    }

    private void enableCohortBuilder() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"cohortBuilder.app", (String)"pih.app.cohortBuilder.label", (String)"fas fa-fw icon-check-in", (String)"owa/cohortbuilder/index.html#/", (String)"App: cohortBuilder", null)));
    }

    private void enableHaitiHIVLink() {
        this.extensions.add(CustomAppLoaderUtil.extension((String)"patientHeader.linkToHivEmr", (String)"pihcore.hiv.viewPatientInHivEmr", (String)"icon-external-link", (String)"link", (String)(PihCoreUtil.getSystemOrRuntimeProperty((String)"haiti_hiv_emr_link_url") + "?patientId={{identifier}}&identifierType=" + "139766e8-15f5-102d-96e4-000c29c2a5d7" + "&dashboard=" + "b1cb1fc1-5190-4f7a-af08-48870975dafc"), null, null, (String)"patientHeader.extraIdentifierLinks", (int)1, (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"identifierType", "139766e8-15f5-102d-96e4-000c29c2a5d7"})));
    }

    private void enablePrograms(Config config) {
        ArrayList<String> supportedPrograms = new ArrayList<String>();
        if (config.isComponentEnabled("ancProgram").booleanValue()) {
            supportedPrograms.add("d830a5c1-30a2-4943-93a0-f918772496ec");
            this.enableANCProgram();
        }
        if (config.isComponentEnabled("asthmaProgram").booleanValue()) {
            supportedPrograms.add("2639449c-8764-4003-be5f-dba522b4b680");
            this.enableAsthmaProgram();
        }
        if (config.isComponentEnabled("ayfsProgram").booleanValue()) {
            supportedPrograms.add("3f257581-7e3a-4cf9-8a22-9a1dbd2a8c10");
            this.enableAYFSProgram();
        }
        if (config.isComponentEnabled("diabetesProgram").booleanValue()) {
            supportedPrograms.add("3f038507-f4bc-4877-ade0-96ce170fc8eb");
            this.enableDiabetesProgram();
        }
        if (config.isComponentEnabled("epilepsyProgram").booleanValue()) {
            supportedPrograms.add("69e6a46d-674e-4281-99a0-4004f293ee57");
            this.enableEpilepsyProgram();
        }
        if (config.isComponentEnabled("familyPlanningProgram").booleanValue()) {
            supportedPrograms.add("bfc32f7d-6e2b-48c2-aa43-9d4f7c87a8e4");
            this.enableFamilyPlanningProgram();
        }
        if (config.isComponentEnabled("gynecologyProgram").booleanValue()) {
            supportedPrograms.add("a932f5d2-9f2b-4eab-823d-8d6a561e9d8c");
            this.enableGynecologyProgram();
        }
        if (config.isComponentEnabled("hiv").booleanValue()) {
            supportedPrograms.add("b1cb1fc1-5190-4f7a-af08-48870975dafc");
            this.enableHIV();
        }
        if (config.isComponentEnabled("hivProgram").booleanValue()) {
            supportedPrograms.add("b1cb1fc1-5190-4f7a-af08-48870975dafc");
            this.enableHIVProgram();
        }
        if (config.isComponentEnabled("hivForms").booleanValue()) {
            this.enableHIVForms();
        }
        if (config.isComponentEnabled("hivIntakeForm").booleanValue()) {
            this.enableHIVIntakeForm();
        }
        if (config.isComponentEnabled("infantProgram").booleanValue()) {
            supportedPrograms.add("dc1b588a-9b94-45e4-8346-71b7c9a24845");
            this.enableInfantProgram();
        }
        if (config.isComponentEnabled("pmtct").booleanValue()) {
            this.enablePMTCTForms();
        }
        if (config.isComponentEnabled("pmtctProgram").booleanValue()) {
            supportedPrograms.add("2e9a4c7d-8257-4e1d-ae8f-51b29e3e7cfa");
            this.enablePMTCTProgram();
        }
        if (config.isComponentEnabled("prep").booleanValue()) {
            supportedPrograms.add("c5e3e6ca-d10f-4b80-9152-11a91f7d38eb");
            this.configureBasicProgramDashboard("c5e3e6ca-d10f-4b80-9152-11a91f7d38eb");
            this.enablePrEP();
        }
        if (config.isComponentEnabled("exposedInfant").booleanValue()) {
            supportedPrograms.add("7e06bf82-9f1a-4218-b68f-823082ef519b");
            this.configureBasicProgramDashboard("7e06bf82-9f1a-4218-b68f-823082ef519b");
            this.enableEIDForm();
        }
        if (config.isComponentEnabled("hypertensionProgram").booleanValue()) {
            supportedPrograms.add("6959057e-9a5c-40ba-a878-292ba4fc35bc");
            this.enableHypertensionProgram();
        }
        if (config.isComponentEnabled("malnutritionProgram").booleanValue()) {
            supportedPrograms.add("61e38de2-44f2-470e-99da-3e97e93d388f");
            this.enableMalnutritionProgram();
        }
        if (config.isComponentEnabled("mentalHealth").booleanValue()) {
            this.enableMentalHealthForm();
            supportedPrograms.add("0e69c3ab-1ccb-430b-b0db-b9760319230f");
            this.enableMentalHealthProgram();
        }
        if (config.isComponentEnabled("mentalHealthForm").booleanValue()) {
            this.enableMentalHealthForm();
        }
        if (config.isComponentEnabled("epilepsy").booleanValue()) {
            this.enableEpilepsyForm();
        }
        if (config.isComponentEnabled("mdrtbProgram").booleanValue()) {
            supportedPrograms.add("7d377413-4626-4342-b015-1ab229964cb4");
            this.enableMdrTbProgram();
        }
        if (config.isComponentEnabled("mentalHealthProgram").booleanValue()) {
            supportedPrograms.add("0e69c3ab-1ccb-430b-b0db-b9760319230f");
            this.enableMentalHealthProgram();
        }
        if (config.isComponentEnabled("ncd").booleanValue()) {
            supportedPrograms.add("515796ec-bf3a-11e7-abc4-cec278b6b50a");
            this.enableNCDs();
            if (config.isComponentEnabled("echocardiogram").booleanValue()) {
                this.enableEcho();
            }
        }
        if (config.isComponentEnabled("ovc").booleanValue()) {
            supportedPrograms.add("e1b2f0b5-6d56-4500-8523-0ba71e75d897");
            this.enableOvc();
        }
        if (config.isComponentEnabled("vaccination").booleanValue()) {
            this.enableVaccinationOnly();
        }
        if (config.isComponentEnabled("hivProgramLocationWarning").booleanValue()) {
            this.enableHivProgramLocationWarning();
        }
        if (config.isComponentEnabled("oncology").booleanValue()) {
            supportedPrograms.add("5bdbf9f6-690c-11e8-adc0-fa7ae01bbebc");
            this.enableOncology();
        }
        if (config.isComponentEnabled("mch").booleanValue()) {
            this.enableMCHForms();
            supportedPrograms.add("41a2715e-8a14-11e8-9a94-a6cf71072f73");
            this.enableMCHProgram();
        }
        if (config.isComponentEnabled("pregnancyProgram").booleanValue()) {
            supportedPrograms.add("6a5713c2-3fd5-46e7-8f25-36a0f7871e12");
            this.enablePregnancyProgram();
        }
        if (config.isComponentEnabled("pregnancyMentalHealthProgram").booleanValue()) {
            supportedPrograms.add("8d7f6e9c-d2c9-4a7f-9ddc-23a8a5e9fc92");
            this.enablePregnancyMentalHealthProgram();
        }
        if (config.isComponentEnabled("tb").booleanValue()) {
            supportedPrograms.add("5682f919-a43b-4d4a-bcc3-ef0426162e23");
            this.enableTBProgram();
        }
        if (config.isComponentEnabled("mchProgram").booleanValue()) {
            supportedPrograms.add("41a2715e-8a14-11e8-9a94-a6cf71072f73");
            this.enableMCHProgram();
        }
        if (config.isComponentEnabled("zika").booleanValue()) {
            supportedPrograms.add("3bea593a-9afd-4642-96a6-210b60f5aff2");
            this.configureBasicProgramDashboard("3bea593a-9afd-4642-96a6-210b60f5aff2");
        }
        if (config.isComponentEnabled("covid19").booleanValue()) {
            supportedPrograms.add("dc227c44-d68e-431b-a20d-a60bb3496543");
            this.configureBasicProgramDashboard("dc227c44-d68e-431b-a20d-a60bb3496543");
        }
        if (supportedPrograms.size() > 0) {
            this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.programSummaryList", (String)"pih.app.programSummaryList.title", (String)"fas fa-fw fa-chart-pie", (String)"/coreapps/applist/appList.page?app=pih.app.programSummaryList", (String)"App: coreapps.summaryDashboard", null), null));
            this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.app.programsList", (String)"coreapps.programsListDashboardWidget.label", (String)"fas fa-fw fa-stethoscope", null, (String)"App: coreapps.patientDashboard", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "programs", "icon", "fas fa-fw fa-stethoscope", "label", "coreapps.programsDashboardWidget.label", "supportedPrograms", StringUtils.join(supportedPrograms, (char)','), "enableProgramDashboards", "true"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        }
    }

    private void configureBasicProgramDashboard(String programUuid) {
        boolean isSierraLeone = this.config.isCountry(ConfigDescriptor.Country.SIERRA_LEONE);
        boolean isPregnancyProgram = "6a5713c2-3fd5-46e7-8f25-36a0f7871e12".equals(programUuid);
        boolean isInfantProgram = "dc1b588a-9b94-45e4-8346-71b7c9a24845".equals(programUuid);
        boolean skipBasicProgramDashboardConfiguration = false;
        if (isSierraLeone && (isPregnancyProgram || isInfantProgram)) {
            skipBasicProgramDashboardConfiguration = true;
        }
        if (!skipBasicProgramDashboardConfiguration) {
            this.configureBasicProgramPatientDashboardWidgets(programUuid);
        }
        this.configureBasicProgramSummaryApps(programUuid);
        this.configureBasicProgramExtensions(programUuid);
    }

    private void configureBasicProgramPatientDashboardWidgets(String programUuid) {
        this.apps.add(CustomAppLoaderUtil.addToProgramDashboardFirstColumn((String)programUuid, (AppDescriptor)CustomAppLoaderUtil.app((String)("pih.app." + programUuid + ".patientProgramSummary"), (String)"coreapps.currentEnrollmentDashboardWidget.label", (String)"fas fa-fw fa-stethoscope", null, (String)"App: coreapps.patientDashboard", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "programstatus", "icon", "fas fa-fw fa-stethoscope", "label", "coreapps.currentEnrollmentDashboardWidget.label", "program", programUuid, "locationTag", "Program Location", "markPatientDeadOutcome", this.config.isComponentEnabled("markPatientDead") != false ? "3cdd446a-26fe-102b-80cb-0017a47871b2" : null, "dashboard", programUuid})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
        this.apps.add(CustomAppLoaderUtil.addToProgramDashboardFirstColumn((String)programUuid, (AppDescriptor)CustomAppLoaderUtil.app((String)("pih.app." + programUuid + ".patientProgramHistory"), (String)"coreapps.programHistoryDashboardWidget.label", (String)"fas fa-fw fa-stethoscope", null, (String)"App: coreapps.patientDashboard", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"icon", "fas fa-fw fa-stethoscope", "label", "coreapps.programHistoryDashboardWidget.label", "program", programUuid, "includeActive", false, "locationTag", "Program Location", "markPatientDeadOutcome", this.config.isComponentEnabled("markPatientDead") != false ? "3cdd446a-26fe-102b-80cb-0017a47871b2" : null, "dashboard", programUuid})), (String)"coreapps", (String)"program/programHistory"));
    }

    private void configureBasicProgramSummaryApps(String programUuid) {
        this.apps.add(CustomAppLoaderUtil.addToProgramSummaryListPage((AppDescriptor)CustomAppLoaderUtil.app((String)("pih.app." + programUuid + ".programSummary.dashboard"), (String)("pih.app." + programUuid + ".programSummary.dashboard"), (String)"fas fa-fw fa-list-alt", (String)("/coreapps/summarydashboard/programEnrollments.page?app=pih.app." + programUuid + ".programSummary.dashboard"), (String)"App: coreapps.summaryDashboard", (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"program", programUuid})), null));
        this.apps.add(CustomAppLoaderUtil.addToProgramSummaryDashboardFirstColumn((String)programUuid, (AppDescriptor)CustomAppLoaderUtil.app((String)("pih.app." + programUuid + " .programStatistics"), (String)("pih.app." + programUuid + ".programStatistics.title"), (String)"fas fa-fw fa-bars", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "programstatistics", "icon", "fas fa-fw fa-bars", "label", "pih.app." + programUuid + ".programStatistics.title", "dateFormat", "dd MMM yyyy", "program", programUuid})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void configureBasicProgramExtensions(String programUuid) {
        String programKey = programUuid.replace("-", "");
        this.extensions.add(CustomAppLoaderUtil.extension((String)("pih.extension." + programKey + " .clinicianDashboardLink"), (String)"registrationapp.clinicalDashboard", (String)"fas fa-fw fa-stethoscope", (String)"link", (String)(this.config.getDashboardUrl() + "&currentDashboard=" + programUuid), (String)"App: coreapps.patientDashboard", null, (String)(programUuid + ".overallActions"), (int)1, null));
        ArrayList<Extension> programExtensions = new ArrayList<Extension>();
        for (Extension e : this.extensions) {
            if (!"patientDashboard.overallActions".equals(e.getExtensionPointId())) continue;
            programExtensions.add(CustomAppLoaderUtil.extension((String)(e.getId() + "." + programUuid), (String)e.getLabel(), (String)e.getIcon(), (String)e.getType(), (String)(e.getUrl() == null ? e.getScript() : e.getUrl()), (String)e.getRequiredPrivilege(), (String)e.getRequire(), (String)(programUuid + ".overallActions"), (int)e.getOrder(), (Map)e.getExtensionParams()));
        }
        this.extensions.addAll(programExtensions);
        this.extensions.add(CustomAppLoaderUtil.fragmentExtension((String)("pih.extension.patientDashboard." + programKey + ".visitIncludes"), (String)"coreapps", (String)"patientdashboard/visitIncludes", null, (String)(programUuid + ".includeFragments"), (Map)CustomAppLoaderUtil.map((Object[])new Object[]{"patientVisitsPage", this.patientVisitsPageWithSpecificVisitUrl, "visitType", "f01c54cb-2225-471a-9cd5-d348552c337c"})));
    }

    private void enableExportPatients() {
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.exportPatients", (String)"pihcore.patient.export", (String)"fas fa-fw fa-external-link-alt", (String)"pihcore/export/exportPatients.page", (int)16, (String)"App: coreapps.systemAdministration", null)));
    }

    private void enableImportPatients() {
        this.apps.add(CustomAppLoaderUtil.addToSystemAdministrationPage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.importPatients", (String)"pihcore.patient.import", (String)"fas fa-fw fa-sign-in-alt", (String)"pihcore/export/importPatients.page", (int)17, (String)"App: coreapps.systemAdministration", null)));
    }

    private void enableProviderRelationships() {
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.relationships.providers.clinicalSummary", (String)"pihcore.providerRelationshipsDashboardWidget.label", (String)"fas fa-fw fa-users", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "relationships", "editPrivilege", "Task: coreapps.editRelationships", "dashboardPage", this.config.getDashboardUrl(), "providerPage", null, "includeRelationshipTypes", "eb567be2-fda1-4746-9d51-833de8a7e81f", "icon", "fas fa-fw fa-users", "label", "pihcore.providerRelationshipsDashboardWidget.label"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableRelationships() {
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.relationships.clinicalSummary", (String)"pihcore.relationshipsDashboardWidget.label", (String)"fas fa-fw fa-users", null, null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "relationships", "editPrivilege", "Task: coreapps.editRelationships", "dashboardPage", this.config.getDashboardUrl(), "providerPage", null, "includeRelationshipTypes", "C8CDE783-00EB-4FEA-9E4B-A2BDCAD8D33E,8d91a01c-c2cc-11de-8d13-0010c6dffd0f,8d91a210-c2cc-11de-8d13-0010c6dffd0f", "icon", "fas fa-fw fa-users", "label", "pihcore.relationshipsDashboardWidget.label"})), (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableOrderEntry() {
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"coreapps.activeDrugOrders", (String)"coreapps.patientdashboard.activeDrugOrders", null, null, null, null), (String)"coreapps", (String)"patientdashboard/activeDrugOrders"));
    }

    private void enablePatientDocuments() {
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardSecondColumn((AppDescriptor)CustomAppLoaderUtil.app((String)"attachments.app.patientDocuments", (String)"pihcore.patientDocuments.label", (String)"fas fa-fw fa-paperclip", null, (String)"App: attachments.attachments.page", null), (String)"coreapps", (String)"attachments/dashboardWidget"));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"attachments.patientDocuments.overallAction", (String)"pihcore.patientDocuments.overallAction.label", (String)"fas fa-fw fa-paperclip", (String)"link", (String)"coreapps/attachments/attachments.page?patient={{patient.uuid}}&patientId={{patient.patientId}}", (String)"App: attachments.attachments.page", null));
    }

    private void enableConditionList() {
        AppDescriptor conditionList = CustomAppLoaderUtil.app((String)"coreapps.conditionList", null, null, null, (String)"Task: Manage Condition Lists", null);
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)conditionList, (String)"coreapps", (String)"conditionlist/conditions"));
    }

    private void enableJ9() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"pih.app.j9Referrals", (String)"pih.app.j9Referrals.title", (String)"fa fa-fw fa-baby", (String)"spa/referrals-queue", (String)"Task: emr.enterMCH", null), (String)RequireUtil.sessionLocationHasTag((String)"Maternal and Child Location")));
    }

    private void enableCommentForm() {
        this.extensions.add(CustomAppLoaderUtil.visitAction((String)"pih.form.comment", (String)"pihcore.comment", (String)"fas fa-fw fa-pencil-alt", (String)"link", (String)CustomAppLoaderUtil.enterStandardHtmlFormLink((String)PihCoreUtil.getFormResource((String)"comment.xml")), null, null));
        HashMap<String, String> encounterParams = new HashMap<String, String>();
        encounterParams.put("encounterType", "c30d6e06-0f00-460a-8f81-3c39a1853b56");
        AppDescriptor notesSummary = CustomAppLoaderUtil.app((String)"pih.app.notes.summary", (String)"pih.app.notes.title", (String)"fas fa-comments", (String)this.addParametersToUrl(this.patientEncountersPageUrl, encounterParams), null, (ObjectNode)CustomAppLoaderUtil.objectNode((Object[])new Object[]{"widget", "obsacrossencounters", "icon", "fas fa-comments", "label", "pih.app.notes.title", "detailsUrl", this.addParametersToUrl(this.patientEncountersPageUrl, encounterParams), "encounterTypes", "c30d6e06-0f00-460a-8f81-3c39a1853b56", "concepts", "3cd9d956-26fe-102b-80cb-0017a47871b2", "sortOrder", "desc", "headers", "zl.date,pih.app.notes.title"}));
        this.apps.add(CustomAppLoaderUtil.addToClinicianDashboardFirstColumn((AppDescriptor)notesSummary, (String)"coreapps", (String)"dashboardwidgets/dashboardWidget"));
    }

    private void enableSpaPreview() {
        this.apps.add(CustomAppLoaderUtil.addToHomePage((AppDescriptor)CustomAppLoaderUtil.app((String)"spa.preview.home", (String)"pihcore.spa.home", (String)"fas fa-fw fa-exclamation-triangle", (String)"spa/home", (String)"App: coreapps.systemAdministration", null), null));
        this.extensions.add(CustomAppLoaderUtil.overallAction((String)"spa.preview.patientChart", (String)"pihcore.spa.patientChart", (String)"fas fa-fw fa-exclamation-triangle", (String)"link", (String)"spa/patient/{{patient.uuid}}/chart/summary", (String)"App: coreapps.systemAdministration", null));
    }

    private void registerLacollinePatientRegistrationEncounterTypes() {
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"f1c286d0-b83f-4cd4-8348-7ea3c28ead13", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-money-bill-alt");
        CustomAppLoaderUtil.registerTemplateForEncounterType((String)"1373cf95-06e8-468b-a3da-360ac1cf026d", (Extension)this.findExtensionById("defaultEncounterTemplate"), (String)"fas fa-fw fa-calendar");
    }

    private void addPaperRecordActionsIncludesIfNeeded() {
        if (!CustomAppLoaderUtil.containsExtension(this.extensions, (String)"paperrecord.patientDashboard.includes").booleanValue()) {
            this.extensions.add(CustomAppLoaderUtil.fragmentExtension((String)"paperrecord.patientDashboard.includes", (String)"paperrecord", (String)"patientdashboard/overallActionsIncludes", null, (String)"patientDashboard.includeFragments", null));
        }
    }

    private void configureAdditionalExtensions(Config config) {
        Collections.sort(config.getExtensions());
        for (Extension extension : config.getExtensions()) {
            this.extensions.add(extension);
        }
    }

    public AppDescriptor findAppById(String id) {
        for (AppDescriptor app : this.apps) {
            if (!app.getId().equals(id)) continue;
            return app;
        }
        this.log.warn((Object)("App Not Found: " + id));
        return null;
    }

    public Extension findExtensionById(String id) {
        for (Extension extension : this.extensions) {
            if (!extension.getId().equals(id)) continue;
            return extension;
        }
        this.log.warn((Object)("Extension Not Found: " + id));
        return null;
    }

    public void setReadyForRefresh(Boolean readyForRefresh) {
        this.readyForRefresh = readyForRefresh;
    }

    public void setApps(List<AppDescriptor> apps) {
        this.apps = apps;
    }

    public void setExtensions(List<Extension> extensions) {
        this.extensions = extensions;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public static enum ReportCategory {
        OVERVIEW,
        DAILY,
        DATA_EXPORT,
        DATA_QUALITY,
        MONITORING;

    }
}
