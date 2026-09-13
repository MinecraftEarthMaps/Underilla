# Optional Paper performance adapter

This startup adapter is for the prepared Paper **26.2 build 123** generation server. It leaves the Paper jar on disk unchanged and applies two small method adaptations when their classes load. Exact original class hashes are checked at build time and runtime; an unmatched class keeps its original behavior.

1. For Underilla's `BiomeProviderFromFile` only, call its four-argument biome method directly. That method already obtains vanilla climate when needed. Paper therefore stops calculating and discarding climate on Underilla cache hits. Other biome providers keep their normal five-argument callback. The prepared configuration uses VoidWorldGenerator outside the reference world; custom external biome providers with different overload behavior have not been validated with this adapter.
2. For random-access vanilla surface-rule lists, use indexed iteration with the same order, null handling and first-match return. Linked lists keep the original iterator path. This retains bedrock, deepslate and all vanilla surface rules.

```powershell
./tools/paper-performance/build.ps1 -ServerDirectory /path/to/server -JavaHome /path/to/java-25 -OutputJar /path/to/UnderillaPaperPerformance.jar
```

Add `-javaagent:UnderillaPaperPerformance.jar` before `-jar paper.jar`. For isolated measurements, the agent argument can be `=climate` or `=surface`; the default applies both. Removing the launch argument disables the adapter. Keep this adapter paired with the validated Underilla build and configuration; retest when updating either plugin or Paper.

The transformer uses the ASM libraries already bundled with this Paper server at build time. The runtime adapter uses only the JDK. Validation exercises the actual patched classes on Paper, including another provider that deliberately returns different results from the four- and five-argument callbacks.
