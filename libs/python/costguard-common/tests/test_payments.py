from decimal import Decimal

import pytest

from costguard_common import payments

AGENT = payments.Intermediary("ООО «Мост Платежей»", "7700000009")


def split(payee, inn, purpose, amount="5350.00", **kw):
    return payments.split(payee, inn, purpose, Decimal(amount), [AGENT], **kw)


def test_a_direct_payment_is_not_split():
    [part] = split("ООО «Тучка Софт»", "7700000002", "Оплата по счету 77 за ТучкаCRM")
    assert (part.vendor, part.inn, part.amount, part.role, part.note) == (
        "ООО «Тучка Софт»", "7700000002", Decimal("5350.00"), "payment", None)


def test_a_stated_commission_is_split_off():
    service, commission = split("ООО Мост Платежей", "7700000009",
                                "Оплата подписки Notion по договору 15-А, в т.ч. вознаграждение агента 350,00 руб.")
    assert (service.vendor, service.amount, service.role, service.intermediary) == (
        "Notion", Decimal("5000.00"), "service", "ООО Мост Платежей")
    assert (commission.vendor, commission.inn, commission.amount, commission.role) == (
        "ООО Мост Платежей", "7700000009", Decimal("350.00"), "commission")


@pytest.mark.parametrize("purpose,commission", [
    ("Оплата ChatGPT Plus. Комиссия агента: 1 200 руб.", Decimal("1200")),
    ("Оплата Figma, агентское вознаграждение 99.90 руб, НДС не облагается", Decimal("99.90")),
    ("Оплата Miro в т.ч. комиссия 50 ₽", Decimal("50")),
])
def test_commission_wordings(purpose, commission):
    assert payments.commission_in(purpose) == commission


def test_vat_is_not_a_commission():
    assert payments.commission_in("Оплата Slack, в т.ч. НДС 20% 1000,00 руб.") is None


def test_without_a_stated_commission_the_whole_amount_is_the_service():
    [part] = split("ООО Мост Платежей", "7700000009", "Оплата подписки Notion Team")
    assert (part.vendor, part.amount, part.note) == ("Notion", Decimal("5350.00"), "commission_unknown")


def test_an_intermediary_without_a_visible_service_stays_as_it_is():
    [part] = split("ООО Мост Платежей", "7700000009", "Оплата по договору 15-А")
    assert (part.vendor, part.role, part.note) == ("ООО Мост Платежей", "payment", "service_unknown")


def test_an_agency_contract_in_the_purpose_marks_an_unlisted_intermediary():
    service, commission = payments.split(
        "ООО Ромашка Пэй", "7700000010", "Оплата Zoom по агентскому договору, комиссия 100 руб.", Decimal("1100"))
    assert (service.vendor, commission.vendor) == ("Zoom", "ООО Ромашка Пэй")


def test_a_named_service_is_not_split_off_an_ordinary_supplier():
    [part] = payments.split("ООО Тучка Софт", "7700000002", "Интеграция со Slack, комиссия 100 руб.",
                            Decimal("1100"))
    assert part.role == "payment"


def test_services_added_by_the_company_and_whole_words():
    [part] = split("ООО Мост Платежей", "7700000009", "Оплата сервиса ТучкаCRM",
                   services={"тучкаcrm": "ООО «Тучка Софт»"})
    assert part.vendor == "ООО «Тучка Софт»"
    assert payments.service_in("Оплата за slackline-тренировку") is None
    assert payments.service_in("Оплата AWS и Slack") == "Amazon Web Services"


def test_a_commission_not_below_the_amount_is_ignored():
    [part] = split("ООО Мост Платежей", "7700000009", "Оплата Notion, комиссия 6000 руб.")
    assert part.note == "commission_unknown"


def test_intermediaries_from_configuration():
    assert payments.intermediaries_of("7700000009; ООО Агент Плюс,") == [
        payments.Intermediary("7700000009", "7700000009"), payments.Intermediary("ООО Агент Плюс")]
