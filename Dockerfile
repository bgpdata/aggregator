# Copyright (c) 2021-2022 Cisco Systems, Inc. and others.
# All rights reserved.
FROM bgpdata/base:latest AS build

ARG VERSION=0.0.0

COPY . /ws
WORKDIR /ws

RUN cd /ws/protocol \
    && mvn clean install \
    && cd /ws \
    && mvn clean package

FROM openjdk:17-slim

# Copy files from previous stages
COPY --from=build /ws/target/aggregator-consumer-0.1.0-SNAPSHOT.jar /usr/local/bgpdata/aggregator-consumer.jar
COPY --from=build /ws/database/  /usr/local/bgpdata/database
COPY  --chmod=755 --from=build /ws/cron_scripts/gen-whois/*.py /usr/local/bgpdata/
COPY  --chmod=755 --from=build /ws/cron_scripts/peeringdb/*.py /usr/local/bgpdata/
COPY  --chmod=755 --from=build /ws/cron_scripts/rpki/*.py /usr/local/bgpdata/
COPY  --chmod=755 --from=build /ws/scripts/geo-csv-to-psql.py /usr/local/bgpdata/
COPY  --chmod=755 --from=build /ws/scripts/db-ip-import.sh /usr/local/bgpdata/

# Add files
COPY  --chmod=755 scripts/run.sh /usr/sbin/run

# Define persistent data volumes
VOLUME ["/config"]

# Consumer JMX console
EXPOSE 9005

# Define working directory.
WORKDIR /tmp

# Base setup tasks
RUN touch /usr/local/version-${VERSION} \
    && chmod 755 /usr/local/bgpdata/*.py

# Install dependencies
RUN apt-get update && \
    apt-get install --allow-unauthenticated -y \
        unzip curl wget whois vim rsyslog cron rsync kafkacat \
        procps python3-minimal python3-distutils python3-psycopg2 \
        python3-dnspython postgresql-client \
        --fix-missing && \
    ln -s /usr/bin/python3 /usr/bin/python

RUN cd /tmp && curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py \
    && python3 get-pip.py

RUN pip install ipaddr pykafka click netaddr

RUN pip3 install urllib3 requests

# Cleanup
RUN apt-get autoremove && apt-get clean

# Define default command.
CMD ["/usr/sbin/run"]
