# Dev seed loader (lead request 2026-10-07, SR slice smoke): the psql client image the dblogins job already uses,
# plus the argon2 CLI and db/seed/0*.sql. Built and run only when ARON_DEV_SEED=true on a dev profile; never in
# stage or prod (db/seed is development data). Context: a temp folder with the seed files and devseed-run.sh.
ARG PSQL_IMAGE
FROM ${PSQL_IMAGE}
RUN apk add --no-cache argon2
COPY --chmod=0444 0*.sql /seed/
COPY --chmod=0555 devseed-run.sh /seed/run.sh
USER postgres
ENTRYPOINT ["/seed/run.sh"]
