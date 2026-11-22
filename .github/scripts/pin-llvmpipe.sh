# Sourced by every script that starts a game on a Linux runner: it prints the CPU that renders the run
# and pins Mesa's llvmpipe to one rasterizer. llvmpipe compiles its shaders for the host's vector
# extensions, and the pool mixes AMD EPYC 7763 (AVX2), Intel Xeon 6975P-C and AMD EPYC 9V45 (AVX-512),
# so the same frame differed in single pixels between runs (#1550). GALLIUM_OVERRIDE_CPU_CAPS=sse4.1
# keeps SSE4.1 and drops AVX and above, and LP_NATIVE_VECTOR_WIDTH=128 sets 128-bit vectors, so every
# CPU class compiles the same code (Mesa's docs/envvars.rst and docs/drivers/llvmpipe.rst).
if [ -r /proc/cpuinfo ]; then
  echo "==> CPU: $(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //') | flags: $(grep -m1 '^flags' /proc/cpuinfo | grep -o -w 'avx2\|avx512f\|fma' | sort -u | tr '\n' ' ')"
fi
export GALLIUM_OVERRIDE_CPU_CAPS=sse4.1
export LP_NATIVE_VECTOR_WIDTH=128
echo "==> llvmpipe pinned: GALLIUM_OVERRIDE_CPU_CAPS=${GALLIUM_OVERRIDE_CPU_CAPS} LP_NATIVE_VECTOR_WIDTH=${LP_NATIVE_VECTOR_WIDTH}"
