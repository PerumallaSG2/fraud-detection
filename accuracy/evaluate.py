#!/usr/bin/env python3
"""Joins replay labels with the decisions the app stored and computes accuracy. Aggregates only.

Two views of "predicted fraud": flagged (REVIEW or BLOCK) and BLOCK only.
  precision = TP / (TP + FP)   recall = TP / (TP + FN)   false positive rate = FP / (FP + TN)
Per rule: how often it fired on fraud vs. on normal transactions, and its precision when it fired.

Usage: python3 -I accuracy/evaluate.py <labels.csv> <scored.csv> <prepare.json> <replay.json> <out results.json>
scored.csv (no header): id,decision,risk_score,rules where rules is "RULE_A|RULE_B" or empty.
"""
import csv
import json
import sys
from collections import Counter

RULES = ["AMOUNT_SPIKE", "GEO_MISMATCH", "ODD_HOURS", "RISKY_MERCHANT", "VELOCITY"]
DECISIONS = ["APPROVE", "REVIEW", "BLOCK"]


def ratio(num, den):
    return round(num / den, 4) if den else None


def matrix(pairs, positive):
    """pairs: (label, decision). Counts with 'predicted fraud' = decision in positive."""
    c = Counter((label, decision in positive) for label, decision in pairs)
    tp, fp, fn, tn = c[(1, True)], c[(0, True)], c[(1, False)], c[(0, False)]
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "precision": ratio(tp, tp + fp), "recall": ratio(tp, tp + fn),
            "falsePositiveRate": ratio(fp, fp + tn)}


def main(labels_path, scored_path, prepare_path, replay_path, out_path):
    with open(labels_path, encoding="utf-8") as f:
        labels = {row["id"]: int(row["label"]) for row in csv.DictReader(f)}
    scored = {}
    with open(scored_path, encoding="utf-8") as f:
        for tx_id, decision, score, rules in csv.reader(f):
            scored[tx_id] = (decision, int(score), [r for r in rules.split("|") if r])

    joined = [(labels[i], *scored[i]) for i in labels if i in scored]
    unscored = len(labels) - len(joined)
    pairs = [(label, decision) for label, decision, _, _ in joined]
    fraud = sum(1 for label, *_ in joined if label == 1)
    normal = len(joined) - fraud

    by_label = {name: Counter(decision for label, decision in pairs if label == value)
                for name, value in (("fraud", 1), ("normal", 0))}
    hits = {name: Counter(rule for label, _, _, rules in joined if label == value for rule in rules)
            for name, value in (("fraud", 1), ("normal", 0))}
    per_rule = {}
    for rule in RULES:
        f_hits, n_hits = hits["fraud"][rule], hits["normal"][rule]
        per_rule[rule] = {"onFraud": f_hits, "onFraudRate": ratio(f_hits, fraud), "onNormal": n_hits,
                          "onNormalRate": ratio(n_hits, normal), "precision": ratio(f_hits, f_hits + n_hits)}

    with open(prepare_path, encoding="utf-8") as f:
        prepared = json.load(f)
    with open(replay_path, encoding="utf-8") as f:
        replay = json.load(f)
    results = {
        "dataset": "kartik2112/fraud-detection fraudTest.csv (Sparkov, CC0-1.0)",
        "prepared": prepared, "replay": replay,
        "evaluated": len(joined), "acceptedButNotScored": unscored, "fraud": fraud, "normal": normal,
        "decisionsByLabel": {k: {d: v[d] for d in DECISIONS} for k, v in by_label.items()},
        "flagged": matrix(pairs, {"REVIEW", "BLOCK"}),
        "blockOnly": matrix(pairs, {"BLOCK"}),
        "rules": per_rule,
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
        f.write("\n")

    print(f"evaluated {len(joined)} ({fraud} fraud, {normal} normal); accepted but not scored: {unscored}\n")
    print("| Predicted fraud when | TP | FP | FN | TN | Precision | Recall | FPR |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|")
    for name, key in (("REVIEW or BLOCK", "flagged"), ("BLOCK only", "blockOnly")):
        m = results[key]
        print(f"| {name} | {m['tp']:,} | {m['fp']:,} | {m['fn']:,} | {m['tn']:,} | {pct(m['precision'])} | "
              f"{pct(m['recall'])} | {pct(m['falsePositiveRate'])} |")
    print("\n| Rule | Fires on fraud | Fires on normal | Precision when it fires |")
    print("|---|---:|---:|---:|")
    for rule, r in per_rule.items():
        print(f"| {rule} | {r['onFraud']:,} ({pct(r['onFraudRate'])}) | {r['onNormal']:,} "
              f"({pct(r['onNormalRate'])}) | {pct(r['precision'])} |")
    print("\n| Label | " + " | ".join(DECISIONS) + " |\n|---|---:|---:|---:|")
    for k, v in results["decisionsByLabel"].items():
        print(f"| {k} | " + " | ".join(f"{v[d]:,}" for d in DECISIONS) + " |")


def pct(x):
    return "-" if x is None else f"{x * 100:.2f}%"


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    main(*sys.argv[1:])
