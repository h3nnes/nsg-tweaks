# Graph Report - nsg-band-hook  (2026-07-11)

## Corpus Check
- 35 files · ~48,709 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 107 nodes · 179 edges · 16 communities (6 shown, 10 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 1 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `de46cbdf`
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

## God Nodes (most connected - your core abstractions)
1. `NrSaHeaderPathlossHook` - 21 edges
2. `NrSaCellColumnsHook` - 19 edges
3. `SettingsToggleHook` - 15 edges
4. `NrSaModUsageRowHook` - 11 edges
5. `NrSaPucchTxRowHook` - 11 edges
6. `LteCaMatrixUlQpskHook` - 9 edges
7. `MainHook` - 4 edges
8. `HeaderGeometry` - 3 edges

## Surprising Connections (you probably didn't know these)
- `LteCaMatrixUlQpskHook` --references--> `ClassLoader`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/LteCaMatrixUlQpskHook.java →   _Bridges community 3 → community 5_
- `NrSaModUsageRowHook` --references--> `XposedInterface`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaModUsageRowHook.java →   _Bridges community 3 → community 15_
- `NrSaModUsageRowHook` --references--> `ClassLoader`  [EXTRACTED]
  nsg-band-hook/app/src/main/java/com/nsgmod/band/NrSaModUsageRowHook.java →   _Bridges community 5 → community 15_

## Import Cycles
- None detected.

## Communities (16 total, 10 thin omitted)

### Community 0 - "MainHook.java"
Cohesion: 0.39
Nodes (5): ModuleLoadedParam, MainHook, Override, PackageReadyParam, XposedModule

### Community 3 - "HiddenFieldProbeHook"
Cohesion: 0.20
Nodes (5): Field, Method, LteCaMatrixUlQpskHook, NrSaPucchTxRowHook, XposedInterface

### Community 4 - ".install"
Cohesion: 0.14
Nodes (6): Constructor, ClassLoader, Field, Method, XposedInterface, SettingsToggleHook

### Community 5 - "NrSaHeaderPathlossHook"
Cohesion: 0.16
Nodes (9): ClassLoader, HeaderGeometry, NrSaHeaderPathlossHook, OnSharedPreferenceChangeListener, SharedPreferences, SuppressWarnings, View, ViewGroup (+1 more)

### Community 6 - "NrSaCellColumnsHook"
Cohesion: 0.16
Nodes (8): Context, ListView, ClassLoader, Field, Method, XposedInterface, NrSaCellColumnsHook, TextView

## Knowledge Gaps
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `NrSaHeaderPathlossHook` connect `NrSaHeaderPathlossHook` to `HiddenFieldProbeHook`?**
  _High betweenness centrality (0.300) - this node is a cross-community bridge._
- **Should `.install` be split into smaller, more focused modules?**
  _Cohesion score 0.14035087719298245 - nodes in this community are weakly interconnected._