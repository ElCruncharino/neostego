"""
Compact CNN steganalyzer with a fixed high-pass (SRM) front-end -- Xu-Net style
(Xu, Wu, Shi, "Structural Design of Convolutional Neural Networks for
Steganalysis", IEEE SPL 2016), with a small bank of SRM residual kernels as in
Ye-Net / SRNet preprocessing.

Why this and not a from-scratch SRNet here: SRNet is a very deep residual net
that needs hundreds of thousands of iterations to converge. On a 1k-pair corpus
it does not learn (it sits at the chance basin), which would make every algorithm
look 'undetectable' for the wrong reason. A fixed high-pass front-end removes the
image content up front, so the net starts from the stego noise residual and
converges in a few epochs -- giving a valid steganalysis adversary whose positive
control (plain LSB) is correctly detected near 100%.
"""

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F


# Three classic SRM high-pass kernels (5x5), normalised by their q factor.
_KV = np.array([[-1, 2, -2, 2, -1],
                [2, -6, 8, -6, 2],
                [-2, 8, -12, 8, -2],
                [2, -6, 8, -6, 2],
                [-1, 2, -2, 2, -1]], dtype=np.float32) / 12.0

# first-order horizontal edge (SRM "1st" class), padded into 5x5
_E1 = np.zeros((5, 5), dtype=np.float32)
_E1[2, 1:4] = np.array([-1, 2, -1], dtype=np.float32)  # 2nd-order along a row

# 3x3 second-order Laplacian-like, padded into 5x5
_SQ = np.zeros((5, 5), dtype=np.float32)
_SQ[1:4, 1:4] = np.array([[-1, 2, -1],
                          [2, -4, 2],
                          [-1, 2, -1]], dtype=np.float32) / 4.0


class SRMFront(nn.Module):
    """Fixed (non-trainable) high-pass bank: 1 channel -> K residual channels."""

    def __init__(self):
        super().__init__()
        kernels = np.stack([_KV, _E1, _SQ])[:, None, :, :]   # K x 1 x 5 x 5
        w = torch.from_numpy(kernels)
        self.register_buffer("weight", w)

    def forward(self, x):
        return F.conv2d(x, self.weight, padding=2)


class ConvBlock(nn.Module):
    def __init__(self, in_ch, out_ch, use_abs=False, pool=True):
        super().__init__()
        self.conv = nn.Conv2d(in_ch, out_ch, 5, padding=2)
        self.use_abs = use_abs
        self.bn = nn.BatchNorm2d(out_ch)
        self.pool = nn.AvgPool2d(5, stride=2, padding=2) if pool else None

    def forward(self, x):
        x = self.conv(x)
        if self.use_abs:
            x = torch.abs(x)
        x = torch.tanh(self.bn(x))
        if self.pool is not None:
            x = self.pool(x)
        return x


class SRMCNN(nn.Module):
    """SRM high-pass + Xu-Net-style conv stack. Input: N x 1 x H x W (raw pixels)."""

    def __init__(self, num_classes=2):
        super().__init__()
        self.front = SRMFront()
        self.b1 = ConvBlock(3, 8, use_abs=True, pool=True)
        self.b2 = ConvBlock(8, 16, pool=True)
        self.b3 = ConvBlock(16, 32, pool=True)
        self.b4 = ConvBlock(32, 64, pool=True)
        self.b5 = ConvBlock(64, 128, pool=False)
        self.fc = nn.Linear(128, num_classes)

    def forward(self, x):
        x = self.front(x)
        x = self.b1(x)
        x = self.b2(x)
        x = self.b3(x)
        x = self.b4(x)
        x = self.b5(x)
        x = x.mean(dim=(2, 3))     # global average pool
        return self.fc(x)
