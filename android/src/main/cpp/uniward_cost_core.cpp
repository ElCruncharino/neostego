// Steganography utility to hide messages into cover files
// Copyright (c) 2026 Nick Haghiri
//
// NEON-accelerated port of UniwardCost.compute (core/.../jpeguniward/UniwardCost.java). Mirrors the
// Java algorithm structure directly for correctness confidence; only the hot inner loops differ. Not
// required to be bit-identical to the Java path -- see UniwardCostAccelerator's javadoc for why.

#include "uniward_cost_core.h"

#include <cmath>

// Double-precision NEON (vld1q_f64 etc.) is AArch64-only; ARMv7 NEON covers single-precision only.
#if defined(__aarch64__)
#include <arm_neon.h>
#define HAVE_NEON 1
#endif

namespace {

constexpr int LF = 16;
constexpr int CENTER = LF / 2;   // 8
constexpr int PAT = 8 + LF - 1;  // 23
constexpr int OFF = LF - 1;      // 15
constexpr double SIGMA = 1.0 / 64.0;

const double HPDF[LF] = {
    -0.0544158422, 0.3128715909, -0.6756307363, 0.5853546837, 0.0158291053, -0.2840155430,
    -0.0004724846, 0.1287474266, 0.0173693010,  -0.0440882539, -0.0139810279, 0.0087460940,
    0.0048703530,  -0.0003917404, -0.0006754494, -0.0001174768};

inline int clampi(int v, int n) { return v < 0 ? 0 : (v >= n ? n - 1 : v); }

void dctMatrix(double a[8][8]) {
    const double c0 = std::sqrt(1.0 / 8.0);
    const double c = std::sqrt(2.0 / 8.0);
    for (int u = 0; u < 8; u++) {
        for (int n = 0; n < 8; n++) {
            a[u][n] = (u == 0) ? c0 : c * std::cos((2 * n + 1) * u * M_PI / 16.0);
        }
    }
}

// abs[u][s'] = |sum_k filter[k] * a[u][s'+k-OFF]|, u=0..7, s'=0..PAT-1
void response(const double a[8][8], const double* filter, double out[8][PAT]) {
    for (int u = 0; u < 8; u++) {
        for (int s = 0; s < PAT; s++) {
            double acc = 0.0;
            for (int k = 0; k < LF; k++) {
                int idx = s + k - OFF;
                if (idx >= 0 && idx < 8) {
                    acc += filter[k] * a[u][idx];
                }
            }
            out[u][s] = std::fabs(acc);
        }
    }
}

// Separable filter over the whole plane -> 1/(|acc|+SIGMA) grid, replicate-padded at the edges.
// planeFlat is row-major [planeH][planeW]; grid is gh x gw (gh>=planeH via block padding upstream).
std::vector<double> invResidual(const double* planeFlat, int planeH, int planeW, int gh, int gw,
                                 const double* rowFilter, const double* colFilter) {
    std::vector<double> tmp(static_cast<size_t>(gh) * gw);
    for (int m = 0; m < gh; m++) {
        int sy = m < planeH ? m : planeH - 1;
        const double* prow = planeFlat + static_cast<size_t>(sy) * planeW;
        double* trow = tmp.data() + static_cast<size_t>(m) * gw;
        for (int n = 0; n < gw; n++) {
            double acc = 0.0;
            for (int b = 0; b < LF; b++) {
                int sx = n + b - CENTER;
                sx = sx < 0 ? 0 : (sx >= planeW ? planeW - 1 : sx);
                acc += colFilter[b] * prow[sx];
            }
            trow[n] = acc;
        }
    }
    std::vector<double> inv(static_cast<size_t>(gh) * gw);
    for (int m = 0; m < gh; m++) {
        double* irow = inv.data() + static_cast<size_t>(m) * gw;
        for (int n = 0; n < gw; n++) {
            double acc = 0.0;
            for (int aTap = 0; aTap < LF; aTap++) {
                int ry = m + aTap - CENTER;
                ry = ry < 0 ? 0 : (ry >= gh ? gh - 1 : ry);
                acc += rowFilter[aTap] * tmp[static_cast<size_t>(ry) * gw + n];
            }
            irow[n] = 1.0 / (std::fabs(acc) + SIGMA);
        }
    }
    return inv;
}

// sum_{s,t} absRow[s]*absCol[t]*inv[r0+s-(CENTER-1)][c0+t-(CENTER-1)], replicate-clamped.
// Interior blocks (fully inside the grid with margin CENTER-1..CENTER) take a contiguous NEON path;
// blocks near the plane edge fall back to the scalar clamped loop. NEON has no gather instruction, so
// the (rare) clamped case isn't worth vectorizing -- most blocks in any real image are interior.
double accumulate(const double* absRow, const double* absCol, const double* inv, int invStride, int r0,
                   int c0, int gh, int gw) {
    const int rBase = r0 - (CENTER - 1);
    const int cBase = c0 - (CENTER - 1);
    const bool interior = rBase >= 0 && rBase + PAT <= gh && cBase >= 0 && cBase + PAT <= gw;

    double sum = 0.0;
    if (interior) {
        for (int s = 0; s < PAT; s++) {
            double rw = absRow[s];
            if (rw == 0.0) {
                continue;
            }
            const double* invRow = inv + static_cast<size_t>(rBase + s) * invStride + cBase;
#ifdef HAVE_NEON
            float64x2_t vsum = vdupq_n_f64(0.0);
            int t = 0;
            for (; t + 2 <= PAT; t += 2) {
                float64x2_t cw = vld1q_f64(absCol + t);
                float64x2_t iv = vld1q_f64(invRow + t);
                vsum = vfmaq_f64(vsum, cw, iv);
            }
            double rowSum = vgetq_lane_f64(vsum, 0) + vgetq_lane_f64(vsum, 1);
            for (; t < PAT; t++) {
                rowSum += absCol[t] * invRow[t];
            }
#else
            double rowSum = 0.0;
            for (int t = 0; t < PAT; t++) {
                rowSum += absCol[t] * invRow[t];
            }
#endif
            sum += rw * rowSum;
        }
    } else {
        for (int s = 0; s < PAT; s++) {
            double rw = absRow[s];
            if (rw == 0.0) {
                continue;
            }
            int rr = clampi(r0 + s - (CENTER - 1), gh);
            const double* invRow = inv + static_cast<size_t>(rr) * invStride;
            double rowSum = 0.0;
            for (int t = 0; t < PAT; t++) {
                double cw = absCol[t];
                if (cw == 0.0) {
                    continue;
                }
                int cc = clampi(c0 + t - (CENTER - 1), gw);
                rowSum += cw * invRow[cc];
            }
            sum += rw * rowSum;
        }
    }
    return sum;
}

}  // namespace

std::vector<double> computeUniwardCost(const double* planeFlat, int planeH, int planeW, int blocksWide,
                                        int blocksHigh, const int* quant) {
    double lpdf[LF];
    for (int k = 0; k < LF; k++) {
        lpdf[k] = ((k & 1) == 0 ? -1.0 : 1.0) * HPDF[LF - 1 - k];
    }

    double a[8][8];
    dctMatrix(a);
    double absRespH[8][PAT];
    double absRespL[8][PAT];
    response(a, HPDF, absRespH);
    response(a, lpdf, absRespL);

    const int gh = blocksHigh * 8;
    const int gw = blocksWide * 8;
    std::vector<double> inv1 = invResidual(planeFlat, planeH, planeW, gh, gw, lpdf, HPDF);
    std::vector<double> inv2 = invResidual(planeFlat, planeH, planeW, gh, gw, HPDF, lpdf);
    std::vector<double> inv3 = invResidual(planeFlat, planeH, planeW, gh, gw, HPDF, HPDF);

    const int numBlocks = blocksWide * blocksHigh;
    std::vector<double> cost(static_cast<size_t>(numBlocks) * 64, 0.0);

    for (int idx = 0; idx < numBlocks; idx++) {
        int br = idx / blocksWide;
        int bc = idx % blocksWide;
        int r0 = br * 8;
        int c0 = bc * 8;
        double* block = cost.data() + static_cast<size_t>(idx) * 64;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                double q = quant[i * 8 + j];
                double rho = accumulate(absRespL[i], absRespH[j], inv1.data(), gw, r0, c0, gh, gw) +
                             accumulate(absRespH[i], absRespL[j], inv2.data(), gw, r0, c0, gh, gw) +
                             accumulate(absRespH[i], absRespH[j], inv3.data(), gw, r0, c0, gh, gw);
                block[i * 8 + j] = q * rho;
            }
        }
    }
    return cost;
}
