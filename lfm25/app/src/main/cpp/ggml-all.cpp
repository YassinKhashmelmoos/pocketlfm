// Combined GGML source for Android
// This file includes all GGML sources to ensure proper linking

// Include ggml.c first to make its functions available
#include "ggml.c"

// Then include CPU backend
#include "ggml-cpu/ggml-cpu.c"
