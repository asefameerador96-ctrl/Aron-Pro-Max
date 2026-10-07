# Dev seed loader (lead request 2026-10-07, SR slice smoke): the psql client image the dblogins job already uses,
# plus the argon2 CLI and db/seed/0*.sql (without 04, the global dev relaxations) and the smoke outlet. Built and run
# only on a dev profile with param devSeed = true; never in stage or prod. Context: a temp folder built by deploy.sh.
ARG PSQL_IMAGE
FROM ${PSQL_IMAGE}
RUN apk add --no-cache argon2
COPY --chmod=0444 0*.sql /seed/
COPY --chmod=0555 devseed-run.sh /seed/run.sh
USER postgres
ENTRYPOINT ["/seed/run.sh"]
