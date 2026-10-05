// Runs the standalone server produced by `next build` (output: "standalone"). The build leaves public/ and .next/static
// out of the standalone folder (a CDN normally serves them); copy them in so the server is self-contained. The Docker
// image of the infra lane can run exactly this file. Env: PORT (3000), HOSTNAME (0.0.0.0).
import { cpSync, existsSync } from "node:fs";
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const standalone = join(root, ".next", "standalone");
if (!existsSync(join(standalone, "server.js"))) {
  console.error("No standalone build found: run `npm run build` first.");
  process.exit(1);
}
if (existsSync(join(root, "public"))) cpSync(join(root, "public"), join(standalone, "public"), { recursive: true });
cpSync(join(root, ".next", "static"), join(standalone, ".next", "static"), { recursive: true });
const child = spawn(process.execPath, ["server.js"], { cwd: standalone, stdio: "inherit", env: process.env });
child.on("exit", (code) => process.exit(code ?? 0));
for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => child.kill(sig));
