// Steganography utility to hide messages into cover files
// Copyright (c) 2026 Nick Haghiri
//
// Pure algorithm core (no JNI/Android dependency), so it can be exercised by a host-compiled test
// alongside the real NDK build. See uniward_cost.cpp for the JNI marshaling wrapper.
#pragma once

#include <vector>

// Mirrors UniwardCost.compute (core/.../jpeguniward/UniwardCost.java). planeFlat is row-major
// [planeH][planeW]; quant is the 64-entry natural-order quant table. Returns numBlocks*64 doubles,
// block-major, matching the Java method's [blockIndex][64] layout flattened.
std::vector<double> computeUniwardCost(const double* planeFlat, int planeH, int planeW, int blocksWide,
                                        int blocksHigh, const int* quant);
