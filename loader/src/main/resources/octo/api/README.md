# API rule packs

Each file here describes what changed between two eras of Minecraft, for the
changes a rename table cannot express: a method that moved to another class, an
instance method that became static, a field that turned into a getter, an API
that was deleted.

Octo loads `<from>-to-<to>.json` for every step between a mod's era and the
running game's, and applies them in order. The era ids are the ones in
`studios.milkdromeda.octo.compat.Era`: `classic`, `alpha`, `beta`, `modloader`,
`fml`, `searge`, `flattening`, `modern`, `current`.

## Adding your own

Drop a file with the same name into `<gameDir>/.octo/api/`. It is merged on top
of the bundled pack, so you can extend or override anything here without
rebuilding the loader. That is the intended way to teach Octo about a mod nobody
has handled yet.

```json
{
  "from": "searge",
  "to": "current",
  "classes": [
    { "from": "old/pkg/Name", "to": "new/pkg/Name", "note": "why" }
  ],
  "methods": [
    { "owner": "some/Owner", "name": "oldName", "desc": "()V",
      "newOwner": "some/Owner", "newName": "newName", "note": "why" }
  ],
  "fields": [
    { "owner": "some/Owner", "name": "field", "desc": "I",
      "newName": "getField", "note": "became a getter" }
  ],
  "removed": [
    { "owner": "some/Owner", "name": "gone", "note": "no replacement exists" }
  ]
}
```

`kind` may be set explicitly to `CLASS_MOVED`, `METHOD_MOVED`,
`METHOD_TO_STATIC`, `FIELD_TO_METHOD`, `REDIRECT_TO_SHIM` or `REMOVED`.
`desc` is optional and, when omitted, matches any descriptor.

## What belongs here, and what does not

These packs are for *documented API changes*. Bulk renames — the tens of
thousands of `func_71410_x` to `getInstance` pairs — belong in a mapping file in
`<gameDir>/.octo/mappings/`, which Octo reads in SRG, TSRG, TSRG2 and Tiny v2
format. Rule packs are the small, hand-written layer on top.
