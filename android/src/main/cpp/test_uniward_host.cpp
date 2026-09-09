// Host-only correctness check: not part of the Android build (CMakeLists.txt doesn't reference it).
// Compares computeUniwardCost's output against the Java UniwardCost.compute reference on identical
// synthetic input; the NEON path only compiles on __aarch64__, so this runs the scalar fallback --
// still verifies the shared algorithm (interior and edge/clamped code paths both), just not the
// vectorized inner loop itself.
//
//   g++ -O2 -std=c++17 -o /tmp/t test_uniward_host.cpp uniward_cost_core.cpp && /tmp/t > /tmp/cpp.txt
//   # then run UniwardCost.compute with the same seed/quant table from Java and diff the two outputs
#include <cstdio>
#include "uniward_cost_core.h"

int main() {
    // 24x24 plane (3x3 blocks): interior block (1,1) plus edge/corner blocks, deterministic pseudo-noise.
    const int planeH = 24, planeW = 24, blocksWide = 3, blocksHigh = 3;
    std::vector<double> plane(planeH * planeW);
    unsigned int seed = 12345;
    for (int i = 0; i < planeH * planeW; i++) {
        seed = seed * 1103515245u + 12345u;
        plane[i] = 100.0 + (double)((seed >> 16) % 156);
    }
    int quant[64];
    for (int k = 0; k < 64; k++) {
        quant[k] = 1 + (k % 20);
    }

    std::vector<double> cost = computeUniwardCost(plane.data(), planeH, planeW, blocksWide, blocksHigh, quant);

    // Print plane dims + full cost grid as CSV for the Java side to diff against.
    std::printf("planeH=%d planeW=%d blocksWide=%d blocksHigh=%d\n", planeH, planeW, blocksWide, blocksHigh);
    for (size_t i = 0; i < cost.size(); i++) {
        std::printf("%.10e%s", cost[i], (i + 1 == cost.size()) ? "\n" : ",");
    }
    return 0;
}
