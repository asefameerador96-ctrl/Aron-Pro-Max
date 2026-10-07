// Aliases over the GENERATED contract types (openapi.d.ts, never edited by hand: `npm run gen:contract`).
// Everything in the web app takes its wire types from here so a contract rename breaks `tsc` (docs/24 s1.3).
import type { components, paths } from "./openapi";

export type { components, paths };
export type Schemas = components["schemas"];

export type Role = Schemas["Role"];
export type UserSummary = Schemas["UserSummary"];
export type ScopeSummary = Schemas["ScopeSummary"];
export type NodeRef = Schemas["NodeRef"];
export type LoginRequest = Schemas["LoginRequest"];
export type LoginResponse = Schemas["LoginResponse"];
export type TokenPair = Schemas["TokenPair"];
export type MfaVerifyRequest = Schemas["MfaVerifyRequest"];
export type Me = Schemas["Me"];
export type Problem = Schemas["Problem"];
export type ProblemCode = Schemas["ProblemCode"];
export type AuditEntry = Schemas["AuditEntry"];
export type AuditPage = Schemas["AuditPage"];
export type Cluster = Schemas["Cluster"];
export type ClusterWrite = Schemas["ClusterWrite"];
export type ClusterPatch = Schemas["ClusterPatch"];
export type ClusterPage = Schemas["ClusterPage"];

export type ApiPath = keyof paths;
export type GeoNode = Schemas["GeoNode"];
export type GeoNodeWrite = Schemas["GeoNodeWrite"];
export type GeoNodePatch = Schemas["GeoNodePatch"];
export type GeoLevel = Schemas["GeoLevel"];
