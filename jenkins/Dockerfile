# Jenkins with os-sast, semgrep and bandit installed on the controller itself: no Docker-in-Docker,
# no agents. Fine for a laptop POC; in production builds run on agents, never on the controller.
FROM jenkins/jenkins:lts-jdk21

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv curl \
 && rm -rf /var/lib/apt/lists/* \
 # the samples repo is bind-mounted and owned by another uid; let git read it
 && git config --system --add safe.directory '*'

# "os-sast" is a named build context (docker-compose.yml: additional_contexts) pointing at ../os-sast
COPY --from=os-sast pyproject.toml README.md /opt/os-sast-src/
COPY --from=os-sast src /opt/os-sast-src/src
RUN python3 -m venv /opt/os-sast \
 && /opt/os-sast/bin/pip install --no-cache-dir "/opt/os-sast-src[scanners]" \
 && ln -s /opt/os-sast/bin/os-sast /opt/os-sast/bin/semgrep /opt/os-sast/bin/bandit /usr/local/bin/

USER jenkins
RUN jenkins-plugin-cli --plugins git workflow-aggregator configuration-as-code job-dsl

# Configuration as code: admin user + the "samples" pipeline job, so there is nothing to click.
COPY casc.yaml /usr/share/jenkins/ref/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml
