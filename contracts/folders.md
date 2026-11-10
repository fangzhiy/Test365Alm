# Project folders

Two independent trees, `TEST_PLAN` and `TEST_LAB`, belong to each project. A null parent is the virtual Subject/Root; existing objects remain at that root without a data rewrite. Names are trimmed, case sensitive, 1–200 characters and unique among siblings. Integer `sortOrder` is nonnegative; ties sort by name then identity.

All paths below are under `/api/v1/projects/{projectId}`. Read requires project membership; writes require an active project writer, same-origin CSRF and transaction-local RLS context. Tenant administration alone grants no access.

- `GET /folders?kind=TEST_PLAN|TEST_LAB`: complete flat adjacency list with `id,parentId,kind,name,sortOrder,rowVersion`; clients render the tree.
- `POST /folders`: `{kind,parentId,name,sortOrder}`, `Idempotency-Key` required; returns 201 and strong ETag. A key freezes the response for 24 hours; conflicting or expired reuse is rejected. Replay rechecks access.
- `PUT /folders/{id}`: `{parentId,name,sortOrder}`, strong positive `If-Match`; rename, reorder or move. Cycles and cross-tree/project parents are rejected.
- `DELETE /folders/{id}`: strong `If-Match`; child folders or placed objects cause 409 `FOLDER_NOT_EMPTY`.
- `GET /folder-placements/{kind}/{objectId}`: location and independent placement `rowVersion` (1 for an untouched root object).
- `PUT /folder-placements/{kind}/{objectId}`: `{folderId}` (null means root), strong `If-Match` using the placement version. This changes organization, never saved test revisions or execution manifests.
- Existing `GET /tests` and `GET /test-sets/page` accept optional `folderId` (`root` or UUID). Omission retains the full-project list; filtering happens before keyset pagination.

Project advisory locking serializes tree changes and placement moves. Database composite foreign keys enforce scope/type and database triggers reject cycles and nonempty deletes. Folder writes, placement writes, audit and the append-only organization Outbox share one transaction. Idempotency snapshots outlive folder deletion.
