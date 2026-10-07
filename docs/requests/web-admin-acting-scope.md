# Request: end date for an acting (temporary) scope assignment

Lane: web-admin. Needed by: user and scope page (`/admin/user-scope/<id>`, F-ADM-006).

## Need
An administrator must be able to give a user a temporary acting scope that ends on a date (cover for leave or a transfer in progress).

## Gap
The write schema for `PUT /v1/admin/users/{id}/scope` carries no per-node `valid_to`, so an end date cannot be expressed. The page can only set or replace the scope, and ending it is a second manual edit.

## Proposed shape
Add an optional `valid_to` (date, Asia/Dhaka business date) to each node entry of the scope write body, and return it on the read. The server treats the node as out of scope after that date.

## Stub
The page sets scope without an end date. See the `REQUEST` comment in `web/src/app/admin/user-scope/[id]/page.tsx`.
