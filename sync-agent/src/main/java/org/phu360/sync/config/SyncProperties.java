package org.phu360.sync.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "phu360.sync")
@Validated
public class SyncProperties {

  @NotBlank
  private String siteCode;

  @NotBlank
  private String hubSiteCode = "HUB";

  private String hubBaseUrl;

  @NotBlank
  private String localBaseUrl = "http://localhost:8080/openmrs/ws/fhir2/R4";

  private final Local local = new Local();
  private final Hub hub = new Hub();

  @Positive
  private int pageSize = 100;

  @Positive
  private long pollIntervalMs = 300000;

  @Positive
  private int overlapSeconds = 30;

  private String watermarkFile = "/var/lib/phu360-sync/watermarks.json";

  private boolean pushEnabled = true;
  private boolean pullEnabled = true;

  private List<String> resources = List.of(
      "Patient", "Person", "Encounter", "Observation",
      "Condition", "MedicationRequest", "MedicationDispense", "ServiceRequest",
      "Immunization", "AllergyIntolerance", "DiagnosticReport",
      "Practitioner", "Location"
  );

  public static class Local {
    @NotBlank
    private String username = "admin";
    @NotBlank
    private String password = "Admin123";

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
  }

  public static class Hub {
    private String username = "sync-bot";
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
  }

  public String getSiteCode() { return siteCode; }
  public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
  public String getHubSiteCode() { return hubSiteCode; }
  public void setHubSiteCode(String hubSiteCode) { this.hubSiteCode = hubSiteCode; }
  public String getHubBaseUrl() { return hubBaseUrl; }
  public void setHubBaseUrl(String hubBaseUrl) { this.hubBaseUrl = hubBaseUrl; }
  public String getLocalBaseUrl() { return localBaseUrl; }
  public void setLocalBaseUrl(String localBaseUrl) { this.localBaseUrl = localBaseUrl; }
  public Local getLocal() { return local; }
  public Hub getHub() { return hub; }
  public int getPageSize() { return pageSize; }
  public void setPageSize(int pageSize) { this.pageSize = pageSize; }
  public long getPollIntervalMs() { return pollIntervalMs; }
  public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
  public int getOverlapSeconds() { return overlapSeconds; }
  public void setOverlapSeconds(int overlapSeconds) { this.overlapSeconds = overlapSeconds; }
  public String getWatermarkFile() { return watermarkFile; }
  public void setWatermarkFile(String watermarkFile) { this.watermarkFile = watermarkFile; }
  public boolean isPushEnabled() { return pushEnabled; }
  public void setPushEnabled(boolean pushEnabled) { this.pushEnabled = pushEnabled; }
  public boolean isPullEnabled() { return pullEnabled; }
  public void setPullEnabled(boolean pullEnabled) { this.pullEnabled = pullEnabled; }
  public List<String> getResources() { return resources; }
  public void setResources(List<String> resources) { this.resources = resources; }
}
