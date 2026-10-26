# Jenkins with os-sast, opengrep and bandit installed on the controller itself: no Docker-in-Docker,
# no agents. Fine for a laptop POC; in production builds run on agents, never on the controller.
FROM jenkins/jenkins:lts-jdk21

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv curl \
 && rm -rf /var/lib/apt/lists/* \
 # the samples repo is bind-mounted and owned by another uid; let git read it
 && git config --system --add safe.directory '*'

# opengrep ships as a standalone binary (not on PyPI). Pinned version + checksum per architecture.
ARG OPENGREP_VERSION=1.32.0
ARG TARGETARCH
RUN set -eu; \
    case "${TARGETARCH:-amd64}" in \
      amd64) asset=opengrep_manylinux_x86;     sum=7360d60174880bd9214f2dfbe439b7bce82b7a3d44cfe945ba833c85cc8bda75 ;; \
      arm64) asset=opengrep_manylinux_aarch64; sum=dc6229319c0d68948733053786a5614873947e5ec1d4669cc0e94ae9038cb99d ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /usr/local/bin/opengrep \
      "https://github.com/opengrep/opengrep/releases/download/v${OPENGREP_VERSION}/${asset}"; \
    echo "${sum}  /usr/local/bin/opengrep" | sha256sum -c -; \
    chmod +x /usr/local/bin/opengrep

# "os-sast" is a named build context (docker-compose.yml: additional_contexts) pointing at ../os-sast
COPY --from=os-sast pyproject.toml README.md /opt/os-sast-src/
COPY --from=os-sast src /opt/os-sast-src/src
RUN python3 -m venv /opt/os-sast \
 && /opt/os-sast/bin/pip install --no-cache-dir "/opt/os-sast-src[scanners]" \
 && ln -s /opt/os-sast/bin/os-sast /opt/os-sast/bin/bandit /usr/local/bin/

USER jenkins
RUN jenkins-plugin-cli --plugins git workflow-aggregator configuration-as-code job-dsl

# Configuration as code: admin user + the "samples" pipeline job, so there is nothing to click.
COPY casc.yaml /usr/share/jenkins/ref/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml
