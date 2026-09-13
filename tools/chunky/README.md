# Chunky compatibility patch

This separate GPL-3.0 component adapts **Chunky 1.5.3** for large rectangular Underilla jobs. It is not shaded into the Underilla plugin. `LICENSE` applies to this directory.

Changes:

- `region-rectangle-v1` traverses the same rectangle as Chunky's loop, grouped by 32x32 region, without storing the entire coordinate list.
- Existing pattern names retain their original traversal. Old checkpoints are never reinterpreted automatically.
- Checkpoints record the contiguous completed prefix, so asynchronous completion cannot skip a hole. Failed requests pause the task and remain retryable.
- Pause and completion drain outstanding chunk requests before saving and releasing the task.

The factory and generation task derive from [Chunky upstream](https://github.com/pop4959/Chunky/tree/master/common/src/main/java/org/popcraft/chunky), retrieved 2026-09-13 and checked against the installed binary. The build requires the exact original jar SHA-256 `530d2c7430a96a39957391b7088be144daa3108f7665896d1c23aa8dd4af32f3`.

```powershell
./tools/chunky/build.ps1 -ChunkyJar /path/to/original/Chunky-1.5.3.jar -JavaHome /path/to/java-25 -OutputJar /path/to/Chunky-tiled.jar
```

Replace the server's Chunky jar while stopped; retain the original backup. Underilla discovers the new iterator for fresh jobs. For an existing loop job, back up its checkpoint, change its pattern to `region-rectangle-v1`, and reset `chunks` to `0`. Keep the original selection and elapsed time. Chunky checks existing chunks and reuses those already fully generated; the new traversal revisits the rest. Never carry a loop cursor into tile order.

A tile checkpoint requires this patched Chunky build on subsequent resumes. The updated Underilla refuses a resume if a dependency replacement would reinterpret that checkpoint using another traversal.
