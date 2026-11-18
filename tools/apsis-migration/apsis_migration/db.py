"""Thin psql wrapper (no driver dependency). Connection: libpq environment (PGHOST, PGDATABASE, ...) or --dsn."""
import os
import subprocess


class DbError(RuntimeError):
    pass


class Db:
    def __init__(self, dsn=None, psql="psql"):
        self.dsn = dsn
        self.psql = psql

    def _base(self):
        cmd = [self.psql, "-X", "-q", "-v", "ON_ERROR_STOP=1"]
        if self.dsn:
            cmd.append(self.dsn)
        return cmd

    def run(self, sql, input_bytes=None, vars=None, single_tx=False):
        """Run SQL text; returns stdout. Errors raise DbError with the server message (never row data: our SQL does not echo rows)."""
        cmd = self._base() + ["-A", "-t"]
        for k, v in (vars or {}).items():
            cmd += ["-v", f"{k}={v}"]
        if single_tx:
            cmd.append("-1")
        cmd += ["-c", sql]
        p = subprocess.run(cmd, input=input_bytes, capture_output=True, env=os.environ.copy())
        if p.returncode != 0:
            raise DbError(p.stderr.decode("utf-8", "replace").strip().splitlines()[-1][:300] if p.stderr else "psql failed")
        return p.stdout.decode("utf-8", "replace")

    def file(self, path, vars=None, single_tx=True):
        """Run a SQL file (psql -f) so \\set / :var substitution works. Single transaction by default."""
        cmd = self._base() + ["-A", "-t"]
        for k, v in (vars or {}).items():
            cmd += ["-v", f"{k}={v}"]
        if single_tx:
            cmd.append("-1")
        cmd += ["-f", str(path)]
        p = subprocess.run(cmd, capture_output=True, env=os.environ.copy())
        if p.returncode != 0:
            msg = p.stderr.decode("utf-8", "replace").strip().splitlines()
            raise DbError((msg[-1] if msg else "psql failed")[:300])
        return p.stdout.decode("utf-8", "replace")

    def scalar(self, sql):
        out = self.run(sql).strip()
        return out.splitlines()[0] if out else None

    def copy_in(self, table, columns, data: bytes):
        cols = ", ".join(f'"{c}"' for c in columns)
        self.run(f"COPY {table} ({cols}) FROM STDIN", input_bytes=data)
