ARG TRINO_VERSION
FROM trinodb/trino-core:$TRINO_VERSION

ARG VERSION

COPY . .
# Switch to root to install packages
USER root

# Install prerequisites, Java 24 (Temurin), and Maven
RUN apt-get update && \
    apt-get install -y wget gnupg curl && \
    # Add Eclipse Temurin (Java 24) repo
    wget -O- https://packages.adoptium.net/artifactory/api/gpg/key/public | apt-key add - && \
    echo "deb https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y temurin-24-jdk maven && \
    # Clean up
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Verify installation
RUN java -version && mvn -version

# Switch back to Trino user
USER trino

RUN mvn clean package -DskipTests=true


ADD target/trino-openapi-$VERSION/ /usr/lib/trino/plugin/openapi/
ADD catalog/ /etc/trino/catalog/disabled/
ADD docker-entrypoint.sh /usr/local/bin/

ENV OPENAPI_AUTH_TYPE=none \
    OPENAPI_CLIENT_ID="" \
    OPENAPI_CLIENT_SECRET="" \
    OPENAPI_USERNAME="" \
    OPENAPI_PASSWORD="" \
    OPENAPI_BEARER_TOKEN="" \
    OPENAPI_API_KEY_NAME="" \
    OPENAPI_API_KEY_VALUE="" \
    OPENAPI_API_KEYS="" \
    OPENAPI_MAX_REQUESTS_PER_SECOND="1.7976931348623157e+308" \
    OPENAPI_MAX_SPLITS_PER_SECOND="1.7976931348623157e+308" \
    OPENAPI_DOMAIN_EXPANSION_LIMIT="256"

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["/usr/lib/trino/bin/run-trino"]
