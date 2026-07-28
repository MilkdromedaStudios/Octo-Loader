# API rule packs and the equivalents table

Two different files live here, answering two different questions.

`equivalents.json` answers "this Minecraft does not have that". It names no eras
and applies to every mod, because it is consulted only *after* a class or member
has been looked up in the running game and not found. See
[the section below](#equivalentsjson).

`<from>-to-<to>.json` answers "this mod is from another era", and is described
first.

## API rule packs

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

## `equivalents.json`

Where a rule pack knows which two eras it bridges, this one knows nothing about
versions at all — and does not need to. Octo consults it only once a class or a
member has been looked for in the running game and has not been found, so an
entry that is wrong for the version you are on simply never fires.

```json
{
  "classes": [
    { "name": "net/minecraft/client/gui/LayeredDraw$Layer",
      "alternatives": ["net/minecraft/client/gui/LayeredDrawer$Layer"],
      "stub": "interface",
      "note": "why" }
  ],
  "methods": [
    { "owner": "net/minecraft/world/entity/LivingEntity",
      "name": "shouldTriggerItemUseEffects",
      "alternatives": ["isUsingItem"], "note": "why" }
  ],
  "fields": [
    { "owner": "net/minecraft/SystemReport", "name": "entries",
      "alternatives": ["details"], "note": "why" }
  ]
}
```

- `alternatives` are tried in order against the running game. The first one that
  exists wins; if none does, the reference is left for a stand-in to answer.
- `stub` says what may be fabricated when nothing survives: `interface` for a
  type mods only implement, `class` for one they extend or construct, and
  nothing at all when the entry does not say. This is the **only** way a
  `net.minecraft.*` class is ever fabricated — Octo will invent a missing corner
  of a loader API on its own, because that gap is Octo's, but inventing a piece
  of Minecraft is a judgement someone has to have written down.
- `owner` on a method or field entry may be omitted, in which case the entry
  answers for any class that asks.

The same entries are used for mixins: an injector, accessor, invoker or shadow
naming a member that is not on the target is re-aimed through this table, and
failing that through the shape of the target class itself. An injector with no
match under any name is marked optional rather than being allowed to abort the
launch.

Drop your own `equivalents.json` in `<gameDir>/.octo/api/` to extend it. Yours is
merged over the bundled one entry by entry.
