"""Thin psql wrapper (no driver dependency). Connection: libpq environment (PGHOST, PGDATABASE, PGPASSWORD or an Entra token in PGPASSWORD ...)
or a --dsn WITHOUT a password. SQL goes to psql on stdin so :'name' variables are real parameters (quoted by psql), never string-built.
Errors are reduced to the SQLSTATE (VERBOSITY=sqlstate): server messages quote offending values and must not reach a log."""
import os
import re
import subprocess


class DbError(RuntimeError):
    pass


class Db:
    def __init__(self, dsn=None, psql="psql", role=None, sync_commit_off=False):
        self.dsn, self.psql, self.role, self.sync_off = dsn, psql, role, sync_commit_off

    def _env(self):
        env = os.environ.copy()
        opts = []
        if self.role:
            opts.append(f"-c role={self.role}")
        if self.sync_off:
            opts.append("-c synchronous_commit=off")
        if opts:
            env["PGOPTIONS"] = (env.get("PGOPTIONS", "") + " " + " ".join(opts)).strip()
        return env

    def _base(self, vars=None, single_tx=False):
        cmd = [self.psql, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=" + ("default" if os.environ.get("APSIS_DEBUG_ERRORS") == "1" else "sqlstate")]
        for k, v in (vars or {}).items():
            cmd += ["-v", f"{k}={v}"]
        if single_tx:
            cmd.append("-1")
        if self.dsn:
            cmd.append(self.dsn)
        return cmd

    def _fail(self, p):
        err = p.stderr.decode("utf-8", "replace")
        m = re.search(r"ERROR:\s+([0-9A-Z]{5})\s*$", err, re.M)
        msg = f"database error SQLSTATE {m.group(1)}" if m else "psql failed (connection or client error)"
        if os.environ.get("APSIS_DEBUG_ERRORS") == "1":       # development only: the server text may quote data; never set in the job
            msg += " | " + " ".join(err.split())[:300]
        raise DbError(msg)

    def run(self, sql, vars=None, single_tx=False):
        p = subprocess.run(self._base(vars, single_tx) + ["-f", "-"], input=sql.encode("utf-8"), capture_output=True, env=self._env())
        if p.returncode != 0:
            self._fail(p)
        return p.stdout.decode("utf-8", "replace")

    def file(self, path, vars=None, single_tx=True):
        p = subprocess.run(self._base(vars, single_tx) + ["-f", str(path)], capture_output=True, env=self._env())
        if p.returncode != 0:
            self._fail(p)
        return p.stdout.decode("utf-8", "replace")

    def scalar(self, sql, vars=None):
        out = self.run(sql, vars).strip()
        return out.splitlines()[0] if out else None

    def copy_in(self, table_sql, columns_sql, data: bytes):
        """table_sql and columns_sql are already-quoted identifiers built from validated names."""
        p = subprocess.run(self._base() + ["-c", f"COPY {table_sql} ({columns_sql}) FROM STDIN"], input=data, capture_output=True, env=self._env())
        if p.returncode != 0:
            self._fail(p)
