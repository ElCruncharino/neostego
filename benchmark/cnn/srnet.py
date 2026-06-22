"""
SRNet steganalysis network (Boroumand, Chen, Fridrich, "Deep Residual Network
for Steganalysis of Digital Images", IEEE TIFS 2019).

Faithful reimplementation of the 12-layer architecture used as the modern
CNN-steganalysis adversary for the NeoStego benchmark. No high-pass / SRM
front-end is hard-coded: SRNet learns the noise residual itself, which is the
whole point of the design.

Input is C x H x W float pixels (raw, un-normalised, as in the paper). C is 3
here because NeoStego embeds independently in the R, G and B channels of the
(visually grey) BOSSbase covers, so colour carries the stego signal.
"""

import torch
import torch.nn as nn


class Type1(nn.Module):
    """Conv -> BN -> ReLU (no residual, no pooling)."""

    def __init__(self, in_ch, out_ch):
        super().__init__()
        self.conv = nn.Conv2d(in_ch, out_ch, 3, padding=1, bias=False)
        self.bn = nn.BatchNorm2d(out_ch)
        self.act = nn.ReLU(inplace=True)

    def forward(self, x):
        return self.act(self.bn(self.conv(x)))


class Type2(nn.Module):
    """Residual block, channels unchanged, no pooling: y = x + BN(conv(ReLU(BN(conv(x)))))."""

    def __init__(self, ch):
        super().__init__()
        self.conv1 = nn.Conv2d(ch, ch, 3, padding=1, bias=False)
        self.bn1 = nn.BatchNorm2d(ch)
        self.act = nn.ReLU(inplace=True)
        self.conv2 = nn.Conv2d(ch, ch, 3, padding=1, bias=False)
        self.bn2 = nn.BatchNorm2d(ch)

    def forward(self, x):
        out = self.act(self.bn1(self.conv1(x)))
        out = self.bn2(self.conv2(out))
        return x + out


class Type3(nn.Module):
    """Residual block with 2x average-pool downsampling and a 1x1 stride-2 skip."""

    def __init__(self, in_ch, out_ch):
        super().__init__()
        self.conv1 = nn.Conv2d(in_ch, out_ch, 3, padding=1, bias=False)
        self.bn1 = nn.BatchNorm2d(out_ch)
        self.act = nn.ReLU(inplace=True)
        self.conv2 = nn.Conv2d(out_ch, out_ch, 3, padding=1, bias=False)
        self.bn2 = nn.BatchNorm2d(out_ch)
        self.pool = nn.AvgPool2d(3, stride=2, padding=1)
        self.skip_conv = nn.Conv2d(in_ch, out_ch, 1, stride=2, bias=False)
        self.skip_bn = nn.BatchNorm2d(out_ch)

    def forward(self, x):
        out = self.act(self.bn1(self.conv1(x)))
        out = self.bn2(self.conv2(out))
        out = self.pool(out)
        skip = self.skip_bn(self.skip_conv(x))
        return out + skip


class Type4(nn.Module):
    """Final residual-style block followed by global average pooling."""

    def __init__(self, in_ch, out_ch):
        super().__init__()
        self.conv1 = nn.Conv2d(in_ch, out_ch, 3, padding=1, bias=False)
        self.bn1 = nn.BatchNorm2d(out_ch)
        self.act = nn.ReLU(inplace=True)
        self.conv2 = nn.Conv2d(out_ch, out_ch, 3, padding=1, bias=False)
        self.bn2 = nn.BatchNorm2d(out_ch)

    def forward(self, x):
        out = self.act(self.bn1(self.conv1(x)))
        out = self.bn2(self.conv2(out))
        # global average pool -> N x C  (mean over spatial dims; DirectML-friendly)
        return out.mean(dim=(2, 3))


class SRNet(nn.Module):
    def __init__(self, in_channels=3, num_classes=2):
        super().__init__()
        self.l1 = Type1(in_channels, 64)
        self.l2 = Type1(64, 16)
        self.t2 = nn.Sequential(*[Type2(16) for _ in range(5)])      # layers 3-7
        self.t3a = Type3(16, 16)                                     # layer 8
        self.t3b = Type3(16, 64)                                     # layer 9
        self.t3c = Type3(64, 128)                                    # layer 10
        self.t3d = Type3(128, 256)                                   # layer 11
        self.t4 = Type4(256, 512)                                    # layer 12
        self.fc = nn.Linear(512, num_classes)

    def forward(self, x):
        x = self.l1(x)
        x = self.l2(x)
        x = self.t2(x)
        x = self.t3a(x)
        x = self.t3b(x)
        x = self.t3c(x)
        x = self.t3d(x)
        x = self.t4(x)
        return self.fc(x)
