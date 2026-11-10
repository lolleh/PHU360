# Docker image for the PHU360 (Sierra Leone OpenMRS) server.
#
# Build context is ./distro; the payload is produced by:
#   scripts/seed-distro-maven-repo.sh && scripts/build-distro.sh
# which runs the OpenMRS SDK build-distro against distro/openmrs-distro.properties
# and writes the six artifacts below into target/distro/web.
#
# Mirrors the SDK's own generated Dockerfile but pins the stable core image.
FROM openmrs/openmrs-core:2.8.9

COPY target/distro/web/openmrs_core/openmrs.war /openmrs/distribution/openmrs_core/

# Route the OpenMRS root to the styled O3 SPA login and redirect the legacy
# login.htm to the SPA so the login page displays with the custom theme.
# Patch the WAR at build time: startup.sh wipes the exploded webapp and
# recopies this WAR on every container start, so exploded-webapp patches
# would be lost on restart.
RUN set -e && \
  rm -rf /tmp/openmrs-warp && mkdir -p /tmp/openmrs-warp && \
  cd /tmp/openmrs-warp && \
  unzip -q /openmrs/distribution/openmrs_core/openmrs.war && \
  sed -i 's|<welcome-file>index.htm</welcome-file>|<welcome-file>spa/index.html</welcome-file>|' WEB-INF/web.xml && \
  sed -i 's|</Context>|  <Valve className="org.apache.catalina.valves.rewrite.RewriteValve" />\n</Context>|' META-INF/context.xml && \
  printf 'RewriteRule ^/openmrs/login\\.htm$ /openmrs/spa/ [R]\n' > WEB-INF/rewrite.config && \
  zip -q -r /openmrs/distribution/openmrs_core/openmrs.war . && \
  cd / && rm -rf /tmp/openmrs-warp

COPY target/distro/web/openmrs-distro.properties /openmrs/distribution/
COPY target/distro/web/openmrs_modules /openmrs/distribution/openmrs_modules
COPY target/distro/web/openmrs_config /openmrs/distribution/openmrs_config
COPY target/distro/web/openmrs_owas /openmrs/distribution/openmrs_owas
COPY target/distro/web/openmrs_spa /openmrs/distribution/openmrs_spa