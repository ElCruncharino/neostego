"""
Train and evaluate SRNet as a CNN-steganalysis adversary against NeoStego.

For one embedding algorithm it builds a cover-vs-stego binary classifier from the
BOSSbase corpus produced by benchmark/bench_boss.sh, training SRNet from scratch
and reporting test accuracy, P_E (minimal total detection error) and ROC AUC.

Cover/stego pairs are kept in the SAME split (train/val/test) so the network
cannot win by memorising image content -- it must learn the embedding residual.

Data layout (under --root, default ~/stego-bench):
    covers_boss/<id>.png          cover
    stego_<algo>/<id>.png         stego for that cover

Device order: DirectML (AMD via WSL dxg) -> CUDA -> CPU.

Usage:
    python srnet_eval.py --algo Adaptive --epochs 40
    python srnet_eval.py --algo RandomLSB --smoke      # tiny fast sanity run
"""

import argparse
import os
import random
import sys
import time

import numpy as np
from PIL import Image

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from srnet import SRNet
from srm_cnn import SRMCNN
from eval_common import auc_score, p_error


def build_model(name):
    """srmcnn: fixed SRM high-pass front-end + compact conv stack (converges on a
    ~1k-pair corpus). srnet: faithful deep SRNet (needs a far larger iteration
    budget; kept for reference)."""
    if name == "srnet":
        return SRNet(in_channels=1)
    return SRMCNN()


def pick_dml_index(dml, prefer):
    """Choose the discrete GPU: honour an explicit index, else prefer a
    'Radeon RX' / non-integrated adapter over the iGPU ('Radeon(TM) Graphics')."""
    n = dml.device_count()
    if prefer is not None and 0 <= prefer < n:
        return prefer
    names = [dml.device_name(i) for i in range(n)]
    for i, nm in enumerate(names):
        if "RX" in nm:
            return i
    for i, nm in enumerate(names):
        if "Graphics" not in nm:        # iGPUs report as "...(TM) Graphics"
            return i
    return n - 1 if n else 0


def get_device(prefer=None):
    try:
        import torch_directml
        if torch_directml.is_available():
            idx = pick_dml_index(torch_directml, prefer)
            return torch_directml.device(idx), "directml (%s)" % torch_directml.device_name(idx)
    except Exception as e:
        print("directml unavailable:", e)
    if torch.cuda.is_available():
        return torch.device("cuda"), "cuda (%s)" % torch.cuda.get_device_name(0)
    return torch.device("cpu"), "cpu"


def load_channel(path, channel):
    """One colour channel as a float H x W array. BOSSbase covers are grey, so a
    single channel is a clean grayscale-steganalysis target and avoids the trivial
    'are the 3 channels identical?' shortcut that grey-cover RGB embedding creates."""
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)[:, :, channel]


class PairDataset(Dataset):
    """Yields (image_tensor, label) for the given list of (path, label) items."""

    def __init__(self, items, crop, train, channel=1):
        self.items = items
        self.crop = crop
        self.train = train
        self.channel = channel

    def __len__(self):
        return len(self.items)

    def __getitem__(self, idx):
        path, label = self.items[idx]
        arr = load_channel(path, self.channel)   # H x W
        h, w = arr.shape
        c = self.crop
        if self.train:
            top = random.randint(0, max(0, h - c))
            left = random.randint(0, max(0, w - c))
        else:
            top = max(0, (h - c) // 2)
            left = max(0, (w - c) // 2)
        arr = arr[top:top + c, left:left + c]
        if self.train:
            # D4 augmentation (steganalysis-safe: flips/rotations preserve the residual)
            if random.random() < 0.5:
                arr = arr[:, ::-1]
            if random.random() < 0.5:
                arr = arr[::-1, :]
            k = random.randint(0, 3)
            if k:
                arr = np.rot90(arr, k)
        arr = np.ascontiguousarray(arr[None, :, :])   # 1 x H x W
        return torch.from_numpy(arr), label


def build_items(root, algo, cover_dir=None, stego_prefix="stego_cnn_"):
    cover_dir = cover_dir or os.path.join(root, "covers_cnn")
    stego_dir = os.path.join(root, stego_prefix + algo)
    ids = []
    for f in os.listdir(cover_dir):
        if f.endswith(".png") and os.path.exists(os.path.join(stego_dir, f)):
            ids.append(f)
    ids.sort(key=lambda s: int(os.path.splitext(s)[0]) if os.path.splitext(s)[0].isdigit() else s)
    pairs = [(os.path.join(cover_dir, f), os.path.join(stego_dir, f)) for f in ids]
    return pairs


def verify_pairs(pairs, channel, n=8):
    """Confirm cover<->stego are true pairs: a +/-1 embed changes a pixel by at
    most 1, so a large max-abs-diff means the cover set is wrong (e.g. orphaned)."""
    import random as _r
    sample = pairs if len(pairs) <= n else _r.Random(0).sample(pairs, n)
    worst = 0
    for cover, stego in sample:
        c = load_channel(cover, channel).astype(np.int16)
        s = load_channel(stego, channel).astype(np.int16)
        if c.shape != s.shape:
            raise SystemExit("pair shape mismatch: %s vs %s" % (cover, stego))
        worst = max(worst, int(np.abs(c - s).max()))
    print("pairing check: max |cover-stego| over %d pairs = %d (expect <=1)" % (len(sample), worst))
    if worst > 1:
        raise SystemExit("ABORT: pairs are not aligned (max-abs-diff %d > 1). "
                         "Regenerate with make_pairs.sh." % worst)


def split_pairs(pairs, seed, val_frac=0.1, test_frac=0.2):
    rng = random.Random(seed)
    idx = list(range(len(pairs)))
    rng.shuffle(idx)
    n = len(idx)
    n_test = int(n * test_frac)
    n_val = int(n * val_frac)
    test_ids = idx[:n_test]
    val_ids = idx[n_test:n_test + n_val]
    train_ids = idx[n_test + n_val:]

    def expand(id_list):
        items = []
        for i in id_list:
            cover, stego = pairs[i]
            items.append((cover, 0))
            items.append((stego, 1))
        return items

    return expand(train_ids), expand(val_ids), expand(test_ids)


@torch.no_grad()
def evaluate(model, loader, device):
    model.eval()
    all_scores, all_labels = [], []
    for x, y in loader:
        x = x.to(device)
        logits = model(x)
        prob = torch.softmax(logits.float(), dim=1)[:, 1]
        all_scores.append(prob.detach().cpu().numpy())
        all_labels.append(y.numpy())
    scores = np.concatenate(all_scores)
    labels = np.concatenate(all_labels)
    acc = ((scores >= 0.5).astype(int) == labels).mean()
    return acc, p_error(labels, scores), auc_score(labels, scores)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.expanduser("~/stego-bench"))
    ap.add_argument("--algo", required=True, help="Adaptive | RandomLSB | RandomLSBMatch")
    ap.add_argument("--cover-dir", default=None, help="cover dir (default: <root>/covers_cnn)")
    ap.add_argument("--stego-prefix", default="stego_cnn_", help="stego dir prefix")
    ap.add_argument("--channel", type=int, default=1, help="colour channel to analyse (0=R,1=G,2=B)")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--crop", type=int, default=256)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--limit", type=int, default=0, help="cap #pairs (0 = all)")
    ap.add_argument("--gpu", type=int, default=None, help="DirectML device index (default: discrete)")
    ap.add_argument("--model", default="srmcnn", choices=["srmcnn", "srnet"],
                    help="srmcnn (SRM high-pass front-end, converges fast) | srnet (deep, reference)")
    ap.add_argument("--smoke", action="store_true", help="tiny fast sanity run")
    args = ap.parse_args()

    if args.smoke:
        args.epochs, args.limit, args.batch = 2, 60, 8

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    device, dname = get_device(args.gpu)
    print("device:", dname)

    pairs = build_items(args.root, args.algo, args.cover_dir, args.stego_prefix)
    if not pairs:
        print("no pairs found for algo", args.algo, "under", args.root)
        sys.exit(1)
    if args.limit:
        pairs = pairs[:args.limit]
    verify_pairs(pairs, args.channel)
    train_items, val_items, test_items = split_pairs(pairs, args.seed)
    print("pairs: %d  | train imgs: %d  val: %d  test: %d"
          % (len(pairs), len(train_items), len(val_items), len(test_items)))

    pin = device.type == "cuda"
    train_loader = DataLoader(PairDataset(train_items, args.crop, True, args.channel),
                              batch_size=args.batch, shuffle=True,
                              num_workers=args.workers, pin_memory=pin, drop_last=True)
    val_loader = DataLoader(PairDataset(val_items, args.crop, False, args.channel),
                            batch_size=args.batch, shuffle=False, num_workers=args.workers)
    test_loader = DataLoader(PairDataset(test_items, args.crop, False, args.channel),
                             batch_size=args.batch, shuffle=False, num_workers=args.workers)

    model = build_model(args.model).to(device)
    print("model:", args.model)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)
    crit = nn.CrossEntropyLoss()

    best_val_pe = 1.0
    best_state = None
    for epoch in range(1, args.epochs + 1):
        model.train()
        t0 = time.time()
        running = 0.0
        nb = 0
        for x, y in train_loader:
            x, y = x.to(device), y.to(device)
            opt.zero_grad()
            logits = model(x)
            loss = crit(logits, y)
            loss.backward()
            opt.step()
            running += loss.item()
            nb += 1
        sched.step()
        val_acc, val_pe, val_auc = evaluate(model, val_loader, device)
        print("epoch %2d  loss %.4f  val_acc %.3f  val_PE %.3f  val_AUC %.3f  (%.1fs)"
              % (epoch, running / max(1, nb), val_acc, val_pe, val_auc, time.time() - t0),
              flush=True)
        if val_pe <= best_val_pe:
            best_val_pe = val_pe
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    if best_state is not None:
        model.load_state_dict(best_state)
    acc, pe, auc = evaluate(model, test_loader, device)
    print("=" * 60)
    print("ALGO %s  [%s]  TEST  acc %.4f  P_E %.4f  AUC %.4f"
          % (args.algo, args.model, acc, pe, auc))
    print("(P_E ~0.5 / AUC ~0.5 => the CNN cannot distinguish stego from cover)")
    print("=" * 60)


if __name__ == "__main__":
    main()
