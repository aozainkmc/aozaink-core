# Reduced ONNX Runtime

The reduced runtime is compiled from ONNX Runtime `v1.20.0` for the unified
`mix_flash_v1` model. It retains the kernels needed to load the original ONNX
graph, the fused kernels produced by optimization, and the NCHWc kernels that
the Windows CPU optimizer creates while loading the model.

Run from the repository root on Windows with Visual Studio 2022 Build Tools,
JDK 21, CMake, Git, and Python available:

```powershell
.\tools\onnxruntime\build_reduced_runtime.ps1 `
  -Python python `
  -CMakePath cmake `
  -JavaHome $env:JAVA_HOME
```

The script clones fixed source revisions below `build/`, patches the ORT 1.20
MSVC compatibility include, compiles only the configured kernels, runs both
image and dynamic-length trajectory inference, and then updates:

`runtime/reduced/win-x64/onnxruntime-1.20.0-reduced.jar`

It reads and hashes the model before and after the build. It never writes to
`runs_mix_flash_v1`, `mixsingle`, or model/checkpoint files.

The GitHub Actions workflow builds and smoke-tests Windows x64, Linux x64,
Linux arm64, macOS x64, and macOS arm64 on matching native runners. It merges
only their required dynamic libraries into one universal runtime and then
builds the three Core variants. Download its universal runtime artifact into:

`runtime/reduced/universal/onnxruntime-1.20.0-reduced.jar`

On Linux and macOS the Unix script strips local symbols from both native
libraries before the smoke test (`strip --strip-unneeded` on Linux, `strip -x`
plus an ad-hoc `codesign` on macOS); the exported C API and JNI symbols stay.
Windows DLLs carry no symbol table, so the Windows build is unchanged.

Normal Gradle builds do not recompile ONNX Runtime. With the universal runtime
present they produce three choices in Core (and the Molu workspace bundle)
`build/libs` directories:

- no classifier: complete official cross-platform ONNX Runtime (fallback);
- `-thin`: no embedded runtime, for environments that provide it separately;
- `-reduced`: one model-specific runtime containing all five desktop targets.

In the `-reduced` variant the native libraries are not left inside the
embedded runtime JAR. The `packReducedNatives` task moves them into
`META-INF/aozaink_core/onnxruntime-native/<platform>/` as xz files, together
with an `index.properties` of their sizes and SHA-256 hashes, and embeds the
`org.tukaani:xz` decoder next to the runtime. On first start Core unpacks the
current platform's two libraries into `<game dir>/.molu/cache/onnxruntime/`
and points `onnxruntime.native.path` there; later starts only check the cached
file sizes. `reducedRuntimeTest` runs the Core tests through this path.

Install exactly one variant. Before the universal CI artifact has been copied
in, local Windows builds intentionally emit `-reduced-win-x64` instead of
pretending the partial runtime is cross-platform. The complete artifact always
remains the cross-platform fallback.
