from datetime import date
from decimal import Decimal

import pytest

from costguard_reports.reconcile import reconcile
from costguard_reports.savings import savings


def test_forecasts_that_add_up_stay_as_they_are():
    company, parts = reconcile([100.0], {"it": [60.0], "sales": [40.0]}, 4.0, {"it": 4.0, "sales": 4.0})
    assert company == pytest.approx([100.0]) and parts == {"it": pytest.approx([60.0]), "sales": pytest.approx([40.0])}


def test_reconciled_forecasts_add_up_and_meet_halfway():
    company, parts = reconcile([130.0, 130.0], {"it": [60.0, 60.0], "sales": [40.0, 40.0]}, 1.0,
                               {"it": 1.0, "sales": 1.0})
    # equal variances: ordinary least squares, the gap of 30 split over the three series
    assert parts["it"] == pytest.approx([70.0, 70.0]) and parts["sales"] == pytest.approx([50.0, 50.0])
    assert company == pytest.approx([sum(p) for p in zip(parts["it"], parts["sales"], strict=True)])


def test_the_series_that_forecasts_worse_moves_more():
    total, _ = reconcile([130.0], {"it": [60.0], "sales": [40.0]}, 0.01, {"it": 100.0, "sales": 100.0})
    assert total[0] == pytest.approx(130.0, abs=0.1)  # the company was sure, the departments move to it
    total, _ = reconcile([130.0], {"it": [60.0], "sales": [40.0]}, 100.0, {"it": 0.01, "sales": 0.01})
    assert total[0] == pytest.approx(100.0, abs=0.1)  # the departments were sure, the company moves to them


def test_no_department_goes_below_zero():
    total, parts = reconcile([10.0], {"it": [100.0], "sales": [0.0]}, 1.0, {"it": 1.0, "sales": 1.0})
    assert min(parts["sales"]) >= 0 and total[0] == pytest.approx(parts["it"][0] + parts["sales"][0])
    assert reconcile([5.0], {}, 1.0, {}) == ([5.0], {})


MONTHS = [date(2026, 12, 1), date(2027, 1, 1), date(2027, 2, 1), date(2027, 3, 1)]
TODAY = date(2026, 11, 10)


def test_savings_start_later_and_count_less_the_earlier_the_finding():
    found = [{"rule": "license.unused", "status": "new", "expectedSavings": 1000},
             {"rule": "license.unused", "status": "in_progress", "expectedSavings": 500},
             {"rule": "subscription.idle-seats", "status": "confirmed", "expectedSavings": 300},
             {"rule": "license.unused", "status": "rejected", "expectedSavings": 9999}]
    months = savings(found, MONTHS, TODAY)
    # December: in progress (0.8 × 500) and confirmed; February: the new one too (0.2 × 1000)
    assert [m.expected for m in months] == [Decimal("700.00"), Decimal("700.00"), Decimal("900.00"),
                                            Decimal("900.00")]
    assert all(m.confirmed == Decimal("300") for m in months)


def test_a_one_off_saving_comes_once_and_a_postponed_one_after_its_date():
    found = [{"rule": "asset.not-returned", "status": "in_progress", "expectedSavings": 80000},
             {"rule": "license.unused", "status": "postponed", "expectedSavings": 100, "postponedUntil": "2027-01-20"}]
    months = savings(found, MONTHS, TODAY)
    assert [m.expected for m in months] == [Decimal("64000.00"), Decimal("0"), Decimal("30.00"), Decimal("30.00")]
