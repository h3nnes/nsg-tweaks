# Graph Report - nsg-band-hook  (2026-07-13)

## Corpus Check
- 37 files · ~50,844 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 128 nodes · 230 edges · 18 communities (7 shown, 11 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS · INFERRED: 1 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7f825075`
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
- ClassLoader
- Field
- Method
- Field
- Method
- XposedInterface
- XposedInterface
- NrSaModUsageRowHook
- ClassLoader
- NrSaPucchTxRowHook

## God Nodes (most connected - your core abstractions)
1. `NrSaHeaderPathlossHook` - 21 edges
2. `NrSaCellColumnsHook` - 19 edges
3. `LteCellColorHook` - 16 edges
4. `SettingsToggleHook` - 15 edges
5. `NrSaModUsageRowHook` - 11 edges
6. `NrSaPucchTxRowHook` - 11 edges
7. `RenamePuschTxLabelHook` - 9 edges
8. `LteCaMatrixUlQpskHook` - 9 edges
9. `MainHook` - 4 edges
10. `HeaderGeometry` - 3 edges

## Surprising Connections (you probably didn't know these)
- `LteCellColorHook` --references--> `ClassLoader`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/LteCellColorHook.java →   _Bridges community 16 → community 15_
- `LteCellColorHook` --references--> `Field`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/LteCellColorHook.java →   _Bridges community 16 → community 3_
- `NrSaHeaderPathlossHook` --references--> `XposedInterface`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaHeaderPathlossHook.java →   _Bridges community 3 → community 5_
- `NrSaModUsageRowHook` --references--> `XposedInterface`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaModUsageRowHook.java →   _Bridges community 3 → community 15_
- `NrSaPucchTxRowHook` --references--> `XposedInterface`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaPucchTxRowHook.java →   _Bridges community 3 → community 17_

## Import Cycles
- None detected.

## Communities (18 total, 11 thin omitted)

### Community 0 - "MainHook.java"
Cohesion: 0.39
Nodes (5): ModuleLoadedParam, MainHook, Override, PackageReadyParam, XposedModule

### Community 3 - "HiddenFieldProbeHook"
Cohesion: 0.30
Nodes (5): Context, Field, Method, LteCaMatrixUlQpskHook, XposedInterface

### Community 4 - ".install"
Cohesion: 0.14
Nodes (6): Constructor, ClassLoader, Field, Method, XposedInterface, SettingsToggleHook

### Community 5 - "NrSaHeaderPathlossHook"
Cohesion: 0.16
Nodes (8): HeaderGeometry, NrSaHeaderPathlossHook, OnSharedPreferenceChangeListener, SharedPreferences, SuppressWarnings, View, ViewGroup, WeakHashMap

### Community 6 - "NrSaCellColumnsHook"
Cohesion: 0.16
Nodes (7): ListView, ClassLoader, Field, Method, XposedInterface, NrSaCellColumnsHook, TextView

### Community 15 - "NrSaModUsageRowHook"
Cohesion: 0.22
Nodes (3): ClassLoader, NrSaModUsageRowHook, RenamePuschTxLabelHook

## Knowledge Gaps
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `LteCellColorHook` connect `ClassLoader` to `HiddenFieldProbeHook`, `NrSaModUsageRowHook`?**
  _High betweenness centrality (0.232) - this node is a cross-community bridge._
- **Should `.install` be split into smaller, more focused modules?**
  _Cohesion score 0.14035087719298245 - nodes in this community are weakly interconnected._