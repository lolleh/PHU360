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

# Servlet filter source used to patch the WAR at build time (redirects
# /openmrs/login.htm to the SPA login page). Copied before the RUN step
# below so it is available inside the image during the build.
COPY files/LoginRedirectFilter.java /openmrs/distribution/openmrs_core/LoginRedirectFilter.java

# Route the OpenMRS root to the styled O3 SPA login and redirect the legacy
# login.htm to the SPA. Patch the WAR at build time: startup.sh wipes the
# exploded webapp and recopies this WAR on every container start, so
# exploded-webapp patches would be lost on restart.
RUN set -e && \
  rm -rf /tmp/openmrs-warp && mkdir -p /tmp/openmrs-warp && \
  cd /tmp/openmrs-warp && \
  jar xf /openmrs/distribution/openmrs_core/openmrs.war && \
  sed -i 's|<welcome-file>index.htm</welcome-file>|<welcome-file>spa/index.html</welcome-file>|' WEB-INF/web.xml && \
  sed -i 's|</Context>|  <Valve className="org.apache.catalina.valves.rewrite.RewriteValve" />\n</Context>|' META-INF/context.xml && \
  printf 'RewriteRule ^/$ spa/index.html [R]\n' > WEB-INF/rewrite.config && \
  # Add a servlet filter that redirects /openmrs/login.htm to the styled SPA
  # login page. The RewriteValve does not reliably match login.htm requests
  # (handled by OpenMRS's PageController), so a filter mapped to /login.htm
  # is used instead. Compiled with the image's JDK.
  mkdir -p WEB-INF/classes && \
  cp /openmrs/distribution/openmrs_core/LoginRedirectFilter.java WEB-INF/classes/ && \
  javac -cp /usr/local/tomcat/lib/servlet-api.jar -d WEB-INF/classes WEB-INF/classes/LoginRedirectFilter.java && \
  rm WEB-INF/classes/LoginRedirectFilter.java && \
  # Register the filter to intercept /login.htm before OpenMRS's PageController.
  # Insert filter + mapping after the charsetFilter comment (first filter in web.xml).
  sed -i '/This charsetFilter MUST BE THE FIRST FILTER/a\  <filter>\n    <filter-name>loginRedirectFilter</filter-name>\n    <filter-class>LoginRedirectFilter</filter-class>\n  </filter>\n  <filter-mapping>\n    <filter-name>loginRedirectFilter</filter-name>\n    <url-pattern>\/login.htm<\/url-pattern>\n  </filter-mapping>' WEB-INF/web.xml && \
  jar cf /tmp/openmrs-warp/openmrs.war . && \
  cp -f /tmp/openmrs-warp/openmrs.war /openmrs/distribution/openmrs_core/openmrs.war && \
  cd / && rm -rf /tmp/openmrs-warp

COPY target/distro/web/openmrs-distro.properties /openmrs/distribution/
COPY target/distro/web/openmrs_modules /openmrs/distribution/openmrs_modules
COPY target/distro/web/openmrs_config /openmrs/distribution/openmrs_config
COPY target/distro/web/openmrs_owas /openmrs/distribution/openmrs_owas
COPY target/distro/web/openmrs_spa /openmrs/distribution/openmrs_spa
