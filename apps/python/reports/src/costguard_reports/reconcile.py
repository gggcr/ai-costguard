"""Forecasts of departments that add up to the forecast of the company (ADR 0034).

Each department and the company are forecast on their own, and those forecasts do not add up. Reconciliation finds
the department forecasts closest to all of them at once that do: MinT with a diagonal covariance (WLS by the
variance of each series' one-step errors). A series that forecasts badly moves more, one that forecasts well
less. The hierarchy has two levels, the company over its departments, so the projection has a closed form: with
precisions w_i of the departments and w of the company (Sherman–Morrison),

    b_i = r_i / w_i - (1 / w_i) * w * sum_j(r_j / w_j) / (1 + w * sum_j(1 / w_j)),   r_i = w * total + w_i * part_i

and the company is the sum of the departments. Spend cannot be negative: a department below zero is set to zero
and the company is the sum again, so the result still adds up.
"""

from __future__ import annotations

EPSILON = 1e-9


def _precision(variance: float) -> float:
    return 1.0 / max(variance, EPSILON)


def reconcile(total: list[float], parts: dict[str, list[float]], total_variance: float,
              part_variances: dict[str, float]) -> tuple[list[float], dict[str, list[float]]]:
    """The company and its departments, step by step of the horizon, reconciled."""
    if not parts:
        return list(total), {}
    names = sorted(parts)
    w = _precision(total_variance)
    ws = {n: _precision(part_variances.get(n, 0.0)) for n in names}
    inverse_sum = sum(1 / ws[n] for n in names)
    out = {n: [] for n in names}
    company = []
    for h, y in enumerate(total):
        r = {n: w * y + ws[n] * parts[n][h] for n in names}
        shared = w * sum(r[n] / ws[n] for n in names) / (1 + w * inverse_sum)
        step = {n: max(r[n] / ws[n] - shared / ws[n], 0.0) for n in names}
        for n in names:
            out[n].append(step[n])
        company.append(sum(step.values()))
    return company, out
