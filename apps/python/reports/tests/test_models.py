from datetime import date
from decimal import Decimal

from costguard_reports import baseline, models
from costguard_reports.backtest import MIN_MONTHS, backtest
from costguard_reports.forecast import forecast


def test_every_model_follows_a_flat_series():
    flat = [100.0] * 12
    for name, model in models.MODELS.items():
        fit = model(flat, 3)
        if fit is None:
            assert name == "seasonal_naive"  # needs two years
            continue
        assert [round(p, 6) for p in fit.points] == [100.0, 100.0, 100.0], name


def test_seasonal_naive_repeats_last_year():
    year = [100.0, 100.0, 300.0] + [100.0] * 9
    fit = models.seasonal_naive(year + year, 4)
    assert fit.points == [100.0, 100.0, 300.0, 100.0]
    assert models.seasonal_naive(year, 1) is None


def test_the_damped_trend_fades_and_theta_grows_by_half_the_slope():
    line = [100.0 + 10 * i for i in range(12)]
    damped = models.ets_damped(line, 12)
    steps = [b - a for a, b in zip(damped.points, damped.points[1:], strict=False)]
    assert all(later <= earlier + 1e-9 for earlier, later in zip(steps, steps[1:], strict=False))
    theta = models.theta(line, 2)
    assert 200 < theta.points[0] < 230 and theta.points[1] > theta.points[0]


def test_the_backtest_picks_the_model_that_fits():
    line = [100.0 + 10 * i for i in range(12)]
    scores = backtest(line, 3)
    assert scores[0].model == "holt" and scores[0].wape == 0.0
    assert {s.model for s in scores} >= {"naive", "holt", "ets_damped", "theta"}
    assert all(s.forecasts > 0 for s in scores)
    # a series that repeats every year: the seasonal model wins
    year = [100.0, 120.0, 400.0, 90.0, 110.0, 100.0, 105.0, 95.0, 130.0, 100.0, 115.0, 98.0]
    assert backtest(year * 3, 3)[0].model == "seasonal_naive"
    assert backtest(line[:MIN_MONTHS - 1]) == []


def test_mase_below_one_beats_repeating_last_month():
    line = [100.0 + 10 * i for i in range(12)]
    by_model = {s.model: s for s in backtest(line, 1)}
    assert by_model["holt"].mase < 1 <= by_model["naive"].mase


def test_the_forecast_names_its_champion_and_challenger():
    year = [100, 120, 400, 90, 110, 100, 105, 95, 130, 100, 115, 98]
    f = forecast([Decimal(v) for v in year * 3], horizon=3)
    assert f.method == "seasonal_naive"
    assert [p.value for p in f.points] == [Decimal("100.00"), Decimal("120.00"), Decimal("400.00")]
    assert f.champion.model == "seasonal_naive" and f.challenger is not None
    assert f.challenger.wape >= f.champion.wape
    # too short to backtest: Holt without scores
    short = forecast([Decimal(v) for v in [100, 110, 120, 130, 140]], horizon=1)
    assert short.method == "holt" and short.scores == [] and short.champion is None


def test_the_baseline_charges_yearly_subscriptions_in_their_renewal_month():
    subs = [
        baseline.Subscription("s1", "it", 3, Decimal("1000"), False, date(2027, 1, 15)),
        baseline.Subscription("s2", "it", 5, Decimal("3000"), True, date(2027, 2, 10)),
        baseline.Subscription("s3", "sales", 2, Decimal("500"), False, date(2027, 1, 1)),
    ]
    months = baseline.months_after(date(2026, 11, 1), 4)
    assert months == [date(2026, 12, 1), date(2027, 1, 1), date(2027, 2, 1), date(2027, 3, 1)]
    by = baseline.baseline(subs, months)
    assert by["it"] == [Decimal(3000), Decimal(3000), Decimal(18000), Decimal(3000)]
    assert by["sales"] == [Decimal(1000)] * 4
    assert by[""] == [Decimal(4000), Decimal(4000), Decimal(19000), Decimal(4000)]
    assert baseline.baseline([], months)[""] == [Decimal(0)] * 4


def test_a_subscription_reads_the_core_api():
    s = baseline.Subscription.of({"id": "x", "departmentId": "d", "seats": 2, "pricePerSeat": 99.5,
                                  "billingPeriod": "year", "renewalDate": "2027-03-01"})
    assert s.yearly and s.price_per_seat == Decimal("99.5") and s.renewal == date(2027, 3, 1)
