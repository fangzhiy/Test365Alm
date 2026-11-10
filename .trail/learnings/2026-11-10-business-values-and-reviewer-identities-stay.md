---
title: Business values and reviewer identities stay with immutable content
author: "周"
date: 2026-11-10
branch: feat-business-fields
id: l-87dfbc34
scope: repo
status: active
src: agent
touches:
  - contracts/business-fields.md
---

## Decided
Business fields stay on exact immutable content revisions because mutable values would change sealed content and existing runs. Freeze each definition alongside its value because metadata changes must not reinterpret history. Reviewer values use local principal UUIDs because display names are not stable identities.

## Tried and ruled out
Metadata validation cannot persist entity values because it has no entity revision transaction. Automatic custom-definition creation during member writes was rejected because metadata writes require project admin rights. Versioned system defaults allow member business writes without expanding metadata permissions.

## Watch out
Omitted businessFields preserves legacy request hashes because durable replay keys depend on that encoding. New creates use an empty snapshot; edits inherit; explicit {} clears values. Later review actions must reauthorize the stored reviewer because membership can be revoked after assignment.

In the code: contracts/business-fields.md; BusinessFields.normalize; test_revision.business_fields.reviewer.value. Formal V23 belongs to business fields, V24 to execution, intentional failure probe V25 is not a formal migration count.
