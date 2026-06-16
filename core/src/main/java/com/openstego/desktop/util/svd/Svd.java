/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util.svd;

/**
 * Compact singular value decomposition for small dense square matrices, computed with the one-sided Jacobi method.
 * <p>
 * The watermarking pipeline decomposes each small image block {@code A = U &middot; diag(S) &middot; V^T}, perturbs the
 * largest singular value {@code S[0]} (which carries most of the block energy and is highly stable under common image
 * processing), and reconstructs the block. One-sided Jacobi is numerically robust and converges in a handful of sweeps
 * for the 8&times;8 blocks used here, so its cubic cost per block is negligible.
 * <p>
 * Singular values (and the corresponding columns of {@code U} and {@code V}) are returned sorted in descending order.
 */
public final class Svd {

    private static final int MAX_SWEEPS = 60;
    private static final double EPS = 1e-12;

    private final int n;
    private final double[][] u;
    private final double[][] v;
    private final double[] s;

    /**
     * Compute the SVD of a square matrix.
     *
     * @param a Square matrix (n&times;n); not modified
     */
    public Svd(double[][] a) {
        this.n = a.length;
        for (double[] row : a) {
            if (row.length != this.n) {
                throw new IllegalArgumentException("matrix must be square");
            }
        }

        // Work on a copy of A in 'u'; accumulate right rotations in 'v'.
        this.u = new double[this.n][this.n];
        this.v = new double[this.n][this.n];
        this.s = new double[this.n];
        for (int i = 0; i < this.n; i++) {
            System.arraycopy(a[i], 0, this.u[i], 0, this.n);
            this.v[i][i] = 1.0;
        }

        decompose();
        sortDescending();
    }

    private void decompose() {
        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            double off = 0.0;
            for (int p = 0; p < this.n - 1; p++) {
                for (int q = p + 1; q < this.n; q++) {
                    double alpha = 0.0;
                    double beta = 0.0;
                    double gamma = 0.0;
                    for (int k = 0; k < this.n; k++) {
                        alpha += this.u[k][p] * this.u[k][p];
                        beta += this.u[k][q] * this.u[k][q];
                        gamma += this.u[k][p] * this.u[k][q];
                    }
                    off += Math.abs(gamma);

                    if (Math.abs(gamma) <= EPS * Math.sqrt(alpha * beta) || (alpha == 0.0 && beta == 0.0)) {
                        continue;
                    }

                    double zeta = (beta - alpha) / (2.0 * gamma);
                    double t = Math.signum(zeta) / (Math.abs(zeta) + Math.sqrt(1.0 + zeta * zeta));
                    if (zeta == 0.0) {
                        t = 1.0;
                    }
                    double c = 1.0 / Math.sqrt(1.0 + t * t);
                    double sn = c * t;

                    for (int k = 0; k < this.n; k++) {
                        double ukp = this.u[k][p];
                        double ukq = this.u[k][q];
                        this.u[k][p] = c * ukp - sn * ukq;
                        this.u[k][q] = sn * ukp + c * ukq;

                        double vkp = this.v[k][p];
                        double vkq = this.v[k][q];
                        this.v[k][p] = c * vkp - sn * vkq;
                        this.v[k][q] = sn * vkp + c * vkq;
                    }
                }
            }
            if (off < EPS) {
                break;
            }
        }

        // Singular values are the norms of the columns of the rotated matrix; normalize those columns to form U.
        for (int j = 0; j < this.n; j++) {
            double norm = 0.0;
            for (int k = 0; k < this.n; k++) {
                norm += this.u[k][j] * this.u[k][j];
            }
            norm = Math.sqrt(norm);
            this.s[j] = norm;
            if (norm > EPS) {
                for (int k = 0; k < this.n; k++) {
                    this.u[k][j] /= norm;
                }
            }
        }
    }

    private void sortDescending() {
        Integer[] order = new Integer[this.n];
        for (int i = 0; i < this.n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(this.s[y], this.s[x]));

        double[] s2 = new double[this.n];
        double[][] u2 = new double[this.n][this.n];
        double[][] v2 = new double[this.n][this.n];
        for (int j = 0; j < this.n; j++) {
            int src = order[j];
            s2[j] = this.s[src];
            for (int k = 0; k < this.n; k++) {
                u2[k][j] = this.u[k][src];
                v2[k][j] = this.v[k][src];
            }
        }
        System.arraycopy(s2, 0, this.s, 0, this.n);
        for (int k = 0; k < this.n; k++) {
            System.arraycopy(u2[k], 0, this.u[k], 0, this.n);
            System.arraycopy(v2[k], 0, this.v[k], 0, this.n);
        }
    }

    /**
     * @return the singular values, sorted descending (a copy)
     */
    public double[] getSingularValues() {
        return this.s.clone();
    }

    /**
     * @param i Index
     * @return the i-th singular value (0 = largest)
     */
    public double getSingularValue(int i) {
        return this.s[i];
    }

    /**
     * Set a singular value, e.g. to embed a watermark bit by quantizing {@code S[0]}.
     *
     * @param i     Index
     * @param value New value
     */
    public void setSingularValue(int i, double value) {
        this.s[i] = value;
    }

    /**
     * Rebuild the matrix from the (possibly modified) factors: {@code U &middot; diag(S) &middot; V^T}.
     *
     * @return the reconstructed n&times;n matrix
     */
    public double[][] reconstruct() {
        double[][] r = new double[this.n][this.n];
        for (int i = 0; i < this.n; i++) {
            for (int j = 0; j < this.n; j++) {
                double sum = 0.0;
                for (int k = 0; k < this.n; k++) {
                    sum += this.u[i][k] * this.s[k] * this.v[j][k];
                }
                r[i][j] = sum;
            }
        }
        return r;
    }
}
