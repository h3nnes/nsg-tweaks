# Graph Report - nsg-band-hook  (2026-07-10)

## Corpus Check
- 35 files · ~48,328 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 74 nodes · 130 edges · 14 communities (5 shown, 9 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 1 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `915a121d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MainHook.java
- .HiddenFieldProbeHook
- HiddenFieldProbeHook.java
- HiddenFieldProbeHook
- .install
- NrSaHeaderPathlossHook
- NrSaCellColumnsHook
- .NrSaCellColumnsHook
- .NrSaHeaderPathlossHook
- Field
- Method
- XposedInterface
- NrSaModUsageRowHook

## God Nodes (most connected - your core abstractions)
1. `NrSaCellColumnsHook` - 19 edges
2. `NrSaHeaderPathlossHook` - 18 edges
3. `NrSaModUsageRowHook` - 11 edges
4. `NrSaPucchTxRowHook` - 11 edges
5. `MainHook` - 4 edges
6. `HeaderGeometry` - 3 edges

## Surprising Connections (you probably didn't know these)
- `NrSaModUsageRowHook` --references--> `Field`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaModUsageRowHook.java →   _Bridges community 13 → community 3_
- `NrSaHeaderPathlossHook` --references--> `XposedInterface`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaHeaderPathlossHook.java →   _Bridges community 3 → community 5_
- `NrSaHeaderPathlossHook` --references--> `ClassLoader`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaHeaderPathlossHook.java →   _Bridges community 13 → community 5_
- `NrSaCellColumnsHook` --references--> `ClassLoader`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaCellColumnsHook.java →   _Bridges community 6 → community 8_
- `NrSaCellColumnsHook` --references--> `Field`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaCellColumnsHook.java →   _Bridges community 6 → community 4_

## Import Cycles
- None detected.

## Communities (14 total, 9 thin omitted)

### Community 0 - "MainHook.java"
Cohesion: 0.33
Nodes (5): ModuleLoadedParam, MainHook, Override, PackageReadyParam, XposedModule

### Community 3 - "HiddenFieldProbeHook"
Cohesion: 0.28
Nodes (4): Field, Method, NrSaPucchTxRowHook, XposedInterface

### Community 4 - ".install"
Cohesion: 0.40
Nodes (4): ListView, Field, Method, TextView

### Community 5 - "NrSaHeaderPathlossHook"
Cohesion: 0.21
Nodes (6): HeaderGeometry, NrSaHeaderPathlossHook, SuppressWarnings, View, ViewGroup, WeakHashMap

## Knowledge Gaps
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `NrSaHeaderPathlossHook` connect `NrSaHeaderPathlossHook` to `MainHook.java`, `HiddenFieldProbeHook`, `NrSaModUsageRowHook`?**
  _High betweenness centrality (0.400) - this node is a cross-community bridge._
- **Why does `NrSaCellColumnsHook` connect `NrSaCellColumnsHook` to `.NrSaHeaderPathlossHook`, `.readPropertyWithIndex`, `.install`, `.NrSaCellColumnsHook`?**
  _High betweenness centrality (0.345) - this node is a cross-community bridge._
- **Why does `NrSaPucchTxRowHook` connect `HiddenFieldProbeHook` to `NrSaModUsageRowHook`?**
  _High betweenness centrality (0.133) - this node is a cross-community bridge._